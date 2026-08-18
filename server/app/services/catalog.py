"""Read-side helpers: the joins and counts that turn rows into what a screen needs.

These live here rather than in the routers so that "which bin, and where is it" is answered one
way. A search hit, a tote's contents and an item's detail page all want the same three
denormalised facts, and computing them three times is how they end up disagreeing.
"""

import datetime
import uuid
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from sqlalchemy import Select, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.models.item import Item, ItemApparel, ItemPhoto
from app.models.location import Location
from app.models.movement import Movement
from app.models.person import Person
from app.models.tote import Tote
from app.schemas.catalog import ApparelOut, ItemOut, ToteOut
from app.sizing import SIZE_SYSTEMS, comparable, parse_size


def local_today() -> datetime.date:
    """Today, in the household's timezone.

    `date.today()` would use the process timezone, which in the container is UTC while the
    house is US Eastern — so a loan due today would be reported overdue from 7pm local. An
    unrecognised zone degrades to UTC rather than raising: an endpoint that 500s is worse than
    a nudge that is a few hours eager.
    """
    try:
        tz = ZoneInfo(settings.local_timezone)
    except (ZoneInfoNotFoundError, ValueError):
        tz = datetime.UTC
    return datetime.datetime.now(tz).date()


def _photo_count():
    """How many photos an item has, as a correlated scalar subquery.

    A subquery rather than a second round trip because every read path that shows items shows
    lists of them — a per-row query would be an N+1 on the screen someone opens while standing in
    front of an open bin. The client needs the number (not the rows) only to decide whether to
    draw a thumbnail and whether to say "3 photos".
    """
    return (
        select(func.count(ItemPhoto.id))
        .where(ItemPhoto.item_id == Item.id)
        .correlate(Item)
        .scalar_subquery()
        .label("photo_count")
    )


def item_query(household_id: uuid.UUID) -> Select:
    """Items with their tote code and location name attached.

    Outer joins throughout: an item with no tote is a normal state (out for the holidays, lent),
    and a tote with no location is a bin that has not been put anywhere yet. An inner join here
    would silently hide exactly the items a user is most likely to be hunting for.
    """
    return (
        select(Item, Tote.code, Location.name, _photo_count())
        .outerjoin(Tote, Item.current_tote_id == Tote.id)
        .outerjoin(Location, Tote.location_id == Location.id)
        .where(Item.household_id == household_id)
        # Drafts are excluded EVERYWHERE this query is used — search, tote contents, item lists.
        # The house rule is that nothing AI-generated enters the catalog without explicit
        # approval, and a draft turning up in search results would be exactly that. The review
        # stack queries drafts explicitly instead.
        .where(Item.is_draft.is_(False))
    )


def apply_size_filter(query: Select, raw: str, tolerance: float = 1.0) -> Select:
    """Narrow a query to items that are approximately this size.

    Matches on the ORDINAL, not the string, so "4T" also finds a garment whose tag read "4"
    under a girls department — which is the whole point of having an index. Two rules keep it
    honest:

    * **Only within comparable lineages.** A men's waist never matches a toddler size, however
      close the numbers land on the shared axis.
    * **An unparseable filter falls back to matching `size_raw` textually** rather than returning
      nothing. Someone typing "M/L" into the box means it literally, and an empty result would
      read as "you own none of these" when the truth is "we could not index that".
    """
    reading = parse_size(raw)
    if reading is None:
        return query.where(ItemApparel.size_raw.ilike(f"%{raw.strip()}%"))
    systems = [s for s in SIZE_SYSTEMS if comparable(s, reading.system)]
    return query.where(
        ItemApparel.size_system.in_(systems),
        ItemApparel.size_ordinal.between(reading.ordinal - tolerance, reading.ordinal + tolerance),
    )


def to_item_out(
    item: Item,
    tote_code: str | None,
    location_name: str | None,
    photo_count: int = 0,
) -> ItemOut:
    out = ItemOut.model_validate(item)
    # Only present for clothing, and only when the relationship was actually loaded. Reading it
    # off an unloaded lazy attribute inside an async request would raise MissingGreenlet, so
    # callers that want apparel must eager-load it (see item_query's selectinload).
    # Populated by the relationship's own `lazy="selectin"` (see models/item.py) rather than an
    # `.options()` here, so a future read path cannot omit it and reintroduce MissingGreenlet.
    out.apparel = ApparelOut.model_validate(item.apparel) if item.apparel is not None else None
    out.tote_code = tote_code
    out.location_name = location_name
    out.photo_count = photo_count
    # Overdue is computed here, once, so a notification and a screen cannot disagree about it.
    out.is_overdue = bool(
        item.expected_back
        and item.status in ("out", "loaned")
        and item.expected_back < local_today()
    )
    return out


