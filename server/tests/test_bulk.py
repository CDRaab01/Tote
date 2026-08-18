"""Acting on a selection of items at once.

Two endpoints that look similar and are deliberately different animals:

* `bulk-move` changes which BIN things are in, so it writes one ledger row each, through the
  same single writer as every other relocation.
* `bulk-bag` changes which BAG inside a bin, which is a label rather than a whereabouts event,
  so it writes nothing to the ledger at all.

Getting that distinction wrong in either direction is the interesting failure: ledger rows for
relabelling would fill "where was this last year" with noise, and a silent bin change would put
a hole in the one record this app promises has none.
"""

import uuid


async def _tote(c, code):
    r = await c.post("/totes", json={"code": code})
    assert r.status_code == 201, r.text
    return r.json()


async def _item(c, name, tote_id):
    r = await c.post("/items", json={"name": name, "tote_id": tote_id})
    assert r.status_code == 201, r.text
    return r.json()


async def _bag(c, tote_id, name="3-6M onesies"):
    r = await c.post(f"/totes/{tote_id}/containers", json={"name": name})
    assert r.status_code == 201, r.text
    return r.json()


async def test_a_selection_moves_together_with_one_ledger_row_each(auth_client):
    here, there = await _tote(auth_client, "A15"), await _tote(auth_client, "B02")
    a = await _item(auth_client, "Onesie", here["id"])
    b = await _item(auth_client, "Sleepsuit", here["id"])
    stays = await _item(auth_client, "Comforter", here["id"])

    r = await auth_client.post(
        "/items/bulk-move", json={"item_ids": [a["id"], b["id"]], "to_tote_id": there["id"]}
    )
    assert r.status_code == 200, r.text
    assert len(r.json()) == 2

    for moved in (a, b):
        got = (await auth_client.get(f"/items/{moved['id']}")).json()
        assert got["current_tote_id"] == there["id"]
        assert got["status"] == "stored"
        reasons = [
            m["reason"] for m in (await auth_client.get(f"/items/{moved['id']}/movements")).json()
        ]
        assert "moved" in reasons
    # The unselected one did not come along.
    assert (await auth_client.get(f"/items/{stays['id']}")).json()["current_tote_id"] == here["id"]


async def test_something_that_is_out_comes_back_as_a_repack_not_a_move(auth_client):
    """The same distinction the item sheet makes one at a time. A year later "it changed bins"
    and "it came back" are different facts, and a bulk action must not flatten them."""
    here, there = await _tote(auth_client, "A15"), await _tote(auth_client, "B02")
    item = await _item(auth_client, "Onesie", here["id"])
    await auth_client.post(f"/items/{item['id']}/move", json={"reason": "unpacked"})

    r = await auth_client.post(
        "/items/bulk-move", json={"item_ids": [item["id"]], "to_tote_id": there["id"]}
    )
    assert r.status_code == 200
    assert r.json()[0]["reason"] == "repacked"


async def test_something_a_person_had_comes_back_as_a_return(auth_client):
    """The third site of the same defect, and the one that costs most.

    `repacked` and `returned` both land the item in a bin, so nothing looks wrong. But the
    `returned` row is the only record that a loan ever ENDED — "who had this and did it come
    back" is the question the people table exists for, and flattening it into a reshelving makes
    that unanswerable from the ledger built to answer it.
    """
    here, there = await _tote(auth_client, "A16"), await _tote(auth_client, "B03")
    item = await _item(auth_client, "Cordless drill", here["id"])
    person = (await auth_client.post("/people", json={"name": "Dave"})).json()
    await auth_client.post(
        f"/items/{item['id']}/move",
        json={"reason": "loaned", "person_id": person["id"]},
    )

    r = await auth_client.post(
        "/items/bulk-move", json={"item_ids": [item["id"]], "to_tote_id": there["id"]}
    )
    assert r.status_code == 200
    assert r.json()[0]["reason"] == "returned"


async def test_a_selection_can_land_straight_in_a_bag(auth_client):
    """The whole point of doing this after a batch of clothing: shoot eight onesies, then put
    them all in the 3-6M bag in one go."""
    here, there = await _tote(auth_client, "A15"), await _tote(auth_client, "B02")
    bag = await _bag(auth_client, there["id"])
    a = await _item(auth_client, "Onesie", here["id"])
    b = await _item(auth_client, "Sleepsuit", here["id"])

    r = await auth_client.post(
        "/items/bulk-move",
        json={
            "item_ids": [a["id"], b["id"]],
            "to_tote_id": there["id"],
            "container_id": bag["id"],
        },
    )
    assert r.status_code == 200

    for moved in (a, b):
        got = (await auth_client.get(f"/items/{moved['id']}")).json()
        assert got["current_tote_id"] == there["id"]
        # Set AFTER the move, because record_move clears container_id on the way in — the
        # destination's bags are not the source's.
        assert got["container_id"] == bag["id"]


