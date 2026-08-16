"""Post-deploy synthetic smoke for Tote (SSO-only, Magpie/Crate pattern).

Tote has no register/login endpoints, so the smoke mints an aud="suite" token from
dragonfly-id's POST /smoke/token using a confidential smoke credential, trades it at Tote's
POST /auth/suite, and exercises an authenticated read. That proves the identity server trusts
us, that Tote's JWKS validation and find-or-create work, and that the database is reachable —
not merely that /health is up.

Auth working is not the same as Tote working. Crate's smoke stopped at /users/me for months,
so a deploy that broke the pipeline the app exists for shipped green. Each phase extends it:

  Phase 2  create a tote, add an item, search for it, move it, delete both
  Phase 3  render a tote card and assert the QR resolves to the tag's URI
  Phase 4  push a generated image through /items/scan and assert the draft processed  [DONE]

The Phase 4 stage draws its own PNG and pushes it through the real pipeline — persist, clean,
identify, draft — then deletes the draft it made. Two things about it are deliberate:

  * It FAILS when no draft is produced, and only WARNS when a draft comes back carrying
    `scan_error = identify_unavailable`. Those are different claims. The first means the
    deploy broke the pipeline; the second means LM Studio is not loaded on the host, which is
    a real problem but not this deploy's problem, and paging `tote-alerts` for it on every
    redeploy is how an alert channel gets muted. The server itself draws exactly this
    distinction (transport failure vs content failure), and the smoke honours it.
  * It CLEANS UP. This runs on every green push to main, against production. A smoke that
    left its drafts behind would quietly fill the review stack with pictures of a test
    gradient, and the first person to notice would be someone reviewing a real bin.

Config (env):
  TOTE_URL                 Tote base URL        (default http://127.0.0.1:8008)
  SMOKE_TOKEN_URL          dragonfly-id smoke endpoint
                           (default https://id.dragonflymedia.org/smoke/token)
  TOTE_SMOKE_CLIENT_ID     smoke client id      (default tote-smoke)
  TOTE_SMOKE_CLIENT_SECRET smoke client secret  (required — from the deployed .env)
  SMOKE_EMAIL              allowlisted subject  (default tote-smoke@dragonflymedia.org;
                           must be in dragonfly-id's SMOKE_SUBJECT_EMAILS)
  SMOKE_SKIP_SCAN          set to 1 to skip the scan stage (it costs ~35 s and needs the
                           vision model; useful when smoking a server-only change by hand)

Exit 0 + "SMOKE_PASS" on success; exit 1 with [FAIL] otherwise. Stdlib only — it runs inside
the server container, where server/tests/ does not exist (the image copies only app/ and
alembic/). That includes the test image: it is drawn with zlib and struct rather than Pillow,
so this script keeps working if the server's dependencies ever change under it.
"""

import binascii
import json
import os
import struct
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zlib

TOTE_URL = os.environ.get("TOTE_URL", "http://127.0.0.1:8008").rstrip("/")
SMOKE_TOKEN_URL = os.environ.get("SMOKE_TOKEN_URL", "https://id.dragonflymedia.org/smoke/token")
CLIENT_ID = os.environ.get("TOTE_SMOKE_CLIENT_ID", "tote-smoke")
CLIENT_SECRET = os.environ.get("TOTE_SMOKE_CLIENT_SECRET", "")
SMOKE_EMAIL = os.environ.get("SMOKE_EMAIL", "tote-smoke@dragonflymedia.org")
TIMEOUT = float(os.environ.get("SMOKE_TIMEOUT", "20"))
SKIP_SCAN = os.environ.get("SMOKE_SKIP_SCAN", "") not in ("", "0", "false", "no")
# /items/scan is synchronous: it persists, cleans and identifies before it answers. A single
# photo measured 35.5 s against the live model, so the 20 s default here would time out on a
# perfectly healthy deploy and report the pipeline broken. Sized like the Android client's
# equivalent override, and for the same reason.
SCAN_TIMEOUT = float(os.environ.get("SMOKE_SCAN_TIMEOUT", "240"))


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


def request(method: str, url: str, *, data=None, headers=None, form=False, timeout=None):
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
        with urllib.request.urlopen(req, timeout=timeout or TIMEOUT) as resp:
            return resp.status, decode_body(resp.read())
    except urllib.error.HTTPError as e:
        return e.code, decode_body(e.read())
    except Exception as e:  # noqa: BLE001 - connection refused, DNS, TLS: a smoke script must
        # report every failure mode as a readable message, never traceback out.
        return 0, str(e)


