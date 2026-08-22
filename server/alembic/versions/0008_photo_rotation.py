"""Record each photograph's display rotation

Written by hand, NOT by autogenerate, for the reason recorded in 0002-0007: autogenerate
proposes dropping `ix_items_search_vector` and the lower(code) unique index every single time.

**Why this column exists.** Until v1.0.57 the client destroyed orientation on the way up:
`ImageBytes.downscaleToJpeg` decoded with BitmapFactory (which ignores the EXIF Orientation tag)
and re-encoded with Bitmap.compress (which writes no EXIF at all), so a portrait photo arrived
as sideways pixels with nothing left to say so. The client is fixed going forward, but for every
photograph already on the volume the rotation is simply not recoverable from the file — there is
no tag to read and no honest way to infer one. This column is where a HUMAN's correction lives.

Non-destructive on purpose. The stored bytes are the one artefact in this app that cannot be
recreated, so rotation is a derived index over them — applied when a derivative is rendered,
never baked back into the original. That also makes it reversible: a wrong correction is one
more tap, not a re-encode that has already lost a generation.

Degrees rather than an EXIF orientation code (1-8): only the four right-angle rotations are
reachable from the UI, degrees read unambiguously in a URL and a filename, and the mirrored EXIF
states have no way to be produced here.

Revision ID: 0008
Revises: 0007
Create Date: 2026-08-22

"""

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "0008"
down_revision: Union[str, None] = "0007"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column(
        "item_photos",
        sa.Column("rotation", sa.Integer(), nullable=False, server_default="0"),
    )
    # Named explicitly: an unnamed CHECK is one `downgrade` cannot then drop — the same trap the
    # unnamed foreign keys in earlier revisions left behind.
    op.create_check_constraint(
        "ck_item_photos_rotation",
        "item_photos",
        "rotation IN (0, 90, 180, 270)",
    )


def downgrade() -> None:
    op.drop_constraint("ck_item_photos_rotation", "item_photos", type_="check")
    op.drop_column("item_photos", "rotation")
