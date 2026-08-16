"""Item-photo storage: binaries on the photos volume, paths in the DB.

Layout: ``{photos_dir}/{item_id}/original_{n}.jpg`` + ``cleaned_{n}.png``. Filenames are
SERVER-generated and never client-supplied, so a crafted upload name cannot traverse paths.

The `order` integer in the filename is the same one stored on `item_photos.order`, which is why
that column is never renumbered: renumbering would leave every file orphaned with nothing
pointing at it.
"""

import uuid
from pathlib import Path

from app.config import settings

ALLOWED_CONTENT_TYPES = {"image/jpeg": ".jpg", "image/png": ".png", "image/webp": ".webp"}


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


def delete_item_photos(item_id: uuid.UUID) -> None:
    """Remove an item's photo directory.

    Called when a draft is discarded. Without it, dismissing a bad scan leaves its JPEGs on the
    volume forever with no row pointing at them — the same orphaning problem the `order` rule
    above exists to prevent, arrived at from the other direction.
    """
    import shutil

    shutil.rmtree(Path(settings.photos_dir) / str(item_id), ignore_errors=True)
