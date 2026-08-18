"""The single writer of an item's whereabouts.

`items.current_tote_id`, `items.status`, `items.out_reason`, `items.out_since` and
`items.expected_back` are **derived state**. This module is the only place that sets them, and
every change also appends a row to `movements`. Nothing else — not a router, not a bulk helper —
may assign those columns directly.

The reason for the discipline is the reason the ledger exists: the app has to answer "where was
this last year", and that is only answerable if every change left a trace. A convenience
`item.current_tote_id = x` somewhere else would be a silent hole in the history, and a hole is
invisible until the day you need the answer.

Invariant, enforced here and asserted in tests:

    current_tote_id is NOT NULL  <=>  status == "stored"

Anything else is a contradiction the UI would have to invent a story for — an item that is both
in bin A14 and lent to Dave.
"""

import datetime
import uuid

from fastapi import HTTPException
from fastapi import status as http_status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.item import Item
from app.models.movement import Movement
from app.models.tote import Tote

# Which reasons put an item INTO a tote, and which take it out. A reason not in either set is a
# programming error rather than a user error, so it raises rather than defaulting — defaulting
# would silently produce the contradictory state the invariant exists to prevent.
_INBOUND = {"initial", "moved", "repacked", "returned", "corrected"}
_OUTBOUND = {"unpacked", "outgrown", "loaned", "disposed"}

# The third kind, and the only one that puts an item nowhere: it entered the CATALOGUE without
# entering a bin. Reviewing a batch and deciding where things go afterwards is a real workflow —
# the alternative is choosing a destination for every draft at the one moment you are least sure,
# with the bin closed and the object already back in it.
#
# Deliberately its own set rather than an outbound reason with a friendly label. "It was never
# filed" and "it came out of a bin" are different facts about an object's history, and the ledger
# is the one place that difference is recoverable a year later.
_UNFILED = {"catalogued"}


def inbound_reason_for(status: str, out_reason: str | None = None) -> str:
    """Which inbound reason describes this item coming back.

    One classifier, because there are four callers and they must agree: the bin's Put back button,
    the item sheet's move, the bulk move, and the person screen's return. Three of them used to
    read `"moved" if stored else "repacked"`, which quietly recorded a loan ending as an ordinary
    reshelving — and the `returned` row is the only record that a loan ever ended, which is the
    question the people table exists to answer.

    `stored` means it is already in a bin and this is a relocation; `loaned` means a person had
    it; `unfiled` means it was never in a bin at all, so it is not coming *back* to anything and
    filing it is an ordinary `moved` — the `_UNFILED` docstring has always said so. Anything else
    came out of a bin and is going back.
    """
    if status == "stored":
        return "moved"
    if status == "loaned":
        return "returned"
    if out_reason == "unfiled":
        return "moved"
    return "repacked"


# The status an outbound reason produces. `disposed` is terminal; the rest are recoverable.
_OUT_STATUS = {
    "unpacked": "out",
    "outgrown": "out",
    "loaned": "loaned",
    "disposed": "disposed",
}

# The `out_reason` recorded alongside. Deliberately narrower than MOVEMENT_REASONS: "why it left"
# and "what happened" are different questions, and conflating them is how "out for the holidays"
# and "outgrown, waiting to be handed down" become indistinguishable.
_OUT_REASON = {
    "unpacked": "unpacked",
    "outgrown": "outgrown",
    "loaned": "loaned",
    "disposed": "other",
    # Never in a bin, as opposed to taken out of one. The invariant below forces SOME non-stored
    # status on an item with no tote, and "unfiled" is the honest one — anything else would claim
    # a history the object does not have.
    "catalogued": "unfiled",
}


async def _owned_tote(db: AsyncSession, user_id: uuid.UUID, tote_id: uuid.UUID) -> Tote:
    tote = (
        await db.execute(select(Tote).where(Tote.id == tote_id, Tote.user_id == user_id))
    ).scalar_one_or_none()
    if tote is None:
        # 404 rather than 403 for someone else's tote: an authenticated user should not be able
        # to probe which ids exist.
        raise HTTPException(http_status.HTTP_404_NOT_FOUND, "Tote not found")
    return tote


