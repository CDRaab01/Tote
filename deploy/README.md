# Deploying Tote

Tote runs on the Dragonfly host (Windows + Docker Desktop) behind a self-hosted GitHub Actions
runner. The runner long-polls GitHub **outbound**, so nothing inbound is opened on the home
network. Tote itself is **tailnet-only** — no Cloudflare tunnel, no public hostname.

## How a deploy happens

1. Push to `main`.
2. `ci.yml` runs on GitHub-hosted runners.
3. If CI is green **and** the trigger was a push to `main`, `deploy.yml` fires on the self-hosted
   runner (`workflow_run`). The weekly scheduled CI run is deliberately excluded — a bit-rot
   check must never ship.
4. `deploy.yml` syncs the deployment clone to the exact commit, then runs `deploy/redeploy.ps1`,
   then verifies the running commit matches.

**Rollback** is `workflow_dispatch` on Deploy with `ref` set to a previous SHA.

## Host configuration

| What | Value |
|---|---|
| Runner labels | `self-hosted`, `tote` |
| Actions variable `TOTE_DIR` | `C:\Code\Tote` |
| Actions variable `TOTE_SERVER_URL` (optional) | overrides the URL compiled into the APK |
| Secrets | `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` (release signing) |
| API port | `127.0.0.1:8008` |
| Postgres port | `127.0.0.1:5439` |
| Tailscale Serve | `:8448` → `8008` |

**Set secrets on the Actions tab, not the Agent tab.** They are different stores; secrets entered
under Agent are invisible to `${{ secrets.* }}` in a workflow, and the symptom is a release that
silently falls back to the committed debug key — which the `Assert signing identity` step is
there to catch.

## One-time host setup

Run **Deploy** manually with `bootstrap_host` checked. It is additive only: it creates the
deployment clone and a minimal `server/.env` **only if they are absent**, and never overwrites an
existing `.env`. (A blind read-then-write once truncated Spotter's `.env` to zero bytes and
crash-looped production — hence the guard.)

Then publish it on the tailnet:

```powershell
tailscale serve status                      # ALWAYS check first
tailscale serve --bg --https=8448 8008
```

Serve ports are a shared namespace and `--https=<port>` **silently overwrites** an existing
mapping. This is not hypothetical: Remnant grabbing `:8443` clobbered Hawksnest's Home Assistant
mapping and took it down for about an hour. Check `tailscale serve status` and trust it over any
documented table.

## Environment variables

Required **non-secret** config lives in `docker-compose.yml`'s `environment:` block as literals.
Secrets live in `server/.env`. Compose does **not** re-read a changed `env_file` when it recreates
a container, so an env_file-only value silently disappears on the next redeploy — this has caused
production regressions three times across the suite.

Verify inside the container, never by reading `.env`:

```powershell
docker compose -f C:\Code\Tote\docker-compose.yml exec server sh -c "env | grep -E 'SUITE_|NFC_'"
```

## The deploy directory is the dev checkout

`TOTE_DIR` is `C:\Code\Tote` — the same path you'd clone into to work on Tote, which is the
convention across all eight apps. Consequences:

- Every green deploy runs `git reset --hard` there. **Never leave uncommitted work or a feature
  branch in that directory** — build in a worktree instead.
- The clone ends up owned by the runner's service account, so `git` reports "dubious ownership"
  when you use it interactively. `redeploy.ps1` self-heals this with
  `git config --global --add safe.directory`.

## Health and identity

`redeploy.ps1` polls `/health` **and** checks `/version` reports `"Tote API"`. Checking `/health`
alone is not enough: it returns a byte-identical `{"status":"ok"}` in every suite app, so a
neighbour owning the port answers instantly and a broken deploy reports green. Crate hit exactly
this when its first deploy pointed at a port Magpie already held.

## Manual redeploy

```powershell
powershell C:\Code\Tote\deploy\redeploy.ps1
powershell C:\Code\Tote\deploy\redeploy.ps1 -Ref 1a2b3c4   # roll back
```

## Not built yet

Backups. Crate's photos went unbacked-up for weeks and its backup script could not run at all on
this host's PowerShell 5.1 (`Set-Content -AsByteStream` is 7+ only) — it threw on the first step
and left empty timestamped directories that looked like real backup sets. When Tote grows a photo
volume in Phase 4, backups land in Phase 7 with a **restore actually rehearsed**, and the freshness
alarm asserts the newest artifact's age rather than the scheduled task's configuration.
