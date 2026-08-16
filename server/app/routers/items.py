"""Items, their movements, and search — the app's primary query path."""

import uuid
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models.category import Category
from app.models.item import Item, ItemApparel
from app.models.movement import Movement
from app.schemas.catalog import ItemIn, ItemOut, ItemPatch, MoveIn, MovementOut, SearchHit
from app.security import CurrentUser
from app.services.apparel_draft import SIZE_TYPE_OF_SYSTEM
from app.services.catalog import item_query, items_for, to_item_out
from app.services.movement import record_move
from app.sizing import parse_size

router = APIRouter(tags=["items"])

Db = Annotated[AsyncSession, Depends(get_db)]


async def _owned_item(db: AsyncSession, user_id: uuid.UUID, item_id: uuid.UUID) -> Item:
    item = (
        await db.execute(select(Item).where(Item.id == item_id, Item.user_id == user_id))
    ).scalar_one_or_none()
    if item is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Item not found")
    return item


async def _one(db: AsyncSession, user_id: uuid.UUID, item_id: uuid.UUID) -> ItemOut:
    row = (await db.execute(item_query(user_id).where(Item.id == item_id))).one_or_none()
    if row is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Item not found")
    return to_item_out(*row)


@router.get("/items", response_model=list[ItemOut])
async def list_items(
    user: CurrentUser,
    db: Db,
    tote_id: uuid.UUID | None = None,
    category_id: uuid.UUID | None = None,
    item_status: str | None = Query(default=None, alias="status"),
    limit: int = Query(default=200, le=500),
    offset: int = Query(default=0, ge=0),
):
    query = item_query(user.id)
    if tote_id:
        query = query.where(Item.current_tote_id == tote_id)
    if category_id:
        query = query.where(Item.category_id == category_id)
    if item_status:
        query = query.where(Item.status == item_status)
    return await items_for(db, query.order_by(Item.name).limit(limit).offset(offset))


@router.post("/items", response_model=ItemOut, status_code=status.HTTP_201_CREATED)
async def create_item(body: ItemIn, user: CurrentUser, db: Db):
    data = body.model_dump()
    tote_id = data.pop("tote_id")
    if data.get("category_id"):
        found = (
            await db.execute(
                select(Category).where(
                    Category.id == data["category_id"], Category.user_id == user.id
                )
            )
        ).scalar_one_or_none()
        if found is None:
            raise HTTPException(status.HTTP_404_NOT_FOUND, "Category not found")

    item = Item(user_id=user.id, **data)
    db.add(item)
    await db.flush()

    if tote_id:
        # Filing an item is a movement like any other, so it gets an `initial` ledger row. An
        # item that appeared in a bin with no history would be the first hole in the ledger.
        await record_move(db, item=item, reason="initial", to_tote_id=tote_id)
    else:
        item.status = "out"
        item.out_reason = "other"

    await db.commit()
    return await _one(db, user.id, item.id)


@router.get("/items/{item_id}", response_model=ItemOut)
async def get_item(item_id: uuid.UUID, user: CurrentUser, db: Db):
    return await _one(db, user.id, item_id)


@router.patch("/items/{item_id}", response_model=ItemOut)
async def patch_item(item_id: uuid.UUID, body: ItemPatch, user: CurrentUser, db: Db):
    """Edits the item's own attributes only.

    Whereabouts is deliberately NOT patchable here: `current_tote_id` and `status` are derived
    from the ledger and have exactly one writer. Moving an item goes through POST
    /items/{id}/move so it always leaves a trace.
    """
    item = await _owned_item(db, user.id, item_id)
    updates = body.model_dump(exclude_unset=True)
    if updates.get("category_id"):
        found = (
            await db.execute(
                select(Category).where(
                    Category.id == updates["category_id"], Category.user_id == user.id
                )
            )
        ).scalar_one_or_none()
        if found is None:
            raise HTTPException(status.HTTP_404_NOT_FOUND, "Category not found")
    apparel_updates = updates.pop("apparel", None)
    for k, v in updates.items():
        setattr(item, k, v)
    if apparel_updates is not None:
        await _apply_apparel(db, item, apparel_updates)
    await db.commit()
    return await _one(db, user.id, item_id)


async def _apply_apparel(db: AsyncSession, item: Item, updates: dict) -> None:
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

    if "size_raw" in updates:
        reading = parse_size(row.size_raw, department=row.department)
        row.size_system = reading.system if reading else None
        row.size_ordinal = reading.ordinal if reading else None
        if "size_type" not in updates:
            row.size_type = SIZE_TYPE_OF_SYSTEM.get(reading.system) if reading else None


@router.delete("/items/{item_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_item(item_id: uuid.UUID, user: CurrentUser, db: Db):
    """Hard delete, cascading the ledger with it.

    For "we no longer own this", the right operation is a `disposed` movement, which keeps the
    history. Delete is for a row created by mistake.
    """
    item = await _owned_item(db, user.id, item_id)
    await db.delete(item)
    await db.commit()


@router.post("/items/{item_id}/move", response_model=MovementOut)
async def move_item(item_id: uuid.UUID, body: MoveIn, user: CurrentUser, db: Db):
    item = await _owned_item(db, user.id, item_id)
    movement = await record_move(
        db,
        item=item,
        reason=body.reason,
        to_tote_id=body.to_tote_id,
        person_id=body.person_id,
        note=body.note,
        expected_back=body.expected_back,
        moved_at=body.moved_at,
    )
    await db.commit()
    await db.refresh(movement)
    return MovementOut.model_validate(movement)


@router.get("/items/{item_id}/movements", response_model=list[MovementOut])
async def item_movements(item_id: uuid.UUID, user: CurrentUser, db: Db):
    """The item's whole history, newest first — "where was this last year"."""
    await _owned_item(db, user.id, item_id)
    rows = (
        (
            await db.execute(
                select(Movement)
                .where(Movement.item_id == item_id)
                .order_by(Movement.moved_at.desc(), Movement.created_at.desc())
            )
        )
        .scalars()
        .all()
    )
    return [MovementOut.model_validate(m) for m in rows]


@router.get("/search", response_model=list[SearchHit])
async def search(
    user: CurrentUser,
    db: Db,
    q: str = Query(min_length=1, max_length=120),
    limit: int = Query(default=50, le=200),
):
    """Full-text search over items — the app's primary entry point.

    `websearch_to_tsquery` rather than `plainto_tsquery`: it accepts quoted phrases and `or`
    without throwing on punctuation a person would naturally type. A query that matches nothing
    returns an empty list, never an error — "no results" is an answer.

    Results are ordered by rank then name, so ties are stable rather than arbitrary; an unstable
    order in a list someone is scanning reads as the app being broken.
    """
    tsquery = func.websearch_to_tsquery("english", q)
    rank = func.ts_rank(Item.search_vector, tsquery)
    rows = (
        await db.execute(
            item_query(user.id)
            .add_columns(rank)
            .where(Item.search_vector.op("@@")(tsquery))
            .order_by(rank.desc(), Item.name)
            .limit(limit)
        )
    ).all()
    return [
        SearchHit(item=to_item_out(item, code, loc), rank=float(r)) for item, code, loc, r in rows
    ]
