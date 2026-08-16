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

## Backups

`deploy/backup.ps1` writes a verified, self-contained set:

```
<BackupDir>\tote-YYYYMMDD-HHmmss\
  db.dump        pg_dump custom format (-Fc)
  photos.tar.gz  the /data/photos volume
  MANIFEST.json  sizes, counts, deployed commit
```

```powershell
powershell C:\Code\Tote\deploy\backup.ps1 -BackupDir \\Diskstation\Media2\Backups\Tote
powershell C:\Code\Tote\deploy\backup.ps1 -Verify      # check the newest set, write nothing
```

**Run it from the deployment clone, not a worktree.** Compose derives its project name from the
directory, so `C:\Code\Tote-something\deploy\backup.ps1` finds no running stack and refuses —
correctly, but it looks like a broken script the first time.

Two volumes, and they are not equally replaceable. The catalog rows are a list of paths; the
**photographs are the artifact**. An item was in someone's hands in a garage and is now sealed in
a taped bin in an attic — losing the photos volume means the only way to learn what a bin holds
is to carry fourteen of them down and open every one.

The script **verifies before it claims success** and **prunes only after** a good new set exists,
so a failing run can never delete the last good backup. What it checks:

| Check | Catches |
|---|---|
| `db.dump` ≥ 1 KB | a dump that failed and left a stub |
| `photos.tar.gz` ≥ 100 B *(unless the DB genuinely has zero photo rows)* | a truncated archive, without false-alarming on an empty catalog |
| `tar tzf` inside a container | a corrupt archive — and it tells that apart from Docker being down, which is a WARN, not a FAIL |
| photo files ≥ `item_photos` rows | missing originals (the pipeline writes files *before* committing rows, so files ≥ rows always holds) |

On the Dragonfly host this is not scheduled directly. `C:\Scripts\Backup-ToteArchive.ps1` wraps
it and owns scheduling, gpg encryption, NAS delivery, retention and logging — the same division
of labour as Crate. `MANIFEST.json` is promoted to the NAS **unencrypted on purpose** so the
freshness check in `Test-SuiteInvariants.ps1` can read a set's age without the passphrase.

### Restoring from a backup

Rehearsed 2026-08-16 against a real set, not just written down:

```powershell
# 1. Decrypt, if the set came from the NAS (the local sets written by backup.ps1 are plaintext).
& "C:\Program Files\Git\usr\bin\gpg.exe" --batch --yes --passphrase-file `
  C:\Users\Sonic\.dragonfly-suite\db-backup.gpg.pass -o db.dump -d db.dump.gpg

# 2. Restore the database into a THROWAWAY first. Never straight over prod: a restore you have
#    not looked at is a claim, and this is the step where you find out the dump was empty.
docker exec tote-db-1 psql -U tote -d postgres -c "CREATE DATABASE tote_restore_test;"
docker cp db.dump tote-db-1:/tmp/restore.dump
docker exec tote-db-1 pg_restore -U tote -d tote_restore_test --no-owner /tmp/restore.dump
docker exec tote-db-1 psql -U tote -d tote_restore_test -c "\dt"   # expect 12 tables

# 3. Photos back onto the volume.
docker run --rm --volumes-from tote-server-1 -v "${PWD}:/backup" `
  alpine tar xzf /backup/photos.tar.gz -C /data/photos
```

A restore is only finished when the rows and the files agree: `select count(*) from item_photos`
against `find /data/photos -type f | wc -l`. Rows without files is a catalog of paths pointing at
nothing, which reads as a working app right up until someone opens an item.

## Not built yet

Nothing in this file. Phase 8 (polish/release) is the remaining deploy-adjacent work.
