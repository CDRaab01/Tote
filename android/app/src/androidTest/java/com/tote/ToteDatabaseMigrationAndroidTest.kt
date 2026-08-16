package com.tote

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tote.data.local.ToteDatabase
import com.tote.data.local.ToteMigrations
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Column-level migration validation — the half that cannot run in CI.
 *
 * Room's `MigrationTestHelper` needs real instrumentation (a device or emulator); this suite's CI
 * has neither, which is why the JVM-side `ToteDatabaseMigrationTest` checks reachability instead.
 * That one catches the fatal mistake — a version bump with no migration. This one catches the
 * subtler one: a migration that *runs* but produces a slightly different table than the schema
 * says, a missing default or a changed nullability, which would corrupt reads later rather than
 * failing at open.
 *
 * Run it with the other on-device checks:
 *
 *     ./gradlew :app:connectedDebugAndroidTest
 *
 * It is a no-op until there are at least two schema versions, which is honest rather than
 * skipped: there is nothing to migrate yet.
 */
@RunWith(AndroidJUnit4::class)
class ToteDatabaseMigrationAndroidTest {

    private companion object {
        const val TEST_DB = "migration-test.db"
    }

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ToteDatabase::class.java,
    )

    @Test
    fun everyMigrationProducesTheSchemaItClaims() {
        val versions = InstrumentationRegistry.getInstrumentation().context.assets
            .list("com.tote.data.local.ToteDatabase").orEmpty()
            .mapNotNull { it.removeSuffix(".json").toIntOrNull() }
            .sorted()
        if (versions.size < 2) return

        helper.createDatabase(TEST_DB, versions.first()).close()
        for (i in 0 until versions.size - 1) {
            // validateDroppedTables = true: a migration that leaves a stale table behind is a
            // schema that no longer matches its own export, and the next migration written
            // against that export will be wrong.
            helper.runMigrationsAndValidate(
                TEST_DB,
                versions[i + 1],
                true,
                *ToteMigrations.ALL,
            ).close()
        }
    }
}
