"""Photo capture: scan a photo into a draft, review it, confirm or discard it.

Nothing here writes to the catalog. A scan produces a **draft**, invisible to search and to a
tote's contents; only `POST /drafts/{id}/confirm` files it, and that is what writes the `initial`
movement row. The house AI rule is that nothing model-generated enters the catalog without
explicit approval, and Tote has no exception to it.
"""

import uuid
from typing import Annotated

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile, status
from fastapi.responses import FileResponse
from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.database import get_db
from app.models.item import Item, ItemPhoto
from app.models.tote import Tote
from app.schemas.catalog import DraftConfirm, DraftOut, ItemOut
from app.security import CurrentUser
from app.services import photo_store
from app.services.apparel_write import apply_apparel
from app.services.catalog import item_query, to_item_out
from app.services.movement import record_move
from app.services.scan_pipeline import scan_photos

router = APIRouter(tags=["scan"])

Db = Annotated[AsyncSession, Depends(get_db)]

MAX_PHOTOS = 8


async def _owned_draft(db: AsyncSession, user_id: uuid.UUID, draft_id: uuid.UUID) -> Item:
    item = (
        await db.execute(
            select(Item).where(
                Item.id == draft_id, Item.user_id == user_id, Item.is_draft.is_(True)
            )
        )
    ).scalar_one_or_none()
    if item is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Draft not found")
    return item


async def _to_draft_out(db: AsyncSession, item: Item) -> DraftOut:
    count = (
        await db.execute(
            select(func.count()).select_from(ItemPhoto).where(ItemPhoto.item_id == item.id)
        )
    ).scalar_one()
    out = DraftOut.model_validate(item)
    out.photo_count = count
    return out


@router.post("/items/scan", response_model=DraftOut, status_code=status.HTTP_201_CREATED)
async def scan(
    user: CurrentUser,
    db: Db,
    photos: Annotated[list[UploadFile], File()],
    tote_id: Annotated[uuid.UUID | None, Form()] = None,
    capture_id: Annotated[uuid.UUID | None, Form()] = None,
):
    """One item, 1-8 photos, one draft.

    The photos are persisted before anything else runs. That order is the point: the item was in
    someone's hands in a garage and is back in a bin by the time any of this fails, so the
    photograph is the one artefact that cannot be recreated.

    **`capture_id` makes this safe to replay, and the client always sends one.** The endpoint
    runs for tens of seconds and commits before it answers, so a client that loses the
    connection genuinely cannot tell a lost request from a lost response — and the capture
    queue's stranded-row recovery re-sends. Without a key, that re-send filed the object again:
    one photograph became four drafts in production on 2026-08-16, and two drafts of one cap are
    indistinguishable from two real caps. A repeat now returns the draft the first attempt made.
    """
    if capture_id is not None:
        existing = (
            await db.execute(
                select(Item).where(Item.user_id == user.id, Item.capture_id == capture_id)
            )
        ).scalar_one_or_none()
        if existing is not None:
            # Deliberately returned whatever state it is in — including a confirmed item that is
            # no longer a draft. The alternative, 409, would push the client back into the retry
            # loop this key exists to end.
            return await _to_draft_out(db, existing)

    if not photos:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, "At least one photo is required")
    if len(photos) > MAX_PHOTOS:
        raise HTTPException(
            status.HTTP_422_UNPROCESSABLE_ENTITY, f"At most {MAX_PHOTOS} photos per item"
        )

    if tote_id is not None:
        found = (
            await db.execute(select(Tote).where(Tote.id == tote_id, Tote.user_id == user.id))
        ).scalar_one_or_none()
        if found is None:
            raise HTTPException(status.HTTP_404_NOT_FOUND, "Tote not found")

    payload: list[tuple[bytes, str]] = []
    for upload in photos:
        content_type = upload.content_type or ""
        if content_type not in photo_store.ALLOWED_CONTENT_TYPES:
            raise HTTPException(
                status.HTTP_422_UNPROCESSABLE_ENTITY,
                f"Unsupported image type {content_type!r}",
            )
        data = await upload.read()
        if not data:
            raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, "Empty photo")
        if len(data) > settings.photo_max_bytes:
            raise HTTPException(
                status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
                "Photo is too large — the client should downscale before upload.",
            )
        payload.append((data, content_type))

    item = await scan_photos(
        db, user_id=user.id, photos=payload, tote_id=tote_id, capture_id=capture_id
    )
    try:
        await db.commit()
    except IntegrityError:
        # Two attempts for the same capture raced: the check above ran before the first one
        # committed. The constraint is the backstop the check cannot be — a scan takes tens of
        # seconds, so the window is wide, and the loser must hand back the winner's draft rather
        # than a 409 the client would retry.
        await db.rollback()
        existing = (
            await db.execute(
                select(Item).where(Item.user_id == user.id, Item.capture_id == capture_id)
            )
        ).scalar_one_or_none()
        if existing is None:
            raise
        return await _to_draft_out(db, existing)
    await db.refresh(item)
    return await _to_draft_out(db, item)


