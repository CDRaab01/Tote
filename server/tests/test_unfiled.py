"""Confirming a draft without choosing a bin.

Filing used to be compulsory at review time, which asks for the decision at the moment you are
least sure: the bin closed, the object already back inside it. Now `tote_id` may be null and the
item lands catalogued but unfiled.

The state is not new — deleting a tote already leaves its contents in exactly it — so the tests
that matter are about the **ledger** keeping "never filed" and "taken out of a bin" apart, and
about the derived-state invariant surviving a reason that puts an item nowhere.
"""

import uuid

from tests.fixtures.images import photo_bytes


async def _draft(client, monkeypatch_target=None, name="Sleepsuit"):
    files = {"photos": ("shot.jpg", photo_bytes(), "image/jpeg")}
    r = await client.post("/items/scan", files=files, data={"name": name})
    assert r.status_code == 201, r.text
    return r.json()


async def test_a_draft_can_be_confirmed_with_no_bin(auth_client):
    draft = await _draft(auth_client)

    r = await auth_client.post(
        f"/drafts/{draft['id']}/confirm", json={"name": "Sleepsuit", "quantity": 1}
    )
    assert r.status_code == 200, r.text
    item = r.json()

    assert item["current_tote_id"] is None
    # The invariant: current_tote_id IS NOT NULL <=> status == 'stored'. An item in no bin
    # cannot be 'stored', and "unfiled" is the honest reason — it was never taken out of
    # anything.
    assert item["status"] == "out"
    assert item["out_reason"] == "unfiled"
    # It IS in the catalogue: searchable, listable, not a draft.
    assert (await auth_client.get("/items")).json()[0]["id"] == item["id"]
    assert (await auth_client.get("/drafts")).json() == []


async def test_the_ledger_distinguishes_never_filed_from_taken_out(auth_client):
    """The reason this is a third movement kind rather than an outbound one with a nice label.

    A year later, "it was never put in a bin" and "it came out of A14" are different facts about
    the same object, and the ledger is the only place that difference survives.
    """
    unfiled = await auth_client.post(
        f"/drafts/{(await _draft(auth_client))['id']}/confirm",
        json={"name": "Sleepsuit", "quantity": 1},
    )
    moves = (await auth_client.get(f"/items/{unfiled.json()['id']}/movements")).json()
    assert [m["reason"] for m in moves] == ["catalogued"]
    assert moves[0]["from_tote_id"] is None and moves[0]["to_tote_id"] is None


async def test_filing_it_later_is_an_ordinary_move(auth_client):
    tote = (await auth_client.post("/totes", json={"code": "A15"})).json()
    item = (
        await auth_client.post(
            f"/drafts/{(await _draft(auth_client))['id']}/confirm",
            json={"name": "Sleepsuit", "quantity": 1},
        )
    ).json()

    r = await auth_client.post(
        f"/items/{item['id']}/move", json={"reason": "moved", "to_tote_id": tote["id"]}
    )
    assert r.status_code == 200, r.text

    filed = (await auth_client.get(f"/items/{item['id']}")).json()
    assert filed["current_tote_id"] == tote["id"]
    assert filed["status"] == "stored"
    # And the out_reason is cleared, not left saying "unfiled" over an item in a bin.
    assert filed["out_reason"] is None

    reasons = [
        m["reason"] for m in (await auth_client.get(f"/items/{item['id']}/movements")).json()
    ]
    assert sorted(reasons) == ["catalogued", "moved"]


async def test_confirming_with_a_bin_is_unchanged(auth_client):
    """The old path is the default and must not have moved. `initial`, not `catalogued`."""
    tote = (await auth_client.post("/totes", json={"code": "A16"})).json()
    item = (
        await auth_client.post(
            f"/drafts/{(await _draft(auth_client))['id']}/confirm",
            json={"name": "Sleepsuit", "quantity": 1, "tote_id": tote["id"]},
        )
    ).json()

    assert item["current_tote_id"] == tote["id"]
    assert item["status"] == "stored"
    moves = (await auth_client.get(f"/items/{item['id']}/movements")).json()
    assert [m["reason"] for m in moves] == ["initial"]


async def test_catalogued_refuses_a_destination(auth_client):
    """It is the one reason that files an item nowhere, so a bin is a contradiction rather than
    a convenience. Refused for the same reason the inbound reasons refuse a null."""
    item = (
        await auth_client.post(
            f"/drafts/{(await _draft(auth_client))['id']}/confirm",
            json={"name": "Sleepsuit", "quantity": 1},
        )
    ).json()
    tote = (await auth_client.post("/totes", json={"code": "A17"})).json()

    r = await auth_client.post(
        f"/items/{item['id']}/move",
        json={"reason": "catalogued", "to_tote_id": tote["id"]},
    )
    assert r.status_code == 422


async def test_unfiled_items_are_not_in_any_bin_s_contents(auth_client):
    tote = (await auth_client.post("/totes", json={"code": "A18"})).json()
    await auth_client.post(
        f"/drafts/{(await _draft(auth_client))['id']}/confirm",
        json={"name": "Sleepsuit", "quantity": 1},
    )

    detail = (await auth_client.get(f"/totes/{tote['id']}")).json()
    assert detail["items"] == []
    # And not in its "out of this tote" section either — it never left this tote, or any.
    assert detail["items_out"] == []
    assert detail["item_count"] == 0


async def test_an_unfiled_item_is_findable(auth_client):
    """The whole point is that it is catalogued. If search could not see it, deferring the bin
    would just be a way to lose things."""
    await auth_client.post(
        f"/drafts/{(await _draft(auth_client, name='Sleepsuit'))['id']}/confirm",
        json={"name": "Sleepsuit", "quantity": 1},
    )
    hits = (await auth_client.get("/search", params={"q": "sleepsuit"})).json()
    assert len(hits) == 1
    assert hits[0]["item"]["tote_code"] is None


async def test_a_bin_that_is_not_yours_is_still_404(auth_client, raw_sql):
    """Optional does not mean unchecked."""
    owner, elsewhere, tote = str(uuid.uuid4()), str(uuid.uuid4()), str(uuid.uuid4())
    await raw_sql(
        "INSERT INTO users (id, name, email) VALUES (:i, 'Other', :e)",
        i=owner,
        e=f"{owner[:8]}@example.com",
    )
    await raw_sql(
        "INSERT INTO households (id, owner_user_id) VALUES (:h, :u)", h=elsewhere, u=owner
    )
    await raw_sql(
        "INSERT INTO totes (id, household_id, user_id, code) VALUES (:t, :h, :u, 'ZZ9')",
        t=tote,
        h=elsewhere,
        u=owner,
    )

    r = await auth_client.post(
        f"/drafts/{(await _draft(auth_client))['id']}/confirm",
        json={"name": "Sleepsuit", "quantity": 1, "tote_id": tote},
    )
    assert r.status_code == 404
