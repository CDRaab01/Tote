"""GET /home — the Find tab's volunteered cards.

Skeleton registered so the app imports; the composition logic lands with its tests in the same
change-set (see schemas/home.py for the contract and the reasoning).
"""

from typing import Annotated

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.schemas.home import HomeOut
from app.security import CurrentUser

router = APIRouter(tags=["home"])

Db = Annotated[AsyncSession, Depends(get_db)]


@router.get("/home", response_model=HomeOut)
async def home(user: CurrentUser, db: Db) -> HomeOut:
    return HomeOut()
