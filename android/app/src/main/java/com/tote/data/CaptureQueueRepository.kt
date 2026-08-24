package com.tote.data

import com.tote.data.local.CaptureQueueDao
import com.tote.data.local.CaptureQueueEntity
import com.tote.data.remote.ApiService
import com.tote.util.ApiErrors
import com.tote.util.ImageBytes
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException

/**
 * The batch capture queue: photos to app storage plus one Room row per item, drained over
 * WorkManager when there is a network.
 *
 * The whole reason it exists is that cataloguing happens in an attic or a garage, which is where
 * the Wi-Fi is worst. Someone shoots a bin's worth of items in one pass with no signal at all and
 * walks back inside; the uploads have to happen later without them thinking about it. A capture
 * that required connectivity at the moment of the shutter would be a feature nobody could use in
 * the one place they need it.
 *
 * This is the only write-behind queue in the app, and that is deliberate: a *move* is an
 * instruction that can be retried from the ledger, but a photograph is new data that cannot be
 * re-derived once the bin is taped shut.
 *
 * ## Drain outcomes
 *
 * Three, not two, and the third is the one specific to this app:
 *
 * | Failure | State | Why |
 * |---|---|---|
 * | [IOException] | `pending` | offline or transient. WorkManager retries with backoff |
 * | [HttpException] | `failed` | the server answered and said no. Surface it; never poison-loop |
 * | [SocketTimeoutException] | `uncertain` | nobody knows whether it landed — see below |
 *
 * `POST /items/scan` is **synchronous**: it persists, cleans and identifies every photo before it
 * answers, measured at 35.5 s for one photo against the live model. So a client-side timeout is
 * not evidence the upload failed — it is most likely evidence the server is still working on it,
 * and the draft will exist.
 *
 * ## Every attempt carries the row id as `capture_id`
 *
 * That is the idempotency key, and it is why a re-send is now safe: the server returns the draft
 * the first attempt created instead of filing the object again. It was added after production
 * turned **one photograph into four drafts** on 2026-08-16 — three uploads had their connection
 * cut after the server had already committed, and [releaseStranded] duly re-sent each one. A
 * duplicate is the worst outcome this app has, because two drafts of one cap read exactly like
 * two real caps.
 *
 * `uncertain` survives that change, and is still worth having: the key makes a retry *harmless*,
 * but only a person can say whether a capture whose fate is unknown is worth waiting on. What
 * changed is that saying "retry" can no longer cost them a duplicate.
 */
