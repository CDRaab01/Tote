"""The capture pipeline: real pixels in, a draft out, and nothing touching the catalog.

Two things are tested here that a mocked pipeline cannot see:

* **Real images are decoded.** Every photo in these tests is built by Pillow and actually opened
  by the cleanup code. Crate's pipeline was green for weeks with fake PNG bytes and a
  monkeypatched `clean_photo`, which is precisely how it shipped a defect that turned every dark
  garment black.
* **The AI boundary is honest.** LM Studio is faked at the HTTP layer, not at the function, so
  the transport error mapping and the forgiving parser are both exercised.
"""

import io
import json
import uuid

import httpx
import pytest
from PIL import Image

from tests.fixtures.images import (
    dark_photo_bytes,
    mean_of_center,
    photo_bytes,
    pure_black_fraction,
)


def _lm_studio(reply: str) -> httpx.AsyncClient:
    """A fake LM Studio that answers with `reply` as the model's content."""

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={"choices": [{"message": {"content": reply}}]},
        )

    return httpx.AsyncClient(transport=httpx.MockTransport(handler))


def _lm_studio_failing(exc: Exception) -> httpx.AsyncClient:
    def handler(request: httpx.Request) -> httpx.Response:
        raise exc

    return httpx.AsyncClient(transport=httpx.MockTransport(handler))


# ── Cleanup, on real pixels ──────────────────────────────────────────────────


def test_cleanup_returns_an_image_that_actually_decodes():
    from app.services.cleanup import clean_photo

    out = clean_photo(photo_bytes())
    img = Image.open(io.BytesIO(out))
    img.load()
    assert img.size[0] > 0


def clean_photo_bytes(data: bytes) -> bytes:
    from app.services.cleanup import clean_photo

    return clean_photo(data)


def test_cleanup_keeps_the_background_transparent_not_white():
    """The photo is a catalog thumbnail read on a phone that is usually in dark mode.

    White was inherited from Crate, where an eBay listing wants exactly that. Here every
    photograph became a glaring white card in a charcoal list. Asserted on the alpha channel
    rather than on a corner pixel's colour, because a white corner and a transparent one look
    identical the moment anything flattens the image — which is how this would silently come
    back.

    Skipped without rembg: the Pillow-only degradation has no cutout to be transparent, and
    asserting otherwise would fail for the wrong reason. CI installs rembg.
    """
    from PIL import Image

    from app.services.cleanup import clean_photo

    out = clean_photo(photo_bytes())
    img = Image.open(io.BytesIO(out))
    if img.mode != "RGBA":
        pytest.skip("rembg unavailable — the degraded path keeps the original, background and all")

    alpha = img.getchannel("A")
    assert alpha.getextrema()[0] == 0, "nothing is transparent — the cutout was composited again"
    # And the subject is still opaque: an image that is transparent everywhere is not a photo.
    assert alpha.getextrema()[1] == 255


def test_cleanup_does_not_black_out_a_dark_subject():
    """The regression that shipped in Crate.

    Applying levels AFTER compositing onto white makes the subject the darkest content in the
    frame, so the 1% shadow clip lands on the subject and maps it toward black — every colourway,
    including a light heather grey, came out pure black.

    Asserted on decoded pixels, and against *black* rather than against an arbitrary fraction of
    the original brightness. A gentle shift is normal autocontrast behaviour and says nothing; a
    subject at or near (0,0,0) is the actual defect.

    Verified on BOTH code paths before being committed — with rembg installed (the compositing
    branch, where the original defect lived: 0.000% pure black) and without it (the Pillow-only
    degradation: 0.032%). CI installs rembg, so the compositing branch is the one that runs there.
    """
    cleaned = clean_photo_bytes(dark_photo_bytes())

    black = pure_black_fraction(cleaned)
    assert black < 0.01, (
        f"{black:.2%} of the cleaned image is pure black — the levels pass is running after "
        "compositing again"
    )
    # Brightness, measured RELATIVE to the subject the camera saw rather than against an
    # absolute floor.
    #
    # It used to be `overall_mean(cleaned) > 50`, which passed for the wrong reason: cleanup
    # composited onto white, so a field of 255s dominated the average and the assertion was
    # mostly measuring the background. Now that the cutout keeps its alpha there is no
    # background to measure, and the honest question is whether the SUBJECT survived. Measured
    # on this fixture the subject goes from a centre of ~(72,75,82) to ~(38,38,45) — autocontrast
    # clipping shadows on an image whose subject is deliberately the darkest thing in frame.
    # The defect this guards produced ~(0,0,0).
    before = sum(mean_of_center(dark_photo_bytes())) / 3
    after = sum(mean_of_center(cleaned)) / 3
    assert after > before * 0.25, (
        f"the subject collapsed from {before:.0f} to {after:.0f} — the levels pass is running "
        "after the background removal again"
    )


