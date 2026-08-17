# Tote — architecture

Kept in lockstep with the code: **update this file in the same commit** as any change that alters
a module's responsibility, a layer boundary, an external contract, or the data model. This is a
suite-wide rule; silently-drifting docs have burned two sibling repos already.

The build plan and the reasoning behind the locked decisions live in [CLAUDE.md](CLAUDE.md).
This file describes what exists **now**.

## Current state: Phases 0-5 complete, plus Phase 7's backups

```
Tote/
├─ android/                     Compose client
│  └─ app/src/main/java/com/tote/
│     ├─ ToteApp.kt             @HiltAndroidApp entry point
│     ├─ MainActivity.kt        single activity + the signed-in/out Gate
│     ├─ data/
│     │  ├─ CatalogRepository   the single place the app talks to the catalog
│     │  ├─ CaptureQueueRepository  the write-behind photo queue and its drain
│     │  ├─ local/TokenStore    session tokens in their own DataStore
│     │  ├─ local/CatalogCache  Room read cache — offline search
│     │  ├─ local/CaptureQueue  Room queue table — the only local-origin data
│     │  └─ remote/             ApiService, DTOs, AuthInterceptor, SuiteAuthManager,
│     │                         ScanTimeoutInterceptor, TokenAuthenticator + RefreshApi
│     ├─ di/                    NetworkModule, DatabaseModule
│     ├─ nfc/                   TagIo, NfcWriteSession, TapRouter
│     ├─ work/UploadWorker.kt   drains the capture queue when connected
│     ├─ util/UiState.kt        Idle/Loading/Success/Error
│     ├─ util/ApiErrors.kt      failure → the message that names the real cause
│     ├─ util/ImageBytes.kt     ≤1600px JPEG downscale before upload
│     └─ ui/
│        ├─ auth/               AuthViewModel + LoginScreen/LoginContent
│        ├─ search/             SearchViewModel + SearchScreen (the home screen)
│        ├─ totes/              ToteList + ToteDetail, with their ViewModels
│        ├─ capture/            CaptureViewModel + CaptureScreen/CaptureContent
│        ├─ review/             ReviewViewModel + ReviewScreen, DraftBadgeViewModel
│        ├─ navigation/         four tabs + the pushed detail route
│        ├─ components/         HazardRule, ToteButton
│        └─ theme/ToteTheme.kt  semantic layer over PULSE
├─ server/                      FastAPI backend
│  ├─ app/
│  │  ├─ main.py                app factory, middleware, /health + /version
│  │  ├─ config.py              pydantic-settings; env > .env
│  │  ├─ database.py            async engine, session factory, DeclarativeBase
│  │  ├─ security.py            session JWTs + CurrentUser dependency
│  │  ├─ limiter.py             slowapi rate limiting
│  │  ├─ apparel/               controlled vocabularies + normalizers (from Crate)
│  │  ├─ sizing/                the size ladder — pure, no I/O
│  │  ├─ models/                the eleven tables of §4
│  │  ├─ routers/               suite_auth, users, catalog, totes, items, public, scan, people
│  │  ├─ schemas/               request/response models
│  │  └─ services/
│  │     ├─ suite_auth.py       JWKS validation + find-or-create
│  │     ├─ movement.py         THE single writer of whereabouts
│  │     ├─ catalog.py          read-side joins, counts, local_today()
│  │     ├─ card.py             the printable index card + the shared tag URI
│  │     ├─ cleanup.py          rembg + Pillow; levels BEFORE compositing
│  │     ├─ photo_store.py      binaries on the volume, paths in the DB
│  │     ├─ scan_pipeline.py    photo → draft (+ the label pass)
│  │     ├─ apparel_draft.py    a label reading → an item_apparel row
│  │     ├─ apparel_write.py    THE single writer of item_apparel
│  │     ├─ fits.py             "what fits Emma right now"
│  │     ├─ ntfy.py             self-hosted push; fail-soft, never ntfy.sh
│  │     ├─ sizing_hints.py     should this item get a label pass at all
│  │     └─ ai/                 vision transport, prompt, salvage parser
│  ├─ alembic/versions/0001_    the whole schema
│  └─ tests/                    pytest, asyncio_mode=auto
├─ deploy/backup.ps1            verified DB + photos backup set
├─ scripts/synthetic_smoke.py   post-deploy smoke: auth AND a real scan through the pipeline
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

### Session renewal

An access token lives 30 minutes; the refresh token that comes with it lives 7 days.
`POST /auth/refresh` redeems one for a new pair. It is **not** gated on
`SUITE_JWKS_URL`/`SUITE_ISSUER` the way `/auth/suite` is — the refresh token is Tote's own HS256
session token, so renewal needs no identity server, and a momentarily unreachable dragonfly-id
must not log every phone out. It is stateless (signature + `"type": "refresh"` claim, no DB
lookup); a deleted user still fails at the next authenticated call, where `get_current_user`
checks the row.

On the client, `TokenAuthenticator` (an OkHttp `Authenticator`, so it fires only on a 401 of a
request that already carried credentials) renews and replays the call through `RefreshApi` — a
second, bare OkHttp client with no auth interceptor and no authenticator, because a 401 on the
renewal itself would otherwise recurse. Note that OkHttp does **not** re-run application
interceptors on an authenticated retry, so the authenticator sets the new `Authorization` header
itself rather than leaving it to `AuthInterceptor`.

Three rules there are each a bug if dropped, and each has a test:

- **One attempt.** Returning a request from an `Authenticator` re-runs it; without the
  `priorResponse` stop condition a permanently-rejected token loops forever.
- **Sign out only on a 4xx.** A dead refresh token is unrecoverable and clearing the store is
  what returns the app to the sign-in screen (`signedIn` is derived from the stored access
  token). An unreachable server is *not* unrecoverable — clearing there would sign the user out
  on any Wi-Fi blip, in a garage, which is where this app is used.
- **Single-flight.** Refresh tokens rotate, so two calls 401ing together would otherwise race and
  the loser would redeem an already-spent token and sign the user out mid-session.

This whole path was missing until 2026-08-16, and its absence was a production incident: the
server minted a refresh token, the client stored it, and nothing could redeem it. Thirty minutes
after each sign-in every call 401'd permanently, while the app still considered itself signed in
and the UI blamed the tailnet. `util/ApiErrors` now maps a failure by status — no HTTP status at
all is the genuine can't-reach-the-tailnet case, and 401 says the session expired — because copy
that names the wrong cause sends the next hour of debugging to the wrong place.

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

## Adding to the seeded vocabulary

`DEFAULT_CATEGORIES` is written **once, at first login**, and never looked at again. That is
correct — the vocabulary belongs to the user and re-seeding would resurrect names they deleted —
but it means adding a name to the tuple reaches new accounts and **nobody who already has one**.
Which, on a single-household app, is nobody at all.

So a new seed name is two changes: the tuple, and a data migration that back-fills every existing
user. A migration rather than a hand-run `INSERT` on the box, because a statement typed into psql
is lost the next time the database is restored from a backup — and a household inventory is
exactly the kind of thing restored years later.

Three properties the back-fill needs, all of them tested:

- **Appended at the end of each user's own ordering**, not slotted in at the position it holds in
  the tuple. Renumbering every existing row would rewrite an ordering the user may have arranged,
  to move one row a few places up a list of twelve.
- **Idempotent by hand as well as by Alembic** (`WHERE NOT EXISTS`, matched case-insensitively).
  `uq_categories_user_name` would raise on a second pass, and a data migration that cannot be
  re-run against a half-migrated restore turns a bad afternoon into a worse one. Case-insensitive
  because somebody who typed their own is as likely to have written "baby", and two rows a picker
  shows as the same word is the fragmentation the categories table exists to prevent.
- **The downgrade only removes rows nothing was filed under.** The schema going backwards is no
  reason to lose data; a category left behind is a far smaller problem than an item that lost the
  one it was in.

The test database migrates to head before any user exists, so the back-fill is a no-op there and
would otherwise ship entirely unexercised — which for a data migration is the same as untested.
The statement is a module-level constant in the migration so `test_category_backfill.py` can run
it directly against a real Postgres.

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

## Client

**Search is the home screen.** The app's job is answering "where is the X", so a browse list
first would make the common case the second thing someone sees. The tote list is the second tab;
tote detail is a pushed route, not a tab, so the bottom bar hides there.

### The offline cache, and its limits

`CatalogCache` (Room) holds a snapshot of the catalog so the app works in the attic and the
garage, which is exactly where the Wi-Fi is worst. A catalog you cannot read standing in front of
the bins is a catalog you stop using.

Three decisions keep it honest:

- **The server stays the source of truth.** Nothing is written back from the cache; every
  mutation goes to the API and then refreshes the snapshot. There is deliberately **no
  write-behind queue** — a move recorded only locally would be a hole in the server's ledger, and
  the ledger is what this app is built around. A move is an instruction that can be retried; a
  photo (Phase 4) is new data that cannot be re-derived, which is why *that* earns a queue and
  this does not.
- **Sync is clear-then-insert in one transaction**, not upsert-and-reconcile. An item deleted on
  the server has to disappear here too; an upsert-only sync leaves tombstones, and a tombstone in
  this app means a trip to the attic for something that no longer exists.
- **Offline results are labelled offline.** The cache matches with `LIKE`, not Postgres
  full-text — reproducing stemming and ranking would drift from the server's answers, and two
  different notions of "matches" is worse than one honest simpler one. Presenting them
  identically would quietly teach that search is inconsistent.

`toteCode` and `locationName` are stored denormalised because the server already denormalises
them: offline search must answer "which bin, and where is it" without a join the cache cannot
reliably reproduce.

### Screen decisions

- A search hit shows **bin and location on the row**, not a tap away — that *is* the answer, and
  making someone open a detail screen to see it turns a one-glance question into two taps.
- Tote detail surfaces **"Out of this tote"** as its own section. That gap is the answer to "I
  thought the lights were in here".
- Unpack and repack are **mutually exclusive** on screen: showing both at once invites the wrong
  tap on a bin already open on the floor.
- Bulk operations send `itemIds = null` for "everything". `encodeDefaults` is on so the null is
  sent explicitly, preserving the server's distinction between null and `[]`.
- Tab navigation uses `popUpTo(startDestination) { saveState = true }`. Without it, bouncing
  between tabs grows the back stack and Back walks the whole history instead of leaving the app —
  a bug Crate shipped and had to fix later.
- A "0 out" count is hidden rather than shown as zero: a permanently-present field trains people
  to ignore it exactly when it stops being zero.

### Real migrations, no destructive fallback

`DatabaseModule` has **no** `fallbackToDestructiveMigration()`. It used to, and that was safe only
while this database held nothing but a disposable copy of server state. From Phase 4 the photo
capture queue lives here: forty photos taken in a garage with no signal, which the server has
never seen. For that data this database is the **only** copy, and a destructive fallback would
delete it on the next schema bump — silently, no crash, leaving the JPEGs orphaned on disk with
nothing recording what they were of.

Without the fallback, a missing migration is a **loud** failure: the app refuses to open the
database. That is the right way round — a crash gets fixed, a silent wipe gets discovered months
later by someone who thinks the camera is broken. The job of the tests below is to move that
failure from a phone to CI.

| Piece | Where | What it guarantees |
|---|---|---|
| `schemas/…/<n>.json` | committed | the record of what version *n* looked like. Nothing can be validated without it, so a test asserts it exists |
| `ToteMigrations.ALL` | main | the only way the database moves between versions |
| `ToteDatabaseMigrationTest` | **JVM, every PR** | every shipped version can still reach the newest through the migration graph, and no migration names a version that was never exported |
| `ToteDatabaseMigrationAndroidTest` | **on device, by hand** | the migrations actually produce the schema they claim, column for column |

The split is deliberate. Room's `MigrationTestHelper` needs real instrumentation, and this suite's
CI has no device — so the *fatal* mistake (bump without a migration) is checked on the JVM where
it can run on every PR, and the *subtler* one (a migration whose SQL is slightly wrong) is checked
on device. The alternative was to skip both because the perfect check cannot run in CI.

**The JVM guard discovers versions from the filesystem**, so nobody has to remember to update it
when version 3 appears. Verified by temporarily bumping to version 2: with no migration it fails
with *"No migration path to version 2 from version(s) [1]"*; with one it passes. Both directions
were checked before the experiment was reverted.

## NFC, the QR, and the index card

### The tag is a pointer, never the truth

A written tag encodes one thing: a URI containing the tote's **code**. Contents are always
fetched live. That is what lets a tag written a year ago still open a bin that has since been
renamed, relabelled, moved and refilled — a tag is a physical object in an attic that no deploy
can patch, so anything it asserts beyond identity would eventually be a lie.

The URI is built from a **code**, not an id, for the same reason: the code is the one identifier
that is also printed on the card, readable by a human, and stable across schema changes. The
base comes from `GET /nfc/base` rather than being compiled into the app, so the value being
burned into physical objects has exactly one source.

The second NDEF record is a short human summary, and it is explicitly a **cache**. Any phone's
stock NFC reader shows it with no app installed. It goes stale as contents change and that is
fine — the app never reads it, and rewriting every tag whenever an item moved would make tags
wrong far more often than right.

### `/t/{code}` is the only unauthenticated surface

Its security property matters more than the page does: **it must not leak contents.** Anyone who
can read the tag already knows the bin exists; what they must not learn from it is what is
inside. So the page shows the code and nothing else — not the label, not the location, not the
count. "A14, open it in Tote" is a useful dead end; "A14 — Christmas decor, Attic, 37 items" is
an inventory printed on the outside of the box. Asserted in tests, including that the code is
escaped, because it arrives from a tag anyone could have written.

### Why not App Links

The tap-to-open filter is `NDEF_DISCOVERED` on the scheme/host/port/path, deliberately **not**
an App Links filter with `autoVerify`. App Links would require a reachable
`.well-known/assetlinks.json` on the host, and the host is tailnet-only. NFC dispatch matches the
filter directly with no verification step. The **port is part of the match**, because the suite
shares one hostname across apps on different ports.

`MainActivity` is `singleTask`, so a second tap while the app is open arrives through
`onNewIntent`, not `onCreate`. The launch intent is therefore Compose state, not a field read
once — reading it only in `onCreate` would make the first tap work and every later one silently
do nothing, which looks exactly like flaky hardware.

### Writing, and its three real failure modes

Writing uses a foreground **reader-mode** session that is live only while the write sheet is
open, so the radio is never left armed and a tap outside that sheet still does the normal thing.
Reader mode rather than foreground dispatch because it suppresses the platform's own handling —
otherwise holding a tag to the phone would bounce the user to the very landing page they are
trying to write.

| Failure | Response |
|---|---|
| Tag locked | say so; nothing else to do |
| Too small | retry with the summary dropped — the URI is the half that matters. Only fail if even that will not fit |
| Moved away mid-write | report it; the tag may be half-written |

**The uid is recorded on the server only after the physical write succeeds.** Recording first
would leave the database claiming a tag that was never written, discovered in an attic. If the
write succeeds but the record fails, the app says *that* specifically, so nobody rewrites a tag
that is already correct.

A tag's **hardware uid** is stored and is unique per user, so reusing one tag for a second bin is
a 409 rather than a silent reassignment. `GET /totes/resolve/{code}` reports a **mismatch** when
the tapped tag is not the recorded one — but still resolves. Someone standing in an attic holding
a bin needs the answer; refusing would strand them.

### The card

Rendered server-side as a 5×3in PDF so there is exactly one layout for a physical object. The
code is set enormous and everything else small: the reader is at arm's length in front of a stack
of identical bins. The printed count says "when printed", because a printed count is a snapshot
and saying when it was true is the difference between a stale number and a lie.

The QR **encodes the same URI as the tag**, via the same `tote_uri` builder — that is what makes
the redundancy real, and a test captures what actually reaches the QR encoder during a render to
keep the two from ever forking. (The rendered pixels were decoded by hand once, 2026-08-16, and
matched; the seam test is what keeps it true without a 60 MB OpenCV install on every CI run.)

The card is mono on purpose. Most people print on a mono printer, and the yellow that makes the
app read as a tote would come out grey.

## Photo capture

### Nothing a model produces enters the catalog

A scan produces a **draft**: excluded from search, from `/items`, and from a tote's contents
until a human confirms it. `POST /drafts/{id}/confirm` is the only path from a photograph to a
catalogued item, and it is what writes the `initial` movement row. The house AI rule is that
nothing model-generated is committed without explicit approval, and Tote has no exception to it —
unlike Crate, which has one documented carve-out for its deterministic price-drop scheduler.

The exclusion lives in `item_query`, so it applies to every read path at once. Adding a new
listing endpoint cannot accidentally surface drafts.

### The order of the pipeline is the design

1. **Persist the originals.** First, before anything else runs.
2. Clean them (rembg + Pillow), in a worker thread.
3. Identify (LM Studio).
4. Save the draft.

Step 1 is first because the photograph is the only artefact that cannot be re-derived. The item
was in someone's hands in a garage and is back in a bin by the time anything downstream fails.
Everything after the write degrades: a failed cleanup logs and continues, and an unreachable model
still yields a draft with the photo attached.

**Identification reads the ORIGINALS, not the cleaned copies.** Measured in Crate: originals win,
and cleanup is unpredictable on some subjects — it once decided a woven brand tab was "the
subject" and cropped the shirt away. A cropped photo cannot be un-cropped for the model. The
cleaned copies exist for display.

### Three inherited fixes that must not be lost

| Fix | Why |
|---|---|
| **No `max_tokens`** on the vision call | the pinned model is a *reasoning* model: hidden reasoning tokens share that budget and it emits no content until done. An answer-sized cap silently returns `""`, which every parser reads as "unreadable photo" — a total failure that looks like a model limitation rather than a config mistake. A test asserts the request body never grows one. |
| **Levels before compositing** | applying them after makes the subject the darkest content in a synthetic white-ground composite, so the shadow clip lands on the subject. In Crate this turned *every* garment colourway pure black. Guarded by a pure-black-fraction assertion on decoded pixels. |
| **`preserve_tone=True`** | per-channel stretching wrecks a saturated subject's hue. Measured on the test fixture: a red subject at (183, 82, 85) keeps its red as (173, 56, 57) with the flag and collapses to a muddy (81, 58, 59) without. |

### Transport failure ≠ content failure

They are mapped to different outcomes because they need different responses from a human:

- **Transport** (unreachable 503 / timeout 504 / rejected 502) raises, and the pipeline records
  `scan_error = "identify_unavailable"` **and logs a warning**. A stored-but-unlogged failure
  would leave `docker logs` silent during exactly the outage someone is diagnosing.
- **Content** (garbled or unparseable reply) degrades to a low-confidence empty draft. The photo
  was hard; a human can fill the rest in.

Collapsing the two would make a dead container and a bad model pin indistinguishable from a
blurry photograph.

### The model may only use the user's own vocabulary

Categories are listed in the prompt and matched back **case-insensitively against the real list**.
A near-miss is dropped, not fuzzy-matched — filing something into the wrong category is the quiet
error that makes a catalog untrustworthy. Same asymmetry as everywhere else: vision output
degrades to null, a human `PATCH` of the same field rejects with a 422.

An implausible `quantity` (zero, 100000, `"four"`, `true`) is dropped rather than coerced. A wrong
count silently changes what the catalog claims you own.

### Storage

Binaries on a named `photos` volume, paths in the DB, filenames **server-generated** so a crafted
upload name cannot traverse paths. The `order` integer in the filename is the same one on
`item_photos.order`, which is why that column is never renumbered — renumbering orphans every
file. Discarding a draft deletes its directory, for the same reason from the other direction.

The U2-Net weights are **baked into the image** at build time. Otherwise the first scan after
every deploy stalls on a ~170 MB download, and fails outright on a host with no egress.


### Cleaned photos keep their alpha

Cleanup is levels → background removal → crop-to-subject, and the result is stored as an **RGBA
cutout, not composited onto white**. White is the eBay convention Crate needs and it was inherited
wholesale; a household catalog read on a phone that is usually in dark mode is not an eBay
listing, and every photograph rendered as a glaring white card in a charcoal list. Transparency
lets each photo sit on the surface it is on and look right in both themes — the same reasoning as
the accent's light/dark role swap. The client already draws photos on `colors.panelHigh`, so no
client change was needed.

**The model never sees this file**, which is what makes the change safe: `scan_pipeline` sends the
ORIGINALS to both the identify and the label pass, measured in Crate as the better input. Worth
knowing before anyone points a vision call at a cleaned copy — most stacks flatten alpha to
**black**, which would put a dark subject on a dark ground and be strictly worse than the white
this used to store.

One consequence in the tests, and it matters more than the change itself: `Image.convert("RGB")`
maps a transparent pixel to black, so the blackening guards would have measured the cut-out ground
as if it were subject and fired permanently. The obvious fix for that — loosening the thresholds —
would have quietly disabled the only test that ever caught the real defect. Instead the fixtures
measure **visible pixels only** (`_visible_pixels`), and the brightness assertion is now relative
to the subject the camera saw rather than an absolute floor: the old `overall_mean > 50` passed
because a field of 255s dominated the average, which is to say it was mostly measuring the
background it is now the absence of.

## The capture queue (client)

The one write-behind queue in the app, and the only table in the local database whose contents
exist nowhere else. Everything else in Room is a disposable copy of server state; a queued
capture is a JPEG of an object that was in someone's hands in a garage and is now back in a
taped bin. That asymmetry is the reason `DatabaseModule` has no destructive fallback.

The flow is built for the place it is used. Cataloguing happens at an open bin in an attic or a
garage — where the Wi-Fi is worst — so nothing in the capture path waits on a network:

1. **Shoot** 1–8 photos of one item. Camera output goes to `filesDir/captures/` through a
   `FileProvider`; the gallery path copies to the same place.
2. **Queue.** The photos move to `filesDir/capture_queue/{id}/` *before* the Room row is written.
   A queue row pointing at a JPEG that is not there is worse than no row — it claims work that
   cannot be done or reconstructed.
3. **Drain.** `UploadWorker` (WorkManager, `CONNECTED` constraint, exponential backoff) calls
   `CaptureQueueRepository.drain()`, which downscales each photo and posts it to `/items/scan`.

The **destination bin is chosen once and remembered** across captures, and rides along as the
scan's `tote_id`. The server records it as the draft's *suggested* destination and does not apply
it — an item enters a tote only when a human confirms. Without this, a batch session would mean
the same dropdown tap fifty times at review.

Downscaling to ≤1600px happens **at drain time, not at the shutter**. It is a requirement, not an
optimisation: a modern phone camera clears the server's 8 MB cap on a single frame, so without it
a bin's worth of captures 413s one at a time after the bin is closed. Doing it late means the
full-resolution original survives on disk until the server has it — resized at capture, a failed
upload would leave only the lossy copy of a photo that cannot be retaken.

### Nothing in the capture path lives in `cacheDir`

Staging used to be `cacheDir/captures/`, on the reasoning that a photo between the shutter and
the Queue tap is transient. It is not transient — it is the **only copy in existence**, exactly
like a queued one, and it is held for as long as it takes to photograph an item, which can be
minutes with the app backgrounded.

Android empties cache directories without warning when storage runs low. On a phone at 100% full
it did: the staged JPEGs were deleted out from under the app between the shutter and the tap, and
`queueItem`'s bare `copyTo` threw `NoSuchFileException` **on the main thread**. The app died
mid-batch and took every other shot in hand with it — twice in eleven minutes, in production, on
2026-08-17. The `file_paths.xml` comment had stated the rule correctly the whole time ("a cache
directory is one the OS may empty at any moment") and the staging directory was in one anyway.

Three consequences, and all three are needed — any one alone leaves a hole:

- **Staging is `filesDir/captures/`.** The OS does not reclaim it.
- **A missing source is skipped, never fatal.** "Should not happen" is no reason for a batch to
  be destroyed if it does. The count of skipped photos is **said out loud**: a batch that quietly
  queues 3 of 5 leaves two holes in the catalogue nobody knows to go back and fill. If *every*
  file is gone, no row is written and the person is told to shoot again while the bin is still
  open in front of them.
- **Shots in hand survive process death**, persisted as paths through `SavedStateHandle` — the
  same reason the destination bin is. Shooting a bin's worth in a garage with the app backgrounded
  between photos is precisely when Android kills the process; held only in memory, a half-shot
  item vanished silently and left its files orphaned.

Durable staging does not clean itself, so the ViewModel **sweeps orphans** on construction:
anything in the directory that is not in the restored shot list belongs to a session that is gone.
Left alone it would grow the app's footprint forever, on a phone that is already out of space —
which is how the whole failure started.

The card PDF stays in `cacheDir`, correctly: the server re-renders one on demand, so it is the one
file here the OS is welcome to reclaim.

**`FileProvider` cannot be exercised under Robolectric in this project** — `getUriForFile` fails
to resolve *any* configured root, including ones that predate this change — so `newCameraTarget`
has no JVM test and `stagingDir` is `internal` to let the location be asserted directly.

### Three drain outcomes, because there are three different situations

| Failure | State | Next |
|---|---|---|
| `IOException` | `pending` | offline/transient — WorkManager retries with backoff |
| `HttpException` | `failed` | the server answered and said no. Surfaced; never auto-retried |
| `SocketTimeoutException` | `uncertain` | **nobody knows whether it landed** |

The third is specific to this app. `POST /items/scan` is **synchronous** — it persists, cleans
and identifies every photo before it responds, measured at 35.5 s for one photo against the live
model — so a client timeout is not evidence of failure. It is more likely evidence the server is
still working and the draft will exist. The row says "it may already be in Review, check there
before retrying", and offers both, because only a person can say whether a capture whose fate is
unknown is worth waiting on.

What a retry *costs* changed on 2026-08-16 — see below.

`drain()` reports **clear** for `uncertain` and `failed` rows. Those wait on a person, and telling
WorkManager to retry would spin the backoff chain against a queue that cannot move.

A drain also begins by releasing anything left in `uploading` by process death. Uploads run for
tens of seconds — ample time for Android to kill a backgrounded app — and an `uploading` row
whose uploader no longer exists would otherwise sit out every future drain forever.

### Every attempt carries `capture_id`, and that is what makes a re-send safe

The queue row's own id, sent as a form part on every attempt for that capture. The server stores
it on the item (`items.capture_id`, unique per user, migration 0003) and a repeat resolves to the
draft the first attempt already made — including one that has since been confirmed into the
catalog. A scan with no key still works, so an older APK keeps functioning; with no key there is
simply nothing to deduplicate on, and guessing from the pixels would merge two identical-looking
ornament boxes into one.

This closes a real production failure. **One photograph became four drafts on 2026-08-16.** The
endpoint commits *before* it answers, so three uploads that had their connection cut after the
commit were indistinguishable, from the client, from uploads that never arrived — the server had
no access-log line for any of them, because uvicorn logs when a response starts and none did.
`releaseStranded` then did exactly what it was written to do and re-sent each one. Nothing in the
three-outcome table above catches this: `uncertain` only covers a socket timeout, and a connection
cut mid-flight arrives as an `IOException`, which is correctly treated as "retry me".

The rule the key encodes: **the id must be stable across attempts.** A freshly generated UUID per
attempt is a key that never matches, which is exactly as useless as no key at all and looks like
it is working. `CaptureQueueRepositoryTest` asserts the same key on a second drain and on a
released stranded row.

### The scan call needs its own timeout, and only it

OkHttp's default read timeout is 10 s against a call measured at 35.5 s, so without an override
*every* scan fails — as a timeout, which the queue must then treat as unknown. The queue would
fill with unresolvable rows for uploads the server was quietly completing, and the symptom would
read as a broken camera. `ScanTimeoutInterceptor` raises read/write timeouts to 240 s/120 s for
`/items/scan` alone. Raised globally instead, a dead tailnet connection would hang the search
screen for four minutes rather than failing fast into the offline cache.

### Room v2

`capture_queue` is the v2 migration, and it is purely additive. `ToteMigrations.MIGRATION_1_2`
copies its DDL verbatim from the committed `schemas/…/2.json` rather than restating the entity;
the on-device test compares column for column, and a hand-typed nullability difference is exactly
what reads as correct and fails there.

## Items are shown, not just listed

A catalog of physical objects is recognised by sight long before it is read. Someone scrolling a
bin's contents is matching pictures against a memory of the thing, so every list that shows items
— tote contents, search results, what fits, what someone has on loan — carries the photograph.

Two rows both reading "Toddler Bed Comforter" are indistinguishable as text and obviously
different as pictures, which is precisely the moment someone notices they filed one twice. That
is not a hypothetical: it is what the owner's first real bin looked like.

`ItemThumbnail` is driven by **`ItemOut.photo_count`**, a correlated subquery on `item_query` so
every read path gets it without an N+1. The client must not discover "no photo" by requesting one
and seeing what comes back — an item added by hand has none, and a 404 per row over the attic's
Wi-Fi is the wrong way to learn that. Items without a photograph get a placeholder frame rather
than a collapsed row, so the list does not jump as images resolve.

**The row could not hold everything.** With a thumbnail, a name, "Lend" and "Take out", the name
truncated to "Toddler Be…" — a row that has failed at its only job. So the row carries the
thumbnail, a two-line name and the *everyday* action; lending and deleting moved into the item
sheet behind a tap — and the sheet has since grown into the place every per-item operation lives.

## The item sheet: one item, and everything that acts on it

The sheet started as an alert dialog holding a photograph and a delete button, which is roughly
what fits in a dialog. Everything else an item needs did not fit, and so did not exist — three
capabilities the server had shipped and the client had never called:

- **Editing a filed item.** `patchItem` had zero callers. A name typed wrong at review time was
  permanent, and the sanctioned remedy was to delete the row and photograph the thing again —
  which destroys the photographs, the only artefact here that cannot be recreated once a bin is
  taped shut.
- **Moving it between bins.** The core verb of a bin app had no button anywhere. `putBack`
  hardcoded the bin it was already in, so relocating something meant delete-and-retype, taking
  the ledger with it.
- **Its history.** `movements()` had zero callers. The ledger has been written faithfully since
  Phase 2 and "where was this last year" — the question it exists to answer — was answerable by
  the database and by nothing a person could tap.

It is now `ui/items/`: a `ModalBottomSheet` over whatever screen opened it, a stateless
`ItemSheetContent` (a sheet renders in its own window and never reaches idle under Robolectric —
the same constraint that split `PickerList` out of `PickerDialog`), and one `ItemSheetViewModel`.

**It takes the `ItemDto` the caller already holds** rather than fetching by id. All three callers
have the full row on screen, so a fetch would be a spinner over data already visible; the write
paths return the updated item, so the sheet stays true without one.

**The pickers are modes of the sheet, not dialogs over it.** A dialog inside a modal sheet is two
scrims and two Back handlers, and the Back that cancels the picker is indistinguishable from the
one that throws away the edit underneath.

### Four rules the sheet is built on

1. **The PATCH body names every field the form owns.** `encodeDefaults` is on in the Json config,
   so a null is serialised as an explicit null, and the server's `exclude_unset=True` treats a
   present null as *clear this*. `ItemUpdate(name = "x")` would therefore blank the description,
   the category and the condition, and set `quantity` to null against a NOT NULL column. The
   endpoint having had no callers is the only reason that was never discovered in production.
2. **The clothing block is omitted unless somebody touched it** — the confirm body's rule,
   extended to the edit path. The server skips a null `apparel` entirely, so an untouched section
   means "leave what the label read". Most items are not garments and most edits never scroll
   that far; clearing it would destroy the only reading of a tag now sealed in a bin.
3. **Whereabouts never goes through PATCH.** Moving is `POST /items/{id}/move`, so every
   relocation leaves a ledger row: `moved` for something stored, `repacked` for something out.
   Two writers of `current_tote_id` would put holes in the one record this app promises has none.
4. **The change is reported after the sheet closes**, because a delete and a move both close it.
   The collector therefore lives *above* the composable's early return — one that only existed
   while the sheet was open would be gone when the report arrived, and the screen behind would go
   on showing a row that no longer exists.

### Where it opens from

The bin's contents, a **search hit**, and a person's fits and loans. All three were previously
dead ends. A search hit's tap was guarded on `currentToteId`, so a row for anything lent out or
unpacked — exactly the things people search for, because they cannot find them — did nothing at
all and said nothing about why. A person's rows could not act on the item at all.

One caller-supplied verb rides in as `extraActionLabel`: the person screen passes "Mark
outgrown…", which needs a person and so has no business being knowledge the sheet holds.

### Two bugs found on the way

**Lending had been unreachable since the picker round.** `LendDialog` imported `PickerDialog` and
never rendered it, so tapping "Lending to" set a flag nobody read, no person could be selected,
and the Lend button — enabled only once one is — could never enable. Nothing failed; the screen
simply did not respond, which is the failure mode hardest to report and easiest to blame on the
tailnet.

**Manual add collected two fields of six.** A hand-added item was permanently uncategorised with
no edit path to fix it, so it was a strictly poorer record than a photographed one for no reason
anyone had chosen. It now takes a description and a category; condition and the clothing block
are a tap away in the sheet, where a filed item is edited.

## Deleting an item

The catalog gets things wrong in one way that editing cannot fix: a row that should never have
existed. A duplicate, a typo, a photograph of the wrong thing. Without a delete the only remedy is
to live with a bin that claims two comforters when it holds one — and a bin that lies once stops
being believed, which is the whole asset this app has.

`DELETE /items/{id}` is a hard delete and takes the ledger with it. It is **not** the same
operation as disposal: "we no longer own this" is a `disposed` movement and keeps its history.
The confirmation says so, because the two are easy to confuse and only one is recoverable.

**It also deletes the photographs from disk**, which it did not until 2026-08-16. The rows
cascade; the files did not, so every deleted item left its photos on the volume forever —
invisible, listed by nothing, and archived faithfully by every nightly backup. In an app where
the photos ARE the artefact and the rows are paths pointing at them, that is the leak that
matters. The call happens *after* the commit, so a failed delete can never destroy the one thing
that cannot be recreated.

The affordance lives one tap deep, in the app's error voice, behind its own confirmation — never
beside "Take out" on the row. A destructive action next to an everyday one is a mis-tap away from
taking a photograph that cannot be retaken once the bin is taped shut.

## A bin is editable, placeable and archivable

Everything about a bin was fixed at creation. The dialog collected a code and a label, and nothing
in the app could change either afterwards — no notes, no category, and, the one that actually
mattered, **no way to say where the bin physically is**. `PATCH /totes/{id}` and the whole
`/locations` CRUD had shipped in Phase 2 and had **no caller anywhere in the client**, so every
row in the catalogue read "A14" with nothing after it and the browse-by-location entry point named
in the product summary did not exist.

### `ToteOut.location_name` — the round's only server change

Denormalised beside `location_id`, exactly as `ItemOut` already does it. Populated from one
`location_names()` map fetched per request rather than a join per row: a household has a handful
of places against however many bins, so one small query beats an N+1 on the endpoint that backs
the browse screen. No migration — it is a read-side field over a column that has existed since
`0001`.

A bin that moves out of a place returns `null`, not a stale name. "Attic" left behind on a bin
now in the garage is worse than no answer, because it is an answer somebody would act on.

### The list is grouped by place, and archived is collapsed rather than hidden

A flat alphabetical run of A14, A15, B02, G01 is a list of codes, and remembering codes is
precisely the work this app exists to remove. Bins group under their location, alphabetically,
with **the placeless ones last under their own heading** in the attention channel. Last rather
than first because a loose end at the top is in the way of every browse; under its own heading
rather than folded into the first real place because that would be a lie about where they are.

Archived bins are cached (`api.totes(includeArchived = true)`) and shown behind a collapsed
"Archived (N)" header. An archived bin is a physical box that still exists somewhere — it is off
the daily list, not out of the catalogue, and a snapshot that dropped it would make "where did A14
go" unanswerable offline, which is where it would be asked.

### `TotePatch` names every field, for the same reason `ItemUpdate` does

`encodeDefaults` is on and the server's `exclude_unset=True` reads a present null as *clear this*,
so a sparse body built from defaults would set `code` null against a NOT NULL column. The client's
`TotePatch` therefore has **no default values at all** — a partial one cannot be written by
accident. That is why archiving carries the code, the label, the location and the notes with it,
and why editing carries `archived` through unchanged: an edit must never quietly un-archive a bin.

### Changing a bin's code is a change to a physical object

The one place in the app that warns about the world outside it. The code is written on an index
card in permanent marker, encoded in that card's QR, and written into the NFC tag's URI as
`/t/A14` — and the server resolves that path **by code**. So renaming a bin does not update the
tag; it makes the tag stop resolving, and the tap lands on "no such bin" in an attic, over a box
that is sitting right there. The warning appears as soon as the code field diverges and the bin
has a tag, before the save rather than after it.

### Delete says what it actually does

"Delete the bin" reads as "delete everything in it" to anyone who has not read the schema. It does
not: `ON DELETE SET NULL` leaves every item catalogued and in no bin, because throwing a box away
must never erase the record of what was in it. The confirmation says so, counts the items that
will be left unfiled, and offers archiving — which is nearly always the operation actually wanted.

### Two smaller things that were simply missing

- **The tag's text record carries the location now.** The spec said it always did; the call passed
  a hardcoded `null`. That record exists for a stock NFC reader on a phone without Tote, and
  "A14 — Christmas decor" with no place is the half of the answer the person already had in their
  hands.
- **`nfc_written_at` reaches the UI.** It was on `ToteOut` from Phase 3 and rendered nowhere. A tag
  written before the bin was renamed carries the old text, and the date is the only way to know
  that from the app.
- **Creating a bin navigates to it.** Creating one is never the goal — labelling it is, and the
  tag and the card live on its detail screen. The dialog used to close onto the list, leaving the
  screen that finishes the job a scroll and a tap away. That is how a bin ends up catalogued and
  unlabelled, which is the exact failure the app exists to prevent.

## Picking one thing out of a list that grows

Every choose-a-bin, choose-a-category, choose-a-person control was a horizontally-scrolling strip
of chips. That shape is fine for five fixed options and wrong for anything that grows: the options
run off the edge, you cannot see how many there are, and finding "G07" among thirty bins means
dragging sideways past twenty-nine others. The catalog is *supposed* to reach fourteen bins and
beyond — that is the product's own problem statement — so the control has to scale with it.

`PickerField` + `PickerDialog` (`ui/components/Picker.kt`) replace it. The closed state is a
field showing the current choice, because most of the time it is being **read** rather than
changed — someone glancing at the capture screen to confirm the bin before shooting twenty
photographs into it. The open state is a vertical list with a search box, appearing only once
there are enough options to be worth filtering.

Applied to: the capture screen's destination, review's category and destination, the
outgrown/returned bin picker, and the lend-to-person picker.

**Chips stayed where chips are right** — condition, department, garment type. Five options that
never change are faster to compare side by side than behind a tap, and they read as a set rather
than a lookup. What they got instead is `FlowRow` in place of `LazyRow`: they now **wrap** rather
than scroll, so "Poor" and "Unisex" stop being clipped at the screen edge. The rule is about
user-grown lists, not about chips being bad.

Three details that are decisions rather than styling:

- **Search matches the detail line as well as the label.** Someone hunting for a bin thinks "the
  one in the attic" as readily as "A15". `matchOptions` is pure and tested for exactly this,
  including that a word from the *middle* of a label matches — prefix-only matching would mean
  "blankets" finds nothing, because every label starts with the bin code, which is the part
  someone is least likely to remember.
- **"None" is a row, not a second tap on the selection.** "Tap the selected chip again to clear
  it" only works if you already knew it. Where clearing is legitimate (category, capture
  destination) it is a labelled row; where it is not (filing at review, an outgrown destination)
  there is no such row, because an item confirmed into the catalog and in no bin is
  indistinguishable from a bug.
- **No confirm button.** Picking is the confirmation. A list where every row is a decision and
  there is still an OK to press is a list you tap twice for no reason.

`PickerList` exists as a separate composable from `PickerDialog` for a test reason worth knowing:
an `AlertDialog` renders in its own window and a Robolectric screenshot of one **never reaches
idle** — it times out after 60 s of composition attempts. The list is the part whose layout is
worth verifying, so it is the part that can be rendered alone.

## The outcome of a write is said out loud

`FeedbackBus` (`util/Feedback.kt`) is the app's one voice for what a write did: a process-wide
`SharedFlow` that ViewModels emit into and the single `ToteNav` Scaffold renders as a snackbar.
Process-wide rather than per-screen because the writes that most need a voice finish after their
screen is gone — a queued upload failing, a confirm landing as the review stack advances.

**The rule: only user-initiated writes speak.** A passive refresh that fails stays silent; the
screens carry their own offline states, and a snackbar per failed poll would turn a bad Wi-Fi
day into a notification stream that teaches dismissal. One message at a time, newest wins.

What closed with it, all found in real use:

- **Filing said nothing.** File-it's only visible effect was the form being replaced by the next
  draft — and because `ReviewViewModel` bypasses `CatalogRepository` for reads, a confirm never
  refreshed the Room snapshot either, so the tote list and Find tiles kept stale counts until
  some other write happened. Confirm now announces "Filed X into A14" (the bin CODE — what is
  written on the physical box) and refreshes the catalog.
- **Every write on the bin screen swallowed failure** (`runCatching{}.onSuccess` with no
  onFailure): offline in the attic — the documented normal condition — the buttons simply
  looked broken. Each now speaks its failure through the bus via `ApiErrors`.
- **Queue rows stored "HTTP 422"** while the server's own sentence — "At most 8 photos per
  item" — was discarded, and a 401 from an expired session read exactly like a validation
  failure. `ApiErrors.detail()` parses FastAPI's `{"detail": …}` (string or Pydantic list) and
  the queue stores that; 401 is named as a session problem.

Confirmation moved to where the stakes are. Both photo-destroying discards (a queue row, a
review draft) were one tap with no question, while deleting a re-photographable FILED item asked
twice — protection on exactly the wrong action. Both discards now confirm; the wording names the
photographs. And Skip **wraps** past the last draft: the end of the stack used to be a trap whose
only enabled exits were File it (demands a bin) and Discard (deletes the photos), so "I don't
know where this goes yet" had no answer. The empty review stack ends with a "Photograph
something" button — the natural start of the next batch, not prose naming a tab. Mixed bins
show both Unpack all and Repack all; the old either/or hid Repack whenever anything was still
inside, which is the normal January state of a Christmas bin.

## Chrome, and the way back

One `TopAppBar` on the single `ToteNav` Scaffold, shown on every non-tab route. Detail screens
had no on-screen way back at all — no bar, no arrow, and the bottom bar deliberately hidden —
which was worst on the flagship path: an NFC tap from a locked phone launches straight into a bin,
chrome-less, with nothing indicating the rest of the app exists. No title, because each screen's
hero already carries its identity.

**Settings** is reached from a gear on the home hero rather than a sixth tab (a bottom bar carries
five, and this screen is an escape hatch, not a destination). Three rows: signed-in email (the
first caller `users/me` has ever had), version, server URL — and **Sign out**, which had been
written, tested, and reachable from nowhere since Phase 1. Given this app's own token-wedge
history, "sign out and back in" has to be something a person can do without clearing app data,
which would also destroy the capture queue. The screen must render during an outage, so the email
is the only remote fact on it and stays honestly null when unreachable.

### The tag-mismatch warning finally arrives

The server has always compared the tapped tag's hardware UID against the one recorded for the bin,
and the client always threw the answer away — so the exact scenario the UID column exists for,
"this tag belongs to A14 but is stuck on a different box", opened the wrong contents with total
confidence, in an attic, where the whole point is not having to open the box. It now travels as a
**nav argument** (`totes/{id}?mismatch=true`) because it is a fact about *this opening*, not about
the bin, and surfaces as an attention card above the contents.

An unresolvable tag no longer dumps you on an empty search box with the code discarded. It
pre-fills search with the code and says which one failed — previously there was no way to tell a
deleted bin from being off the tailnet from a tap that never registered, and the one piece of
information the person had was thrown away.

### The index card is downloaded, not linked

`CardDownloader` fetches the PDF with the app's **authenticated** OkHttp client, writes it to
`cacheDir/cards/<code>.pdf`, and hands the system a `content://` URI through the camera's existing
FileProvider. The old path `ACTION_VIEW`-ed the card URL directly, which could never have worked:
the endpoint requires a bearer token and an external browser has none, so the tap opened a 401
while the bin screen went on saying "no card printed" (the server only stamps `card_printed_at` on
a successful render). Photos were routed through the authenticated client for exactly this reason;
the PDF was not. Naming the file by bin code matters — that string is what the print dialog shows.