@Singleton
class CaptureQueueRepository @Inject constructor(
    private val dao: CaptureQueueDao,
    private val api: ApiService,
) {
    val queue: Flow<List<CaptureQueueEntity>> = dao.observeAll()
    val pendingCount: Flow<Int> = dao.observeCount()

    /**
     * Rows that cannot move without a decision — rejected outright, or of unknown fate.
     *
     * The Review badge counts server-side drafts, so the LOCAL half of the pipeline had no
     * signal anywhere outside the Catalogue tab's bottom section. Someone photographs a bin in
     * the garage, one upload is rejected, and nothing ever says so — which is exactly the
     * "work the person believes is finished and is not" the draft badge was built to prevent.
     */
    val stuckCount: Flow<Int> = dao.observeAll().map { rows ->
        rows.count {
            it.state == CaptureQueueEntity.STATE_FAILED ||
                it.state == CaptureQueueEntity.STATE_UNCERTAIN
        }
    }

    /** Persist a shot set as one queued item. The files are already in app storage. */
    suspend fun enqueue(
        photoFiles: List<File>,
        toteId: String? = null,
        toteCode: String? = null,
        name: String? = null,
        categoryId: String? = null,
        describe: Boolean = false,
    ): String {
        require(photoFiles.isNotEmpty()) { "a capture needs at least one photo" }
        val id = UUID.randomUUID().toString()
        dao.upsert(
            CaptureQueueEntity(
                id = id,
                photoPaths = photoFiles.joinToString("\n") { it.absolutePath },
                toteId = toteId,
                toteCode = toteCode,
                // Blank is not an answer: the server falls back to identifying on a blank name,
                // and storing "" here would read downstream as "the person named it".
                name = name?.trim()?.takeIf { it.isNotEmpty() },
                categoryId = categoryId,
                describe = describe,
                state = CaptureQueueEntity.STATE_PENDING,
                createdAtMs = System.currentTimeMillis(),
            )
        )
        return id
    }

    /**
     * What one bounded drain achieved.
     *
     * @param allClear nothing is left waiting on a *network*. A rejected or uncertain row never
     *   sets it false: those wait on a person.
     * @param morePending rows remain solely because the batch bound was reached. The caller banks
     *   the progress and comes straight back rather than backing off.
     * @param uploaded how many captures actually reached the server this run. **This is what
     *   decides whether a run counts as progress**, and it exists because `allClear` alone
     *   conflated "the network died on the last of eight" with "nothing moved at all" — see
     *   [UploadWorker], where treating those the same froze a queue for an hour.
     */
    data class DrainResult(val allClear: Boolean, val morePending: Boolean, val uploaded: Int)

    /**
     * Drain pending rows, oldest first, up to a bounded amount of work.
     *
     * ## Why it is bounded
     *
     * Android stops a background worker after about ten minutes, and one scan costs ~31 s against
     * the live model. An unbounded drain over a queue longer than about nineteen items therefore
     * *cannot* finish, and being killed counts against WorkManager's attempt counter exactly like
     * a failure — so the backoff doubled on every pass until a real 41-item queue was managing
     * one to three uploads an hour and could not recover on its own (production, 2026-08-23).
     *
     * Stopping short and reporting success is what holds that counter at zero. See [UploadWorker].
     *
     * Both bounds earn their place: [maxItems] covers the ordinary item, and [budgetMs] covers the
     * one that is a single capture but eight photographs, where a count alone still overruns.
     */
    suspend fun drain(
        maxItems: Int = DEFAULT_BATCH,
        budgetMs: Long = DEFAULT_BUDGET_MS,
    ): DrainResult {
        // Anything an earlier process left mid-flight. Uploads here run for tens of seconds,
        // which is ample time for Android to kill a backgrounded app, and an `uploading` row
        // whose uploader no longer exists would sit out every future drain.
        dao.releaseStranded()

        val startedAtMs = System.currentTimeMillis()
        var allClear = true
        var attempted = 0
        var uploaded = 0

        for (entry in dao.listUploadable()) {
            if (attempted >= maxItems || System.currentTimeMillis() - startedAtMs >= budgetMs) {
                return DrainResult(allClear = allClear, morePending = true, uploaded = uploaded)
            }
            attempted++

            // Photos that are gone are not a transport problem and must not be reported as one:
            // `readBytes` on a missing file throws IOException, which would hold this row pending
            // and `allClear` false for ever — rebuilding the exact backoff spiral this batching
            // exists to end, behind a row that can never succeed. Head-of-line poison, and the
            // reason a genuine outage must stay distinguishable from a broken row.
            val missing = entry.paths.filter { !File(it).exists() }
            if (missing.isNotEmpty()) {
                dao.setState(
                    entry.id,
                    CaptureQueueEntity.STATE_FAILED,
                    entry.attempts + 1,
                    "The photos for this capture are no longer on the phone.",
                )
                continue
            }

            dao.setState(entry.id, CaptureQueueEntity.STATE_UPLOADING, entry.attempts, null)
            try {
                val parts = entry.paths.mapIndexed { index, path ->
                    // Downscaled at upload time rather than at capture time so the originals
                    // survive on disk until the server has them. If this ran at the shutter, a
                    // failed upload would leave only the lossy copy of a photo that cannot be
                    // retaken.
                    val jpeg = ImageBytes.downscaleToJpeg(File(path).readBytes())
                    MultipartBody.Part.createFormData(
                        "photos",
                        "photo_$index.jpg",
                        jpeg.toRequestBody(JPEG.toMediaType()),
                    )
                }
                val toteId = entry.toteId?.toRequestBody(TEXT.toMediaType())
                // The row id, not a new UUID: it must be STABLE across retries, because it is
                // the only thing that lets the server recognise a re-send as the same photograph
                // rather than a second object. See the `uncertain` note above — this is what
                // makes a re-send safe at all.
                api.scanItem(
                    photos = parts,
                    toteId = toteId,
                    captureId = entry.id.toRequestBody(TEXT.toMediaType()),
                    // Sent only when the person actually answered. An empty part is not the same
                    // as an absent one to a form parser, and the server reads absent as "identify
                    // this for me".
                    name = entry.name?.toRequestBody(TEXT.toMediaType()),
                    categoryId = entry.categoryId?.toRequestBody(TEXT.toMediaType()),
                    describe = entry.describe.toString().toRequestBody(TEXT.toMediaType()),
                )
                // The server owns it now: drop the row and the local copies. Keeping them would
                // accumulate a second, invisible photo library on the phone.
                deleteFiles(entry)
                dao.delete(entry.id)
                uploaded++
            } catch (e: SocketTimeoutException) {
                dao.setState(
                    entry.id,
                    CaptureQueueEntity.STATE_UNCERTAIN,
                    entry.attempts + 1,
                    e.message,
                )
            } catch (e: HttpException) {
                // The server's own sentence, not a bare status code. "At most 8 photos per
                // item" is a fixable mistake; "HTTP 422" is a mystery — and a 401 from an
                // expired session used to read exactly like a validation failure, though the
                // recovery could not be more different.
                val why = when {
                    e.code() == 401 -> "Your session expired. Sign in with Dragonfly again."
                    else -> ApiErrors.detail(e) ?: "The server rejected it (HTTP ${e.code()})"
                }
                dao.setState(
                    entry.id,
                    CaptureQueueEntity.STATE_FAILED,
                    entry.attempts + 1,
                    why,
                )
            } catch (e: IOException) {
                dao.setState(
                    entry.id,
                    CaptureQueueEntity.STATE_PENDING,
                    entry.attempts + 1,
                    // NEVER `e.message` alone: an IOException is entitled to a null message, and
                    // several common ones have one. The capture screen decides between "waiting
                    // its turn" and "waiting for a connection" by whether a message is stored, so
                    // a null here makes a row that genuinely failed on the network look merely
                    // queued — which is exactly what disguised the 2026-08-24 stall on screen.
                    e.message?.takeIf { it.isNotBlank() } ?: "Couldn't reach the server.",
                )
                allClear = false
            }
        }
        return DrainResult(allClear = allClear, morePending = false, uploaded = uploaded)
    }

    /** A row the user has decided about: send it back through the drain. */
    suspend fun retry(id: String) {
        dao.byId(id)?.let {
            dao.setState(it.id, CaptureQueueEntity.STATE_PENDING, it.attempts, null)
        }
    }

    /** Throw a capture away — the row and the photos both. */
    suspend fun discard(id: String) {
        val entry = dao.byId(id) ?: return
        deleteFiles(entry)
        dao.delete(id)
    }

    private fun deleteFiles(entry: CaptureQueueEntity) {
        entry.paths.forEach { File(it).delete() }
        // The per-capture directory too, or `filesDir/capture_queue` fills with empty folders.
        entry.paths.firstOrNull()?.let { File(it).parentFile?.delete() }
    }

    private companion object {
        const val JPEG = "image/jpeg"
        const val TEXT = "text/plain"

        /**
         * Eight items at the measured ~31 s each is about four minutes — comfortably inside the
         * ten Android allows, with room for the batch to run slower than measured.
         */
        const val DEFAULT_BATCH = 8

        /** The same ceiling expressed in time, for the capture that is eight photographs. */
        const val DEFAULT_BUDGET_MS = 5L * 60L * 1000L
    }
}