def test_levels_preserve_the_subjects_colour():
    """The other half of Crate's cleanup fix, and the half this fixture can actually demonstrate.

    `autocontrast(preserve_tone=True)` derives ONE mapping from luminance. Per-channel stretching
    — what "auto levels" usually means — pushes each channel against its own endpoints, which
    wrecks the hue of a saturated subject. Measured on this fixture, a red subject at
    (183, 82, 85) becomes (173, 56, 57) with preserve_tone and (81, 58, 59) without: the red
    channel collapses and the object turns muddy grey.

    That matters here because colour is one of the few things a photograph tells you about a
    boxed item that the catalog cannot, and a listed colour that disagrees with the object is
    worse than no colour at all.

    Note on scope: the *ordering* half of the fix (levels before compositing) is guarded by the
    pure-black assertion above rather than by a comparison. A synthetic three-tone fixture does
    not reproduce the compositing interaction that caused the original blackening — an attempt to
    simulate it here produced no measurable difference, so it was removed rather than kept as a
    test that looks meaningful and is not.
    """
    import io

    from PIL import Image, ImageOps

    from tests.fixtures.images import photo_bytes as _photo

    src = _photo(subject=(170, 40, 40), background=(190, 190, 195))
    img = Image.open(io.BytesIO(src)).convert("RGB")

    def centre(image) -> tuple[int, int, int]:
        buf = io.BytesIO()
        image.save(buf, format="PNG")
        return mean_of_center(buf.getvalue())

    kept = centre(ImageOps.autocontrast(img, cutoff=1, preserve_tone=True))
    lost = centre(ImageOps.autocontrast(img, cutoff=1, preserve_tone=False))

    # The subject stays recognisably red with preserve_tone, and stops being red without it.
    assert kept[0] > kept[1] * 2, f"preserve_tone lost the hue: {kept}"
    assert lost[0] < kept[0] * 0.7, (
        f"per-channel stretching no longer damages hue ({lost} vs {kept}) — if Pillow's "
        "behaviour changed, re-check whether preserve_tone is still doing anything"
    )


def test_cleanup_raises_on_garbage_and_the_pipeline_catches_it():
    """Cleanup must never block a draft.

    It DOES raise on genuine garbage — asserted specifically rather than as a blind `Exception`,
    so a future change that starts raising something else here is visible. The pipeline's job is
    to catch it, which `test_a_scan_survives_a_photo_that_cannot_be_cleaned` covers; this half
    just pins the contract between the two.
    """
    from PIL import UnidentifiedImageError

    from app.services.cleanup import clean_photo

    with pytest.raises(UnidentifiedImageError):
        clean_photo(b"not an image at all")


# ── The prompt parser ────────────────────────────────────────────────────────


def test_parser_survives_code_fences_and_preamble():
    from app.services.ai.identify_prompts import parse_identify

    draft = parse_identify(
        'Sure! Here is the JSON:\n```json\n{"name": "Cordless drill", "confidence": "high"}\n```'
    )
    assert draft is not None
    assert draft.name == "Cordless drill"
    assert draft.confidence == "high"


def test_parser_returns_none_for_unusable_output():
    from app.services.ai.identify_prompts import parse_identify

    assert parse_identify("I'm not sure what this is.") is None
    assert parse_identify("") is None


def test_parser_drops_an_invented_category():
    """The model may only answer with one of the user's own categories. A near-miss is dropped,
    not fuzzy-matched: filing into the wrong category is the quiet error that makes a catalog
    untrustworthy."""
    from app.services.ai.identify_prompts import parse_identify

    cats = ["Tools", "Christmas / seasonal decor"]
    assert parse_identify('{"category": "Home & Garden"}', cats).category is None
    assert parse_identify('{"category": "tools"}', cats).category == "Tools"


def test_parser_drops_an_unknown_condition():
    from app.services.ai.identify_prompts import parse_identify

    assert parse_identify('{"condition": "mint"}').condition is None
    assert parse_identify('{"condition": "Like New"}').condition == "like_new"


def test_parser_refuses_an_implausible_quantity():
    """A wrong count silently changes what the catalog claims you own."""
    from app.services.ai.identify_prompts import parse_identify

    assert parse_identify('{"quantity": 0}').quantity is None
    assert parse_identify('{"quantity": 100000}').quantity is None
    assert parse_identify('{"quantity": "four"}').quantity is None
    assert parse_identify('{"quantity": true}').quantity is None
    assert parse_identify('{"quantity": 4}').quantity == 4


async def test_the_vision_request_sets_no_max_tokens():
    """gemma-4 is a reasoning model: hidden reasoning tokens share the `max_tokens` budget and it
    emits NO content until it is done. An answer-sized cap silently returns "", which every parser
    reads as an unreadable photo. This asserts the request body never grows one.

    Async, using the suite's event loop. An earlier version called `asyncio.run()` from a sync
    test, which replaced the session-scoped loop and made every async test after it fail with
    "no current event loop" — a single test taking out twenty-six others.
    """
    from app.services.ai.identify_prompts import build_identify_messages
    from app.services.ai.vision import _chat_vision

    captured: dict = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured.update(json.loads(request.content))
        return httpx.Response(200, json={"choices": [{"message": {"content": "{}"}}]})

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    await _chat_vision(build_identify_messages([]), client)

    assert "max_tokens" not in captured, (
        "a max_tokens cap was added to the vision request — with a reasoning model this "
        "silently returns an empty answer"
    )
    # And the low-temperature setting that keeps it faithful rather than inventive.
    assert captured["temperature"] == 0.2


# ── Scan end to end ──────────────────────────────────────────────────────────


async def _scan(
    client, photo: bytes, tote_id: str | None = None, capture_id: str | None = None, **kw
):
    files = {"photos": ("shot.jpg", photo, "image/jpeg")}
    data = {}
    if tote_id:
        data["tote_id"] = tote_id
    if capture_id:
        data["capture_id"] = capture_id
    return await client.post("/items/scan", files=files, data=data, **kw)


