"""People, fits, and lending — Phase 6's two questions, and the honesty rules around them.

    "what fits Emma right now"  and  "who has the drill"

The tests that matter most here are the ones about **not answering**. `fits` returning
`answered=false` is a different sentence from returning an empty list, and a client that renders
them the same tells someone "you own nothing that fits" when the truth is "nobody has recorded
her size". Only one of those is a reason to stop looking.
"""

import datetime

import pytest

from app.services.catalog import local_today
from app.services.ntfy import overdue_message

# The HOUSEHOLD's today, not the process's. The server compares `expected_back` against
# `local_today()` because the container runs UTC and the house does not — so a test using
# `date.today()` would disagree with the code under test for several hours a day, which is
# precisely the flake that would show up once and never reproduce.
TODAY = local_today()


async def _person(client, name="Emma", **kw):
    return (await client.post("/people", json={"name": name, **kw})).json()


async def _size(client, person_id, garment_type="tops", size_raw="4T", **kw):
    return (
        await client.post(
            f"/people/{person_id}/sizes",
            json={"garment_type": garment_type, "size_raw": size_raw, **kw},
        )
    ).json()


async def _garment(client, tote_id, name, size_raw, department=None):
    item = (await client.post("/items", json={"name": name, "tote_id": tote_id})).json()
    await client.patch(
        f"/items/{item['id']}",
        json={"apparel": {"size_raw": size_raw, "department": department}},
    )
    return item


# ── People ─────────────────────────────────────────────────────────────────────────────────


async def test_a_person_reports_the_sizes_in_effect_today(auth_client):
    person = await _person(auth_client)
    await _size(
        auth_client,
        person["id"],
        "tops",
        "3T",
        effective_from=str(TODAY - datetime.timedelta(days=400)),
    )
    await _size(
        auth_client,
        person["id"],
        "tops",
        "4T",
        effective_from=str(TODAY - datetime.timedelta(days=10)),
    )
    await _size(auth_client, person["id"], "shoes", "shoe 10", effective_from=str(TODAY))

    fetched = (await auth_client.get(f"/people/{person['id']}")).json()
    current = {s["garment_type"]: s["size_raw"] for s in fetched["current_sizes"]}
    # The newest per garment type wins; the older row is history, not noise.
    assert current == {"tops": "4T", "shoes": "shoe 10"}


async def test_two_readings_on_the_same_day_resolve_to_the_later_one(auth_client):
    """`effective_from` is a DATE, so two readings recorded on one day tie — and with no second
    sort key the winner was whatever Postgres returned first, which can differ between queries.

    Found in the owner's real data: `9 month` and the typo `9 moth` recorded for one person on
    one day. When the typo won, `fits` answered "cannot say" for that garment type while the good
    reading sat on the person's screen looking perfectly recorded — and it would flip back on the
    next request. Non-determinism reads as a haunted app, not a bug report.
    """
    person = await _person(auth_client, "Cedric")
    await _size(auth_client, person["id"], "tops", "9 month", effective_from=str(TODAY))
    await _size(auth_client, person["id"], "tops", "9 moth", effective_from=str(TODAY))

    # The typo was recorded second, so it is current — a later reading supersedes an earlier one
    # even on the same day, which is what "record it again to correct it" has to mean.
    current = (await auth_client.get(f"/people/{person['id']}")).json()["current_sizes"]
    tops = [s for s in current if s["garment_type"] == "tops"]
    assert len(tops) == 1
    assert tops[0]["size_raw"] == "9 moth"

    # And it is STABLE: the same answer every time, which is the actual property under test.
    for _ in range(5):
        again = (await auth_client.get(f"/people/{person['id']}")).json()["current_sizes"]
        assert [s["size_raw"] for s in again if s["garment_type"] == "tops"] == ["9 moth"]


