"""Items, their movements, and search — the app's primary query path."""

import uuid
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models.category import Category
from app.models.container import Container
from app.models.item import Item, ItemApparel
from app.models.movement import Movement
from app.models.tote import Tote
from app.schemas.catalog import (
    BulkBagIn,
    BulkRelocateIn,
    ItemIn,
    ItemOut,
    ItemPatch,
    MoveIn,
    MovementOut,
    SearchHit,
)
from app.security import CurrentUser
from app.services import photo_store
from app.services.apparel_write import apply_apparel
from app.services.catalog import apply_size_filter, item_query, items_for, to_item_out
from app.services.movement import inbound_reason_for, record_move

router = APIRouter(tags=["items"])

Db = Annotated[AsyncSession, Depends(get_db)]


async def _owned_item(db: AsyncSession, household_id: uuid.UUID, item_id: uuid.UUID) -> Item:
    item = (
        await db.execute(select(Item).where(Item.id == item_id, Item.household_id == household_id))
    ).scalar_one_or_none()
    if item is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Item not found")
    return item


async def _one(db: AsyncSession, household_id: uuid.UUID, item_id: uuid.UUID) -> ItemOut:
    row = (await db.execute(item_query(household_id).where(Item.id == item_id))).one_or_none()
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
    size: str | None = Query(default=None, description="e.g. 4T, 6X, W8 — matched approximately"),
    limit: int = Query(default=200, le=500),
    offset: int = Query(default=0, ge=0),
):
    query = item_query(user.household_id)
    if tote_id:
        query = query.where(Item.current_tote_id == tote_id)
    if category_id:
        query = query.where(Item.category_id == category_id)
    if item_status:
        query = query.where(Item.status == item_status)
    if size:
        # An inner join, unlike every other join in item_query: filtering by size means the
        # caller only wants things that HAVE a size, so an item with no apparel row is correctly
        # absent rather than being swept in by a null.
        query = query.join(ItemApparel, ItemApparel.item_id == Item.id)
        query = apply_size_filter(query, size)
    return await items_for(db, query.order_by(Item.name).limit(limit).offset(offset))


@router.post("/items", response_model=ItemOut, status_code=status.HTTP_201_CREATED)
async def create_item(body: ItemIn, user: CurrentUser, db: Db):
    data = body.model_dump()
    tote_id = data.pop("tote_id")
    if data.get("category_id"):
        found = (
            await db.execute(
                select(Category).where(
                    Category.id == data["category_id"], Category.household_id == user.household_id
                )
            )
        ).scalar_one_or_none()
        if found is None:
            raise HTTPException(status.HTTP_404_NOT_FOUND, "Category not found")

    item = Item(user_id=user.id, household_id=user.household_id, **data)
    db.add(item)
    await db.flush()

    if tote_id:
        # Filing an item is a movement like any other, so it gets an `initial` ledger row. An
        # item that appeared in a bin with no history would be the first hole in the ledger.
        await record_move(
            db, item=item, reason="initial", to_tote_id=tote_id, moved_by_user_id=user.id
        )
    else:
        # And so is not filing it. This used to set the two fields by hand, which left an item
        # with NO ledger row at all — the hole the branch above exists to prevent, dug by the
        # other branch — and stamped `other` where the same state reached through review's
        # confirm-without-a-bin is `unfiled`. Two ways into one state that disagreed about what
        # the state was, which is why filing one of them later read as "it came back".
        await record_move(db, item=item, reason="catalogued", moved_by_user_id=user.id)

    await db.commit()
    return await _one(db, user.household_id, item.id)


@router.get("/items/{item_id}", response_model=ItemOut)
async def get_item(item_id: uuid.UUID, user: CurrentUser, db: Db):
    return await _one(db, user.household_id, item_id)


