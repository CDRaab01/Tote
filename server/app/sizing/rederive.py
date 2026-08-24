"""Re-derive the stored size index from the readings already on disk.

`size_system` and `size_ordinal` are computed from `size_raw` at **write** time, which means a
change to the ladder reaches new rows and nobody else. Rows written before the change keep the
answer the old table gave — usually `NULL`, i.e. invisible to `fits` — and nothing in the app
ever revisits them.

That has now bitten twice. #36 added `3m`/`6m`/`9m` and left three garments behind, still sitting
in the open-items table months later with a hand-run script nobody could execute. The 2026-08-23
widening would have left fourteen more. So the mechanism gets fixed here rather than the symptom:
a ladder change is now a three-line migration that calls [rederive_sizes].

**`size_raw` is never touched.** It is the reading — what a human saw printed on a tag that is
now sealed in a bin — and this module only ever rewrites the derived index over it. A re-derive
is therefore always safe to run again: it is a pure function of `size_raw` and the current
ladder, so running it twice changes nothing the first run did not.

Deliberately lightweight `sa.table()` constructs rather than the ORM models. This is called from
migrations, and a migration pinned to a model breaks the day the model gains a column the
migration's revision predates.
"""

from sqlalchemy import Float, String, Uuid, column, select, table, update

from app.sizing import parse_size

_item_apparel = table(
    "item_apparel",
    column("item_id", Uuid),
    column("size_raw", String),
    column("size_system", String),
    column("size_ordinal", Float),
    column("department", String),
)

_person_sizes = table(
    "person_sizes",
    column("id", Uuid),
    column("size_raw", String),
    column("size_system", String),
    column("size_ordinal", Float),
)


def rederive_sizes(conn) -> dict[str, int]:
    """Recompute every stored size index from its `size_raw`. Returns per-table change counts.

    Only rows whose derived values actually change are written, so a no-op run issues no UPDATEs
    and the counts mean what they say.
    """
    return {
        "item_apparel": _rederive(conn, _item_apparel, "item_id", with_department=True),
        "person_sizes": _rederive(conn, _person_sizes, "id", with_department=False),
    }


def _rederive(conn, tbl, key: str, *, with_department: bool) -> int:
    cols = [tbl.c[key], tbl.c.size_raw, tbl.c.size_system, tbl.c.size_ordinal]
    if with_department:
        cols.append(tbl.c.department)

    changed = 0
    for row in conn.execute(select(*cols).where(tbl.c.size_raw.isnot(None))).mappings():
        # A person's size is recorded without a department (see routers/people.py), a garment's
        # with one. Passing the wrong thing here would silently re-derive some rows differently
        # from how the write path derives them, which is worse than not running at all.
        reading = (
            parse_size(row["size_raw"], department=row["department"])
            if with_department
            else parse_size(row["size_raw"])
        )
        system = reading.system if reading else None
        ordinal = reading.ordinal if reading else None
        if system == row["size_system"] and ordinal == row["size_ordinal"]:
            continue
        conn.execute(
            update(tbl)
            .where(tbl.c[key] == row[key])
            .values(size_system=system, size_ordinal=ordinal)
        )
        changed += 1
    return changed