async def test_the_good_reading_wins_when_it_is_the_later_one(auth_client):
    """The inverse, and the one that matters after somebody fixes a typo: delete-and-re-record is
    the sanctioned repair (sizes are deletable, never editable), so the repair must actually take
    effect on the same day it is made."""
    person = await _person(auth_client, "Cedric")
    await _size(auth_client, person["id"], "tops", "9 moth", effective_from=str(TODAY))
    await _size(auth_client, person["id"], "tops", "9 month", effective_from=str(TODAY))

    current = (await auth_client.get(f"/people/{person['id']}")).json()["current_sizes"]
    tops = next(s for s in current if s["garment_type"] == "tops")
    assert tops["size_raw"] == "9 month"
    assert tops["size_ordinal"] == pytest.approx(0.75)


async def test_a_future_size_does_not_become_current(auth_client):
    """Recording "she will be in a 5T in September" must not change what fits her in June."""
    person = await _person(auth_client)
    await _size(auth_client, person["id"], "tops", "4T", effective_from=str(TODAY))
    await _size(
        auth_client,
        person["id"],
        "tops",
        "5T",
        effective_from=str(TODAY + datetime.timedelta(days=90)),
    )

    fetched = (await auth_client.get(f"/people/{person['id']}")).json()
    assert [s["size_raw"] for s in fetched["current_sizes"]] == ["4T"]


async def test_sizes_accumulate_rather_than_overwrite(auth_client):
    """A history, not a value — last winter's answer is what tells you which bin to open."""
    person = await _person(auth_client)
    await _size(
        auth_client,
        person["id"],
        "tops",
        "3T",
        effective_from=str(TODAY - datetime.timedelta(days=365)),
    )
    await _size(auth_client, person["id"], "tops", "4T")

    history = (await auth_client.get(f"/people/{person['id']}/sizes")).json()
    assert [s["size_raw"] for s in history] == ["4T", "3T"]


async def test_a_persons_size_goes_on_the_same_ladder_as_a_garments(auth_client):
    person = await _person(auth_client)
    row = await _size(auth_client, person["id"], "tops", "6X")
    assert row["size_system"] == "youth_numeric"
    assert row["size_ordinal"] == 6.5


async def test_an_unparseable_size_is_still_recorded(auth_client):
    """It is a record of what was said. The index is what may be absent, never the reading."""
    person = await _person(auth_client)
    row = await _size(auth_client, person["id"], "tops", "M/L")
    assert row["size_raw"] == "M/L"
    assert row["size_system"] is None


# ── fits ───────────────────────────────────────────────────────────────────────────────────


async def test_fits_returns_items_with_their_totes(auth_client):
    """Phase 6's exit criterion, literally: "what fits Emma" returns items with their totes."""
    tote = (await auth_client.post("/totes", json={"code": "A15", "label": "4T winter"})).json()
    person = await _person(auth_client)
    await _size(auth_client, person["id"], "tops", "4T")

    await _garment(auth_client, tote["id"], "Winter coat", "4T")
    await _garment(auth_client, tote["id"], "Play dress", "5T")
    await _garment(auth_client, tote["id"], "Big kid hoodie", "12", "boys")
    await auth_client.post("/items", json={"name": "Ratchet set", "tote_id": tote["id"]})

    result = (await auth_client.get(f"/people/{person['id']}/fits")).json()
    assert result["answered"] is True
    names = {i["name"] for i in result["items"]}
    # 4T and 5T are one rung apart, inside the default tolerance.
    assert names == {"Winter coat", "Play dress"}
    # And the bin comes with them — otherwise the answer is useless in an attic.
    assert all(i["tote_code"] == "A15" for i in result["items"])


async def test_fits_says_it_cannot_say_rather_than_returning_nothing(auth_client):
    """The distinction the whole endpoint shape exists for.

    "We have nothing that fits" and "nobody recorded her size" are different sentences, and only
    one of them is a reason to stop looking.
    """
    person = await _person(auth_client, "Nobody")
    result = (await auth_client.get(f"/people/{person['id']}/fits")).json()
    assert result["answered"] is False
    assert result["reason"] == "no_sizes_recorded"
    assert result["items"] == []


async def test_an_unindexed_size_is_cannot_say_not_no_match(auth_client):
    """Distinguished from having no size at all, because the fix is different: re-read the tag,
    rather than add a size."""
    person = await _person(auth_client)
    await _size(auth_client, person["id"], "tops", "M/L")

    result = (await auth_client.get(f"/people/{person['id']}/fits")).json()
    assert result["answered"] is False
    assert result["reason"] == "no_indexed_size"


