"""GET /home's two volunteered cards, composed. The contract and its reasoning: schemas/home.py.

Both are pure read-side compositions — the seasonal card over the movement ledger, the next-size
card over recorded sizes and the ladder — and both refuse to speak rather than stretch: a card
that cannot be honestly assembled is None, and the client draws nothing.
"""

import datetime
import uuid
from typing import NamedTuple

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
from app.sizing import SizeReading, next_sizes_up, rung_band

# The seasonal window: [a year before today, eight weeks after that]. A year as 365 days on
# purpose — `today.replace(year=today.year - 1)` raises on Feb 29, and against a weeks-wide
# window a leap day of drift is invisible while a home screen that 500s every fourth February
# is not. timedelta(days=365) and timedelta(weeks=8) survive any calendar.
_YEAR_BACK = datetime.timedelta(days=365)
_WINDOW = datetime.timedelta(weeks=8)

# How far up the ladder "nearly" is allowed to look, and it takes BOTH limits.
#
# Two rungs, because a rung the catalogue holds nothing in is not worth naming and the infant
# ladder in particular prints nearly the same garment size several ways (`9-12M` and `12M` are
# 0.125 apart) — a household can easily own everything in one and nothing in the other. And 1.0
# on the shared axis, the same number `fits` uses, because two rungs of `toddler` is two years of
# a child and calling that "nearly" would advertise a trip for clothes nobody can wear yet.
#
# There is deliberately NO ordinal tolerance around the chosen rung any more. The band comes from
# `rung_band`, i.e. from what the rungs actually are: the old ±0.5 was documented as "half a
# rung" and is only that on the coarse ladders, so on `infant_months` it spanned up to nine of
# them and the card counted sizes it had not named.
_LOOKAHEAD_RUNGS = 2
_LOOKAHEAD_ORDINAL = 1.0

# A tag can read "Heather Grey / M/L". Past this the raw reading is a description, not a size, and
# the ladder's own key is the more useful label.
_MAX_LABEL_LENGTH = 12


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
        # Capped: the card is a glance, and six bin glyphs is already a shelf's worth — which is
        # exactly why `tote_count` has to carry the real number. `item_count` above sums EVERY
        # qualifying bin, so without it the card can promise 137 items over six swatches that
        # hold fewer, and somebody visits every glyph on screen and comes up short.
        totes=[SeasonalTote(id=t.id, code=t.code, color_hex=color_hex(t.color)) for t in totes[:6]],
        tote_count=len(totes),
        location_name=location_name,
        # The EARLIEST qualifying date — when the unpacking STARTED last year.
        unpacked_on=min(first for _, first in rows),
        item_count=item_count,
        category_name=category_name,
    )


class _Candidate(NamedTuple):
    """One person's answer, carried whole so the query that counted it can be rebuilt exactly.

    A plain tuple here grew to seven positions and was unpacked in a different order than it was
    packed — the kind of thing that type-checks and quietly puts a person's name in a uuid.
    """

    count: int
    person_name: str
    person_id: uuid.UUID
    rung: SizeReading
    band: tuple[float, float]
    systems: list[str]
    floor: float


def _stored_at_rung(
    household_id: uuid.UUID, systems: list[str], band: tuple[float, float], floor: float
) -> tuple:
    """Garments at ONE rung of the ladder, and actually in a bin.

    `size_system.in_(systems)` is the comparability gate, built by fits' `_systems_for` so a
    shoe reading can never match a sweater however close the shared axis puts them. And UNLIKE
    `fits_query` — which deliberately reports garments wherever they are — this filters
    `status == 'stored'`: the card's promise is "already waiting in a bin", and a garment that
    is lent out, unpacked or disposed of is not.

    `band` is `rung_band`'s half-open `[lo, hi)`, so the count covers exactly the rung the card
    is about to name. It replaced a fixed `±0.5` that only behaved on the coarse ladders — see
    `rung_band`, which carries the measurements.

    `floor` is the wearer's CURRENT ordinal. Redundant now that the band starts above their rung,
    and kept because it says out loud that this card never counts the clothes already on their
    back — a promise worth one cheap comparison.
    """
    lo, hi = band
    return (
        Item.household_id == household_id,
        Item.is_draft.is_(False),
        Item.status == "stored",
        ItemApparel.size_system.in_(systems),
        ItemApparel.size_ordinal > floor,
        ItemApparel.size_ordinal >= lo,
        ItemApparel.size_ordinal < hi,
    )


