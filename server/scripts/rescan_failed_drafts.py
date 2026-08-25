"""Re-run identification on every draft whose scan failed, straight against the database.

The per-item path is `POST /drafts/{id}/rescan`, offered as a button in review, and it is the
right tool for one draft. This is the bulk one: when an outage lands twenty drafts at once, a
person should not have to tap through twenty of them to find out that the outage is over.

It exists because that is not hypothetical. On 2026-08-25 LM Studio came back with
`google/gemma-4-e4b` loaded onto the integrated GPU; every vision call ran past the 60 s timeout,
and twenty consecutive captures were filed as `identify_unavailable`. None of them had lost
anything — the pipeline persists the originals before it ever calls the model — but recovering
them meant writing this by hand against production.

Run it inside the server container, which already holds the database URL and the photo volume::

    docker exec -w /app tote-server-1 python scripts/rescan_failed_drafts.py --dry-run
    docker exec -w /app tote-server-1 python scripts/rescan_failed_drafts.py

`--dry-run` does the model calls and prints what it would write, then rolls back. `--limit N`
stops after N drafts, which is how to spend one call finding out whether the model is healthy
again before committing to a twenty-minute run.
"""

import argparse
import asyncio
import sys
import time

from fastapi import HTTPException
from sqlalchemy import select

from app.database import AsyncSessionLocal
from app.models.item import Item
from app.services.scan_pipeline import NoStoredPhotos, identify_into, stored_photo_urls


def _parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="call the model and print the answers, then roll back without writing.",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=None,
        help="stop after this many drafts (use 1 to probe whether the model is healthy).",
    )
    return parser.parse_args(argv)


async def _rescan_one(db, item: Item) -> str:
    urls = await stored_photo_urls(db, item)
    await identify_into(db, item, urls, household_id=item.household_id)
    size = item.apparel.size_raw if item.apparel is not None else None
    return f"{item.name!r} conf={item.scan_confidence}" + (f" size={size!r}" if size else "")


async def main(argv: list[str] | None = None) -> int:
    args = _parse_args(argv)

    async with AsyncSessionLocal() as db:
        # Ids, not instances, and materialised before the loop starts. A rollback expires every
        # object in the session, so holding ORM rows across iterations and then reading `item.id`
        # to report a failure raises MissingGreenlet instead — the same trap the scan router's
        # `household_id = user.household_id` line exists to sidestep, and it cost a run here.
        query = (
            select(Item.id)
            .where(Item.is_draft.is_(True), Item.scan_error.is_not(None))
            .order_by(Item.created_at)
        )
        if args.limit is not None:
            query = query.limit(args.limit)
        ids = list((await db.execute(query)).scalars().all())

        print(f"{len(ids)} draft(s) with a scan error{' (DRY RUN)' if args.dry_run else ''}\n")
        if not ids:
            return 0

        done = failed = 0
        for n, item_id in enumerate(ids, 1):
            started = time.monotonic()
            try:
                item = (await db.execute(select(Item).where(Item.id == item_id))).scalar_one()
                result = await _rescan_one(db, item)
                # Per item, so a run over twenty drafts cannot lose nineteen good answers to one
                # failure at the end of it.
                await (db.rollback() if args.dry_run else db.commit())
                done += 1
            except NoStoredPhotos:
                await db.rollback()
                failed += 1
                result = "SKIPPED: no readable original on the volume"
            except HTTPException as e:
                # The model is still unreachable. Stop rather than grind through the rest: every
                # remaining draft will fail the same way, and each failure costs the full timeout.
                await db.rollback()
                failed += 1
                print(f"[{n}/{len(ids)}] {item_id} model unreachable: {e.detail}")
                print("\nstopping — the rest would fail the same way.")
                break
            except Exception as e:  # noqa: BLE001 - one bad draft must not end the run
                await db.rollback()
                failed += 1
                result = f"FAILED {type(e).__name__}: {e}"
            print(f"[{n}/{len(ids)}] {item_id} {time.monotonic() - started:5.1f}s  {result}")

        print(f"\ndone: {done} re-identified, {failed} not")
        return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
