# CLAUDE.md — "Tote"

> A digital catalog of what is physically in your storage bins. Every tote carries an
> index card and an NFC tag; tap the tag to see what's inside, search the catalog to
> find which tote something is in, and move items in and out as kids grow into sizes
> or the holidays come around. Eighth app in the personal suite alongside **Spotter**
> (fitness), **Plate** (nutrition), **Cookbook** (recipes), **Dragonfly** (hub/identity),
> **Magpie** (finance), **Remnant** (capture), and **Crate** (eBay selling). Same stack,
> same conventions, same PULSE design language consumed as the shared `pulse-ui` library.

---

## START HERE — state as of 2026-08-16

**Phases 0-7 are complete.** Everything below is merged to `main`, deployed, and verified
against production — not just green in CI.

| | Status |
|---|---|
| Live at | `https://dragonfly.tail2ce561.ts.net:8448` (tailnet only) |
| Tests | **251 server** (pytest, real Postgres) + **104 Android** (measured 2026-08-16) |
| CI/CD | green; every push to `main` deploys, `notify.yml` pages `tote-alerts` on red |

### The next task

**Phase 8 — polish + release** (§8). Phase 6 landed 2026-08-16.

**Before either: the on-device pass has never been run.** Phases 4 and 5 added the camera flow, a
WorkManager queue holding photos that exist nowhere else, and a Room schema now at **v3** — all
of which only a real phone can exercise. See the open items table.

### Six things that will cost you time if you do not know them

1. **`C:\Code\Tote` IS production.** `TOTE_DIR` points at it and every green deploy runs
   `git reset --hard` there. Work in a worktree (`git worktree add ../Tote-x -b branch main`),
   copy `android/local.properties` into it, and build a fresh `server/.venv` there — an editable
   install from another checkout silently shadows the code you are testing.
2. **Alembic autogenerate will try to DROP two indexes, every single time.**
   `ix_items_search_vector` (GIN full-text) and `uq_totes_user_code_lower` (unique on
   `lower(code)`) are invisible to model metadata, so it proposes removing them. Accepting that
   makes search sequential and lets two bins both be "A14". Delete those lines by hand. It also
   emits **unnamed foreign keys** that `downgrade` cannot then drop — name them.
3. **Room has no destructive fallback.** Bumping `@Database(version=)` requires a migration in
   `ToteMigrations.ALL` **and** a committed schema export under `android/app/schemas/`.
   `ToteDatabaseMigrationTest` fails the build otherwise. This exists precisely because the
   capture queue you are about to add is data that exists nowhere else.
4. **Render the screens.** Three real contrast bugs got past code review, tests and CI and were
   only visible in a Roborazzi PNG. Add scenes to `ScreenshotTest`, run with
   `-Proborazzi.test.record=true`, and *look at the images*.
5. **Scans need LM Studio running** on the host with `google/gemma-4-e4b` loaded. A live scan
   measured **35.5 s** against a 60 s timeout — headroom, but cleanup is sequential per photo,
   so an 8-photo item will be slower. If `/items/scan` 503s, check `GET :1234/v1/models` first.
6. **Install `rembg` in your test venv.** Without it `clean_photo` silently takes the degraded
   Pillow-only path, so the compositing branch — where the original blackening defect lived —
   never runs locally while CI runs it.

### Local test recipe

```bash
# Server (from a worktree's server/ directory)
python -m venv .venv && ./.venv/Scripts/python.exe -m pip install -e ".[dev]"
./.venv/Scripts/python.exe -m pip install "rembg[cpu]==2.0.78"   # see #6 above
docker exec tote-db-1 psql -U tote -d tote -c "CREATE DATABASE tote_dev;"
DATABASE_URL="postgresql+asyncpg://tote:tote@127.0.0.1:5439/tote_dev" \
  SECRET_KEY=dev DB_NULLPOOL=true ./.venv/Scripts/python.exe -m pytest tests/ -q
```

`127.0.0.1` never `localhost` (Docker publishes IPv4 only; the `::1` fallback stalls), and
`DB_NULLPOOL=true` or pooled asyncpg connections bind a dead event loop. CI runs **both**
`ruff check` and `ruff format --check`.

```bash
cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest
```

### Open items that need a human

| Item | State |
|---|---|
| **`tote-smoke` credential** | **Not done, and needed on BOTH sides.** dragonfly-id currently has `SMOKE_CLIENTS=magpie-smoke` only, and `SMOKE_SUBJECT_EMAILS` lacks `tote-smoke@dragonflymedia.org`. Add both there, and the same secret value in Tote's `server/.env`. Until then `scripts/synthetic_smoke.py` cannot run its auth stage. |
| **On-device pass** | Never run. Camera flow, the AppAuth redirect, and **NFC read/write against real NTAG215s** — none of which CI or an emulator can test. `ToteDatabaseMigrationAndroidTest` also only runs here (`./gradlew :app:connectedDebugAndroidTest`). |
| **ntfy topic** `tote-alerts` | Referenced by `notify.yml`; confirm it exists on the self-hosted ntfy (`:8095`). |
| **Backups** | Phase 7. **Nothing backs up `/data/photos` yet.** Once real photos exist they are the artifact — the rows are just paths pointing at them. |
| **Dragonfly `ServiceRegistry` row** | Deferred until the URL was real. It is real now. |
| **The physical bootstrap** | Printing the first index cards and writing the first tags — the moment the design either works in an attic or does not. |

### Deliberately not done

