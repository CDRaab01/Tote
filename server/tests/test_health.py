"""Phase 0: the two unauthenticated endpoints the deploy and the app both depend on."""


async def test_health_is_ok(client):
    r = await client.get("/health")
    assert r.status_code == 200
    assert r.json() == {"status": "ok"}


async def test_version_reports_the_suite_shape(client):
    """/version must carry the four keys the hub and the deploy smoke read.

    The hub cannot get a versionCode from the GitHub API, so this endpoint plus the released
    version.json are the whole update story — a missing key here breaks the hub silently.
    """
    r = await client.get("/version")
    assert r.status_code == 200
    body = r.json()
    assert set(body) == {"name", "version", "commit", "built_at"}
    assert body["name"] == "Tote API"


async def test_security_headers_are_present(client):
    r = await client.get("/health")
    assert r.headers["X-Content-Type-Options"] == "nosniff"
    assert r.headers["X-Frame-Options"] == "DENY"
    assert r.headers["Referrer-Policy"] == "no-referrer"


async def test_hsts_is_off_by_default(client):
    """Tote is tailnet-only and terminates TLS at Tailscale Serve, so it must not claim HSTS
    unless explicitly enabled — an HSTS header from a service reached over plain HTTP on the
    loopback publish would be a lie the browser caches."""
    r = await client.get("/health")
    assert "Strict-Transport-Security" not in r.headers


async def test_version_matches_the_installed_package(client):
    """The number `/version` reports must be the one the build stamped into the artefact.

    This is a regression test with a date on it: `pyproject` was bumped to 1.0.0 for the v1
    release while a literal in `main.py` stayed at 0.1.0, and a freshly built production image
    reported the wrong version with total confidence. The hub reads this endpoint and the deploy
    smoke reads this endpoint; two strings for one fact will always eventually disagree.
    """
    from importlib.metadata import version as installed_version

    r = await client.get("/version")
    assert r.json()["version"] == installed_version("tote-server")
