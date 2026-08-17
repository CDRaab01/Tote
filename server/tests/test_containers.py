"""Bags inside a tote.

A real bin is not a flat pile: a tote of baby clothes is three zip bags and a loose blanket, and
"which bag is the 3-6M one" was unanswerable.

The tests that matter here are the ones about **whereabouts staying a single fact**. A container
is a label inside one bin and carries no location of its own, so the only way it can lie is by
an item claiming membership of a bag in a bin it is not in. Those are the cases below.
"""

import uuid


async def _tote(c, code="A15"):
    r = await c.post("/totes", json={"code": code})
    assert r.status_code == 201, r.text
    return r.json()


async def _bag(c, tote_id, name="3-6M onesies", notes=None):
    r = await c.post(f"/totes/{tote_id}/containers", json={"name": name, "notes": notes})
    assert r.status_code == 201, r.text
    return r.json()


async def _item(c, name, tote_id):
    r = await c.post("/items", json={"name": name, "tote_id": tote_id})
    assert r.status_code == 201, r.text
    return r.json()


async def test_a_bag_lives_in_a_bin_and_counts_what_it_holds(auth_client):
    tote = await _tote(auth_client)
    bag = await _bag(auth_client, tote["id"], notes="mostly onesies, some vests")
    assert bag["item_count"] == 0
    # The notes are the point for a bag that is only approximately catalogued.
    assert bag["notes"] == "mostly onesies, some vests"

    item = await _item(auth_client, "Onesie", tote["id"])
    await auth_client.patch(f"/items/{item['id']}", json={"container_id": bag["id"]})

    listed = (await auth_client.get(f"/totes/{tote['id']}/containers")).json()
    assert listed[0]["item_count"] == 1
    # And the bin's own detail carries its bags, so one request answers "what is in here".
    detail = (await auth_client.get(f"/totes/{tote['id']}")).json()
    assert [c["name"] for c in detail["containers"]] == ["3-6M onesies"]
    assert detail["items"][0]["container_id"] == bag["id"]


async def test_an_item_cannot_join_a_bag_in_a_different_bin(auth_client):
    """The one way a container could lie about whereabouts, refused.

    The item would claim membership of a grouping inside a bin it is not in, which is exactly
    the contradiction that keeping location on the ITEM is meant to prevent.
    """
    here, elsewhere = await _tote(auth_client, "A15"), await _tote(auth_client, "B02")
    bag_over_there = await _bag(auth_client, elsewhere["id"], name="winter stuff")
    item = await _item(auth_client, "Onesie", here["id"])

    r = await auth_client.patch(f"/items/{item['id']}", json={"container_id": bag_over_there["id"]})
    assert r.status_code == 422
    assert "not in the tote" in r.json()["detail"]


async def test_leaving_the_bin_leaves_the_bag(auth_client):
    """A bag is a grouping INSIDE a tote, so an item that is out of the tote is in no bag.

    Cleared by the movement service — the single writer of derived state — rather than by each
    caller, because a stale container_id would make a bin's grouping claim something the bin does
    not contain.
    """
    tote = await _tote(auth_client)
    bag = await _bag(auth_client, tote["id"])
    item = await _item(auth_client, "Onesie", tote["id"])
    await auth_client.patch(f"/items/{item['id']}", json={"container_id": bag["id"]})

    await auth_client.post(f"/items/{item['id']}/move", json={"reason": "unpacked"})

    assert (await auth_client.get(f"/items/{item['id']}")).json()["container_id"] is None
    assert (await auth_client.get(f"/totes/{tote['id']}/containers")).json()[0]["item_count"] == 0


async def test_moving_to_another_bin_does_not_carry_the_bag_across(auth_client):
    here, there = await _tote(auth_client, "A15"), await _tote(auth_client, "B02")
    bag = await _bag(auth_client, here["id"])
    item = await _item(auth_client, "Onesie", here["id"])
    await auth_client.patch(f"/items/{item['id']}", json={"container_id": bag["id"]})

    await auth_client.post(
        f"/items/{item['id']}/move", json={"reason": "moved", "to_tote_id": there["id"]}
    )

    moved = (await auth_client.get(f"/items/{item['id']}")).json()
    assert moved["current_tote_id"] == there["id"]
    # The destination's bags are not the source's.
    assert moved["container_id"] is None


async def test_deleting_a_bag_keeps_its_contents_exactly_where_they_are(auth_client):
    """Undo the grouping, never the contents — the same promise deleting a tote makes."""
    tote = await _tote(auth_client)
    bag = await _bag(auth_client, tote["id"])
    item = await _item(auth_client, "Onesie", tote["id"])
    await auth_client.patch(f"/items/{item['id']}", json={"container_id": bag["id"]})

    r = await auth_client.delete(f"/totes/{tote['id']}/containers/{bag['id']}")
    assert r.status_code == 204

    survivor = (await auth_client.get(f"/items/{item['id']}")).json()
    assert survivor["current_tote_id"] == tote["id"]
    assert survivor["container_id"] is None


async def test_deleting_the_bin_takes_its_bags_and_leaves_its_items(auth_client):
    """CASCADE on the bag, SET NULL on the item. A bag has no meaning outside its bin; the
    things that were in it still exist."""
    tote = await _tote(auth_client)
    bag = await _bag(auth_client, tote["id"])
    item = await _item(auth_client, "Onesie", tote["id"])
    await auth_client.patch(f"/items/{item['id']}", json={"container_id": bag["id"]})

    assert (await auth_client.delete(f"/totes/{tote['id']}")).status_code == 204

    survivor = (await auth_client.get(f"/items/{item['id']}")).json()
    assert survivor["current_tote_id"] is None
    assert survivor["container_id"] is None


async def test_a_bag_can_be_renamed_but_never_relocated(auth_client):
    tote = await _tote(auth_client)
    bag = await _bag(auth_client, tote["id"])

    r = await auth_client.patch(
        f"/totes/{tote['id']}/containers/{bag['id']}",
        json={"name": "  6-9M sleepsuits  ", "notes": "the ones with feet"},
    )
    assert r.status_code == 200
    assert r.json()["name"] == "6-9M sleepsuits"

    # There is no tote_id on ContainerPatch at all, so a body that tries to relocate the bag is
    # ignored rather than obeyed. A bag that could change bins would be the second source of
    # truth for whereabouts this whole design refuses.
    elsewhere = await _tote(auth_client, "B02")
    moved = await auth_client.patch(
        f"/totes/{tote['id']}/containers/{bag['id']}",
        json={"name": "still here", "tote_id": elsewhere["id"]},
    )
    assert moved.status_code == 200
    assert moved.json()["tote_id"] == tote["id"]
    # And it is still listed under the bin it started in, not the one the body named.
    assert (await auth_client.get(f"/totes/{elsewhere['id']}/containers")).json() == []


async def test_someone_else_s_bin_has_no_bags_you_can_reach(auth_client, raw_sql):
    owner, tote = str(uuid.uuid4()), str(uuid.uuid4())
    await raw_sql(
        "INSERT INTO users (id, name, email) VALUES (:i, 'Other', :e)",
        i=owner,
        e=f"{owner[:8]}@example.com",
    )
    await raw_sql("INSERT INTO totes (id, user_id, code) VALUES (:t, :u, 'ZZ9')", t=tote, u=owner)

    assert (await auth_client.get(f"/totes/{tote}/containers")).status_code == 404
    assert (
        await auth_client.post(f"/totes/{tote}/containers", json={"name": "theirs"})
    ).status_code == 404
