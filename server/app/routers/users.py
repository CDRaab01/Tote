from fastapi import APIRouter

from app.schemas.auth import UserOut
from app.security import CurrentUser

router = APIRouter(prefix="/users", tags=["users"])


@router.get("/me", response_model=UserOut)
async def me(user: CurrentUser) -> UserOut:
    """The authenticated user. Also the cheapest proof a token is valid, which is what the
    post-deploy smoke uses before it exercises anything heavier."""
    return UserOut(id=str(user.id), email=user.email, name=user.name)
