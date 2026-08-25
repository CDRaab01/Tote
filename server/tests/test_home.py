"""GET /home — the two volunteered cards, and the honesty rules that keep them quiet.

Both cards must refuse to render rather than stretch: the seasonal card speaks only from the
ledger's own `unpacked` rows, the next-size card only from recorded sizes and the ladder. Most
of these tests are therefore about staying NULL — a card that fires a season early, or counts a
lent-out garment, is the kind of wrong that teaches someone to ignore the screen.
"""

import datetime

from app.services.catalog import local_today

# The HOUSEHOLD's today, not the process's — same rule as test_people.py: the server windows on
# `local_today()` and the container runs UTC.
TODAY = local_today()


async def _tote(c, code="A14", **kw):
    r = await c.post("/totes", json={"code": code, **kw})
    assert r.status_code == 201, r.text
    return r.json()


async def _item(c, name="Lights", tote_id=None, **kw):
    r = await c.post("/items", json={"name": name, "tote_id": tote_id, **kw})
    assert r.status_code == 201, r.text
    return r.json()


async def _person(c, name="Emma", **kw):
    r = await c.post("/people", json={"name": name, **kw})
    assert r.status_code == 201, r.text
    return r.json()


async def _size(c, person_id, size_raw, garment_type="tops"):
    r = await c.post(
        f"/people/{person_id}/sizes", json={"garment_type": garment_type, "size_raw": size_raw}
    )
    assert r.status_code == 201, r.text
    return r.json()


async def _garment(c, tote_id, name, size_raw, department=None):
    item = await _item(c, name, tote_id=tote_id)
    r = await c.patch(
        f"/items/{item['id']}",
        json={"apparel": {"size_raw": size_raw, "department": department}},
    )
    assert r.status_code == 200, r.text
    return item


async def _unpacked_last_year(c, raw_sql, tote_id, days_ago=360, repack=True):
    """Unpack the bin through the API, then push the ledger rows back in time.

    The API cannot backdate a bulk unpack (the ledger is append-only and `moved_at` is the
    server's), so the test reaches under it with raw SQL. Noon UTC, so the row lands on the
    same DATE whatever hour the suite runs at.
    """
    r = await c.post(f"/totes/{tote_id}/unpack", json={})
    assert r.status_code == 200, r.text
    if repack:
        # Back in the bin, as they would be by January: the card is about THIS year's contents.
        # BEFORE the backdate, because repack finds its items by their latest movement — pushed
        # into the past, the unpacked row loses that race to the `initial` row.
        r = await c.post(f"/totes/{tote_id}/repack", json={})
        assert r.status_code == 200, r.text
    ts = datetime.datetime.combine(
        TODAY - datetime.timedelta(days=days_ago), datetime.time(12), tzinfo=datetime.UTC
    )
    await raw_sql(
        "UPDATE movements SET moved_at = :ts WHERE from_tote_id = :t AND reason = 'unpacked'",
        ts=ts,
        t=tote_id,
    )


async def _home(c):
    r = await c.get("/home")
    assert r.status_code == 200, r.text
    return r.json()


# ── Nothing to say ─────────────────────────────────────────────────────────────────────────


async def test_an_empty_catalogue_volunteers_nothing(auth_client):
    assert await _home(auth_client) == {"seasonal": None, "next_size": None}


# ── The seasonal card ──────────────────────────────────────────────────────────────────────


async def test_last_years_unpack_fires_the_seasonal_card(auth_client, raw_sql):
    loc = (await auth_client.post("/locations", json={"name": "Attic"})).json()
    cat = (await auth_client.post("/categories", json={"name": "Christmas"})).json()
    t = await _tote(auth_client, "A14", location_id=loc["id"], category_id=cat["id"], color="green")
    for name in ("Lights", "Tree stand", "Star"):
        await _item(auth_client, name, tote_id=t["id"])
    await _unpacked_last_year(auth_client, raw_sql, t["id"], days_ago=360)

    card = (await _home(auth_client))["seasonal"]
    assert card is not None
    assert [b["code"] for b in card["totes"]] == ["A14"]
    # The bin glyph's colour comes from the one shared mapping (services/colors.py).
    assert card["totes"][0]["color_hex"] == "#2A5240"
    assert card["location_name"] == "Attic"
    assert card["category_name"] == "Christmas"
    assert card["item_count"] == 3
    # The EARLIEST qualifying date — when the unpacking started last year.
    assert card["unpacked_on"] == str(TODAY - datetime.timedelta(days=360))


