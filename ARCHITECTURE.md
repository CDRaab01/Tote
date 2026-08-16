# Tote — architecture

Kept in lockstep with the code: **update this file in the same commit** as any change that alters
a module's responsibility, a layer boundary, an external contract, or the data model. This is a
suite-wide rule; silently-drifting docs have burned two sibling repos already.

The build plan and the reasoning behind the locked decisions live in [CLAUDE.md](CLAUDE.md).
This file describes what exists **now**.

## Current state: Phase 2 (catalog, ledger, search) — server side

```
Tote/
├─ android/                     Compose client
│  └─ app/src/main/java/com/tote/
│     ├─ ToteApp.kt             @HiltAndroidApp entry point
│     ├─ MainActivity.kt        single activity + the signed-in/out Gate
│     ├─ data/
│     │  ├─ local/TokenStore    session tokens in their own DataStore
│     │  └─ remote/             ApiService, DTOs, AuthInterceptor, SuiteAuthManager
│     ├─ di/NetworkModule.kt    Retrofit/OkHttp/Json wiring
│     ├─ util/UiState.kt        Idle/Loading/Success/Error
│     └─ ui/
│        ├─ HomeScreen.kt       Phase 0 placeholder — replaced in Phase 2
│        ├─ auth/               AuthViewModel + LoginScreen/LoginContent
│        ├─ components/         HazardRule, ToteButton
│        └─ theme/ToteTheme.kt  semantic layer over PULSE
├─ server/                      FastAPI backend
│  ├─ app/
│  │  ├─ main.py                app factory, middleware, /health + /version
│  │  ├─ config.py              pydantic-settings; env > .env
│  │  ├─ database.py            async engine, session factory, DeclarativeBase
│  │  ├─ security.py            session JWTs + CurrentUser dependency
│  │  ├─ limiter.py             slowapi rate limiting
│  │  ├─ models/                the eleven tables of §4
│  │  ├─ routers/               suite_auth, users, catalog, totes, items
│  │  ├─ schemas/               request/response models
│  │  └─ services/
│  │     ├─ suite_auth.py       JWKS validation + find-or-create
│  │     ├─ movement.py         THE single writer of whereabouts
│  │     └─ catalog.py          read-side joins, counts, local_today()
│  ├─ alembic/versions/0001_    the whole schema
│  └─ tests/                    pytest, asyncio_mode=auto
├─ scripts/synthetic_smoke.py   post-deploy smoke (SSO-only apps have no login to script)
├─ docker-compose.yml           db + server, host ports 8008/5439
└─ .github/workflows/           ci.yml, notify.yml, release.yml, deploy.yml
```

## Layering

The client presents; **the server decides**. This is the suite's standing rule and it has
specific force here: size comparison, "what fits this person", completeness of an item record,
and every derived location value are computed server-side and delivered ready to display. A
client must never compute which size is bigger — the ladder is subtle enough (`6X` sorts between
6 and 7) that two implementations would drift.

## Theme: the Slate accent

