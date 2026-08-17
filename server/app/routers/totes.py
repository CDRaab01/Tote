"""Totes: the bins themselves, plus the bulk unpack/repack that the holidays require."""

import datetime
import uuid
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query, status
from fastapi.responses import Response
from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models.category import Category
from app.models.item import Item
from app.models.location import Location
from app.models.movement import Movement
from app.models.tote import Tote
from app.routers.containers import containers_for
from app.schemas.catalog import (
    BulkMoveIn,
    MovementOut,
    NfcResolveOut,
    NfcWriteIn,
    ToteDetail,
    ToteIn,
    ToteOut,
    TotePatch,
)
from app.security import CurrentUser
from app.services.card import render_card
from app.services.catalog import (
    item_query,
    items_for,
    location_names,
    out_counts,
    to_tote_out,
    tote_counts,
)
from app.services.movement import repack_tote, unpack_tote

router = APIRouter(prefix="/totes", tags=["totes"])

Db = Annotated[AsyncSession, Depends(get_db)]

DUPLICATE_CODE = "A tote with that code already exists (codes are case-insensitive)"


async def _owned_tote(db: AsyncSession, user_id: uuid.UUID, tote_id: uuid.UUID) -> Tote:
    tote = (
        await db.execute(select(Tote).where(Tote.id == tote_id, Tote.user_id == user_id))
    ).scalar_one_or_none()
    if tote is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Tote not found")
    return tote


async def _check_refs(db: AsyncSession, user_id: uuid.UUID, updates: dict) -> None:
    for field, model in (("category_id", Category), ("location_id", Location)):
        ref = updates.get(field)
        if ref:
            found = (
                await db.execute(select(model).where(model.id == ref, model.user_id == user_id))
            ).scalar_one_or_none()
            if found is None:
                raise HTTPException(status.HTTP_404_NOT_FOUND, f"{model.__name__} not found")


@router.get("", response_model=list[ToteOut])
async def list_totes(
    user: CurrentUser,
    db: Db,
    location_id: uuid.UUID | None = None,
    category_id: uuid.UUID | None = None,
    include_archived: bool = Query(default=False),
):
    query = select(Tote).where(Tote.user_id == user.id)
    if location_id:
        query = query.where(Tote.location_id == location_id)
    if category_id:
        query = query.where(Tote.category_id == category_id)
    if not include_archived:
        query = query.where(Tote.archived.is_(False))
    rows = (await db.execute(query.order_by(Tote.code))).scalars().all()

    # Counts fetched once for the whole list rather than per row: this endpoint backs the
    # browse-by-location screen, and a per-tote count query would be a clean N+1.
    counts = await tote_counts(db, user.id)
    outs = await out_counts(db, user.id)
    locations = await location_names(db, user.id)
    return [await to_tote_out(db, t, counts, outs, locations) for t in rows]


@router.post("", response_model=ToteOut, status_code=status.HTTP_201_CREATED)
async def create_tote(body: ToteIn, user: CurrentUser, db: Db):
    await _check_refs(db, user.id, body.model_dump())
    tote = Tote(user_id=user.id, **body.model_dump())
    db.add(tote)
    try:
        await db.commit()
    except IntegrityError:
        # The uniqueness is a functional index on lower(code) in migration 0001, so this is the
        # database refusing two bins that would look identically labelled on their cards.
        await db.rollback()
        raise HTTPException(status.HTTP_409_CONFLICT, DUPLICATE_CODE)
    await db.refresh(tote)
    return await to_tote_out(db, tote)


@router.get("/{tote_id}", response_model=ToteDetail)
async def get_tote(tote_id: uuid.UUID, user: CurrentUser, db: Db):
    tote = await _owned_tote(db, user.id, tote_id)
    base = await to_tote_out(db, tote)
    detail = ToteDetail(**base.model_dump())

    # The bags in this bin. An empty list is the ordinary answer — most bins are not subdivided.
    detail.containers = await containers_for(db, user.id, tote_id)

    detail.items = await items_for(
        db, item_query(user.id).where(Item.current_tote_id == tote_id).order_by(Item.name)
    )

    # Items whose LAST movement left this tote and have not returned. Shown rather than hidden:
    # the gap between "what should be in here" and "what is in here" is the single most common
    # reason to stop trusting a catalog.
    latest = (
        select(Movement.item_id, Movement.from_tote_id)
        .join(Item, Item.id == Movement.item_id)
        .where(
            Item.user_id == user.id,
            Item.current_tote_id.is_(None),
            Item.status != "disposed",
        )
        .order_by(Movement.item_id, Movement.moved_at.desc(), Movement.created_at.desc())
        .distinct(Movement.item_id)
        .subquery()
    )
    out_ids = (
        (await db.execute(select(latest.c.item_id).where(latest.c.from_tote_id == tote_id)))
        .scalars()
        .all()
    )
    if out_ids:
        detail.items_out = await items_for(
            db, item_query(user.id).where(Item.id.in_(out_ids)).order_by(Item.name)
        )
    return detail


