"""Sharing a catalogue with another Dragonfly account.

The membership half of this is ordinary. The merge is not, and most of what is asserted below is
about the merge refusing to do something plausible: silently renaming a bin whose code is
written on a card, folding two bins that both call themselves A14, or letting somebody discover
the size of an irreversible operation afterwards.
"""

import pytest


async def _tote(client, code, **extra):
    r = await client.post("/totes", json={"code": code, **extra})
    assert r.status_code == 201, r.text
    return r.json()


async def _item(client, name, tote_id=None):
    body = {"name": name, "quantity": 1}
    if tote_id:
        body["tote_id"] = tote_id
    r = await client.post("/items", json=body)
    assert r.status_code == 201, r.text
    return r.json()


async def _invite(owner, invitee_email):
    return await owner.post("/household/members", json={"email": invitee_email})


async def _email_of(client):
    return (await client.get("/users/me")).json()["email"]


# --- The default: everybody has a household of one ---------------------------------------


async def test_a_new_account_is_a_household_of_one(auth_client):
    """No "solo" special case anywhere: the access checks read `household_id` unconditionally,
    which only works because there is always one to read."""
    r = await auth_client.get("/household")
    assert r.status_code == 200
    body = r.json()
    assert body["you_are_owner"] is True
    assert body["shared"] is False
    assert len(body["members"]) == 1


async def test_nothing_is_shared_until_the_invite_is_accepted(auth_client, other_client):
    """A pending invite grants nothing. Being added to somebody's household without agreeing to
    it would hand them your catalogue, which is the one thing an invite must never do."""
    mine = await _tote(auth_client, "A14")
    assert (await _invite(auth_client, await _email_of(other_client))).status_code == 201

    assert (await other_client.get(f"/totes/{mine['id']}")).status_code == 404
    assert (await other_client.get("/household")).json()["shared"] is False


# --- Invites ------------------------------------------------------------------------------


async def test_inviting_someone_who_has_never_signed_in_is_404(auth_client):
    """They must have an account for the merge preview to be computed against."""
    r = await _invite(auth_client, "nobody@example.com")
    assert r.status_code == 404
    assert "sign in to Tote once" in r.json()["detail"]


async def test_inviting_yourself_is_rejected(auth_client):
    r = await _invite(auth_client, await _email_of(auth_client))
    assert r.status_code == 400


async def test_the_invite_carries_a_preview_of_what_it_would_move(auth_client, other_client):
    """Counted against the INVITEE's catalogue: they are the one giving something up."""
    await _invite(auth_client, await _email_of(other_client))
    theirs = await _tote(other_client, "Q1")
    await _item(other_client, "Their drill", theirs["id"])

    preview = (await other_client.get("/household/invite")).json()["preview"]
    assert preview["totes"] == 1
    assert preview["items"] == 1
    assert preview["conflicts"] == {}


async def test_declining_leaves_both_catalogues_alone(auth_client, other_client):
    mine = await _tote(auth_client, "A14")
    await _invite(auth_client, await _email_of(other_client))

    assert (await other_client.post("/household/decline")).status_code == 204

    assert (await other_client.get("/household/invite")).json() is None
    assert (await other_client.get(f"/totes/{mine['id']}")).status_code == 404
    assert (await auth_client.get(f"/totes/{mine['id']}")).status_code == 200


# --- Accepting: the merge -----------------------------------------------------------------


async def test_accepting_makes_both_catalogues_one(auth_client, other_client):
    mine = await _tote(auth_client, "A14")
    theirs = await _tote(other_client, "B02")
    await _invite(auth_client, await _email_of(other_client))

    r = await other_client.post("/household/accept")
    assert r.status_code == 200, r.text
    assert r.json()["shared"] is True

    # Each of them can now reach the other's bin, which is the entire feature.
    assert (await other_client.get(f"/totes/{mine['id']}")).status_code == 200
    assert (await auth_client.get(f"/totes/{theirs['id']}")).status_code == 200


async def test_a_colliding_bin_code_blocks_the_merge_and_names_the_code(auth_client, other_client):
    """**The reason this is not Cookbook's household.** Two bins both called A14 is not a
    database inconvenience — it is two boxes in an attic with the same card on them, and no rule
    the server could apply is right. So it refuses, and says which one to go and look at."""
    await _tote(auth_client, "A14")
    await _tote(other_client, "a14")  # same bin code, different case
    await _invite(auth_client, await _email_of(other_client))

    r = await other_client.post("/household/accept")
    assert r.status_code == 409
    assert r.json()["detail"]["conflicts"]["tote_codes"] == ["a14"]

    # And nothing moved: a refused merge is not a half-done one.
    assert (await other_client.get("/household")).json()["shared"] is False


