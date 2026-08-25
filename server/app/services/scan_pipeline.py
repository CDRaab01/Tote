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
from pathlib import Path

from fastapi import HTTPException
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.category import Category
from app.models.item import Item, ItemPhoto
from app.services import photo_store
from app.services.ai.vision import data_url, describe_item, identify_item, read_label
from app.services.apparel_draft import apparel_from_label
from app.services.cleanup import clean_photo
from app.services.sizing_hints import looks_like_clothing

logger = logging.getLogger(__name__)

# Recorded on the item when the model could not be reached at all. Deliberately distinct from a
# low-confidence draft: one means "the photo was hard", the other means "the server is
# misconfigured", and they need completely different responses from a human.
SCAN_UNAVAILABLE = "identify_unavailable"


async def _categories_for(db: AsyncSession, household_id: uuid.UUID) -> list[str]:
    rows = (
        (
            await db.execute(
                select(Category.name)
                .where(Category.household_id == household_id)
                .order_by(Category.sort_order)
            )
        )
        .scalars()
        .all()
    )
    return list(rows)


# Extension back to content type, for photographs read off the volume rather than off a request.
# `save_original` chose the extension from the upload's content type, so this is that decision
# read back rather than a guess.
_TYPE_FOR_EXT = {
    ext: content_type for content_type, ext in photo_store.ALLOWED_CONTENT_TYPES.items()
}


class NoStoredPhotos(Exception):
    """An item has no readable original on the volume, so there is nothing to re-read."""


async def stored_photo_urls(db: AsyncSession, item: Item) -> list[str]:
    """Data URLs for an item's ORIGINAL photographs, straight off the volume.

    The re-scan path's counterpart to the uploads a fresh scan holds in memory. Originals, never
    the cleaned copies, for the reason step 3 gives: background removal is unpredictable on some
    subjects and a cropped photo cannot be un-cropped for the model.

    Raises [NoStoredPhotos] when there is nothing to send. A row whose file is missing is a
    genuinely different situation from a model outage — no number of retries will fix it — so it
    must not come back as "try again later".
    """
    photos = (
        (
            await db.execute(
                select(ItemPhoto).where(ItemPhoto.item_id == item.id).order_by(ItemPhoto.order)
            )
        )
        .scalars()
        .all()
    )
    urls: list[str] = []
    for photo in photos:
        try:
            data = photo_store.read_bytes(photo.original_path)
        except OSError:
            # Logged at error, not warning: the database says this file exists and it does not,
            # which is the one failure here that no retry can clear.
            logger.error(
                "item %s photo %s: original missing at %s",
                item.id,
                photo.order,
                photo.original_path,
            )
            continue
        ext = Path(photo.original_path).suffix.lower()
        urls.append(data_url(data, _TYPE_FOR_EXT.get(ext, "image/jpeg")))
    if not urls:
        raise NoStoredPhotos
    return urls


