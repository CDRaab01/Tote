"""The index card, the tag URI, and the one unauthenticated surface.

The security test here is the important one. `/t/{code}` is reachable with no credentials by
anything on the tailnet, and a tag is a physical object on the outside of a box — so the page
must not turn "this bin exists" into "here is what is in it".
"""

import uuid

from app.services.card import tote_uri


async def _tote(c, code="A14", **kw):
    r = await c.post("/totes", json={"code": code, **kw})
    assert r.status_code == 201, r.text
    return r.json()


# ── The public landing page ──────────────────────────────────────────────────


async def test_the_landing_page_needs_no_auth(client):
    """A tap has to land somewhere useful on a phone that is not signed in."""
    r = await client.get("/t/A14")
    assert r.status_code == 200
    assert "A14" in r.text


async def test_the_landing_page_leaks_nothing_about_contents(auth_client, client):
    """The whole security property of this route.

    Anyone who can read the tag already knows the bin exists. What they must not learn is what is
    inside it, or even its label and location — that would be an inventory printed on the outside
    of the box.
    """
    r = await auth_client.post("/locations", json={"name": "Attic"})
    loc = r.json()
    await _tote(auth_client, "A14", label="Christmas decor", location_id=loc["id"])
    await auth_client.post("/items", json={"name": "Very distinctive doohickey", "tote_id": None})

    page = (await client.get("/t/A14")).text
    for secret in ("Christmas decor", "Attic", "doohickey"):
        assert secret not in page, f"the landing page leaked {secret!r}"


async def test_the_landing_page_escapes_the_code(client):
    """The code arrives from a physical tag anyone could have written, and lands in HTML.

    The payload deliberately contains no slash: a slash splits the path so the request never
    reaches this handler at all (it 404s), which would make the test pass while proving nothing
    about escaping.
    """
    r = await client.get("/t/<script>alert(1)")
    assert r.status_code == 200
    assert "<script>alert" not in r.text
    assert "&lt;script&gt;" in r.text


async def test_an_unknown_code_still_renders_rather_than_500ing(client):
    """A tag written for a tote that was later deleted must not produce an error page — the
    person is standing in front of a real bin either way."""
    assert (await client.get("/t/ZZZZ")).status_code == 200


# ── The tag URI ──────────────────────────────────────────────────────────────


def test_the_tag_uri_is_built_from_the_code_not_an_id():
    """A written tag is a physical object no deploy can patch, so what it encodes must be
    something the server can still honour after ids or schemas change. The code is also the thing
    printed on the card and readable by a human."""
    uri = tote_uri("A14", base="https://example.test:8448")
    assert uri == "https://example.test:8448/t/A14"


def test_the_uri_base_is_configurable_not_compiled_in():
    assert tote_uri("A14", base="https://other/") == "https://other/t/A14"


async def test_the_client_can_ask_where_to_point_tags(client):
    r = await client.get("/nfc/base")
    assert r.status_code == 200
    assert r.json()["base"].startswith("http")


# ── The card ─────────────────────────────────────────────────────────────────


async def test_the_card_renders_a_pdf(auth_client):
    t = await _tote(auth_client, "A14", label="Christmas decor")
    r = await auth_client.get(f"/totes/{t['id']}/card")
    assert r.status_code == 200
    assert r.headers["content-type"] == "application/pdf"
    # Magic bytes rather than a length check: a zero-byte or HTML-error body would otherwise
    # sail through, which is exactly how a "working" backup turned out to be empty in Crate.
    assert r.content[:5] == b"%PDF-", r.content[:40]
    assert len(r.content) > 1000


async def test_the_card_encodes_the_same_uri_the_tag_does(auth_client, monkeypatch):
    """If the QR and the NFC tag ever encoded different things, a dead tag would be
    unrecoverable by the card — which is the entire reason the QR exists.

    Verified at the seam rather than by decoding pixels: this captures what actually reaches
    `qrcode.add_data` during a real card render, and compares it to the same `tote_uri` the tag
    writer uses. Decoding the rendered image needs a ~60 MB OpenCV install on every CI run to
    prove the same contract. The pixels WERE decoded by hand once, 2026-08-16, and came back
    byte-identical to `tote_uri` — this test is what keeps it that way.
    """
    import qrcode

    encoded: list[str] = []
    original = qrcode.QRCode.add_data

    def capture(self, data, *a, **kw):
        encoded.append(data)
        return original(self, data, *a, **kw)

    monkeypatch.setattr(qrcode.QRCode, "add_data", capture)

    t = await _tote(auth_client, "B02")
    r = await auth_client.get(f"/totes/{t['id']}/card")
    assert r.status_code == 200
    assert encoded == [tote_uri("B02")], encoded
    assert encoded[0].endswith("/t/B02")


