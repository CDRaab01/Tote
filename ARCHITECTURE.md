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
│     │                         ScanTimeoutInterceptor
│     ├─ di/                    NetworkModule, DatabaseModule
│     ├─ nfc/                   TagIo, NfcWriteSession, TapRouter
│     ├─ work/UploadWorker.kt   drains the capture queue when connected
│     ├─ util/UiState.kt        Idle/Loading/Success/Error
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
│  │  ├─ routers/               suite_auth, users, catalog, totes, items, public, scan
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

## The capture queue (client)

The one write-behind queue in the app, and the only table in the local database whose contents
exist nowhere else. Everything else in Room is a disposable copy of server state; a queued
capture is a JPEG of an object that was in someone's hands in a garage and is now back in a
taped bin. That asymmetry is the reason `DatabaseModule` has no destructive fallback.

The flow is built for the place it is used. Cataloguing happens at an open bin in an attic or a
garage — where the Wi-Fi is worst — so nothing in the capture path waits on a network:

1. **Shoot** 1–8 photos of one item. Camera output goes to `cacheDir/captures/` through a
   `FileProvider`; the gallery path copies to the same place.
2. **Queue.** The photos move to `filesDir/capture_queue/{id}/` *before* the Room row is written.
   A cache directory is one the OS may empty at any moment, and a queue row pointing at an
   evicted JPEG is worse than no row — it claims work that cannot be done or reconstructed.
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

### Three drain outcomes, because there are three different situations

| Failure | State | Next |
|---|---|---|
| `IOException` | `pending` | offline/transient — WorkManager retries with backoff |
| `HttpException` | `failed` | the server answered and said no. Surfaced; never auto-retried |
| `SocketTimeoutException` | `uncertain` | **nobody knows whether it landed** |

The third is specific to this app. `POST /items/scan` is **synchronous** — it persists, cleans
and identifies every photo before it responds, measured at 35.5 s for one photo against the live
model — so a client timeout is not evidence of failure. It is more likely evidence the server is
still working and the draft will exist. Retrying automatically would file the same object twice,
and a duplicate in a storage catalog is indistinguishable from two real ornament boxes. There is
no idempotency key on the endpoint, so the honest answer is to stop and ask: the row says "it may
already be in Review, check there before retrying", and offers both.

`drain()` reports **clear** for `uncertain` and `failed` rows. Those wait on a person, and telling
WorkManager to retry would spin the backoff chain against a queue that cannot move.

A drain also begins by releasing anything left in `uploading` by process death. Uploads run for
tens of seconds — ample time for Android to kill a backgrounded app — and an `uploading` row
whose uploader no longer exists would otherwise sit out every future drain forever.

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

**No polling.** The sibling app this pattern came from polls because its scan is asynchronous.
Tote's is synchronous — it identifies before it answers — so a draft that exists is already
processed, and polling would be asking a question whose answer cannot change.

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

## Not yet built

Phases 0-4 and the backup half of Phase 7 are complete. What remains: the sizing ladder
(Phase 5); people, `fits()` and lending (Phase 6); polish and the Roborazzi/empty-state sweep
(Phase 8).

The smoke script carries an explicit list of what each phase must add to it. Crate's stopped at
`/users/me` for months, so "auth works" read as "the app works" while the pipeline the app exists
for was never exercised.
