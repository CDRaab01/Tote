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
    overall_mean,
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
    # Whole-image brightness, not a centre sample: when rembg is available the pipeline CROPS to
    # the subject, so a before/after centre comparison would measure the reframing rather than
    # the exposure. Measured across both code paths and both fixtures, this lands between 87 and
    # 169; a blackened image would be near zero.
    brightness = overall_mean(cleaned)
    assert brightness > 50, f"the cleaned image came out near-black (mean {brightness})"


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


async def _scan(client, photo: bytes, tote_id: str | None = None, **kw):
    files = {"photos": ("shot.jpg", photo, "image/jpeg")}
    data = {"tote_id": tote_id} if tote_id else {}
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

    tote = (await auth_client.post("/totes", json={"code": "Z09"})).json()
    async with AsyncSessionLocal() as db:
        other = User(name="O", email=f"o-{uuid.uuid4().hex[:8]}@e.com")
        db.add(other)
        await db.flush()
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

    draft = (await _scan(auth_client, photo_bytes())).json()
    tote = (await auth_client.post("/totes", json={"code": "Y08"})).json()

    async with AsyncSessionLocal() as db:
        other = User(name="O", email=f"o-{uuid.uuid4().hex[:8]}@e.com")
        db.add(other)
        await db.flush()
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
