"""Suite SSO tests — self-contained RS256 keypair, JWKS fetch faked, no network.

Tote is SSO-only: `POST /auth/suite` is the single login path, so these tests cover the whole
front door. The negative cases matter as much as the happy one — a suite token from the wrong
issuer, or for the wrong audience, must not open an account.
"""

import time
import uuid

import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from jose import jwk, jwt
from sqlalchemy import func, select

from app.config import settings
from app.database import AsyncSessionLocal
from app.models.category import DEFAULT_CATEGORIES, Category
from app.models.user import User, UserSettings

KID = "test-kid"
ISSUER = "https://id.test"
AUDIENCE = "suite"

_private_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
_private_pem = _private_key.private_bytes(
    serialization.Encoding.PEM,
    serialization.PrivateFormat.PKCS8,
    serialization.NoEncryption(),
).decode()
_public_pem = (
    _private_key.public_key()
    .public_bytes(
        serialization.Encoding.PEM,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    .decode()
)
_jwk = jwk.construct(_public_pem, algorithm="RS256").to_dict()
_jwk["kid"] = KID
JWKS = {"keys": [_jwk]}


def mint(email: str | None, *, issuer: str = ISSUER, audience: str = AUDIENCE, name=None) -> str:
    claims = {
        "sub": str(uuid.uuid4()),
        "iss": issuer,
        "aud": audience,
        "exp": int(time.time()) + 300,
    }
    if email is not None:
        claims["email"] = email
    if name is not None:
        claims["name"] = name
    return jwt.encode(claims, _private_pem, algorithm="RS256", headers={"kid": KID})


@pytest.fixture
def suite_enabled(monkeypatch):
    monkeypatch.setattr(settings, "suite_jwks_url", "https://id.test/jwks.json")
    monkeypatch.setattr(settings, "suite_issuer", ISSUER)
    monkeypatch.setattr(settings, "suite_audience", AUDIENCE)

    async def fake_fetch_jwks(*, force: bool = False) -> dict:
        return JWKS

    import app.services.suite_auth as sa_mod

    monkeypatch.setattr(sa_mod, "_fetch_jwks", fake_fetch_jwks)


async def test_disabled_by_default_returns_404(client, monkeypatch):
    """With the flags unset the endpoint is off. For an SSO-only app that means NO login path
    at all, which is exactly why SUITE_JWKS_URL/SUITE_ISSUER are pinned in compose
    `environment:` rather than living only in an env_file compose won't re-read."""
    monkeypatch.setattr(settings, "suite_jwks_url", None)
    monkeypatch.setattr(settings, "suite_issuer", None)
    r = await client.post("/auth/suite", json={"suite_token": mint("a@b.com")})
    assert r.status_code == 404


async def test_first_login_creates_user_settings_and_categories(client, suite_enabled):
    email = f"new-{uuid.uuid4().hex[:8]}@dragonflymedia.org"
    r = await client.post("/auth/suite", json={"suite_token": mint(email, name="Chris")})
    assert r.status_code == 200
    assert r.json()["access_token"]

    async with AsyncSessionLocal() as db:
        user = (await db.execute(select(User).where(User.email == email))).scalar_one()
        assert user.name == "Chris"
        # Settings row seeded, so every later reader has a value rather than a None to guard.
        assert (
            await db.execute(select(UserSettings).where(UserSettings.user_id == user.id))
        ).scalar_one_or_none() is not None
        # Categories seeded, so a brand-new account can file its first item instead of meeting
        # an empty picker.
        count = (
            await db.execute(
                select(func.count()).select_from(Category).where(Category.user_id == user.id)
            )
        ).scalar_one()
        assert count == len(DEFAULT_CATEGORIES)


async def test_second_login_links_the_same_account_and_does_not_reseed(client, suite_enabled):
    """Accounts link BY EMAIL across the suite. A second login must find the existing user, not
    create a parallel one — and must not duplicate the seeded categories."""
    email = f"repeat-{uuid.uuid4().hex[:8]}@dragonflymedia.org"
    assert (await client.post("/auth/suite", json={"suite_token": mint(email)})).status_code == 200
    assert (await client.post("/auth/suite", json={"suite_token": mint(email)})).status_code == 200

    async with AsyncSessionLocal() as db:
        users = (await db.execute(select(User).where(User.email == email))).scalars().all()
        assert len(users) == 1
        count = (
            await db.execute(
                select(func.count()).select_from(Category).where(Category.user_id == users[0].id)
            )
        ).scalar_one()
        assert count == len(DEFAULT_CATEGORIES)


async def test_email_is_normalised_so_case_does_not_fork_the_account(client, suite_enabled):
    """Identity servers are not required to normalise case, and a forked account here would
    silently split one household's catalog in two."""
    base = f"case-{uuid.uuid4().hex[:8]}@dragonflymedia.org"
    assert (await client.post("/auth/suite", json={"suite_token": mint(base)})).status_code == 200
    upper = await client.post("/auth/suite", json={"suite_token": mint(base.upper())})
    assert upper.status_code == 200

    async with AsyncSessionLocal() as db:
        users = (await db.execute(select(User).where(User.email == base))).scalars().all()
        assert len(users) == 1


async def test_token_without_email_is_rejected(client, suite_enabled):
    """Accounts link by email; a token that carries none cannot be linked to anything, and
    inventing a placeholder would silently create an orphan account."""
    r = await client.post("/auth/suite", json={"suite_token": mint(None)})
    assert r.status_code == 401


async def test_wrong_issuer_is_rejected(client, suite_enabled):
    r = await client.post(
        "/auth/suite", json={"suite_token": mint("x@y.com", issuer="https://evil")}
    )
    assert r.status_code == 401


async def test_wrong_audience_is_rejected(client, suite_enabled):
    r = await client.post("/auth/suite", json={"suite_token": mint("x@y.com", audience="other")})
    assert r.status_code == 401


async def test_garbage_token_is_rejected(client, suite_enabled):
    r = await client.post("/auth/suite", json={"suite_token": "not-a-jwt"})
    assert r.status_code == 401


async def test_users_me_requires_a_token(client):
    assert (await client.get("/users/me")).status_code == 401


async def test_users_me_returns_the_logged_in_user(client, suite_enabled):
    email = f"me-{uuid.uuid4().hex[:8]}@dragonflymedia.org"
    token = (
        await client.post("/auth/suite", json={"suite_token": mint(email, name="Sam")})
    ).json()["access_token"]
    r = await client.get("/users/me", headers={"Authorization": f"Bearer {token}"})
    assert r.status_code == 200
    assert r.json()["email"] == email
    assert r.json()["name"] == "Sam"


async def test_refresh_returns_a_working_new_session(client, suite_enabled):
    """The whole point: a session must be renewable without the browser SSO flow. Without this
    endpoint the app wedged in production 30 minutes after every sign-in, every call 401ing."""
    email = f"refresh-{uuid.uuid4().hex[:8]}@dragonflymedia.org"
    first = (await client.post("/auth/suite", json={"suite_token": mint(email)})).json()

    r = await client.post("/auth/refresh", json={"refresh_token": first["refresh_token"]})
    assert r.status_code == 200
    body = r.json()
    assert body["access_token"] and body["refresh_token"]

    me = await client.get("/users/me", headers={"Authorization": f"Bearer {body['access_token']}"})
    assert me.status_code == 200
    assert me.json()["email"] == email


async def test_refresh_rotates_and_the_new_refresh_token_also_works(client, suite_enabled):
    """The client persists whatever comes back, so a returned refresh token that could not
    itself be redeemed would strand the session one renewal later — a bug with a 30-minute
    fuse, which is the kind that reaches a phone in an attic."""
    email = f"rotate-{uuid.uuid4().hex[:8]}@dragonflymedia.org"
    first = (await client.post("/auth/suite", json={"suite_token": mint(email)})).json()
    second = (
        await client.post("/auth/refresh", json={"refresh_token": first["refresh_token"]})
    ).json()
    third = await client.post("/auth/refresh", json={"refresh_token": second["refresh_token"]})
    assert third.status_code == 200


async def test_refresh_rejects_an_access_token(client, suite_enabled):
    """Same secret, so only the `type` claim separates them. Accepting an access token here
    would let a leaked one renew itself forever, outliving its 30-minute blast radius."""
    body = (
        await client.post(
            "/auth/suite",
            json={"suite_token": mint(f"acc-{uuid.uuid4().hex[:8]}@dragonflymedia.org")},
        )
    ).json()
    r = await client.post("/auth/refresh", json={"refresh_token": body["access_token"]})
    assert r.status_code == 401


async def test_refresh_rejects_garbage(client):
    assert (
        await client.post("/auth/refresh", json={"refresh_token": "not-a-jwt"})
    ).status_code == 401


async def test_refresh_works_while_sso_is_disabled(client, monkeypatch, suite_enabled):
    """Deliberate asymmetry with `/auth/suite`: refresh redeems Tote's OWN HS256 token and needs
    no identity server, so an unreachable or misconfigured dragonfly-id must not log out every
    already-signed-in phone."""
    body = (
        await client.post(
            "/auth/suite",
            json={"suite_token": mint(f"nosso-{uuid.uuid4().hex[:8]}@dragonflymedia.org")},
        )
    ).json()
    monkeypatch.setattr(settings, "suite_jwks_url", None)
    monkeypatch.setattr(settings, "suite_issuer", None)
    r = await client.post("/auth/refresh", json={"refresh_token": body["refresh_token"]})
    assert r.status_code == 200


async def test_a_refresh_token_cannot_be_used_as_an_access_token(client, suite_enabled):
    """Both are signed with the same secret, so only the `type` claim separates them. If that
    check regressed, a long-lived refresh token would silently become a long-lived session."""
    body = (
        await client.post(
            "/auth/suite",
            json={"suite_token": mint(f"swap-{uuid.uuid4().hex[:8]}@dragonflymedia.org")},
        )
    ).json()
    r = await client.get("/users/me", headers={"Authorization": f"Bearer {body['refresh_token']}"})
    assert r.status_code == 401
