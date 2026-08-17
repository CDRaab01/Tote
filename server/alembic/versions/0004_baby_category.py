"""Back-fill the "Baby" seed category for accounts that already exist

The seeded vocabulary in `DEFAULT_CATEGORIES` is only written at first login, so adding a name
there reaches new accounts and nobody else. This gives the existing ones the same row.

Data, not schema, and deliberately a migration rather than a one-off `INSERT` on the box: a
hand-run statement is lost the next time the database is restored from a backup, and a household
inventory is exactly the kind of thing that gets restored years later.

Written by hand, NOT by autogenerate, for the reason recorded in 0002 and 0003: autogenerate
cannot see `ix_items_search_vector` or `uq_totes_user_code_lower` in the model metadata and
proposes dropping both, every single time. `test_schema.py` asserts they survive at head.

Revision ID: 0004
Revises: 0003
Create Date: 2026-08-17

"""

from typing import Sequence, Union

from alembic import op

# revision identifiers, used by Alembic.
revision: str = "0004"
down_revision: Union[str, None] = "0003"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None

CATEGORY = "Baby"

# Module-level so a test can run it directly. The test database migrates to head before any user
# exists, so this statement is a no-op there and would otherwise ship entirely unexercised —
# which for a data migration is the same as untested.
#
# Appended at the end of each user's own ordering rather than slotted in at the position it holds
# in DEFAULT_CATEGORIES: renumbering every existing row would rewrite an ordering the user may
# have arranged, to move one row a few places up a list of twelve.
#
# `WHERE NOT EXISTS` makes it idempotent by hand as well as by Alembic. `uq_categories_user_name`
# would raise otherwise, and a data migration that cannot be re-run against a half-migrated
# restore turns a bad afternoon into a worse one.
BACKFILL_SQL = f"""
INSERT INTO categories (id, user_id, name, sort_order)
SELECT gen_random_uuid(), u.id, '{CATEGORY}',
       COALESCE((SELECT MAX(c.sort_order) + 1 FROM categories c WHERE c.user_id = u.id), 0)
FROM users u
WHERE NOT EXISTS (
    SELECT 1 FROM categories c
    WHERE c.user_id = u.id AND lower(c.name) = lower('{CATEGORY}')
)
"""

# Only rows nothing has been filed under. Downgrading must not throw away a bin's worth of filing
# somebody did here — the schema going backwards is no reason to lose data, and a category left
# behind is a far smaller problem than an item that lost the one it was in.
REMOVE_SQL = f"""
DELETE FROM categories c
WHERE lower(c.name) = lower('{CATEGORY}')
  AND NOT EXISTS (SELECT 1 FROM items i WHERE i.category_id = c.id)
  AND NOT EXISTS (SELECT 1 FROM totes t WHERE t.category_id = c.id)
"""


def upgrade() -> None:
    op.execute(BACKFILL_SQL)


def downgrade() -> None:
    op.execute(REMOVE_SQL)
