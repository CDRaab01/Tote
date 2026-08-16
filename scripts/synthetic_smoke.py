"""Post-deploy synthetic smoke for Tote (SSO-only, Magpie/Crate pattern).

Tote has no register/login endpoints, so the smoke mints an aud="suite" token from
dragonfly-id's POST /smoke/token using a confidential smoke credential, trades it at Tote's
POST /auth/suite, and exercises an authenticated read. That proves the identity server trusts
us, that Tote's JWKS validation and find-or-create work, and that the database is reachable —
not merely that /health is up.

READ THIS BEFORE THE NEXT PHASE. Auth working is not the same as Tote working. Crate's smoke
stopped at /users/me for months, so a deploy that broke the pipeline the app exists for
shipped green. Right now Phase 1 has nothing past auth to exercise, which is the only reason
this is honest today. Each phase must extend it:

  Phase 2  create a tote, add an item, search for it, move it, delete both
  Phase 3  render a tote card and assert the QR resolves to the tag's URI
  Phase 4  push a generated image through /items/scan and assert the draft processed

Config (env):
  TOTE_URL                 Tote base URL        (default http://127.0.0.1:8008)
  SMOKE_TOKEN_URL          dragonfly-id smoke endpoint
                           (default https://id.dragonflymedia.org/smoke/token)
  TOTE_SMOKE_CLIENT_ID     smoke client id      (default tote-smoke)
  TOTE_SMOKE_CLIENT_SECRET smoke client secret  (required — from the deployed .env)
  SMOKE_EMAIL              allowlisted subject  (default tote-smoke@dragonflymedia.org;
                           must be in dragonfly-id's SMOKE_SUBJECT_EMAILS)

Exit 0 + "SMOKE_PASS" on success; exit 1 with [FAIL] otherwise. Stdlib only — it runs inside
the server container, where server/tests/ does not exist (the image copies only app/ and
alembic/).
"""

import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

TOTE_URL = os.environ.get("TOTE_URL", "http://127.0.0.1:8008").rstrip("/")
SMOKE_TOKEN_URL = os.environ.get(
    "SMOKE_TOKEN_URL", "https://id.dragonflymedia.org/smoke/token"
)
CLIENT_ID = os.environ.get("TOTE_SMOKE_CLIENT_ID", "tote-smoke")
CLIENT_SECRET = os.environ.get("TOTE_SMOKE_CLIENT_SECRET", "")
SMOKE_EMAIL = os.environ.get("SMOKE_EMAIL", "tote-smoke@dragonflymedia.org")
TIMEOUT = float(os.environ.get("SMOKE_TIMEOUT", "20"))


def fail(msg: str, hint: str = "") -> None:
    print(f"[FAIL] {msg}")
    if hint:
        print(f"       {hint}")
    sys.exit(1)


def decode_body(raw: bytes):
    """Decode a response body for logging.

    Lists are kept intact rather than repr-truncated: Crate's version stringified arrays into a
    200-char repr, which made every list endpoint unreadable in a failure log — exactly when you
    need to read it.
    """
    try:
        parsed = json.loads(raw.decode("utf-8"))
    except Exception:  # noqa: BLE001 - any decode failure means "log the raw bytes instead"
        return raw[:200].decode("utf-8", "replace")
    if isinstance(parsed, list):
        return {"_list": parsed}
    return parsed


def request(method: str, url: str, *, data=None, headers=None, form=False):
    body = None
    hdrs = dict(headers or {})
    if data is not None:
        if form:
            body = urllib.parse.urlencode(data).encode()
            hdrs["Content-Type"] = "application/x-www-form-urlencoded"
        else:
            body = json.dumps(data).encode()
            hdrs["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=body, headers=hdrs, method=method)
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            return resp.status, decode_body(resp.read())
    except urllib.error.HTTPError as e:
        return e.code, decode_body(e.read())
    except Exception as e:  # noqa: BLE001 - connection refused, DNS, TLS: a smoke script must
        # report every failure mode as a readable message, never traceback out.
        return 0, str(e)


def main() -> None:
    print(f"Tote smoke -> {TOTE_URL}")

    if not CLIENT_SECRET:
        fail(
            "TOTE_SMOKE_CLIENT_SECRET is not set.",
            "It must match an entry in dragonfly-id's SMOKE_CLIENTS and live in Tote's "
            "server/.env.",
        )

    # 1. The app is up, and it is actually Tote. /health is byte-identical across all eight
    #    suite apps, so it cannot tell Tote apart from a neighbour that owns the port.
    status, body = request("GET", f"{TOTE_URL}/health")
    if status != 200:
        fail(f"/health returned {status}: {body}", "Is the container running?")
    status, body = request("GET", f"{TOTE_URL}/version")
    if status != 200:
        fail(f"/version returned {status}: {body}")
    if body.get("name") != "Tote API":
        fail(
            f"/version reports {body.get('name')!r}, not 'Tote API'.",
            "Another app owns this port — check docker-compose.yml against `docker ps`.",
        )
    print(f"  version: {body.get('version')} (commit {body.get('commit')})")

    # 2. Mint a suite token. dragonfly-id refuses any subject outside SMOKE_SUBJECT_EMAILS, so
    #    a leaked smoke credential cannot mint a token for a real account.
    status, body = request(
        "POST",
        SMOKE_TOKEN_URL,
        data={"client_id": CLIENT_ID, "client_secret": CLIENT_SECRET, "email": SMOKE_EMAIL},
        form=True,
    )
    if status != 200:
        fail(
            f"minting a suite token returned {status}: {body}",
            f"Check {CLIENT_ID} is in dragonfly-id's SMOKE_CLIENTS with this secret, and that "
            f"{SMOKE_EMAIL} is in SMOKE_SUBJECT_EMAILS.",
        )
    suite_token = body.get("access_token") or body.get("token")
    if not suite_token:
        fail(f"smoke token response carried no token: {body}")
    print("  minted a suite token")

    # 3. Trade it for a Tote session. This is the whole front door of an SSO-only app.
    status, body = request("POST", f"{TOTE_URL}/auth/suite", data={"suite_token": suite_token})
    if status == 404:
        fail(
            "/auth/suite is disabled (404).",
            "SUITE_JWKS_URL/SUITE_ISSUER are missing from the container. They are pinned in "
            "docker-compose.yml's `environment:` precisely because compose does not re-read a "
            "changed env_file on recreate. Verify with: docker compose exec server sh -c "
            "'env | grep SUITE_'",
        )
    if status != 200:
        fail(f"/auth/suite returned {status}: {body}")
    access = body.get("access_token")
    if not access:
        fail(f"/auth/suite carried no access_token: {body}")
    print("  exchanged it for a Tote session")

    # 4. Authenticated read — proves the token verifies AND the database is reachable.
    status, body = request(
        "GET", f"{TOTE_URL}/users/me", headers={"Authorization": f"Bearer {access}"}
    )
    if status != 200:
        fail(f"/users/me returned {status}: {body}")
    if body.get("email") != SMOKE_EMAIL:
        fail(f"/users/me returned {body.get('email')!r}, expected {SMOKE_EMAIL!r}")
    print(f"  authenticated as {body.get('email')}")

    print("SMOKE_PASS")


if __name__ == "__main__":
    main()
