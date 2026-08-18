package com.tote.data.local

import androidx.room.migration.Migration

/**
 * Every schema migration this database has ever shipped, in order.
 *
 * There is no destructive fallback (see `DatabaseModule`), so this list is the *only* way the
 * database moves between versions. It is currently empty because the database has only ever been
 * at version 1 — that is the normal state for a new app, not an oversight.
 *
 * ## Adding a version
 *
 * 1. Change the entities, and bump `version` on `@Database`.
 * 2. Build once. Room writes `app/schemas/…/<n>.json`. **Commit that file** — it is the record
 *    of what version *n* looked like and the only thing a migration test can validate against.
 * 3. Add a `Migration(n-1, n)` below with the SQL, and put it in [ALL].
 * 4. `ToteDatabaseMigrationTest` walks every exported version pair automatically, so a missing
 *    or wrong migration fails in CI. You do not have to remember to update the test.
 *
 * ## What a migration must not do
 *
 * Anything that drops data the server does not also hold. The catalog tables (`cached_items`,
 * `cached_totes`) are a disposable copy and could in principle be rebuilt — but from Phase 4 the
 * capture queue lives here too, and a queued photo taken in a garage with no signal exists
 * **nowhere else**. `DROP TABLE` on a queue table is not a migration, it is data loss with extra
 * steps.
 *
 * If a change to a cache table is genuinely easier to express as drop-and-recreate, that is
 * fine — but write it explicitly for that table, so the decision is visible in the diff rather
 * than implied by a global flag.
 */
object ToteMigrations {

    /**
     * v2 — the photo capture queue (Phase 4).
     *
     * Purely additive: one new table, nothing touched on `cached_items` or `cached_totes`. That
     * is the whole migration, and it is the one the no-destructive-fallback rule was put in place
     * a version early for — this table holds photos that exist nowhere else, so the version bump
     * that introduces it must not also be the one that could have wiped it.
     *
     * The SQL is copied verbatim out of `schemas/com.tote.data.local.ToteDatabase/2.json` with
     * Room's TABLE_NAME placeholder substituted, rather than typed out from the entity. The
     * on-device migration test compares the migrated database against that file column for
     * column, and a hand-written difference in a nullability flag or a default is exactly the
     * kind of thing that reads as correct and fails there.
     *
     * Declared **before** [ALL], which is not cosmetic: properties in a Kotlin object initialise
     * in declaration order, so an `ALL` above this would capture a null.
     */
    private val MIGRATION_1_2 = Migration(1, 2) { db ->
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `capture_queue` (" +
                "`id` TEXT NOT NULL, " +
                "`photoPaths` TEXT NOT NULL, " +
                "`toteId` TEXT, " +
                "`toteCode` TEXT, " +
                "`state` TEXT NOT NULL, " +
                "`attempts` INTEGER NOT NULL, " +
                "`lastError` TEXT, " +
                "`createdAtMs` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
    }

    /**
     * v3 — the garment tag's reading, cached for offline search (Phase 5).
     *
     * A plain `ADD COLUMN`, nullable with no default, which is the one shape SQLite will do
     * in place. **Not** drop-and-recreate: `cached_items` is disposable in principle, but
     * rebuilding it here would empty the offline catalog until the next successful sync — and
     * the whole reason this table exists is the attic, where that sync cannot happen.
     *
     * Only `size_raw` is cached, deliberately. The system/ordinal index belongs to the server;
     * a client copy would eventually be compared against a ladder this app does not implement.
     */
    private val MIGRATION_2_3 = Migration(2, 3) { db ->
        db.execSQL("ALTER TABLE `cached_items` ADD COLUMN `sizeRaw` TEXT")
    }

    /**
     * v4 — what the person said the item is, carried on the queue row (name-first capture).
     *
     * Three plain `ADD COLUMN`s on `capture_queue`, which is the one table in this database
     * whose contents exist nowhere else: a queued row points at photographs of an object that
     * is already back in a taped bin. So drop-and-recreate is not available here even in
     * principle, and additive is not a convenience — it is the only shape allowed.
     *
     * `describe` is NOT NULL with a default, because SQLite cannot add a NOT NULL column
     * without one and because the honest value for every row already in the queue is "nobody
     * asked for a description".
     */
    private val MIGRATION_3_4 = Migration(3, 4) { db ->
        db.execSQL("ALTER TABLE `capture_queue` ADD COLUMN `name` TEXT")
        db.execSQL("ALTER TABLE `capture_queue` ADD COLUMN `categoryId` TEXT")
        db.execSQL(
            "ALTER TABLE `capture_queue` ADD COLUMN `describe` INTEGER NOT NULL DEFAULT 0"
        )
    }

    /**
     * v5 — the photo count on cached items, so a cached row can draw its thumbnail.
     *
     * `cached_items` is the disposable half of this database and could in principle be rebuilt,
     * but additive is still right: a rebuild empties the offline catalogue until the next
     * successful sync, and the attic is precisely where that sync cannot happen.
     *
     * NOT NULL with a default of 0, which is also the honest value — every row already cached
     * was written before the count was carried, and 0 renders the placeholder rather than a
     * broken image.
     */
    private val MIGRATION_4_5 = Migration(4, 5) { db ->
        db.execSQL("ALTER TABLE `cached_items` ADD COLUMN `photoCount` INTEGER NOT NULL DEFAULT 0")
    }

    /**
     * Passed to `Room.databaseBuilder(...).addMigrations(*ALL)`.
     *
     * Order does not matter to Room — it finds a path through the graph — but keeping them in
     * ascending order keeps the file readable.
     */
    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
    )
}
