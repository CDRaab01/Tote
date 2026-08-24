"""Re-deriving the size index after a ladder change.

The ladder computes `size_system`/`size_ordinal` from `size_raw` at **write** time, so widening
it reaches new rows and nobody else. That has now stranded rows twice — #36's three `6m`
garments, and the fourteen the 2026-08-23 widening would have left — so the re-derive is a
reusable function with tests rather than a script somebody has to remember to run.

The invariant that matters most here is the one about what is NOT touched: `size_raw` is the
reading a human took off a tag that is now inside a taped bin, and no amount of re-derivation may
rewrite it.
"""

import uuid

import pytest
from sqlalchemy import text

from app.sizing.rederive import rederive_sizes

pytestmark = pytest.mark.asyncio


async def _garment(auth_client, size_raw: str) -> str:
    """A filed item carrying a clothing reading, via the real write path."""
    created = await auth_client.post(
        "/items", json={"name": f"Sleepsuit {uuid.uuid4().hex[:6]}", "quantity": 1}
    )
    assert created.status_code in (200, 201), created.text
    item_id = created.json()["id"]
    patched = await auth_client.patch(
        f"/items/{item_id}",
        json={
            "name": created.json()["name"],
            "quantity": 1,
            "apparel": {"size_raw": size_raw},
        },
    )
    assert patched.status_code == 200, patched.text
    return item_id


async def _apparel(db, item_id: str) -> dict:
    row = (
        (
            await db.execute(
                text(
                    "SELECT size_raw, size_system, size_ordinal FROM item_apparel "
                    "WHERE item_id = :i"
                ),
                {"i": uuid.UUID(item_id)},
            )
        )
        .mappings()
        .first()
    )
    return dict(row) if row else {}


async def _strand(db, item_id: str) -> None:
    """Blank the derived index, which is exactly the state a pre-widening row is in."""
    await db.execute(
        text("UPDATE item_apparel SET size_system = NULL, size_ordinal = NULL WHERE item_id = :i"),
        {"i": uuid.UUID(item_id)},
    )
    await db.commit()


async def test_rederive_reaches_a_row_the_old_ladder_could_not_place(auth_client, db):
    item_id = await _garment(auth_client, "12-18M")
    await _strand(db, item_id)
    assert (await _apparel(db, item_id))["size_ordinal"] is None

    changed = await db.run_sync(lambda s: rederive_sizes(s.connection()))
    await db.commit()

    after = await _apparel(db, item_id)
    assert after["size_ordinal"] == pytest.approx(1.25)
    assert after["size_system"] == "infant_months"
    assert changed["item_apparel"] >= 1


async def test_rederive_never_touches_the_reading(auth_client, db):
    """`size_raw` is the tag. The bin is taped shut; this is the only copy."""
    raw = "12 months/mois"
    item_id = await _garment(auth_client, raw)
    await _strand(db, item_id)

    await db.run_sync(lambda s: rederive_sizes(s.connection()))
    await db.commit()

    assert (await _apparel(db, item_id))["size_raw"] == raw


async def test_rederive_is_idempotent(auth_client, db):
    """A pure function of `size_raw` and the current ladder, so a second run is a no-op. It has
    to be: this runs in a migration, and migrations get re-run against restored databases."""
    item_id = await _garment(auth_client, "18-24 months")
    await _strand(db, item_id)

    first = await db.run_sync(lambda s: rederive_sizes(s.connection()))
    await db.commit()
    second = await db.run_sync(lambda s: rederive_sizes(s.connection()))
    await db.commit()

    assert first["item_apparel"] >= 1
    assert second["item_apparel"] == 0, "a second pass changed rows the first should have settled"


async def test_rederive_leaves_a_reading_it_still_cannot_place_alone(auth_client, db):
    """A bare number stays unparsed, and that is the designed answer rather than a gap to close.
    The re-derive must not be read as a licence to guess retroactively."""
    item_id = await _garment(auth_client, "12")
    await _strand(db, item_id)

    await db.run_sync(lambda s: rederive_sizes(s.connection()))
    await db.commit()

    after = await _apparel(db, item_id)
    assert after["size_ordinal"] is None
    assert after["size_raw"] == "12"
