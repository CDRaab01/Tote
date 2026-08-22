"""Photo capture: scan a photo into a draft, review it, confirm or discard it.

Nothing here writes to the catalog. A scan produces a **draft**, invisible to search and to a
tote's contents; only `POST /drafts/{id}/confirm` files it, and that is what writes the `initial`
movement row. The house AI rule is that nothing model-generated enters the catalog without
explicit approval, and Tote has no exception to it.
"""

import asyncio
import datetime
import uuid
from typing import Annotated

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile, status
from fastapi.responses import FileResponse
from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.database import get_db
from app.models.category import Category
from app.models.item import Item, ItemPhoto
from app.models.tote import Tote
from app.schemas.catalog import DraftConfirm, DraftOut, ItemOut, ScanIsbnIn, ScanIsbnOut
from app.security import CurrentUser
from app.services import photo_store
from app.services.apparel_write import apply_apparel
from app.services.books import LookupUnavailable, description_for, fetch_cover, lookup_isbn
from app.services.catalog import item_query, to_item_out
from app.services.movement import record_move
from app.services.scan_pipeline import scan_photos

router = APIRouter(tags=["scan"])

Db = Annotated[AsyncSession, Depends(get_db)]

MAX_PHOTOS = 8

# Cacheable, but only in the requester's own cache: these are photographs of the inside of a
# house behind auth, and a shared cache on the path must never hold them. A day, because the
# client's image cache makes re-validation the only cost — and FileResponse answers no 304s,
# so expiry means re-downloading a tens-of-KB derivative, not the original.
PHOTO_CACHE_CONTROL = "private, max-age=86400"


