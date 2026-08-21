"""Used-first ordering, usage counts, and the icon back-fill.

The ordering test was checked against the old `sort_order, name` query before being kept — it
fails there, which is what makes it a regression test rather than a description.
"""

import importlib.util
import pathlib
import uuid

MIGRATION = pathlib.Path(__file__).resolve().parents[1] / "alembic/versions/0007_category_icons.py"
_spec = importlib.util.spec_from_file_location("m0007", MIGRATION)
m0007 = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(m0007)


async def _tote(client, code):
    r = await client.post("/totes", json={"code": code})
    assert r.status_code == 201, r.text
    return r.json()


async def _category(client, name, icon=None):
    r = await client.post("/categories", json={"name": name, "icon": icon})
    assert r.status_code == 201, r.text
    return r.json()


async def _item(client, name, tote_id, category_id):
    r = await client.post(
        "/items",
        json={"name": name, "quantity": 1, "tote_id": tote_id, "category_id": category_id},
    )
    assert r.status_code == 201, r.text
    return r.json()


# ── Used-first ordering ────────────────────────────────────────────────────────────────


async def test_categories_come_back_most_used_first(auth_client):
    """The whole feature is this ordering: what the household files under rises, the empty
    seeded rows sink, and every picker inherits it without sorting for itself."""
    tote = await _tote(auth_client, "CU1")
    # Fixture seeds only "Tools" at sort_order 0; add two more, file into them unevenly.
    books = await _category(auth_client, "Books")
    xmas = await _category(auth_client, "Christmas")
    for i in range(3):
        await _item(auth_client, f"book {i}", tote["id"], books["id"])
    await _item(auth_client, "lights", tote["id"], xmas["id"])

    got = (await auth_client.get("/categories")).json()

    assert [c["name"] for c in got] == ["Books", "Christmas", "Tools"]
    assert [c["item_count"] for c in got] == [3, 1, 0]


async def test_drafts_do_not_promote_a_category(auth_client, db):
    """An unreviewed model guess must not push a category up the pickers."""
    from app.models.item import Item
    from app.services import household_service as hh

    books = await _category(auth_client, "Books")
    household = await hh.household_of(db, auth_client.user_id)
    for i in range(5):
        db.add(
            Item(
                household_id=household.id,
                user_id=auth_client.user_id,
                name=f"draft {i}",
                category_id=uuid.UUID(books["id"]),
                is_draft=True,
                status="out",
                out_reason="other",
            )
        )
    await db.commit()

    got = (await auth_client.get("/categories")).json()
    books_row = next(c for c in got if c["name"] == "Books")
    assert books_row["item_count"] == 0


async def test_ties_fall_back_to_the_seeded_order(auth_client):
    """At zero items everywhere — a brand-new household — the list must read exactly as the
    seed intended, not alphabetically."""
    await _category(auth_client, "Aardvark care")  # alphabetically first, seeded last

    got = (await auth_client.get("/categories")).json()
    # "Tools" is the fixture's sort_order-0 seed; the new row lands after it, not before.
    assert [c["name"] for c in got] == ["Tools", "Aardvark care"]


async def test_counts_are_scoped_to_the_household(auth_client, other_client):
    """Another household's filing must not leak into your ordering — the count subquery joins
    on category_id alone, and the category's own household scoping is what contains it."""
    mine = await _category(auth_client, "Books")
    theirs = await _category(other_client, "Books")
    their_tote = await _tote(other_client, "CU2")
    await _item(other_client, "their book", their_tote["id"], theirs["id"])

    got = (await auth_client.get("/categories")).json()
    mine_row = next(c for c in got if c["id"] == mine["id"])
    assert mine_row["item_count"] == 0


# ── The icon back-fill (migration 0007) ────────────────────────────────────────────────


async def _household(raw_sql) -> str:
    uid, hid = str(uuid.uuid4()), str(uuid.uuid4())
    await raw_sql(
        "INSERT INTO users (id, name, email) VALUES (:i, 'T', :e)",
        i=uid,
        e=f"{uid[:8]}@example.com",
    )
    await raw_sql("INSERT INTO households (id, owner_user_id) VALUES (:h, :u)", h=hid, u=uid)
    await raw_sql(
        "INSERT INTO household_members (id, household_id, user_id) VALUES (:i, :h, :u)",
        i=str(uuid.uuid4()),
        h=hid,
        u=uid,
    )
    return hid


async def _icon_of(raw_sql, hid, name):
    r = await raw_sql(
        "SELECT icon FROM categories WHERE household_id = :h AND name = :n", h=hid, n=name
    )
    return r.scalar_one()


async def test_the_backfill_fills_null_icons_by_name(raw_sql):
    hid = await _household(raw_sql)
    await raw_sql(
        "INSERT INTO categories (id, household_id, name, sort_order) VALUES (:i, :h, 'Books', 0)",
        i=str(uuid.uuid4()),
        h=hid,
    )

    for statement in m0007.BACKFILL_STATEMENTS:
        await raw_sql(statement)

    assert await _icon_of(raw_sql, hid, "Books") == "📚"


async def test_the_backfill_never_overwrites_a_chosen_icon(raw_sql):
    """`icon IS NULL` is the guard: somebody who already picked 🏠 for their "Books" keeps it."""
    hid = await _household(raw_sql)
    await raw_sql(
        "INSERT INTO categories (id, household_id, name, icon, sort_order)"
        " VALUES (:i, :h, 'Books', '🏠', 0)",
        i=str(uuid.uuid4()),
        h=hid,
    )

    for statement in m0007.BACKFILL_STATEMENTS:
        await raw_sql(statement)

    assert await _icon_of(raw_sql, hid, "Books") == "🏠"


async def test_the_backfill_is_idempotent(raw_sql):
    hid = await _household(raw_sql)
    await raw_sql(
        "INSERT INTO categories (id, household_id, name, sort_order) VALUES (:i, :h, 'Tools', 0)",
        i=str(uuid.uuid4()),
        h=hid,
    )

    for _ in range(2):
        for statement in m0007.BACKFILL_STATEMENTS:
            await raw_sql(statement)

    assert await _icon_of(raw_sql, hid, "Tools") == "🔧"


async def test_the_downgrade_reverts_only_its_own_writes(raw_sql):
    hid = await _household(raw_sql)
    await raw_sql(
        "INSERT INTO categories (id, household_id, name, sort_order) VALUES (:i, :h, 'Books', 0)",
        i=str(uuid.uuid4()),
        h=hid,
    )
    await raw_sql(
        "INSERT INTO categories (id, household_id, name, icon, sort_order)"
        " VALUES (:i, :h, 'Tools', '🛠️', 1)",
        i=str(uuid.uuid4()),
        h=hid,
    )
    for statement in m0007.BACKFILL_STATEMENTS:
        await raw_sql(statement)

    for statement in m0007.REVERT_STATEMENTS:
        await raw_sql(statement)

    assert await _icon_of(raw_sql, hid, "Books") is None  # ours, reverted
    assert await _icon_of(raw_sql, hid, "Tools") == "🛠️"  # theirs (not the seed emoji), kept


async def test_a_new_household_seeds_icons_directly(auth_client):
    """The other half of the #29 rule: new households get icons from the seed itself. The
    fixture seeds via `create_household` + a hand-made Tools row, so assert through the real
    seed map instead: every DEFAULT name has an icon."""
    from app.models.category import DEFAULT_CATEGORIES, DEFAULT_CATEGORY_ICONS

    assert set(DEFAULT_CATEGORY_ICONS) == set(DEFAULT_CATEGORIES), (
        "every seeded name carries an icon, and no icon is orphaned from a name"
    )