@router.patch("/{tote_id}", response_model=ToteOut)
async def patch_tote(tote_id: uuid.UUID, body: TotePatch, user: CurrentUser, db: Db):
    tote = await _owned_tote(db, user.id, tote_id)
    updates = body.model_dump(exclude_unset=True)
    await _check_refs(db, user.id, updates)
    if updates.get("code"):
        updates["code"] = updates["code"].strip()
    for k, v in updates.items():
        setattr(tote, k, v)
    try:
        await db.commit()
    except IntegrityError:
        await db.rollback()
        raise HTTPException(status.HTTP_409_CONFLICT, DUPLICATE_CODE)
    await db.refresh(tote)
    return await to_tote_out(db, tote)


@router.delete("/{tote_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_tote(tote_id: uuid.UUID, user: CurrentUser, db: Db):
    """Deleting a tote leaves its items in the catalog, unfiled (ON DELETE SET NULL).

    Throwing a bin away must not erase the record of what was in it. Archiving (PATCH archived)
    is usually what someone actually wants; delete is for a bin created by mistake.
    """
    tote = await _owned_tote(db, user.id, tote_id)
    await db.delete(tote)
    await db.commit()


@router.post("/{tote_id}/unpack", response_model=list[MovementOut])
async def unpack(tote_id: uuid.UUID, body: BulkMoveIn, user: CurrentUser, db: Db):
    """Take everything (or a selection) out, in one transaction and one ledger row per item."""
    moves = await unpack_tote(
        db, user_id=user.id, tote_id=tote_id, item_ids=body.item_ids, note=body.note
    )
    await db.commit()
    return [MovementOut.model_validate(m) for m in moves]


@router.post("/{tote_id}/repack", response_model=list[MovementOut])
async def repack(tote_id: uuid.UUID, body: BulkMoveIn, user: CurrentUser, db: Db):
    """Put back what came out of here — not every loose item in the house."""
    moves = await repack_tote(
        db, user_id=user.id, tote_id=tote_id, item_ids=body.item_ids, note=body.note
    )
    await db.commit()
    return [MovementOut.model_validate(m) for m in moves]


@router.get("/{tote_id}/card", include_in_schema=False)
async def tote_card(tote_id: uuid.UUID, user: CurrentUser, db: Db):
    """The printable index card, as a PDF.

    Rendered server-side so there is exactly one layout: the card is a physical object, and two
    renderers would eventually disagree in a way only discoverable in an attic.
    """
    tote = await _owned_tote(db, user.id, tote_id)

    location = None
    if tote.location_id:
        location = (
            await db.execute(select(Location.name).where(Location.id == tote.location_id))
        ).scalar_one_or_none()
    category = None
    if tote.category_id:
        category = (
            await db.execute(select(Category.name).where(Category.id == tote.category_id))
        ).scalar_one_or_none()

    counts = await tote_counts(db, user.id)
    pdf = render_card(
        tote,
        location=location,
        category=category,
        item_count=counts.get(tote.id, 0),
    )

    tote.card_printed_at = datetime.datetime.now(datetime.UTC)
    await db.commit()

    return Response(
        content=pdf,
        media_type="application/pdf",
        headers={"Content-Disposition": f'inline; filename="tote-{tote.code}.pdf"'},
    )


@router.post("/{tote_id}/nfc", response_model=ToteOut)
async def record_tag_write(tote_id: uuid.UUID, body: NfcWriteIn, user: CurrentUser, db: Db):
    """Record that a physical tag now carries this tote.

    Called AFTER the client has written the tag, never before: if the write failed, the database
    must not claim a tag exists that does not. The uid is unique per user, so re-using one tag
    for a second bin is a 409 rather than a silent reassignment that would leave two bins
    pointing at one tag.
    """
    tote = await _owned_tote(db, user.id, tote_id)
    tote.nfc_tag_uid = body.tag_uid
    tote.nfc_written_at = datetime.datetime.now(datetime.UTC)
    try:
        await db.commit()
    except IntegrityError:
        await db.rollback()
        raise HTTPException(
            status.HTTP_409_CONFLICT,
            "That tag is already on another tote. Erase it or use a fresh one.",
        )
    await db.refresh(tote)
    return await to_tote_out(db, tote)


@router.get("/resolve/{code}", response_model=NfcResolveOut)
async def resolve_code(
    code: str, user: CurrentUser, db: Db, tag_uid: str | None = Query(default=None)
):
    """Resolve a tapped tag (or scanned QR) to one of this user's totes.

    Authenticated, unlike the public landing page: this returns an id the app will immediately
    use to fetch contents.

    A `tag_uid` that does not match the stored one is reported but does NOT block. Someone
    standing in an attic holding a bin needs the answer; a hard refusal because a tag was
    rewritten would strand them. Saying "this is not the tag we recorded for A14" is the useful
    behaviour, and the app surfaces it.
    """
    tote = (
        await db.execute(
            select(Tote).where(
                Tote.user_id == user.id, func.lower(Tote.code) == code.strip().lower()
            )
        )
    ).scalar_one_or_none()
    if tote is None:
        return NfcResolveOut(tote_id=None, code=code)
    mismatch = bool(tag_uid and tote.nfc_tag_uid and tag_uid != tote.nfc_tag_uid)
    return NfcResolveOut(tote_id=tote.id, code=tote.code, tag_mismatch=mismatch)
