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
| `uq_totes_household_code_lower` | unique on `lower(code)` per **household** (was per user until `0006`) — the code is printed on an index card and written into an NFC tag, so "a14" and "A14" being two bins is a real-world ambiguity |
| `ix_items_search_vector` | GIN over a STORED generated `tsvector`; a btree cannot answer "does this document contain these lexemes" |

Consequently **`conftest.py` builds the test schema with alembic, not `metadata.create_all`** —
a deliberate deviation from the siblings. Under `create_all` both objects would be silently
absent and the tests that prove them would be testing a schema that never ships.

`search_vector` covers the item's own name, description and notes. Category lives in another
table and a generated column cannot join, so category is a **filter**, not a search term.

## Book scanning (ISBN)

`POST /items/scan-isbn` turns a scanned barcode into a **filed item** — no photograph, no
review. Its own endpoint because `/items/scan` requires at least one photo and is multipart for
that reason; and its own trust story, because nothing here is model output: an ISBN lookup
returns database rows keyed by the number printed on the object, owner-confirmed exempt from
the no-auto-commit rule, which stands untouched for everything vision produces.

### The tri-state contract (services/books.py)

The lookup has three outcomes and they are three different facts:

| Outcome | Means | The endpoint does |
|---|---|---|
| `BookMetadata` | the book is known | files a real item + `initial`/`catalogued` ledger row |
| `None` | the database answered: unknown ISBN | a draft for Review (`scan_error="isbn_not_found"`) |
| raises `LookupUnavailable` | the database could not be reached | 503, **nothing committed** |

Collapsing the last two is the failure the module must never have: a network flake minting a
"this book does not exist" draft is a junk draft per Wi-Fi hiccup, and the Review tab becomes
noise. OpenLibrary is **retried once** — the measured failure mode is a connection reset on
rapid consecutive calls, exactly what a shelf session produces — then Google Books, but only
when `GOOGLE_BOOKS_API_KEY` is set (unkeyed requests 429, measured).

### Where the metadata lands

Title in `name`, "by {authors} · {publisher}, {year}" in `description`, `ISBN {n}` in `notes` —
the exact three columns `search_vector` covers, so a book is findable by its author and its
ISBN with no schema change. Category is the household's "Books" by case-insensitive name (null
if they deleted it — a missing label is a smaller wrong than resurrecting a name they removed).
The cover downloads from the lookup's own URL into `photo_store` as photo 0; its failure never
takes the filing with it (the label-pass rule, applied to images).

### Idempotency and the timeout chain

`capture_id` is **required** here where `/items/scan` merely accepts one: this endpoint files a
real item with no review behind it, so a replayed request without a key would silently put a
second copy of a book in the catalogue. Same unique constraint, same race backstop.

Three timeouts nest, and the middle one is load-bearing: OpenLibrary per-attempt **12 s**
(`OPENLIBRARY_TIMEOUT_SECONDS` — cold calls measured 8.8–11.8 s, the 8 s default would fail
every first scan of a sitting) < the whole lookup's `asyncio.timeout(30)` < the client
interceptor's **45 s** for this path. Breach the middle one and the client manufactures a
FAILED row over a filing that succeeded — capture_id makes the retry safe, but the session
list would lie.

### The client session

The GMS code scanner (Play-Services UI, no camera permission, EAN-13 only) is one-shot,
auto-relaunched: scan-scan-scan, each book's network call running behind the scanner modal.
It cannot run under Robolectric, so it lives behind `BookBarcodeScanner` — a one-method seam
the tests drive with scripted barcodes. Two guards sit in the ViewModel before any network:
a non-Bookland EAN-13 (the soup can next to the shelf) is announced and dropped, and an ISBN
already scanned this session is skipped — two copies of one book is what the quantity field is
for, and silently filing twice is the storage-catalogue sin. Retry re-sends the SAME
capture_id. The flow is online-only by design: the lookup inherently needs network, nothing
commits on failure, and books are scanned indoors — so the capture queue stays out of it and
Room stays at v5.

## The ladder reads what is on the tag