def post_multipart(url: str, token: str, filename: str, image: bytes, timeout: float):
    """Upload one image as multipart/form-data.

    Hand-rolled because this script is stdlib-only and `requests` is not in the server image.
    The boundary is random per call so a retry can never collide with a body that quotes it.
    """
    boundary = f"----tote-smoke-{uuid.uuid4().hex}"
    body = b"".join(
        [
            f"--{boundary}\r\n".encode(),
            f'Content-Disposition: form-data; name="photos"; filename="{filename}"\r\n'.encode(),
            b"Content-Type: image/png\r\n\r\n",
            image,
            f"\r\n--{boundary}--\r\n".encode(),
        ]
    )
    req = urllib.request.Request(
        url,
        data=body,
        method="POST",
        headers={
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            "Authorization": f"Bearer {token}",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, decode_body(resp.read())
    except urllib.error.HTTPError as e:
        return e.code, decode_body(e.read())
    except Exception as e:  # noqa: BLE001 - same reason as `request`.
        return 0, str(e)


def make_png(width: int = 320, height: int = 320) -> bytes:
    """Draw a real, decodable PNG with the standard library only.

    Not a fake header or a handful of magic bytes: the pipeline this stage exercises decodes
    the image, removes its background and sends it to a vision model, and every one of those
    steps would reject a stub. The suite has been burned by exactly that shortcut before — a
    photo pipeline stayed green for weeks because no test ever put a real pixel through it,
    while it was blackening every dark subject.

    A soft two-tone blob on a light ground, so background removal has an actual subject to find
    rather than a flat field it can legitimately return empty.
    """
    rows = []
    cx, cy, r2 = width // 2, height // 2, (min(width, height) // 3) ** 2
    for y in range(height):
        row = bytearray([0])  # PNG filter byte: none
        for x in range(width):
            if (x - cx) ** 2 + (y - cy) ** 2 < r2:
                row += bytes((200, 60, 55)) if (x + y) % 32 < 16 else bytes((150, 40, 38))
            else:
                row += bytes((242, 242, 240))
        rows.append(bytes(row))
    raw = zlib.compress(b"".join(rows), 6)

    def chunk(tag: bytes, payload: bytes) -> bytes:
        return (
            struct.pack(">I", len(payload))
            + tag
            + payload
            + struct.pack(">I", binascii.crc32(tag + payload) & 0xFFFFFFFF)
        )

    return b"".join(
        [
            b"\x89PNG\r\n\x1a\n",
            chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)),
            chunk(b"IDAT", raw),
            chunk(b"IEND", b""),
        ]
    )


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

    # 5. The capture pipeline — the thing the app is actually for.
    if SKIP_SCAN:
        print("  scan stage skipped (SMOKE_SKIP_SCAN)")
    else:
        smoke_scan(access)

    print("SMOKE_PASS")


def smoke_scan(access: str) -> None:
    """Push a generated image through /items/scan, assert a draft came back, then clean up."""
    auth = {"Authorization": f"Bearer {access}"}
    image = make_png()
    print(f"  scanning a generated {len(image)}-byte PNG (up to {SCAN_TIMEOUT:.0f}s)...")

    status, body = post_multipart(
        f"{TOTE_URL}/items/scan", access, "smoke.png", image, SCAN_TIMEOUT
    )
    if status == 0:
        fail(
            f"/items/scan did not answer: {body}",
            "The endpoint is synchronous and slow. If this is a timeout rather than a "
            "connection error, check the host's LM Studio and raise SMOKE_SCAN_TIMEOUT.",
        )
    if status != 201:
        fail(
            f"/items/scan returned {status}: {body}",
            "This is the pipeline the app exists for. Check `docker compose logs server` and "
            "that the photos volume is mounted and writable.",
        )

    draft_id = body.get("id")
    if not draft_id:
        fail(f"/items/scan returned no draft id: {body}")
    if not body.get("is_draft"):
        # A scan that produced a catalogued item rather than a draft would mean the house AI
        # rule had been broken — something model-generated entered the catalog unreviewed.
        fail(
            f"/items/scan produced a NON-draft item ({draft_id}).",
            "Nothing model-generated may enter the catalog without confirmation. Check "
            "scan_pipeline.py and item_query's draft exclusion.",
        )
    if body.get("photo_count", 0) < 1:
        fail(
            f"draft {draft_id} has no photos attached ({body.get('photo_count')}).",
            "Originals are persisted before anything else runs, so zero photos means the "
            "volume write failed — the one step in the pipeline that cannot be re-derived.",
        )

    # Identification is a separate claim from the pipeline being alive, and the server keeps
    # them apart on purpose. Report, do not fail: an unloaded model is a host problem, and
    # paging on it for every redeploy is how an alert channel gets ignored.
    if body.get("scan_error") == "identify_unavailable":
        print(
            "  [WARN] the pipeline ran but the vision model was unreachable "
            "(scan_error=identify_unavailable). Check `curl :1234/v1/models` on the host."
        )
    else:
        print(
            f"  identified {body.get('name')!r} "
            f"(confidence={body.get('scan_confidence')}, photos={body.get('photo_count')})"
        )

    # Clean up. This runs against production on every green push to main; a smoke that left
    # its drafts behind would fill the review stack with pictures of a test gradient.
    status, delete_body = request("DELETE", f"{TOTE_URL}/drafts/{draft_id}", headers=auth)
    if status != 204:
        fail(
            f"could not discard smoke draft {draft_id} (DELETE returned {status}: {delete_body}).",
            "Delete it by hand from the Review tab — otherwise it sits in the stack looking "
            "like a real capture.",
        )
    print("  discarded the smoke draft")


if __name__ == "__main__":
    main()
