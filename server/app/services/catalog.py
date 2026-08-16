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
from app.models.item import Item
from app.models.location import Location
from app.models.movement import Movement
from app.models.tote import Tote
from app.schemas.catalog import ItemOut, ToteOut


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


def item_query(user_id: uuid.UUID) -> Select:
    """Items with their tote code and location name attached.

    Outer joins throughout: an item with no tote is a normal state (out for the holidays, lent),
    and a tote with no location is a bin that has not been put anywhere yet. An inner join here
    would silently hide exactly the items a user is most likely to be hunting for.
    """
    return (
        select(Item, Tote.code, Location.name)
        .outerjoin(Tote, Item.current_tote_id == Tote.id)
        .outerjoin(Location, Tote.location_id == Location.id)
        .where(Item.user_id == user_id)
        # Drafts are excluded EVERYWHERE this query is used — search, tote contents, item lists.
        # The house rule is that nothing AI-generated enters the catalog without explicit
        # approval, and a draft turning up in search results would be exactly that. The review
        # stack queries drafts explicitly instead.
        .where(Item.is_draft.is_(False))
    )


def to_item_out(item: Item, tote_code: str | None, location_name: str | None) -> ItemOut:
    out = ItemOut.model_validate(item)
    out.tote_code = tote_code
    out.location_name = location_name
    # Overdue is computed here, once, so a notification and a screen cannot disagree about it.
    out.is_overdue = bool(
        item.expected_back
        and item.status in ("out", "loaned")
        and item.expected_back < local_today()
    )
    return out


async def items_for(db: AsyncSession, query: Select) -> list[ItemOut]:
    rows = (await db.execute(query)).all()
    return [to_item_out(item, code, loc) for item, code, loc in rows]


async def tote_counts(db: AsyncSession, user_id: uuid.UUID) -> dict[uuid.UUID, int]:
    """items currently in each tote, computed rather than stored."""
    rows = (
        await db.execute(
            select(Item.current_tote_id, func.count())
            .where(
                Item.user_id == user_id,
                Item.current_tote_id.is_not(None),
                Item.is_draft.is_(False),
            )
            .group_by(Item.current_tote_id)
        )
    ).all()
    return {tote_id: n for tote_id, n in rows}


async def out_counts(db: AsyncSession, user_id: uuid.UUID) -> dict[uuid.UUID, int]:
    """Per tote: how many items left it and have not come back.

    Derived from the ledger rather than from a column, using each item's LATEST movement. That
    is what makes "I thought the lights were in here" answerable instead of merely mysterious.
    """
    latest = (
        select(Movement.item_id, Movement.from_tote_id)
        .join(Item, Item.id == Movement.item_id)
        .where(Item.user_id == user_id, Item.current_tote_id.is_(None), Item.status != "disposed")
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


async def to_tote_out(
    db: AsyncSession, tote: Tote, counts: dict | None = None, outs: dict | None = None
) -> ToteOut:
    counts = counts if counts is not None else await tote_counts(db, tote.user_id)
    outs = outs if outs is not None else await out_counts(db, tote.user_id)
    out = ToteOut.model_validate(tote)
    out.item_count = counts.get(tote.id, 0)
    out.out_count = outs.get(tote.id, 0)
    return out