async def test_an_unpack_thirteen_months_ago_stays_quiet(auth_client, raw_sql):
    """The window opens a year back. Anything older belongs to a season further away than the
    card's promise, and firing on it would teach the user the card is noise."""
    t = await _tote(auth_client)
    await _item(auth_client, tote_id=t["id"])
    await _unpacked_last_year(auth_client, raw_sql, t["id"], days_ago=395)
    assert (await _home(auth_client))["seasonal"] is None


async def test_an_unpack_ten_months_ago_is_still_in_the_window(auth_client, raw_sql):
    """The window runs 8 weeks FORWARD of a year ago — [today-365d, today-309d] — so an unpack
    ~10 months back (310 days) still qualifies: what was unpacked a little late last year is
    heralded a little late this year."""
    t = await _tote(auth_client)
    await _item(auth_client, tote_id=t["id"])
    await _unpacked_last_year(auth_client, raw_sql, t["id"], days_ago=310)
    assert (await _home(auth_client))["seasonal"] is not None


async def test_an_archived_bin_never_fires_the_card(auth_client, raw_sql):
    """Archiving is how a bin leaves service; a card pointing at one would send someone to the
    attic for a bin that is deliberately out of play."""
    t = await _tote(auth_client)
    await _item(auth_client, tote_id=t["id"])
    await _unpacked_last_year(auth_client, raw_sql, t["id"])
    r = await auth_client.patch(f"/totes/{t['id']}", json={"archived": True})
    assert r.status_code == 200, r.text
    assert (await _home(auth_client))["seasonal"] is None


async def test_bins_still_empty_since_last_year_are_not_worth_a_trip(auth_client, raw_sql):
    """Unpacked last November and never refilled: the qualifying bins hold nothing now, so there
    is nothing to go and get — the card requires at least one item currently stored."""
    t = await _tote(auth_client)
    await _item(auth_client, tote_id=t["id"])
    await _unpacked_last_year(auth_client, raw_sql, t["id"], repack=False)
    assert (await _home(auth_client))["seasonal"] is None


async def test_location_and_category_appear_only_when_every_bin_agrees(auth_client, raw_sql):
    loc = (await auth_client.post("/locations", json={"name": "Attic"})).json()
    cat = (await auth_client.post("/categories", json={"name": "Christmas"})).json()
    for code in ("A14", "B02"):
        t = await _tote(auth_client, code, location_id=loc["id"], category_id=cat["id"])
        await _item(auth_client, f"Thing {code}", tote_id=t["id"])
        await _unpacked_last_year(auth_client, raw_sql, t["id"])

    card = (await _home(auth_client))["seasonal"]
    assert [b["code"] for b in card["totes"]] == ["A14", "B02"]
    assert card["location_name"] == "Attic"
    assert card["category_name"] == "Christmas"
    assert card["item_count"] == 2


async def test_a_bin_with_no_location_breaks_the_agreement(auth_client, raw_sql):
    """Nulls count as disagreement, for the location and the category alike: "all in the attic,
    except the one nobody placed" is not a sentence to say with confidence."""
    loc = (await auth_client.post("/locations", json={"name": "Attic"})).json()
    cat = (await auth_client.post("/categories", json={"name": "Christmas"})).json()
    placed = await _tote(auth_client, "A14", location_id=loc["id"], category_id=cat["id"])
    loose = await _tote(auth_client, "B02")  # no location, no category
    for t in (placed, loose):
        await _item(auth_client, f"Thing {t['code']}", tote_id=t["id"])
        await _unpacked_last_year(auth_client, raw_sql, t["id"])

    card = (await _home(auth_client))["seasonal"]
    assert [b["code"] for b in card["totes"]] == ["A14", "B02"]
    assert card["location_name"] is None
    assert card["category_name"] is None


# ── The next-size card ─────────────────────────────────────────────────────────────────────


async def test_next_size_counts_the_stored_garments_one_rung_up(auth_client):
    person = await _person(auth_client, "Emma")
    await _size(auth_client, person["id"], "9-12M")
    t1 = await _tote(auth_client, "A14")
    t2 = await _tote(auth_client, "B02")
    await _garment(auth_client, t1["id"], "Sleepsuit", "12-18M")
    await _garment(auth_client, t1["id"], "Vest", "12-18M")
    await _garment(auth_client, t2["id"], "Dungarees", "12-18M")
    # Out of the band entirely — must be neither counted nor referenced.
    await _garment(auth_client, t2["id"], "Coat", "4T")

    card = (await _home(auth_client))["next_size"]
    assert card is not None
    assert card["person_id"] == person["id"]
    assert card["person_name"] == "Emma"
    # The ladder's rung above 9-12M (0.875) is 12M (1.0) — that is the label, always a rung of
    # the ladder and never a tag's words. The 12-18M tags index to the 15M rung (1.25), inside
    # the half-rung tolerance, so they are exactly what the card counts.
    assert card["next_label"] == "12M"
    assert card["garment_count"] == 3
    # Bin references, most garments first, so the card says where to go.
    assert [b["code"] for b in card["totes"]] == ["A14", "B02"]