async def scan_photos(
    db: AsyncSession,
    *,
    household_id: uuid.UUID,
    user_id: uuid.UUID,
    photos: list[tuple[bytes, str]],
    tote_id: uuid.UUID | None = None,
    capture_id: uuid.UUID | None = None,
    name: str | None = None,
    category_id: uuid.UUID | None = None,
    describe: bool = False,
    client=None,
) -> Item:
    """One item, one or more photos, one draft.

    Returns the draft item. The caller commits.

    `capture_id` is the client's queue-row id, stored so a replayed upload resolves here instead
    of filing the object twice — see the router, which does the lookup.

    **`name` turns the identify call off.** When the person says what the thing is, asking the
    model to guess is worse than useless on three counts, and the third is the one that is not
    obvious:

    1. It is the slow half. Identify is the omnibus call; dropping it roughly halves a scan
       measured at 35.5 s for one photo.
    2. Its answer would be overwritten anyway, so every wrong guess is a correction chore in
       review and nothing else.
    3. **It gates the label pass.** `looks_like_clothing` reads the name and category identify
       chose, so a bad guess does not merely cost a correction — it can silently suppress the
       size read, which is the one vision output measured to work well. A human-supplied name
       and category make that gate trustworthy instead of circular.

    This is the same rule the two-call split already follows, taken one step further: a narrow
    question beats a broad one, and no question at all beats a narrow one nobody needed to ask.
    """
    item = Item(
        # Scope and provenance are different things now: the household is who may see this
        # draft, `user_id` is which member pressed the shutter.
        household_id=household_id,
        user_id=user_id,
        capture_id=capture_id,
        # The person's own words when they gave them, otherwise a placeholder replaced by
        # identification if it works. Never left blank: an item row with no name renders as an
        # empty line in every list, which looks like corruption.
        name=(name.strip() if name and name.strip() else "Unidentified item"),
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

    # 3. Identify — unless the person already did. The ORIGINALS are sent, not the cleaned
    #    composites.
    #
    #    Measured in Crate: originals win. Cleanup is unpredictable on some subjects — it once
    #    decided a woven brand tab was "the subject" and cropped the shirt away — and a cropped
    #    photo cannot be un-cropped for the model. The cleaned copies are for display.
    urls = [data_url(data, content_type) for data, content_type in photos]

    if name and name.strip():
        # Named by hand: identify is skipped entirely. The condition and quantity it would have
        # filled are simply not answered, which is honest — nobody asked, so nothing was guessed.
        # The category comes from the person too, so the clothing gate below reads their
        # vocabulary rather than a guess about it.
        if category_id is not None:
            item.category_id = category_id
        if describe:
            # A narrow second question, and the one place a wrong answer is nearly free: a
            # description is editable in review and nothing downstream depends on it — except
            # `search_vector`, which is generated over name + description + notes. That is the
            # whole reason to ask. "The one with the ducks on it" finds nothing unless something
            # wrote "ducks", and a photographed item with a bare name is unfindable by any words
            # but its own. Its own try/except for the same reason the label pass has one.
            try:
                described = await describe_item(urls, item.name, client=client)
            except HTTPException as e:
                logger.warning("describe unavailable for item %s: %s", item.id, e.detail)
                described = None
            except Exception:
                logger.exception("describe failed for item %s", item.id)
                described = None
            if described is not None:
                item.description = described.description
        await _read_the_label(
            db, item, urls, household_id=household_id, category_id=category_id, client=client
        )
        item.draft_tote_id = tote_id
        item.processed_at = _now()
        return item

    # The destination is remembered but NOT applied: an item only enters a tote when a human
    # confirms the draft, and applying it here would put an unreviewed guess into a bin's
    # contents. Confirmation is what writes the `initial` movement row.
    #
    # Set BEFORE identification, not after, and that ordering is a fix rather than a tidy-up. It
    # used to be the last line of the happy path, so the `identify_unavailable` branch returned
    # without ever reaching it — a model outage silently threw away the bin the person chose at
    # capture time, on exactly the drafts already facing the most hand-editing.
    item.draft_tote_id = tote_id

    try:
        await identify_into(db, item, urls, household_id=household_id, client=client)
    except HTTPException as e:
        # Transport failure: the model was unreachable or rejected the request. Record it and
        # keep the draft — the photos are already saved and a human can fill in the rest. Logged
        # as well as stored, because a stored-but-unlogged failure leaves `docker logs` silent
        # during exactly the outage someone is trying to diagnose.
        #
        # Recoverable, and that is why it is stored rather than only logged: `POST
        # /drafts/{id}/rescan` replays the identification against the photos already on the
        # volume, so an outage costs a tap later instead of a second trip to the attic.
        logger.warning("identify unavailable for item %s: %s", item.id, e.detail)
        item.scan_error = SCAN_UNAVAILABLE
        item.processed_at = _now()
        return item

    item.processed_at = _now()
    return item


async def identify_into(
    db: AsyncSession,
    item: Item,
    urls: list[str],
    *,
    household_id: uuid.UUID,
    client=None,
) -> None:
    """Ask the model what this is, and write the answer onto `item`.

    Steps 3 and 4 of a scan, extracted because a **re-scan** replays exactly them: the photos are
    persisted before the model is ever called, so a failed identification is recoverable without
    re-photographing anything. One implementation rather than two — two would drift, and the
    drift would surface as "the retry gives a different shape of answer than the scan did".

    **Raises** `HTTPException` when the model cannot be reached, and the two callers want
    opposite things from that. A scan records `scan_error` and keeps the draft, because the
    photograph it has just taken must not be lost. A re-scan lets it become a 503: nothing is at
    risk, and somebody who asked for a retry needs to be told it failed rather than watch it
    silently no-op.

    Every field the model owns is REPLACED, including with `None`. A re-scan is a fresh answer,
    not a merge onto a stale one — leaving the previous run's condition or category behind would
    produce a draft that is half one reading and half another, with nothing on screen saying so.
    """
    categories = await _categories_for(db, household_id)
    draft = await identify_item(urls, categories, client=client)

    if draft.name:
        item.name = draft.name
    item.description = draft.description
    item.notes = None
    item.condition = draft.condition
    if draft.quantity:
        item.quantity = draft.quantity
    item.scan_confidence = draft.confidence
    item.scan_error = None
    item.category_id = None
    # Cleared rather than left to be overwritten, because the label pass below only assigns when
    # it actually reads a tag. Without this, re-scanning something the model now calls a saucepan
    # keeps the size it read from the same photo when it thought the object was a jumper.
    item.apparel = None

    if draft.category:
        match = (
            await db.execute(
                select(Category).where(
                    Category.household_id == household_id, Category.name == draft.category
                )
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
    await _read_the_label(
        db, item, urls, household_id=household_id, category_name=draft.category, client=client
    )


async def _read_the_label(
    db: AsyncSession,
    item: Item,
    urls: list[str],
    *,
    household_id: uuid.UUID,
    category_name: str | None = None,
    category_id: uuid.UUID | None = None,
    client=None,
) -> None:
    """If this looks like clothing, ask the label what size it is — a SECOND, narrow call.

    Its own try/except, and that is the entire point of the shape. A 503 from this call reaching
    the outer handler would rewrite a perfectly good identification as `identify_unavailable`,
    turning "we could not read the tag" into "we could not see the photo". Crate learned this the
    hard way; do not flatten these two handlers together.

    The ORIGINALS go to the model, never the cleaned copies: background removal is unpredictable
    on labels and once decided a woven brand tab was "the subject". And there is deliberately NO
    retry against the cleaned copy on a null — measured, it recovers the failing image two runs in
    three and answers a *wrong size* the third.

    The gate takes a category **name** on the identified path (the model answers in names) and a
    category **id** on the named-by-hand path (a picker answers in ids). Resolving the id here
    rather than making the caller do it keeps the gate's one rule in one place.
    """
    if category_name is None and category_id is not None:
        category_name = (
            await db.execute(
                select(Category.name).where(
                    Category.id == category_id, Category.household_id == household_id
                )
            )
        ).scalar_one_or_none()

    if not looks_like_clothing(item.name, category_name):
        return

    try:
        label = await read_label(urls, client=client)
    except HTTPException as e:
        logger.warning("label pass unavailable for item %s: %s", item.id, e.detail)
        label = None
    except Exception:
        logger.exception("label pass failed for item %s", item.id)
        label = None
    if label is None:
        return

    apparel = apparel_from_label(item.id, label)
    if apparel is not None:
        # Through the relationship: `delete-orphan` would remove a standalone row on the next
        # flush, so the size would vanish between the scan and the review screen.
        item.apparel = apparel


def _clean_to_disk(data: bytes, target: str) -> None:
    """Clean one photo and write it. Runs entirely in a worker thread."""
    cleaned = clean_photo(data)
    with open(target, "wb") as fh:
        fh.write(cleaned)


def _now():
    import datetime

    return datetime.datetime.now(datetime.UTC)
