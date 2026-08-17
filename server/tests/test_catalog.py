"""Tote/item CRUD, the movement ledger, and search.

The tests that matter most here are the ones about **derived state**. `current_tote_id` and
`status` are computed from the ledger by a single service, and the failure mode if that
discipline slips is not a crash — it is an item that is quietly both in bin A14 and lent to
Dave, which nothing detects until someone goes looking in the attic.
"""

import uuid


async def _tote(c, code="A14", **kw):
    r = await c.post("/totes", json={"code": code, **kw})
    assert r.status_code == 201, r.text
    return r.json()


async def _item(c, name="Ratchet set", tote_id=None, **kw):
    r = await c.post("/items", json={"name": name, "tote_id": tote_id, **kw})
    assert r.status_code == 201, r.text
    return r.json()


# ── Totes ────────────────────────────────────────────────────────────────────


async def test_tote_code_collision_is_a_409_not_a_500(auth_client):
    """The database refuses it via a functional unique index; the API has to turn that into a
    conflict the UI can explain, not an opaque server error."""
    await _tote(auth_client, "A14")
    r = await auth_client.post("/totes", json={"code": "a14"})
    assert r.status_code == 409
    assert "case-insensitive" in r.json()["detail"]


async def test_tote_code_is_stripped_before_it_is_stored(auth_client):
    """Leading/trailing space is invisible on a handwritten card and would defeat the
    case-insensitive uniqueness, producing two bins that look identically labelled."""
    t = await _tote(auth_client, "  B02  ")
    assert t["code"] == "B02"
    r = await auth_client.post("/totes", json={"code": "b02"})
    assert r.status_code == 409


async def test_item_count_is_computed_not_stored(auth_client):
    t = await _tote(auth_client)
    for n in ("Lights", "Tree stand", "Ornaments"):
        await _item(auth_client, n, tote_id=t["id"])
    r = await auth_client.get("/totes")
    assert r.json()[0]["item_count"] == 3


async def test_deleting_a_tote_keeps_its_items(auth_client):
    """Throwing a bin away must not erase the record of what was in it."""
    t = await _tote(auth_client)
    item = await _item(auth_client, tote_id=t["id"])
    assert (await auth_client.delete(f"/totes/{t['id']}")).status_code == 204

    got = (await auth_client.get(f"/items/{item['id']}")).json()
    assert got["current_tote_id"] is None
    assert got["tote_code"] is None


async def _second_user_token() -> str:
    """A second, unrelated account — for asserting isolation rather than assuming it."""
    from app.database import AsyncSessionLocal
    from app.models.user import User, UserSettings
    from app.security import create_access_token

    async with AsyncSessionLocal() as db:
        other = User(name="Other", email=f"o-{uuid.uuid4().hex[:8]}@example.com")
        db.add(other)
        await db.flush()
        db.add(UserSettings(user_id=other.id))
        await db.commit()
        return create_access_token(str(other.id))


async def test_another_users_tote_is_404_not_403(auth_client, client):
    """404 rather than 403 so an authenticated user cannot probe which ids exist, and cannot
    tell "not yours" apart from "does not exist"."""
    mine = await _tote(auth_client)
    headers = {"Authorization": f"Bearer {await _second_user_token()}"}

    assert (await client.get(f"/totes/{mine['id']}", headers=headers)).status_code == 404
    assert (
        await client.patch(f"/totes/{mine['id']}", json={"label": "x"}, headers=headers)
    ).status_code == 404
    assert (await client.delete(f"/totes/{mine['id']}", headers=headers)).status_code == 404
    # And an id that genuinely does not exist is indistinguishable from the above.
    assert (await auth_client.get(f"/totes/{uuid.uuid4()}")).status_code == 404


async def test_another_users_item_cannot_be_moved(auth_client, client):
    """The movement service is the only writer of whereabouts, so this is the endpoint that
    would let someone else rearrange your attic."""
    t = await _tote(auth_client)
    item = await _item(auth_client, tote_id=t["id"])
    headers = {"Authorization": f"Bearer {await _second_user_token()}"}
    r = await client.post(f"/items/{item['id']}/move", json={"reason": "unpacked"}, headers=headers)
    assert r.status_code == 404
    assert (await auth_client.get(f"/items/{item['id']}")).json()["status"] == "stored"