async def test_only_garments_actually_in_a_bin_count(auth_client):
    """UNLIKE fits — which deliberately reports garments wherever they are — the card promises
    "already waiting in a bin". Lent, taken-out and disposed-of garments are not."""
    person = await _person(auth_client)
    await _size(auth_client, person["id"], "9-12M")
    t = await _tote(auth_client)
    await _garment(auth_client, t["id"], "Sleepsuit", "12-18M")  # stays stored
    lent = await _garment(auth_client, t["id"], "Vest", "12-18M")
    out = await _garment(auth_client, t["id"], "Dungarees", "12-18M")
    gone = await _garment(auth_client, t["id"], "Romper", "12-18M")
    for item, body in (
        (lent, {"reason": "loaned", "person_id": person["id"]}),
        (out, {"reason": "unpacked"}),
        (gone, {"reason": "disposed"}),
    ):
        r = await auth_client.post(f"/items/{item['id']}/move", json=body)
        assert r.status_code == 200, r.text

    card = (await _home(auth_client))["next_size"]
    assert card["garment_count"] == 1
    assert [b["code"] for b in card["totes"]] == ["A14"]


async def test_a_reading_in_a_non_comparable_system_never_counts(auth_client, raw_sql):
    """The comparability gate, isolated. No real tag in a non-comparable system can land within
    half a rung of an infant size — the axis puts shoes at +100 and adults at 17-plus — so the
    derived index is forced to the exact collision the gate exists to prevent: a shoe reading
    sitting on the shared axis right where 12M lives. `_SYSTEMS_FOR_GARMENT` must refuse it."""
    person = await _person(auth_client)
    await _size(auth_client, person["id"], "9-12M")
    t = await _tote(auth_client)
    shoe = await _garment(auth_client, t["id"], "Trainers", "4T")  # placeholder reading
    await raw_sql(
        "UPDATE item_apparel SET size_system = 'shoe_us_child', size_ordinal = 1.0 "
        "WHERE item_id = :i",
        i=shoe["id"],
    )
    assert (await _home(auth_client))["next_size"] is None


async def test_an_unparseable_size_never_becomes_a_guess(auth_client):
    """ "5TT" does not parse, so the person has no rung to climb from — and the ladder never
    invents one, however many next-size garments sit in bins."""
    person = await _person(auth_client)
    await _size(auth_client, person["id"], "5TT")
    t = await _tote(auth_client)
    await _garment(auth_client, t["id"], "Sleepsuit", "12-18M")
    assert (await _home(auth_client))["next_size"] is None


async def test_the_person_with_more_garments_waiting_wins(auth_client):
    emma = await _person(auth_client, "Emma")
    await _size(auth_client, emma["id"], "9-12M")
    zed = await _person(auth_client, "Zed")
    await _size(auth_client, zed["id"], "4T")
    t = await _tote(auth_client)
    for name in ("Sleepsuit", "Vest"):
        await _garment(auth_client, t["id"], name, "12-18M")  # Emma's next band: 2 garments
    for name in ("Coat", "Jumper", "Dungarees"):
        await _garment(auth_client, t["id"], name, "5T")  # Zed's next band: 3 garments

    card = (await _home(auth_client))["next_size"]
    assert card["person_name"] == "Zed"
    assert card["next_label"] == "5T"
    assert card["garment_count"] == 3


async def test_the_clothes_they_wear_today_are_not_the_next_size(auth_client):
    """The half-rung tolerance around the next rung reaches BELOW the wearer's current size
    (9-12M sits 0.125 under 12M), and a card that counts the clothes already on the person's
    back as "waiting in the next size" advertises a bin trip for nothing. The band is open
    below the wearer's own ordinal — current and outgrown sizes never count."""
    person = await _person(auth_client)
    await _size(auth_client, person["id"], "9-12M")
    t = await _tote(auth_client)
    await _garment(auth_client, t["id"], "Sleepsuit", "12-18M")
    await _garment(auth_client, t["id"], "Vest", "12-18M")
    # Stored right beside them: the size they wear now, and one they have outgrown. Both sit
    # inside a naive symmetric band around 12M (1.0); neither is a reason to open the bin.
    await _garment(auth_client, t["id"], "Bodysuit they wear now", "9-12M")
    await _garment(auth_client, t["id"], "Outgrown bodysuit", "6-9M")

    card = (await _home(auth_client))["next_size"]
    assert card is not None
    assert card["next_label"] == "12M"
    assert card["garment_count"] == 2
