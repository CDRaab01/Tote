"""Back-fill icons onto the seeded categories

Written by hand, NOT by autogenerate, for the reason recorded in 0002-0006: autogenerate
proposes dropping `ix_items_search_vector` and the lower(code) unique index every single time.
There is no schema change here at all — `categories.icon` has existed since 0001 and nothing
ever wrote it — this is purely the #29 rule: **a seed change reaches new households and nobody
else**, so the tuple change (DEFAULT_CATEGORY_ICONS) is paired with a back-fill.

Per household by construction: the UPDATE matches by name across every household, not by seed
provenance. `icon IS NULL` does two jobs at once — it makes the statement idempotent, and it
guarantees a user-chosen icon on a same-named category is never overwritten. The downgrade
reverts only our own writes (`AND icon = <the emoji we set>`), so it cannot strip an icon a
person picked themselves.

Revision ID: 0007
Revises: 0006
Create Date: 2026-08-21

"""

from typing import Sequence, Union

from alembic import op

# revision identifiers, used by Alembic.
revision: str = "0007"
down_revision: Union[str, None] = "0006"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None

# Mirrors DEFAULT_CATEGORY_ICONS at the time this migration shipped. Deliberately a frozen copy
# rather than an import: migrations are history, and importing live code means a future edit to
# the map silently rewrites what this migration does on a fresh database.
ICONS = (
    ("Christmas / seasonal decor", "🎄"),
    ("Clothing", "👕"),
    ("Baby", "🍼"),
    ("Electronics", "🔌"),
    ("Vintage games", "🕹️"),
    ("Tools", "🔧"),
    ("Kitchen", "🍳"),
    ("Books", "📚"),
    ("Documents", "📄"),
    ("Toys", "🧸"),
    ("Sporting goods", "⚽"),
    ("Craft / hobby", "🧶"),
)

# Module-level constants so the tests can run the statements directly against real Postgres
# (the 0004 pattern) — the test DB migrates to head before any household exists, so the
# migration itself is a no-op there.
BACKFILL_STATEMENTS = [
    "UPDATE categories SET icon = '{icon}' "
    "WHERE lower(name) = lower('{name}') AND icon IS NULL".format(icon=icon, name=name)
    for name, icon in ICONS
]

REVERT_STATEMENTS = [
    "UPDATE categories SET icon = NULL "
    "WHERE lower(name) = lower('{name}') AND icon = '{icon}'".format(icon=icon, name=name)
    for name, icon in ICONS
]


def upgrade() -> None:
    for statement in BACKFILL_STATEMENTS:
        op.execute(statement)


def downgrade() -> None:
    for statement in REVERT_STATEMENTS:
        op.execute(statement)