Tote leads `PulseAccent.Slate`, added to Pulse in
[#20](https://github.com/CDRaab01/Pulse/pull/20). It is the only accent in the family that is a
**pair of hues** rather than a bright/deep pair of one hue — a charcoal body with a safety-yellow
marking, after the black-and-yellow site tote.

The constraint that shapes it: **white on `PulseYellow` is 1.42:1**, so yellow can never carry
text. The halves therefore swap roles by theme.

| | text-bearing half | container | measured |
|---|---|---|---|
| Dark | `PulseYellow` (the surface is already charcoal) | dark olive | 13.66:1 on ink; ink on yellow 11.92:1 |
| Light | `PulseSlateDeep` | pale yellow | 10.35:1 on white; charcoal on yellow 9.30:1 |

### Two places the pair leaks, and why each needs an app-layer override

A pair accent breaks an assumption the rest of the family can make: that the hero gradient and
the channel base are the same hue, so one `on` colour serves both. For Slate they are charcoal
and yellow, so they need different `on` colours — and both leaks were found by **rendering the
screens**, not by reading the code.

- **`ToteColors.hazard` is not `slate.base`.** `base` is the yellow only in dark mode. The
  hazard band lives on the hero, which is charcoal in *both* themes, so the band is the bright
  yellow in both. A separate `hazardOnSurface` steps down to `PulseYellowDeep` for marks placed
  on the app's own background, where bright yellow is 1.42:1 and invisible.
- **`ToteButton` overrides `PulseButton`'s label colour to white.** `PulseButton` fills a
  non-tonal button with the hero gradient but colours the label with `accent.on` — the dark ink
  that belongs on the channel's yellow fill. Under Slate that renders near-black text on a
  charcoal button. Tonal buttons are untouched: there the fill is `accent.dim` and the label is
  `accent.base`, which is already correct.

`ToteThemeTest` asserts every one of these ratios rather than trusting the comments — including
two negative cases: white-on-yellow must *fail* (the property the whole design hangs on), and
`accent.on` on the hero must *fail* (so a future "simplification" back to `PulseButton`'s default
breaks a test instead of someone's sign-in screen).

### Channel semantics

| Channel | Hue | Meaning |
|---|---|---|
| `slate` | the accent pair | hero, primary actions, tote identity |
| `stored` | recovery green | stored / put away / complete |
| `search` | electric blue | search hits, cross-references |
| `attention` | rose | overdue loan, untagged tote, drafts waiting |
| `provenance` | violet | the movement ledger and history |

**Attention is rose, not the amber every sibling uses.** Tote's lead is a safety yellow and amber
sits only 14.9° of hue from it, so an amber warning next to a yellow brand mark reads as one
signal and the whole screen looks like a warning. Red stays the error voice.

## Auth

Tote is **SSO-only**: `POST /auth/suite` is the entire front door, and there are no
register/password endpoints to add later. It validates an RS256 suite token against
dragonfly-id's published JWKS (no shared secret), then finds or creates the local user **by
email** — which is how one account spans the suite.

First login also seeds the user's `user_settings` row and the default categories. Seeding at
account creation rather than lazily means a brand-new account can file its first item instead of
meeting an empty picker.

The endpoint is feature-flagged on `SUITE_JWKS_URL`/`SUITE_ISSUER` and 404s without them. For an
SSO-only app that means *no login path at all*, which is exactly why both are pinned as literals
in compose's `environment:` block rather than living in an env_file compose will not re-read.

On the client, `SuiteAuthManager` runs OIDC authorization-code + PKCE through AppAuth in a Custom
Tab. Because the Custom Tab shares the system browser's session, signing in to one suite app
skips the login in the others — that is the actual single sign-on.

**The manifest override on `RedirectUriReceiverActivity` is load-bearing.** AppAuth leaves that
activity inheriting the app theme, which is `android:Theme.Material`, and it crashes on the way
back from the browser with "You need to use a Theme.AppCompat theme". The crash is invisible to
CI and to every screenshot test, because it only happens on a real device at the exact moment a
user signs in.

## Server

`/health` and `/version` are unauthenticated on purpose — the app shows what's running before
login, and the deploy smoke needs a target that doesn't require a token. `/version` returns
`{name, version, commit, built_at}`; the four keys are asserted in tests because the hub cannot
read a versionCode from the GitHub API, so this endpoint plus the released `version.json` are the
entire update story.

Two exception handlers are wired from the start:

- `IntegrityError → 409`. This matters more here than in the siblings: `totes.code` will be
  unique per user because that code is printed on a physical index card, so a collision is a
  real-world ambiguity.
- `DBAPIError` with SQLSTATE class `22` → **422, not 500**. A NUL byte in a text field is the
  client sending something unstorable, not a server fault.

HSTS is off by default and asserted off in tests: Tote terminates TLS at Tailscale Serve and is
published on loopback, so advertising HSTS from the origin would be a lie the browser caches.

## Configuration

Required **non-secret** config lives in `docker-compose.yml`'s `environment:` block as literals;
secrets live in `server/.env`. Compose does not re-read a changed `env_file` when it recreates a
container, so an env_file-only flag silently vanishes on the next redeploy — this has caused
production regressions three times across the suite, most recently Crate's `NTFY_TOPIC`, which
interpolated from a root `.env` that repo didn't have and so could never be anything but empty.

`NFC_URI_BASE` is pinned there for a sharper reason than the others: it gets **baked into
physical NFC tags** sitting on bins in an attic. A tag cannot be patched by a redeploy, so the
server resolves `/t/<code>` by code regardless of host — the escape hatch if the URL ever changes.

## CI

`ci.yml` runs entirely on GitHub-hosted runners. **Suite invariant 7**: never put a
`pull_request` trigger on a job that runs on a self-hosted runner — the repos are public and the
runners are on the production host.

`notify.yml` pages `tote-alerts` on a failed run. It exists in Phase 0 rather than being
retrofitted, and it is a separate `workflow_run` workflow rather than an `if: failure()` step
precisely *because* of invariant 7: the suite's ntfy is tailnet-only, so the page must run on the
self-hosted runner, which therefore must not be reachable from `pull_request`. All workflow
metadata reaches the script through `env:`, never interpolated as `${{ }}` into a shell body — a
branch name is attacker-controlled text and this runs on the prod host.

Ruff is pinned to the **same version** in CI and in `pyproject.toml`'s dev extra. Crate's CI pins
0.4.4 while its pyproject asks for 0.16.2, so a rule added between those versions passes in one
place and fails in the other.

## Release and deploy

`release.yml` cuts a signed APK on any `main` push touching `android/**`. `versionCode` is
**epoch minutes** (suite invariant 2 — `github.run_number` was rejected because renaming a
workflow resets it to 1, which Android reads as a downgrade). The `Assert signing identity` step
pins the suite signer to `5a596c9e…` and fails the release on any mismatch, so a missing
`KEYSTORE_*` secret can never silently publish an APK signed with the committed debug key —
which would be uninstallable over existing installs.

`deploy.yml` fires on `workflow_run` after CI goes green on a **push** to `main` (the weekly
scheduled CI run is excluded — a bit-rot check must never ship) and runs `deploy/redeploy.ps1` on
the self-hosted runner. `workflow_dispatch` with a prior SHA in `ref` is the rollback lever.

Two checks guard the deploy, and both exist because of specific past failures:

- **Identity**: `/health` returns a byte-identical `{"status":"ok"}` in every suite app, so
  polling it alone cannot distinguish Tote from whichever neighbour owns the port. Crate's first
  deploy pointed at a port Magpie held, got an instant "ok", and reported green while Crate was
  still booting. `redeploy.ps1` therefore also requires `/version` to report `"Tote API"`.
- **Freshness**: the post-deploy step asserts the served `/version.commit` equals the commit just
  deployed, which is what catches a container that silently failed to rebuild.

`TOTE_DIR` is `C:\Code\Tote` — the deploy directory *is* the dev checkout, as for all eight apps.
Every green deploy `git reset --hard`s it, so work happens in a worktree, never there.

## Data model

All eleven tables land in migration `0001`. The shape is in CLAUDE.md §4; what matters
architecturally is where the invariants live.

- **`movements` is an append-only ledger, and `items.current_tote_id`/`status` are derived from
  it** by one service module. Nothing else writes them. The question the table exists to answer
  is "where was this last year", and an edit-in-place model cannot answer it.
- **`item_apparel` is a one-to-one extension**, not eleven mostly-null columns on every ratchet
  set and board game.
- **`person_sizes` is a history, not a current value.** A child's size moves, and last winter's
  answer is what tells you which bin to open next winter.
- **`items.current_tote_id` is `ON DELETE SET NULL`.** Throwing a bin away must not erase the
  record of what was in it.

Two objects exist **only in the migration**, because SQLAlchemy cannot express them on a model:

| Object | Why |
|---|---|
| `uq_totes_user_code_lower` | unique on `lower(code)` per user — the code is printed on an index card and written into an NFC tag, so "a14" and "A14" being two bins is a real-world ambiguity |
| `ix_items_search_vector` | GIN over a STORED generated `tsvector`; a btree cannot answer "does this document contain these lexemes" |

Consequently **`conftest.py` builds the test schema with alembic, not `metadata.create_all`** —
a deliberate deviation from the siblings. Under `create_all` both objects would be silently
absent and the tests that prove them would be testing a schema that never ships.

`search_vector` covers the item's own name, description and notes. Category lives in another
table and a generated column cannot join, so category is a **filter**, not a search term.

## The movement ledger

`app/services/movement.py` is the **single writer** of `current_tote_id`, `status`,
`out_reason`, `out_since` and `expected_back`. Every change appends a `movements` row; nothing
else assigns those columns, and `PATCH /items` deliberately cannot (asserted in tests). A
convenience assignment elsewhere would be a hole in the history exactly where someone was in a
hurry, and a hole is invisible until the day you need the answer.

The invariant, enforced in one place and asserted for **every** reason rather than a sample:

```
current_tote_id is NOT NULL  <=>  status == "stored"
```

The contradiction it prevents is invisible in the UI — an item that is both in bin A14 and lent
to Dave renders perfectly and is only wrong in the attic.

Reasons are split into inbound (`initial`, `moved`, `repacked`, `returned`, `corrected`) and
outbound (`unpacked`, `outgrown`, `loaned`, `disposed`). An inbound reason without a destination
is a 422, and so is an outbound reason *with* one: "lent to Dave, into bin A14" is a
contradiction, not a shorthand.

`record_move` does not commit. The caller owns the transaction, so unpacking forty items is one
atomic operation rather than forty chances to half-succeed.

### Bulk operations

`unpack` and `repack` exist because that is what the holidays actually look like. Modelling it
as fifty individual edits means nobody does it, and a catalog nobody updates is worse than no
catalog — it is one you trust and shouldn't.

- **`repack` without a selection takes back only the items whose *last* movement left THIS
  tote.** A naive "items with no tote" query would sweep up the whole house, including things
  that are lent out.
- **`item_ids: null` means everything; `[]` is an explicit selection of nothing.** Conflating
  them would let a UI bug empty a whole bin.

## Read side

`app/services/catalog.py` owns the joins so that "which bin, and where is it" is answered one
way. A search hit, a tote's contents and an item detail all carry the same denormalised
`tote_code` / `location_name`, computed once — three implementations is how they end up
disagreeing.

All joins are **outer**: an item with no tote is a normal state (out for the holidays, lent), and
an inner join would silently hide exactly the items someone is most likely hunting for.

`item_count` and `out_count` are computed per request and fetched **once for a whole list**, not
per row — the tote list backs the browse-by-location screen and a per-tote count would be a clean
N+1.

### Overdue, and why timezone is load-bearing

`is_overdue` is computed server-side so a screen and a notification cannot disagree. It resolves
through `local_today()` against a configured `LOCAL_TIMEZONE`, **not** `date.today()`: the
container runs UTC and the house is US Eastern, so a loan due today was being reported overdue
from 7pm local. That is the same class of failure as a test that passes in CI's UTC and fails at
home. An unrecognised zone degrades to UTC rather than raising — a nudge a few hours eager beats
a dead endpoint. `tzdata` is a runtime dependency because slim images ship no tz database.

## Search

`GET /search` uses **`websearch_to_tsquery`**, not `plainto_tsquery`: quoted phrases, `or`, and
stray punctuation are what people actually type, and throwing a 500 at them is not an option. No
matches returns `[]` — "no results" is an answer, and an error there reads as the app being
broken. Ordering is rank then name so ties are stable; an unstable order in a list someone is
scanning also reads as broken.

## Ownership

Every route resolves rows scoped to the authenticated user and returns **404, not 403**, for
someone else's — asserted across GET/PATCH/DELETE and `move`. 403 would let an authenticated user
probe which ids exist and tell "not yours" apart from "does not exist".

## Not yet built

The Android half of Phase 2 (tote list, tote detail, item add/edit, search screen, Room cache for
offline search); NFC and the index card
(Phase 3); photo capture and the AI draft pipeline (Phase 4); the sizing ladder (Phase 5);
people and lending (Phase 6); backups (Phase 7, once there are photos to lose).

The smoke script carries an explicit list of what each phase must add to it. Crate's stopped at
`/users/me` for months, so "auth works" read as "the app works" while the pipeline the app exists
for was never exercised.
