"""GET /home's two volunteered cards, composed. The contract and its reasoning: schemas/home.py.

Both are pure read-side compositions — the seasonal card over the movement ledger, the next-size
card over recorded sizes and the ladder — and both refuse to speak rather than stretch: a card
that cannot be honestly assembled is None, and the client draws nothing.
"""

import datetime
import uuid

from sqlalchemy import Date, cast, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.category import Category
from app.models.item import Item, ItemApparel
from app.models.movement import Movement
from app.models.person import Person
from app.models.tote import Tote
from app.schemas.home import NextSizeCard, SeasonalCard, SeasonalTote
from app.services.catalog import location_names, tote_counts
from app.services.colors import color_hex
from app.services.fits import _systems_for, current_sizes
from app.sizing import SizeReading, next_size_up

# The seasonal window: [a year before today, eight weeks after that]. A year as 365 days on
# purpose — `today.replace(year=today.year - 1)` raises on Feb 29, and against a weeks-wide
# window a leap day of drift is invisible while a home screen that 500s every fourth February
# is not. timedelta(days=365) and timedelta(weeks=8) survive any calendar.
_YEAR_BACK = datetime.timedelta(days=365)
_WINDOW = datetime.timedelta(weeks=8)

# Half a rung either side, tighter than fits' default 1.0: the card names ONE next size, and a
# whole-rung band would sweep in the size after next and inflate the count it advertises.
_NEXT_SIZE_TOLERANCE = 0.5


def _unanimous(ids: set[uuid.UUID | None]) -> uuid.UUID | None:
    """The one id every qualifying bin shares, else None — and a bin with none set counts as
    disagreement. "All in the attic, except the one nobody placed" is not a sentence the card
    should say with confidence."""
    if len(ids) == 1:
        return next(iter(ids))
    return None


async def seasonal_card(
    db: AsyncSession, household_id: uuid.UUID, today: datetime.date
) -> SeasonalCard | None:
    """Around this time last year these bins were unpacked — they'll be wanted again soon.

    Grounded entirely in the ledger: `unpacked` rows whose date falls in the window, grouped by
    the bin they came out of. A bin qualifies only while it still exists, belongs to this
    household and is not archived — and the card shows at all only when those bins currently
    HOLD something, because "the bins you emptied last November are still empty" is not worth
    a trip.
    """
    window_start = today - _YEAR_BACK
    moved_on = cast(Movement.moved_at, Date)
    rows = (
        await db.execute(
            select(Tote, func.min(moved_on).label("first_unpacked"))
            .select_from(Movement)
            .join(Tote, Movement.from_tote_id == Tote.id)
            .where(
                Movement.reason == "unpacked",
                Tote.household_id == household_id,
                Tote.archived.is_(False),
                moved_on.between(window_start, window_start + _WINDOW),
            )
            .group_by(Tote.id)
            .order_by(Tote.code)
        )
    ).all()
    if not rows:
        return None

    # What those bins hold NOW — the one "current item count" definition, reused rather than
    # restated. Bins unpacked last year and never refilled gate the whole card off.
    counts = await tote_counts(db, household_id)
    item_count = sum(counts.get(tote.id, 0) for tote, _ in rows)
    if item_count < 1:
        return None

    totes = [tote for tote, _ in rows]
    location_id = _unanimous({t.location_id for t in totes})
    location_name = None
    if location_id is not None:
        location_name = (await location_names(db, household_id)).get(location_id)
    category_id = _unanimous({t.category_id for t in totes})
    category_name = None
    if category_id is not None:
        category_name = await db.scalar(
            select(Category.name).where(
                Category.id == category_id, Category.household_id == household_id
            )
        )
    return SeasonalCard(
        # Capped: the card is a glance, and six bin glyphs is already a shelf's worth.
        totes=[SeasonalTote(id=t.id, code=t.code, color_hex=color_hex(t.color)) for t in totes[:6]],
        location_name=location_name,
        # The EARLIEST qualifying date — when the unpacking STARTED last year.
        unpacked_on=min(first for _, first in rows),
        item_count=item_count,
        category_name=category_name,
    )