# ── The ledger and derived state ─────────────────────────────────────────────


async def test_filing_an_item_writes_an_initial_movement(auth_client):
    """An item that appeared in a bin with no history would be the first hole in the ledger."""
    t = await _tote(auth_client)
    item = await _item(auth_client, tote_id=t["id"])

    moves = (await auth_client.get(f"/items/{item['id']}/movements")).json()
    assert len(moves) == 1
    assert moves[0]["reason"] == "initial"
    assert moves[0]["from_tote_id"] is None
    assert moves[0]["to_tote_id"] == t["id"]


async def test_the_invariant_holds_across_every_reason(auth_client):
    """current_tote_id is NOT NULL <=> status == 'stored'.

    Asserted for every reason rather than a sample, because the contradiction it prevents is
    invisible: an item both in a bin and lent out renders fine, and is only wrong in the attic.
    """
    t = await _tote(auth_client)
    for reason, expect_stored in (
        ("unpacked", False),
        ("repacked", True),
        ("loaned", False),
        ("returned", True),
        ("outgrown", False),
        ("moved", True),
    ):
        item = await _item(auth_client, f"item-{reason}", tote_id=t["id"])
        body = {"reason": reason}
        if expect_stored:
            body["to_tote_id"] = t["id"]
        r = await auth_client.post(f"/items/{item['id']}/move", json=body)
        assert r.status_code == 200, r.text

        got = (await auth_client.get(f"/items/{item['id']}")).json()
        if expect_stored:
            assert got["status"] == "stored" and got["current_tote_id"] == t["id"], reason
        else:
            assert got["status"] != "stored" and got["current_tote_id"] is None, reason


async def test_an_inbound_reason_without_a_destination_is_422(auth_client):
    t = await _tote(auth_client)
    item = await _item(auth_client, tote_id=t["id"])
    r = await auth_client.post(f"/items/{item['id']}/move", json={"reason": "moved"})
    assert r.status_code == 422


async def test_an_outbound_reason_with_a_destination_is_422(auth_client):
    """ "Lent to Dave, into bin A14" is a contradiction, not a shorthand."""
    t = await _tote(auth_client)
    item = await _item(auth_client, tote_id=t["id"])
    r = await auth_client.post(
        f"/items/{item['id']}/move", json={"reason": "loaned", "to_tote_id": t["id"]}
    )
    assert r.status_code == 422


async def test_patching_an_item_cannot_change_its_whereabouts(auth_client):
    """Whereabouts has exactly one writer. If PATCH could set it, the ledger would have holes
    exactly where someone took a shortcut."""
    t = await _tote(auth_client)
    item = await _item(auth_client, tote_id=t["id"])
    r = await auth_client.patch(
        f"/items/{item['id']}",
        json={"name": "Renamed", "current_tote_id": None, "status": "loaned"},
    )
    assert r.status_code == 200
    assert r.json()["name"] == "Renamed"
    # The unknown keys were ignored, not applied.
    assert r.json()["status"] == "stored"
    assert r.json()["current_tote_id"] == t["id"]


async def test_movements_are_ordered_newest_first(auth_client):
    t1 = await _tote(auth_client, "A01")
    t2 = await _tote(auth_client, "A02")
    item = await _item(auth_client, tote_id=t1["id"])
    await auth_client.post(f"/items/{item['id']}/move", json={"reason": "unpacked"})
    await auth_client.post(
        f"/items/{item['id']}/move", json={"reason": "moved", "to_tote_id": t2["id"]}
    )
    moves = (await auth_client.get(f"/items/{item['id']}/movements")).json()
    assert [m["reason"] for m in moves] == ["moved", "unpacked", "initial"]


# ── Unpack / repack ──────────────────────────────────────────────────────────


async def test_unpack_then_repack_round_trips(auth_client):
    """What the holidays actually look like."""
    t = await _tote(auth_client)
    names = ("Lights", "Tree stand", "Ornaments")
    for n in names:
        await _item(auth_client, n, tote_id=t["id"])

    moves = (await auth_client.post(f"/totes/{t['id']}/unpack", json={})).json()
    assert len(moves) == 3
    detail = (await auth_client.get(f"/totes/{t['id']}")).json()
    assert detail["item_count"] == 0
    # The gap is shown, not hidden - this is the answer to "I thought the lights were in here".
    assert {i["name"] for i in detail["items_out"]} == set(names)
    assert detail["out_count"] == 3

    moves = (await auth_client.post(f"/totes/{t['id']}/repack", json={})).json()
    assert len(moves) == 3
    detail = (await auth_client.get(f"/totes/{t['id']}")).json()
    assert detail["item_count"] == 3
    assert detail["items_out"] == []