async def items_for(db: AsyncSession, query: Select) -> list[ItemOut]:
    rows = (await db.execute(query)).all()
    out = [to_item_out(item, code, loc, photos) for item, code, loc, photos in rows]
    await attach_borrowers(db, out)
    return out


async def attach_borrowers(db: AsyncSession, items: list[ItemOut]) -> None:
    """Fill in `loaned_to` for whichever of these items are on loan.

    ONE query for the whole page, not one per row: "who has it" appears on every list, and a
    per-item lookup would make the ledger join quietly quadratic on the screen people use most.

    The borrower comes from the newest `loaned` movement, because the item row knows only that it
    is out — which is the whole reason lending needs the ledger to be answerable at all.
    """
    loaned = [i for i in items if i.status == "loaned"]
    if not loaned:
        return

    newest = (
        select(Movement.item_id, func.max(Movement.moved_at).label("at"))
        .where(Movement.item_id.in_([i.id for i in loaned]), Movement.reason == "loaned")
        .group_by(Movement.item_id)
        .subquery()
    )
    rows = (
        await db.execute(
            select(Movement.item_id, Person.name)
            .join(
                newest, (Movement.item_id == newest.c.item_id) & (Movement.moved_at == newest.c.at)
            )
            .join(Person, Person.id == Movement.person_id)
            .where(Movement.reason == "loaned")
        )
    ).all()
    by_item = {item_id: name for item_id, name in rows}
    for item in loaned:
        item.loaned_to = by_item.get(item.id)


async def tote_counts(db: AsyncSession, household_id: uuid.UUID) -> dict[uuid.UUID, int]:
    """items currently in each tote, computed rather than stored."""
    rows = (
        await db.execute(
            select(Item.current_tote_id, func.count())
            .where(
                Item.household_id == household_id,
                Item.current_tote_id.is_not(None),
                Item.is_draft.is_(False),
            )
            .group_by(Item.current_tote_id)
        )
    ).all()
    return {tote_id: n for tote_id, n in rows}


async def out_counts(db: AsyncSession, household_id: uuid.UUID) -> dict[uuid.UUID, int]:
    """Per tote: how many items left it and have not come back.

    Derived from the ledger rather than from a column, using each item's LATEST movement. That
    is what makes "I thought the lights were in here" answerable instead of merely mysterious.
    """
    latest = (
        select(Movement.item_id, Movement.from_tote_id)
        .join(Item, Item.id == Movement.item_id)
        .where(
            Item.household_id == household_id,
            Item.current_tote_id.is_(None),
            Item.status != "disposed",
        )
        .order_by(Movement.item_id, Movement.moved_at.desc(), Movement.created_at.desc())
        .distinct(Movement.item_id)
        .subquery()
    )
    rows = (
        await db.execute(
            select(latest.c.from_tote_id, func.count())
            .where(latest.c.from_tote_id.is_not(None))
            .group_by(latest.c.from_tote_id)
        )
    ).all()
    return {tote_id: n for tote_id, n in rows}


async def location_names(db: AsyncSession, household_id: uuid.UUID) -> dict[uuid.UUID, str]:
    """Every location this household has, by id.

    Fetched whole rather than joined per tote for the same reason the counts are: the list
    endpoint backs the browse-by-location screen, and a household has a handful of locations
    against however many bins. One small query beats a join repeated per row.
    """
    rows = (
        await db.execute(
            select(Location.id, Location.name).where(Location.household_id == household_id)
        )
    ).all()
    return {location_id: name for location_id, name in rows}


async def to_tote_out(
    db: AsyncSession,
    tote: Tote,
    counts: dict | None = None,
    outs: dict | None = None,
    locations: dict | None = None,
) -> ToteOut:
    counts = counts if counts is not None else await tote_counts(db, tote.household_id)
    outs = outs if outs is not None else await out_counts(db, tote.household_id)
    locations = locations if locations is not None else await location_names(db, tote.household_id)
    out = ToteOut.model_validate(tote)
    out.item_count = counts.get(tote.id, 0)
    out.out_count = outs.get(tote.id, 0)
    # Denormalised for the same reason `ItemOut.location_name` is: "A14" alone does not answer
    # "where do I go", and every screen that shows a bin wants the place it is in. Sent here so
    # the client never has to hold a locations table alongside the bins to read one line.
    out.location_name = locations.get(tote.location_id) if tote.location_id else None
    return out