async def _owned_draft(db: AsyncSession, household_id: uuid.UUID, draft_id: uuid.UUID) -> Item:
    item = (
        await db.execute(
            select(Item).where(
                Item.id == draft_id, Item.household_id == household_id, Item.is_draft.is_(True)
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
    name: Annotated[str | None, Form(max_length=160)] = None,
    category_id: Annotated[uuid.UUID | None, Form()] = None,
    describe: Annotated[bool, Form()] = False,
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

    **`name` switches identification off.** When it is present the omnibus vision call is
    skipped and the photos go straight to the label pass. That is faster (identify is the slow
    half of a scan measured at 35.5 s for one photo), it removes a correction chore, and — the
    part that is not obvious — it makes the *size* read more reliable, because the clothing gate
    stops depending on a guess it would otherwise have to trust. `category_id` rides along for
    the same reason: the gate reads the person's own vocabulary instead of the model's guess at
    it. Leave both out and the endpoint behaves exactly as it always has.
    """
    if capture_id is not None:
        existing = (
            await db.execute(
                select(Item).where(
                    Item.household_id == user.household_id, Item.capture_id == capture_id
                )
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
            await db.execute(
                select(Tote).where(Tote.id == tote_id, Tote.household_id == user.household_id)
            )
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

    if category_id is not None:
        owned = (
            await db.execute(
                select(Category).where(
                    Category.id == category_id, Category.household_id == user.household_id
                )
            )
        ).scalar_one_or_none()
        if owned is None:
            raise HTTPException(status.HTTP_404_NOT_FOUND, "Category not found")

    item = await scan_photos(
        db,
        household_id=user.household_id,
        user_id=user.id,
        photos=payload,
        tote_id=tote_id,
        capture_id=capture_id,
        name=name,
        category_id=category_id,
        describe=describe,
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
                select(Item).where(
                    Item.household_id == user.household_id, Item.capture_id == capture_id
                )
            )
        ).scalar_one_or_none()
        if existing is None:
            raise
        return await _to_draft_out(db, existing)
    await db.refresh(item)
    return await _to_draft_out(db, item)


@router.post("/items/scan-isbn", response_model=ScanIsbnOut, status_code=status.HTTP_201_CREATED)
async def scan_isbn(body: ScanIsbnIn, user: CurrentUser, db: Db):
    """A scanned book barcode becomes a filed item — no photograph, no review.

    Its own endpoint rather than a mode of `/items/scan`, which requires at least one photo and
    is multipart for that reason; a barcode has no bytes to carry. And its own rules, because
    the trust story is different: **nothing here is model output.** An ISBN lookup returns
    database rows keyed by the number printed on the object, so the no-auto-commit rule for
    AI-generated data does not apply (owner-confirmed) and the book files directly into the
    chosen bin. The rule itself stands untouched for everything vision produces.

    Three outcomes, and the middle one is the design (see `services/books.py`):

    * **Found** → a real item, filed with an ordinary ledger row. Title in `name`, author and
      imprint in `description`, `ISBN {n}` in `notes` — the three columns `search_vector`
      covers, so a book is findable by its author for free. Category is the household's
      "Books" if they still have one. The cover lands as photo 0 when it can be fetched;
      a coverless book still files.
    * **Not found** → a draft for the Review tab (`scan_error="isbn_not_found"`), where a human
      names it. A definitive answer from the database, not a failure.
    * **Databases unreachable** → 503 with nothing committed, so the client's Retry is safe.
      A network flake must never mint a "this book does not exist" draft.

    `capture_id` is required and does exactly what it does on `/items/scan` — a replayed
    request returns what the first attempt made instead of filing the book twice.
    """
    existing = (
        await db.execute(
            select(Item).where(
                Item.household_id == user.household_id, Item.capture_id == body.capture_id
            )
        )
    ).scalar_one_or_none()
    if existing is not None:
        return ScanIsbnOut(
            found=not existing.is_draft, source=None, item=await _to_draft_out(db, existing)
        )

    if body.tote_id is not None:
        found_tote = (
            await db.execute(
                select(Tote).where(Tote.id == body.tote_id, Tote.household_id == user.household_id)
            )
        ).scalar_one_or_none()
        if found_tote is None:
            raise HTTPException(status.HTTP_404_NOT_FOUND, "Tote not found")

    try:
        meta = await lookup_isbn(body.isbn)
    except LookupUnavailable:
        # Nothing has been written, so the client can retry the same capture_id safely.
        raise HTTPException(
            status.HTTP_503_SERVICE_UNAVAILABLE,
            "Couldn't reach the book database — try again in a moment",
        )

    if meta is None:
        item = Item(
            household_id=user.household_id,
            user_id=user.id,
            name="Unidentified book",
            notes=f"ISBN {body.isbn}",
            is_draft=True,
            status="out",
            out_reason="other",
            scan_error="isbn_not_found",
            capture_id=body.capture_id,
            draft_tote_id=body.tote_id,
            processed_at=datetime.datetime.now(datetime.UTC),
        )
        db.add(item)
        commit_result = await _commit_or_return_winner(db, user.household_id, body.capture_id)
        if commit_result is not None:
            return commit_result
        await db.refresh(item)
        return ScanIsbnOut(found=False, source=None, item=await _to_draft_out(db, item))

    # The household's "Books" category, if they still have one. By name rather than a stored id
    # because the vocabulary is the user's — they may have renamed or deleted it, and a book
    # with no category label is a smaller wrong than resurrecting a name they removed.
    books_category_id = (
        await db.execute(
            select(Category.id).where(
                Category.household_id == user.household_id, func.lower(Category.name) == "books"
            )
        )
    ).scalar_one_or_none()

    item = Item(
        household_id=user.household_id,
        user_id=user.id,
        name=meta.title[:160],
        description=description_for(meta),
        notes=f"ISBN {body.isbn}",
        category_id=books_category_id,
        is_draft=False,
        status="out",
        out_reason="other",
        capture_id=body.capture_id,
        processed_at=datetime.datetime.now(datetime.UTC),
    )
    db.add(item)
    await db.flush()

    # The cover is a nicety on a filing that has already succeeded — its failure must never
    # take the book with it (the label-pass rule, applied to images).
    cover = await fetch_cover(meta.cover_url)
    if cover is not None:
        data, content_type = cover
        path = photo_store.save_original(item.id, 0, data, content_type)
        db.add(ItemPhoto(item_id=item.id, order=0, original_path=path, role="front"))

    await record_move(
        db,
        item=item,
        reason="initial" if body.tote_id is not None else "catalogued",
        to_tote_id=body.tote_id,
        moved_by_user_id=user.id,
    )

    commit_result = await _commit_or_return_winner(db, user.household_id, body.capture_id)
    if commit_result is not None:
        return commit_result
    await db.refresh(item)
    return ScanIsbnOut(found=True, source=meta.source, item=await _to_draft_out(db, item))


async def _commit_or_return_winner(
    db: AsyncSession, household_id: uuid.UUID, capture_id: uuid.UUID
) -> ScanIsbnOut | None:
    """Commit, or hand back the row a racing attempt already made. None means "we won".

    The same backstop `/items/scan` carries: the idempotency check runs before the lookup, the
    lookup takes seconds, and two retries of one scan can race through that window. The unique
    constraint decides; the loser returns the winner's row rather than a 409 the client would
    just retry into the same wall.
    """
    try:
        await db.commit()
    except IntegrityError:
        await db.rollback()
        existing = (
            await db.execute(
                select(Item).where(Item.household_id == household_id, Item.capture_id == capture_id)
            )
        ).scalar_one_or_none()
        if existing is None:
            raise
        return ScanIsbnOut(
            found=not existing.is_draft, source=None, item=await _to_draft_out(db, existing)
        )
    return None


@router.get("/drafts", response_model=list[DraftOut])
async def list_drafts(user: CurrentUser, db: Db):
    """The review stack, oldest first — the order they were shot in, which is the order the
    person remembers them in."""
    rows = (
        (
            await db.execute(
                select(Item)
                .where(Item.household_id == user.household_id, Item.is_draft.is_(True))
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
    item = await _owned_draft(db, user.household_id, draft_id)

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

    # Confirming is a movement either way, so it always leaves a ledger row — an item that
    # appeared with no history would be the first hole in it. Which reason depends on whether a
    # bin was chosen: `initial` puts it in one, `catalogued` records that it exists and is not in
    # one yet. Both are honest; neither is a placeholder.
    await record_move(
        db,
        item=item,
        reason="initial" if body.tote_id is not None else "catalogued",
        to_tote_id=body.tote_id,
        moved_by_user_id=user.id,
    )
    await db.commit()

    row = (await db.execute(item_query(user.household_id).where(Item.id == item.id))).one()
    return to_item_out(*row)


@router.delete("/drafts/{draft_id}", status_code=status.HTTP_204_NO_CONTENT)
async def discard(draft_id: uuid.UUID, user: CurrentUser, db: Db):
    """Throw a draft away, photos and all.

    The files are deleted too. Leaving them would accumulate orphaned JPEGs on the volume with no
    row pointing at them — invisible until the disk fills, and impossible to attribute afterwards.
    """
    item = await _owned_draft(db, user.household_id, draft_id)
    item_id = item.id
    await db.delete(item)
    await db.commit()
    photo_store.delete_item_photos(item_id)


@router.get("/items/{item_id}/photos/{order}", include_in_schema=False)
async def item_photo(
    item_id: uuid.UUID,
    order: int,
    user: CurrentUser,
    db: Db,
    cleaned: bool = True,
    w: int | None = None,
):
    """Serve one photo, optionally resized.

    Authenticated: these are photographs of the inside of someone's house. Falls back to the
    original when no cleaned copy exists, so a photo whose cleanup failed still displays rather
    than showing a broken frame.

    ``w`` asks for a WebP derivative no wider than that, from the fixed
    :data:`photo_store.THUMBNAIL_WIDTHS` set — generated on first request, cached beside the
    source. Without it every 52dp list thumbnail on the client downloaded the full cleaned PNG
    (megabytes, over the attic's Wi-Fi), which is why lists scrolled ahead of their pictures.
    The source is chosen FIRST and the derivative's name follows it, so a thumb made from the
    original is superseded the moment a cleaned copy exists. A source that does not decode (a
    corrupt upload the scan deliberately kept) is served whole rather than turned into a 500.
    """
    if w is not None and w not in photo_store.THUMBNAIL_WIDTHS:
        raise HTTPException(
            status.HTTP_422_UNPROCESSABLE_ENTITY,
            f"w must be one of {sorted(photo_store.THUMBNAIL_WIDTHS)}",
        )

    owned = (
        await db.execute(
            select(Item.id).where(Item.id == item_id, Item.household_id == user.household_id)
        )
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

    from_cleaned = cleaned and photo.cleaned_path is not None
    path = (photo.cleaned_path if cleaned else None) or photo.original_path
    if w is not None:
        dest = photo_store.thumbnail_path(path, order, w, from_cleaned=from_cleaned)
        thumb = await asyncio.to_thread(photo_store.ensure_thumbnail, path, dest, w)
        if thumb is not None:
            path = str(thumb)
    return FileResponse(path, headers={"Cache-Control": PHOTO_CACHE_CONTROL})