Write-tag is **disabled with a reason** on a phone without NFC. The check used to live in the click
handler while the button stayed drawn and enabled, so tapping it was indistinguishable from a
frozen app.

## Screens re-read themselves, and say when they are working

Every tab ViewModel refreshed only in `init`, and `tabTo` deliberately preserves them
(`saveState`/`restoreState`) — so Find's counters, the overdue card, the people list and the tote
list were frozen at whatever they said the first time that tab was opened, for the life of the
process. There was no pull-to-refresh either, and `ToteListViewModel.refreshing` had existed
unused since it was written.

Two mechanisms, one contract: `RefreshOnResume` (`ui/components/`) fires the same idempotent
`refresh()` on every `ON_RESUME`, and `PullToRefreshBox` gives the manual pull. Both funnel into
the identical call, so there is one code path to reason about. The refresh must be quiet — it runs
on every tab switch, so a screen that announced or reset scroll position on resume would be worse
than the staleness it cures. A failed re-poll keeps what is already on screen rather than
replacing a readable list with an error.

**Loading is a third state, distinct from empty and from unreachable.** The tote list previously
showed "No totes yet" during the *first* load as well as on failure, because only failure was
guarded — the same lie the empty-state rule above exists to prevent, arriving through the one door
that rule had left open. People and Person detail rendered literally nothing while loading;
Review's three branches all missed, leaving a hero over blank space; Search tracked `searching` and
never rendered it, so a slow attic query was indistinguishable from a frozen screen still showing
the last results.

