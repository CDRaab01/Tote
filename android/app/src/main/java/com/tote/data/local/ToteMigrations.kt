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
     * Passed to `Room.databaseBuilder(...).addMigrations(*ALL)`.
     *
     * Order does not matter to Room — it finds a path through the graph — but keeping them in
     * ascending order keeps the file readable.
     */
    val ALL: Array<Migration> = arrayOf(
        // No migrations yet: the database has only ever been at version 1.
        //
        // Example of the shape the first one will take, when Phase 4 adds the capture queue:
        //
        // Migration(1, 2) { db ->
        //     db.execSQL(
        //         """
        //         CREATE TABLE IF NOT EXISTS capture_queue (
        //             id TEXT NOT NULL PRIMARY KEY,
        //             …
        //         )
        //         """.trimIndent()
        //     )
        // }
        //
        // Copy the CREATE TABLE straight out of the generated schema JSON rather than writing it
        // by hand — the migration test compares the migrated database against that file column
        // for column, and a hand-typed difference in a default or a nullability flag will fail.
    )
}
