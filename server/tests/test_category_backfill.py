"""The 0004 back-fill, exercised against a real database.

A seeded category only ever reaches accounts created *after* it is added, because
`DEFAULT_CATEGORIES` is written at first login and never again. So adding a name to that tuple
does nothing at all for the account already using the app — the back-fill is the whole feature,
and on the test database it is a no-op (no users exist when migrations run) and would otherwise
ship completely unexercised.
"""

import importlib.util
import pathlib
import uuid

MIGRATION = pathlib.Path(__file__).resolve().parents[1] / "alembic/versions/0004_baby_category.py"
_spec = importlib.util.spec_from_file_location("m0004", MIGRATION)
m0004 = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(m0004)


async def _user(raw_sql) -> str:
    uid = str(uuid.uuid4())
    await raw_sql(
        "INSERT INTO users (id, name, email) VALUES (:i, 'T', :e)",
        i=uid,
        e=f"{uid[:8]}@example.com",
    )
    return uid


async def _categories(raw_sql, uid: str) -> list[tuple[str, int]]:
    rows = await raw_sql(
        "SELECT name, sort_order FROM categories WHERE user_id = :u ORDER BY sort_order",
        u=uid,
    )
    return [(r[0], r[1]) for r in rows.all()]


async def test_an_existing_account_gains_the_category_at_the_end_of_its_own_order(raw_sql):
    uid = await _user(raw_sql)
    for order, name in enumerate(("Tools", "Kitchen")):
        await raw_sql(
            "INSERT INTO categories (id, user_id, name, sort_order) VALUES (:i, :u, :n, :o)",
            i=str(uuid.uuid4()),
            u=uid,
            n=name,
            o=order,
        )

    await raw_sql(m0004.BACKFILL_SQL)

    # Appended, not slotted in: the existing rows keep the order they had. Renumbering them to
    # place one row a few positions higher would rewrite an ordering the user may have arranged.
    assert await _categories(raw_sql, uid) == [("Tools", 0), ("Kitchen", 1), ("Baby", 2)]


async def test_running_it_twice_adds_nothing(raw_sql):
    """Idempotent by hand as well as by Alembic. `uq_categories_user_name` would raise on a
    second pass, and a data migration that cannot be re-run against a half-migrated restore
    turns a bad afternoon into a worse one."""
    uid = await _user(raw_sql)

    await raw_sql(m0004.BACKFILL_SQL)
    await raw_sql(m0004.BACKFILL_SQL)

    assert [n for n, _ in await _categories(raw_sql, uid)] == ["Baby"]


async def test_an_account_that_already_named_one_itself_is_left_alone(raw_sql):
    """Matched case-insensitively, because somebody typing their own is as likely to write
    "baby" — and two rows a picker shows as the same word is exactly the fragmentation the
    categories table exists to prevent."""
    uid = await _user(raw_sql)
    await raw_sql(
        "INSERT INTO categories (id, user_id, name, sort_order) VALUES (:i, :u, 'baby', 7)",
        i=str(uuid.uuid4()),
        u=uid,
    )

    await raw_sql(m0004.BACKFILL_SQL)

    assert await _categories(raw_sql, uid) == [("baby", 7)]


async def test_downgrade_keeps_a_category_something_was_filed_under(raw_sql):
    """The schema going backwards is no reason to lose data. An orphaned category is a far
    smaller problem than an item that lost the one it was in."""
    uid = await _user(raw_sql)
    await raw_sql(m0004.BACKFILL_SQL)
    row = (
        await raw_sql("SELECT id FROM categories WHERE user_id = :u AND name = 'Baby'", u=uid)
    ).scalar_one()
    await raw_sql(
        "INSERT INTO items (id, user_id, name, category_id, quantity, status)"
        " VALUES (:i, :u, 'Cot sheet', :c, 1, 'stored')",
        i=str(uuid.uuid4()),
        u=uid,
        c=str(row),
    )

    await raw_sql(m0004.REMOVE_SQL)

    assert [n for n, _ in await _categories(raw_sql, uid)] == ["Baby"]


async def test_downgrade_removes_an_untouched_one(raw_sql):
    uid = await _user(raw_sql)
    await raw_sql(m0004.BACKFILL_SQL)

    await raw_sql(m0004.REMOVE_SQL)

    assert await _categories(raw_sql, uid) == []