### Two badges, two meanings

Review counts server-side drafts waiting on a decision. **Catalogue** now counts local queue rows
that are `failed` or `uncertain` — captures that cannot proceed without a human. Both halves of
the loop can stall silently, and only one of them was ever visible: someone photographs a bin in
the garage, one upload is rejected, and nothing anywhere said so.

### Dates are picked, not typed

`DateField` wraps an M3 date picker and holds an ISO string the server accepts. The loan "back by"
and person birthdate fields were free text expecting `2026-09-30` on a QWERTY keyboard with no
validation — and in the lend dialog the resulting rejection was swallowed, so a malformed date
closed the dialog looking exactly like a successful loan. The field is read-only with an explicit
Clear, because both dates are genuinely optional. Quantity fields get a numeric keyboard (they
filtered non-digits silently, which reads as a broken keyboard), and search gets `ImeAction.Search`
and a clear-X.

### The capture destination survives process death

Held in `SavedStateHandle` keyed by tote **id**, restored against the cached bins. The situation
the queue exists for — shooting a bin's worth in a garage with the app backgrounded between shots
— is exactly when Android kills the process, and losing the destination there silently reset it
to "Decide later". The cost surfaced at review as one picker tap per item: precisely what choosing
it up front was meant to avoid.

## An empty screen must say WHY it is empty

