"""Verify pass, typo-tolerant search, and a photo of the place itself

Written by hand, NOT by autogenerate, for the reason recorded in 0002-0009: autogenerate
proposes dropping `ix_items_search_vector` and the lower(code) unique index every single time.

Three small pieces of schema behind one product round:

- `totes.last_verified_at` — when a human last stood in front of the bin and confirmed the
  catalogue against what is physically inside (the Verify flow). The ledger faithfully records
  every *intentional* move; nothing until now could catch the move nobody recorded, and that
  drift is what kills every inventory system eventually. Null means never verified, which is
  true of every bin at migration time.

- `CREATE EXTENSION pg_trgm` — trigram similarity for the close-matches search fallback. It
  runs ONLY when full-text finds nothing and the caller opted in, so "sleepsuite" stops reading
  as "we don't own one". No trigram index on purpose: a household catalogue is hundreds of rows,
  and another expression index invisible to model metadata would double the autogenerate trap
  this docstring's first line exists to warn about.

- `locations.photo_path` — the one optional photo of the place itself (the shelf, the rack), so
  the bins list can be navigated by sight. Path in the DB, bytes on the photos volume, exactly
  like item photographs.

Revision ID: 0010
Revises: 0009
Create Date: 2026-08-24

"""

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "0010"
down_revision: Union[str, None] = "0009"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column(
        "totes",
        sa.Column("last_verified_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.add_column(
        "locations",
        sa.Column("photo_path", sa.String(length=255), nullable=True),
    )
    # pg_trgm ships with the postgres image; the container's app role owns the database, so no
    # separate superuser step is needed. IF NOT EXISTS keeps re-runs and restored dumps happy.
    op.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm")


def downgrade() -> None:
    # Symmetric on purpose. Dropping the extension is safe here because nothing else in this
    # database uses trigram operators — the fallback query in the app is the only caller.
    op.execute("DROP EXTENSION IF EXISTS pg_trgm")
    op.drop_column("locations", "photo_path")
    op.drop_column("totes", "last_verified_at")