async def test_a_shared_nfc_tag_blocks_the_merge(auth_client, other_client):
    """One physical sticker cannot belong to two bins."""
    a = await _tote(auth_client, "A14")
    b = await _tote(other_client, "B02")
    for client, tote in ((auth_client, a), (other_client, b)):
        r = await client.post(f"/totes/{tote['id']}/nfc", json={"tag_uid": "04A224FA"})
        assert r.status_code == 200, r.text
    await _invite(auth_client, await _email_of(other_client))

    r = await other_client.post("/household/accept")
    assert r.status_code == 409
    assert r.json()["detail"]["conflicts"]["nfc_tags"] == ["04A224FA"]


async def test_the_preview_shows_the_conflict_before_you_press_accept(auth_client, other_client):
    """Recomputed per read, so renaming the bin actually clears the block rather than leaving a
    stale refusal cached from invite time."""
    await _tote(auth_client, "A14")
    theirs = await _tote(other_client, "A14")
    await _invite(auth_client, await _email_of(other_client))

    assert (await other_client.get("/household/invite")).json()["preview"]["conflicts"] == {
        "tote_codes": ["a14"]
    }

    r = await other_client.patch(f"/totes/{theirs['id']}", json={"code": "A15", "archived": False})
    assert r.status_code == 200, r.text

    assert (await other_client.get("/household/invite")).json()["preview"]["conflicts"] == {}
    assert (await other_client.post("/household/accept")).status_code == 200


async def test_same_named_locations_are_folded_not_duplicated(auth_client, other_client):
    """There is one attic, however many people put things in it. Two rows would split
    browse-by-location in half and put the same word twice in a filter."""
    mine = (await auth_client.post("/locations", json={"name": "Attic"})).json()
    theirs = (await other_client.post("/locations", json={"name": "attic"})).json()
    their_bin = await _tote(other_client, "Q1", location_id=theirs["id"])
    await _invite(auth_client, await _email_of(other_client))

    assert (await other_client.post("/household/accept")).status_code == 200

    locations = (await auth_client.get("/locations")).json()
    assert [loc["name"] for loc in locations] == ["Attic"]
    # The target's row won, and their bin followed it rather than being orphaned.
    assert (await auth_client.get(f"/totes/{their_bin['id']}")).json()["location_id"] == mine["id"]


async def test_the_seeded_categories_do_not_arrive_twice(auth_client, other_client):
    """Both accounts got the full `DEFAULT_CATEGORIES` at first login, so **every** seeded name
    collides on a merge. Without the fold, sharing would double the category picker."""
    before = {c["name"] for c in (await auth_client.get("/categories")).json()}
    await _invite(auth_client, await _email_of(other_client))

    assert (await other_client.post("/household/accept")).status_code == 200

    after = [c["name"] for c in (await auth_client.get("/categories")).json()]
    assert len(after) == len(set(after))
    assert set(after) == before


async def test_items_keep_their_category_across_a_fold(auth_client, other_client):
    """The fold repoints every FK at the surviving row. A dropped `category_id` would be silent:
    the item still lists, just uncategorised, and nobody would connect it to having shared."""
    theirs = (await other_client.get("/categories")).json()
    tools = next(c for c in theirs if c["name"] == "Tools")
    their_bin = await _tote(other_client, "Q1")
    r = await other_client.post(
        "/items",
        json={
            "name": "Ratchet set",
            "quantity": 1,
            "tote_id": their_bin["id"],
            "category_id": tools["id"],
        },
    )
    assert r.status_code == 201, r.text
    item_id = r.json()["id"]
    await _invite(auth_client, await _email_of(other_client))

    assert (await other_client.post("/household/accept")).status_code == 200

    got = (await auth_client.get(f"/items/{item_id}")).json()
    assert got["category_id"] is not None
    mine = {c["id"]: c["name"] for c in (await auth_client.get("/categories")).json()}
    assert mine[got["category_id"]] == "Tools"


async def test_a_merged_item_is_findable_by_search(auth_client, other_client):
    """`search_vector` is generated per row and never re-derived, so a merge that changed
    ownership without touching the text must still leave the item searchable."""
    their_bin = await _tote(other_client, "Q1")
    await _item(other_client, "Soldering iron", their_bin["id"])
    await _invite(auth_client, await _email_of(other_client))
    assert (await other_client.post("/household/accept")).status_code == 200

    hits = (await auth_client.get("/search", params={"q": "soldering"})).json()
    assert [h["item"]["name"] for h in hits] == ["Soldering iron"]


# --- Living in a shared household ---------------------------------------------------------


