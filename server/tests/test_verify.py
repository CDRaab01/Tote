"""POST /totes/{id}/verify — the audit that keeps the catalogue trustworthy.

The endpoint's contract is the COVERAGE RULE: every item the catalogue says is stored in the
bin must be claimed present or missing, or nothing happens at all. Most of these tests are
about the "nothing happens" half — a partial audit that stamped `last_verified_at` anyway
would quietly redefine the stamp as "somebody opened the lid once".
"""

import datetime
import uuid


async def _tote(c, code="A14", **kw):
    r = await c.post("/totes", json={"code": code, **kw})
    assert r.status_code == 201, r.text
    return r.json()


async def _item(c, name="Ratchet set", tote_id=None, **kw):
    r = await c.post("/items", json={"name": name, "tote_id": tote_id, **kw})
    assert r.status_code == 201, r.text
    return r.json()


def _ts(s):
    """Parsed rather than compared as strings, so the assertions are about ordering and
    equality, not about which spelling of UTC the serializer prefers."""
    return datetime.datetime.fromisoformat(s)


async def test_verify_stamps_the_tote_and_moves_only_the_missing(auth_client):
    t = await _tote(auth_client)
    seen = await _item(auth_client, "Lights", tote_id=t["id"])
    gone = await _item(auth_client, "Tree stand", tote_id=t["id"])

    r = await auth_client.post(
        f"/totes/{t['id']}/verify", json={"present": [seen["id"]], "missing": [gone["id"]]}
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["present_count"] == 1
    assert body["missing_count"] == 1
    assert body["last_verified_at"] is not None

    # The missing item is out, honestly: the catalogue was wrong about it, nobody unpacked it.
    got = (await auth_client.get(f"/items/{gone['id']}")).json()
    assert got["status"] == "out"
    assert got["out_reason"] == "missing"
    assert got["current_tote_id"] is None

    # One `corrected` ledger row, recording which bin the item went missing from.
    moves = (await auth_client.get(f"/items/{gone['id']}/movements")).json()
    assert [m["reason"] for m in moves] == ["corrected", "initial"]
    assert moves[0]["from_tote_id"] == t["id"]
    assert moves[0]["to_tote_id"] is None
    assert moves[0]["note"] == "Missing at verify"

    # The present item is where the catalogue said — no move, no ledger row.
    assert (await auth_client.get(f"/items/{seen['id']}")).json()["status"] == "stored"
    moves = (await auth_client.get(f"/items/{seen['id']}/movements")).json()
    assert [m["reason"] for m in moves] == ["initial"]

    # And the stamp survives to the tote's own read path.
    detail = (await auth_client.get(f"/totes/{t['id']}")).json()
    assert _ts(detail["last_verified_at"]) == _ts(body["last_verified_at"])


async def test_an_unaccounted_item_is_422_and_nothing_changes(auth_client):
    """All or nothing: the item that WAS claimed missing must not move either."""
    t = await _tote(auth_client)
    forgotten = await _item(auth_client, "Lights", tote_id=t["id"])
    claimed = await _item(auth_client, "Tree stand", tote_id=t["id"])

    r = await auth_client.post(
        f"/totes/{t['id']}/verify", json={"present": [], "missing": [claimed["id"]]}
    )
    assert r.status_code == 422
    assert "not accounted for" in r.json()["detail"]

    assert (await auth_client.get(f"/totes/{t['id']}")).json()["last_verified_at"] is None
    for item in (forgotten, claimed):
        got = (await auth_client.get(f"/items/{item['id']}")).json()
        assert got["status"] == "stored"
        assert got["current_tote_id"] == t["id"]
        moves = (await auth_client.get(f"/items/{item['id']}/movements")).json()
        assert [m["reason"] for m in moves] == ["initial"]


async def test_an_id_in_both_lists_is_422(auth_client):
    t = await _tote(auth_client)
    item = await _item(auth_client, tote_id=t["id"])
    r = await auth_client.post(
        f"/totes/{t['id']}/verify", json={"present": [item["id"]], "missing": [item["id"]]}
    )
    assert r.status_code == 422
    assert "both" in r.json()["detail"]
    # Coverage was numerically satisfied here, so the contradiction check must fire on its own.
    assert (await auth_client.get(f"/items/{item['id']}")).json()["status"] == "stored"
    assert (await auth_client.get(f"/totes/{t['id']}")).json()["last_verified_at"] is None


async def test_an_id_not_stored_in_this_tote_is_422(auth_client):
    """An unknown id and an item stored in some other bin fail identically — the response must
    not let a caller probe which is which."""
    t = await _tote(auth_client, "A01")
    other_bin = await _tote(auth_client, "B01")
    mine = await _item(auth_client, "Here", tote_id=t["id"])
    elsewhere = await _item(auth_client, "There", tote_id=other_bin["id"])

    for stranger in (str(uuid.uuid4()), elsewhere["id"]):
        r = await auth_client.post(
            f"/totes/{t['id']}/verify",
            json={"present": [mine["id"], stranger], "missing": []},
        )
        assert r.status_code == 422, r.text
        assert "not stored in this tote" in r.json()["detail"]
    assert (await auth_client.get(f"/totes/{t['id']}")).json()["last_verified_at"] is None


async def test_another_households_tote_is_404(auth_client, other_client):
    t = await _tote(auth_client)
    item = await _item(auth_client, tote_id=t["id"])
    r = await other_client.post(
        f"/totes/{t['id']}/verify", json={"present": [item["id"]], "missing": []}
    )
    assert r.status_code == 404
    assert (await auth_client.get(f"/totes/{t['id']}")).json()["last_verified_at"] is None


async def test_an_empty_bin_verifies_trivially(auth_client):
    """ "Checked and empty" is exactly as much knowledge as "checked and all present"."""
    t = await _tote(auth_client)
    r = await auth_client.post(f"/totes/{t['id']}/verify", json={})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["present_count"] == 0
    assert body["missing_count"] == 0
    assert body["last_verified_at"] is not None
    assert (await auth_client.get(f"/totes/{t['id']}")).json()["last_verified_at"] is not None


async def test_items_already_out_are_not_part_of_the_audit(auth_client):
    """The bin cannot testify about what is not in it: an out item is neither required in the
    coverage set nor accepted in it, and verifying leaves its own story untouched."""
    t = await _tote(auth_client)
    stays = await _item(auth_client, "Stays", tote_id=t["id"])
    out = await _item(auth_client, "Out", tote_id=t["id"])
    await auth_client.post(f"/items/{out['id']}/move", json={"reason": "unpacked"})

    # Not required: coverage is satisfied without it.
    r = await auth_client.post(
        f"/totes/{t['id']}/verify", json={"present": [stays["id"]], "missing": []}
    )
    assert r.status_code == 200, r.text

    got = (await auth_client.get(f"/items/{out['id']}")).json()
    assert got["status"] == "out"
    assert got["out_reason"] == "unpacked"  # not rewritten to "missing"
    moves = (await auth_client.get(f"/items/{out['id']}/movements")).json()
    assert [m["reason"] for m in moves] == ["unpacked", "initial"]

    # And not accepted: claiming it would claim something the bin cannot know.
    r = await auth_client.post(
        f"/totes/{t['id']}/verify",
        json={"present": [stays["id"]], "missing": [out["id"]]},
    )
    assert r.status_code == 422


async def test_verifying_twice_updates_the_stamp(auth_client):
    t = await _tote(auth_client)
    first = (await auth_client.post(f"/totes/{t['id']}/verify", json={})).json()
    second = (await auth_client.post(f"/totes/{t['id']}/verify", json={})).json()
    assert _ts(second["last_verified_at"]) > _ts(first["last_verified_at"])


async def test_inbound_corrected_still_files_into_a_bin(auth_client):
    """`corrected` gained an outbound meaning; the inbound one — "it was in B01 all along" —
    must be exactly what it always was."""
    a = await _tote(auth_client, "A01")
    b = await _tote(auth_client, "B01")
    item = await _item(auth_client, tote_id=a["id"])
    r = await auth_client.post(
        f"/items/{item['id']}/move", json={"reason": "corrected", "to_tote_id": b["id"]}
    )
    assert r.status_code == 200, r.text
    got = (await auth_client.get(f"/items/{item['id']}")).json()
    assert got["status"] == "stored"
    assert got["current_tote_id"] == b["id"]
    assert got["out_reason"] is None
