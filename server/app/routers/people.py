"""People, their sizes, and the two questions those tables exist to answer.

    "what fits Emma right now"   -> GET /people/{id}/fits
    "who has the drill"          -> `on_loan_count` on PersonOut, and /people/{id}/on-loan

Household members and lendees deliberately share one table. Both answer "where did this go and
whose is it", and splitting them would mean deciding, at the moment you lend a nephew a coat,
whether he is family — which is not a question a storage app should ask.
"""

import uuid
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models.item import Item
from app.models.movement import Movement
from app.models.person import Person, PersonSize
from app.models.user import UserSettings
from app.schemas.catalog import ItemOut, MovementOut
from app.schemas.people import (
    FitsOut,
    OutgrownIn,
    PersonIn,
    PersonOut,
    PersonPatch,
    PersonSizeIn,
    PersonSizeOut,
)
from app.security import CurrentUser
from app.services import ntfy
from app.services.catalog import item_query, items_for, local_today
from app.services.fits import current_sizes, fits_query
from app.services.movement import record_move
from app.services.ntfy import overdue_message
from app.sizing import parse_size

router = APIRouter(tags=["people"])

Db = Annotated[AsyncSession, Depends(get_db)]


async def _owned(db: AsyncSession, user_id: uuid.UUID, person_id: uuid.UUID) -> Person:
    person = (
        await db.execute(select(Person).where(Person.id == person_id, Person.user_id == user_id))
    ).scalar_one_or_none()
    if person is None:
        # 404 rather than 403, so an authenticated user cannot probe which ids exist.
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Person not found")
    return person


async def _to_person_out(db: AsyncSession, person: Person) -> PersonOut:
    out = PersonOut.model_validate(person)
    out.current_sizes = [
        PersonSizeOut.model_validate(s) for s in (await current_sizes(db, person.id)).values()
    ]
    out.on_loan_count = await _on_loan_count(db, person)
    return out


async def _on_loan_count(db: AsyncSession, person: Person) -> int:
    """How many items this person currently has.

    Derived from the LEDGER rather than a column on `items`: the item knows it is loaned, but
    only the movement row knows to whom. Taking the newest `loaned` movement per still-loaned
    item is what makes "who has the drill" answerable at all.
    """
    newest = (
        select(Movement.item_id, func.max(Movement.moved_at).label("at"))
        .where(Movement.reason == "loaned")
        .group_by(Movement.item_id)
        .subquery()
    )
    return (
        await db.execute(
            select(func.count())
            .select_from(Item)
            .join(newest, newest.c.item_id == Item.id)
            .join(
                Movement,
                (Movement.item_id == Item.id)
                & (Movement.moved_at == newest.c.at)
                & (Movement.reason == "loaned"),
            )
            .where(
                Item.user_id == person.user_id,
                Item.status == "loaned",
                Movement.person_id == person.id,
            )
        )
    ).scalar_one()


# ── People ─────────────────────────────────────────────────────────────────────────────────


@router.get("/people", response_model=list[PersonOut])
async def list_people(user: CurrentUser, db: Db):
    rows = (
        (await db.execute(select(Person).where(Person.user_id == user.id).order_by(Person.name)))
        .scalars()
        .all()
    )
    return [await _to_person_out(db, p) for p in rows]


@router.post("/people", response_model=PersonOut, status_code=status.HTTP_201_CREATED)
async def create_person(body: PersonIn, user: CurrentUser, db: Db):
    person = Person(user_id=user.id, **body.model_dump())
    db.add(person)
    await db.commit()
    await db.refresh(person)
    return await _to_person_out(db, person)


@router.get("/people/{person_id}", response_model=PersonOut)
async def get_person(person_id: uuid.UUID, user: CurrentUser, db: Db):
    return await _to_person_out(db, await _owned(db, user.id, person_id))


@router.patch("/people/{person_id}", response_model=PersonOut)
async def patch_person(person_id: uuid.UUID, body: PersonPatch, user: CurrentUser, db: Db):
    person = await _owned(db, user.id, person_id)
    for k, v in body.model_dump(exclude_unset=True).items():
        setattr(person, k, v)
    await db.commit()
    return await _to_person_out(db, person)