async def test_fits_never_matches_a_shoe_size_against_a_sweater(auth_client):
    """A shared ordinal axis makes this syntactically possible, so it is blocked explicitly."""
    tote = (await auth_client.post("/totes", json={"code": "A16"})).json()
    person = await _person(auth_client)
    await _size(auth_client, person["id"], "shoes", "shoe 10")
    await _garment(auth_client, tote["id"], "Sweater", "4T")

    result = (
        await auth_client.get(f"/people/{person['id']}/fits", params={"garment_type": "shoes"})
    ).json()
    assert [i["name"] for i in result["items"]] == []


async def test_fits_tolerance_widens_the_net(auth_client):
    tote = (await auth_client.post("/totes", json={"code": "A17"})).json()
    person = await _person(auth_client)
    await _size(auth_client, person["id"], "tops", "4T")
    await _garment(auth_client, tote["id"], "Size six", "6", "girls")

    tight = (await auth_client.get(f"/people/{person['id']}/fits")).json()
    assert [i["name"] for i in tight["items"]] == []
    loose = (
        await auth_client.get(f"/people/{person['id']}/fits", params={"tolerance": 2.5})
    ).json()
    assert [i["name"] for i in loose["items"]] == ["Size six"]


# ── Lending ────────────────────────────────────────────────────────────────────────────────


async def test_who_has_it_is_answerable_from_the_ledger(auth_client):
    """The item row knows only that it is out. Only the movement knows to whom — which is the
    whole reason lending needs the ledger."""
    tote = (await auth_client.post("/totes", json={"code": "G01"})).json()
    dave = await _person(auth_client, "Dave")
    drill = (
        await auth_client.post("/items", json={"name": "Cordless drill", "tote_id": tote["id"]})
    ).json()

    await auth_client.post(
        f"/items/{drill['id']}/move",
        json={
            "reason": "loaned",
            "person_id": dave["id"],
            "expected_back": str(TODAY + datetime.timedelta(days=7)),
        },
    )

    listed = (await auth_client.get("/items")).json()
    borrowed = next(i for i in listed if i["name"] == "Cordless drill")
    assert borrowed["status"] == "loaned"
    assert borrowed["loaned_to"] == "Dave"

    on_loan = (await auth_client.get(f"/people/{dave['id']}/on-loan")).json()
    assert [i["name"] for i in on_loan] == ["Cordless drill"]
    assert (await auth_client.get(f"/people/{dave['id']}")).json()["on_loan_count"] == 1


async def test_returning_clears_the_borrower_and_puts_it_back(auth_client):
    tote = (await auth_client.post("/totes", json={"code": "G02"})).json()
    dave = await _person(auth_client, "Dave")
    drill = (await auth_client.post("/items", json={"name": "Drill", "tote_id": tote["id"]})).json()

    await auth_client.post(
        f"/items/{drill['id']}/move", json={"reason": "loaned", "person_id": dave["id"]}
    )
    await auth_client.post(
        f"/items/{drill['id']}/move", json={"reason": "returned", "to_tote_id": tote["id"]}
    )

    back = (await auth_client.get(f"/items/{drill['id']}")).json()
    assert back["status"] == "stored"
    assert back["loaned_to"] is None
    assert (await auth_client.get(f"/people/{dave['id']}")).json()["on_loan_count"] == 0


async def test_overdue_lists_only_what_is_past_due(auth_client):
    tote = (await auth_client.post("/totes", json={"code": "G03"})).json()
    dave = await _person(auth_client, "Dave")
    for name, due in [
        ("Late drill", TODAY - datetime.timedelta(days=3)),
        ("Due tomorrow", TODAY + datetime.timedelta(days=1)),
        ("Due today", TODAY),
    ]:
        item = (await auth_client.post("/items", json={"name": name, "tote_id": tote["id"]})).json()
        await auth_client.post(
            f"/items/{item['id']}/move",
            json={"reason": "loaned", "person_id": dave["id"], "expected_back": str(due)},
        )

    overdue = (await auth_client.get("/overdue")).json()
    # Due TODAY is not overdue — a loan due today is a loan due today, and reporting it as late
    # from the moment the date arrives is how a nudge becomes noise.
    assert [i["name"] for i in overdue] == ["Late drill"]
    assert overdue[0]["is_overdue"] is True
    assert overdue[0]["loaned_to"] == "Dave"