async def test_a_bag_in_the_wrong_bin_is_refused(auth_client):
    here, there = await _tote(auth_client, "A15"), await _tote(auth_client, "B02")
    # A bag in the bin they are LEAVING, not the one they are going to.
    wrong_bag = await _bag(auth_client, here["id"])
    item = await _item(auth_client, "Onesie", here["id"])

    r = await auth_client.post(
        "/items/bulk-move",
        json={
            "item_ids": [item["id"]],
            "to_tote_id": there["id"],
            "container_id": wrong_bag["id"],
        },
    )
    assert r.status_code == 422
    # And nothing moved: the check happens before any write.
    assert (await auth_client.get(f"/items/{item['id']}")).json()["current_tote_id"] == here["id"]


async def test_one_bad_id_moves_nothing(auth_client):
    """All or nothing. A partial move leaves someone believing a selection is together when part
    of it never went — which is worse than an error, because nothing says so."""
    here, there = await _tote(auth_client, "A15"), await _tote(auth_client, "B02")
    real = await _item(auth_client, "Onesie", here["id"])

    r = await auth_client.post(
        "/items/bulk-move",
        json={"item_ids": [real["id"], str(uuid.uuid4())], "to_tote_id": there["id"]},
    )
    assert r.status_code == 404
    assert (await auth_client.get(f"/items/{real['id']}")).json()["current_tote_id"] == here["id"]


async def test_bagging_a_selection_writes_nothing_to_the_ledger(auth_client):
    """Which bag a thing sits in is a LABEL, not a whereabouts event. Ledger rows here would
    fill "where was this last year" with noise about relabelling."""
    tote = await _tote(auth_client, "A15")
    bag = await _bag(auth_client, tote["id"])
    a = await _item(auth_client, "Onesie", tote["id"])
    b = await _item(auth_client, "Sleepsuit", tote["id"])
    before = len((await auth_client.get(f"/items/{a['id']}/movements")).json())

    r = await auth_client.post(
        "/items/bulk-bag", json={"item_ids": [a["id"], b["id"]], "container_id": bag["id"]}
    )
    assert r.status_code == 204

    assert (await auth_client.get(f"/items/{a['id']}")).json()["container_id"] == bag["id"]
    assert len((await auth_client.get(f"/items/{a['id']}/movements")).json()) == before
    assert (await auth_client.get(f"/totes/{tote['id']}/containers")).json()[0]["item_count"] == 2


async def test_a_null_bag_makes_a_selection_loose_again(auth_client):
    tote = await _tote(auth_client, "A15")
    bag = await _bag(auth_client, tote["id"])
    item = await _item(auth_client, "Onesie", tote["id"])
    await auth_client.post(
        "/items/bulk-bag", json={"item_ids": [item["id"]], "container_id": bag["id"]}
    )

    r = await auth_client.post("/items/bulk-bag", json={"item_ids": [item["id"]]})
    assert r.status_code == 204
    assert (await auth_client.get(f"/items/{item['id']}")).json()["container_id"] is None


async def test_bagging_something_that_is_not_in_that_bin_is_refused(auth_client):
    here, there = await _tote(auth_client, "A15"), await _tote(auth_client, "B02")
    bag = await _bag(auth_client, there["id"])
    item = await _item(auth_client, "Onesie", here["id"])

    r = await auth_client.post(
        "/items/bulk-bag", json={"item_ids": [item["id"]], "container_id": bag["id"]}
    )
    assert r.status_code == 422


async def test_an_empty_selection_is_refused_rather_than_treated_as_everything(auth_client):
    """Unlike unpack, where null means "the whole bin" because the bin is right there and its
    contents are obvious. There is no obvious default set for "move these", and inventing one
    would move things nobody chose."""
    there = await _tote(auth_client, "B02")
    r = await auth_client.post("/items/bulk-move", json={"item_ids": [], "to_tote_id": there["id"]})
    assert r.status_code == 422


async def test_someone_else_s_items_are_not_movable(auth_client, raw_sql):
    there = await _tote(auth_client, "B02")
    owner, item = str(uuid.uuid4()), str(uuid.uuid4())
    await raw_sql(
        "INSERT INTO users (id, name, email) VALUES (:i, 'Other', :e)",
        i=owner,
        e=f"{owner[:8]}@example.com",
    )
    await raw_sql(
        "INSERT INTO items (id, user_id, name, quantity, status) "
        "VALUES (:i, :u, 'Theirs', 1, 'out')",
        i=item,
        u=owner,
    )

    r = await auth_client.post(
        "/items/bulk-move", json={"item_ids": [item], "to_tote_id": there["id"]}
    )
    assert r.status_code == 404