async def next_size_card(db: AsyncSession, household_id: uuid.UUID) -> NextSizeCard | None:
    """A person is nearing the next size band and the catalogue already holds garments in it.

    Built on the recorded size history and the ladder's `next_size_up`, never on age guesses —
    `Person.birthdate` plays no part. Each parsed current size yields candidates from the next
    few rungs up WITHIN its own system (crossing systems is the ladder caller's explicit,
    labelled decision, and an unprompted card is no place for an approximate claim), and the
    **nearest rung the catalogue actually holds something in** wins for that person. The
    candidate holding the most stored garments wins overall; ties go to the alphabetically first
    person, so the card is stable between requests.

    **Looking past the very next rung is the fix for a real failure.** The card used to name the
    next rung unconditionally and count a fixed ±0.5 around it. On the infant ladder that band is
    about four rungs wide, so in production it announced "9-12M · 58 garments" for a household
    that owned nothing at 9-12M — the 58 were 54 tagged `12M` and 4 tagged `12-18M`. Naming a
    rung with nothing in it is not merely unhelpful, it is what forced the band to be wide enough
    to find something to count.
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
    best: _Candidate | None = None
    for person in people:
        sizes = await current_sizes(db, person.id)
        for garment_type, size in sizes.items():
            if size.size_system is None or size.size_ordinal is None:
                # Recorded but unplaceable ("5TT"): no ordinal, no next rung, never a guess.
                continue
            current = SizeReading(size.size_raw, size.size_system, size.size_ordinal)
            for rung in next_sizes_up(current, _LOOKAHEAD_RUNGS):
                if rung.ordinal - current.ordinal > _LOOKAHEAD_ORDINAL:
                    # Two rungs of `toddler` is two years of a child. Not "nearly".
                    break
                band = rung_band(rung.system, rung.ordinal)
                if band is None:
                    # A formula-based system has no rungs to bound a band with.
                    continue
                systems = _systems_for(garment_type, rung.system)
                if not systems:
                    continue
                count = (
                    await db.execute(
                        select(func.count())
                        .select_from(Item)
                        .join(ItemApparel, ItemApparel.item_id == Item.id)
                        .where(*_stored_at_rung(household_id, systems, band, current.ordinal))
                    )
                ).scalar_one()
                if count == 0:
                    # Nothing at this rung, so it is not worth naming — try the next one up.
                    continue
                candidate = _Candidate(
                    count, person.name, person.id, rung, band, systems, current.ordinal
                )
                if (
                    best is None
                    or count > best.count
                    or (count == best.count and person.name < best.person_name)
                ):
                    best = candidate
                # The NEAREST rung with anything in it is this person's answer; a further one
                # holding more garments is a later trip, not this one.
                break
    if best is None:
        return None

    where = _stored_at_rung(household_id, best.systems, best.band, best.floor)

    # Where to go: the bins holding those garments, most first. Three is enough to say where,
    # and `stored` guarantees every counted garment IS in one of them. `tote_count` is the whole
    # number of them, because the count above spans every bin while this list is capped —
    # without it the card showed three swatches beside a number covering seven.
    held = func.count().label("held")
    rows = (
        await db.execute(
            select(Tote.id, Tote.code, Tote.color, held)
            .select_from(Item)
            .join(ItemApparel, ItemApparel.item_id == Item.id)
            .join(Tote, Item.current_tote_id == Tote.id)
            .where(*where)
            .group_by(Tote.id)
            .order_by(held.desc(), Tote.code)
        )
    ).all()

    # The label is the garments' OWN most common tag, not the ladder's table key.
    #
    # One rung has several spellings — `12-18M` and `15M` are the same ordinal — so a card can
    # name a rung correctly and still print words that appear on nothing in the bin. Drawing the
    # label from the rows just counted makes "named a size you own nothing in" structurally
    # impossible: the label and the number come from one set. It falls back to the rung key when
    # the reading is absent or long enough to be a description ("Heather Grey / M/L").
    raw_rows = (
        await db.execute(
            select(ItemApparel.size_raw, func.count().label("n"))
            .select_from(Item)
            .join(ItemApparel, ItemApparel.item_id == Item.id)
            .where(*where, ItemApparel.size_raw.is_not(None))
            .group_by(ItemApparel.size_raw)
            .order_by(func.count().desc(), ItemApparel.size_raw)
        )
    ).all()
    modal = next(
        (raw for raw, _ in raw_rows if raw and len(raw) <= _MAX_LABEL_LENGTH),
        None,
    )

    return NextSizeCard(
        person_id=best.person_id,
        person_name=best.person_name,
        next_label=modal or best.rung.raw,
        garment_count=best.count,
        totes=[
            SeasonalTote(id=i, code=code, color_hex=color_hex(color))
            for i, code, color, _ in rows[:3]
        ],
        tote_count=len(rows),
    )
