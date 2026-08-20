"""The invariants the whole app leans on, asserted directly rather than as a side effect.

Every other test file exercises a feature and happens to depend on these. This one asserts them
over the *whole table*, after each of the paths that can write derived state — which is how the
deleted-bin hole was found: every feature test passed while the catalogue held rows in a shape
CLAUDE.md §4 says cannot exist.
"""

import uuid

import pytest
from sqlalchemy import select, text

from app.models.item import Item
from app.models.movement import Movement
from app.sizing import parse_size


async def _tote(client, code, **extra):
    r = await client.post("/totes", json={"code": code, **extra})
    assert r.status_code == 201, r.text
    return r.json()


async def _item(client, name, tote_id=None, **extra):
    body = {"name": name, "quantity": 1, **extra}
    if tote_id:
        body["tote_id"] = tote_id
    r = await client.post("/items", json=body)
    assert r.status_code == 201, r.text
    return r.json()


async def _assert_invariant(db, household_id):
    """`current_tote_id IS NOT NULL <=> status == 'stored'`, over every item in a household.

    Whole-household rather than just the item under test, deliberately: the bug this file was
    written for left the *contents of a deleted bin* in the forbidden shape, and an assertion
    scoped to the row the test was holding would have looked at the bin instead.

    Not whole-*table*, though, and that is not timidity — `test_schema.py` plants raw rows with
    `status='stored'` and no tote on purpose, to exercise a generated column without going
    through the service. Those are synthetic by design and would poison a global sweep.
    """
    bad = (
        await db.execute(
            text(
                "SELECT id, status, current_tote_id FROM items "
                "WHERE household_id = :h AND (current_tote_id IS NOT NULL) <> (status = 'stored')"
            ),
            {"h": str(household_id)},
        )
    ).all()
    assert bad == [], f"invariant violated on {len(bad)} row(s): {bad[:5]}"


# ── The status/whereabouts invariant, across every writer ──────────────────────────────


@pytest.mark.parametrize(
    "reason",
    ["moved", "unpacked", "repacked", "outgrown", "loaned", "returned", "disposed", "corrected"],
)
async def test_every_movement_reason_preserves_the_invariant(auth_client, db, reason):
    bin_a = await _tote(auth_client, f"INV-{reason[:3]}")
    item = await _item(auth_client, "Thing", bin_a["id"])

    body = {"reason": reason}
    if reason in ("moved", "repacked", "returned"):
        body["to_tote_id"] = bin_a["id"]
    assert (await auth_client.post(f"/items/{item['id']}/move", json=body)).status_code in (
        200,
        422,
    )
    await _assert_invariant(db, auth_client.household_id)


async def test_the_bulk_paths_preserve_the_invariant(auth_client, db):
    a = await _tote(auth_client, "INV-BLKA")
    b = await _tote(auth_client, "INV-BLKB")
    ids = [(await _item(auth_client, f"x{i}", a["id"]))["id"] for i in range(3)]

    await auth_client.post(f"/totes/{a['id']}/unpack", json={})
    await _assert_invariant(db, auth_client.household_id)
    await auth_client.post(f"/totes/{a['id']}/repack", json={})
    await _assert_invariant(db, auth_client.household_id)
    await auth_client.post("/items/bulk-move", json={"item_ids": ids, "to_tote_id": b["id"]})
    await _assert_invariant(db, auth_client.household_id)


# ── Deleting a bin is a whereabouts event ──────────────────────────────────────────────


async def test_deleting_a_bin_leaves_its_contents_in_a_legal_state(auth_client, db):
    """`items.current_tote_id` is `ON DELETE SET NULL`, so the database nulls the tote and would
    leave `status` reading `stored` — an item that claims to be in a bin and is in none."""
    a = await _tote(auth_client, "DEL-A")
    item = await _item(auth_client, "Tree stand", a["id"])

    assert (await auth_client.delete(f"/totes/{a['id']}")).status_code == 204

    await _assert_invariant(db, auth_client.household_id)
    row = (await db.execute(select(Item).where(Item.id == uuid.UUID(item["id"])))).scalar_one()
    assert row.status == "out"
    assert row.out_reason == "unfiled"


async def test_deleting_a_bin_records_which_bin_it_was(auth_client, db):
    """The row's `from_tote_id` is SET NULL a moment later, so the CODE has to survive in the
    note — "it left A14 when A14 was deleted" is the entire value of the row."""
    a = await _tote(auth_client, "DEL-B")
    item = await _item(auth_client, "Tree stand", a["id"])

    await auth_client.delete(f"/totes/{a['id']}")

    rows = (
        (await db.execute(select(Movement).where(Movement.item_id == uuid.UUID(item["id"]))))
        .scalars()
        .all()
    )
    last = max(rows, key=lambda m: m.moved_at)
    assert last.reason == "bin_deleted"
    assert "DEL-B" in (last.note or ""), last.note