`parse_size` resolves a system from a marker in the string — `W8`, `Women's 8` — and falls back
to the `department` argument only for a **bare** number, where the ambiguity is real (youth 8 and
women's 8 are different garments for different people).

The women's marker parser shipped and the youth one did not, while `parse_size`'s own docstring
promised both. `GIRLS 8` — which is what somebody types when they type what is printed — landed
with no ordinal and was **invisible to `fits`**, the one query the ladder exists to serve. The
department chip did not rescue it either, because `girls_8` is not a bare number and never
reached that branch. Same silent failure as the missing `6m` rungs, found the same way: by trying
what a real tag says.

`_parse_marked_youth` takes **prefix forms only** (`girls_8`, `b10`, `youth_6x`). A trailing `y`
belongs to `_parse_shoe`, and quietly taking `8y` from there would swap one wrong answer for
another across the shoe/garment boundary the ladder keeps deliberately separate.

**The derived index follows its inputs, all of them.** `apply_apparel` re-derives when the
department changes as well as the reading — it used to key on `size_raw` alone, so correcting a
mis-guessed department was accepted while the ordinal kept its old value. That matters because on
the scan path the department comes from the MODEL, and production carries `mens` and `womens` on
12-month onesies; the person who spots that at review has to be able to fix it and have the fix
take.

## Category ordering, icons, and browse

**The server owns the ordering, and the ordering is the feature.** `GET /categories` returns
most-used first (`item_count` — a correlated subquery, computed never stored, drafts excluded so
an unreviewed model guess cannot promote a label). All four client pickers render that order
through ONE mapping (`ui/components/CategoryUi.kt`), and no screen sorts for itself: with twelve
seeded categories and one in use, the eleven empty ones sink instead of standing in front of
every choice. Ties fall back to `sort_order` — and `POST /categories` appends new names after
everything the household has rather than defaulting to 0, or a new category would jump above
the seeds in every tie-break.

**Icons are an emoji string in `categories.icon`** — a column that existed since 0001 and was
never written. Seeded for new households from `DEFAULT_CATEGORY_ICONS` (a separate map, because
the tuple's order is load-bearing) and back-filled for existing ones by migration `0007`, whose
`icon IS NULL` guard is both idempotency and the promise that a hand-picked icon is never
overwritten. The #29 rule now covers icons: a seed change is the map + a migration.

**Browse-by-category** (§1's third entry point, unbuilt until now): chips on Find for **used
categories only** — empty seeded rows as chips would reproduce the picker clutter this removes —
opening a pushed route that lists `GET /items?category_id=` (an endpoint live since Phase 2,
first caller). The rows are the search hit's row, not `ItemRow`, because browse's whole question
is "which bin" and that is the one row whose second line answers it. Online-only (the Room cache
does not carry category ids), and the unreachable state says so instead of claiming empty.

**The manager** (Settings → Categories) is the first caller of POST/PATCH/DELETE `/categories`.
`CategoryPatch` always sends both fields — the TotePatch discipline, or a rename would silently
strip the icon. Delete counts what loses the label and says the contents keep their bins.

## Adding to the seeded vocabulary

`DEFAULT_CATEGORIES` is written **once, at first login**, and never looked at again. That is
correct — the vocabulary belongs to the user and re-seeding would resurrect names they deleted —
but it means adding a name to the tuple reaches new accounts and **nobody who already has one**.

**Since `0006` the back-fill is per household, not per user.** A two-person household would
otherwise get the seeded name twice — two rows a picker renders as one word, which is exactly the
fragmentation the categories table exists to prevent, and the only place a seed addition can
create a duplicate, because it is the one insert nobody types by hand.
`tests/test_category_backfill.py` pins the household-shaped statement; it no longer executes
`0004`'s own SQL, which is frozen history that runs before `household_id` exists.
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

## Catalogued, but not filed yet

Confirming a draft used to require a bin. That put the destination decision at review time —
which is the moment you are *least* sure, because the bin is closed and the object is already
back inside it. `DraftConfirm.tote_id` is optional now.

**The state is not new.** An item with no bin already existed: deleting a tote leaves its
contents unfiled (`ON DELETE SET NULL`), search already renders "Not in a tote", and bin
contents already exclude it. What is new is a deliberate way to *arrive* there.

### Deleting a bin is a whereabouts event

`items.current_tote_id` is `ON DELETE SET NULL`, so deleting a tote used to null the whereabouts
at the database level while `status` went on reading `stored` — an item claiming to be in a bin
and in none, which is exactly what the invariant forbids. Nothing crashed; the item sheet simply
offered "Move it… it left one bin and entered another" for something the list beside it called
"not in a bin".

So `delete_tote` moves the contents out first, through `record_move` like every other
relocation, with a reason of its own: **`bin_deleted`**. Not `unpacked`, because nobody unpacked
anything and a year later those are different facts; not `catalogued`, which #33 deliberately
reserved for something that was never in a bin at all.

The bin's **code goes in the note**, and that is load-bearing: `movements.from_tote_id` is
`SET NULL` too, so a moment after the commit the row could no longer say which bin it came out
of — and "it left A14 when A14 was deleted" is the whole value of the row. `inbound_reason_for`
then files it later as `moved` rather than `repacked`, on the same reasoning as an item that was
never filed: "it came back" is untrue of a thing whose bin ceased to exist.

### A third kind of movement reason

The ledger has had two kinds — `_INBOUND` (needs a destination) and `_OUTBOUND` (must not have
one). Unfiled needs a third, `_UNFILED = {"catalogued"}`, which also refuses a destination but
means something different: **the item entered the catalogue without entering a bin.**

It could have been an outbound reason with a friendly label. It is not, because "it was never put
in a bin" and "it came out of A14" are different facts about an object's history, and the ledger
is the one place that difference survives a year. `out_reason` is `unfiled` for the same reason.

The derived-state invariant is untouched: `current_tote_id IS NOT NULL <=> status == "stored"`.
An unfiled item has no tote, so it cannot be `stored`; it is `out`/`unfiled`. Filing it later is
an ordinary inbound `moved`, which clears `out_reason` and sets `stored` through the same single
writer as everything else.

### Where the loose ends go

Deferring is only reasonable if the deferred things visibly accumulate somewhere the person will
look — otherwise "decide later" is a way to lose an object you have already photographed and
named. So the Totes tab carries a **"Not in a bin (N)"** section, collapsed to one line, directly
under the header, in the attention channel, and absent entirely at zero.

Filing from there needs no new screen: the rows open the **item sheet**, whose move button
already reads "Put it away" for an item that is out. The list is drawn from the Room cache
(`unfiledItems()`), so it works in the garage like everything else.

That query deliberately does **not** filter on `out_reason = 'unfiled'`. An item unpacked from a
bin and never put back is in the same practical position — it exists and nothing says where it
is. The ledger keeps *why* they differ; this list is about what to do next. `disposed` is
excluded because it is terminal, not a loose end.

## Bags inside a bin

A real bin is not a flat pile. A tote of baby clothes is three zip bags and a loose blanket, and
"which bag is the 3-6M one" was unanswerable — the contents were forty rows in one list, which is
the shape that makes somebody tip the whole bin out on the floor.

`containers` is one level of grouping **inside** a tote: id, user_id, tote_id, name, notes.

### It is a label, not a location — and that is the whole design

A container belongs to exactly one tote and **carries no whereabouts**. An item's location stays
`items.current_tote_id`, full stop; `container_id` only says which bag inside that tote it sits
in.

The alternative was tempting and was rejected on purpose. A movable bag — "take the 3-6M bag down
from the attic" as one action — needs its own `tote_id`, which the item's `current_tote_id` can
then contradict, and **nothing fails loudly when they drift**. Two sources of truth for where a
thing is, in the one app whose entire promise is answering that. So a bag does not move as a unit;
its items move, one ledger row each, through the single writer that has always owned this.

Three consequences follow mechanically:

- **Leaving the tote leaves the bag.** `record_move` clears `container_id` alongside
  `current_tote_id` — in the single writer, not in each caller. A stale membership would make a
  bin's grouping claim something the bin does not contain. Entering a tote clears it too: the
  destination's bags are not the source's.
- **An item cannot join a bag in another bin.** `PATCH /items/{id}` validates `container_id`
  against the item's *current* tote and 422s otherwise. That is the only way a container could
  ever lie, and it is closed.
- **There is no way to move a bag.** `ContainerPatch` has no `tote_id`, and every route hangs off
  `/totes/{tote_id}/containers` rather than a flat collection.

### The two delete rules are the design, not defaults

- `containers.tote_id` → **CASCADE**. A bag has no meaning outside the bin it is in.
- `items.container_id` → **SET NULL**. Deleting a bin — or a bag — loses the grouping and never
  the contents, the same promise `current_tote_id` already makes.

Which is why removing a bag needs no confirmation dialog, unlike almost every other delete here:
nothing is destroyed but the label. The copy says so, because "delete" reads as "delete the
contents" to everyone who has not read the schema.

### Notes earn their place

A bag is often only *approximately* catalogued — "mostly 3-6M onesies, some vests". That is worth
recording even when the individual garments are not, and it is what somebody reads **instead of**
opening the bag. It is the single most useful field on the table and the reason a free-text
"which bag" column on the item would not have done.

`item_count` is computed per bag in one grouped query, never stored — same reason as
`totes.item_count`, and this one is read while somebody is holding the bag open.

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

## Refreshing the snapshot

`CatalogRepository.refresh()` is the client's one expensive call — three endpoints and a full
Room `replaceAll` — and it runs after **every** write, so two properties matter.

**The three calls are concurrent.** They do not depend on one another, and in series they were
three times the latency on the path every write takes.

**Concurrent callers collapse into one.** Every tab refreshes in its ViewModel's `init` and again
on its first resume (both deliberate, both paid for in bugs), so opening a screen fetched the
whole catalogue twice within milliseconds; a double-tapped pull-to-refresh did the same. A caller
that finds a refresh already running returns instead of starting a second — the one in flight is
about to write the same rows, and Room's flows push them out regardless of who asked.

`force = true` is for **writes**, which must observe their own change and therefore wait for the
lock rather than skipping. Every `.also { refresh(...) }` on a write path passes it.

Still linear in the whole catalogue, and knowingly: a write downloads every item, not the one
that changed. That is ~31 KB at 43 items and ~585 KB at 800. Patching the cache locally instead
was considered and rejected — bin counts are derived server-side precisely because a stored count
is the first thing to drift, and reconstructing them on the client would reintroduce that. The
real fix is a conditional fetch, which needs server support.

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

Every route resolves rows scoped to the caller's **household** and returns **404, not 403**, for
another household's — asserted across GET/PATCH/DELETE and `move`. 403 would let an authenticated
user probe which ids exist and tell "not yours" apart from "does not exist".

**`household_id` is who may see a row. `user_id` is who created it. They are never the same
question**, and the second must never be used for access: it is nullable with `ON DELETE SET
NULL`, so a shared catalogue survives the deletion of whichever member happened to enter the row.

## Household sharing

Two people, one catalogue. Every user owns a **household of one** from first login
(`services/suite_auth.py`), so there is no solo special case anywhere — the access check is always
`household_id == user.household_id`, a single-column equality with the same query plan the
per-user check had. `User.household_id` resolves through a `lazy="selectin"` relationship for the
same reason `Item.apparel` has one: a lazy load under asyncio raises MissingGreenlet, and this is
read on essentially every request.

### Why this is not Cookbook's household

Cookbook shares by **widening reads**: a recipe stays owned by its creator and co-members are
allowed to see it. That works there because two "Groceries" lists are a nuisance and nothing more.

It does not work here, because every namespace in Tote is attached to something you can hold. A
tote `code` is written on an index card; an `nfc_tag_uid` **is** a specific sticker; a `Location`
is a place in the house. Widening reads while leaving those unique per *user* would let two people
own a bin "A14" and one physical tag claim two bins — the exact ambiguity `models/tote.py` was
written to prevent, reintroduced by the act of sharing. `test_schema.py` pins it directly: two
members of one household cannot both own `A14`.

So the household **owns** the data. Migration `0006` moved `household_id` onto all six catalogue
tables and rescoped all four uniqueness constraints onto it.

### Accepting an invite is a merge, and there are two kinds of collision

An invitee never joins from nowhere — they arrive with a catalogue of their own. Accepting moves
their bins, items, people and ledger into the inviting household and deletes the one they came
from. **There is no undo**, because after the merge the two people have been moving each other's
things and there is no seam left to split along.

| Collision | Answer | Why |
|---|---|---|
| Location / category **names** | folded silently into the target's row, FKs repointed | Two "Attic" rows, or the `DEFAULT_CATEGORIES` both accounts got at first login, are the *same real thing* recorded twice. Keeping both splits browse-by-location in half and puts one word twice in a filter. |
| Tote **codes**, **NFC tags** | **refused** — 409 naming the codes | No rule the server could apply is right. Renaming silently makes a printed card lie about which box it is on; merging claims two real bins are one. A person has to walk to the attic. |

**Merge what is a duplicate record; refuse what is a duplicate object.** The conflict list is
recomputed on every read of `GET /household/invite` rather than stored at invite time — a cached
refusal would keep refusing after somebody renamed the bin to clear it.

### A merge may never strand somebody

An invitee's household might not be just them, and if it is not, the merge is catastrophic for
whoever else is in it: re-parenting the data and deleting the source household CASCADEs
`household_members`, so anybody still in that household loses their membership row. That is not a
lost catalogue — `User.household_id` is deliberately non-defensive, so it raises, and **every
endpoint 500s for them permanently**. They cannot sign their way out either, because
`suite_login` only creates a household on the branch that handles a new account.

So `merge_conflicts` reports the other members as a blocker alongside the duplicate codes and
tags, and the merge refuses: **you may only join another household from one that is just you.**
That is the same rule that stops an owner leaving a populated household, reached from the other
side — a household is never left ownerless, and never left memberless either.

Two belts, because the failure is unrecoverable. The guard above is the fix; `suite_login` also
restores a missing household for an existing account, so anyone who somehow reaches that state
can walk out of it by signing in.

### Consent, and leaving

An invite is its own table (`household_invites`), not a member row carrying a `status` as in
Cookbook: the invitee is already the active member of *their own* household and
`household_members.user_id` is unique. Nothing is shared until they accept.

**Leaving forfeits the catalogue** — the opposite of Cookbook, where leaving costs nothing because
recipes were always yours. Here the household owns the bins, so walking out means walking out of
the attic; the leaver gets a fresh empty household and nothing is copied back. The owner cannot
leave a populated household at all (it would be ownerless); they transfer ownership first. Nothing
ever deletes a populated `households` row — `owner_user_id` is `ON DELETE RESTRICT` precisely so a
user deletion can never cascade into an entire household inventory.

### What sharing added that did not exist before

- **`movements.moved_by_user_id`.** "Who moved it" is a question that does not exist in a
  one-person catalogue and is the first one asked in a shared one. Null on every row written
  before `0006`, and rendered in the item sheet's history **only when the household is actually
  shared** — otherwise it is your own name on every row.
- **The overdue nudge fans out.** `POST /overdue/nudge` notifies every member's topic,
  deduplicated (members with no override collapse to one deployment-topic push). Notifying only
  whoever pressed the button means the drill is overdue for both of you and nags one of them.
- **`nfc_uri_base` moved to `households`.** It was never read, so moving it was cheap — but
  per-user it was wrong by construction: two members writing different bases produce tags that
  open for one person and not the other, discoverable only by walking to the attic.
- **The client empties its Room cache on any membership change**, rather than merely refreshing.
  Membership alters the entire visible set, and Room is the offline read model — a departed member
  would otherwise keep browsing bins they can no longer fetch, an attic that reads perfectly until
  they tap something.

### An invitation is visible from both ends, and revocable

`HouseholdOut.pending` carries invitations sent and unanswered. It is a **separate list from
`members`**, not a member row with a flag: a pending invitee shares nothing, and giving them the
member shape is how a roster starts claiming somebody has joined a household they have not.
`shared` counts members only, so a household with one member and one invitation is still not a
shared catalogue and does not start showing "who moved it".

`DELETE /household/invites/{user_id}` withdraws one, owner-only and scoped to the caller's own
household. Before it, only the invitee could end an invitation by declining — and since an email
address is free text matched against accounts, a typo sent a real standing invitation to whoever
owns that address with no way to take it back.

### Finding out you have been invited

Settings is reached through one icon on the Find hero — deliberately, since it is an escape
hatch rather than a daily destination. That is fine for a sign-out button and wrong for an
invitation, which somebody else starts and which goes stale while it waits, so sharing shipped
with no way to discover you had been invited at all. `InviteBadgeViewModel` puts the app's
standard rose attention mark on that icon, the same channel as drafts on Review and stuck
uploads on Catalogue.

Both the badge and the Settings section follow one rule, and it is the app's oldest: **a failed
request is not an answer.** Neither clears a known invitation on failure — `HouseholdViewModel`
kept the household half on error and assigned the invitation half unconditionally, so a single
dropped request deleted a real invitation off the screen, healed on the next success, and read
as a flicker rather than an error. `HouseholdState.reachable` now carries the difference so the
section can say it is showing what it last read.

### No per-object sharing, deliberately

Cookbook layers a per-recipe `shared` opt-in on top of its household. There is no equivalent here
and there should not be: a half-shared catalogue answers "where is the ratchet set" with
"somewhere you cannot see", which is worse than not sharing at all. Membership is the only switch.

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

### The stack is worked one at a time, but not in a fixed order

One-at-a-time stands, and the reason is unchanged: a screen of twenty expandable cards is one
somebody abandons halfway through, which leaves the catalogue half-true. What was wrong was the
**order**. Oldest-first is a sensible default because it is the order they were shot in; being
unable to leave it made the stack a queue you had to serve rather than a pile you could work.

`DraftChooser` is a grid of photographs behind the position counter — which is the one control on
that screen already talking about the stack rather than the draft, so it is where somebody looks
when they want a different one. Tapping a tile calls `jumpTo`, which reuses `moveTo` and therefore
resets the edits exactly like Skip does; carrying them would apply one item's corrected name to a
different photograph.

A grid rather than a filmstrip, deliberately: the picker round removed horizontally-scrolling
strips everywhere because they run off the edge and hide their own length, and twenty drafts have
exactly that problem. The current draft is outlined, not merely implied by position — without it,
jumping away and back leaves the screen with no answer to "which am I on".

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

### A row gets exactly one clickable

Owner-reported: tapping an item in a bin did nothing. The row had **two** click handlers stacked —
`PanelCard(onClick = …)` for the tap and a `combinedClickable` on the inner `Row` for the
long-press that starts a selection. `PanelCard` renders its own clickable `Surface` and puts the
content inside it, so the inner modifier lies on top and consumes the pointer first: every tap went
to its `onClick = {}` and stopped there. Long-press still worked, because the inner one owned it,
which is what made only half the screen look broken.

Both gestures now live on **one** `combinedClickable` on the Row, with `PanelCard(onClick = null,
contentPadding = 0.dp)` and the card's padding moved inside the Row — so the whole panel stays
tappable rather than only the content within its padding. The rule: **if a row needs a long press,
the tap belongs on the same modifier**, never on a clickable container underneath it, which cannot
be reached and only re-creates the trap.

### "Not in a bin" is a screen, not a section

Confirming without a bin was added deliberately (deferring the destination at review is only
reasonable if the deferred things visibly accumulate). The place they accumulated was a section
that unfolded inside the Totes tab, and at the owner's real scale — **32 loose garments** — it was
reported as *"I don't know what the items are. It's a shit of scrolling. I can't multi select."*
Three separate faults:

**The rows were drawn from the Room cache, which carried no photo count.** So `UnfiledRow` was a
hand-rolled, stripped-down row: no thumbnail, no description, and a shouting
`12M · CATALOGUED, NOT FILED` caption under every one. That is the list where rows are *hardest*
to tell apart — no bin, no location, and six things honestly called "Onesie 12m" — and it was the
only list with the least to tell them apart by. `CachedItem` now carries `photoCount` (**Room v5**,
additive `ADD COLUMN … DEFAULT 0`), which also gives offline search results their pictures back.

**There were two implementations of an item row and they had drifted.** `ItemRow` is now
`ui/components/ItemRow.kt`, shared by a bin's contents, a bin's out-list and this screen. The
bespoke one is gone.

**It is its own route.** Browsing bins and clearing loose ends are different jobs, and thirty-two
rows unfolding on top of the bins made the tab useless for the first while doing the second badly.
The tab keeps a one-line count in the attention channel — a signal is exactly what belongs there —
and it opens `UnfiledScreen`.

**Filing is bulk.** Same selection model as a bin (long-press or Select), one verb — `File into…` —
and one `bulkMove`, so the server writes one ledger row each in a single transaction. A per-row
`File…` still opens the item sheet, which is where the photographs are and therefore the right
place to settle "which of these six onesies is this one".

### Two ways into "not in a bin" disagreed about what it was

Found while wiring the above. `POST /items` with no `tote_id` set `status`/`out_reason` **by hand**
and wrote **no ledger row at all** — the exact hole the branch immediately above it exists to
prevent, dug by the other branch — and stamped `other`, where the same state reached through
review's confirm-without-a-bin is `unfiled`.

It now goes through `record_move(reason="catalogued")` like review does, so both paths produce one
state with one history. That also fixed the reason on the way out: `inbound_reason_for` takes
`out_reason` now, and something **never in a bin** is filed as `moved` rather than `repacked` —
"it came back" is untrue of a thing that had never been anywhere, and CLAUDE.md has said filing
later is an ordinary `moved` since the feature shipped.

### The bin screen when everything is out

Unpacking a bin is a first-class operation, so "everything is out" is a normal state — and it was
the state the screen handled worst. Owner-reported as "annoying to sort through… it doesn't feel
clean". Four things compounded, and they are worth keeping apart because only one of them was a
matter of taste:

1. **Selection was unreachable and unusable.** The Select button was gated on `items.size > 1`, so
   it vanished at exactly the moment it was wanted — unpacking empties `items` and fills
   `itemsOut`. And the out rows had never had `selected`/`onToggle`/`onLongPress` wired at all. So
   with everything out, the only ways back were one row at a time or **Repack all**. *Partial
   repack — the actual January workflow, and the outgrown-clothes workflow — had no path.*
   Select now counts both lists and lives beside Unpack/Repack all, where it applies to the bin
   rather than to one section; the out rows tick like any other.
2. **The verbs now depend on what is ticked.** A selection can span both lists, and the two
   directions are mutually exclusive: something already out cannot be taken out, something in the
   bin cannot be put back. The bar shows what fits, and for a selection spanning both shows only
   **Move** — the one verb well defined for every item in it — and says why rather than leaving
   two buttons mysteriously absent. `Bag…` requires everything ticked to be in the bin, because a
   bag belongs to this tote and can only label what is in it.
3. **The list is grouped by size**, in ladder order, whenever there is more than one size to tell
   apart. This is not decoration: **alphabetically 12m sorts before 6m**, so a name-ordered bin of
   clothes reintroduces one layer up the exact confusion the ladder exists to remove. `outBySize`
   orders by the server's `size_ordinal` — displayed, never computed — puts unsized things last
   under their own heading, and leaves a single-size list flat, the same rule as "Loose in the bin".
   Under a size heading the row drops its own size mark, and inside "Out of this tote" it drops
   "Out since it was unpacked": both are the heading above repeated on every row, and dropping them
   gives the description the width to be a whole sentence.
4. **The chrome above the rows is smaller.** The whole "In this tote" block — header, "Everything
   is out" card and count — is skipped when the bin is empty and things are out of it, since the
   section immediately below says all of it in full. And a bin that is already tagged *and* carded
   gets a single line rather than a panel with two buttons: that state is finished work, and
   rewriting a tag is rare. The loud panel stays for every unfinished state, where it is an
   attention signal rather than furniture.

The fifth complaint — the list moving under your finger — is mostly answered by (1): each
`putBack` triggers a full `load()`, so thirty-one taps were thirty-one re-reads. `putBackSelected`
is one request and one reload.

**`putBackSelected` goes through `bulkMove` into this same tote, not `repack`.** The server picks
each item's inbound reason from its own status, which is how a lent thing in that selection gets
`returned` while an unpacked one gets `repacked` — a bulk repack could not express the difference.

### A loan ends with `returned`, wherever it is put back

`returned` is in the server's inbound set and the item sheet has always rendered it as "Returned
into A14" — but for a long time only the **person screen** ever sent it. A lent item appears in its
bin under "Out of this tote" (`items_out` is every item whose last movement left this tote, which
includes loans) with the same **Put back** button as anything else, and both that button and the
sheet's move classified anything not `stored` as `repacked`.

So the ordinary way a borrowed thing comes home — hand it back, open the bin, tap Put back —
recorded it as though it had merely been unpacked and reshelved. Both rows land the item in the
same place, and a year later they are not the same fact: **the `returned` row is the only record
that a loan ever ended.** "Who had this and did it come back" is the question the people table
exists for, and it was unanswerable from the ledger built to answer it.

**There were three writers, not two.** `POST /items/bulk-move` carried the same
`"moved" if stored else "repacked"`, so the fix now lives in one place — `inbound_reason_for` in
`movement.py`, beside `_INBOUND`/`_OUTBOUND` — and every caller reads it. The two client sites
classify identically and need no round trip: the status is on the row that rendered the button.

### Gestures need a test that presses the pixels

This class of bug has now shipped twice — the `currentToteId` guard above, and this one — and
neither was catchable by the tests this app had. A ViewModel test proves a handler does the right
thing *when called*; a Roborazzi baseline proves the row is *drawn*. Both pass at full green while
the gesture never reaches the handler at all, and the only symptom is a person tapping a thing and
nothing happening.

`ItemRowTapTest` is the first interaction test here and asserts the gesture rather than the layout:
a tap opens, a long press selects **and does not also open**, and while selecting a tap ticks
instead of opening. It was verified against the broken code before being kept — two of its three
cases fail there, which is the only evidence that a regression test is worth its runtime.

Its `@Config(qualifiers = Pixel5)` is load-bearing, not decoration: the rows sit in a lazy list and
on Robolectric's default tiny window they are never composed, which fails as "no such node" and
reads exactly like the bug the file exists to catch.

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

### Review has to know about the queue, not just the drafts

The rule above has one more instance, and it is the one that cost real data. Review knew only
about drafts, so with uploads in flight it said **"Nothing waiting"** — over a queue that was
about to produce exactly what the person was standing there waiting for. A scan takes ~35 s, so
that window is wide and completely ordinary.

The consequence is worse than confusion: not seeing that a capture had sent, the owner
photographed the object again and filed a duplicate. In a storage catalogue two rows for one
comforter are indistinguishable from two comforters.

So the queue is now on this screen too:

- **A strip above the stack**, counting *uploading*, *waiting for signal* and *needs you*
  separately — three counts, not one total, because the right next action differs for each and a
  single "3 pending" flattens them into a number nobody can act on. Rose when something has
  stopped, violet when it is merely in flight.
- **The empty state distinguishes empty from EARLY.** With captures coming it reads "3 on the
  way" and drops the "Photograph something" button, which would otherwise be an invitation to
  shoot the thing that is already uploading.

The queue is merged into the state **at the screen**, not inside `ReviewUiState`'s own flow:
combining them in the ViewModel would make every upload tick re-emit the draft somebody is
mid-edit on.

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

## Acting on a selection

Two bulk endpoints that look alike and are deliberately different animals:

- **`POST /items/bulk-move`** changes which *bin* things are in, so it writes **one ledger row
  each**, through `record_move` like every other relocation. A bulk action is a convenience for
  the person, never a shortcut past the single writer of derived state. `moved` for something
  stored and `repacked` for something out, matched per item — a year later "it changed bins" and
  "it came back" are different facts and a bulk action must not flatten them.
- **`POST /items/bulk-bag`** changes which *bag* inside a bin, and writes **nothing to the
  ledger**. Which bag a thing sits in is a label; relabelling is not a whereabouts event, and
  rows for it would fill "where was this last year" with noise.

Getting that distinction wrong in either direction is the interesting failure, so both have tests
asserting the ledger *count*, not just the outcome.

Three more rules the bulk paths follow:

- **One transaction, not N requests.** The same reason `record_move` does not commit: forty items
  moved individually is forty chances to half-succeed, leaving a selection somebody believes is
  together and is not.
- **All or nothing.** One unknown id fails the whole call before any write. A partial move is
  worse than an error because nothing says so.
- **`item_ids` is required and non-empty**, unlike `BulkMoveIn`'s. "Everything applicable" is a
  sensible default for unpack — the bin is right there and its contents are obvious — but there is
  no obvious default set for "move these somewhere else", and inventing one would move things
  nobody chose.

**There is no bulk delete.** The one destructive action in this app removes photographs that
cannot be retaken, and doing that to twenty rows behind a single tap is a mis-tap with no undo.

### Selection on the client

`selection` is a **nullable set**: null means not selecting at all, an empty set means selecting
with nothing picked. One field rather than a boolean plus a set, so the screen cannot disagree
with itself about which mode it is in.

While selecting, a row's tap **ticks rather than opens** — two meanings for one gesture on one
screen makes every tap a gamble — and the per-row action button disappears, because the bar owns
the verbs. Long-press on any row enters selection with that row already ticked. The bar is
disabled rather than hidden at zero: one that appears and vanishes as you tick things is a layout
that jumps under your thumb.

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

### A bare month is not the same trade — it was a hole

`3m`, `6m` and `9m` were **missing** from `_INFANT_MONTHS`, on the reasoning that infant clothing
is sold in ranges (`0-3M`, `3-6M`). Real tags and real people do not agree, and the table was
already inconsistent with itself: `12M`, `18M` and `24M` were bare points from the start.

Found in production, which is the part worth recording. Four garments in the owner's first real
bin of baby clothes were typed `6m`, parsed to nothing, and were therefore invisible to `fits` —
the exact silent failure the ladder exists to prevent, and one nothing in CI could have caught
because it needs somebody to type what is on an actual tag.

The bare points are the **midpoints of the ranges either side of them**: `3-6m` sits at 0.375
between `3m` (0.25) and `6m` (0.5), so a range and its endpoints interleave in tag order rather
than colliding. `36m` is here too — some tags say it instead of `3T` — and it lands on the same
ordinal `3T` does, which is the point of one shared axis. Two tests pin exactly that: that the
twelve infant rungs are strictly increasing and all distinct, and that `36m` and `3T` agree.

Unlike the bare number above, this is **not** a designed under-read. `6m` is unambiguous; there
was simply no row for it.

### A ladder change does not reach rows already written

The index is derived **at write time** — `size_system`/`size_ordinal` are recomputed from
`size_raw` on every write and are never client-settable, which is the right rule and has its own
section below. The consequence is easy to miss and was: shipping the bare months fixed nothing for
the garments already in the bin. They keep the null the ladder gave them the day they were stored,
so `fits` still cannot see them, and the deploy looks successful because the code is correct.

**Any change to `app/sizing/ladder.py` is therefore two jobs: the rungs, and a backfill of rows
where `size_raw IS NOT NULL AND size_ordinal IS NULL`.** Re-derive through `parse_size` rather than
writing values by hand, so the parser stays the one implementation; `size_raw` is never touched, and
setting the two derived columns back to NULL undoes it. It is not a migration — the schema does not
change, and the set to fix depends on which rungs moved.

## Name-first capture: the question you do not ask

The scan makes two vision calls for a garment — the omnibus `identify_item` and the narrow
`read_label` — and the split is measured, not aesthetic (see below). Name-first takes the same
logic one step further: **no question at all beats a narrow question nobody needed to ask.**

When `POST /items/scan` carries a `name`, identify is skipped entirely. Three things follow, and
the third is the one that is not obvious:

1. **It is the slow half.** Identify is the omnibus call; dropping it roughly halves a scan
   measured at 35.5 s for one photo.
2. **Its answer would have been overwritten**, so every wrong guess was a correction chore in
   review and nothing else.
3. **Identify gates the label pass.** `looks_like_clothing` reads the name and category *identify
   chose*, so a bad guess does not merely cost a correction — it can silently suppress the size
   read, which is the one vision output measured to work well. A human-supplied name and category
   make that gate trustworthy instead of circular. The feature is therefore faster **and** more
   accurate, which is rare enough to be worth stating plainly.

`category_id` rides along for the same reason, and `_read_the_label` resolves it to a name so the
gate's one rule stays in one place. Both paths remain available: leave the name out and the
endpoint behaves exactly as it always has. Whitespace is not an answer — a blank falls back to
identifying.

**The named path must not skip the housekeeping the identified path does.** It returns early, and
early returns are exactly the shape that loses `draft_tote_id` and `processed_at`. There is a test
pinning that specifically.

### The description is optional, and it is not decoration

`items.search_vector` is a generated column over **name, description and notes**. So "the one with
the ducks on it" finds nothing unless something wrote "ducks", and a photographed item with a bare
name is close to unfindable by any words except its own.

That is the argument for `describe_item` — a third narrow prompt, told what the object is and
asked only what distinguishes *this* one. It is the same argument in reverse that keeps it
**off by default**: text that feeds a search index must not contain things the photo does not
show, because a hallucinated detail is not a cosmetic blemish, it is a false hit on a search
someone trusts. So the prompt is forbidden to re-identify, told to prefer null over padding, and
the whole call is opt-in per capture run.

It gets **its own `except`**, like the label pass and for the same reason: a description is the
most disposable thing this pipeline produces and must never take the size read with it. A failed
describe is not a scan failure and is not reported as one.

### On the client

The name, the category and the describe toggle are **sticky across shots** and persisted through
`SavedStateHandle` — the batch this exists for is twenty sleepsuits in a garage with the app
backgrounded between shots, and typing the name twenty times is precisely the per-item work the
feature removes. They ride on the queue row (Room **v4**, three additive columns on
`capture_queue`), because the row is what survives to upload time.

The field holds what was typed, spaces and all; **the trim happens at the repository boundary**,
which is also where a blank becomes a null rather than an empty string. `""` stored on the row
would reach the server as "the person named it" and file an item called nothing at all, with
identification skipped — named by no one.

## The label pass
### The gate is only as good as the category list

`looks_like_clothing` matches the **category the model chose** first, then falls back to a word
list over the name. Both halves are hand-maintained vocabularies, which means **adding a seeded
category can silently switch the label pass off for everything filed under it**.

That happened the day "Baby" was seeded: it matched none of `_CLOTHING_CATEGORY_HINTS`, so a
sleepsuit filed under it would have skipped the size read unless its *name* carried a listed word
— and the list contained `sleeper` but not `sleepsuit`, no `swaddle`, no `bib`. A distinction
nobody typing into a phone would predict, producing exactly the failure the gate exists to
prevent, silently.

**So: any new seeded category is also a change to this file.** If it can contain a garment, it
belongs in the hints. The asymmetry decides the close calls — a baby bin holds a monitor and a
steriliser with no label between them, and each costs one wasted call, while one missed sleepsuit
costs a trip to the attic.



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

`size_raw` rides on the **same caption line** as the bin on a search hit. On a **tote-detail row**
it is now its own element instead — `dataSmall`, in the provenance violet, between the text column
and the action button.

That changed after looking at a real bin. On a screen of children's clothing the size is the
single most-read fact — the whole app exists to answer "which bin has the 4T coats" — and it was
the first words of a dim grey run-on caption it shared with the loan status. Two consequences, one
of them the point: it is legible at a glance, and it is prominent enough that **the size does not
need repeating in the name**, which is what people were doing (`Shirt 12m` above a caption reading
`12M`).

The row's second line is now the item's **description**, and up to two lines of it. It was carried
on every DTO and shown only inside the item sheet, which is fine until a bin holds six garments all
honestly called "Shirt": as text those rows were identical, only the thumbnail told them apart, and
the sentence that *does* tell them apart was one tap away on each of them. Two lines rather than
one because the thumbnail, the size and the action leave that column about twenty characters wide,
and one line of it is `Navy blue sleeves, li…` against `Navy blue with white s…` — the same failure,
moved a few words later. Height is only spent on the rows whose descriptions need it.

`tote_detail_same_name_dark` / `_light` are the baselines for exactly this, six near-identical rows
in one frame. The light one is separate on purpose: the size mark is the first place the provenance
violet carries *text* at data-type size, and a ratio that holds against charcoal says nothing about
one against white.

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

## Photo derivatives

`GET /items/{id}/photos/{order}` takes `?w=` (one of `photo_store.THUMBNAIL_WIDTHS` — a fixed
set, because the width names a file the server will create and an open integer would let one
client mint unbounded derivatives per photo; anything else is 422). The derivative is generated
on first request — Pillow, `thumbnail` + LANCZOS, EXIF-transposed — and cached beside its source
as `thumb_{order}_{width}_{c|o}.webp`, **inside the item's directory**, so `delete_item_photos`'
rmtree collects derivatives without knowing they exist.

Why this exists: without it, every 52dp list thumbnail on the client downloaded the full cleaned
PNG — megabytes of RGBA over the attic's Wi-Fi to paint a square smaller than a stamp — which is
why lists scrolled ahead of their pictures.

Three rules worth knowing:

- **WebP, never JPEG.** The cleaned copies are RGBA cutouts and a JPEG derivative would flatten
  their alpha to black — the same defect class the cleanup module's compositing rules exist to
  prevent, arriving through a new door. A test asserts the thumb keeps its transparency.
- **The source is chosen first; the derivative's name follows it** (`_c` from the cleaned copy,
  `_o` from the original). That is the supersession mechanism: a thumb made from the original
  while no cleaned copy existed is simply never served again once one does — no invalidation
  step. Today cleanup runs synchronously inside the scan request, so that transition cannot
  actually occur in production; the suffix is the contract for the day cleanup goes async, and
  meanwhile it keeps `cleaned=false` book-cover thumbs from colliding with cleaned-derived
  ones. Stale `_o` files are dead weight bounded by widths × sources × photos and are reaped
  only by item deletion. A source that does not decode (a corrupt upload the scan deliberately
  kept) is served whole rather than turned into a 500.
- **Every photo response carries `Cache-Control: private, max-age=86400`.** Private because
  these are photographs of the inside of a house behind auth; a day because Coil's disk cache
  is the client's offline photo store and, without the header, HTTP heuristics made freshly
  catalogued photos — exactly the ones being scrolled — revalidate on every pass. Caveat:
  Starlette's `FileResponse` sets ETag/Last-Modified but answers no 304s, so expiry means
  re-downloading a tens-of-KB derivative, not the original. Generation is atomic against
  concurrent requests (per-writer temp file + `os.replace`), and the serving path derives the
  thumb's directory from the source's own parent — never through the mkdir-ing `item_dir()` —
  so it cannot re-create a directory a concurrent delete just removed.

Client side, `PhotoUrls` appends `w` in a fixed position because the full URL is Coil's cache
key; lists ask for 192 (52dp thumbs), grid tiles and the review strips 512, the item sheet's
hero 1024. The Room cache stays photo-free on purpose: Coil's disk cache already is the offline
photo store once the server permits caching, and a second cache with a second eviction policy
would be a drift machine.

## Which way up: orientation

**The client destroyed orientation on the way up, and for every photograph taken before
v1.0.57 it is not recoverable.** A phone camera writes its pixels in *sensor* orientation and
records how far to turn them in an EXIF Orientation tag. `ImageBytes.downscaleToJpeg` decoded
with `BitmapFactory` — which ignores that tag — and re-encoded with `Bitmap.compress`, which
writes no EXIF at all. So the upload was sideways pixels with nothing left in the file to say
which way up they belonged. There was no EXIF handling anywhere in the client and no
`exifinterface` dependency; the server's `exif_transpose` in `ensure_thumbnail` was correct
defensively and a no-op on these files, because there was no tag left to act on.

Two halves, because the problem has two halves:

**Forward — the pixels become canonical.** The tag is now read off the ORIGINAL bytes *before*
the decode (the last moment it exists) and baked in with a matrix during the re-encode. All
eight orientation states are handled, not just the three rotations: a half-handled tag is worse
than an unread one, because it puts *some* photographs right and leaves others wrong with no
pattern anyone can spot. Uploaded bytes are then upright by construction — no metadata anyone
downstream has to remember to honour. `ImageBytesTest` builds real EXIF-bearing JPEGs; two of
its four orientation cases were **checked against the unfixed code and fail there**, and the
180° case asserts a marked corner *moved* rather than only that the dimensions survived, because
a half-turn preserves dimensions and a test that cannot fail reads as coverage.

**Backward — a person says, and the app records it.** `item_photos.rotation` (migration `0008`,
degrees, CHECK-constrained to the four right angles) is a HUMAN's correction. It is applied when
a derivative is rendered and **never baked into the stored bytes**: the photographs are the one
artefact here that cannot be recreated, so rotation stays a derived index over them — which also
makes a wrong turn one more tap instead of a lost generation of re-encoding. The app does not
guess an angle from the pixels; a landscape-shaped garment photo is a decent *suggestion* and
this codebase does not write suggestions as fact.

Three consequences worth knowing:

- **Rotation joins the derivative's cache key** (`thumb_{order}_{width}_{c|o}[_r{deg}].webp`),
  for the same reason the source does. The suffix is omitted at 0, so every derivative made
  before rotation existed keeps its name and stays a cache hit.
- **It is in the URL too** (`&r=`), and that is not the client deciding how to turn a photo —
  the server applies its stored rotation when `r` is absent, so a curl or an older client still
  gets it the right way up. `r` exists because the whole URL is Coil's cache key: without a term
  that moves when a photograph is corrected, the phone would serve the old thumbnail from disk
  for `max-age` and the fix would look like it had not worked. `ItemOut.photo_rotation` carries
  photo 0's angle so a list row can build that URL without a request per row.
- **The full-size path honours rotation as well**, via a `_full` derivative. Nothing in the
  client asks for that path today — every call site passes a width — which is exactly why it is
  pinned by a test: an unused branch quietly serving sideways pixels is how this comes back.

`GET /photos/orientation` lists every non-draft photograph for the fix screen (drafts are
excluded — they are about to be looked at on review anyway), and `POST /photos/bulk-rotate`
records one pass in one transaction, all or nothing, because a partial save leaves a grid
somebody has just finished correcting half-corrected with nothing on screen saying which half.
The screen holds its pending turns in memory and previews each with a local `Modifier.rotate`
rather than re-fetching, so a correction pass costs no round trips until Save; leaving without
saving changes nothing.

## The bin screen is a photo grid

The bin screen's contents are `ItemCell`s (`ui/totes/ItemCell.kt`) — photograph-first cells, two
to a row — not `ItemRow`s. The layouts split by question: a bin's contents are recognised by
*sight* (you are matching pictures against a memory of the thing, and the photographs are the one
part of the catalogue that cost real work to capture), so the photo takes the cell and the words
caption it. Everywhere an item is listed *across* bins — search, unfiled, a person's fits and
loans, category browse — the tote code and location matter more than the picture is big, and
`ui/components/ItemRow.kt` remains the one shared row there. Do not "unify" them back into one
component; that difference is the design.

The cells are chunked into full-width `LazyColumn` rows (`itemCellRows`) rather than a nested
lazy grid, because the screen interleaves bag headers, size-group headings and empty states with
the cells, and a grid that cannot host arbitrary full-width rows would force every heading out of
the scroll. Grouping (bags then loose; out-list by size in ladder order via `outBySize`) is
unchanged.

Three rules carried over from `ItemRow` verbatim, each with a test behind it:

- **One `combinedClickable` on one modifier** carries tap and long-press. A second clickable
  container underneath cannot be reached (#38); the cell's everyday verb ("Take out" / "Put
  back") is a `TextButton` *inside* the tappable cell, which is exactly that trap's shape, so
  `ItemRowTapTest` presses it and asserts the verb wins the pointer without falling through.
- **While selecting, a tap ticks instead of opening**, and `selection` stays the nullable set.
- **Out items keep their own section**; in it they render dimmed with a rose "Out" mark, and
  routine status is suppressed under headings that already say it.

The management chrome moved off the content's path, which was the point of the redesign: edit /
write-tag / print-card are icons on the hero (the write icon is disabled without NFC and the
labelling line says why), the labelling state is **one line** with one verb (rose-dotted only
when the bin has neither tag nor card — a bin with either can already be found, and the attention
channel must stay rare to stay loud; the settled state is a dated sentence with no verbs), and
`Add item` plus an `Unpack…` / `Repack…` menu are **pinned at the bottom**. While selecting, the
`SelectionBar` takes that pinned slot, so the verbs for what is ticked stay under the thumb doing
the ticking. The `LazyColumn` carries 120dp of bottom content padding so the last cells scroll
clear of the pinned bar. `Select` lives in the header of whichever section is drawn first, so a
fully unpacked bin (no "In this tote" block) still offers it.

## Not yet built

Phases 0-7 are complete on both sides. What remains is Phase 8: polish, the empty/error-state
sweep, the README, and the Dragonfly `ServiceRegistry` row now that the URL is real.

The smoke script carries an explicit list of what each phase must add to it. Crate's stopped at
`/users/me` for months, so "auth works" read as "the app works" while the pipeline the app exists
for was never exercised.
