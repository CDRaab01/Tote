"""Bags inside a tote — one level of grouping within a bin.

Every route hangs off `/totes/{tote_id}` rather than a flat `/containers`, and that is the model
speaking: a bag cannot exist except inside the bin it belongs to, and there is no operation that
moves one between bins. See `models/container.py` for why — a container carrying its own
whereabouts would give this app two answers to the one question it exists to answer.
"""

import uuid
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models.container import Container
from app.models.item import Item
from app.models.tote import Tote
from app.schemas.catalog import ContainerIn, ContainerOut, ContainerPatch
from app.security import CurrentUser

router = APIRouter(tags=["containers"])

Db = Annotated[AsyncSession, Depends(get_db)]


async def _owned_tote(db: Db, household_id: uuid.UUID, tote_id: uuid.UUID) -> Tote:
    tote = (
        await db.execute(select(Tote).where(Tote.id == tote_id, Tote.household_id == household_id))
    ).scalar_one_or_none()
    if tote is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Tote not found")
    return tote


async def _owned_container(
    db: Db, household_id: uuid.UUID, tote_id: uuid.UUID, container_id: uuid.UUID
) -> Container:
    await _owned_tote(db, household_id, tote_id)
    container = (
        await db.execute(
            select(Container).where(
                Container.id == container_id,
                Container.tote_id == tote_id,
                Container.household_id == household_id,
            )
        )
    ).scalar_one_or_none()
    if container is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Container not found")
    return container


async def container_counts(db: Db, tote_id: uuid.UUID) -> dict[uuid.UUID, int]:
    """How many items are in each bag of this tote, in one query.

    Computed, never stored, for the same reason `totes.item_count` is: a denormalised count is
    the first thing to drift, and this one is read while somebody is holding the bag open.
    """
    rows = (
        await db.execute(
            select(Item.container_id, func.count())
            .where(
                Item.current_tote_id == tote_id,
                Item.container_id.is_not(None),
                Item.is_draft.is_(False),
            )
            .group_by(Item.container_id)
        )
    ).all()
    return {container_id: n for container_id, n in rows}


async def containers_for(db: Db, household_id: uuid.UUID, tote_id: uuid.UUID) -> list[ContainerOut]:
    rows = (
        (
            await db.execute(
                select(Container)
                .where(Container.tote_id == tote_id, Container.household_id == household_id)
                .order_by(Container.name)
            )
        )
        .scalars()
        .all()
    )
    counts = await container_counts(db, tote_id)
    out = []
    for row in rows:
        model = ContainerOut.model_validate(row)
        model.item_count = counts.get(row.id, 0)
        out.append(model)
    return out


@router.get("/totes/{tote_id}/containers", response_model=list[ContainerOut])
async def list_containers(tote_id: uuid.UUID, user: CurrentUser, db: Db):
    await _owned_tote(db, user.household_id, tote_id)
    return await containers_for(db, user.household_id, tote_id)


@router.post(
    "/totes/{tote_id}/containers",
    response_model=ContainerOut,
    status_code=status.HTTP_201_CREATED,
)
async def create_container(tote_id: uuid.UUID, body: ContainerIn, user: CurrentUser, db: Db):
    await _owned_tote(db, user.household_id, tote_id)
    container = Container(
        user_id=user.id,
        household_id=user.household_id,
        tote_id=tote_id,
        name=body.name.strip(),
        notes=body.notes,
    )
    db.add(container)
    await db.commit()
    await db.refresh(container)
    # Freshly created, so it holds nothing. Stated rather than counted.
    return ContainerOut.model_validate(container)


@router.patch("/totes/{tote_id}/containers/{container_id}", response_model=ContainerOut)
async def patch_container(
    tote_id: uuid.UUID,
    container_id: uuid.UUID,
    body: ContainerPatch,
    user: CurrentUser,
    db: Db,
):
    """Rename a bag or change what it says it holds. Deliberately cannot move it between bins."""
    container = await _owned_container(db, user.household_id, tote_id, container_id)
    updates = body.model_dump(exclude_unset=True)
    if updates.get("name"):
        updates["name"] = updates["name"].strip()
    for key, value in updates.items():
        setattr(container, key, value)
    await db.commit()
    await db.refresh(container)
    counts = await container_counts(db, tote_id)
    out = ContainerOut.model_validate(container)
    out.item_count = counts.get(container.id, 0)
    return out


@router.delete("/totes/{tote_id}/containers/{container_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_container(tote_id: uuid.UUID, container_id: uuid.UUID, user: CurrentUser, db: Db):
    """Undo the grouping, never the contents.

    `items.container_id` is `ON DELETE SET NULL`, so the items stay exactly where they are — in
    this bin, loose. That is the same promise `current_tote_id` makes when a tote is deleted, and
    it is the reason emptying a bag is a safe thing to do without a confirmation dialog.
    """
    container = await _owned_container(db, user.household_id, tote_id, container_id)
    await db.delete(container)
    await db.commit()