@router.get("/drafts", response_model=list[DraftOut])
async def list_drafts(user: CurrentUser, db: Db):
    """The review stack, oldest first — the order they were shot in, which is the order the
    person remembers them in."""
    rows = (
        (
            await db.execute(
                select(Item)
                .where(Item.user_id == user.id, Item.is_draft.is_(True))
                .order_by(Item.created_at)
            )
        )
        .scalars()
        .all()
    )
    return [await _to_draft_out(db, i) for i in rows]


@router.post("/drafts/{draft_id}/confirm", response_model=ItemOut)
async def confirm(draft_id: uuid.UUID, body: DraftConfirm, user: CurrentUser, db: Db):
    """Accept a draft into the catalog.

    This is the only path from a photograph to a catalogued item, and it is a human action. The
    edits in the body win over whatever the model said — every field is overwritten, not merged,
    so a corrected name cannot be quietly reverted by a later re-read of the draft.
    """
    item = await _owned_draft(db, user.id, draft_id)

    item.name = body.name.strip()
    item.description = body.description
    item.notes = body.notes
    item.category_id = body.category_id
    item.quantity = body.quantity
    item.condition = body.condition
    item.is_draft = False
    item.draft_tote_id = None

    # Apparel is merged, not replaced: an omitted block leaves the label pass's reading intact.
    # Passed through the same helper the PATCH path uses, so `size_system`/`size_ordinal` are
    # re-derived from `size_raw` here too and a client still cannot store an index that
    # disagrees with the reading it indexes.
    if body.apparel is not None:
        await apply_apparel(db, item, body.apparel.model_dump(exclude_unset=True))

    # Filing is a movement like any other, so it leaves an `initial` ledger row. An item that
    # appeared in a bin with no history would be the first hole in the ledger.
    await record_move(db, item=item, reason="initial", to_tote_id=body.tote_id)
    await db.commit()

    row = (await db.execute(item_query(user.id).where(Item.id == item.id))).one()
    return to_item_out(*row)


@router.delete("/drafts/{draft_id}", status_code=status.HTTP_204_NO_CONTENT)
async def discard(draft_id: uuid.UUID, user: CurrentUser, db: Db):
    """Throw a draft away, photos and all.

    The files are deleted too. Leaving them would accumulate orphaned JPEGs on the volume with no
    row pointing at them — invisible until the disk fills, and impossible to attribute afterwards.
    """
    item = await _owned_draft(db, user.id, draft_id)
    item_id = item.id
    await db.delete(item)
    await db.commit()
    photo_store.delete_item_photos(item_id)


@router.get("/items/{item_id}/photos/{order}", include_in_schema=False)
async def item_photo(
    item_id: uuid.UUID, order: int, user: CurrentUser, db: Db, cleaned: bool = True
):
    """Serve one photo.

    Authenticated: these are photographs of the inside of someone's house. Falls back to the
    original when no cleaned copy exists, so a photo whose cleanup failed still displays rather
    than showing a broken frame.
    """
    owned = (
        await db.execute(select(Item.id).where(Item.id == item_id, Item.user_id == user.id))
    ).scalar_one_or_none()
    if owned is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Item not found")

    photo = (
        await db.execute(
            select(ItemPhoto).where(ItemPhoto.item_id == item_id, ItemPhoto.order == order)
        )
    ).scalar_one_or_none()
    if photo is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Photo not found")

    path = (photo.cleaned_path if cleaned else None) or photo.original_path
    return FileResponse(path)
