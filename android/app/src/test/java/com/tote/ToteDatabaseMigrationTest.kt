package com.tote

import com.tote.data.local.ToteMigrations
import java.io.File
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The guard that makes a missing migration impossible to ship.
 *
 * `DatabaseModule` has no destructive fallback. A schema bump without a matching migration makes
 * the app refuse to open its database — the right failure, loud rather than silent, but only if
 * it happens in CI instead of on a phone in a garage. This test is what moves it.
 *
 * Why it matters more here than in a typical app: from Phase 4 the photo capture queue lives in
 * this database. Forty photos taken with no signal exist **nowhere else** until they upload. A
 * wipe there is not a cache miss, it is lost work leaving orphaned JPEGs on disk with nothing
 * recording what they were of.
 *
 * ## What this checks, and what it deliberately does not
 *
 * It reads the **committed schema exports** and proves every shipped version can still reach the
 * newest one through [ToteMigrations.ALL]. That catches the fatal mistake — bumping `version` and
 * forgetting the migration — and it discovers versions from the filesystem rather than a
 * hard-coded list, so nobody has to remember to update this file when version 3 appears.
 *
 * It does **not** execute the SQL or compare the resulting tables column by column. That is
 * Room's `MigrationTestHelper`, which in 2.8.4 only works against real instrumentation: it needs
 * a device or emulator, and this suite's CI has neither. `ToteDatabaseMigrationAndroidTest` on
 * the `androidTest` side does that, run by hand alongside the other on-device checks.
 *
 * Splitting it this way is deliberate. The mistake that destroys data fails on every PR; the
 * subtler one — a migration whose SQL is slightly wrong — is caught where it can be caught at
 * all, rather than being skipped entirely because the perfect check cannot run in CI.
 */
class ToteDatabaseMigrationTest {

    private companion object {
        const val SCHEMA_DIR = "com.tote.data.local.ToteDatabase"
    }

    private val schemaDir: File
        get() = File(
            requireNotNull(System.getProperty("tote.schemaDir")) {
                "tote.schemaDir is not set — see tasks.withType<Test> in app/build.gradle.kts"
            },
            SCHEMA_DIR,
        )

    /** Every version with a committed schema export, ascending. */
    private fun exportedVersions(): List<Int> =
        schemaDir.listFiles().orEmpty()
            .mapNotNull { it.name.removeSuffix(".json").toIntOrNull() }
            .sorted()

    @Test
    fun `the current schema is exported and committed`() {
        // Without the exported JSON there is nothing to reason about, so a missing file would
        // silently disable every guard below it. Fail loudly instead.
        assertTrue(
            exportedVersions().isNotEmpty(),
            "No exported schemas in $schemaDir. Check exportSchema = true on @Database, the " +
                "room.schemaLocation ksp arg, and that app/schemas is committed.",
        )
    }

    @Test
    fun `every shipped version can still reach the newest one`() {
        val versions = exportedVersions()
        val newest = versions.last()

        // Migration edges as Room sees them. One migration may span several versions (1 → 3), so
        // this is a reachability question rather than a consecutive-pairs one.
        val edges = ToteMigrations.ALL.map { it.startVersion to it.endVersion }

        val unreachable = versions.filter { start ->
            if (start == newest) return@filter false
            val seen = mutableSetOf(start)
            val queue = ArrayDeque(listOf(start))
            var reached = false
            while (queue.isNotEmpty() && !reached) {
                val at = queue.removeFirst()
                for ((from, to) in edges) {
                    if (from == at && seen.add(to)) {
                        if (to == newest) reached = true else queue.addLast(to)
                    }
                }
            }
            !reached
        }

        assertTrue(
            unreachable.isEmpty(),
            "No migration path to version $newest from version(s) $unreachable.\n" +
                "A phone sitting on one of those would refuse to open its database, and from " +
                "Phase 4 that database holds photo captures that exist nowhere else.\n" +
                "Add the migration to ToteMigrations.ALL — the procedure is in its KDoc.",
        )
    }

    @Test
    fun `no migration claims a version that was never shipped`() {
        // A migration referring to a version with no exported schema means either the export was
        // not committed or the version numbers have a typo. Both leave a database that cannot be
        // validated against anything.
        val versions = exportedVersions().toSet()
        val bogus = ToteMigrations.ALL
            .flatMap { listOf(it.startVersion, it.endVersion) }
            .filterNot { it in versions }
            .distinct()

        assertTrue(
            bogus.isEmpty(),
            "Migration(s) reference version(s) $bogus with no committed schema export in " +
                "$schemaDir. Either the export was not committed, or the versions are wrong.",
        )
    }
}