async def test_a_scan_produces_a_draft_not_a_catalog_item(auth_client, monkeypatch):
    """The house rule, asserted: nothing model-generated enters the catalog without approval."""
    import app.services.scan_pipeline as pipeline

    async def fake_identify(urls, categories=None, client=None):
        from app.services.ai.identify_prompts import IdentifyDraft

        return IdentifyDraft(name="Cordless drill", confidence="high")

    monkeypatch.setattr(pipeline, "identify_item", fake_identify)

    r = await _scan(auth_client, photo_bytes())
    assert r.status_code == 201, r.text
    assert r.json()["is_draft"] is True
    assert r.json()["name"] == "Cordless drill"

    # Invisible to search and to the item list until confirmed.
    assert (await auth_client.get("/search", params={"q": "cordless"})).json() == []
    assert (await auth_client.get("/items")).json() == []
    assert len((await auth_client.get("/drafts")).json()) == 1


async def test_confirming_a_draft_files_it_and_writes_the_ledger_row(auth_client, monkeypatch):
    import app.services.scan_pipeline as pipeline

    async def fake_identify(urls, categories=None, client=None):
        from app.services.ai.identify_prompts import IdentifyDraft

        return IdentifyDraft(name="Cordless drill", confidence="high")

    monkeypatch.setattr(pipeline, "identify_item", fake_identify)

    tote = (await auth_client.post("/totes", json={"code": "G01"})).json()
    draft = (await _scan(auth_client, photo_bytes())).json()

    r = await auth_client.post(
        f"/drafts/{draft['id']}/confirm",
        json={"tote_id": tote["id"], "name": "Cordless drill, 18V", "quantity": 1},
    )
    assert r.status_code == 200
    assert r.json()["current_tote_id"] == tote["id"]
    # The human's edit wins over the model's answer.
    assert r.json()["name"] == "Cordless drill, 18V"

    moves = (await auth_client.get(f"/items/{draft['id']}/movements")).json()
    assert [m["reason"] for m in moves] == ["initial"]
    assert (await auth_client.get("/search", params={"q": "cordless"})).json() != []


async def test_an_unreachable_model_still_produces_a_draft_with_the_photo(auth_client, monkeypatch):
    """The photo is the one thing that cannot be re-created — the item is back in a bin by now.

    A model outage must not lose it, and must be recorded as an outage rather than as an
    unreadable photo: those need completely different responses from a human.
    """
    from fastapi import HTTPException

    import app.services.scan_pipeline as pipeline

    async def unreachable(urls, categories=None, client=None):
        raise HTTPException(503, "Couldn't reach LM Studio. Is it running?")

    monkeypatch.setattr(pipeline, "identify_item", unreachable)

    r = await _scan(auth_client, photo_bytes())
    assert r.status_code == 201
    body = r.json()
    assert body["is_draft"] is True
    assert body["scan_error"] == "identify_unavailable"
    assert body["photo_count"] == 1


async def test_discarding_a_draft_removes_its_photos(auth_client, monkeypatch):
    """Otherwise dismissing a bad scan leaves JPEGs on the volume with no row pointing at them —
    invisible until the disk fills, and unattributable afterwards."""
    from pathlib import Path

    import app.services.scan_pipeline as pipeline
    from app.config import settings

    async def fake_identify(urls, categories=None, client=None):
        from app.services.ai.identify_prompts import IdentifyDraft

        return IdentifyDraft(name="Thing")

    monkeypatch.setattr(pipeline, "identify_item", fake_identify)

    draft = (await _scan(auth_client, photo_bytes())).json()
    d = Path(settings.photos_dir) / draft["id"]
    assert d.exists() and any(d.iterdir())

    assert (await auth_client.delete(f"/drafts/{draft['id']}")).status_code == 204
    assert not d.exists()


async def test_a_scan_rejects_an_unsupported_content_type(auth_client):
    r = await auth_client.post("/items/scan", files={"photos": ("x.txt", b"hello", "text/plain")})
    assert r.status_code == 422


async def test_a_scan_rejects_more_than_eight_photos(auth_client):
    files = [("photos", (f"{i}.jpg", photo_bytes(), "image/jpeg")) for i in range(9)]
    r = await auth_client.post("/items/scan", files=files)
    assert r.status_code == 422


