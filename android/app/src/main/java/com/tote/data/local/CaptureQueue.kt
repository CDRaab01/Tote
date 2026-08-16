package com.tote.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One queued capture: the photos of a single item, waiting to become a server draft.
 *
 * **This is the one table in the app whose contents exist nowhere else.** Everything else in this
 * database is a disposable copy of server state; these rows point at JPEGs of an object that was
 * in someone's hands in a garage and is now back in a sealed bin. That asymmetry is why
 * `DatabaseModule` has no destructive migration fallback, and why a `DROP TABLE` here would not
 * be a migration.
 *
 * The photos themselves live under `filesDir/capture_queue/{id}/`, moved there out of the cache
 * before the row is written — a cache directory is a directory the OS may empty at any time, and
 * a queue row pointing at a deleted JPEG is worse than no row at all.
 */
@Entity(tableName = "capture_queue")
data class CaptureQueueEntity(
    @PrimaryKey val id: String,
    /** Newline-joined absolute paths, in shoot order. */
    val photoPaths: String,
    /**
     * The bin being filled, if one was chosen before shooting.
     *
     * Carried through to `POST /items/scan` as `tote_id`, which the server records as the draft's
     * *suggested* destination and deliberately does not apply — an item enters a tote only when a
     * human confirms the draft. Cataloguing happens standing at one open bin, so choosing it once
     * and having every capture remember it is the difference between a batch flow and fifty
     * identical dropdown taps at review time.
     */
    val toteId: String?,
    /** Denormalised so the queue can say "→ A14" without a lookup it may be offline for. */
    val toteCode: String?,
    /** pending | uploading | uncertain | failed (uploaded rows are deleted). */
    val state: String,
    val attempts: Int = 0,
    val lastError: String? = null,
    val createdAtMs: Long,
) {
    val paths: List<String> get() = photoPaths.split('\n').filter { it.isNotBlank() }

    companion object {
        /** Waiting for a network, or waiting its turn in the drain. */
        const val STATE_PENDING = "pending"
        const val STATE_UPLOADING = "uploading"

        /**
         * The upload timed out, so nobody knows whether the server got it.
         *
         * Distinct from [STATE_FAILED] because the correct next action is different, and getting
         * it wrong duplicates work. Tote's `/items/scan` is **synchronous** — it persists,
         * cleans and identifies every photo before it answers, measured at 35.5 s for a single
         * photo on the live host — so a client-side timeout very likely means the request
         * *arrived* and the draft *exists*. Automatically retrying would file the same object
         * into the same bin twice, and the second copy is indistinguishable from a real duplicate
         * once it is in the catalog.
         *
         * So the queue stops and says so: check Review, then discard this row or retry it. That
         * is the honest state, and it is one the user can actually resolve.
         */
        const val STATE_UNCERTAIN = "uncertain"

        /** The server answered, and the answer was no. Never retried automatically. */
        const val STATE_FAILED = "failed"
    }
}

@Dao
interface CaptureQueueDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CaptureQueueEntity)

    @Query("SELECT * FROM capture_queue ORDER BY createdAtMs ASC")
    fun observeAll(): Flow<List<CaptureQueueEntity>>

    /**
     * What a drain should attempt, oldest first.
     *
     * `pending` only. A `failed` or `uncertain` row is waiting on a human decision, and picking
     * it up again on the next connectivity change is exactly the poison-row loop the states
     * exist to prevent. `uploading` is excluded because something is already doing it.
     */
    @Query(
        "SELECT * FROM capture_queue WHERE state = :state ORDER BY createdAtMs ASC"
    )
    suspend fun listUploadable(state: String = CaptureQueueEntity.STATE_PENDING):
        List<CaptureQueueEntity>

    @Query("SELECT * FROM capture_queue WHERE id = :id")
    suspend fun byId(id: String): CaptureQueueEntity?

    @Query(
        "UPDATE capture_queue SET state = :state, attempts = :attempts, lastError = :lastError " +
            "WHERE id = :id"
    )
    suspend fun setState(id: String, state: String, attempts: Int, lastError: String?)

    @Query("DELETE FROM capture_queue WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM capture_queue")
    fun observeCount(): Flow<Int>

    /**
     * Anything left mid-upload by process death, back to pending.
     *
     * Without this a row is stranded forever: `uploading` is excluded from the drain, and the
     * process that set it is gone. The window is real — an upload takes half a minute or more,
     * which is ample time for Android to kill a backgrounded app.
     */
    @Query(
        "UPDATE capture_queue SET state = :pending WHERE state = :uploading"
    )
    suspend fun releaseStranded(
        pending: String = CaptureQueueEntity.STATE_PENDING,
        uploading: String = CaptureQueueEntity.STATE_UPLOADING,
    )
}
