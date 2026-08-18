"""Back-filling a newly seeded category into accounts that already exist.

A seeded category only ever reaches accounts created *after* it is added, because
`DEFAULT_CATEGORIES` is written at first login and never again. So adding a name to that tuple
does nothing at all for the account already using the app — the back-fill is the whole feature,
and on the test database it is a no-op (no users exist when migrations run) and would otherwise
ship completely unexercised.

## Why this no longer executes migration 0004's own statement

It used to, and it can't any more. 0004's `BACKFILL_SQL` inserts `(user_id, name, sort_order)`,
which is correct **at its point in the chain** — it runs before 0006 exists, when `categories`
has no `household_id` column at all. Production is unaffected: migrations run in order, 0004
back-fills per user, and 0006 then derives every row's `household_id` from its `user_id`.

But this module runs against a database migrated to **head**, where `household_id` is NOT NULL.
Executing a frozen historical statement there was always a slight cheat that happened to work,
and 0006 ended it. The statement itself must not be edited — it has already run on the live
database, and rewriting shipped migration history is how a restore stops reproducing the schema
it restored from.

So what is pinned here is the **rule**, on the axis it now lives on: a new seed name reaches
every existing *household*, appended to that household's own ordering, idempotently. That is the
statement the next seed addition will have to write (see CLAUDE.md), and `REMOVE_SQL` is still
exercised verbatim because a delete needs no column that did not exist yet.
"""

import importlib.util
import pathlib
import uuid

MIGRATION = pathlib.Path(__file__).resolve().parents[1] / "alembic/versions/0004_baby_category.py"
_spec = importlib.util.spec_from_file_location("m0004", MIGRATION)
m0004 = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(m0004)

# 0004's statement, rescoped to the household axis — the shape the NEXT seed addition must use.
# Deliberately spelled out rather than derived from `m0004.BACKFILL_SQL` by string surgery: a
# regex that quietly stopped matching would leave this file asserting nothing.
BACKFILL_SQL = f"""
INSERT INTO categories (id, household_id, name, sort_order)
SELECT gen_random_uuid(), h.id, '{m0004.CATEGORY}',
       COALESCE(
           (SELECT MAX(c.sort_order) + 1 FROM categories c WHERE c.household_id = h.id), 0
       )
FROM households h
WHERE NOT EXISTS (
    SELECT 1 FROM categories c
    WHERE c.household_id = h.id AND lower(c.name) = lower('{m0004.CATEGORY}')
)
"""


async def _household(raw_sql) -> str:
    """A user with the household of one they get at first login. Returns the household id."""
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


async def _categories(raw_sql, hid: str) -> list[tuple[str, int]]:
    rows = await raw_sql(
        "SELECT name, sort_order FROM categories WHERE household_id = :h ORDER BY sort_order",
        h=hid,
    )
    return [(r[0], r[1]) for r in rows.all()]


async def test_an_existing_household_gains_the_category_at_the_end_of_its_own_order(raw_sql):
    hid = await _household(raw_sql)
    for order, name in enumerate(("Tools", "Kitchen")):
        await raw_sql(
            "INSERT INTO categories (id, household_id, name, sort_order) VALUES (:i, :h, :n, :o)",
            i=str(uuid.uuid4()),
            h=hid,
            n=name,
            o=order,
        )

    await raw_sql(BACKFILL_SQL)

    # Appended, not slotted in: the existing rows keep the order they had. Renumbering them to
    # place one row a few positions higher would rewrite an ordering the user may have arranged.
    assert await _categories(raw_sql, hid) == [("Tools", 0), ("Kitchen", 1), ("Baby", 2)]


async def test_running_it_twice_adds_nothing(raw_sql):
    """Idempotent by hand as well as by Alembic. `uq_categories_household_name` would raise on a
    second pass, and a data migration that cannot be re-run against a half-migrated restore
    turns a bad afternoon into a worse one."""
    hid = await _household(raw_sql)

    await raw_sql(BACKFILL_SQL)
    await raw_sql(BACKFILL_SQL)

    assert [n for n, _ in await _categories(raw_sql, hid)] == ["Baby"]


async def test_a_household_that_already_named_one_itself_is_left_alone(raw_sql):
    """Matched case-insensitively, because somebody typing their own is as likely to write
    "baby" — and two rows a picker shows as the same word is exactly the fragmentation the
    categories table exists to prevent."""
    hid = await _household(raw_sql)
    await raw_sql(
        "INSERT INTO categories (id, household_id, name, sort_order) VALUES (:i, :h, 'baby', 7)",
        i=str(uuid.uuid4()),
        h=hid,
    )

    await raw_sql(BACKFILL_SQL)

    assert await _categories(raw_sql, hid) == [("baby", 7)]


async def test_one_row_per_household_not_per_member(raw_sql):
    """The failure the rescope exists to prevent, in the seed path specifically.

    Per user, a two-person household got the seeded name **twice** — two "Baby" rows a picker
    renders as one word, which is precisely the fragmentation the categories table exists to
    stop. It is also the only place a seed addition could silently create a duplicate, because
    it is the one insert nobody types by hand.
    """
    hid = await _household(raw_sql)
    partner = str(uuid.uuid4())
    await raw_sql(
        "INSERT INTO users (id, name, email) VALUES (:i, 'Partner', :e)",
        i=partner,
        e=f"{partner[:8]}@example.com",
    )
    await raw_sql(
        "INSERT INTO household_members (id, household_id, user_id) VALUES (:i, :h, :u)",
        i=str(uuid.uuid4()),
        h=hid,
        u=partner,
    )

    await raw_sql(BACKFILL_SQL)

    assert [n for n, _ in await _categories(raw_sql, hid)] == ["Baby"]


async def test_downgrade_keeps_a_category_something_was_filed_under(raw_sql):
    """The schema going backwards is no reason to lose data. An orphaned category is a far
    smaller problem than an item that lost the one it was in."""
    hid = await _household(raw_sql)
    await raw_sql(BACKFILL_SQL)
    row = (
        await raw_sql("SELECT id FROM categories WHERE household_id = :h AND name = 'Baby'", h=hid)
    ).scalar_one()
    await raw_sql(
        "INSERT INTO items (id, household_id, name, category_id, quantity, status)"
        " VALUES (:i, :h, 'Cot sheet', :c, 1, 'stored')",
        i=str(uuid.uuid4()),
        h=hid,
        c=str(row),
    )

    # Verbatim from the migration: a DELETE needs no column that did not exist yet.
    await raw_sql(m0004.REMOVE_SQL)

    assert [n for n, _ in await _categories(raw_sql, hid)] == ["Baby"]


async def test_downgrade_removes_an_untouched_one(raw_sql):
    hid = await _household(raw_sql)
    await raw_sql(BACKFILL_SQL)

    await raw_sql(m0004.REMOVE_SQL)

    assert await _categories(raw_sql, hid) == []