async def test_an_item_from_a_deleted_bin_files_as_moved_not_repacked(auth_client, db):
    """ "It came back" is untrue of a thing whose bin ceased to exist — the same reasoning as
    something that was never filed."""
    a = await _tote(auth_client, "DEL-C")
    b = await _tote(auth_client, "DEL-D")
    item = await _item(auth_client, "Tree stand", a["id"])
    await auth_client.delete(f"/totes/{a['id']}")

    r = await auth_client.post(
        "/items/bulk-move", json={"item_ids": [item["id"]], "to_tote_id": b["id"]}
    )
    assert r.status_code == 200, r.text
    assert r.json()[0]["reason"] == "moved"


async def test_the_contents_of_a_deleted_bin_are_still_findable(auth_client):
    a = await _tote(auth_client, "DEL-E")
    await _item(auth_client, "Zamboni", a["id"])
    await auth_client.delete(f"/totes/{a['id']}")

    hits = (await auth_client.get("/search", params={"q": "zamboni"})).json()
    assert [h["item"]["name"] for h in hits] == ["Zamboni"]


# ── The ladder reads what is printed on a tag ──────────────────────────────────────────


@pytest.mark.parametrize(
    "raw,ordinal",
    [
        ("GIRLS 8", 8.0),
        ("Girls 8", 8.0),
        ("girls 8", 8.0),
        ("Boys 10", 10.0),
        ("B10", 10.0),
        ("G8", 8.0),
        ("Y8", 8.0),
        ("Youth 6X", 6.5),
        ("Girls 6X", 6.5),
        ("Juniors 12", 12.0),
    ],
)
def test_a_youth_marker_in_the_string_resolves_the_ladder(raw, ordinal):
    """`Womens 8` parsed and `GIRLS 8` did not, while `parse_size`'s docstring promised both.
    A garment typed exactly as its tag reads landed with no ordinal — invisible to `fits`."""
    reading = parse_size(raw)
    assert reading is not None, f"{raw!r} does not parse"
    assert reading.system == "youth_numeric"
    assert reading.ordinal == ordinal


@pytest.mark.parametrize("raw", ["8", "10", "12"])
def test_a_bare_number_still_refuses_to_guess(raw):
    """The marker parser must not have quietly made bare numbers resolvable — youth 8 and
    women's 8 are different garments for different people, and that stays unanswerable."""
    assert parse_size(raw) is None


@pytest.mark.parametrize("raw,system", [("8y", None), ("Womens 8", "womens_numeric")])
def test_the_youth_marker_did_not_steal_its_neighbours(raw, system):
    """Prefix forms only: a trailing `y` belongs to the shoe parser, and women's markers must
    keep resolving to women's."""
    reading = parse_size(raw)
    assert (reading.system if reading else None) == system


# ── A correction to the department must reach the derived size ─────────────────────────


async def test_correcting_the_department_re_derives_the_size(auth_client, db):
    """The department disambiguates a bare number, and on the scan path it arrives from the
    MODEL — production has `mens` and `womens` on 12-month onesies. Spotting that at review and
    fixing the chip used to be accepted while the ordinal kept its old value, so the garment
    stayed indexed as a women's 8 and "what fits Emma" went on missing it."""
    a = await _tote(auth_client, "DEP-A")
    item = await _item(auth_client, "Skirt", a["id"])

    body = {"name": "Skirt", "quantity": 1}
    r = await auth_client.patch(
        f"/items/{item['id']}",
        json={**body, "apparel": {"size_raw": "8", "department": "womens"}},
    )
    assert r.status_code == 200, r.text
    assert r.json()["apparel"]["size_system"] == "womens_numeric"

    # Department ALONE — `exclude_unset` means a partial apparel block is a legal patch, and it
    # is the natural shape for "I only fixed the chip". (The Android client happens to send both
    # fields together, so this is a contract hole rather than a live one — but the derived index
    # must follow its input on every path that can change it, not on the one the client uses.)
    r = await auth_client.patch(
        f"/items/{item['id']}", json={**body, "apparel": {"department": "girls"}}
    )
    assert r.status_code == 200, r.text
    got = r.json()["apparel"]
    assert got["size_system"] == "youth_numeric", "the correction did not reach the ordinal"
    assert got["size_ordinal"] == 8.0
    assert got["size_raw"] == "8", "the reading itself must be untouched"
