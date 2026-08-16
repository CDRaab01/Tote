"""The apparel write path, over HTTP.

The property under test is the **write-path asymmetry** the whole module is built around: vision
output degrades to null, a human's PATCH of the same field rejects with a 422. A model that
half-read a tag should cost a null; a person who typed something is making a claim, and silently
storing an unrecognised claim is how a filter quietly stops matching.
"""

TOTE = {"code": "C01", "label": "Kids clothes"}


async def _item(client, name="Winter coat"):
    tote = (await client.post("/totes", json=TOTE)).json()
    return (await client.post("/items", json={"name": name, "tote_id": tote["id"]})).json()


async def test_an_item_without_clothing_specifics_reports_apparel_null(auth_client):
    """Absent is the normal case — most things in a house are not garments — and it must not
    look like an empty form someone forgot to fill in."""
    item = await _item(auth_client, "Ratchet set")
    assert item["apparel"] is None


async def test_patching_size_raw_derives_the_index(auth_client):
    item = await _item(auth_client)
    r = await auth_client.patch(
        f"/items/{item['id']}", json={"apparel": {"size_raw": "4T", "material": "Fleece"}}
    )
    assert r.status_code == 200
    apparel = r.json()["apparel"]
    assert apparel["size_raw"] == "4T"
    assert apparel["size_system"] == "toddler"
    assert apparel["size_ordinal"] == 4.0
    assert apparel["size_type"] == "toddler"
    assert apparel["material"] == "Fleece"


async def test_an_unparseable_size_is_stored_raw_with_a_null_index(auth_client):
    """The designed outcome. The reading survives; only the derived index is absent."""
    item = await _item(auth_client)
    r = await auth_client.patch(f"/items/{item['id']}", json={"apparel": {"size_raw": "M/L"}})
    apparel = r.json()["apparel"]
    assert apparel["size_raw"] == "M/L"
    assert apparel["size_system"] is None
    assert apparel["size_ordinal"] is None


async def test_the_department_disambiguates_a_bare_number_over_http(auth_client):
    item = await _item(auth_client)
    r = await auth_client.patch(
        f"/items/{item['id']}", json={"apparel": {"size_raw": "8", "department": "girls"}}
    )
    assert r.json()["apparel"]["size_system"] == "youth_numeric"


async def test_clearing_size_raw_clears_the_index_with_it(auth_client):
    """A stale ordinal pointing at a size nobody can see any more is worse than no ordinal."""
    item = await _item(auth_client)
    await auth_client.patch(f"/items/{item['id']}", json={"apparel": {"size_raw": "4T"}})
    r = await auth_client.patch(f"/items/{item['id']}", json={"apparel": {"size_raw": None}})
    apparel = r.json()["apparel"]
    assert apparel["size_raw"] is None
    assert apparel["size_system"] is None
    assert apparel["size_ordinal"] is None


async def test_a_client_cannot_set_the_index_directly(auth_client):
    """`size_system`/`size_ordinal` are not on ApparelPatch at all.

    If they were, a client could store "4T" indexed as an adult L and nothing downstream would
    ever catch the disagreement. Unknown keys are ignored by the model, so the derived values
    stay derived.
    """
    item = await _item(auth_client)
    r = await auth_client.patch(
        f"/items/{item['id']}",
        json={"apparel": {"size_raw": "4T", "size_system": "adult_alpha", "size_ordinal": 24.0}},
    )
    apparel = r.json()["apparel"]
    assert apparel["size_system"] == "toddler"
    assert apparel["size_ordinal"] == 4.0


async def test_a_hand_typed_unknown_enum_is_a_422(auth_client):
    """Strict where the vision path is forgiving — the asymmetry, over the wire."""
    item = await _item(auth_client)
    r = await auth_client.patch(
        f"/items/{item['id']}", json={"apparel": {"department": "spacesuit"}}
    )
    assert r.status_code == 422


async def test_a_hand_typed_enum_is_still_forgiving_about_shape(auth_client):
    """Forgiving on shape, strict on membership: "Women's" and "Girls" are the same claim."""
    item = await _item(auth_client)
    r = await auth_client.patch(
        f"/items/{item['id']}", json={"apparel": {"department": "Women's", "season": "All Season"}}
    )
    assert r.status_code == 200
    assert r.json()["apparel"]["department"] == "womens"
    assert r.json()["apparel"]["season"] == "all"


async def test_apparel_survives_a_round_trip_through_the_list_endpoint(auth_client):
    """The relationship is eager-loaded at the model, so every read path carries it — including
    the ones that never mention apparel."""
    item = await _item(auth_client)
    await auth_client.patch(f"/items/{item['id']}", json={"apparel": {"size_raw": "6X"}})

    listed = (await auth_client.get("/items")).json()
    assert listed[0]["apparel"]["size_raw"] == "6X"
    assert listed[0]["apparel"]["size_ordinal"] == 6.5

    hits = (await auth_client.get("/search", params={"q": "winter"})).json()
    assert hits[0]["item"]["apparel"]["size_system"] == "youth_numeric"
