"""Re-derive every stored size index after widening the infant ladder

The ladder gained the six-month ranges (`6-12m`, `12-18m`, `18-24m` and their siblings), the
`mth`/`mths` spelling, and a rule for slash-separated alternates. All three are write-time
derivations, so on their own they reach new rows and nobody else.

Measured on the owner's live catalogue the day this was written: **18 of 144 sized garments had
a reading the ladder could not place**, and 14 of them are formats this change now understands —
`12 months/mois`, `18-24 months`, `12-18M`, `6-12 mths`, `2T/2TL/2Alt.`. Every one was invisible
to `fits`, which is the question the ladder exists to answer, and every one is on a tag that is
now inside a taped bin.

The remaining four are `12`, `18` and `12/18`. They stay unparsed **by design**: a bare number is
a youth 12 or a women's 12 and the module refuses to guess, which is the trade recorded in
`parse_size`'s docstring. `size_raw` keeps them for a human.

This also clears the three `6m` garments stranded by #36, which have been sitting in the
open-items table ever since with a hand-run script that could not be executed.

Data, not schema. A migration rather than a script on the box, for the reason 0004 records: a
hand-run statement is lost the next time the database is restored, and a household inventory is
exactly the kind of thing restored years later.

Revision ID: 0009
Revises: 0008
Create Date: 2026-08-23

"""

from typing import Sequence, Union

from alembic import op

from app.sizing.rederive import rederive_sizes

# revision identifiers, used by Alembic.
revision: str = "0009"
down_revision: Union[str, None] = "0008"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    counts = rederive_sizes(op.get_bind())
    print(f"0009: re-derived sizes {counts}")


def downgrade() -> None:
    """No-op, deliberately.

    There is nothing to restore. `size_system`/`size_ordinal` are a derived index over `size_raw`,
    which this migration does not touch — so the "old" values are not data that was lost, they are
    what the previous ladder would compute, and downgrading the code recomputes them on the next
    write. Inventing an inverse here would mean storing the pre-derivation state of a cache.
    """