async def test_a_deleted_person_does_not_erase_the_loan_from_the_ledger(auth_client):
    """A loan that happened still happened. Tidying a contact list must not put a hole in the one
    record this app promises never to have holes in."""
    tote = (await auth_client.post("/totes", json={"code": "G04"})).json()
    dave = await _person(auth_client, "Dave")
    drill = (await auth_client.post("/items", json={"name": "Drill", "tote_id": tote["id"]})).json()
    await auth_client.post(
        f"/items/{drill['id']}/move", json={"reason": "loaned", "person_id": dave["id"]}
    )

    assert (await auth_client.delete(f"/people/{dave['id']}")).status_code == 204

    # Newest first, which is how the endpoint has always returned them.
    movements = (await auth_client.get(f"/items/{drill['id']}/movements")).json()
    assert [m["reason"] for m in movements] == ["loaned", "initial"]
    # The item is still out; only the name is gone.
    assert (await auth_client.get(f"/items/{drill['id']}")).json()["status"] == "loaned"


# ── Outgrown ───────────────────────────────────────────────────────────────────────────────


async def test_an_outgrown_run_moves_together_and_says_why(auth_client):
    """One transaction for the whole run, and the reason survives: six months on, "we packed
    these away" and "she grew out of these" are different bins."""
    wearing = (await auth_client.post("/totes", json={"code": "W01"})).json()
    attic = (await auth_client.post("/totes", json={"code": "A18"})).json()
    person = await _person(auth_client)

    items = [
        await _garment(auth_client, wearing["id"], name, "3T")
        for name in ("Red shirt", "Blue shirt", "Green shirt")
    ]

    result = (
        await auth_client.post(
            f"/people/{person['id']}/outgrown",
            json={"item_ids": [i["id"] for i in items], "tote_id": attic["id"]},
        )
    ).json()
    assert len(result) == 6  # one `outgrown` and one `moved` per item

    filed = (await auth_client.get("/items", params={"tote_id": attic["id"]})).json()
    assert {i["name"] for i in filed} == {"Red shirt", "Blue shirt", "Green shirt"}

    history = (await auth_client.get(f"/items/{items[0]['id']}/movements")).json()
    assert [m["reason"] for m in history] == ["moved", "outgrown", "initial"]


async def test_an_outgrown_run_is_all_or_nothing(auth_client):
    """A half-applied run would leave the catalog claiming some of a size is in the attic and the
    rest is still being worn."""
    wearing = (await auth_client.post("/totes", json={"code": "W02"})).json()
    attic = (await auth_client.post("/totes", json={"code": "A19"})).json()
    person = await _person(auth_client)
    real = await _garment(auth_client, wearing["id"], "Red shirt", "3T")

    r = await auth_client.post(
        f"/people/{person['id']}/outgrown",
        json={
            "item_ids": [real["id"], "00000000-0000-0000-0000-000000000000"],
            "tote_id": attic["id"],
        },
    )
    assert r.status_code == 404

    # Nothing moved.
    still = (await auth_client.get(f"/items/{real['id']}")).json()
    assert still["tote_code"] == "W02"


# ── The nudge ──────────────────────────────────────────────────────────────────────────────


def test_the_overdue_message_names_the_things():
    """A count alone tells you nothing actionable."""

    class _Item:
        def __init__(self, name, who, due):
            self.name, self.loaned_to, self.expected_back = name, who, due

    title, body = overdue_message([_Item("Drill", "Dave", TODAY), _Item("Ladder", None, None)])
    assert title == "2 items are overdue"
    assert "Drill · with Dave" in body
    assert "Ladder" in body


