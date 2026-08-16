import asyncio
import os

# Must be set before any `app.*` import: the engine is built at app.database import time.
# NullPool keeps pooled asyncpg connections from binding to a single event loop (the suite's
# known local-pytest failure mode — "Task attached to a different loop").
os.environ.setdefault("DB_NULLPOOL", "true")

# Photos must not land in the real volume path during tests. Set before app.config is imported,
# for the same reason DB_NULLPOOL is: settings are read at import time.
import tempfile

os.environ.setdefault("PHOTOS_DIR", tempfile.mkdtemp(prefix="tote-test-photos-"))

import pytest
import pytest_asyncio
from alembic.config import Config
from httpx import ASGITransport, AsyncClient
from sqlalchemy import text

from alembic import command
from app.database import AsyncSessionLocal, engine
from app.limiter import limiter
from app.main import app

# Disable rate limiting for the test suite.
limiter.enabled = False


@pytest.fixture(scope="session")
def event_loop():
    """Share a single event loop across the whole test session."""
    loop = asyncio.new_event_loop()
    yield loop
    loop.close()


@pytest.fixture(scope="session", autouse=True)
def migrate():
    """Build the schema with ALEMBIC, not `Base.metadata.create_all`.

    The siblings use create_all here, and for them it is equivalent. It is not equivalent for
    Tote: two schema objects exist ONLY in migration 0001 because SQLAlchemy cannot express
    them on a model — the functional unique index on `lower(totes.code)` and the GIN index on
    `items.search_vector`. Under create_all both would be silently absent, so the tests that
    prove "a14 and A14 are the same bin" and that search uses an index would be testing a
    schema that never ships.

    Deliberately synchronous and session-scoped: alembic's env.py calls asyncio.run() itself,
    which would explode inside an already-running loop.
    """
    command.upgrade(Config("alembic.ini"), "head")
    yield


@pytest_asyncio.fixture(scope="session", autouse=True)
async def dispose_engine():
    yield
    await engine.dispose()


@pytest_asyncio.fixture
async def client():
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as c:
        yield c


@pytest_asyncio.fixture
async def db():
    async with AsyncSessionLocal() as session:
        yield session


@pytest_asyncio.fixture
async def raw_sql():
    """Execute SQL directly, for asserting database-level constraints that the ORM would never
    exercise (functional indexes, generated columns)."""

    async def _run(sql: str, **params):
        async with AsyncSessionLocal() as session:
            result = await session.execute(text(sql), params)
            await session.commit()
            return result

    return _run


@pytest_asyncio.fixture
async def auth_client(client):
    """A client already carrying a session for a fresh, isolated user.

    Every test gets its own user rather than sharing one. That is not tidiness: the schema's
    uniqueness constraints are per-user (tote codes especially), so a shared user would make
    tests order-dependent in a way that only shows up when someone adds the fifteenth one.
    """
    import uuid as _uuid

    from app.models.category import Category
    from app.models.user import User, UserSettings
    from app.security import create_access_token

    email = f"t-{_uuid.uuid4().hex[:10]}@example.com"
    async with AsyncSessionLocal() as db:
        user = User(name="Test", email=email)
        db.add(user)
        await db.flush()
        db.add(UserSettings(user_id=user.id))
        db.add(Category(user_id=user.id, name="Tools", sort_order=0))
        await db.commit()
        await db.refresh(user)
        user_id = user.id

    client.headers["Authorization"] = f"Bearer {create_access_token(str(user_id))}"
    client.user_id = user_id
    return client