Three times in this app a screen has confidently reported nothing when the truth was "I could not
find out":

| Screen | Said | Meant |
|---|---|---|
| Review | "Nothing waiting" | four drafts existed; the ViewModel never re-fetched |
| Totes | "No totes yet" | the cache was empty *and* the server was unreachable |
| Person → fits | (would have said) "nothing fits" | no size is recorded to match against |

The pattern is the same every time and so is the cost: a screen that says "there is nothing" is
believed, and the person acts on it — creating A14 for the second time, or walking away from a bin
that has exactly what they came for. An empty state is a **claim about the world**, and it may
only be made when the app actually knows.

So: every list that can be empty for two different reasons distinguishes them, and the
distinguishing state gets its own Roborazzi baseline. `ToteListViewModel.unreachable` exists for
exactly this and is only consulted when the list is empty — with bins on screen a failed refresh
is not worth saying, because the screen still answers the question it was opened for.

## The review stack (client)

The gate between a photograph and the catalog. Nothing the model produced is filed until someone
taps **File it**, and every field it filled in is editable first — the house AI rule, which Tote
has no exception to. `POST /drafts/{id}/confirm` is the only path from a photograph to a
catalogued item, and it is what writes the `initial` movement row.

**One draft at a time, not a scrolling list.** This is the tail of a batch — twenty items
photographed in one pass — and a list of twenty expandable cards is a screen people abandon
halfway, which leaves the catalog half-true. A counter says where you are; Back, Skip and Discard
say what else you can do.

