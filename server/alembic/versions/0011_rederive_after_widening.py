"""Re-derive sizes after the ladder learned four more real tag spellings.

A ladder change reaches new rows and nobody else: `size_system`/`size_ordinal` are computed at
**write** time, so every garment already in a bin keeps the answer the old table gave — usually
`NULL`, i.e. invisible to `fits`. #55 fixed that mechanism rather than the symptom, and this is
the three-line migration it promised.

What the ladder learned, all of it a spelling the tag itself disambiguates and none of it a
guess (see `ladder.py` for each):

* `M(8)`, `XS (4/5)`, `L (10-12)` — an alpha size the tag qualifies with a youth number.
* `18m-24m`, `3M-6M` — a month range carrying its unit on both sides.
* `6 MESES`, `6 MOIS` — months on Spanish- and Canadian-market tags.
* `YS` — a youth alpha printed as one token.

Measured against a copy of production before writing this: **19 of 65 unreadable garments become
readable**, and nothing already correct moves (`rederive_sizes` writes only rows whose derived
values actually change).

The 46 that stay unread are staying on purpose. Most are bare numbers like `18` and `24` on rows
named "Baby bodysuit" — and the ladder, given a department, would read those as **women's 27 and
30**. They are months. That is exactly the wrong-ordinal-sends-you-to-the-wrong-bin-twice failure
the never-infer rule exists to prevent, so `size_raw` keeps them for a human. The rest are
centimetre sizing (`128cm`, `UP TO 92cm`), which is a ladder that does not exist yet.
"""

from collections.abc import Sequence

from alembic import op
from app.sizing.rederive import rederive_sizes

# revision identifiers, used by Alembic.
revision: str = "0011"
down_revision: str | None = "0010"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    counts = rederive_sizes(op.get_bind())
    print(f"0011: re-derived sizes {counts}")


def downgrade() -> None:
    """No-op, deliberately — same reasoning as 0009.

    `size_system`/`size_ordinal` are a derived index over `size_raw`, which this migration does
    not touch. The "old" values are not data that was lost; they are what the previous ladder
    would compute, and downgrading the code recomputes them on the next write. An inverse here
    would mean storing the pre-derivation state of a cache.
    """
