"""The "what fits Emma right now" query. Resolved server-side; clients display, never compute.

That split is not ceremony. The ladder is subtle enough — `6X` sorts between 6 and 7, and some
cross-system comparisons are meaningless rather than merely approximate — that two
implementations would drift, and the one that drifted would send someone to the attic for a
garment that does not fit.

Two properties this module refuses to give up:

* **A person's size is a history, not a value.** `person_sizes` accumulates rows and the newest
  `effective_from` on or before today wins. Last winter's answer is exactly what tells you which
  bin to open next winter, so nothing is overwritten and nothing in the future is used.
* **An unindexed size matches nothing, and that is reported as "cannot say" rather than "does
  not fit".** An item whose tag could not be placed on the ladder is not evidence of a bad fit —
  it is an absence of evidence, and the two must not look the same to whoever is deciding
  whether to climb into the attic.
"""

import datetime
import uuid

from sqlalchemy import Select, and_, or_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.item import Item, ItemApparel
from app.models.person import PersonSize
from app.services.catalog import item_query, local_today
from app.sizing import SIZE_SYSTEMS, comparable

# Which garment types a size system can speak for. `fits` is asked per garment type because a
# person has a different answer for tops and for shoes, and matching a shoe size against a
# sweater is the kind of nonsense a shared ordinal axis makes syntactically possible.
_SYSTEMS_FOR_GARMENT: dict[str, tuple[str, ...]] = {
    "shoes": ("shoe_us_child", "shoe_us_adult"),
}


def _systems_for(garment_type: str, size_system: str) -> list[str]:
    allowed = _SYSTEMS_FOR_GARMENT.get(garment_type)
    candidates = (
        allowed
        if allowed is not None
        else [s for s in SIZE_SYSTEMS if s not in ("shoe_us_child", "shoe_us_adult")]
    )
    return [s for s in candidates if comparable(s, size_system)]


async def current_sizes(
    db: AsyncSession, person_id: uuid.UUID, on: datetime.date | None = None
) -> dict[str, PersonSize]:
    """The newest size per garment type that is in effect on `on` (default today).

    Rows dated in the future are excluded rather than treated as current: recording "Emma will be
    in a 5T in September" must not change what fits her in June.
    """
    as_of = on or local_today()
    rows = (
        (
            await db.execute(
                select(PersonSize)
                .where(PersonSize.person_id == person_id, PersonSize.effective_from <= as_of)
                .order_by(
                    PersonSize.garment_type,
                    PersonSize.effective_from.desc(),
                    # Tiebreaker, and it is load-bearing. `effective_from` is a DATE, so two
                    # readings recorded on the same day tie — and with no second key the winner
                    # is whatever Postgres returns first, which can differ between queries. A
                    # mistyped size entered alongside a good one on the same day could therefore
                    # shadow it intermittently, and an unparseable winner makes `fits` answer
                    # "cannot say" for that garment type while the good reading sits right there
                    # on the person's screen looking recorded. Found in the owner's real data:
                    # `9 month` and `9 moth` on the same person, same day.
                    PersonSize.created_at.desc(),
                )
            )
        )
        .scalars()
        .all()
    )
    newest: dict[str, PersonSize] = {}
    for row in rows:
        newest.setdefault(row.garment_type, row)
    return newest


def fits_query(
    household_id: uuid.UUID,
    sizes: dict[str, PersonSize],
    tolerance: float = 1.0,
    garment_type: str | None = None,
) -> Select | None:
    """Items that approximately fit, given a person's current sizes.

    Returns None when there is nothing to match on — no indexed size for the requested garment
    type. **None means "cannot say", and a caller must not render it as an empty result**: "we
    have nothing that fits" and "we do not know her size" are different sentences, and only one
    of them is a reason to stop looking.
    """
    wanted = (
        {garment_type: sizes[garment_type]}
        if garment_type and garment_type in sizes
        else ({} if garment_type else sizes)
    )
    clauses = []
    for gtype, size in wanted.items():
        if size.size_ordinal is None or size.size_system is None:
            # The person's own size could not be placed on the ladder. Skipping it is right;
            # inventing an ordinal for it would be the exact failure the ladder refuses.
            continue
        systems = _systems_for(gtype, size.size_system)
        if not systems:
            continue
        clauses.append(
            and_(
                ItemApparel.size_system.in_(systems),
                ItemApparel.size_ordinal.between(
                    size.size_ordinal - tolerance, size.size_ordinal + tolerance
                ),
            )
        )
    if not clauses:
        return None

    # An INNER join: "what fits" is a question about things that have a size, so an item with no
    # apparel row is correctly absent rather than swept in by a null comparison.
    return (
        item_query(household_id)
        .join(ItemApparel, ItemApparel.item_id == Item.id)
        .where(or_(*clauses))
        .order_by(ItemApparel.size_ordinal, Item.name)
    )
