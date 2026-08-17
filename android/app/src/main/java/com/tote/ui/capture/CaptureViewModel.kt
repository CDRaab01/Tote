package com.tote.ui.capture

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.tote.data.CaptureQueueRepository
import com.tote.data.local.CachedTote
import com.tote.data.local.CatalogDao
import com.tote.data.local.CaptureQueueEntity
import com.tote.util.FeedbackBus
import com.tote.work.UploadWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The server's cap, mirrored so the UI can stop someone before the upload is rejected. */
const val MAX_PHOTOS_PER_ITEM = 8

@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val app: Application,
    private val repository: CaptureQueueRepository,
    private val feedback: FeedbackBus,
    private val savedState: SavedStateHandle,
    private val catalogDao: CatalogDao,
) : ViewModel() {

    /** Photos shot for the item currently in hand — not yet a queue row. */
    private val _shots = MutableStateFlow<List<File>>(emptyList())
    val shots: StateFlow<List<File>> = _shots

    val queue: StateFlow<List<CaptureQueueEntity>> = repository.queue
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The bin list comes from the Room cache, not the API.
     *
     * Cataloguing happens at the bin, which is where the Wi-Fi is worst — a destination picker
     * that needed a network would be empty in exactly the place it is used.
     */
    val totes: StateFlow<List<CachedTote>> = catalogDao.totes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The bin being filled. Chosen once and remembered across captures — that is what makes this
     * a batch flow rather than fifty identical picker taps at review time.
     *
     * Persisted through `SavedStateHandle`, because the exact situation the queue exists for —
     * shooting a bin's worth in a garage with the app backgrounded between shots — is also when
     * Android kills the process. Losing the destination there silently reset it to "Decide
     * later", and the cost showed up at review as one picker tap per item: precisely what
     * choosing it up front was meant to avoid.
     */
    private val _destination = MutableStateFlow<CachedTote?>(null)
    val destination: StateFlow<CachedTote?> = _destination

    private var pendingCameraTarget: File? = null

    init {
        // Restored by id against the cached bins, not stored whole: a CachedTote snapshot would
        // go stale, and the id is the only part that is stable.
        val savedId: String? = savedState[DESTINATION_KEY]
        if (savedId != null) {
            viewModelScope.launch {
                _destination.value = totes.value.firstOrNull { it.id == savedId }
                    ?: catalogDao.totes().first().firstOrNull { it.id == savedId }
            }
        }
    }

    fun chooseDestination(tote: CachedTote?) {
        _destination.value = tote
        savedState[DESTINATION_KEY] = tote?.id
    }

    /** A fresh camera output target under cache/captures/; returns its content Uri. */
    fun newCameraTarget(): Uri {
        val dir = File(app.cacheDir, "captures").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        pendingCameraTarget = file
        return FileProvider.getUriForFile(app, "com.tote.fileprovider", file)
    }

    fun onCameraResult(success: Boolean) {
        val file = pendingCameraTarget
        pendingCameraTarget = null
        if (success && file != null && file.exists() && _shots.value.size < MAX_PHOTOS_PER_ITEM) {
            _shots.value = _shots.value + file
        }
    }

    fun onGalleryPicked(uris: List<Uri>) {
        val room = MAX_PHOTOS_PER_ITEM - _shots.value.size
        if (room <= 0) return
        val dir = File(app.cacheDir, "captures").apply { mkdirs() }
        val copied = uris.take(room).mapNotNull { uri ->
            runCatching {
                val file = File(dir, "${UUID.randomUUID()}.jpg")
                app.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { input.copyTo(it) }
                } ?: return@runCatching null
                file
            }.getOrNull()
        }
        _shots.value = _shots.value + copied
    }

    fun removeShot(file: File) {
        _shots.value = _shots.value - file
        file.delete()
    }

    /** The shots in hand become ONE queued item; the queue uploads in the background. */
    fun queueItem() {
        val shots = _shots.value
        if (shots.isEmpty()) return
        val tote = _destination.value
        _shots.value = emptyList()
        viewModelScope.launch {
            // Out of the cache and into durable storage BEFORE the row exists. A queue row
            // pointing at a photo the OS has since evicted from the cache is worse than no row:
            // it claims work that cannot be done and cannot be reconstructed.
            val id = UUID.randomUUID().toString()
            val dir = File(app.filesDir, "capture_queue/$id").apply { mkdirs() }
            val moved = shots.mapIndexed { index, src ->
                val dst = File(dir, "photo_$index.jpg")
                src.copyTo(dst, overwrite = true)
                src.delete()
                dst
            }
            repository.enqueue(moved, toteId = tote?.id, toteCode = tote?.code)
            UploadWorker.kick(WorkManager.getInstance(app))
            // Say it landed. The only signal used to be the thumbnail strip vanishing and a
            // counter incrementing further down the scroll — likely off-screen mid-batch, on
            // the one screen used with a bin open and hands full.
            val photos = "${moved.size} photo${if (moved.size == 1) "" else "s"}"
            feedback.say(
                if (tote != null) "Queued — $photos for ${tote.code}"
                else "Queued — $photos, bin decided at review"
            )
        }
    }

    fun retry(id: String) {
        viewModelScope.launch {
            repository.retry(id)
            UploadWorker.kick(WorkManager.getInstance(app))
        }
    }

    fun discard(id: String) {
        viewModelScope.launch { repository.discard(id) }
    }

    private companion object {
        const val DESTINATION_KEY = "capture_destination_id"
    }
}