**Position is preserved.** Deciding about a draft removes it from the in-memory stack and lands
on the next one; it does not re-fetch. A refresh here would drop someone back at the top of a
stack they were ten items into, and that is the mechanism by which a review session stops
halfway. `ReviewViewModelTest` asserts both the landing and the single fetch.

**Edits reset on every move.** They are per-draft and held apart from the DTO. Carrying them
would silently apply one item's corrected name to the next photograph in the stack.

**The destination is pre-selected** from the bin chosen at capture time (`draft_tote_id`), which
is the payoff for carrying it through the queue: the common case needs no tap here at all. Filing
still requires *some* bin — a confirmed item that is in no bin and never was is indistinguishable
from a bug — and the bin list comes from the Room cache, because reviewing happens on the way
back from the garage before the Wi-Fi is good again.

**No polling, but it re-reads on resume.** The sibling app this pattern came from polls because
its scan is asynchronous. Tote's is synchronous — it identifies before it answers — so a draft
that exists is already processed, and polling would be asking a question whose answer cannot
change.

It must still re-ask when the screen comes back, and originally it did not: `refresh()` ran only
in `init`, and this ViewModel outlives a tab switch. A draft that finished uploading while the app
was open therefore stayed invisible until the app was killed and reopened — while the tab badge,
which *does* poll on a 60 s ticker, counted it. Observed in production on 2026-08-16 as a review
tab reading **4** over a screen reading **"Nothing waiting"**, which is worse than either being
wrong alone: a count that disagrees with the list makes the person doubt the catalog.

