from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Request, status
from jose import JWTError, jwt
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.database import get_db
from app.limiter import limiter
from app.schemas.auth import RefreshRequest, SuiteLoginRequest, TokenResponse
from app.security import create_access_token, create_refresh_token
from app.services.suite_auth import suite_login

router = APIRouter(prefix="/auth", tags=["auth"])


@router.post("/suite", response_model=TokenResponse)
@limiter.limit("10/minute")
async def suite(
    request: Request,
    req: SuiteLoginRequest,
    db: Annotated[AsyncSession, Depends(get_db)],
):
    """Trade a Dragonfly suite token for a Tote session (the ONLY login path — SSO-only app).

    Disabled (404) unless `suite_jwks_url` + `suite_issuer` are configured. Those two are pinned
    in compose `environment:` precisely because losing them here means no login path at all.
    """
    return await suite_login(db, req.suite_token)


@router.post("/refresh", response_model=TokenResponse)
@limiter.limit("10/minute")
async def refresh(request: Request, req: RefreshRequest):
    """Redeem a refresh token for a new access/refresh pair (Magpie/Crate `/auth/refresh`).

    Without this, a Tote session dies 30 minutes after sign-in and the only way back in is the
    full browser SSO flow — which the client cannot trigger on its own, so the app wedges with
    every call 401ing. That is exactly what happened in production on 2026-08-16.

    Deliberately NOT gated on `suite_jwks_url`/`suite_issuer` the way `/auth/suite` is: this
    token is Tote's own HS256 session token, so redeeming it needs no identity server. A
    momentarily unreachable dragonfly-id must not log everyone out.

    No DB lookup: the token's own signature plus the `"type": "refresh"` claim is sufficient,
    mirroring the siblings. A deleted user still fails at the next authenticated call, where
    `get_current_user` does check the row exists.
    """
    credentials_exception = HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid refresh token"
    )
    try:
        payload = jwt.decode(
            req.refresh_token, settings.secret_key, algorithms=[settings.algorithm]
        )
        # An access token presented here must be rejected: it is the shorter-lived of the two
        # and accepting it would let a leaked access token renew itself indefinitely.
        if payload.get("type") != "refresh":
            raise credentials_exception
        user_id: str | None = payload.get("sub")
        if not user_id:
            raise credentials_exception
    except JWTError:
        raise credentials_exception
    return TokenResponse(
        access_token=create_access_token(user_id),
        refresh_token=create_refresh_token(user_id),
    )
