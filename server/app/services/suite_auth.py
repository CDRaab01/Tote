"""Suite SSO — trade a Dragonfly-issued suite token for a Tote session.

Validates the RS256 suite access token against the identity server's published JWKS (no shared
secret), then finds the local user by email or creates one on first sight. Entirely behind the
`suite_jwks_url`/`suite_issuer` flags: with them unset the endpoint is disabled — and since
Tote is SSO-only (no password endpoints, Magpie precedent), that means no login path at all,
which is why the two flags are pinned in compose `environment:` in production.
"""

import time

import httpx
from fastapi import HTTPException, status
from jose import JWTError, jwt
from jose.exceptions import JWKError
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.models.category import DEFAULT_CATEGORIES, Category
from app.models.user import User, UserSettings
from app.schemas.auth import TokenResponse
from app.security import create_access_token, create_refresh_token
from app.services.household_service import create_household

# Small in-process JWKS cache; refetched on TTL expiry or an unknown `kid` (key rotation).
_JWKS_CACHE: dict = {"fetched_at": 0.0, "jwks": None}
_JWKS_TTL_SECONDS = 3600


async def _fetch_jwks(*, force: bool = False) -> dict:
    now = time.time()
    if not force and _JWKS_CACHE["jwks"] and now - _JWKS_CACHE["fetched_at"] < _JWKS_TTL_SECONDS:
        return _JWKS_CACHE["jwks"]
    async with httpx.AsyncClient(timeout=settings.external_timeout_seconds) as client:
        resp = await client.get(settings.suite_jwks_url)
        resp.raise_for_status()
        jwks = resp.json()
    _JWKS_CACHE.update(fetched_at=now, jwks=jwks)
    return jwks


def _select_key(jwks: dict, kid: str | None) -> dict | None:
    for key in jwks.get("keys", []):
        if kid is None or key.get("kid") == kid:
            return key
    return None


async def _verify_suite_token(token: str) -> dict:
    unauthorized = HTTPException(status.HTTP_401_UNAUTHORIZED, "Invalid suite token")
    try:
        kid = jwt.get_unverified_header(token).get("kid")
    except JWTError:
        raise unauthorized

    jwks = await _fetch_jwks()
    key = _select_key(jwks, kid)
    if key is None:
        # Unknown key id → the identity server may have rotated; refetch once.
        jwks = await _fetch_jwks(force=True)
        key = _select_key(jwks, kid)
    if key is None:
        raise unauthorized

    try:
        return jwt.decode(
            token,
            key,
            algorithms=["RS256"],
            audience=settings.suite_audience,
            issuer=settings.suite_issuer,
        )
    except (JWTError, JWKError):
        raise unauthorized


async def suite_login(db: AsyncSession, suite_token: str) -> TokenResponse:
    if not settings.suite_jwks_url or not settings.suite_issuer:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Suite login is not enabled")

    claims = await _verify_suite_token(suite_token)
    email = (claims.get("email") or "").strip().lower()
    if not email:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Suite token carries no email")

    result = await db.execute(select(User).where(User.email == email))
    user = result.scalar_one_or_none()
    if user is None:
        # First time in this app → link a fresh account by email. No password column exists
        # (SSO-only); seed the household, the per-user settings row AND the default categories
        # alongside, so every reader has values to read and a brand-new account is immediately
        # usable rather than presenting an empty category picker on the first item.
        user = User(name=claims.get("name") or email.split("@")[0], email=email)
        db.add(user)
        await db.flush()
        # A household of ONE, always — including for someone who will be invited into another
        # one an hour later. It is what lets `user.household_id` be non-optional everywhere and
        # keeps "solo" from being a special case the access checks have to remember.
        household = await create_household(db, user.id)
        db.add(UserSettings(user_id=user.id))
        for order, name in enumerate(DEFAULT_CATEGORIES):
            db.add(
                Category(household_id=household.id, user_id=user.id, name=name, sort_order=order)
            )
        await db.commit()
        await db.refresh(user)
    elif user.membership is None:
        # A household is an invariant, not a feature: `User.household_id` raises without one and
        # every endpoint 500s. Signing in is the one action a person in that state can still
        # perform, so it is the only place the invariant can be restored — the branch above only
        # runs for accounts that do not exist yet. Belt and braces behind the guard in
        # `merge_conflicts`, which is what stops anyone arriving here in the first place.
        await create_household(db, user.id)
        await db.commit()
        await db.refresh(user)

    return TokenResponse(
        access_token=create_access_token(str(user.id)),
        refresh_token=create_refresh_token(str(user.id)),
    )