`syncPreservingPosition()` runs on every `ON_RESUME` and re-reads the stack **by id**: the person
stays on the draft they were looking at and a half-typed correction survives. A plain `refresh()`
on resume would reset to the top of the stack and discard the edit every time they glanced at
another app — the precise behaviour the one-at-a-time review exists to avoid. A resume that
cannot reach the server changes nothing at all, because the stack on screen is still usable in a
garage with the bin open.

### The two scan notices are kept apart

`scan_error = "identify_unavailable"` and `scan_confidence = "low"` render as different messages,
because the server went to trouble to keep them distinct and collapsing them here would waste
that. The first means the model could not be reached and nobody looked at this photograph at all;
the second means it looked and found the photo hard. Merged into one "check this", a server
outage would send someone off to reshoot a perfectly good picture.

### Destructive actions speak in the error voice

Discard — on both the review stack and the capture queue — deletes photographs, the one artefact
in this app that cannot be recreated. It renders in `colorScheme.error` rather than the accent, so
it does not look like the Skip beside it. Three identical tonal buttons where the third is
unrecoverable is a row designed for the wrong tap. `ToteButton` grew optional `channel`/
`dimChannel` for this.

### The Review tab carries a badge

`DraftBadgeViewModel` is deliberately separate from `ReviewViewModel`: a badge that only appears
once you open the tab it is on does nothing. An uncatalogued draft is work someone believes is
finished and is not — the bin is taped shut and nothing says the item never reached the catalog —
so it is the rose attention channel, visible from every screen. It refreshes when the local queue
changes (an upload finishing is what creates a draft) with a slow ticker as a backstop for drafts
created on another device, and it fails silently: a tailnet blip must not raise an error over
whatever screen someone is actually using.

## Backups

Two Docker volumes hold everything: `pgdata` and `photos`. Compose only claims they survive a
redeploy — not `down -v`, not a disk failure, not a host rebuild. `deploy/backup.ps1` writes a
timestamped set (`db.dump`, `photos.tar.gz`, `MANIFEST.json`) somewhere else entirely.

**The two volumes are not equally replaceable.** The catalog rows are a list of paths; the
photographs are the artifact. An item was in someone's hands in a garage and is now sealed in a
taped bin in an attic — losing `photos` means the only way to learn what a bin holds is to carry
fourteen of them down and open every one. Losing `pgdata` is bad; losing `photos` is the exact
failure the app exists to prevent.

### Division of labour

| Layer | Owns |
|---|---|
| `deploy/backup.ps1` (repo) | producing a **verified** set |
| `C:\Scripts\Backup-ToteArchive.ps1` (host) | scheduling, gpg, NAS delivery, retention, logging |
| `Test-SuiteInvariants.ps1` (host) | asserting a recent set actually **landed** |

Same split as Crate, for the same reason: the repo script must stay runnable by hand on any
machine with the stack up, and secrets/paths belong to the host. `MANIFEST.json` is promoted to
the NAS **unencrypted on purpose** — it holds only counts, sizes and a timestamp, and leaving it
readable is what lets the freshness check work without the gpg passphrase.

### Verify, then prune — never the other way round

The script checks what it wrote before reporting success, and deletes old sets only after a good
new one exists, so a failing run can never remove the last good backup. This host has already
proved why: the nightly DB job produced **nothing for two weeks** while `Get-ScheduledTask`
reported `State=Ready`.

Four checks, each aimed at a specific way a backup lies:

- `db.dump` under 1 KB — the dump failed and left a stub.
- `photos.tar.gz` under 100 B — a truncated archive. **Suspended when the database genuinely has
  zero photo rows**, because an empty gzipped tar is ~45 B and a brand-new Tote would otherwise
  fail its backup every night until the first scan. A nightly false alarm is the fastest way to
  train someone to ignore a real one.
- `tar tzf` inside a container — a corrupt archive. Docker being unavailable is a **WARN**, not a
  FAIL: that says nothing about the archive, and condemning a good set for it is its own failure.
- photo files ≥ `item_photos` rows — missing originals. This holds because `scan_pipeline`
  persists originals to disk *before* committing their rows, so files ≥ rows is an invariant of
  any consistent set.

Two bugs were found by running it rather than reading it, both inherited from Crate:

1. **A set could pass at write time and fail `-Verify` a minute later.** Verification had no row
   count, so the "zero photos is legitimate" exemption could not apply — and `-Verify` never ran
   the files-vs-rows cross-check at all, so it was simultaneously too strict and too lax.
   `-Verify` now reads `photo_rows` from the set's own manifest and asks the same question the
   write path asked. Tote hit this on its very first backup: 87-byte archive, 0 rows, PASS then
   FAIL.
