"""Photo → draft item.

The order is: persist the originals, clean them, identify, save a draft. Persisting first is
deliberate — the photograph is the one thing that cannot be re-derived. The item was in someone's
hands in a garage, and by the time anything downstream fails it is back in a bin. Everything
after the write is best-effort and degrades.

Nothing here ever auto-commits. A scan produces a **draft**, excluded from search and from tote
contents until a human confirms it. That is the house AI rule and Tote has no exception to it.
"""

import asyncio
import logging
import uuid

from fastapi import HTTPException
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.category import Category
from app.models.item import Item, ItemPhoto
from app.services import photo_store
from app.services.ai.vision import data_url, identify_item, read_label
from app.services.apparel_draft import apparel_from_label
from app.services.cleanup import clean_photo
from app.services.sizing_hints import looks_like_clothing

logger = logging.getLogger(__name__)

# Recorded on the item when the model could not be reached at all. Deliberately distinct from a
# low-confidence draft: one means "the photo was hard", the other means "the server is
# misconfigured", and they need completely different responses from a human.
SCAN_UNAVAILABLE = "identify_unavailable"


async def _categories_for(db: AsyncSession, user_id: uuid.UUID) -> list[str]:
    rows = (
        (
            await db.execute(
                select(Category.name)
                .where(Category.user_id == user_id)
                .order_by(Category.sort_order)
            )
        )
        .scalars()
        .all()
    )
    return list(rows)


async def scan_photos(
    db: AsyncSession,
    *,
    user_id: uuid.UUID,
    photos: list[tuple[bytes, str]],
    tote_id: uuid.UUID | None = None,
    client=None,
) -> Item:
    """One item, one or more photos, one draft.

    Returns the draft item. The caller commits.
    """
    item = Item(
        user_id=user_id,
        # A placeholder name, replaced by identification if it works. Never left blank: an item
        # row with no name renders as an empty line in every list, which looks like corruption.
        name="Unidentified item",
        is_draft=True,
        status="out",
        out_reason="other",
    )
    db.add(item)
    await db.flush()

    # 1. Persist the originals FIRST. Everything after this point can fail without losing the
    #    one artefact that cannot be recreated.
    saved: list[ItemPhoto] = []
    for order, (data, content_type) in enumerate(photos):
        path = photo_store.save_original(item.id, order, data, content_type)
        photo = ItemPhoto(item_id=item.id, order=order, original_path=path)
        db.add(photo)
        saved.append(photo)
    await db.flush()

    # 2. Clean them. CPU-bound (rembg + Pillow), so off the event loop. A failure here is not
    #    fatal: the draft proceeds with the originals, which the model can still read.
    for photo, (data, _) in zip(saved, photos, strict=True):
        target = photo_store.cleaned_path_for(item.id, photo.order)
        try:
            # Clean AND write in the same worker thread. Both are blocking; doing the write back
            # on the event loop would stall every other request for the length of a disk write,
            # which on a batch of eight photos is not theoretical.
            await asyncio.to_thread(_clean_to_disk, data, target)
        except Exception:
            # Deliberately blind. Cleanup can fail in many ways (a corrupt upload, a broken
            # onnxruntime, an unwritable volume) and NONE of them may block a draft: the photo is
            # already saved by this point, and it is the part that cannot be recreated.
            logger.exception("cleanup failed for item %s photo %s", item.id, photo.order)
            continue
        photo.cleaned_path = target

    # 3. Identify. The ORIGINALS are sent, not the cleaned composites.
    #
    #    Measured in Crate: originals win. Cleanup is unpredictable on some subjects — it once
    #    decided a woven brand tab was "the subject" and cropped the shirt away — and a cropped
    #    photo cannot be un-cropped for the model. The cleaned copies are for display.
    urls = [data_url(data, content_type) for data, content_type in photos]
    categories = await _categories_for(db, user_id)

    try:
        draft = await identify_item(urls, categories, client=client)
    except HTTPException as e:
        # Transport failure: the model was unreachable or rejected the request. Record it and
        # keep the draft — the photos are already saved and a human can fill in the rest. Logged
        # as well as stored, because a stored-but-unlogged failure leaves `docker logs` silent
        # during exactly the outage someone is trying to diagnose.
        logger.warning("identify unavailable for item %s: %s", item.id, e.detail)
        item.scan_error = SCAN_UNAVAILABLE
        item.processed_at = _now()
        return item

    if draft.name:
        item.name = draft.name
    item.description = draft.description
    item.notes = None
    item.condition = draft.condition
    if draft.quantity:
        item.quantity = draft.quantity
    item.scan_confidence = draft.confidence

    if draft.category:
        match = (
            await db.execute(
                select(Category).where(Category.user_id == user_id, Category.name == draft.category)
            )
        ).scalar_one_or_none()
        if match is not None:
            item.category_id = match.id

    # 4. If this looks like clothing, ask the label what size it is — a SECOND, narrow call.
    #
    #    Its own try/except, and that is the entire point of the shape. A 503 from this call
    #    reaching the outer handler would rewrite a perfectly good identification as
    #    `identify_unavailable`, turning "we could not read the tag" into "we could not see the
    #    photo". Crate learned this the hard way; do not flatten these two handlers together.
    #
    #    The ORIGINALS go to the model, never the cleaned copies: background removal is
    #    unpredictable on labels and once decided a woven brand tab was "the subject". And there
    #    is deliberately NO retry against the cleaned copy on a null — measured, it recovers the
    #    failing image two runs in three and answers a *wrong size* the third.
    if looks_like_clothing(item.name, draft.category):
        try:
            label = await read_label(urls, client=client)
        except HTTPException as e:
            logger.warning("label pass unavailable for item %s: %s", item.id, e.detail)
            label = None
        except Exception:
            logger.exception("label pass failed for item %s", item.id)
            label = None
        if label is not None:
            apparel = apparel_from_label(item.id, label)
            if apparel is not None:
                # Through the relationship: `delete-orphan` would remove a standalone row on the
                # next flush, so the size would vanish between the scan and the review screen.
                item.apparel = apparel

    # The destination is remembered but NOT applied: an item only enters a tote when a human
    # confirms the draft, and applying it here would put an unreviewed guess into a bin's
    # contents. Confirmation is what writes the `initial` movement row.
    item.draft_tote_id = tote_id
    item.processed_at = _now()
    return item


def _clean_to_disk(data: bytes, target: str) -> None:
    """Clean one photo and write it. Runs entirely in a worker thread."""
    cleaned = clean_photo(data)
    with open(target, "wb") as fh:
        fh.write(cleaned)


def _now():
    import datetime

    return datetime.datetime.now(datetime.UTC)