async def test_printing_a_card_records_when(auth_client):
    """So the app can say which bins have never been labelled — the ones that will be a mystery
    in six months."""
    t = await _tote(auth_client, "C03")
    assert (await auth_client.get(f"/totes/{t['id']}")).json()["card_printed_at"] is None
    assert (await auth_client.get(f"/totes/{t['id']}/card")).status_code == 200
    assert (await auth_client.get(f"/totes/{t['id']}")).json()["card_printed_at"] is not None


async def test_another_users_card_is_404(auth_client, client):
    t = await _tote(auth_client, "D04")
    from app.database import AsyncSessionLocal
    from app.models.user import User, UserSettings
    from app.security import create_access_token
    from app.services.household_service import create_household

    async with AsyncSessionLocal() as db:
        other = User(name="O", email=f"o-{uuid.uuid4().hex[:8]}@e.com")
        db.add(other)
        await db.flush()
        # A household of one, exactly as a real first login gives them. Without it the account
        # has no scope at all and every request 500s on `user.household_id` — which would make
        # these isolation tests pass for entirely the wrong reason.
        await create_household(db, other.id)
        db.add(UserSettings(user_id=other.id))
        await db.commit()
        token = create_access_token(str(other.id))

    r = await client.get(f"/totes/{t['id']}/card", headers={"Authorization": f"Bearer {token}"})
    assert r.status_code == 404


# ── Tag registration and mismatch ────────────────────────────────────────────


async def test_recording_a_tag_write_stores_the_hardware_uid(auth_client):
    t = await _tote(auth_client, "E05")
    r = await auth_client.post(f"/totes/{t['id']}/nfc", json={"tag_uid": "04A2B3C4D5E6"})
    assert r.status_code == 200
    assert r.json()["nfc_tag_uid"] == "04A2B3C4D5E6"


async def test_one_physical_tag_cannot_belong_to_two_totes(auth_client):
    """Re-using a tag must be a conflict, not a silent reassignment that leaves two bins
    believing they own the same physical sticker."""
    a = await _tote(auth_client, "E06")
    b = await _tote(auth_client, "E07")
    assert (
        await auth_client.post(f"/totes/{a['id']}/nfc", json={"tag_uid": "DEADBEEF"})
    ).status_code == 200
    r = await auth_client.post(f"/totes/{b['id']}/nfc", json={"tag_uid": "DEADBEEF"})
    assert r.status_code == 409


async def test_resolve_finds_a_tote_case_insensitively(auth_client):
    """Codes are compared case-insensitively everywhere else; a tag written in lower case must
    not fail to resolve the bin whose card says A14."""
    t = await _tote(auth_client, "F08")
    r = await auth_client.get("/totes/resolve/f08")
    assert r.status_code == 200
    assert r.json()["tote_id"] == t["id"]


async def test_resolve_reports_a_mismatched_tag_but_still_resolves(auth_client):
    """Someone in an attic holding a bin needs the answer. A hard refusal because the tag was
    rewritten would strand them; saying so is the useful behaviour."""
    t = await _tote(auth_client, "F09")
    await auth_client.post(f"/totes/{t['id']}/nfc", json={"tag_uid": "1111AAAA"})

    r = await auth_client.get("/totes/resolve/F09", params={"tag_uid": "2222BBBB"})
    assert r.status_code == 200
    assert r.json()["tote_id"] == t["id"]
    assert r.json()["tag_mismatch"] is True


async def test_resolve_does_not_flag_a_mismatch_when_no_uid_was_recorded(auth_client):
    """A tote whose tag was never registered must not report every tap as suspicious."""
    tote = await _tote(auth_client, "F10")
    r = await auth_client.get("/totes/resolve/F10", params={"tag_uid": "3333CCCC"})
    assert r.json()["tote_id"] == tote["id"]
    assert r.json()["tag_mismatch"] is False


async def test_resolve_returns_a_null_id_for_an_unknown_code(auth_client):
    r = await auth_client.get("/totes/resolve/NOPE")
    assert r.status_code == 200
    assert r.json()["tote_id"] is None