Five **dependabot** PRs are open in Pulse (#19, #16) and Dragonfly (#30, #29, #27). They were
left alone on purpose: Gradle/Kotlin/AGP versions must stay aligned with Pulse across all six
Compose consumers, so a bump is a suite-wide, all-repos-in-one-sitting change and merging one in
isolation can break the composite build everywhere.

---

## 0. Read this first

This file is the source of truth for the build. Work **phase by phase** (§8); do not start
a later phase before the earlier one's exit criteria (tests green, CI green) are met. When
a decision is ambiguous, **match the existing suite apps' choice** — inspect the sibling
repos (**Crate** is the closest template: newest conventions, same capture pipeline, same
tailnet-only/SSO-only posture; Cookbook is the reference for the general full-pipeline
shape). If this file conflicts with how the existing apps actually do something, **the
existing apps win** — flag the conflict.

Before writing code in any phase: restate the phase goal, list the files you'll touch,
flag any assumption, then proceed.

### Decisions locked 2026-08-15 (user-confirmed)

| Topic | Decision |
|---|---|
| Name / package | **Tote**, `com.tote`, repo `CDRaab01/Tote`, default branch `main` |
| Ports | **API 8008 / Postgres 5439** — verified free 2026-08-15 against *both* `netstat` and `docker ps` |
| Reachability | **Tailnet-only** — Tailscale Serve **`:8448`** (verified free against live `tailscale serve status`). No cloudflared, no `tunnel` profile, no public hostname. A complete household inventory of electronics, tools and vintage games is a burglar's shopping list. |
| Auth | **SSO-only** (Magpie/Crate precedent): "Sign in with Dragonfly" via `POST /auth/suite`. No register/password endpoints. Synthetic-smoke token for deploy smokes. |
| NFC payload | **Pointer + cached summary.** NDEF record 1 = URI `https://dragonfly.tail2ce561.ts.net:8448/t/<code>`; record 2 = Text with tote code, label, category, location, item count, last-updated date. The app always fetches live contents; the cached text is for a stock NFC reader on a phone without Tote. Contents changing never invalidates a tag. |
| Capture | **One photo per item**, reusing Crate's proven scan pipeline (batch queue → background drafts → review stack). Multi-item detection from one bulk photo is explicitly **not v1** (§10). |
| Sizing | **Copy + extend Crate's `app/apparel/`** (pure module, suite rule: no shared monolith), **plus a new `app/sizing/` size-ladder module** that Crate does not have — see §5, this is the real new logic in the app. |
| Pulse accent | **Slate — LOCKED and shipped** ([Pulse #20](https://github.com/CDRaab01/Pulse/pull/20), merged 2026-08-15). A *pair*: charcoal body + safety yellow, after the black-and-yellow site tote. See §3. |
| Deploy | Self-hosted runner labeled **`tote`**, `vars.TOTE_DIR`, green-`main` redeploy — clone Crate's `deploy/`. |

---

## 1. Product summary

The problem Tote solves is not "make a list." It is: **six months from now, in the attic,
which of these fourteen identical grey bins has the 4T winter coats in it** — and the
inverse, **we have a 4-year-old now, what do we already own that fits.**

Three entry points, in expected order of daily use:

1. **Search** — type "ratchet set" or "Zelda" or "4T", get the item and the tote it is in
   and where that tote physically is. This is the primary query path; design for it first
   (§4, Postgres full-text + GIN).
2. **Tap** — hold the phone to the tote's NFC tag, the app opens that tote's live contents.
   No typing, no hunting for the right bin in a list.
3. **Browse** — by location (everything in the attic), by category (all Christmas), by
   person (what fits Emma right now).

And two write paths:

- **Catalog** — photograph an item, Gemma drafts name/category/condition (and reads a
  clothing tag for size), you confirm, it lands in a tote. Batch queue so you can shoot a
  whole bin's worth back to back.
- **Move** — take items out and put them back. This is a first-class operation, not an
  edit: "unpack the Christmas tote" in November and "repack" in January; "Emma outgrew
  these" moves a size run from the wearing pile to a tote; "lent the drill to Dave."
  Every move is a ledger row (§4 `movements`), so an item's whereabouts has a history and
  "where was this last year" is answerable.

**Physical artifacts Tote produces**: a printable **index card** per tote (code, label,
category, location, item count, date, and a **QR code**) and a **written NFC tag**. The QR
is deliberate redundancy — NFC tags fail, get covered by tape, or sit on a bin someone is
holding in both arms; a QR on the same card costs nothing and reads from four feet away.

---

## 2. Stack & ecosystem decisions (already made — do not relitigate)

- **Client:** Android, Kotlin, Jetpack Compose, MVVM + repository, Room + Retrofit — mirror
  Crate's client architecture. Room holds the capture queue (photos + draft state must
  survive process death and upload over WorkManager) and a **read cache of the catalog**;
  the server is the source of truth. The read cache matters more here than in any sibling:
  **the attic and the garage have bad Wi-Fi**, and a catalog you can't read where the bins
  physically are is a catalog you won't use. Search must work against the cache offline.
- **Backend:** Python FastAPI, SQLAlchemy 2.0 async + Alembic, Postgres, same layout as
  Crate (`app/routers|services|models|schemas`), same lint/test tooling (pytest + ruff,
  same configs).
- **Own backend, own DB, own users table** (SSO find-or-create by email). One-app-one-backend
  stays the rule; cross-app needs use the established patterns (RS256 suite tokens,
  `CROSS_APP_SECRET` JWTs) — no shared monolith, no shared database.
- **Deployment:** Docker Compose (`db`, `server`) on the Dragonfly host. Host ports **API
  8008, Postgres 5439**. Migrations on boot, `GET /health` + `GET /version`
  (unauthenticated), self-hosted GitHub Actions runner (`tote` label) redeploy — clone
  Crate's `deploy/`. **No cloudflared / no `tunnel` profile.**
- **Suite conventions Tote must uphold:**
  - `/version` reports `{name, version, commit, built_at}`; CI publishes a signed release
    APK + `version.json` on any `android/**` push to `main` (`release.yml`, epoch-minutes
    versionCode, suite signing key, `apksigner` guard pinned to `5a596c9e…`; the release
    job checks out the sibling **Pulse** repo).
  - Config broker: `util/SuiteConfigReader` reads
    `content://com.dragonfly.suiteconfig/config/tote` in `App.onCreate`, falling back to
    local prefs (copy Crate's reader).
  - **Compose env rule:** required non-secret config lives in `docker-compose.yml`'s
    `environment:` block as **literals**; secrets in `server/.env`. Compose does not reload
    `env_file` content on recreate — this has caused production regressions three times
    now, most recently Crate's `NTFY_TOPIC`, which interpolated `${NTFY_TOPIC:-}` from a
    root `.env` that repo did not have and so could never be anything but empty.
  - **`notify.yml` from Phase 0, not retrofitted.** Crate was the first repo in the entire
    suite to notify on a red run (2026-08-14) — before that, `pip-audit` sat red for three
    weeks unnoticed. Copy Crate's: it must run on the self-hosted runner (suite ntfy is
    tailnet-only on `:8095`), which is exactly why it **cannot** be an `if: failure()` step
    inside `ci.yml` — `ci.yml` has a `pull_request` trigger and **suite invariant 7**
    forbids a self-hosted job being reachable from one. Pass all `workflow_run` metadata
    via `env:`, **never** interpolated into the shell: it fires for fork PRs with base-repo
    privileges and a branch name is attacker-controlled text on the prod host.
  - **Cross-repo registrations at Phase 0** (small PRs in sibling repos): Dragonfly
    `AppRegistry` + manifest `<queries>` gain `tote | com.tote | CDRaab01/Tote`;
    dragonfly-id gains the `tote` static OIDC client (redirect `com.tote:/oauth2redirect`)
    plus a `tote` smoke client and `tote-smoke@dragonflymedia.org` in `SMOKE_SUBJECT_EMAILS`;
    Pulse registers the accent (§3). Defer Dragonfly's status-dashboard `ServiceRegistry`
    row until the real ts.net URL exists (the Hawksnest URL-guess lesson).
- **AI:** LM Studio only, server-side, house guardrail model — vision output is
  validated/salvaged server-side, drafts are never auto-committed, the user confirms
  before anything is written to the catalog. **No exceptions in this app**: unlike Crate,
  Tote has no deterministic-policy carve-out. Nothing writes itself.

---

## 3. PULSE (shared library)

- Consume `design.pulse:pulse-ui` via Gradle composite build (`includeBuild("../../Pulse")`;
  sibling checkout, CI checks it out too) — never copy tokens/components in-tree.
- **Accent: `PulseAccent.Slate`, shipped in Pulse 2026-08-15.** It is the only accent in the
  family that is a **pair of hues** rather than a bright/deep pair of one hue — a charcoal
  body with a safety-yellow marking, after the black-and-yellow site tote.
  The constraint everything follows from: **white on `PulseYellow` is 1.42:1**, so yellow
  can never carry text. The halves therefore swap roles by theme — dark mode's surface is
  already the charcoal, so the yellow leads (13.66:1 on ink, inked at 11.92:1); on white the
  charcoal takes the text-bearing primary (10.35:1) and yellow drops to the container fill
  (charcoal on it, 9.30:1). Both halves are present in both themes.
  **The hero sweep contains no yellow, on measured grounds**: a slate→yellow blend passes
  through the olive `#8A8023` where white falls to 4.04:1 *and* ink only reaches 4.81:1 —
  the one blend in the family where neither text colour is safe. The hero is charcoal-only
  and the yellow goes on top as a mark (`ToteColors.hazard`, `HazardRule`). Do not
  "complete" the gradient with a yellow stop; `ToteThemeTest` guards the ratios, including
  the negative case that white-on-yellow must **fail**.
- **Tote channel semantics** (app-side `ui/theme/ToteTheme.kt`): **slate** = hero/primary
  actions and tote identity; **recovery green** = stored/complete/put-away; **rose** =
  attention (item out past its expected return, tote with no NFC tag written, uncatalogued
  drafts waiting); **electric blue** = search and cross-references; violet stays
  supporting/provenance.
  **Attention is rose, not the amber every sibling uses** — Tote's lead is a safety yellow
  and amber sits only 14.9° of hue from it, so an amber "needs attention" mark beside a
  yellow "this is Tote" mark reads as a single signal and the whole screen looks like a
  warning. Red stays the error voice. For the same reason, hue alone does not separate Tote
  from Cookbook: the structural contrast does (Cookbook's warm primary and bright ink-text
  hero vs Tote's cool primary and charcoal white-text hero). Keep that gap wide.

---

## 4. Data model (backend)

- `users` — id, email, name, created_at. SSO find-or-create by email; **no password hash
  column** (SSO-only), mirror Magpie/Crate.
- `totes` — id, user_id, **code** (short human label written on the index card, e.g. `A14`;
  **unique per user**, case-insensitive), label, category_id, location_id (nullable),
  notes, bin_kind (free text: "27gal clear", "banker box"), color, `nfc_tag_uid` (nullable),
  `nfc_written_at` (nullable), `card_printed_at` (nullable), archived (bool), created_at.
  **`item_count` is computed, never stored** — a denormalized count is the first thing to
  drift, and the count is on the NFC tag and index card where drift is visible.
- `locations` — id, user_id, name ("Attic", "Garage rack B", "Basement closet"), parent_id
  (nullable, one level of nesting is enough), sort_order. A light table rather than free
  text on the tote, because "show me everything in the attic" is a browse entry point (§1)
  and free text fragments into "attic"/"Attic"/"the attic".
- `categories` — id, user_id, name, icon, sort_order, seeded with the user's real domains
  (Christmas/seasonal decor, clothing, electronics, vintage games, tools, kitchen, books,
  documents, toys, sporting goods, craft/hobby) and freely editable. Seeded rows, not a
  Python enum: this vocabulary is the user's and will change.
- `items` — id, user_id, name, description, category_id, **quantity** (int, default 1 — a
  tote holds "4× ornament box", and forcing four rows is worse than a count), condition
  (`new|like_new|good|fair|poor`), **status** (`stored|out|loaned|disposed`),
  **current_tote_id** (nullable — null means not in any tote right now), `out_reason`
  (nullable), `out_since` (nullable), `expected_back` (nullable), value_est (nullable),
  acquired_at (nullable), notes, **`search_vector`** (tsvector, GIN-indexed, generated from
  name + description + category + tags), created_at, updated_at.
- `item_photos` — id, item_id, order, original_path, cleaned_path (nullable until
  processed), role (`front|back|detail|tag`, nullable), created_at. Binaries live on a
  server volume (`/data/photos`, named volume, survives redeploys); the DB stores paths
  only. **Copy Crate's `photo_role_rank` discipline**: `ItemPhoto.order` is never rewritten
  because `photo_store` derives on-disk filenames from it, and renumbering orphans files.
- `item_apparel` — the clothing specifics, one-to-one nullable extension of `items` rather
  than eleven mostly-null columns on every ratchet set and board game: `size_raw` (verbatim
  from the tag — see §5), `size_system`, `size_ordinal` (nullable), department, size_type,
  color, material, style, fit, sleeve_length, `measurements_in` (JSON), `season`
  (`winter|summer|all`, nullable).
- `movements` — **the ledger, and the reason this app is not a spreadsheet.** id, item_id,
  from_tote_id (nullable), to_tote_id (nullable), quantity, reason
  (`initial|moved|unpacked|repacked|outgrown|loaned|returned|disposed|corrected`), note,
  person_id (nullable — who it was lent to, or who outgrew it), moved_at, created_at.
  `items.current_tote_id` and `items.status` are **derived state kept in sync by one
  service module** (`app/services/movement.py`); nothing else writes them directly.
- `people` — id, user_id, name, birthdate (nullable), notes. The wearer profiles behind
  "what fits Emma right now" and "who has the drill."
- `person_sizes` — id, person_id, garment_type (`tops|bottoms|shoes|outerwear`),
  `size_system`, `size_ordinal`, `size_raw`, effective_from, notes. A history, not a current
  value: a child's size is a moving target and last winter's answer is what tells you which
  bin to open next winter.
- `settings` — per-user: default location, NFC URI base (so a hostname change doesn't
  require a code deploy), card layout preference, ntfy topic override. Seeded defaults.

Constraints worth writing down as constraints, not conventions: `totes.code` unique per
user (the code is printed on a physical card — a duplicate is a real-world ambiguity);
`items.current_tote_id` must be null whenever `status != 'stored'`, enforced in the movement
service and asserted in tests.

---

## 5. Sizing — the one genuinely new module (`app/sizing/`)

**Copy `app/apparel/` from Crate** (`attributes.py` + `completeness.py`): controlled
vocabularies, the forgiving-shape/strict-membership `normalize_enum`, bounds-checked
`normalize_measurements`, and `photo_role_rank`. That module is pure, heavily tested, and
already carries the write-path asymmetry Tote wants — **vision output degrades (unknown
enum → null), a hand `PATCH` rejects (422)**.

**What the copy does not give you, and must not be assumed to**: Crate's `SIZE_TYPES` is
`regular|petite|plus|big_tall|juniors|maternity` — an eBay merchandising axis, *not* the
infant/toddler/youth/adult axis Tote needs. And Crate's `size` is **deliberately free text**,
because a real tag says "Heather Grey / M/L" and enumerating it loses what a human read off
the garment. That decision is correct and Tote keeps it. So the NB → 3-6M → 4T → 10 → mens S
→ womens 6 ladder is **new code**, and it is the hardest logic in this app.

`app/sizing/` — pure, no I/O, exhaustively table-driven-tested (Cookbook's `lists/merge.py`
and Crate's `pricing/` precedent):

- **`size_raw` is sacred.** Whatever is on the tag is stored verbatim, forever, unmodified.
  Everything below is a *derived index* over it, and a derived index that is wrong must
  never be able to destroy the reading.
- **`parse_size(raw) -> (system, ordinal) | None`** maps a raw string onto a ladder:
  - `infant_months` — NB, 0-3M, 3-6M, 6-9M, 9-12M, 12M, 18M, 24M
  - `toddler` — 2T, 3T, 4T, 5T
  - `youth_numeric` — 4, 5, 6, 6X, 7, 8, 10, 12, 14, 16 (note **6X**, which sorts between
    6 and 7 and is exactly the kind of thing a naive integer parse silently mangles)
  - `youth_alpha` — XS/S/M/L/XL (youth)
  - `adult_alpha` — XXS … 3XL
  - `womens_numeric` — 0, 2, 4, 6, …
  - `mens_waist` — 32x30 style, waist and inseam captured separately
  - `shoe_us_child` / `shoe_us_adult`
- **`ordinal` is a float on one shared approximate-body-size axis** so a query can ask "the
  next size up" across a boundary (4T → youth 5). **Cross-system equivalence is approximate
  and the UI must label it as such** — 4T and youth 4 are not the same garment and the app
  must never assert they are. Within a system, ordering is exact and can be trusted.
- **Unparseable ⇒ `size_raw` kept, `size_system`/`size_ordinal` null, item saved normally.**
  Never guess. This is the same trade as Crate's never-infer rule: a null sends a human to
  the bin, a wrong size sends them to the wrong bin twice.
- **`fits(person, item, tolerance)`** — the "what fits Emma right now" query, resolved
  server-side against `person_sizes` (clients display, never compute).

**Reading the size off a tag reuses Crate's measured work — read that first.** Crate's
2026-08-15 label pass took size reading from 3/18 to 15/18 with **zero invented sizes**, and
the way it got there is non-obvious and expensive to rediscover:

- A **separate narrow prompt** that does exactly one job (transcribe what is printed) beats
  pushing the omnibus identify prompt harder. The louder omnibus prompt gained **no recall
  and produced a reproducible wrong answer**.
- It reads the **original** photo, not the cleaned one. Cleanup is unpredictable on labels —
  it once decided a woven brand tab was "the subject" and cropped the shirt away.
- The label pass needs **its own `except`**. A 503 from the label call reaching the outer
  handler rewrites a good identification as `identify_unavailable`.
- **Do not** retry the cleaned copy when the original returns null: measured, it recovers
  the failing image 2 runs in 3 and answers a *wrong size* the third.
- gemma-4 is a **reasoning model**: set **no `max_tokens`** on vision calls. An
  answer-sized budget lets hidden reasoning tokens eat it and silently return `""`, which
  every parser reads as "unreadable."
- If you re-measure: **derive ground truth by reading the image, never from a filename**,
  and only trust **same-session paired comparisons** — results shift between sessions and
  three runs cannot distinguish 12 from 15.

---

## 6. NFC, QR, and the index card

**Tag format** (locked, §0): NDEF with two records.

```
record 1  URI   https://dragonfly.tail2ce561.ts.net:8448/t/A14
record 2  Text  TOTE A14 — Christmas / decor
                Attic shelf 2 · 37 items · updated 2026-08-15
```

- **Launching the app from a tap does not need Android App Links verification.** Register an
  `NDEF_DISCOVERED` intent filter on `scheme=https`, the ts.net host, and `pathPrefix=/t/`.
  App Links `autoVerify` would require a reachable `.well-known/assetlinks.json` on a
  tailnet-only host, which is a fight worth not having. NFC dispatch matches on the filter
  directly.
- **Writing** uses `Ndef`/`NdefFormatable` in a foreground reader-mode session. Handle the
  three real failure modes explicitly: tag too small for the payload (truncate the *text*
  record, never the URI), tag read-only, tag moved away mid-write.
- **Store the tag's hardware UID** in `totes.nfc_tag_uid` on write. It is what lets Tote say
  "this tag belongs to tote A14, but you have A14's card on a different bin" instead of
  silently trusting whatever the tag claims.
- **The URI base comes from `settings`**, not a constant. If the ts.net URL ever changes,
  every already-written tag in the attic is a physical object that cannot be patched by a
  deploy — the server resolving `/t/<code>` regardless of host is the escape hatch, and it
  is why record 1 points at a *path with the code in it* rather than an opaque id.
- **Server serves `/t/<code>`** as a tiny unauthenticated-shell HTML page that requires auth
  to show contents: enough to say "Tote A14 — sign in to view", so a tap from a phone
  without Tote is not a dead end. It must not leak contents pre-auth.

**Index card + QR**: `GET /totes/{id}/card` renders a printable card (PDF, index-card sized)
carrying code, label, category, location, item count, date, and a **QR encoding the same
URI as the NFC tag**. Generated server-side so the layout is one implementation. The QR is
the redundancy that makes the system survive a dead tag, and it reads from across a room.

---

## 7. Feature flows

**Catalog an item.** Camera screen with a running queue chip (N pending), Crate's proven
path: 1–8 photos per item, downscaled client-side to ≤1600px JPEG (`util/ImageBytes.kt`
precedent — raw camera captures blow the upload cap), persisted to the local queue (Room +
files), uploaded by WorkManager when connected. Server processes each draft async: cleanup
→ identify (name, category, condition) → **if clothing, the label pass for size** → draft
ready. Review stack: photos, identified item, category, quantity, size if read, everything
editable. Accept ⇒ item lands in the selected tote with an `initial` movement row.
**Note the offline reality**: cataloging happens in the garage. The queue must hold a full
bin's worth of captures and upload later without the user thinking about it.

**Find a thing.** Search-first home. Query hits `search_vector`, results show item → tote
code → location, with the tote's photo/color if set. Tapping through opens the tote.
Offline, the same search runs against the Room cache.

**Tap a tote.** NFC tap → tote screen → live contents, grouped by category, with the
"items currently out of this tote" section visible rather than hidden (that section is the
answer to "I thought the lights were in here").

**Unpack / repack.** Bulk operations on a tote: "unpack" moves every item to `out` with
reason `unpacked` and one movement row each; "repack" returns them. Partial selection
supported. This is what the holidays actually look like, and modeling it as fifty
individual edits would mean nobody does it and the catalog rots.

**Outgrown → next size.** From a person's screen: mark a size run outgrown (reason
`outgrown`, `person_id` set) and file it into a tote in one flow. The inverse — "Emma is
in 5T now, what do we have" — resolves through `fits()` (§5) and lists items with their
totes, so it is one trip to the attic instead of four.

**Lend.** Mark out with reason `loaned`, `person_id`, `expected_back`. Items out past
`expected_back` surface on Home under the amber attention channel and, if a topic is
configured, as an ntfy nudge.

---

## 8. Build phases (each ends with green tests + green CI)

**Phase 0 — Scaffold + suite registrations.** ✅ **DONE 2026-08-15.**
- ✅ Pulse: `PulseAccent.Slate` + accent-claim row ([#20](https://github.com/CDRaab01/Pulse/pull/20),
  merged). Verified: pulse-ui assemble + tests green, index regenerated, no consumer matches
  `PulseAccent` exhaustively, Cookbook `:app:assembleDebug` built against the branch.
- ✅ Tote repo: Android skeleton consuming `pulse-ui` (ToteTheme, slate-led, 5 contrast tests),
  FastAPI skeleton with `/health` + `/version` (4 tests), Docker Compose (8008/5439),
  `ci.yml` (ruff check **and** `ruff format --check`, pytest, assembleDebug) + `notify.yml`.
- ✅ `release.yml` + `deploy.yml` + `deploy/` — landed once the Actions config existed. The
  secrets had originally been entered on the repo's **Agent** tab rather than **Actions**; those
  are separate stores and `${{ secrets.* }}` cannot see the Agent ones, so the API reported zero.
  Re-set on Actions from `C:\Users\Sonic\.dragonfly-suite\`, after verifying the keystore's
  SHA-256 matches the suite pin `5a596c9e…` and that `suite-keystore.base64.txt` decodes
  byte-identically to `suite-release.jks`.
- ✅ Sibling PRs: Dragonfly `AppRegistry` + `<queries>` and dragonfly-id's `tote` OIDC client
  ([Dragonfly #31](https://github.com/CDRaab01/Dragonfly/pull/31), merged 2026-08-15, with a
  registration test). The `tote-smoke` **secret value** is still human-gated.
*Exit: empty app builds with the slate theme; CI green; trivial tests pass.*

**Phase 1 — SSO auth + data model.** ✅ **DONE 2026-08-15** (#1). `POST /auth/suite` (clone Crate's SSO-only shape: JWKS
validation, find-or-create by email, feature-flagged on `SUITE_JWKS_URL`/`SUITE_ISSUER`
pinned in compose `environment:`), AppAuth client (`SuiteAuthManager`, client id `tote`,
redirect `com.tote:/oauth2redirect`, **keep the AppCompat theme override on
`RedirectUriReceiverActivity`** — suite apps use `android:Theme.Material` and AppAuth
crashes on the redirect otherwise), `synthetic_smoke.py`. Alembic `0001` for all §4 tables
including the GIN index.
**Amended 2026-08-16 — `POST /auth/refresh` + client `TokenAuthenticator`.** Phase 1 shipped the
siblings' SSO shape but *not* their `/auth/refresh`, and the client stored a refresh token it had
no way to redeem. Access tokens live 30 minutes, so the app wedged half an hour after every
sign-in — every call 401ing, the app still believing it was signed in (a stored token string is
not a valid one), and the UI saying "check you're on the tailnet". Found in production. See
ARCHITECTURE.md "Session renewal" for the three client rules that each have a test behind them.
*Exit: "Sign in with Dragonfly" works against the live identity server; schema migrates
**and downgrades** cleanly on a fresh DB.*

**Phase 2 — Totes, locations, categories, manual items.** ✅ **DONE 2026-08-16** (#2 server, #3
client, #4 flaky-test fix). Full CRUD, the movement service
and ledger, unpack/repack bulk ops, search endpoint. Android: tote list, tote detail, manual
add/edit, search screen, Room cache + offline search.
*Exit: a tote can be created, filled by hand, searched, unpacked and repacked, with a
correct ledger — the app is genuinely useful before any AI or NFC exists.*

**Phase 3 — NFC + index card.** ✅ **DONE 2026-08-16.** Tag read (reader mode + `NDEF_DISCOVERED` launch) and write,
UID storage and mismatch warning, `/t/<code>` server page, `GET /totes/{id}/card` PDF with QR.
*Exit: tap a written tag on a locked phone → Tote opens that tote; a printed card's QR
resolves to the same place; a dead tag is recoverable via the card.*

**Phase 4 — Photo capture → AI draft.** ✅ **DONE 2026-08-16** (server #7, capture queue #8,
review stack #9). The pipeline was verified end-to-end against the real model on prod: a photo
through `/items/scan` returned `name='Red storage box'`, `confidence='low'`, `is_draft=true`, in
35.5 s. The client adds the Room capture queue drained by WorkManager, ≤1600px downscale, and the
one-draft-at-a-time review stack. Three things worth knowing before touching it, all in
ARCHITECTURE.md: a scan **timeout** is its own queue state (`uncertain`) because the endpoint is
synchronous and a retry would file the item twice; `ScanTimeoutInterceptor` raises the read
timeout for that path alone (at OkHttp's 10 s default *every* scan fails); and review keeps its
**position** across a decision rather than re-fetching.
**Amended 2026-08-16 after the first real on-device run** — two bugs, both found by using it:
**(a) one photograph became four drafts.** `/items/scan` commits before it answers, so an upload
whose connection is cut after the commit is indistinguishable from one that never arrived, and
`releaseStranded` re-sent it. Every attempt now carries the queue row id as **`capture_id`** and
the server returns the draft it already made (`items.capture_id`, unique per user, migration
0003). **(b) the review screen never re-fetched** — `refresh()` ran only in `init` and the
ViewModel outlives a tab switch, so a draft that landed while the app was open was invisible until
a restart, while the polling badge counted it: the tab said 4 over a screen saying "Nothing
waiting". `syncPreservingPosition()` now runs on every resume and re-reads **by id**, so position
and half-typed edits survive. Both in ARCHITECTURE.md.

**Still not done on device** — see the open items table.
⚠️ **Prerequisite done 2026-08-16**: the Room database
now uses real migrations with **no destructive fallback**, guarded by a JVM test that walks the
committed schema exports (and an on-device test for column-level validation). Adding the capture
queue to that database is now safe — a version bump without a migration fails CI instead of
deleting queued photos. **Bumping `version` requires a migration and a committed schema export;
the procedure is in `ToteMigrations`' KDoc.** Batch capture queue (camera + gallery, downscale,
Room queue, WorkManager upload), `POST /items/scan` pipeline: rembg/Pillow cleanup, Gemma
identify + category + condition, draft persisted. Review stack with full editing. Vision and
cleanup mocked in CI; live LM Studio smoke locally. **Bake the U2-Net weights into the
image** so the container works offline and cold-starts fast.
*Exit: photo → reviewed item in a tote, end-to-end on device against real LM Studio.*

**Phase 5 — Sizing + apparel.** ✅ **DONE 2026-08-16** (#11 server, #12 client).
`app/sizing/` (the ladder, 90 table-driven tests), `app/apparel/` copied from Crate and trimmed,
the narrow label pass wired into the scan pipeline with **its own `except`**, and apparel on
`ItemOut`/`ItemPatch`. Verified live against the real model, **including the negative controls**:
a drawn `4T / GIRLS` tag parsed to toddler/4.0, and both a no-size label and a non-label returned
no size. Three things to know before touching it, all in ARCHITECTURE.md: a **bare number does not
parse** without a department (youth 8 vs women's 8 — designed, not a gap); `size_system`/
`size_ordinal` are **never client-settable**, they are re-derived from `size_raw` on every write;
and `Item.apparel` is `lazy="selectin"` because a lazy load under asyncio raises MissingGreenlet
from inside Pydantic's `from_attributes`, on paths that never mention apparel.
The client adds the clothing section to review, the tag's words on search and tote-detail rows,
`GET /items?size=` matching by ordinal, and Room **v3** caching `size_raw` for offline search.
Two client rules worth knowing: an **untouched** clothing section is omitted from the confirm body
(the server reads omitted as "leave what the label read"), and only `size_raw` is cached — never
the derived index, which the server owns.
*Exit: a garment photographed with its tag lands with `size_raw` verbatim and a correct
ordinal; an unreadable tag lands with a null ordinal and no invented size; the ladder's
table-driven tests cover every system including 6X.*

**Phase 6 — People, fits, and lending.** ✅ **DONE 2026-08-16** (#13 server, #17 client). `/people` CRUD + size history, `GET /people/{id}/fits`, `/people/{id}/on-loan`,
`/people/{id}/outgrown`, `GET /overdue`, `POST /overdue/nudge`, `loaned_to` on every item, and
ntfy pinned as compose literals. Three things to know: **`fits` distinguishes "nothing matches"
from "cannot say"** (`answered:false` + a `reason`) and a client MUST NOT render them the same;
`loaned_to` comes from the newest `loaned` movement in ONE query per page, because the item row
never knows who has it; and **due today is not overdue**, or the nudge becomes noise.
The client adds a fifth tab (People — the last one a bottom bar can carry), the person screen
with sizes/fits/on-loan, lending from the bin an item is in, the outgrown → tote flow, and the
overdue card on Home. Three client rules, all in ARCHITECTURE.md: **`answered:false` and an empty
`items` render as different screens** with separate Roborazzi baselines, because one means "read a
tag" and the other means "stop looking"; narrowing by garment type **re-asks the server** rather
than filtering locally, since the ladder has one writer; and the lend date stays **optional**,
because an invented due date manufactures a nudge nobody agreed to and that is how a notification
channel gets muted.
*Exit: "what fits Emma" returns items with their totes; a lent item nags on time.*

**Phase 7 — Backups + deploy hardening.** ✅ **DONE 2026-08-16** (#10). `deploy/backup.ps1`
(DB dump + photos tar + MANIFEST; verifies before claiming success, prunes only after the new
set verifies) and the scan stage in `scripts/synthetic_smoke.py`. Two inherited bugs were found
by *running* it: a set could pass at write time and fail `-Verify` a minute later, and a corrupt
archive produced a PowerShell stack trace instead of a diagnosis (5.1 turns native stderr into a
terminating error even with `2>$null`). **Crate has both.** Restore rehearsed end to end.
Host-side wiring (`Backup-ToteArchive.ps1`, the scheduled task, the `tote` row in
`Backup-DragonflyDatabases.ps1`, the `Test-SuiteInvariants.ps1` freshness check) lives in
`C:\Scripts` and is recorded in OPERATIONS.md, not here.
*Exit: a backup set exists, has been **verified by decrypting and restoring from what
actually landed**, and a stale or 0-byte set fails the weekly check.*

**Phase 8 — Polish + release.** Empty states, error states, Roborazzi baselines (light +
dark), deploy live (runner + redeploy + Tailscale Serve `:8448`), tagged APK via
`release.yml`, README, ARCHITECTURE.md complete, Dragonfly `ServiceRegistry` row now that
the URL is real.
*Exit: v1 feature-complete, deployed tailnet-only, CI/CD green end to end.*

---

## 9. Testing & CI

- **Backend:** table-driven unit tests for the size ladder, the movement service's derived
  state, and the vision parsers; router tests against a test DB; **LM Studio and ntfy always
  mocked in CI**. pytest + ruff, same configs as Crate.
- **Local test recipe** (suite-standard, do not rediscover): throwaway DB inside the tote-db
  container, `DATABASE_URL` host **`127.0.0.1` never `localhost`** (Docker publishes IPv4
  only; the ::1-first fallback turned a 5 s suite into 6+ minutes), **`DB_NULLPOOL=true`**
  (pooled asyncpg connections bind a dead event loop otherwise — "Task attached to a
  different loop").
- **Real-image tests, not fake PNG bytes.** Copy Crate's `tests/fixtures/images.py`
  (Pillow-built, no binaries in git). Crate's entire photo pipeline was green for weeks with
  every test monkeypatching `clean_photo`, so no pixel had ever been decoded — and it was
  hiding a real defect that blackened every dark garment.
- **Android:** VM + repository/queue/sync unit tests; Roborazzi screenshot baselines (dark +
  light), `workflow_dispatch` job like the siblings. **Verify baselines before re-recording** —
  recording rewrites every file, and most of the diff will be anti-aliasing jitter that
  buries the two that changed meaning.
- **CI:** every PR — lint, format-check, unit tests both sides, assembleDebug; block merge on
  red. **CD:** self-hosted `tote` runner redeploys green `main`; manual `workflow_dispatch`
  with `ref` as rollback. Android CI jobs are **not** required PR checks by default — verify
  each by name before merging `android/**`.
- No secrets in repo. Non-secret required config pinned in compose `environment:` (§2).

---

## 10. Conventions & guardrails

- **Update `ARCHITECTURE.md` in the same PR** when a change alters architecture — a module's
  responsibility, a layer boundary, an external contract, or the data model. Suite-wide rule,
  corrected explicitly once already; never a follow-up commit.
- Match the siblings' code style, package naming (`com.tote`), commit style, PR scoping. One
  phase per PR-sized chunk; restate assumptions before coding.
- **AI guardrails:** prompts live server-side in one auditable module (`app/services/ai/`);
  vision output is schema-validated with salvage and degrades to a low-confidence draft
  rather than erroring; **nothing AI-generated enters the catalog without explicit user
  approval.** Tote has no unattended-write exception.
- **Never infer a size.** Restated because it is the rule most likely to be "improved" away:
  under-reading is the designed trade.
- Derived state (`current_tote_id`, `status`, item counts) has exactly one writer. Clients
  display, never compute.
- Store canonical units server-side, convert at the edges (measurements are inches here
  because tape measures and garment tags are; that is the edge, and it is the raw reading).
- When something is deferred or human-gated, write it down here or in ARCHITECTURE.md — not
  in chat history.

**Explicitly not v1:** multi-item detection from one bulk photo (the biggest future labor
saver, deliberately deferred — it is new prompt ground and the one-photo-per-item path is
proven); barcode/UPC lookup for boxed goods; value/insurance reporting and export;
multi-user households with per-person permissions; a web client. **Post-v1 cross-app:** a
`/cross-app/summary` endpoint feeding the Dragonfly weekly digest, and an **outgrown → Crate
handoff** (an item marked outgrown offers "list it for sale", pushing name/photos/apparel
attributes to Crate over `CROSS_APP_SECRET`) — which is the payoff for copying Crate's
apparel vocabulary instead of inventing a parallel one.

---

## 11. Host facts this app depends on (verified 2026-08-15)

- Ports **8008/5439** free per `netstat` **and** `docker ps`. Docker Desktop's proxy makes
  `netstat` alone unreliable — check both.
- Tailscale Serve **`:8448`** free per live `tailscale serve status`. **Serve ports are a
  shared namespace and `tailscale serve --https=<port>` silently overwrites an existing
  mapping** — this broke Hawksnest's Home Assistant path when Remnant grabbed `:8443`. Re-run
  `tailscale serve status` immediately before claiming it, and trust it over any table.
- `CDRaab01/Tote` does not exist yet; create it with `main` as the default branch.
- **The deploy dir will be the code checkout.** Every sibling's `<APP>_DIR` is
  `C:\Code\<Repo>`, and Crate's deploy `git reset --hard`s it on every green main. So
  `C:\Code\Tote` is prod once `TOTE_DIR` is set — **never leave work on a branch there**;
  build in an isolated worktree.
- **Backups fail silently on this host.** The nightly DB job produced nothing for two weeks
  while `Get-ScheduledTask` reported `Ready`. Assert artifact **freshness**, never task
  configuration. And `powershell.exe` here is **5.1**: `Set-Content -AsByteStream` is
  PowerShell 7+ only and will throw, leaving an empty timestamped directory that looks like
  a backup until you open it. Pipe `pg_dump` through `cmd` straight to disk.

---

## Human-gated items (the build never blocks on these)

1. ~~**Accent confirmation**~~ — **DONE 2026-08-15**: Slate (charcoal + safety yellow).
2. ~~**Repo creation**~~ — **DONE 2026-08-15**: `CDRaab01/Tote`, public. Still worth confirming
   Actions settings require approval for first-time fork contributors (suite invariant 7).
3. ~~**Runner + config**~~ — **DONE 2026-08-15**: runner online (label `tote`, host `DRAGONFLY`),
   `TOTE_DIR` variable set, and all four `KEYSTORE_*` secrets set on the **Actions** tab.
   **The trap worth remembering:** they were first entered on the repo's **Agent** tab. Agent
   (Copilot coding-agent) and Actions are separate stores — `${{ secrets.* }}` cannot read the
   Agent ones, the Actions API reports `total_count: 0`, and there is no way to copy them across
   (GitHub never exposes a secret's value, and no `copilot` environment is created). The failure
   mode is quiet: `release.yml` falls back to the committed debug key and ships an APK that
   cannot install over an existing one. The `Assert signing identity` step is what catches it.
   `TOTE_SERVER_URL` remains optional (overrides the URL compiled into the APK).
4. ~~**Tailscale Serve `:8448`**~~ — **DONE 2026-08-15**, live at
   `https://dragonfly.tail2ce561.ts.net:8448`. Verified additive by diffing the entire
   `tailscale serve status` output before and after: only the `:8448` row appeared and all
   eleven pre-existing mappings survived. Do the same on any future Serve change — `--https=<port>`
   silently overwrites, and Remnant grabbing `:8443` took Home Assistant down for about an hour.
5. **dragonfly-id + Dragonfly sibling PRs** merged (OIDC client, smoke client, AppRegistry).
6. **ntfy topic** `tote-alerts`.
7. **On-device pass** — camera flow, AppAuth redirect, and specifically **NFC read/write on
   real tags** (which cannot be emulated in CI or on an emulator; buy NTAG215s and test the
   too-small-payload and read-only paths for real).
8. **The physical bootstrap** — printing the first index cards and writing the first tags,
   which is the moment the design either works in an attic or doesn't.
