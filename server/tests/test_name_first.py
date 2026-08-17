"""Name-first capture: when the person says what it is, the model is not asked.

The point is not only speed. `identify_item` is the omnibus call and its answer *gates*
`looks_like_clothing`, so a wrong guess does not merely cost a correction in review — it can
silently suppress the size read, which is the one vision output measured to work well. Supplying
the name and the category makes that gate read the person's own vocabulary instead of a guess
about it.

So these tests assert three separable things, and the second is the one that would rot quietly:
identify is not called, the label pass still is, and the description is only asked for when it
was asked for.
"""

import pytest

from app.services.ai.describe_prompts import parse_describe
from tests.fixtures.images import photo_bytes


async def _scan(client, photo: bytes, **data):
    files = {"photos": ("shot.jpg", photo, "image/jpeg")}
    return await client.post("/items/scan", files=files, data=data)


@pytest.fixture
def no_identify(monkeypatch):
    """Make identify explode. Any test that trips it was not name-first after all."""
    import app.services.scan_pipeline as pipeline

    async def boom(*a, **kw):
        raise AssertionError("identify_item must not be called when the item was named by hand")

    monkeypatch.setattr(pipeline, "identify_item", boom)
    return pipeline


async def test_a_named_item_keeps_its_name_and_never_asks_the_model(auth_client, no_identify):
    r = await _scan(auth_client, photo_bytes(), name="  Sleepsuit  ")
    assert r.status_code == 201, r.text
    body = r.json()
    # Trimmed, and never overwritten. The whole feature in one line.
    assert body["name"] == "Sleepsuit"
    assert body["is_draft"] is True
    # Nothing was guessed, so nothing is claimed: no confidence, no scan error. An empty
    # description here means "nobody asked", not "the model failed".
    assert body["scan_confidence"] is None
    assert body["scan_error"] is None
    assert body["description"] is None


async def test_the_label_pass_still_runs_and_reads_the_person_s_category(
    auth_client, no_identify, monkeypatch
):
    """The gate reads the supplied category id, resolved to its name.

    "Baby" is the case worth pinning: it is a seeded category that says nothing about clothing
    on its face, and a garment filed under it must still get its tag read.
    """
    from app.services.ai.label_prompts import LabelDraft

    async def fake_label(urls, client=None):
        return LabelDraft(size="3-6M", department="girls")

    monkeypatch.setattr(no_identify, "read_label", fake_label)

    # Created here rather than assumed: the auth_client fixture seeds one category, not the
    # full DEFAULT_CATEGORIES. "Baby" is the case worth pinning because it says nothing about
    # clothing on its face — it reaches the gate through _CLOTHING_CATEGORY_HINTS alone.
    baby = (await auth_client.post("/categories", json={"name": "Baby"})).json()

    r = await _scan(auth_client, photo_bytes(), name="Sleepsuit", category_id=baby["id"])
    assert r.status_code == 201, r.text
    body = r.json()
    assert body["category_id"] == baby["id"]
    # Verbatim, and placed on the ladder — the derived index the server owns.
    assert body["apparel"]["size_raw"] == "3-6M"
    assert body["apparel"]["size_system"] == "infant_months"


async def test_a_named_item_that_is_not_clothing_skips_the_label_pass(
    auth_client, no_identify, monkeypatch
):
    """Two calls saved, not one. The gate is the same one-sided rule as before — it just gets
    trustworthy inputs now."""
    called = {"n": 0}

    async def counting_label(urls, client=None):
        called["n"] += 1

    monkeypatch.setattr(no_identify, "read_label", counting_label)

    r = await _scan(auth_client, photo_bytes(), name="Ratchet set")
    assert r.status_code == 201, r.text
    assert called["n"] == 0


async def test_describe_is_only_asked_for_when_asked_for(auth_client, no_identify, monkeypatch):
    from app.services.ai.describe_prompts import DescribeDraft

    seen = {"names": []}

    async def fake_describe(urls, name, client=None):
        seen["names"].append(name)
        return DescribeDraft(description="Grey with yellow ducks, pilling at the cuffs")

    monkeypatch.setattr(no_identify, "describe_item", fake_describe)

    off = await _scan(auth_client, photo_bytes(), name="Sleepsuit")
    assert off.json()["description"] is None
    assert seen["names"] == []

    on = await _scan(auth_client, photo_bytes(), name="Sleepsuit", describe="true")
    assert on.json()["description"] == "Grey with yellow ducks, pilling at the cuffs"
    # It is told what the thing is, which is the entire reason the prompt can be narrow.
    assert seen["names"] == ["Sleepsuit"]