async def test_repack_only_takes_back_what_came_out_of_this_tote(auth_client):
    """A naive "all items with no tote" query would sweep up the whole house — including things
    that are lent out or belong to a different bin entirely."""
    a = await _tote(auth_client, "A01")
    b = await _tote(auth_client, "B01")
    mine = await _item(auth_client, "From A", tote_id=a["id"])
    theirs = await _item(auth_client, "From B", tote_id=b["id"])

    await auth_client.post(f"/totes/{a['id']}/unpack", json={})
    await auth_client.post(f"/totes/{b['id']}/unpack", json={})

    moves = (await auth_client.post(f"/totes/{a['id']}/repack", json={})).json()
    assert len(moves) == 1

    assert (await auth_client.get(f"/items/{mine['id']}")).json()["current_tote_id"] == a["id"]
    assert (await auth_client.get(f"/items/{theirs['id']}")).json()["current_tote_id"] is None


async def test_an_empty_selection_unpacks_nothing(auth_client):
    """`null` means "everything"; `[]` is an explicit selection of nothing. Conflating them
    would let a UI bug empty a whole bin."""
    t = await _tote(auth_client)
    await _item(auth_client, tote_id=t["id"])
    moves = (await auth_client.post(f"/totes/{t['id']}/unpack", json={"item_ids": []})).json()
    assert moves == []
    assert (await auth_client.get(f"/totes/{t['id']}")).json()["item_count"] == 1


async def test_partial_unpack_leaves_the_rest_alone(auth_client):
    t = await _tote(auth_client)
    keep = await _item(auth_client, "Keep", tote_id=t["id"])
    take = await _item(auth_client, "Take", tote_id=t["id"])
    moves = (
        await auth_client.post(f"/totes/{t['id']}/unpack", json={"item_ids": [take["id"]]})
    ).json()
    assert len(moves) == 1
    detail = (await auth_client.get(f"/totes/{t['id']}")).json()
    assert [i["id"] for i in detail["items"]] == [keep["id"]]


async def test_a_tote_carries_the_name_of_the_place_it_is_in(auth_client):
    """ "A14" is half an answer. The place it is in is the other half, and it comes on every
    read path — list, detail and patch — so no screen has to hold a locations table alongside
    the bins just to render one line."""
    loc = (await auth_client.post("/locations", json={"name": "Attic"})).json()
    t = await _tote(auth_client, "A14", location_id=loc["id"])
    assert t["location_name"] == "Attic"

    listed = (await auth_client.get("/totes")).json()
    assert listed[0]["location_name"] == "Attic"
    assert (await auth_client.get(f"/totes/{t['id']}")).json()["location_name"] == "Attic"

    moved = (await auth_client.patch(f"/totes/{t['id']}", json={"location_id": None})).json()
    # A bin with no place says so with a null rather than a stale name — "Attic" left behind
    # on a bin that moved to the garage is worse than no answer at all.
    assert moved["location_name"] is None


async def test_a_bin_with_no_location_is_not_an_error(auth_client):
    t = await _tote(auth_client, "B02")
    assert t["location_name"] is None


# ── Search ───────────────────────────────────────────────────────────────────


async def test_search_finds_an_item_and_tells_you_which_bin_and_where(auth_client):
    """The whole point of the app in one assertion: a hit carries its bin and its location, so
    the answer is one request rather than three."""
    r = await auth_client.post("/locations", json={"name": "Attic"})
    loc = r.json()
    t = await _tote(auth_client, "A14", location_id=loc["id"])
    await _item(auth_client, "Ratchet set", tote_id=t["id"], description="3/8 inch drive")

    hits = (await auth_client.get("/search", params={"q": "ratchet"})).json()
    assert len(hits) == 1
    assert hits[0]["item"]["tote_code"] == "A14"
    assert hits[0]["item"]["location_name"] == "Attic"