async def test_either_member_can_move_the_others_item(auth_client, other_client):
    """Fully collaborative, matching Cookbook: a shared catalogue where only the creator can
    move a thing describes no real household."""
    await _invite(auth_client, await _email_of(other_client))
    assert (await other_client.post("/household/accept")).status_code == 200

    mine = await _tote(auth_client, "A14")
    theirs = await _tote(other_client, "B02")
    item = await _item(auth_client, "Drill", mine["id"])

    r = await other_client.post(
        f"/items/{item['id']}/move", json={"reason": "moved", "to_tote_id": theirs["id"]}
    )
    assert r.status_code == 200, r.text
    assert (await auth_client.get(f"/items/{item['id']}")).json()["current_tote_id"] == theirs["id"]


async def test_the_ledger_records_which_member_moved_it(auth_client, other_client):
    """A question that does not exist in a one-person catalogue and is the first one asked in a
    shared one."""
    await _invite(auth_client, await _email_of(other_client))
    assert (await other_client.post("/household/accept")).status_code == 200
    mine = await _tote(auth_client, "A14")
    theirs = await _tote(other_client, "B02")
    item = await _item(auth_client, "Drill", mine["id"])

    await other_client.post(
        f"/items/{item['id']}/move", json={"reason": "moved", "to_tote_id": theirs["id"]}
    )

    history = (await auth_client.get(f"/items/{item['id']}/movements")).json()
    assert history[0]["moved_by_user_id"] == str(other_client.user_id)


async def test_a_bin_code_cannot_be_reused_by_the_other_member(auth_client, other_client):
    """Enforced by the database, not just by the merge: the constraint has to hold for every bin
    created *after* the households became one, which is most of them."""
    await _invite(auth_client, await _email_of(other_client))
    assert (await other_client.post("/household/accept")).status_code == 200
    await _tote(auth_client, "A14")

    r = await other_client.post("/totes", json={"code": "a14"})
    assert r.status_code == 409, r.text


# --- Leaving ------------------------------------------------------------------------------


async def test_leaving_forfeits_the_shared_catalogue(auth_client, other_client):
    """The opposite of Cookbook, where leaving costs nothing because recipes were always yours.
    Here the household owns the bins, so walking out means walking out of the attic."""
    await _invite(auth_client, await _email_of(other_client))
    assert (await other_client.post("/household/accept")).status_code == 200
    mine = await _tote(auth_client, "A14")

    assert (await other_client.post("/household/leave")).status_code == 204

    assert (await other_client.get(f"/totes/{mine['id']}")).status_code == 404
    assert (await other_client.get("/totes")).json() == []
    # The bin stayed where it was, with its contents.
    assert (await auth_client.get(f"/totes/{mine['id']}")).status_code == 200


async def test_the_owner_cannot_leave_a_household_with_members_in_it(auth_client, other_client):
    """It would leave the catalogue ownerless. Transfer first — an explicit act, because it
    hands over the ability to remove everybody else."""
    await _invite(auth_client, await _email_of(other_client))
    assert (await other_client.post("/household/accept")).status_code == 200

    r = await auth_client.post("/household/leave")
    assert r.status_code == 409
    assert "Transfer ownership" in r.json()["detail"]


async def test_a_solo_leave_does_not_wipe_your_own_catalogue(auth_client):
    """A no-op, deliberately: "leave" must never become a way to delete everything you own."""
    mine = await _tote(auth_client, "A14")
    assert (await auth_client.post("/household/leave")).status_code == 204
    assert (await auth_client.get(f"/totes/{mine['id']}")).status_code == 200


async def test_only_the_owner_can_remove_someone_else(auth_client, other_client):
    await _invite(auth_client, await _email_of(other_client))
    assert (await other_client.post("/household/accept")).status_code == 200

    r = await other_client.delete(f"/household/members/{auth_client.user_id}")
    assert r.status_code in (400, 403)

    assert (
        await auth_client.delete(f"/household/members/{other_client.user_id}")
    ).status_code == 204
    assert (await auth_client.get("/household")).json()["shared"] is False


async def test_ownership_transfers_and_the_old_owner_can_then_leave(auth_client, other_client):
    await _invite(auth_client, await _email_of(other_client))
    assert (await other_client.post("/household/accept")).status_code == 200

    r = await auth_client.post(f"/household/transfer/{other_client.user_id}")
    assert r.status_code == 200
    assert r.json()["you_are_owner"] is False

    assert (await auth_client.post("/household/leave")).status_code == 204
    assert (await other_client.get("/household")).json()["you_are_owner"] is True


@pytest.mark.parametrize("path", ["/household", "/household/invite"])
async def test_the_household_surface_needs_a_session(client, path):
    assert (await client.get(path)).status_code == 401
