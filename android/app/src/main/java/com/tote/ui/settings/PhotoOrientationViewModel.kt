package com.tote.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tote.data.remote.ApiService
import com.tote.data.remote.BulkRotateRequest
import com.tote.data.remote.PhotoOrientationDto
import com.tote.data.remote.PhotoRotationDto
import com.tote.util.ApiErrors
import com.tote.util.FeedbackBus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PhotoOrientationState(
    val photos: List<PhotoOrientationDto> = emptyList(),
    val loaded: Boolean = false,
    val unreachable: Boolean = false,
    /**
     * Corrections made on this screen but not yet saved, keyed by photo.
     *
     * Held apart from [photos] rather than mutated into it so the screen can say how many are
     * pending, and so leaving without saving changes nothing — a grid that wrote on every tap
     * would turn a mis-tap into a round trip and a wrong angle recorded as fact.
     */
    val pending: Map<PhotoKey, Int> = emptyMap(),
    val busy: Boolean = false,
) {
    /** What each tile should draw at right now: the pending turn if there is one, else stored. */
    fun rotationOf(photo: PhotoOrientationDto): Int =
        pending[PhotoKey(photo.itemId, photo.order)] ?: photo.rotation

    val changeCount: Int get() = pending.size
}

/** A photograph's address. `item_id` alone is not unique — an item can hold eight. */
data class PhotoKey(val itemId: String, val order: Int)

/**
 * Putting the catalogue's photographs the right way up.
 *
 * This screen exists because of a defect now closed at the source: until v1.0.57 the capture path
 * decoded with BitmapFactory (which ignores the EXIF Orientation tag) and re-encoded with
 * Bitmap.compress (which writes none), so a portrait photo was uploaded as sideways pixels with
 * nothing left in the file to say so. New photographs are baked upright at capture. For the ones
 * already on the volume there is nothing to infer from, and this app does not guess — so a person
 * looks at them and says, and the answer is stored as a correction rather than re-encoded into
 * the one artefact here that cannot be recreated.
 *
 * Nothing is written until **Save**: one request for the whole pass, all or nothing, because a
 * partial save leaves a grid somebody has just finished correcting half-corrected with nothing on
 * screen saying which half.
 */
@HiltViewModel
class PhotoOrientationViewModel @Inject constructor(
    private val api: ApiService,
    private val feedback: FeedbackBus,
) : ViewModel() {

    private val _state = MutableStateFlow(PhotoOrientationState())
    val state: StateFlow<PhotoOrientationState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { api.photosForOrientation() }
                .onSuccess {
                    _state.value = _state.value.copy(
                        photos = it,
                        loaded = true,
                        unreachable = false,
                        // Dropped on a reload: they described the list we just replaced.
                        pending = emptyMap(),
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(loaded = true, unreachable = true)
                }
        }
    }

    /** One tap: a quarter turn clockwise, wrapping back to upright on the fourth. */
    fun turn(photo: PhotoOrientationDto) {
        val key = PhotoKey(photo.itemId, photo.order)
        val next = (_state.value.rotationOf(photo) + 90) % 360
        val pending = _state.value.pending.toMutableMap()
        // Back to what the server already has is not a change — dropping it keeps the count
        // honest and stops a save writing rows that say nothing.
        if (next == photo.rotation) pending.remove(key) else pending[key] = next
        _state.value = _state.value.copy(pending = pending)
    }

    fun discardChanges() {
        _state.value = _state.value.copy(pending = emptyMap())
    }

    fun save(onSaved: () -> Unit = {}) {
        val pending = _state.value.pending
        if (pending.isEmpty() || _state.value.busy) return
        // Set before the launch: two taps land synchronously, and a guard that only trips once
        // the coroutine runs lets both through.
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            runCatching {
                api.bulkRotate(
                    BulkRotateRequest(
                        pending.map { (key, rotation) ->
                            PhotoRotationDto(
                                itemId = key.itemId,
                                order = key.order,
                                rotation = rotation,
                            )
                        }
                    )
                )
            }.onSuccess {
                val count = pending.size
                feedback.say(
                    "Turned $count photograph${if (count == 1) "" else "s"} the right way up."
                )
                refresh()
                onSaved()
            }.onFailure {
                feedback.say(
                    ApiErrors.detail(it) ?: ApiErrors.message(it, "Couldn't save those turns.")
                )
            }
            _state.value = _state.value.copy(busy = false)
        }
    }
}
