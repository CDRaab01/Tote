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
import com.tote.data.remote.ApiService
import com.tote.data.remote.CategoryDto
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
    private val api: ApiService,
) : ViewModel() {

    /**
     * Where photos live between the shutter and the Queue tap.
     *
     * `filesDir`, **never `cacheDir`**. Android empties cache directories without warning when
     * storage runs low, and it did: on a phone at 100% full the staged JPEGs were deleted out
     * from under the app between shooting and queueing, and `queueItem`'s copy then threw
     * `NoSuchFileException` on the main thread and killed the app mid-batch — taking every other
     * shot in hand with it. These files are the only copy of a photograph until the server has
     * them, which makes a directory the OS is allowed to reclaim the wrong home for them.
     */
    // `internal` so a test can assert which directory this is. That is the whole regression:
    // FileProvider cannot be exercised under Robolectric here (it fails to resolve ANY of the
    // configured roots, including ones that predate this change), so `newCameraTarget` cannot
    // be called in a JVM test and the location has to be checked directly.
    internal val stagingDir: File get() = File(app.filesDir, "captures").apply { mkdirs() }

    /**
     * Photos shot for the item currently in hand — not yet a queue row.
     *
     * Persisted as paths through `SavedStateHandle` for the same reason the destination is: the
     * situation this flow exists for — shooting a bin's worth in a garage, app backgrounded
     * between shots — is also when Android kills the process. Held only in memory, a half-shot
     * item vanished silently and left its files orphaned on disk.
     */
    private val _shots = MutableStateFlow(restoreShots())
    val shots: StateFlow<List<File>> = _shots

    private fun restoreShots(): List<File> =
        savedState.get<List<String>>(SHOTS_KEY).orEmpty()
            .map(::File)
            // A path whose file is gone is not a photo. Dropped quietly here because this is a
            // restore, not something the person just did — see `queueItem` for the case where
            // it IS worth saying out loud.
            .filter { it.exists() }

    private fun setShots(shots: List<File>) {
        _shots.value = shots
        savedState[SHOTS_KEY] = shots.map(File::getAbsolutePath)
    }

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

    /**
     * What the next shot is, until it is something else.
     *
     * The whole point is that it is **sticky**. Photographing twenty sleepsuits should be twenty
     * shutter presses; typing "sleepsuit" twenty times is the per-item work this feature exists
     * to remove. It survives process death for the same reason the destination does — the batch
     * this is for happens in a garage with the app backgrounded between shots.
     *
     * Blank is a real choice, not an empty field waiting to be filled: it means "let the model
     * identify this one", which is what the app has always done and still does.
     */
    private val _itemName = MutableStateFlow(savedState[NAME_KEY] ?: "")
    val itemName: StateFlow<String> = _itemName

    private val _categoryId = MutableStateFlow<String?>(savedState[CATEGORY_KEY])
    val categoryId: StateFlow<String?> = _categoryId

    private val _describe = MutableStateFlow(savedState[DESCRIBE_KEY] ?: false)
    val describe: StateFlow<Boolean> = _describe

    /** The category vocabulary, read once and held for the run. */
    private val _categories = MutableStateFlow<List<CategoryDto>>(emptyList())
    val categories: StateFlow<List<CategoryDto>> = _categories

    fun setItemName(value: String) {
        _itemName.value = value
        savedState[NAME_KEY] = value
    }

    fun chooseCategory(id: String?) {
        _categoryId.value = id
        savedState[CATEGORY_KEY] = id
    }

    fun setDescribe(value: Boolean) {
        _describe.value = value
        savedState[DESCRIBE_KEY] = value
    }

    fun loadCategories() {
        viewModelScope.launch {
            runCatching { api.categories() }.onSuccess { _categories.value = it }
        }
    }

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
        sweepOrphans()
        loadCategories()
    }

    /**
     * Delete staged files nothing can reach any more.
     *
     * Staging is durable now, which means it no longer cleans itself. Anything in the directory
     * that is not in the restored shot list is unreachable — the session that shot it is gone —
     * and leaving it would grow the app's footprint forever on a phone already out of space.
     */
    private fun sweepOrphans() {
        val keep = _shots.value.map(File::getAbsolutePath).toSet()
        stagingDir.listFiles()?.forEach { file ->
            if (file.absolutePath !in keep) file.delete()
        }
    }

    fun chooseDestination(tote: CachedTote?) {
        _destination.value = tote
        savedState[DESTINATION_KEY] = tote?.id
    }

    /** A fresh camera output target under files/captures/; returns its content Uri. */
    fun newCameraTarget(): Uri {
        val file = File(stagingDir, "${UUID.randomUUID()}.jpg")
        pendingCameraTarget = file
        return FileProvider.getUriForFile(app, "com.tote.fileprovider", file)
    }

    fun onCameraResult(success: Boolean) {
        val file = pendingCameraTarget
        pendingCameraTarget = null
        if (success && file != null && file.exists() && _shots.value.size < MAX_PHOTOS_PER_ITEM) {
            setShots(_shots.value + file)
        }
    }

    fun onGalleryPicked(uris: List<Uri>) {
        val room = MAX_PHOTOS_PER_ITEM - _shots.value.size
        if (room <= 0) return
        val dir = stagingDir
        val copied = uris.take(room).mapNotNull { uri ->
            runCatching {
                val file = File(dir, "${UUID.randomUUID()}.jpg")
                app.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { input.copyTo(it) }
                } ?: return@runCatching null
                file
            }.getOrNull()
        }
        setShots(_shots.value + copied)
    }

    fun removeShot(file: File) {
        setShots(_shots.value - file)
        file.delete()
    }

    /** The shots in hand become ONE queued item; the queue uploads in the background. */
    fun queueItem() {
        val shots = _shots.value
        if (shots.isEmpty()) return
        val tote = _destination.value
        setShots(emptyList())
        viewModelScope.launch {
            // A staged file that has gone missing is skipped, never fatal. This used to be a
            // bare `copyTo` over every shot, and one missing source threw NoSuchFileException
            // straight up the main thread: the app died mid-batch and took the surviving photos
            // with it. Staging is durable now, so this should not happen at all — but "should
            // not" is no reason for the rest of the batch to be destroyed if it does.
            val (present, missing) = shots.partition { it.exists() }
            if (present.isEmpty()) {
                feedback.say("Those photos are gone from this phone. Shoot them again.")
                return@launch
            }

            // Into the queue's own directory BEFORE the row exists. A queue row pointing at a
            // photo that is not there is worse than no row: it claims work that cannot be done
            // and cannot be reconstructed.
            val id = UUID.randomUUID().toString()
            val dir = File(app.filesDir, "capture_queue/$id").apply { mkdirs() }
            val moved = present.mapIndexedNotNull { index, src ->
                runCatching {
                    val dst = File(dir, "photo_$index.jpg")
                    src.copyTo(dst, overwrite = true)
                    src.delete()
                    dst
                }.getOrNull()
            }
            if (moved.isEmpty()) {
                feedback.say("Couldn't save those photos. Check the phone's storage.")
                return@launch
            }

            repository.enqueue(
                moved,
                toteId = tote?.id,
                toteCode = tote?.code,
                name = _itemName.value,
                categoryId = _categoryId.value,
                describe = _describe.value,
            )
            UploadWorker.kick(WorkManager.getInstance(app))
            // Say it landed. The only signal used to be the thumbnail strip vanishing and a
            // counter incrementing further down the scroll — likely off-screen mid-batch, on
            // the one screen used with a bin open and hands full.
            val lost = shots.size - moved.size
            val photos = "${moved.size} photo${if (moved.size == 1) "" else "s"}"
            val what = _itemName.value.trim().ifEmpty { null }
            val queued = when {
                what != null && tote != null -> "Queued $what — $photos for ${tote.code}"
                what != null -> "Queued $what — $photos, bin decided at review"
                tote != null -> "Queued — $photos for ${tote.code}"
                else -> "Queued — $photos, bin decided at review"
            }
            // Counted out loud rather than swallowed: a batch that quietly queues 3 of 5 leaves
            // two holes in the catalogue that nobody knows to go back and fill.
            feedback.say(
                if (lost > 0) {
                    "$queued · $lost couldn't be read and " +
                        (if (lost == 1) "was" else "were") + " skipped"
                } else {
                    queued
                }
            )
            if (missing.isNotEmpty()) sweepOrphans()
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
        const val SHOTS_KEY = "capture_shot_paths"
        const val NAME_KEY = "capture_item_name"
        const val CATEGORY_KEY = "capture_category_id"
        const val DESCRIBE_KEY = "capture_describe"
    }
}