async def test_a_scan_into_someone_elses_tote_is_404(auth_client, client):
    from app.database import AsyncSessionLocal
    from app.models.user import User, UserSettings
    from app.security import create_access_token
    from app.services.household_service import create_household

    tote = (await auth_client.post("/totes", json={"code": "Z09"})).json()
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

    r = await client.post(
        "/items/scan",
        files={"photos": ("a.jpg", photo_bytes(), "image/jpeg")},
        data={"tote_id": tote["id"]},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert r.status_code == 404


async def test_another_users_draft_cannot_be_confirmed(auth_client, client, monkeypatch):
    import app.services.scan_pipeline as pipeline

    async def fake_identify(urls, categories=None, client=None):
        from app.services.ai.identify_prompts import IdentifyDraft

        return IdentifyDraft(name="Thing")

    monkeypatch.setattr(pipeline, "identify_item", fake_identify)

    from app.database import AsyncSessionLocal
    from app.models.user import User, UserSettings
    from app.security import create_access_token
    from app.services.household_service import create_household

    draft = (await _scan(auth_client, photo_bytes())).json()
    tote = (await auth_client.post("/totes", json={"code": "Y08"})).json()

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

    r = await client.post(
        f"/drafts/{draft['id']}/confirm",
        json={"tote_id": tote["id"], "name": "Stolen"},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert r.status_code == 404


# ── The vision transport, faked at the HTTP layer ───────────────────────────


async def test_transport_errors_map_to_distinct_statuses():
    """503 unreachable, 504 timeout, 502 rejected. Collapsing them would make a dead container
    and a bad model pin indistinguishable in the logs."""
    from fastapi import HTTPException

    from app.services.ai.vision import identify_item

    for exc, expected in (
        (httpx.ConnectError("refused"), 503),
        (httpx.ReadTimeout("slow"), 504),
    ):
        with pytest.raises(HTTPException) as caught:
            await identify_item([], client=_lm_studio_failing(exc))
        assert caught.value.status_code == expected


async def test_a_garbled_model_reply_degrades_to_a_low_confidence_draft():
    """Content failure, not transport failure — an unreadable photo still produces a row a human
    can fill in."""
    from app.services.ai.vision import identify_item

    draft = await identify_item([], client=_lm_studio("total nonsense, no json here"))
    assert draft.confidence == "low"
    assert draft.name is None


async def test_a_scan_survives_a_photo_that_cannot_be_cleaned(auth_client, monkeypatch):
    """The other half of the cleanup contract.

    A corrupt upload declaring itself image/jpeg passes content-type validation and then fails to
    decode. The scan must still produce a draft with the original saved: the photo bytes are the
    part that cannot be recreated, and refusing the whole scan because cleanup choked would throw
    them away for a cosmetic step.
    """
    import app.services.scan_pipeline as pipeline

    async def fake_identify(urls, categories=None, client=None):
        from app.services.ai.identify_prompts import IdentifyDraft

        return IdentifyDraft(name="Unreadable but saved")

    monkeypatch.setattr(pipeline, "identify_item", fake_identify)

    r = await auth_client.post(
        "/items/scan", files={"photos": ("broken.jpg", b"not an image", "image/jpeg")}
    )
    assert r.status_code == 201, r.text
    assert r.json()["photo_count"] == 1

    # The original is served even though there is no cleaned copy — a failed cleanup shows the
    # photo, not a broken frame.
    photo = await auth_client.get(f"/items/{r.json()['id']}/photos/0")
    assert photo.status_code == 200


async def test_confirming_without_an_apparel_block_keeps_what_the_label_read(
    auth_client, monkeypatch
):
    """Omitted means "leave it", not "clear it".

    Every other field on DraftConfirm is overwritten outright, because every other field is on
    the review screen and a blank one is a decision. Apparel is a section a user may never open,
    and clearing a correctly-read 4T because nobody scrolled to it would silently destroy the
    only reading of a tag now sealed in a bin.
    """
    import app.services.scan_pipeline as pipeline
    from app.services.ai.identify_prompts import IdentifyDraft
    from app.services.ai.label_prompts import LabelDraft

    async def fake_identify(urls, categories=None, client=None):
        return IdentifyDraft(name="Winter coat", confidence="high")

    async def fake_label(urls, client=None):
        return LabelDraft(size="4T", department="girls")

    monkeypatch.setattr(pipeline, "identify_item", fake_identify)
    monkeypatch.setattr(pipeline, "read_label", fake_label)

    tote = (await auth_client.post("/totes", json={"code": "S20"})).json()
    draft = (await _scan(auth_client, photo_bytes())).json()
    assert draft["apparel"]["size_raw"] == "4T"

    confirmed = (
        await auth_client.post(
            f"/drafts/{draft['id']}/confirm",
            json={"tote_id": tote["id"], "name": "Winter coat"},
        )
    ).json()
    assert confirmed["apparel"]["size_raw"] == "4T"
    assert confirmed["apparel"]["size_ordinal"] == 4.0


async def test_confirming_with_an_apparel_block_rederives_the_index(auth_client, monkeypatch):
    """A human correcting the size on the review screen must not leave a stale ordinal behind."""
    import app.services.scan_pipeline as pipeline
    from app.services.ai.identify_prompts import IdentifyDraft

    async def fake_identify(urls, categories=None, client=None):
        return IdentifyDraft(name="Snowsuit", confidence="high")

    monkeypatch.setattr(pipeline, "identify_item", fake_identify)

    tote = (await auth_client.post("/totes", json={"code": "S21"})).json()
    draft = (await _scan(auth_client, photo_bytes())).json()

    confirmed = (
        await auth_client.post(
            f"/drafts/{draft['id']}/confirm",
            json={
                "tote_id": tote["id"],
                "name": "Snowsuit",
                "apparel": {"size_raw": "6X"},
            },
        )
    ).json()
    assert confirmed["apparel"]["size_raw"] == "6X"
    assert confirmed["apparel"]["size_system"] == "youth_numeric"
    assert confirmed["apparel"]["size_ordinal"] == 6.5


# ── Replay safety ────────────────────────────────────────────────────────────
#
# The endpoint commits before it answers and runs for tens of seconds, so a client that loses
# the connection cannot tell a lost request from a lost response — and the capture queue
# re-sends stranded rows. In production on 2026-08-16 one photograph became FOUR drafts that
# way. Duplicates are the worst possible failure for a catalog: two drafts of one cap read
# exactly like two real caps, and the whole product is answering "what do we already own".


async def test_replaying_a_capture_returns_the_same_draft(auth_client, monkeypatch):
    import app.services.scan_pipeline as pipeline
    from app.services.ai.identify_prompts import IdentifyDraft

    calls = {"n": 0}

    async def fake_identify(urls, categories=None, client=None):
        calls["n"] += 1
        return IdentifyDraft(name="Plaid baseball cap", confidence="high")

    monkeypatch.setattr(pipeline, "identify_item", fake_identify)

    capture = str(uuid.uuid4())
    first = await _scan(auth_client, photo_bytes(), capture_id=capture)
    second = await _scan(auth_client, photo_bytes(), capture_id=capture)

    assert first.status_code == 201
    assert second.status_code == 201
    assert first.json()["id"] == second.json()["id"]
    assert len((await auth_client.get("/drafts")).json()) == 1
    # The replay must not re-run the model either: that is 35 s of GPU per lost response.
    assert calls["n"] == 1


async def test_the_capture_lookup_survives_its_own_retries(auth_client, db, monkeypatch):
    """The re-read loop rolls back between attempts, and a rollback mid-helper must not leave the
    session unusable — that would turn a recoverable race into a 500 on a path whose entire job
    is recovering from one."""
    import app.routers.scan as scan_router

    capture = uuid.uuid4()
    household = auth_client.household_id

    # Nothing filed under this key: the loop runs to exhaustion, rolling back between reads.
    missing = await scan_router._draft_for_capture(
        db, household, capture, attempts=scan_router._RACE_REREAD_ATTEMPTS
    )
    assert missing is None

    # And the session still works afterwards.
    assert await scan_router._draft_for_capture(db, household, None) is None


async def test_a_capture_race_re_reads_instead_of_409ing(auth_client, monkeypatch):
    """The bug this exists for: the loser of a race is told by the unique constraint that a row
    exists, then reads and does not find it, and a single empty read used to become a 409.

    A 409 here is not cosmetic — the phone records it as a FAILED capture, so a photograph the
    server already holds needs a human to clear it by hand. One occurred in production during
    the 2026-08-23 queue drain.
    """
    import app.routers.scan as scan_router
    import app.services.scan_pipeline as pipeline
    from app.services.ai.identify_prompts import IdentifyDraft

    async def fake_identify(urls, categories=None, client=None):
        return IdentifyDraft(name="Plaid baseball cap", confidence="high")

    monkeypatch.setattr(pipeline, "identify_item", fake_identify)

    capture = str(uuid.uuid4())
    first = await _scan(auth_client, photo_bytes(), capture_id=capture)
    assert first.status_code == 201

    # Force the *first* read of every lookup to miss, exactly as the losing racer's did. The
    # row is really there, so a retrying lookup finds it on the second read and a
    # single-read one returns None and 409s.
    real = scan_router._draft_for_capture
    state = {"first": True}

    async def flaky(db, household_id, capture_id, *, attempts=1):
        if state["first"]:
            state["first"] = False
            return None
        return await real(db, household_id, capture_id, attempts=attempts)

    monkeypatch.setattr(scan_router, "_draft_for_capture", flaky)

    second = await _scan(auth_client, photo_bytes(), capture_id=capture)

    # Recovered: the same draft handed back, not a 409, and no second object in the catalog.
    assert second.status_code == 201, second.text
    assert second.json()["id"] == first.json()["id"]
    assert len((await auth_client.get("/drafts")).json()) == 1


async def test_a_different_capture_is_a_different_draft(auth_client, monkeypatch):
    """The negative control. A key that deduplicated too eagerly would silently drop the second
    of two genuinely different objects photographed back to back — which is the normal way this
    app is used, standing over an open bin."""
    import app.services.scan_pipeline as pipeline
    from app.services.ai.identify_prompts import IdentifyDraft

    async def fake_identify(urls, categories=None, client=None):
        return IdentifyDraft(name="Thing", confidence="high")

    monkeypatch.setattr(pipeline, "identify_item", fake_identify)

    await _scan(auth_client, photo_bytes(), capture_id=str(uuid.uuid4()))
    await _scan(auth_client, photo_bytes(), capture_id=str(uuid.uuid4()))

    assert len((await auth_client.get("/drafts")).json()) == 2


async def test_a_scan_without_a_capture_id_still_works(auth_client, monkeypatch):
    """An older APK on someone's phone must keep working — the update train is not instant."""
    import app.services.scan_pipeline as pipeline
    from app.services.ai.identify_prompts import IdentifyDraft

    async def fake_identify(urls, categories=None, client=None):
        return IdentifyDraft(name="Thing", confidence="high")

    monkeypatch.setattr(pipeline, "identify_item", fake_identify)

    assert (await _scan(auth_client, photo_bytes())).status_code == 201
    assert (await _scan(auth_client, photo_bytes())).status_code == 201
    # Two, not one: with no key there is nothing to deduplicate ON, and guessing from the
    # pixels would merge two identical-looking ornament boxes into one.
    assert len((await auth_client.get("/drafts")).json()) == 2


async def test_replaying_after_the_draft_was_confirmed_does_not_file_it_twice(
    auth_client, monkeypatch
):
    """The nastiest ordering: the response was lost, the human reviewed and filed the draft, and
    only then does the queue retry. Re-creating here would put a second copy of an already
    catalogued object into the review stack, and the human has no way to tell it is a ghost."""
    import app.services.scan_pipeline as pipeline
    from app.services.ai.identify_prompts import IdentifyDraft

    async def fake_identify(urls, categories=None, client=None):
        return IdentifyDraft(name="Cap", confidence="high")

    monkeypatch.setattr(pipeline, "identify_item", fake_identify)

    tote = (await auth_client.post("/totes", json={"code": "R9"})).json()
    capture = str(uuid.uuid4())
    draft = (await _scan(auth_client, photo_bytes(), capture_id=capture)).json()
    await auth_client.post(
        f"/drafts/{draft['id']}/confirm", json={"tote_id": tote["id"], "name": "Cap"}
    )

    replay = await _scan(auth_client, photo_bytes(), capture_id=capture)

    assert replay.status_code == 201
    assert replay.json()["id"] == draft["id"]
    assert (await auth_client.get("/drafts")).json() == []
    assert len((await auth_client.get("/items")).json()) == 1


async def test_a_catalogued_item_reports_its_photo_count(auth_client, monkeypatch):
    """The client needs to know whether to draw a thumbnail BEFORE it asks for one.

    Without this it would have to fire a photo request per row and render whatever a 404 looks
    like — on the screen someone opens standing in front of an open bin, over the attic's Wi-Fi.
    """
    import app.services.scan_pipeline as pipeline
    from app.services.ai.identify_prompts import IdentifyDraft

    async def fake_identify(urls, categories=None, client=None):
        return IdentifyDraft(name="Toddler bed comforter", confidence="high")

    monkeypatch.setattr(pipeline, "identify_item", fake_identify)

    tote = (await auth_client.post("/totes", json={"code": "D1"})).json()
    draft = (await _scan(auth_client, photo_bytes())).json()
    filed = (
        await auth_client.post(
            f"/drafts/{draft['id']}/confirm",
            json={"tote_id": tote["id"], "name": "Toddler bed comforter"},
        )
    ).json()
    assert filed["photo_count"] == 1

    # And on the list paths, which is where it is actually used.
    contents = (await auth_client.get(f"/totes/{tote['id']}")).json()
    assert contents["items"][0]["photo_count"] == 1

    # An item added by hand has none, and that is the common case for anything not photographed.
    by_hand = (
        await auth_client.post("/items", json={"name": "Second comforter", "tote_id": tote["id"]})
    ).json()
    assert by_hand["photo_count"] == 0


async def test_deleting_an_item_deletes_its_photographs_from_disk(auth_client, monkeypatch):
    """The rows cascade; the files did not.

    Deleting an item used to leave its photographs on the volume forever — invisible to the app,
    listed by nothing, and archived faithfully by every nightly backup. The photos ARE the
    artefact in this app; the rows are paths pointing at them.
    """
    import app.services.scan_pipeline as pipeline
    from app.services import photo_store
    from app.services.ai.identify_prompts import IdentifyDraft

    async def fake_identify(urls, categories=None, client=None):
        return IdentifyDraft(name="Comforter", confidence="high")

    monkeypatch.setattr(pipeline, "identify_item", fake_identify)

    tote = (await auth_client.post("/totes", json={"code": "D2"})).json()
    draft = (await _scan(auth_client, photo_bytes())).json()
    filed = (
        await auth_client.post(
            f"/drafts/{draft['id']}/confirm",
            json={"tote_id": tote["id"], "name": "Comforter"},
        )
    ).json()

    directory = photo_store.item_dir(uuid.UUID(filed["id"]))
    assert any(directory.iterdir())

    assert (await auth_client.delete(f"/items/{filed['id']}")).status_code == 204
    assert not directory.exists() or not any(directory.iterdir())


# ── Sized photos ─────────────────────────────────────────────────────────────
#
# `?w=` exists because every 52dp list thumbnail on the client downloaded the FULL cleaned PNG —
# megabytes of RGBA over the attic's Wi-Fi to paint a square smaller than a stamp, which is why
# lists scrolled ahead of their pictures.


def _fake_identify(monkeypatch, name="Sized thing"):
    import app.services.scan_pipeline as pipeline

    async def fake(urls, categories=None, client=None):
        from app.services.ai.identify_prompts import IdentifyDraft

        return IdentifyDraft(name=name)

    monkeypatch.setattr(pipeline, "identify_item", fake)


async def test_a_sized_photo_is_webp_no_larger_than_asked(auth_client, monkeypatch):
    _fake_identify(monkeypatch)
    draft = (await _scan(auth_client, photo_bytes(size=(1400, 1000)))).json()

    r = await auth_client.get(f"/items/{draft['id']}/photos/0", params={"w": 192})
    assert r.status_code == 200
    assert r.headers["content-type"] == "image/webp"
    assert max(Image.open(io.BytesIO(r.content)).size) == 192

    # `thumbnail` never upscales, and the cleaned copy is a crop — so the ceiling for a large
    # `w` is the SOURCE's long edge, measured off the actual file rather than the upload.
    full = await auth_client.get(f"/items/{draft['id']}/photos/0")
    source_edge = max(Image.open(io.BytesIO(full.content)).size)
    r = await auth_client.get(f"/items/{draft['id']}/photos/0", params={"w": 1024})
    assert max(Image.open(io.BytesIO(r.content)).size) == min(1024, source_edge)


async def test_a_sized_cleaned_photo_keeps_its_transparency(auth_client, monkeypatch):
    """The derivative is WebP, never JPEG, precisely for this assertion: a JPEG thumb would
    flatten the cutout's alpha to black — the defect class the cleanup tests guard, arriving
    through a new door. Skipped without rembg, same as the cleanup alpha test."""
    _fake_identify(monkeypatch)
    draft = (await _scan(auth_client, photo_bytes())).json()

    full = await auth_client.get(f"/items/{draft['id']}/photos/0")
    if Image.open(io.BytesIO(full.content)).mode != "RGBA":
        pytest.skip("rembg unavailable — the degraded cleanup path has no alpha to preserve")

    r = await auth_client.get(f"/items/{draft['id']}/photos/0", params={"w": 192})
    thumb = Image.open(io.BytesIO(r.content))
    assert thumb.mode == "RGBA"
    # < 16 rather than == 0: LANCZOS is allowed to feather the cutout's edge, not to fill it.
    assert thumb.getchannel("A").getextrema()[0] < 16, (
        "nothing is transparent in the thumb — the derivative flattened the cutout"
    )


async def test_an_unknown_width_is_rejected(auth_client, monkeypatch):
    """The width names a file the server will create; an open integer would let one client mint
    an unbounded family of derivatives per photo. Fixed set, 422 outside it."""
    _fake_identify(monkeypatch)
    draft = (await _scan(auth_client, photo_bytes())).json()
    for w in (200, 0, -1):
        r = await auth_client.get(f"/items/{draft['id']}/photos/0", params={"w": w})
        assert r.status_code == 422, w


async def test_photo_responses_carry_cache_control(auth_client, monkeypatch):
    """`private` because these are photos of the inside of a house behind auth; a day because
    the client's disk cache is the offline story for the attic and revalidation was the reason
    freshly catalogued photos re-downloaded on every scroll."""
    _fake_identify(monkeypatch)
    draft = (await _scan(auth_client, photo_bytes())).json()
    for params in ({}, {"w": 192}):
        r = await auth_client.get(f"/items/{draft['id']}/photos/0", params=params)
        assert r.headers["cache-control"] == "private, max-age=86400", params


async def test_a_sized_request_survives_an_undecodable_original(auth_client, monkeypatch):
    """The scan deliberately keeps an upload cleanup could not read; asking for a thumb of it
    must degrade to serving it whole, not turn a working photo endpoint into a 500."""
    _fake_identify(monkeypatch, name="Unreadable but saved")
    r = await auth_client.post(
        "/items/scan", files={"photos": ("broken.jpg", b"not an image", "image/jpeg")}
    )
    item_id = r.json()["id"]

    sized = await auth_client.get(f"/items/{item_id}/photos/0", params={"w": 192})
    assert sized.status_code == 200
    assert sized.content == b"not an image"


async def test_a_thumb_from_the_original_is_superseded_by_the_cleaned_copy(
    auth_client, raw_sql, monkeypatch
):
    """Proves the mechanism, not a live bug.

    Today cleanup runs synchronously inside the scan request, so a cleaned copy cannot arrive
    after a client has ever seen the photo. The `_c`/`_o` source suffix on the derivative's
    filename is the contract for the day cleanup goes async — and meanwhile it is what keeps
    `cleaned=false` book-cover thumbs from colliding with cleaned-derived ones.
    """
    import app.services.scan_pipeline as pipeline
    from app.services import photo_store

    _fake_identify(monkeypatch)

    def broken_clean(data, target):
        raise OSError("cleanup broken for this test")

    monkeypatch.setattr(pipeline, "_clean_to_disk", broken_clean)
    draft = (await _scan(auth_client, photo_bytes())).json()

    first = await auth_client.get(f"/items/{draft['id']}/photos/0", params={"w": 192})
    r0, _, b0 = mean_of_center(first.content)
    assert b0 > r0, "the original-derived thumb should show the fixture's blue subject"

    # The cleaned copy lands later, as an async cleanup would: solid red, unmistakable.
    cleaned = photo_store.cleaned_path_for(uuid.UUID(draft["id"]), 0)
    Image.new("RGBA", (300, 300), (200, 30, 30, 255)).save(cleaned, format="PNG")
    await raw_sql(
        "UPDATE item_photos SET cleaned_path = :p WHERE item_id = :i",
        p=cleaned,
        i=uuid.UUID(draft["id"]),
    )

    second = await auth_client.get(f"/items/{draft['id']}/photos/0", params={"w": 192})
    r1, _, b1 = mean_of_center(second.content)
    assert r1 > b1, "the cleaned copy must supersede the original-derived thumb"


async def test_the_thumbnail_is_cached_on_disk(auth_client, monkeypatch):
    from app.services import photo_store

    _fake_identify(monkeypatch)
    draft = (await _scan(auth_client, photo_bytes())).json()

    first = await auth_client.get(f"/items/{draft['id']}/photos/0", params={"w": 192})
    thumbs = list(photo_store.item_dir(uuid.UUID(draft["id"])).glob("thumb_0_192_*.webp"))
    assert len(thumbs) == 1, "the derivative is generated once and kept beside its source"
    second = await auth_client.get(f"/items/{draft['id']}/photos/0", params={"w": 192})
    assert second.content == first.content


# ── Orientation ──────────────────────────────────────────────────────────────
#
# The client used to destroy orientation on the way up: BitmapFactory ignores the EXIF
# Orientation tag and Bitmap.compress writes none, so a portrait photo arrived as sideways pixels
# with nothing left to say so. The capture path is fixed at the source now; `rotation` is how the
# photographs already on the volume get put right — by a person, because there is nothing left in
# the file to infer it from.


async def _rotatable(auth_client, monkeypatch):
    """A draft whose photo is unmistakably wider than it is tall."""
    _fake_identify(monkeypatch)
    return (await _scan(auth_client, photo_bytes(size=(800, 400)))).json()


async def test_a_rotation_turns_the_photograph_and_is_recorded(auth_client, monkeypatch):
    draft = await _rotatable(auth_client, monkeypatch)

    flat = await auth_client.get(f"/items/{draft['id']}/photos/0", params={"w": 192})
    wide, tall = Image.open(io.BytesIO(flat.content)).size
    assert wide > tall, "the fixture should start out landscape"

    turned = await auth_client.get(f"/items/{draft['id']}/photos/0", params={"w": 192, "r": 90})
    w2, h2 = Image.open(io.BytesIO(turned.content)).size
    assert h2 > w2, "a quarter turn must swap the edges"
    # Bounded by the width it was asked for, not by the edge the sensor happened to record:
    # rotation is applied BEFORE the resize.
    assert max(w2, h2) == 192


async def test_a_recorded_rotation_applies_without_being_asked(auth_client, monkeypatch):
    """A curl, a test, or a client that predates rotation still gets it the right way up."""
    draft = await _rotatable(auth_client, monkeypatch)
    await auth_client.post(
        "/photos/bulk-rotate",
        json={"photos": [{"item_id": draft["id"], "order": 0, "rotation": 90}]},
    )

    bare = await auth_client.get(f"/items/{draft['id']}/photos/0", params={"w": 192})
    w, h = Image.open(io.BytesIO(bare.content)).size
    assert h > w

    # And the FULL-size response honours it too — no `w` at all. Nothing in the client asks for
    # that path today, which is exactly why it is worth pinning: an unused branch that quietly
    # serves sideways pixels is how this comes back.
    full = await auth_client.get(f"/items/{draft['id']}/photos/0")
    fw, fh = Image.open(io.BytesIO(full.content)).size
    assert fh > fw


async def test_a_turned_photograph_gets_its_own_cached_derivative(auth_client, monkeypatch):
    """Rotation is part of the derivative's name.

    Without it a corrected photo would serve the old file under the same URL, and the fix would
    look like it had not worked — on the client for a day, because of `Cache-Control`.
    """
    from app.services import photo_store

    draft = await _rotatable(auth_client, monkeypatch)
    await auth_client.get(f"/items/{draft['id']}/photos/0", params={"w": 192})
    await auth_client.get(f"/items/{draft['id']}/photos/0", params={"w": 192, "r": 180})

    directory = photo_store.item_dir(uuid.UUID(draft["id"]))
    assert len(list(directory.glob("thumb_0_192_*.webp"))) == 2
    assert list(directory.glob("thumb_0_192_*_r180.webp")), "the turned one is keyed by its angle"


async def test_an_unknown_rotation_is_rejected(auth_client, monkeypatch):
    draft = await _rotatable(auth_client, monkeypatch)
    for r in (45, 1, -90, 360):
        got = await auth_client.get(f"/items/{draft['id']}/photos/0", params={"w": 192, "r": r})
        assert got.status_code == 422, r


async def test_the_item_carries_its_first_photograph_s_rotation(auth_client, monkeypatch):
    """So a list row can build a cache-correct thumbnail URL without a request per row."""
    _fake_identify(monkeypatch)
    tote = (await auth_client.post("/totes", json={"code": "R01"})).json()
    draft = (await _scan(auth_client, photo_bytes(size=(800, 400)))).json()
    filed = (
        await auth_client.post(
            f"/drafts/{draft['id']}/confirm", json={"tote_id": tote["id"], "name": "Sideways"}
        )
    ).json()
    assert filed["photo_rotation"] == 0

    await auth_client.post(
        "/photos/bulk-rotate",
        json={"photos": [{"item_id": filed["id"], "order": 0, "rotation": 270}]},
    )
    listed = (await auth_client.get("/items", params={"tote_id": tote["id"]})).json()
    assert listed[0]["photo_rotation"] == 270


async def test_the_orientation_list_names_the_object_and_skips_drafts(auth_client, monkeypatch):
    _fake_identify(monkeypatch)
    tote = (await auth_client.post("/totes", json={"code": "R02"})).json()
    draft = (await _scan(auth_client, photo_bytes())).json()
    await auth_client.post(
        f"/drafts/{draft['id']}/confirm", json={"tote_id": tote["id"], "name": "Filed thing"}
    )
    # A second scan left unconfirmed: still a draft, and about to be looked at on review anyway.
    await _scan(auth_client, photo_bytes())

    listed = (await auth_client.get("/photos/orientation")).json()
    assert [p["item_name"] for p in listed] == ["Filed thing"]
    assert listed[0]["tote_code"] == "R02"
    assert listed[0]["rotation"] == 0


async def test_bulk_rotate_is_all_or_nothing(auth_client, monkeypatch):
    """A partial save leaves a grid the person has just finished correcting half-corrected,
    with nothing on screen saying which half."""
    draft = await _rotatable(auth_client, monkeypatch)

    got = await auth_client.post(
        "/photos/bulk-rotate",
        json={
            "photos": [
                {"item_id": draft["id"], "order": 0, "rotation": 90},
                {"item_id": draft["id"], "order": 7, "rotation": 90},
            ]
        },
    )
    assert got.status_code == 404

    listed = (await auth_client.get("/photos/orientation")).json()
    assert all(p["rotation"] == 0 for p in listed), "nothing may have been written"


async def test_another_household_cannot_rotate_your_photographs(
    auth_client, other_client, monkeypatch
):
    draft = await _rotatable(auth_client, monkeypatch)
    got = await other_client.post(
        "/photos/bulk-rotate",
        json={"photos": [{"item_id": draft["id"], "order": 0, "rotation": 90}]},
    )
    assert got.status_code == 404
    assert (await other_client.get("/photos/orientation")).json() == []
