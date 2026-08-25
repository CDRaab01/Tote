"""Search upgrades: the size filter on /search, the close-match fallback, and bin colours.

Three properties matter here. The size filter is the SAME filter `/items?size=` uses — one
implementation, so the two paths can never disagree about what "4T" reaches. The trigram
fallback runs only when the full-text pass found nothing and labels everything it returns,
because a near-miss presented as a real match quietly teaches people that search lies. And the
bin colour rides every hit as a resolved hex, so the swatch a person matches to a physical bin
by sight is painted from one mapping rather than re-guessed per screen.
"""


async def _tote(c, code="A14", **kw):
    r = await c.post("/totes", json={"code": code, **kw})
    assert r.status_code == 201, r.text
    return r.json()


async def _item(c, name, tote_id=None, **kw):
    r = await c.post("/items", json={"name": name, "tote_id": tote_id, **kw})
    assert r.status_code == 201, r.text
    return r.json()


async def _sized(c, name, tote_id, size_raw, department=None):
    item = await _item(c, name, tote_id=tote_id)
    r = await c.patch(
        f"/items/{item['id']}", json={"apparel": {"size_raw": size_raw, "department": department}}
    )
    assert r.status_code == 200, r.text
    return r.json()


# ── The size filter on /search ───────────────────────────────────────────────


async def test_search_narrows_by_size_on_the_ordinal_not_the_string(auth_client):
    """ "4T winter coat" is one question in the person's head. The filter matches the LADDER —
    "4T" also finds a garment whose tag read "4" under a girls department — and an incomparable
    lineage never matches however close the numbers land on the shared axis."""
    t = await _tote(auth_client, "S01")
    await _sized(auth_client, "Winter coat", t["id"], "4T")
    await _sized(auth_client, "Winter dress", t["id"], "4", department="girls")
    await _sized(auth_client, "Winter jeans", t["id"], "32x30")
    await _item(auth_client, "Winter sled", tote_id=t["id"])

    everything = (await auth_client.get("/search", params={"q": "winter"})).json()
    assert len(everything) == 4

    hits = (await auth_client.get("/search", params={"q": "winter", "size": "4T"})).json()
    names = {h["item"]["name"] for h in hits}
    assert names == {"Winter coat", "Winter dress"}
    # The men's waist is out of lineage, and the sled has no apparel row at all — the size
    # filter is an inner join, so it is absent rather than swept in by a null.
    assert "Winter jeans" not in names
    assert "Winter sled" not in names


# ── The close-match fallback ─────────────────────────────────────────────────


async def test_a_typo_stays_empty_unless_the_caller_opts_into_close_matches(auth_client):
    t = await _tote(auth_client, "S02")
    await _item(auth_client, "Sleepsuit", tote_id=t["id"])

    r = await auth_client.get("/search", params={"q": "sleepsiut"})
    assert r.status_code == 200
    assert r.json() == []


async def test_include_close_rescues_a_transposed_typo_and_labels_it(auth_client):
    """ "sleepsiut" finds the Sleepsuit — labelled close_match, with the trigram similarity as
    its rank, so the client can say "did you mean" rather than presenting a guess as a
    result. The colour swatch rides the fallback hit exactly as it rides a real one."""
    t = await _tote(auth_client, "S03", color="green")
    await _item(auth_client, "Sleepsuit", tote_id=t["id"])

    r = await auth_client.get("/search", params={"q": "sleepsiut", "include_close": True})
    hits = r.json()
    assert [h["item"]["name"] for h in hits] == ["Sleepsuit"]
    assert hits[0]["close_match"] is True
    # Past the 0.25 floor, below an exact match's certainty.
    assert 0.25 < hits[0]["rank"] < 1.0
    assert hits[0]["item"]["tote_color_hex"] == "#2A5240"


async def test_a_typo_the_stemmer_already_forgives_is_a_real_match_not_a_close_one(auth_client):
    """ "sleepsuite" never reaches the fallback: english stemming maps it onto "sleepsuit", so
    the full-text pass matches it outright and the hit is NOT labelled. The fallback exists
    for the typos stemming cannot absorb — a transposed pair, not a spare vowel."""
    t = await _tote(auth_client, "S04")
    await _item(auth_client, "Sleepsuit", tote_id=t["id"])

    r = await auth_client.get("/search", params={"q": "sleepsuite", "include_close": True})
    hits = r.json()
    assert [h["item"]["name"] for h in hits] == ["Sleepsuit"]
    assert hits[0]["close_match"] is False