def test_the_overdue_message_caps_the_list():
    """A push notification that has to be scrolled is one nobody reads."""

    class _Item:
        def __init__(self, n):
            self.name, self.loaned_to, self.expected_back = f"Item {n}", None, None

    title, body = overdue_message([_Item(n) for n in range(9)])
    assert title == "9 items are overdue"
    assert "…and 4 more" in body


@pytest.mark.parametrize("base", ["https://ntfy.sh", "http://www.ntfy.sh", "https://NTFY.SH"])
async def test_ntfy_refuses_to_send_to_the_public_service(base, monkeypatch):
    """Its topics are effectively public URLs, and these messages name what you own and who has
    it — the same reasoning that keeps the whole app tailnet-only."""
    from app.config import settings
    from app.services import ntfy

    monkeypatch.setattr(settings, "ntfy_base_url", base)
    monkeypatch.setattr(settings, "ntfy_topic", "tote-alerts")

    async def boom(*args, **kwargs):  # pragma: no cover - must never be reached
        raise AssertionError("a request was made to a public ntfy host")

    monkeypatch.setattr("httpx.AsyncClient.post", boom)
    assert await ntfy.send("t", "m") is False


async def test_the_nudge_reports_why_it_sent_nothing(auth_client):
    """ "Nothing was overdue", "ntfy is not configured" and "ntfy is down" are three different
    facts, and a channel that is quietly broken looks exactly like one with nothing to say."""
    quiet = (await auth_client.post("/overdue/nudge")).json()
    assert quiet == {"overdue": 0, "sent": False, "reason": "nothing_overdue"}

    tote = (await auth_client.post("/totes", json={"code": "G05"})).json()
    dave = await _person(auth_client, "Dave")
    item = (await auth_client.post("/items", json={"name": "Drill", "tote_id": tote["id"]})).json()
    await auth_client.post(
        f"/items/{item['id']}/move",
        json={
            "reason": "loaned",
            "person_id": dave["id"],
            "expected_back": str(TODAY - datetime.timedelta(days=2)),
        },
    )

    unconfigured = (await auth_client.post("/overdue/nudge")).json()
    assert unconfigured["overdue"] == 1
    assert unconfigured["sent"] is False
    assert unconfigured["reason"] == "ntfy_not_configured"
    assert unconfigured["title"] == "1 item is overdue"


async def test_the_nudge_actually_sends_when_ntfy_is_configured(auth_client, monkeypatch):
    """The test that was missing, and the reason a 500 reached production.

    Every earlier nudge test asserted the `ntfy_not_configured` branch, because the test
    environment has no ntfy — so the send path was never executed once, and it contained an
    attribute that does not exist (`user.ntfy_topic`; the override lives on `user_settings`).
    Green tests, green CI, and a 500 on the first real call.

    Configuring ntfy here and asserting on what was *handed to the transport* is what makes the
    send path real. The transport itself stays stubbed — CI must never make an outbound request.
    """
    from app.config import settings
    from app.services import ntfy

    sent: dict = {}

    async def capture(title, message, *, topic=None, priority=3, tags=None, client=None):
        sent.update(title=title, message=message, topic=topic, priority=priority)
        return True

    monkeypatch.setattr(settings, "ntfy_base_url", "http://host.docker.internal:8095")
    monkeypatch.setattr(settings, "ntfy_topic", "tote-alerts")
    monkeypatch.setattr(ntfy, "send", capture)

    tote = (await auth_client.post("/totes", json={"code": "G06"})).json()
    dave = await _person(auth_client, "Dave")
    item = (await auth_client.post("/items", json={"name": "Ladder", "tote_id": tote["id"]})).json()
    await auth_client.post(
        f"/items/{item['id']}/move",
        json={
            "reason": "loaned",
            "person_id": dave["id"],
            "expected_back": str(TODAY - datetime.timedelta(days=5)),
        },
    )

    result = (await auth_client.post("/overdue/nudge")).json()
    assert result == {"overdue": 1, "sent": True, "reason": None, "title": "1 item is overdue"}
    assert "Ladder · with Dave" in sent["message"]
    # No per-user override configured, so the compose default topic is used.
    assert sent["topic"] is None
    assert sent["priority"] == 4
