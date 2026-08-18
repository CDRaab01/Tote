"""Locations and categories — the two small vocabularies everything else hangs off."""

import uuid
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models.category import Category
from app.models.location import Location
from app.models.tote import Tote
from app.schemas.catalog import (
    CategoryIn,
    CategoryOut,
    CategoryPatch,
    LocationIn,
    LocationOut,
    LocationPatch,
)
from app.security import CurrentUser

router = APIRouter(tags=["catalog"])

Db = Annotated[AsyncSession, Depends(get_db)]


async def _owned(db: AsyncSession, model, household_id: uuid.UUID, obj_id: uuid.UUID):
    obj = (
        await db.execute(
            select(model).where(model.id == obj_id, model.household_id == household_id)
        )
    ).scalar_one_or_none()
    if obj is None:
        # 404 not 403: an authenticated user must not be able to probe which ids exist.
        raise HTTPException(status.HTTP_404_NOT_FOUND, f"{model.__name__} not found")
    return obj


# ── Locations ────────────────────────────────────────────────────────────────


@router.get("/locations", response_model=list[LocationOut])
async def list_locations(user: CurrentUser, db: Db):
    rows = (
        (
            await db.execute(
                select(Location)
                .where(Location.household_id == user.household_id)
                .order_by(Location.sort_order, Location.name)
            )
        )
        .scalars()
        .all()
    )
    return [LocationOut.model_validate(r) for r in rows]


@router.post("/locations", response_model=LocationOut, status_code=status.HTTP_201_CREATED)
async def create_location(body: LocationIn, user: CurrentUser, db: Db):
    if body.parent_id:
        await _owned(db, Location, user.household_id, body.parent_id)
    loc = Location(user_id=user.id, household_id=user.household_id, **body.model_dump())
    db.add(loc)
    await db.commit()
    await db.refresh(loc)
    return LocationOut.model_validate(loc)


@router.patch("/locations/{location_id}", response_model=LocationOut)
async def patch_location(location_id: uuid.UUID, body: LocationPatch, user: CurrentUser, db: Db):
    loc = await _owned(db, Location, user.household_id, location_id)
    # exclude_unset, NOT exclude_none: a null here is an instruction ("clear the parent"), and
    # kotlinx clients send explicit nulls. exclude_none would make clearing impossible.
    updates = body.model_dump(exclude_unset=True)
    if updates.get("parent_id") == location_id:
        raise HTTPException(
            status.HTTP_422_UNPROCESSABLE_ENTITY, "A location cannot be its own parent"
        )
    if updates.get("parent_id"):
        await _owned(db, Location, user.household_id, updates["parent_id"])
    for k, v in updates.items():
        setattr(loc, k, v)
    await db.commit()
    await db.refresh(loc)
    return LocationOut.model_validate(loc)


@router.delete("/locations/{location_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_location(location_id: uuid.UUID, user: CurrentUser, db: Db):
    """Deleting a location does not delete its totes — they become unplaced.

    Same reasoning as items surviving a deleted tote: removing a shelf from the model must not
    erase the record of what was on it.
    """
    loc = await _owned(db, Location, user.household_id, location_id)
    await db.delete(loc)
    await db.commit()


# ── Categories ───────────────────────────────────────────────────────────────


@router.get("/categories", response_model=list[CategoryOut])
async def list_categories(user: CurrentUser, db: Db):
    rows = (
        (
            await db.execute(
                select(Category)
                .where(Category.household_id == user.household_id)
                .order_by(Category.sort_order, Category.name)
            )
        )
        .scalars()
        .all()
    )
    return [CategoryOut.model_validate(r) for r in rows]


@router.post("/categories", response_model=CategoryOut, status_code=status.HTTP_201_CREATED)
async def create_category(body: CategoryIn, user: CurrentUser, db: Db):
    cat = Category(user_id=user.id, household_id=user.household_id, **body.model_dump())
    db.add(cat)
    await db.commit()
    await db.refresh(cat)
    return CategoryOut.model_validate(cat)


@router.patch("/categories/{category_id}", response_model=CategoryOut)
async def patch_category(category_id: uuid.UUID, body: CategoryPatch, user: CurrentUser, db: Db):
    cat = await _owned(db, Category, user.household_id, category_id)
    for k, v in body.model_dump(exclude_unset=True).items():
        setattr(cat, k, v)
    await db.commit()
    await db.refresh(cat)
    return CategoryOut.model_validate(cat)


@router.delete("/categories/{category_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_category(category_id: uuid.UUID, user: CurrentUser, db: Db):
    """Items and totes in this category become uncategorised (ON DELETE SET NULL), never deleted.

    The vocabulary is the user's and will change — renaming and pruning it must be safe, or they
    will stop curating it and it stops being useful.
    """
    cat = await _owned(db, Category, user.household_id, category_id)
    # Belt and braces: the FK is SET NULL, but being explicit here documents the intent at the
    # place someone will read it.
    await db.execute(
        Tote.__table__.update().where(Tote.category_id == cat.id).values(category_id=None)
    )
    await db.delete(cat)
    await db.commit()