2. **A corrupt archive produced a PowerShell stack trace instead of a diagnosis.** Under the
   script-level `$ErrorActionPreference = "Stop"`, Windows PowerShell 5.1 turns a native
   command's stderr into a terminating `NativeCommandError` — and `2>$null` does not prevent it —
   so `docker run` against a bad archive killed the script before it could tell "corrupt" apart
   from "Docker is down". It failed closed, which is the important half, but the wrapper's log at
   04:30 is the only thing anyone reads. `Test-BackupSet` now sets `Continue` in its own scope,
   which is the correct semantic for a function whose job is to classify failures.

The dump is written by redirecting through `cmd.exe`, never through the PowerShell pipeline:
`Set-Content -AsByteStream` is PowerShell 7+ only and this host has no `pwsh`, while the 5.1
spelling (`-Encoding Byte`) decodes the bytes to text and corrupts the dump.

### Restore is rehearsed, not documented

The procedure in `deploy/README.md` was run end to end on 2026-08-16 against a real set: restore
into a throwaway database, confirm all 12 tables land, then untar the photos back onto the volume.
A restore that has only been written down is a claim.

## The deploy smoke exercises the pipeline, not just the front door

`scripts/synthetic_smoke.py` draws a real PNG with `zlib`/`struct` (no Pillow — the script is
stdlib-only so it survives a dependency change under it), pushes it through `POST /items/scan`,
asserts a **draft** came back with a photo attached, and then deletes it.

Three deliberate choices:

- **Fails on no draft; warns on `identify_unavailable`.** Those are different claims. The first
  means this deploy broke the pipeline; the second means LM Studio is not loaded on the host —
  real, but not this deploy's fault, and paging `tote-alerts` for it on every redeploy is how an
  alert channel gets muted. The server already separates transport failure from content failure;
  the smoke honours the same line.
- **A non-draft response is a hard failure.** If a scan ever produced a catalogued item directly,
  something model-generated would have entered the catalog unreviewed, which is the one rule this
  app has no exception to.
- **It cleans up.** This runs against production on every green push to `main`. A smoke that left
  its drafts behind would fill the review stack with pictures of a test gradient, and the first
  person to notice would be someone reviewing a real bin.

Its own timeout is 240 s, not the script's 20 s default, for the same reason the Android client
overrides OkHttp's: `/items/scan` is synchronous and a single photo measured 35.5 s. Verified
against the live model on 2026-08-16 — the generated circle came back as *"Striped red circle
decor"*, low confidence, one photo, and the draft was gone afterwards.

## Sizing

The one genuinely new module in this app, and the one whose failure mode is a person driving to
the attic for the wrong bin. It is pure, has no I/O, and every function is allowed to answer
"I don't know".

### `size_raw` is sacred

Whatever the tag said is stored verbatim, forever. `size_system` and `size_ordinal` are a
**derived index** over it, and a derived index that is wrong must never be able to destroy the
reading. So a row carrying only `size_raw = "M/L"` is a *good* outcome: a human reads it in two
seconds and nothing was thrown away.

Three places enforce that the index can never disagree with the reading it indexes:

- `ApparelPatch` does not accept `size_system`/`size_ordinal` **at all** — they are recomputed
  from `size_raw` on every write. A client that could set them could store "4T" indexed as an
  adult L, and nothing downstream would catch it.
- Clearing `size_raw` clears the index with it, rather than leaving a stale ordinal pointing at a
  size nobody can see any more.
- `size_type` (the age band) is **derived from the parsed system**, not asked of the model. A
  sewn-in label prints "4T", not "toddler"; asking would invite exactly the inference the app
  refuses. It is therefore null exactly when the size is unparsed.

### The ordinal axis, and why comparability is narrower than it

For children the ordinal **is approximate age in years** — `3-6M` is 0.375, `2T` is 2.0, youth
`10` is 10.0 — so a query can cross 4T → youth 5 the way a parent does. Adults continue above 16.
Shoes sit on their own band, because a shoe size is not a body size and letting "youth 8" and
"kids' shoe 8" collide would make any mixed sort quietly nonsense.

**Within a system the ordering is exact** (6 < 6X < 7, always). Across systems it is an
approximation, and some cross-system comparisons are not merely approximate but meaningless — a
men's 32 waist and a women's 8 are not two points on one scale. So systems carry a **lineage** and
callers must ask `comparable()` before comparing. That relation is deliberately **not transitive**:
women's numeric ↔ adult alpha and adult alpha ↔ men's waist are both comparable, women's ↔ men's
is not. That is a property of clothing, not a bug.

`within_tolerance()` returns **None, not False**, when either side is unparsed or the systems do
not compare. A caller that conflates them hides every item whose tag could not be read — the
opposite of what someone standing in front of fourteen bins needs.

### 6X is why this is a table

`6X` sorts between 6 and 7. A naive integer parse reads it as 6 or throws, and a 6X coat filed as
a 6 is a coat someone pulls out and finds does not fit. Every rung is written out and reviewable
against a real tag rather than computed.

### A bare number does not parse

`8` is a youth 8 or a women's 8, and they are different garments for different people. With no
department there is nothing in the string to tell them apart, so `parse_size` returns None and
`size_raw` keeps the `8`. **This is the module's designed trade, not a gap**: a null sends someone
to the bin, a wrong ordinal sends them to the wrong bin twice. A department resolves it (`8` under
`girls` is a youth 8) because that is evidence, not a guess.

## The label pass

A **second, narrow** vision call that does exactly one job: transcribe what is printed on the tag.
Measured in Crate, the omnibus identify prompt read about 1 in 6 legible sizes; the same model
asked in isolation read 3 of 4 and correctly returned nothing for the two labels with no size.
Telling the omnibus prompt to try harder was measured and **rejected** — no recall gain, plus a
reproducible wrong answer.

| Rule | Why |
|---|---|
| Reads the **original**, never the cleaned copy | background removal once decided a woven brand tab was "the subject" and cropped the shirt away |
| **No retry** against the cleaned copy on a null | measured: recovers the image 2 runs in 3 and answers a *wrong size* the third |
| **Its own `except`** at the call site | a 503 here reaching the outer handler rewrites a good identification as `identify_unavailable` — "we could not read the tag" becomes "we could not see the photo" |
| No `max_tokens` | the pinned model is a reasoning model; an answer-sized budget silently returns `""` |

Tote asks the label for **size, department and material** — not Crate's `size_type`, which is a
merchandising axis a sewn-in tag never prints.

The gate (`sizing_hints.looks_like_clothing`) is **one-sided on purpose**: a false positive costs
one wasted model call, a false negative loses the size of a garment now sealed in a bin. It
matches the model's chosen category first (the user's own vocabulary) and falls back to a word
list over the name.

Verified live against `gemma-4-e4b` on 2026-08-16: a drawn tag reading `4T / GIRLS / 100% COTTON`
came back parsed to `toddler`, ordinal `4.0`. Both negative controls — a brand-and-care label with
no size, and a photo that is not a label at all — returned **no size**, which is the half that
actually matters.

## Apparel on the client

### The tag's own words go where people look

`size_raw` rides on the **same caption line** as the bin on a search hit, and beside the status
on a tote-detail row. Not a row of its own: the question someone is asking is "which bin", and a
second line competes with the answer while costing a garment's worth of scrolling in a list they
are reading with an open bin in front of them.

### The review form states both outcomes, and neither is a fault

Under the size field, the app says either *"Kept word for word, and placed on the ladder as
toddler sizing"* or *"Kept word for word. Not placed on the size ladder, which is fine — nothing
is ever guessed."* The second is the **designed** result, so it is phrased as a working outcome
rather than as "unrecognised", which would read as a chore the reviewer has to clear.

That text sits under the field rather than in the section header's trailing slot, where the
first version put it — and where rendering showed it clipped off the right edge.

### An untouched clothing section is OMITTED from confirm

`DraftEdits.touchedApparel` tracks whether the reviewer changed anything in that section, and the
confirm body carries `apparel` only when they did. The server reads an omitted block as "leave
what the label read", so sending an unchanged copy would work *today* and would silently start
clearing columns the moment this form stops carrying every field the row has. Two tests pin it.

### Only `size_raw` is cached (Room v3)

The offline search matches it, because "4T" is a thing people type standing in front of the bins —
exactly where there is no signal. The derived system and ordinal are **not** cached: the server
owns that index, and a client copy would eventually be compared against a ladder this app does not
implement.

The migration is a plain nullable `ADD COLUMN`, not drop-and-recreate. `cached_items` is
disposable in principle, but rebuilding it would empty the offline catalog until the next
successful sync — and the attic is precisely where that sync cannot happen.

## Size-aware filtering

`GET /items?size=4T` matches on the **ordinal**, not the string, so it also finds a garment whose
tag read "4" under a girls department — the point of having an index at all. Three rules:

- **Only within comparable lineages.** A men's waist never matches a toddler size however close
  the numbers land on the shared axis.
- **An inner join**, unlike every other join in `item_query`. Filtering by size means the caller
  wants things that *have* a size, so an item with no apparel row is correctly absent rather than
  swept in by a null.