def _stored_in_band(
    household_id: uuid.UUID, systems: list[str], ordinal: float, floor: float
) -> tuple:
    """`within_tolerance` in SQL form, over garments that are actually IN a bin.

    `size_system.in_(systems)` is the comparability gate, built by fits' `_systems_for` so a
    shoe reading can never match a sweater however close the shared axis puts them. And UNLIKE
    `fits_query` — which deliberately reports garments wherever they are — this filters
    `status == 'stored'`: the card's promise is "already waiting in a bin", and a garment that
    is lent out, unpacked or disposed of is not.

    `floor` is the wearer's CURRENT ordinal, and the band is open below it: the symmetric
    tolerance around the next rung otherwise reaches back to sizes the person wears today
    (9-12M sits 0.125 under 12M), and a card that counts the clothes already on their back as
    "waiting in the next size" is advertising a bin trip for nothing.
    """
    return (
        Item.household_id == household_id,
        Item.is_draft.is_(False),
        Item.status == "stored",
        ItemApparel.size_system.in_(systems),
        ItemApparel.size_ordinal > floor,
        ItemApparel.size_ordinal.between(
            ordinal - _NEXT_SIZE_TOLERANCE, ordinal + _NEXT_SIZE_TOLERANCE
        ),
    )


async def next_size_card(db: AsyncSession, household_id: uuid.UUID) -> NextSizeCard | None:
    """A person is nearing the next size band and the catalogue already holds garments in it.

    Built on the recorded size history and the ladder's `next_size_up`, never on age guesses —
    `Person.birthdate` plays no part. Each parsed current size yields at most one candidate:
    the next rung up WITHIN its own system (crossing systems is the ladder caller's explicit,
    labelled decision, and an unprompted card is no place for an approximate claim). The
    candidate holding the most stored garments wins; ties go to the alphabetically first
    person, so the card is stable between requests.
    """
    people = (
        (
            await db.execute(
                select(Person).where(Person.household_id == household_id).order_by(Person.name)
            )
        )
        .scalars()
        .all()
    )
    best: tuple[int, str, uuid.UUID, SizeReading, list[str]] | None = None
    for person in people:
        sizes = await current_sizes(db, person.id)
        for garment_type, size in sizes.items():
            if size.size_system is None or size.size_ordinal is None:
                # Recorded but unplaceable ("5TT"): no ordinal, no next rung, never a guess.
                continue
            rung = next_size_up(SizeReading(size.size_raw, size.size_system, size.size_ordinal))
            if rung is None:
                # Top of the system, or a formula-based system with no rung table.
                continue
            systems = _systems_for(garment_type, rung.system)
            if not systems:
                continue
            count = (
                await db.execute(
                    select(func.count())
                    .select_from(Item)
                    .join(ItemApparel, ItemApparel.item_id == Item.id)
                    .where(*_stored_in_band(household_id, systems, rung.ordinal, size.size_ordinal))
                )
            ).scalar_one()
            if count == 0:
                continue
            if best is None or count > best[0] or (count == best[0] and person.name < best[1]):
                best = (count, person.name, person.id, rung, systems, size.size_ordinal)
    if best is None:
        return None
    count, person_name, person_id, rung, systems, current_ordinal = best

    # Where to go: the bins holding those garments, most first. Three is enough to say where,
    # and `stored` guarantees every counted garment IS in one of them.
    held = func.count().label("held")
    rows = (
        await db.execute(
            select(Tote.id, Tote.code, Tote.color, held)
            .select_from(Item)
            .join(ItemApparel, ItemApparel.item_id == Item.id)
            .join(Tote, Item.current_tote_id == Tote.id)
            .where(*_stored_in_band(household_id, systems, rung.ordinal, current_ordinal))
            .group_by(Tote.id)
            .order_by(held.desc(), Tote.code)
            .limit(3)
        )
    ).all()
    return NextSizeCard(
        person_id=person_id,
        person_name=person_name,
        # next_size_up rebuilds this from the rung's own table key — a ladder label, never
        # anything a tag said.
        next_label=rung.raw,
        garment_count=count,
        totes=[
            SeasonalTote(id=i, code=code, color_hex=color_hex(color)) for i, code, color, _ in rows
        ],
    )