async def test_search_covers_notes_as_well_as_the_name(auth_client):
    t = await _tote(auth_client)
    await _item(auth_client, "Blue box", tote_id=t["id"], notes="the good soldering iron")
    hits = (await auth_client.get("/search", params={"q": "soldering"})).json()
    assert len(hits) == 1


async def test_search_with_no_matches_is_an_empty_list_not_an_error(auth_client):
    """ "No results" is an answer. An error here would read as the app being broken."""
    r = await auth_client.get("/search", params={"q": "nothing-matches-this"})
    assert r.status_code == 200
    assert r.json() == []


async def test_search_survives_punctuation_a_person_would_actually_type(auth_client):
    """websearch_to_tsquery rather than plainto_tsquery: quotes and stray punctuation are what
    people type, and throwing a 500 at them is not an option."""
    t = await _tote(auth_client)
    await _item(auth_client, "Ratchet set", tote_id=t["id"])
    for q in ('"ratchet set"', "ratchet or wrench", "ratchet!", "ratchet -wrench"):
        r = await auth_client.get("/search", params={"q": q})
        assert r.status_code == 200, f"{q!r} -> {r.text}"


async def test_search_does_not_leak_another_users_items(auth_client, client):
    t = await _tote(auth_client, "A14")
    await _item(auth_client, "Very distinctive doohickey", tote_id=t["id"])

    token = await _second_user_token()
    r = await client.get(
        "/search",
        params={"q": "doohickey"},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert r.json() == []


# ── Overdue ──────────────────────────────────────────────────────────────────


async def test_overdue_is_computed_server_side(auth_client):
    """So a screen and a notification cannot disagree about what "overdue" means."""
    import datetime

    from app.services.catalog import local_today

    t = await _tote(auth_client)
    item = await _item(auth_client, "Drill", tote_id=t["id"])
    yesterday = (local_today() - datetime.timedelta(days=1)).isoformat()
    await auth_client.post(
        f"/items/{item['id']}/move",
        json={"reason": "loaned", "expected_back": yesterday},
    )
    got = (await auth_client.get(f"/items/{item['id']}")).json()
    assert got["status"] == "loaned"
    assert got["is_overdue"] is True


async def test_an_item_in_a_tote_is_never_overdue(auth_client):
    import datetime

    from app.services.catalog import local_today

    t = await _tote(auth_client)
    item = await _item(auth_client, "Drill", tote_id=t["id"])
    yesterday = (local_today() - datetime.timedelta(days=1)).isoformat()
    await auth_client.post(
        f"/items/{item['id']}/move", json={"reason": "loaned", "expected_back": yesterday}
    )
    await auth_client.post(
        f"/items/{item['id']}/move", json={"reason": "returned", "to_tote_id": t["id"]}
    )
    got = (await auth_client.get(f"/items/{item['id']}")).json()
    assert got["is_overdue"] is False
    assert got["expected_back"] is None


async def test_overdue_uses_the_household_timezone_not_the_containers(auth_client, monkeypatch):
    """The container runs UTC; the house does not.

    Without an explicit zone, an item due today is reported overdue from 7pm local — visibly
    wrong to the person holding the phone, and exactly the class of bug that fails in
    US/Eastern while passing in CI's UTC.
    """
    import datetime

    from app.config import settings
    from app.services.catalog import local_today

    monkeypatch.setattr(settings, "local_timezone", "Pacific/Kiritimati")  # UTC+14
    ahead = local_today()
    monkeypatch.setattr(settings, "local_timezone", "Pacific/Midway")  # UTC-11
    behind = local_today()

    # STRICTLY later, not "exactly one day later". These zones are 25 hours apart, so their
    # local dates differ by one day for 23 hours out of every 24 and by TWO for the remaining
    # hour. An `== 1` assertion therefore passes locally almost always and fails in a one-hour
    # window each day -- which is exactly what it did on CI, at 10:03 UTC, having passed here.
    # The invariant that actually matters is that the configured zone changes the answer at all.
    assert ahead > behind, "the configured zone is not being honoured"

    # An unknown zone must degrade to UTC rather than 500 the endpoint.
    monkeypatch.setattr(settings, "local_timezone", "Not/AZone")
    assert local_today() == datetime.datetime.now(datetime.UTC).date()