- **An unparseable filter falls back to matching `size_raw` textually.** Someone typing "M/L"
  means it literally, and an empty result would read as "you own none of these" when the truth is
  "we could not index that".

## People, fits, and lending

Household members and lendees deliberately share one table. Both answer "where did this go and
whose is it", and splitting them would mean deciding, at the moment you lend a nephew a coat,
whether he is family — which is not a question a storage app should ask.

### A person's size is a history, not a value

`person_sizes` **appends**; nothing is overwritten. A child's size is a moving target, and last
winter's answer is exactly what tells you which bin to open this winter. `current_sizes` takes the
newest row per garment type with `effective_from` **on or before today** — recording "she will be
in a 5T in September" must not change what fits her in June.

A person's size goes through the same `parse_size` as a garment tag, so both land on one ladder
placed by one implementation. An unparseable reading is stored with a null index and still counts
as a record of what was said.

### `fits` distinguishes "nothing matches" from "cannot say"

This is the endpoint's whole shape. `answered: false` with a `reason` means the question could not
be asked:

| `reason` | What it means | What fixes it |
|---|---|---|
| `no_sizes_recorded` | nobody has said what size this person is | add a size |
| `no_indexed_size` | a size was recorded but could not be placed on the ladder | re-read the tag |

A client that rendered either as an empty list would tell someone *"you own nothing that fits"*
when the truth is *"nobody recorded her size"* — and only one of those is a reason to stop
looking. It is also an INNER join on apparel, so an item with no size is absent rather than swept
in by a null; and shoe sizes are never matched against sweaters, which a shared ordinal axis makes
syntactically possible.

### "Who has the drill" comes from the ledger

The item row knows only that it is *out*. Only the movement knows *to whom* — which is the whole
reason lending needs the ledger. `loaned_to` is resolved from the newest `loaned` movement in
**one query per page** (`attach_borrowers`), not one per row: it appears on every list, and a
per-item lookup would make that join quietly quadratic on the screen people use most.

Deleting a person nulls `person_id` on their movements and keeps the rows. A loan that happened
still happened; erasing it to tidy a contact list would put a hole in the one record this app
promises never to have holes in.

### The outgrown run is one transaction

`POST /people/{id}/outgrown` writes two movements per item — `outgrown` out of the wearing pile,
then `moved` into the bin — so a run never rests in the contradictory state of being outgrown and
nowhere. All-or-nothing: a half-applied run would leave the catalog claiming some of a size is in
the attic and the rest is still being worn.

The reason is `outgrown`, not `moved`, and that distinction is the point. Six months on, "we
packed these away" and "she grew out of these" are a bin to re-open and a bin to pass on.

### The nudge

`POST /overdue/nudge` is an **endpoint, not a timer**. Scheduling on this host belongs to
`C:\Scripts` + Task Scheduler — the same division of labour as the backups — so the service stays
stateless and the nudge can also be fired by hand.

It reports *why* it sent nothing, because "nothing was overdue", "ntfy is not configured" and
"ntfy is down" are three different facts and a channel that is quietly broken looks exactly like
one with nothing to say.

**Never ntfy.sh.** `services/ntfy.py` refuses that host rather than trusting configuration: its
topics are effectively public URLs, and these messages name what you own and who has it — the same
reasoning that keeps the whole service tailnet-only. Config is compose `environment:` **literals**
pointing at `host.docker.internal:8095`; an interpolated `${NTFY_TOPIC:-}` is exactly the shape
that left Crate's notifications silently empty for weeks.

**Due today is not overdue.** Reporting a loan as late from the moment its date arrives is how a
nudge becomes noise, so the comparison is strictly `expected_back < local_today()` — in the
household's timezone, since the container runs UTC and the house does not.

The per-user topic override lives on `user_settings`, **not** on `users`. Reading it off the user
500'd on the first real call in production while every test passed — the test environment has no
ntfy configured, so the send path had never once been executed. A test that configures ntfy and
asserts on what reaches the transport (stubbed; CI never makes an outbound request) is what closes
that gap, and it is the general lesson: **a branch guarded by configuration the test environment
lacks is a branch with no coverage at all**, however many tests surround it.


## People, fits, and lending (client)

Five tabs now, and People is the fifth and last one this app gets — a bottom bar carries no more.
It earns the slot because "what fits her right now" and "who has the drill" are asked as often as
"which bin", and burying the two features the ledger was *built* for under a menu would make them
the two nobody uses.

**No Room cache behind People, unlike the catalog.** The bins have to be readable in an attic with
no signal because that is where they physically are. People are read at the kitchen table on the
way to deciding what to go and look for, and a stale wearer profile is worse than an honest
failure: it is what sends someone up a ladder for a size the child grew out of in March. The one
exception is the **destination picker** in the outgrown and returned flows, which reads bins from
the Room cache — filing outgrown clothes happens on the way back from the attic, and a picker that
needed the network would be empty exactly when it is used.

### `answered = false` is not an empty result

The distinction the whole fits endpoint is built around, and it is a *rendering* rule as much as a
data one:

| Server says | Screen says |
|---|---|
| `answered: false`, `reason: no_sizes_recorded` | **"We can't say yet"** — record a size and this fills in |
| `answered: false`, other reason | **"We can't say yet"** — the reading could not be placed on the ladder, so matching would be a guess |
| `answered: true`, `items: []` | **"Nothing in that size"** — we checked, and we own nothing that matches |

One of those sentences means *go and read a tag*; the other means *stop looking*. They have
separate Roborazzi baselines (`person_fits_unanswered_dark`, `person_fits_nothing_dark`) precisely
because a regression that collapsed them would pass every unit test in the suite and be visible
nowhere else.

Narrowing to a garment type **re-asks the server** rather than filtering the list here. Matching
against the size ladder has exactly one writer and it is not the client; a client-side filter would
eventually disagree with the ntfy nudge, and the person in the attic has no way to tell which of
the two lied.

### Sizes are recorded, never derived here

The record-size dialog sends `size_raw` and nothing else. `size_system`/`size_ordinal` are derived
server-side on every write, and a client that could set them could file a 4T indexed as an adult L
— which would then match on every fits query forever, silently. The screen shows the tag's own
words everywhere and never the ordinal: the index exists to be queried, not read.

### A person is editable; a size is deletable but never editable

A wearer profile is typed by hand, and everything downstream of it is silent when it is wrong. A
mistyped size is the worst case in the app: `"5TT"` does not parse, so `size_system`/`size_ordinal`
stay null, so `fits` answers `answered: false` **forever** — the screen keeps saying "We can't say
yet" while the size sits right there on the same screen looking recorded. Until this round there
was no way to remove it, and the profile carrying it could not be renamed or deleted either.

So: `PATCH /people/{id}` for the name and birthdate, delete for the profile, and a **size history**
sheet (the first caller of `GET /people/{id}/sizes`) where any recorded size can be removed.

Sizes are **deletable, not editable**, and that is the same rule as everywhere else in this app:
`size_raw` is what somebody read off a tag, and an in-place edit would quietly rewrite the reading
while keeping its timestamp. Delete and re-record instead — the new row re-derives its index from
scratch and dates itself honestly. The "We can't say yet" copy now names History as the fix path,
so the symptom points at its own cure rather than at a dead end.

Deleting a person does not delete history. Movement rows survive with a null `person_id`, so
"this went out in November" stays true and only the name goes — the confirm copy says exactly that,
because a delete that silently shortened the ledger would be the one destructive act in the app
nobody had been warned about.

`busy` disables every write button while one is in flight. It had been tracked and never read since
Phase 6, which is how a double-tapped Remove became two DELETEs and a 404 in the snackbar.

### Lending

From the bin the thing is in, because that is where someone is standing when they hand it over.
`reason = "loaned"` plus `person_id`, and optionally `expected_back`.

**The date is optional and stays optional.** Plenty of lending genuinely happens without one, and
a required field here would be either lied to or filled with an invented date — which manufactures
an overdue nudge nobody agreed to, and that is how a notification channel gets muted and stops
working for the loans that did have a date.

`loaned_to` comes back on every item and the row says **"Lent to Dave"**, not "lent out". The two
are the same fact and only one of them gets the drill back. The item row itself never knows the
answer; it comes from the newest `loaned` movement, which is the entire reason this needs a ledger
rather than a status column.

Returning requires a destination bin. `returned` is an inbound reason and the server rejects it
without one (422) — the UI honours that rather than working around it, because an item that is
"back" but in no bin is exactly the state the catalog exists to make impossible.

### The overdue card on Home

Search-first Home carries one card the app volunteers unprompted: what is out past its date, named
with who has it. It sits above the stats and only while idle — mid-search it would be between
someone and the answer they came for.

Overdue is computed **server-side**, against the household's local today. The card and the ntfy
nudge therefore cannot disagree about what "overdue" means, which they would within hours of each
other if the phone's clock or the container's UTC got a vote. A failed fetch leaves the card
absent rather than raising: an unreachable server genuinely cannot tell you that anything is late.

## Not yet built

Phases 0-7 are complete on both sides. What remains is Phase 8: polish, the empty/error-state
sweep, the README, and the Dragonfly `ServiceRegistry` row now that the URL is real.

The smoke script carries an explicit list of what each phase must add to it. Crate's stopped at
`/users/me` for months, so "auth works" read as "the app works" while the pipeline the app exists
for was never exercised.