async def test_a_real_match_is_never_diluted_by_close_ones(auth_client):
    """When full-text finds anything the fallback does not run at all: the similarly-named item
    stays off the page entirely, and the real hit is not labelled close."""
    t = await _tote(auth_client, "S05")
    await _item(auth_client, "Hammer", tote_id=t["id"])
    # Trigram-close to "hammer" but no full-text match — exactly the row that would dilute the
    # result if the fallback ever ran alongside real hits.
    await _item(auth_client, "Hammor", tote_id=t["id"])

    r = await auth_client.get("/search", params={"q": "hammer", "include_close": True})
    hits = r.json()
    assert [h["item"]["name"] for h in hits] == ["Hammer"]
    assert hits[0]["close_match"] is False


async def test_the_fallback_reads_descriptions_as_well_as_names(auth_client):
    t = await _tote(auth_client, "S06")
    await _item(auth_client, "Blue box", tote_id=t["id"], description="sleepsuit")

    r = await auth_client.get("/search", params={"q": "sleepsiut", "include_close": True})
    assert [h["item"]["name"] for h in r.json()] == ["Blue box"]


async def test_the_fallback_respects_the_size_filter(auth_client):
    """The trigram pass reuses the same base query, size filter included — a close match in the
    wrong size is still the wrong garment."""
    t = await _tote(auth_client, "S07")
    await _sized(auth_client, "Sleepsuit", t["id"], "4T")

    found = (
        await auth_client.get(
            "/search", params={"q": "sleepsiut", "include_close": True, "size": "4T"}
        )
    ).json()
    assert [h["item"]["name"] for h in found] == ["Sleepsuit"]
    assert found[0]["close_match"] is True

    wrong_size = (
        await auth_client.get(
            "/search", params={"q": "sleepsiut", "include_close": True, "size": "32x30"}
        )
    ).json()
    assert wrong_size == []


async def test_close_matches_never_cross_a_household(auth_client, other_client):
    """The trigram pass is scoped exactly like the full-text one: someone else's Sleepsuit is
    not a close match, it is invisible."""
    t = await _tote(other_client, "S08")
    await _item(other_client, "Sleepsuit", tote_id=t["id"])

    r = await auth_client.get("/search", params={"q": "sleepsiut", "include_close": True})
    assert r.status_code == 200
    assert r.json() == []


# ── Bin colours on the read paths ────────────────────────────────────────────


async def test_search_hits_carry_the_bin_colour_as_a_paintable_hex(auth_client):
    green = await _tote(auth_client, "S09", color="green")
    plain = await _tote(auth_client, "S10")
    odd = await _tote(auth_client, "S11", color="taupe-ish")
    await _item(auth_client, "Lantern one", tote_id=green["id"])
    await _item(auth_client, "Lantern two", tote_id=plain["id"])
    await _item(auth_client, "Lantern three", tote_id=odd["id"])

    hits = (await auth_client.get("/search", params={"q": "lantern"})).json()
    by_code = {h["item"]["tote_code"]: h["item"]["tote_color_hex"] for h in hits}
    assert by_code["S09"] == "#2A5240"
    # Unset and unrecognised both read as null — the client falls back to its neutral swatch,
    # because a wrong colour sends someone to the wrong bin with confidence.
    assert by_code["S10"] is None
    assert by_code["S11"] is None


async def test_item_rows_carry_the_bin_colour_too(auth_client):
    green = await _tote(auth_client, "S12", color="green")
    plain = await _tote(auth_client, "S13")
    await _item(auth_client, "Garland", tote_id=green["id"])
    await _item(auth_client, "Tinsel", tote_id=plain["id"])

    rows = (await auth_client.get("/items")).json()
    by_name = {r["name"]: r["tote_color_hex"] for r in rows}
    assert by_name["Garland"] == "#2A5240"
    assert by_name["Tinsel"] is None


async def test_the_tote_itself_reports_its_resolved_colour(auth_client):
    """`ToteOut.color_hex` comes from the same mapping the hits use, so the swatch on a bin row
    and the swatch on its items can never disagree. First known colour word wins."""
    await _tote(auth_client, "S14", color="dark green lid")
    await _tote(auth_client, "S15", color="27gal")

    totes = (await auth_client.get("/totes")).json()
    by_code = {t["code"]: t["color_hex"] for t in totes}
    assert by_code["S14"] == "#2A5240"
    assert by_code["S15"] is None