@router.patch("/items/{item_id}", response_model=ItemOut)
async def patch_item(item_id: uuid.UUID, body: ItemPatch, user: CurrentUser, db: Db):
    """Edits the item's own attributes only.

    Whereabouts is deliberately NOT patchable here: `current_tote_id` and `status` are derived
    from the ledger and have exactly one writer. Moving an item goes through POST
    /items/{id}/move so it always leaves a trace.
    """
    item = await _owned_item(db, user.household_id, item_id)
    updates = body.model_dump(exclude_unset=True)
    if updates.get("category_id"):
        found = (
            await db.execute(
                select(Category).where(
                    Category.id == updates["category_id"],
                    Category.household_id == user.household_id,
                )
            )
        ).scalar_one_or_none()
        if found is None:
            raise HTTPException(status.HTTP_404_NOT_FOUND, "Category not found")
    if updates.get("container_id"):
        # Against the item's CURRENT tote, not merely against ownership. A bag in another bin
        # would be exactly the contradiction the container model refuses to allow: the item would
        # claim membership of a grouping inside a bin it is not in.
        found = (
            await db.execute(
                select(Container).where(
                    Container.id == updates["container_id"],
                    Container.household_id == user.household_id,
                    Container.tote_id == item.current_tote_id,
                )
            )
        ).scalar_one_or_none()
        if found is None:
            raise HTTPException(
                status.HTTP_422_UNPROCESSABLE_ENTITY,
                "That bag is not in the tote this item is in",
            )

    apparel_updates = updates.pop("apparel", None)
    for k, v in updates.items():
        setattr(item, k, v)
    if apparel_updates is not None:
        await apply_apparel(db, item, apparel_updates)
    await db.commit()
    return await _one(db, user.household_id, item_id)


@router.post("/items/bulk-move", response_model=list[MovementOut])
async def bulk_move(body: BulkRelocateIn, user: CurrentUser, db: Db):
    """Move a selection of items into one bin, in ONE transaction.

    One ledger row each, written by `record_move` like every other relocation — a bulk operation
    is a convenience for the person, never a shortcut past the single writer of derived state.
    And one transaction rather than N requests, for the reason `record_move` does not commit:
    forty items moved individually is forty chances to half-succeed, leaving a selection the
    person believes is together and is not.

    The reason is matched to each item by `inbound_reason_for` — `moved` for something already
    stored, `returned` for something a person had, `repacked` for anything else that is out. A
    year later "it changed bins", "it came back" and "Dave gave it back" are different facts, and
    the last of those is the only record that a loan ever ended.
    """
    tote = (
        await db.execute(
            select(Tote).where(Tote.id == body.to_tote_id, Tote.household_id == user.household_id)
        )
    ).scalar_one_or_none()
    if tote is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Tote not found")

    container = None
    if body.container_id is not None:
        container = (
            await db.execute(
                select(Container).where(
                    Container.id == body.container_id,
                    Container.household_id == user.household_id,
                    # Against the DESTINATION, not where the items are now: a bag in any other
                    # bin is the contradiction the container model exists to prevent.
                    Container.tote_id == body.to_tote_id,
                )
            )
        ).scalar_one_or_none()
        if container is None:
            raise HTTPException(
                status.HTTP_422_UNPROCESSABLE_ENTITY,
                "That bag is not in the tote these items are going to",
            )

    items = (
        (
            await db.execute(
                select(Item).where(
                    Item.id.in_(body.item_ids),
                    Item.household_id == user.household_id,
                    Item.is_draft.is_(False),
                )
            )
        )
        .scalars()
        .all()
    )
    if len(items) != len(set(body.item_ids)):
        # All or nothing. A partial move would leave the person believing a selection is
        # together when some of it never went.
        raise HTTPException(status.HTTP_404_NOT_FOUND, "One or more items not found")

    moves = []
    for item in items:
        moves.append(
            await record_move(
                db,
                item=item,
                reason=inbound_reason_for(item.status, item.out_reason),
                to_tote_id=body.to_tote_id,
                note=body.note,
                moved_by_user_id=user.id,
            )
        )
        # After the move, because `record_move` clears container_id on the way in — the
        # destination's bags are not the source's.
        if container is not None:
            item.container_id = container.id
    await db.commit()
    return [MovementOut.model_validate(m) for m in moves]


