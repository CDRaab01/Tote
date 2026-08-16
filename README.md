# Tote

A digital catalog of what is physically in your storage bins.

Every tote carries an index card and an NFC tag. Tap the tag to see what's inside, search the
catalog to find which tote something is in, and move items in and out as kids grow into sizes or
the holidays come around.

Eighth app in the Dragonfly personal suite, alongside **Spotter** (fitness), **Plate**
(nutrition), **Cookbook** (recipes), **Dragonfly** (hub/identity), **Magpie** (finance),
**Remnant** (capture) and **Crate** (eBay selling). Same stack, same conventions, same PULSE
design language.

## The problem it actually solves

Not "make a list." It's: six months from now, in the attic, **which of these fourteen identical
grey bins has the 4T winter coats in it** — and the inverse, **we have a four-year-old now, what
do we already own that fits.**

## Status

**Phase 0 (scaffold) complete.** The app builds with the Slate theme, the server answers
`/health` and `/version`, CI is green. Nothing is deployed yet.

The full build plan, data model, and the reasoning behind every locked decision live in
[CLAUDE.md](CLAUDE.md). Architecture notes are in [ARCHITECTURE.md](ARCHITECTURE.md).

## Stack

| Part | What |
|---|---|
| Client | Android, Kotlin, Jetpack Compose, MVVM + repository |
| Design | [PULSE](https://github.com/CDRaab01/Pulse) via Gradle composite build — Tote leads the **Slate** accent |
| Server | Python FastAPI, SQLAlchemy 2.0 async + Alembic, Postgres |
| Auth | SSO only — "Sign in with Dragonfly" (OIDC, RS256/JWKS) |
| Reach | Tailnet only, via Tailscale Serve `:8448`. No public hostname, by design. |
| Ports | API **8008**, Postgres **5439** |

## Local development

The server needs a Postgres to talk to and the Android build needs the sibling Pulse checkout at
`../Pulse`.

```bash
# Server: bring up the database, then run the tests against a throwaway DB inside it.
docker compose up -d db
docker exec tote-db-1 psql -U tote -d tote -c "CREATE DATABASE tote_test;"

cd server
python -m venv .venv && ./.venv/Scripts/python.exe -m pip install -e ".[dev]"
DATABASE_URL="postgresql+asyncpg://tote:tote@127.0.0.1:5439/tote_test" \
  SECRET_KEY=dev DB_NULLPOOL=true ./.venv/Scripts/python.exe -m pytest tests/ -v
```

Two things that will otherwise cost you an afternoon, both suite-wide lessons:

- Use **`127.0.0.1`, never `localhost`**, in `DATABASE_URL`. Docker publishes on IPv4 only and
  the `::1`-first fallback stalls every connection.
- Set **`DB_NULLPOOL=true`** for tests. SQLAlchemy's default pool binds asyncpg connections to
  the event loop that created them, which breaks under pytest-asyncio's per-test loops
  ("Task attached to a different loop"). `conftest.py` sets it, but only if nothing has imported
  `app.*` first — the engine is built at import time.

```bash
# Android
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

CI runs `ruff check` **and** `ruff format --check` — run both before pushing.