@router.delete("/people/{person_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_person(person_id: uuid.UUID, user: CurrentUser, db: Db):
    """Remove a person and their size history.

    The `movements` rows they appear on are NOT deleted — `person_id` is nulled by the FK and the
    ledger keeps the event. A loan that happened still happened, and erasing it to tidy up a
    contact list would put a hole in the one record this app promises never to have holes in.
    """
    person = await _owned(db, user.id, person_id)
    await db.delete(person)
    await db.commit()


# ── Sizes ──────────────────────────────────────────────────────────────────────────────────


@router.get("/people/{person_id}/sizes", response_model=list[PersonSizeOut])
async def list_sizes(person_id: uuid.UUID, user: CurrentUser, db: Db):
    """The whole history, newest first — not just what is current.

    Deliberately the full list: "what size was she last winter" is the question that tells you
    which bin to open this winter, and it is unanswerable from a current value.
    """
    await _owned(db, user.id, person_id)
    rows = (
        (
            await db.execute(
                select(PersonSize)
                .where(PersonSize.person_id == person_id)
                .order_by(PersonSize.effective_from.desc(), PersonSize.garment_type)
            )
        )
        .scalars()
        .all()
    )
    return [PersonSizeOut.model_validate(r) for r in rows]


@router.post(
    "/people/{person_id}/sizes",
    response_model=PersonSizeOut,
    status_code=status.HTTP_201_CREATED,
)
async def add_size(person_id: uuid.UUID, body: PersonSizeIn, user: CurrentUser, db: Db):
    """Record a size. **Appends** — it never overwrites the previous one.

    The index is derived here from `size_raw`, exactly as it is for an item, so a person's size
    and a garment's size are placed on the same ladder by the same code. An unparseable reading
    is stored with a null index and still counts as a record of what was said.
    """
    await _owned(db, user.id, person_id)
    reading = parse_size(body.size_raw)
    row = PersonSize(
        person_id=person_id,
        garment_type=body.garment_type,
        size_raw=body.size_raw,
        size_system=reading.system if reading else None,
        size_ordinal=reading.ordinal if reading else None,
        effective_from=body.effective_from or local_today(),
        notes=body.notes,
    )
    db.add(row)
    await db.commit()
    await db.refresh(row)
    return PersonSizeOut.model_validate(row)


@router.delete("/people/{person_id}/sizes/{size_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_size(person_id: uuid.UUID, size_id: uuid.UUID, user: CurrentUser, db: Db):
    await _owned(db, user.id, person_id)
    row = (
        await db.execute(
            select(PersonSize).where(PersonSize.id == size_id, PersonSize.person_id == person_id)
        )
    ).scalar_one_or_none()
    if row is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Size not found")
    await db.delete(row)
    await db.commit()


# ── The two questions ──────────────────────────────────────────────────────────────────────


@router.get("/people/{person_id}/fits", response_model=FitsOut)
async def fits(
    person_id: uuid.UUID,
    user: CurrentUser,
    db: Db,
    garment_type: str | None = None,
    tolerance: float = Query(default=1.0, ge=0.0, le=4.0),
):
    """What do we already own that fits this person right now.

    **`answered=false` is not an empty result.** It means the person has no indexed size to match
    on, and a client must say so rather than rendering "nothing fits" — one of those sentences is
    a reason to stop looking and the other is a reason to go and read a tag.
    """
    person = await _owned(db, user.id, person_id)
    sizes = await current_sizes(db, person.id)

    if not sizes:
        return FitsOut(
            answered=False,
            reason="no_sizes_recorded",
            garment_type=garment_type,
            tolerance=tolerance,
        )

    query = fits_query(user.id, sizes, tolerance=tolerance, garment_type=garment_type)
    if query is None:
        return FitsOut(
            answered=False,
            # Distinguished from having no sizes at all: a size was recorded, it just could not
            # be placed on the ladder, and the fix is a different one (re-read the tag, not add
            # a size).
            reason="no_indexed_size",
            garment_type=garment_type,
            tolerance=tolerance,
            matched_sizes=[PersonSizeOut.model_validate(s) for s in sizes.values()],
        )

    matched = [
        s
        for gtype, s in sizes.items()
        if s.size_ordinal is not None and (garment_type is None or gtype == garment_type)
    ]
    return FitsOut(
        answered=True,
        garment_type=garment_type,
        tolerance=tolerance,
        matched_sizes=[PersonSizeOut.model_validate(s) for s in matched],
        items=await items_for(db, query),
    )


@router.get("/people/{person_id}/on-loan", response_model=list[ItemOut])
async def on_loan(person_id: uuid.UUID, user: CurrentUser, db: Db):
    """What this person currently has of yours."""
    await _owned(db, user.id, person_id)
    newest = (
        select(Movement.item_id, func.max(Movement.moved_at).label("at"))
        .where(Movement.reason == "loaned")
        .group_by(Movement.item_id)
        .subquery()
    )
    query = (
        item_query(user.id)
        .join(newest, newest.c.item_id == Item.id)
        .join(
            Movement,
            (Movement.item_id == Item.id)
            & (Movement.moved_at == newest.c.at)
            & (Movement.reason == "loaned"),
        )
        .where(Item.status == "loaned", Movement.person_id == person_id)
        .order_by(Item.expected_back.nulls_last(), Item.name)
    )
    return await items_for(db, query)


@router.post("/people/{person_id}/outgrown", response_model=list[MovementOut])
async def outgrown(person_id: uuid.UUID, body: OutgrownIn, user: CurrentUser, db: Db):
    """Mark a run of items outgrown and file them into a tote, in one action.

    One transaction for the whole run, so a selection of forty either all move or none do — a
    half-applied outgrown run would leave the catalog claiming some of a size is in the attic and
    the rest is still being worn.

    Note the reason is `outgrown`, not `moved`: six months from now the difference between "we
    packed these away" and "she grew out of these" is the difference between a bin to re-open and
    a bin to pass on.
    """
    person = await _owned(db, user.id, person_id)
    rows = (
        (await db.execute(select(Item).where(Item.user_id == user.id, Item.id.in_(body.item_ids))))
        .scalars()
        .all()
    )
    found = {r.id for r in rows}
    missing = [str(i) for i in body.item_ids if i not in found]
    if missing:
        raise HTTPException(status.HTTP_404_NOT_FOUND, f"Items not found: {', '.join(missing)}")

    movements = []
    for item in rows:
        # Out of the wearing pile...
        movements.append(
            await record_move(db, item=item, reason="outgrown", person_id=person.id, note=body.note)
        )
        # ...and straight into the bin, so the run never rests in the contradictory state of
        # being outgrown and nowhere.
        movements.append(
            await record_move(
                db,
                item=item,
                reason="moved",
                to_tote_id=body.tote_id,
                person_id=person.id,
                note=body.note,
            )
        )
    await db.commit()
    return [MovementOut.model_validate(m) for m in movements]


@router.post("/overdue/nudge", tags=["items"])
async def nudge_overdue(user: CurrentUser, db: Db):
    r"""Push a reminder about everything out past its expected return.

    Deliberately an ENDPOINT rather than a background timer inside the app. Scheduling on this
    host belongs to `C:\Scripts` + Task Scheduler — the same division of labour as the backups —
    so the service stays stateless and a nudge can also be triggered by hand from the app.

    Returns what it would have said even when nothing was sent, so a caller can tell "nothing was
    overdue" apart from "ntfy is not configured" apart from "ntfy is down". A notification
    channel that is quietly broken is indistinguishable from one with nothing to say, and that is
    the failure this endpoint's response shape exists to prevent.
    """
    items = await _overdue_items(db, user.id)
    if not items:
        return {"overdue": 0, "sent": False, "reason": "nothing_overdue"}

    title, message = overdue_message(items)
    if not ntfy.is_configured():
        return {
            "overdue": len(items),
            "sent": False,
            "reason": "ntfy_not_configured",
            "title": title,
        }

    # The per-user override lives on `user_settings`, NOT on `users` — reading it off the user
    # 500'd in production while every test passed, because the test environment has no ntfy
    # configured and so never reached this line at all. See the test that now does.
    override = (
        await db.execute(select(UserSettings.ntfy_topic).where(UserSettings.user_id == user.id))
    ).scalar_one_or_none()

    sent = await ntfy.send(
        title,
        message,
        topic=override or None,
        priority=4,
        tags=["hourglass_flowing_sand"],
    )
    return {
        "overdue": len(items),
        "sent": sent,
        "reason": None if sent else "ntfy_send_failed",
        "title": title,
    }


async def _overdue_items(db: AsyncSession, user_id: uuid.UUID) -> list[ItemOut]:
    query = (
        item_query(user_id)
        .where(
            Item.expected_back.is_not(None),
            Item.status.in_(("out", "loaned")),
            Item.expected_back < local_today(),
        )
        .order_by(Item.expected_back)
    )
    return await items_for(db, query)


@router.get("/overdue", response_model=list[ItemOut], tags=["items"])
async def overdue(user: CurrentUser, db: Db):
    """Everything out past its expected return.

    The date comparison uses the household's local today, not UTC — the container runs UTC and
    the house does not, so without that an item due today reads as overdue from 7pm local.
    """
    return await _overdue_items(db, user.id)
