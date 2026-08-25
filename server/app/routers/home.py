"""GET /home — the Find tab's volunteered cards.

Thin on purpose: the composition lives in app/services/home.py and the contract (with the
reasoning) in app/schemas/home.py. A null card is simply absent from the screen, so this
endpoint never explains itself — it answers with whatever it can honestly say.
"""

from typing import Annotated

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.schemas.home import HomeOut
from app.security import CurrentUser
from app.services.catalog import local_today
from app.services.home import next_size_card, seasonal_card

router = APIRouter(tags=["home"])

Db = Annotated[AsyncSession, Depends(get_db)]


@router.get("/home", response_model=HomeOut)
async def home(user: CurrentUser, db: Db) -> HomeOut:
    # `local_today()` resolved once, here at the edge: the cards reason about dates in the
    # household's timezone, and the container's own clock runs UTC.
    return HomeOut(
        seasonal=await seasonal_card(db, user.household_id, local_today()),
        next_size=await next_size_card(db, user.household_id),
    )