@router.post("/items/bulk-bag", status_code=status.HTTP_204_NO_CONTENT)
async def bulk_bag(body: BulkBagIn, user: CurrentUser, db: Db):
    """Put a selection into a bag, or take them out of one.

    NOT a movement and deliberately no ledger rows: the items do not change bin. Which bag a
    thing sits in inside a tote is a label, and relabelling is not a whereabouts event — writing
    one would fill the history someone reads for "where was this last year" with noise.

    Every item must already be in the bag's tote. That is the same rule the single-item PATCH
    enforces, and it is the only way this could ever produce a container that lies.
    """
    container = None
    if body.container_id is not None:
        container = (
            await db.execute(
                select(Container).where(
                    Container.id == body.container_id, Container.household_id == user.household_id
                )
            )
        ).scalar_one_or_none()
        if container is None:
            raise HTTPException(status.HTTP_404_NOT_FOUND, "Container not found")

    items = (
        (
            await db.execute(
                select(Item).where(
                    Item.id.in_(body.item_ids),
                    Item.household_id == user.household_id,
                    Item.is_draft.is_(False),
                )
            )
        )
        .scalars()
        .all()
    )
    if len(items) != len(set(body.item_ids)):
        raise HTTPException(status.HTTP_404_NOT_FOUND, "One or more items not found")

    for item in items:
        if container is not None and item.current_tote_id != container.tote_id:
            raise HTTPException(
                status.HTTP_422_UNPROCESSABLE_ENTITY,
                "That bag is not in the tote these items are in",
            )
        item.container_id = container.id if container is not None else None
    await db.commit()


@router.delete("/items/{item_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_item(item_id: uuid.UUID, user: CurrentUser, db: Db):
    """Hard delete, cascading the ledger with it.

    For "we no longer own this", the right operation is a `disposed` movement, which keeps the
    history. Delete is for a row created by mistake.
    """
    item = await _owned_item(db, user.household_id, item_id)
    await db.delete(item)
    await db.commit()
    # The rows cascade; the FILES do not. Until this call existed, deleting an item left its
    # photographs on the volume forever — invisible, un-listed, and counted by nothing except
    # the backup that dutifully archived them every night. Done after the commit so a failed
    # delete never destroys the one artefact that cannot be recreated.
    photo_store.delete_item_photos(item_id)


@router.post("/items/{item_id}/move", response_model=MovementOut)
async def move_item(item_id: uuid.UUID, body: MoveIn, user: CurrentUser, db: Db):
    item = await _owned_item(db, user.household_id, item_id)
    movement = await record_move(
        db,
        item=item,
        reason=body.reason,
        to_tote_id=body.to_tote_id,
        person_id=body.person_id,
        note=body.note,
        moved_by_user_id=user.id,
        expected_back=body.expected_back,
        moved_at=body.moved_at,
    )
    await db.commit()
    await db.refresh(movement)
    return MovementOut.model_validate(movement)


@router.get("/items/{item_id}/movements", response_model=list[MovementOut])
async def item_movements(item_id: uuid.UUID, user: CurrentUser, db: Db):
    """The item's whole history, newest first — "where was this last year"."""
    await _owned_item(db, user.household_id, item_id)
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
            item_query(user.household_id)
            .add_columns(rank)
            .where(Item.search_vector.op("@@")(tsquery))
            .order_by(rank.desc(), Item.name)
            .limit(limit)
        )
    ).all()
    return [
        SearchHit(item=to_item_out(item, code, loc, photos), rank=float(r))
        for item, code, loc, photos, r in rows
    ]
