"""Item-photo storage: binaries on the photos volume, paths in the DB.

Layout: ``{photos_dir}/{item_id}/original_{n}.jpg`` + ``cleaned_{n}.png``. Filenames are
SERVER-generated and never client-supplied, so a crafted upload name cannot traverse paths.

The `order` integer in the filename is the same one stored on `item_photos.order`, which is why
that column is never renumbered: renumbering would leave every file orphaned with nothing
pointing at it.
"""

import os
import uuid
from pathlib import Path

from PIL import Image, ImageOps

from app.config import settings

ALLOWED_CONTENT_TYPES = {"image/jpeg": ".jpg", "image/png": ".png", "image/webp": ".webp"}

# The widths a client may ask for via `?w=` on the photo endpoint. A fixed set, because the
# request is attacker-reachable input naming a file to create: an open integer would let one
# client mint an unbounded family of derivatives per photo.
THUMBNAIL_WIDTHS = frozenset({192, 512, 1024})

# The rotations a photograph may carry, in degrees clockwise. Only right angles: they are all the
# UI can produce, and each one is lossless to apply. Same reasoning as the widths above — the
# value lands in a filename, so it is a fixed set rather than an open integer.
ROTATIONS = frozenset({0, 90, 180, 270})


def item_dir(item_id: uuid.UUID) -> Path:
    d = Path(settings.photos_dir) / str(item_id)
    d.mkdir(parents=True, exist_ok=True)
    return d


def save_original(item_id: uuid.UUID, order: int, data: bytes, content_type: str) -> str:
    ext = ALLOWED_CONTENT_TYPES[content_type]
    path = item_dir(item_id) / f"original_{order}{ext}"
    path.write_bytes(data)
    return str(path)


def cleaned_path_for(item_id: uuid.UUID, order: int) -> str:
    # Always PNG: the white-replacement composite needs a lossless target, and re-encoding a
    # cleaned composite as JPEG would put ringing artefacts on the hard subject/background edge.
    return str(item_dir(item_id) / f"cleaned_{order}.png")


def read_bytes(path: str) -> bytes:
    return Path(path).read_bytes()


def thumbnail_path(
    source_path: str, order: int, width: int | None, *, from_cleaned: bool, rotation: int = 0
) -> Path:
    """Where the sized derivative of ``source_path`` lives.

    ``thumb_{order}_{width}_{c|o}[_r{deg}].webp``, BESIDE the source — inside the item's
    directory, so :func:`delete_item_photos`'s rmtree collects derivatives without knowing they
    exist. A ``width`` of None is the full-size slot (``full``), which only ever exists for a
    rotated photograph: unturned and unsized, the source itself is already the answer.

    Rotation joins the key for the same reason the source does: a turned photograph is different
    bytes, and serving the old file under the same name is how a correction appears not to have
    worked. The suffix is omitted at 0 so every derivative made before rotation existed keeps its
    name and stays a cache hit.

    The ``c``/``o`` suffix encodes which source the thumb was derived from. The route picks the
    source first (cleaned when present, else original) and the suffix follows, so a thumb made
    from the original while cleanup was pending is simply never served again once the cleaned
    copy exists — superseded by filename, no invalidation step. It also keeps ``cleaned=false``
    requests (the book covers) from colliding with cleaned-derived thumbs of the same photo.

    Derived from the source's own parent rather than :func:`item_dir`, deliberately: that helper
    mkdirs as a side effect, and the serving path must never be able to re-create a directory
    that :func:`delete_item_photos` just removed.
    """
    suffix = "c" if from_cleaned else "o"
    turn = f"_r{rotation}" if rotation else ""
    size = width if width is not None else "full"
    return Path(source_path).parent / f"thumb_{order}_{size}_{suffix}{turn}.webp"


def ensure_thumbnail(
    source_path: str, dest: Path, width: int | None, rotation: int = 0
) -> Path | None:
    """Make ``dest`` exist as a ``width``-bounded WebP of ``source_path``; return it, or None.

    Sync and CPU-bound (Pillow) — call via ``asyncio.to_thread``. None means the source does
    not decode (the scan deliberately keeps an upload that cleanup could not read, and serving
    it beats a 500 — same contract as the cleanup module's "never block on cosmetics"); the
    caller serves the source unresized.

    WebP because the cleaned copies are RGBA cutouts and the transparency must survive — a JPEG
    derivative would flatten alpha to black, the exact defect class ``cleanup.clean_photo``'s
    compositing rules exist to prevent. ``Image.thumbnail`` never upscales, so a source smaller
    than ``width`` passes through at its own size.

    ``rotation`` (degrees clockwise) is applied BEFORE the resize, so the width bounds the edge a
    viewer will actually see rather than the one the sensor happened to record. A ``width`` of
    None turns the photograph without resizing it — the full-size response for a corrected photo.

    Atomic against concurrent requests for the same derivative: each writer saves to its own
    temp name and ``os.replace``s it into place, so both succeed and one file wins.
    """
    if dest.exists():
        return dest
    try:
        img = Image.open(source_path)
        img.load()
    except OSError:
        return None
    # The client strips EXIF when it re-encodes, but a decode failure there falls back to the
    # original bytes, and book covers arrive from the internet — so honour orientation here
    # rather than letting a thumb disagree with Coil's EXIF-aware render of the full image.
    img = ImageOps.exif_transpose(img)
    if rotation:
        # `expand` so a 90/270 turn keeps every pixel instead of cropping to the old frame.
        # Pillow rotates counter-clockwise; the stored value is clockwise, as a person reads it.
        img = img.rotate(-rotation, expand=True)
    has_alpha = img.mode in ("RGBA", "LA") or (img.mode == "P" and "transparency" in img.info)
    img = img.convert("RGBA" if has_alpha else "RGB")
    if width is not None:
        img.thumbnail((width, width), Image.LANCZOS)
    tmp = dest.parent / f".{dest.name}.{uuid.uuid4().hex}.tmp"
    try:
        img.save(tmp, format="WEBP")
        os.replace(tmp, dest)
    finally:
        tmp.unlink(missing_ok=True)
    return dest


def delete_item_photos(item_id: uuid.UUID) -> None:
    """Remove an item's photo directory.

    Called when a draft is discarded. Without it, dismissing a bad scan leaves its JPEGs on the
    volume forever with no row pointing at them — the same orphaning problem the `order` rule
    above exists to prevent, arrived at from the other direction.
    """
    import shutil

    shutil.rmtree(Path(settings.photos_dir) / str(item_id), ignore_errors=True)