async def test_a_failed_describe_costs_only_the_description(auth_client, no_identify, monkeypatch):
    """Its own except, for the same reason the label pass has one. A description is the most
    disposable thing this pipeline produces and must never take the size read with it."""
    from fastapi import HTTPException

    from app.services.ai.label_prompts import LabelDraft

    async def unreachable(urls, name, client=None):
        raise HTTPException(503, "model unreachable")

    async def fake_label(urls, client=None):
        return LabelDraft(size="3-6M")

    monkeypatch.setattr(no_identify, "describe_item", unreachable)
    monkeypatch.setattr(no_identify, "read_label", fake_label)

    r = await _scan(auth_client, photo_bytes(), name="Sleepsuit", describe="true")
    assert r.status_code == 201, r.text
    body = r.json()
    assert body["description"] is None
    # The expensive read survived.
    assert body["apparel"]["size_raw"] == "3-6M"
    # And it is NOT reported as a scan failure: the scan worked, one optional extra did not.
    assert body["scan_error"] is None


async def test_the_destination_is_still_remembered_and_still_not_applied(auth_client, no_identify):
    """The named path must not skip the housekeeping the identified path does. It returns early,
    which is exactly the shape that loses a line like this one."""
    tote = (await auth_client.post("/totes", json={"code": "A15"})).json()

    r = await _scan(auth_client, photo_bytes(), name="Sleepsuit", tote_id=tote["id"])
    assert r.status_code == 201, r.text
    assert r.json()["draft_tote_id"] == tote["id"]
    # Suggested, never applied — an item enters a bin only when a human confirms.
    assert (await auth_client.get(f"/totes/{tote['id']}")).json()["item_count"] == 0


async def test_a_blank_name_falls_back_to_identifying(auth_client, monkeypatch):
    """Whitespace is not an answer. Both paths stay available and the old one is the default."""
    import app.services.scan_pipeline as pipeline

    async def fake_identify(urls, categories=None, client=None):
        from app.services.ai.identify_prompts import IdentifyDraft

        return IdentifyDraft(name="Cordless drill", confidence="high")

    monkeypatch.setattr(pipeline, "identify_item", fake_identify)

    r = await _scan(auth_client, photo_bytes(), name="   ")
    assert r.json()["name"] == "Cordless drill"


async def test_a_category_that_is_not_yours_is_404(auth_client, raw_sql):
    """Ownership, not merely existence. The id is real and resolves — to somebody else's row."""
    import uuid

    owner, category = str(uuid.uuid4()), str(uuid.uuid4())
    await raw_sql(
        "INSERT INTO users (id, name, email) VALUES (:i, 'Other', :e)",
        i=owner,
        e=f"{owner[:8]}@example.com",
    )
    await raw_sql(
        "INSERT INTO categories (id, user_id, name, sort_order) VALUES (:c, :u, 'Theirs', 0)",
        c=category,
        u=owner,
    )

    r = await _scan(auth_client, photo_bytes(), name="Sleepsuit", category_id=category)
    assert r.status_code == 404


# ── the describe reply ─────────────────────────────────────────────────────────────────────


def test_a_describe_reply_salvages_the_same_way_the_others_do():
    assert (
        parse_describe('```json\n{"description": "Blue, ducks"}\n```').description == "Blue, ducks"
    )
    assert parse_describe('Sure! {"description": "Blue, ducks"} Hope that helps').description == (
        "Blue, ducks"
    )
    # Null is a real answer: a plain object, a dark photo, a close crop. It must not become the
    # string "null" or an empty description that reads downstream as "a human wrote nothing".
    assert parse_describe('{"description": null}').description is None
    assert parse_describe('{"description": "   "}').description is None
    assert parse_describe("total nonsense, no json here") is None
