# Tote — architecture

Kept in lockstep with the code: **update this file in the same commit** as any change that alters
a module's responsibility, a layer boundary, an external contract, or the data model. This is a
suite-wide rule; silently-drifting docs have burned two sibling repos already.

The build plan and the reasoning behind the locked decisions live in [CLAUDE.md](CLAUDE.md).
This file describes what exists **now**.

## Current state: Phase 0 (scaffold)

```
Tote/
├─ android/                     Compose client
│  └─ app/src/main/java/com/tote/
│     ├─ ToteApp.kt             @HiltAndroidApp entry point
│     ├─ MainActivity.kt        single activity, edge-to-edge, hosts the Compose tree
│     └─ ui/
│        ├─ HomeScreen.kt       Phase 0 placeholder — proves the theme renders
│        ├─ components/
│        │  └─ ToteBrand.kt     HazardRule — the yellow band
│        └─ theme/ToteTheme.kt  semantic layer over PULSE
├─ server/                      FastAPI backend
│  ├─ app/
│  │  ├─ main.py                app factory, middleware, /health + /version
│  │  ├─ config.py              pydantic-settings; env > .env
│  │  ├─ database.py            async engine, session factory, DeclarativeBase
│  │  └─ limiter.py             slowapi rate limiting
│  ├─ alembic/                  migration scaffold (no versions yet — Phase 1)
│  └─ tests/                    pytest, asyncio_mode=auto
├─ docker-compose.yml           db + server, host ports 8008/5439
└─ .github/workflows/           ci.yml, notify.yml
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

`ToteColors.hazard` is deliberately **not** `slate.base`: `base` is the yellow only in dark mode,
and the hazard mark is the half that must stay yellow in both. It steps down to
`PulseYellowDeep` on light surfaces, where bright yellow is effectively invisible.

`ToteThemeTest` asserts these ratios rather than trusting the comments — including the negative
case that white-on-yellow must *fail*, which is the property the whole design hangs on.

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

## Not yet built

`release.yml` (needs the `KEYSTORE_*` secrets) and `deploy.yml` + `deploy/` (need the `TOTE_DIR`
variable and a `server/.env` on the host). Everything else follows the phase plan in CLAUDE.md.