async def record_move(
    db: AsyncSession,
    *,
    item: Item,
    reason: str,
    to_tote_id: uuid.UUID | None = None,
    person_id: uuid.UUID | None = None,
    note: str | None = None,
    expected_back: datetime.date | None = None,
    moved_at: datetime.datetime | None = None,
    quantity: int | None = None,
) -> Movement:
    """Append a ledger row and bring the item's derived state in line with it.

    Does NOT commit — the caller owns the transaction, so a bulk unpack of forty items is one
    atomic operation rather than forty chances to half-succeed.
    """
    if reason in _INBOUND:
        if to_tote_id is None:
            raise HTTPException(
                http_status.HTTP_422_UNPROCESSABLE_ENTITY,
                f"reason '{reason}' puts an item into a tote, so to_tote_id is required",
            )
        await _owned_tote(db, item.user_id, to_tote_id)
        new_tote_id: uuid.UUID | None = to_tote_id
        new_status = "stored"
        new_out_reason = None
        new_out_since = None
        new_expected_back = None
    elif reason in _UNFILED:
        if to_tote_id is not None:
            raise HTTPException(
                http_status.HTTP_422_UNPROCESSABLE_ENTITY,
                f"reason '{reason}' files an item nowhere, so to_tote_id must be null",
            )
        new_tote_id = None
        # `out`, because the invariant is `current_tote_id IS NOT NULL <=> status == 'stored'`
        # and this item is in no tote. It reads correctly everywhere that already handles a
        # tote-less item — search says "Not in a tote", bin contents exclude it — and filing it
        # later is an ordinary inbound `moved`.
        new_status = "out"
        new_out_reason = _OUT_REASON[reason]
        new_out_since = moved_at or datetime.datetime.now(datetime.UTC)
        new_expected_back = None
    elif reason in _OUTBOUND:
        if to_tote_id is not None:
            raise HTTPException(
                http_status.HTTP_422_UNPROCESSABLE_ENTITY,
                f"reason '{reason}' takes an item out of a tote, so to_tote_id must be null",
            )
        new_tote_id = None
        new_status = _OUT_STATUS[reason]
        new_out_reason = _OUT_REASON[reason]
        new_out_since = moved_at or datetime.datetime.now(datetime.UTC)
        new_expected_back = expected_back
    else:
        raise ValueError(f"unknown movement reason: {reason!r}")

    movement = Movement(
        item_id=item.id,
        from_tote_id=item.current_tote_id,
        to_tote_id=new_tote_id,
        quantity=quantity if quantity is not None else item.quantity,
        reason=reason,
        person_id=person_id,
        note=note,
        # A caller may backdate: you catalogue the Christmas unpack in January, and the ledger
        # should say when it happened, not when you got round to recording it.
        moved_at=moved_at or datetime.datetime.now(datetime.UTC),
    )
    db.add(movement)

    item.current_tote_id = new_tote_id
    # A bag is a grouping INSIDE a tote, so leaving the tote leaves the bag. Cleared here, in
    # the single writer of derived state, rather than by each caller — a stale container_id on an
    # item that is out would make a bin's grouping claim something the bin does not contain.
    # Entering a tote clears it too: the destination's bags are not the source's.
    item.container_id = None
    item.status = new_status
    item.out_reason = new_out_reason
    item.out_since = new_out_since
    item.expected_back = new_expected_back
    return movement


async def unpack_tote(
    db: AsyncSession,
    *,
    user_id: uuid.UUID,
    tote_id: uuid.UUID,
    item_ids: list[uuid.UUID] | None = None,
    note: str | None = None,
) -> list[Movement]:
    """Take everything (or a selection) out of a tote in one operation.

    This exists because it is what the holidays actually look like. Modelling it as fifty
    individual edits would mean nobody does it, and a catalog nobody updates is worse than no
    catalog — it is a catalog you trust and shouldn't.
    """
    await _owned_tote(db, user_id, tote_id)
    query = select(Item).where(Item.user_id == user_id, Item.current_tote_id == tote_id)
    if item_ids is not None:
        query = query.where(Item.id.in_(item_ids))
    items = (await db.execute(query)).scalars().all()
    return [await record_move(db, item=i, reason="unpacked", note=note) for i in items]


async def repack_tote(
    db: AsyncSession,
    *,
    user_id: uuid.UUID,
    tote_id: uuid.UUID,
    item_ids: list[uuid.UUID] | None = None,
    note: str | None = None,
) -> list[Movement]:
    """Put items back into a tote.

    Without an explicit selection this returns the items whose LAST movement left this tote —
    "put back what came out of here", which is the actual January operation. It deliberately
    does not sweep up every loose item in the house, which is what a naive "all items with no
    tote" query would do.
    """
    await _owned_tote(db, user_id, tote_id)

    if item_ids is not None:
        query = select(Item).where(Item.user_id == user_id, Item.id.in_(item_ids))
        items = (await db.execute(query)).scalars().all()
    else:
        latest = (
            select(Movement.item_id, Movement.from_tote_id)
            .join(Item, Item.id == Movement.item_id)
            .where(Item.user_id == user_id, Item.current_tote_id.is_(None))
            .order_by(Movement.item_id, Movement.moved_at.desc(), Movement.created_at.desc())
            .distinct(Movement.item_id)
            .subquery()
        )
        ids = (
            (await db.execute(select(latest.c.item_id).where(latest.c.from_tote_id == tote_id)))
            .scalars()
            .all()
        )
        items = (
            (await db.execute(select(Item).where(Item.id.in_(ids)))).scalars().all() if ids else []
        )

    return [
        await record_move(db, item=i, reason="repacked", to_tote_id=tote_id, note=note)
        for i in items
    ]
