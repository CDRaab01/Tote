package com.tote.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * A read cache of the catalog, so the app is usable where the bins physically are.
 *
 * This matters more in Tote than in any sibling: cataloguing and looking things up happen in an
 * attic and a garage, which is exactly where the Wi-Fi is worst. A catalog you cannot read
 * standing in front of the bins is a catalog you stop using.
 *
 * The server remains the source of truth. Nothing here is ever written back — these rows are a
 * snapshot of the last successful fetch, and every mutation goes to the API and then refreshes
 * the snapshot. Treating the cache as authoritative is how two devices start disagreeing about
 * where the Christmas lights are.
 *
 * `toteCode` and `locationName` are stored denormalised because the server already denormalises
 * them: offline search has to answer "which bin, and where is it" without a join the cache
 * cannot reliably reproduce.
 */
@Entity(tableName = "cached_items")
data class CachedItem(
    @PrimaryKey val id: String,
    val name: String,
    val description: String?,
    val notes: String?,
    val quantity: Int,
    val status: String,
    val currentToteId: String?,
    val toteCode: String?,
    val locationName: String?,
    val isOverdue: Boolean,
)

@Entity(tableName = "cached_totes")
data class CachedTote(
    @PrimaryKey val id: String,
    val code: String,
    val label: String?,
    val locationId: String?,
    val locationName: String?,
    val itemCount: Int,
    val outCount: Int,
    val archived: Boolean,
)

@Dao
interface CatalogDao {
    @Query("SELECT * FROM cached_totes WHERE archived = 0 ORDER BY code")
    fun totes(): Flow<List<CachedTote>>

    @Query("SELECT * FROM cached_items WHERE currentToteId = :toteId ORDER BY name")
    fun itemsInTote(toteId: String): Flow<List<CachedItem>>

    /**
     * Offline search.
     *
     * Deliberately a LIKE scan rather than an attempt to reproduce Postgres full-text: FTS
     * stemming and ranking would drift from the server's answers, and two different notions of
     * "matches" is worse than one honest, simpler one. The UI labels offline results as such.
     * A household catalog is thousands of rows, not millions, so a scan is fine.
     */
    @Query(
        """
        SELECT * FROM cached_items
        WHERE name LIKE '%' || :q || '%'
           OR description LIKE '%' || :q || '%'
           OR notes LIKE '%' || :q || '%'
        ORDER BY name LIMIT 50
        """
    )
    suspend fun search(q: String): List<CachedItem>

    @Query("SELECT COUNT(*) FROM cached_items") suspend fun itemCount(): Int

    @Query("SELECT COUNT(*) FROM cached_totes WHERE archived = 0") suspend fun toteCount(): Int

    @Query("SELECT COUNT(*) FROM cached_items WHERE status != 'stored' AND status != 'disposed'")
    suspend fun outCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertItems(items: List<CachedItem>)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertTotes(totes: List<CachedTote>)

    @Query("DELETE FROM cached_items") suspend fun clearItems()

    @Query("DELETE FROM cached_totes") suspend fun clearTotes()

    /**
     * Replace the whole snapshot in one transaction.
     *
     * Clear-then-insert rather than upsert-and-reconcile, because an item DELETED on the server
     * has to disappear here too. An upsert-only sync leaves tombstones: the app would keep
     * showing something that no longer exists, which for this app means a trip to the attic.
     */
    @Transaction
    suspend fun replaceAll(totes: List<CachedTote>, items: List<CachedItem>) {
        clearTotes()
        clearItems()
        upsertTotes(totes)
        upsertItems(items)
    }
}

/**
 * The local database.
 *
 * `exportSchema = true` and the exported JSON is COMMITTED. That file is not a build artefact —
 * it is the record of what version N actually looked like, and it is the only thing a migration
 * test can validate against. Deleting it silently disables every guard below it.
 *
 * **Bumping `version` requires a migration.** There is no destructive fallback: see
 * `DatabaseModule`, and `ToteDatabaseMigrationTest` for the procedure.
 */
@Database(entities = [CachedItem::class, CachedTote::class], version = 1, exportSchema = true)
abstract class ToteDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao
}
