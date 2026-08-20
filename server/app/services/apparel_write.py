"""The one writer of `item_apparel`, shared by the PATCH path and draft confirmation.

One implementation on purpose. Both callers must re-derive the size index from `size_raw`, and
two copies of that rule is how one of them ends up not doing it.
"""

from sqlalchemy.ext.asyncio import AsyncSession

from app.models.item import Item, ItemApparel
from app.services.apparel_draft import SIZE_TYPE_OF_SYSTEM
from app.sizing import parse_size


async def apply_apparel(db: AsyncSession, item: Item, updates: dict) -> None:
    """Merge a human's clothing edits, re-deriving the size index from the raw string.

    Two rules, both of which exist so the stored index can never disagree with the reading it
    indexes:

    * **`size_system`/`size_ordinal` are never accepted from a client.** They are recomputed from
      `size_raw` here. A client that could set them directly could store "4T" indexed as an adult
      L, and nothing downstream would ever catch it.
    * **A cleared `size_raw` clears the index with it**, rather than leaving a stale ordinal
      pointing at a size nobody can see any more.

    `size_type` is left alone when the caller set it explicitly (it is a strict enum on this path
    — a person is making a claim), and otherwise re-derived alongside the rest.
    """
    # Assigned THROUGH the relationship, never `db.add`-ed standalone. The relationship carries
    # `delete-orphan`, so a row whose parent's `apparel` attribute still reads None is an orphan
    # by definition and SQLAlchemy deletes it again on flush — the write appears to succeed and
    # the field is silently empty on the very next read.
    row = item.apparel
    if row is None:
        row = ItemApparel(item_id=item.id)
        item.apparel = row

    for k, v in updates.items():
        setattr(row, k, v)

    # Re-derived when EITHER the reading or the department it is read against changes.
    #
    # It used to key on `size_raw` alone, which made a correction silently ineffective: the
    # department disambiguates a bare number (a tag reading `8` is a youth 8 or a women's 8),
    # and it arrives from the MODEL on the scan path, where it is routinely wrong — production
    # has `mens` and `womens` on 12-month onesies. Somebody spotting that at review and fixing
    # the chip had their correction accepted and the derived ordinal left alone, so the garment
    # stayed indexed as a women's 8 and "what fits Emma" went on missing it.
    if "size_raw" in updates or "department" in updates:
        reading = parse_size(row.size_raw, department=row.department)
        row.size_system = reading.system if reading else None
        row.size_ordinal = reading.ordinal if reading else None
        if "size_type" not in updates:
            row.size_type = SIZE_TYPE_OF_SYSTEM.get(reading.system) if reading else None
