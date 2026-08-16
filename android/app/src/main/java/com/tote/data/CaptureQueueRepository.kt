package com.tote.data

import com.tote.data.local.CaptureQueueDao
import com.tote.data.local.CaptureQueueEntity
import com.tote.data.remote.ApiService
import com.tote.util.ImageBytes
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
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

    /** Persist a shot set as one queued item. The files are already in app storage. */
    suspend fun enqueue(
        photoFiles: List<File>,
        toteId: String? = null,
        toteCode: String? = null,
    ): String {
        require(photoFiles.isNotEmpty()) { "a capture needs at least one photo" }
        val id = UUID.randomUUID().toString()
        dao.upsert(
            CaptureQueueEntity(
                id = id,
                photoPaths = photoFiles.joinToString("\n") { it.absolutePath },
                toteId = toteId,
                toteCode = toteCode,
                state = CaptureQueueEntity.STATE_PENDING,
                createdAtMs = System.currentTimeMillis(),
            )
        )
        return id
    }

    /**
     * Drain every pending row.
     *
     * Returns true when nothing is left waiting on a network — WorkManager takes that as done.
     * False means "retry me later", and only a genuine transport failure produces it: a rejected
     * or uncertain row is waiting on a person, and telling WorkManager to retry for those would
     * spin the backoff chain against a queue that cannot move.
     */
    suspend fun drain(): Boolean {
        // Anything an earlier process left mid-flight. Uploads here run for tens of seconds,
        // which is ample time for Android to kill a backgrounded app, and an `uploading` row
        // whose uploader no longer exists would sit out every future drain.
        dao.releaseStranded()

        var allClear = true
        for (entry in dao.listUploadable()) {
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
                api.scanItem(parts, toteId, entry.id.toRequestBody(TEXT.toMediaType()))
                // The server owns it now: drop the row and the local copies. Keeping them would
                // accumulate a second, invisible photo library on the phone.
                deleteFiles(entry)
                dao.delete(entry.id)
            } catch (e: SocketTimeoutException) {
                dao.setState(
                    entry.id,
                    CaptureQueueEntity.STATE_UNCERTAIN,
                    entry.attempts + 1,
                    e.message,
                )
            } catch (e: HttpException) {
                dao.setState(
                    entry.id,
                    CaptureQueueEntity.STATE_FAILED,
                    entry.attempts + 1,
                    "HTTP ${e.code()}",
                )
            } catch (e: IOException) {
                dao.setState(
                    entry.id,
                    CaptureQueueEntity.STATE_PENDING,
                    entry.attempts + 1,
                    e.message,
                )
                allClear = false
            }
        }
        return allClear
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
    }
}
