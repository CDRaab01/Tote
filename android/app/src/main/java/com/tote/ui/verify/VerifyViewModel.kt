package com.tote.ui.verify

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tote.data.CatalogRepository
import com.tote.data.remote.ItemDto
import com.tote.data.remote.ToteDetailDto
import com.tote.util.ApiErrors
import com.tote.util.FeedbackBus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException

/**
 * One pass over one open bin: everything the catalog says is stored in it, declared present or
 * missing, in a single answer.
 *
 * The screen exists because a catalogue of physical objects drifts silently. Things get borrowed
 * by hand, moved to another bin during a tidy, or thrown away — none of which passes through the
 * app — and nothing about a stale row looks stale. A verify pass is the only moment the two
 * copies of the truth are compared with the lid open, so the date it writes is worth more than
 * any single row it corrects.
 *
 * Partial coverage is refused rather than accepted: a pass that skipped half the bin would stamp
 * "checked" over items nobody looked at, which is worse than never checking at all, because the
 * date is exactly what makes the rest of the catalog believable.
 */
@HiltViewModel
class VerifyViewModel @Inject constructor(
    private val repo: CatalogRepository,
    private val feedback: FeedbackBus,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val toteId: String = checkNotNull(savedState["toteId"])

    private val _state = MutableStateFlow(VerifyUiState())
    val state: StateFlow<VerifyUiState> = _state.asStateFlow()

    /**
     * Emitted once the pass has landed, so the screen can leave.
     *
     * A signal rather than a flag, for the same reason the item sheet reports its writes this
     * way: the navigation happens once, and a boolean left true would send the screen back
     * again on the next recomposition.
     */
    private val _done = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val done: SharedFlow<Unit> = _done

    init {
        load()
    }

    /**
     * A live read, never the cache.
     *
     * The answer sheet has to be the bin as the server currently understands it: verifying
     * against a snapshot taken before somebody else moved something would record a decision
     * about an item that is no longer in this bin, and the server would refuse the pass for
     * coverage anyway.
     */
    fun load() {
        viewModelScope.launch {
            // Clearing the error is what puts the spinner back on a retry: no bin and no error
            // IS the loading state, so there is no fourth flag to keep in step with the others.
            _state.value = _state.value.copy(error = null)
            runCatching { repo.tote(toteId) }
                .onSuccess { _state.value = _state.value.copy(tote = it) }
                .onFailure {
                    _state.value = _state.value.copy(
                        error = ApiErrors.message(it, "Couldn't load this bin."),
                    )
                }
        }
    }

    /**
     * Say where one item is: in the bin, or not.
     *
     * A choice, not a toggle — tapping the chip that is already lit changes nothing. Undeciding
     * an item can only ever take the pass further from the full coverage the server requires,
     * and a mis-tap is corrected by tapping the other chip, which is one gesture either way.
     */
    fun mark(itemId: String, here: Boolean) {
        val current = _state.value
        _state.value = current.copy(
            present = if (here) current.present + itemId else current.present - itemId,
            missing = if (here) current.missing - itemId else current.missing + itemId,
        )
    }

    /**
     * Send the pass.
     *
     * Refused here as well as disabled on the screen: the server 422s on partial coverage, and
     * asking it anyway would spend a round trip — in an attic, on the worst Wi-Fi in the house —
     * to be told something the client already knows.
     */
    fun finish() {
        val current = _state.value
        val tote = current.tote ?: return
        if (!current.complete || current.submitting) return

        viewModelScope.launch {
            _state.value = current.copy(submitting = true)
            runCatching {
                repo.verifyTote(
                    toteId = tote.id,
                    // Ordered by the bin's own list rather than by the sets, so the request is
                    // reproducible and reads like the screen that produced it.
                    present = current.items.map { it.id }.filter { it in current.present },
                    missing = current.items.map { it.id }.filter { it in current.missing },
                )
            }
                .onSuccess { result ->
                    _state.value = _state.value.copy(submitting = false)
                    feedback.say(
                        if (result.missingCount == 0) {
                            "Verified ${tote.code} — everything accounted for"
                        } else {
                            "Verified ${tote.code} — ${result.missingCount} marked missing"
                        }
                    )
                    _done.tryEmit(Unit)
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(submitting = false)
                    // The server's own sentence first: a refusal here names WHICH part of the
                    // bin was not declared, and that is the whole of what to do about it.
                    feedback.say(
                        refusal(e) ?: ApiErrors.message(e, "Couldn't record that check.")
                    )
                }
        }
    }
}

/**
 * The screen, in one shape.
 *
 * Flat and stateless so the whole thing can be screenshotted at every stage of a pass, which is
 * the only way the two-chip row gets looked at in both themes.
 */
data class VerifyUiState(
    /** Null with no [error] is the loading state — the bin is the screen. */
    val tote: ToteDetailDto? = null,
    val present: Set<String> = emptySet(),
    val missing: Set<String> = emptySet(),
    val error: String? = null,
    val submitting: Boolean = false,
) {
    /**
     * What is being verified: the items the catalog says are STORED in this bin.
     *
     * Not `itemsOut` — something already recorded as out of the bin is not expected to be in it,
     * and asking someone to confirm the absence of thirty things they lent out last spring is
     * how a two-minute pass becomes one nobody finishes.
     */
    val items: List<ItemDto> get() = tote?.items.orEmpty()

    val decided: Int get() = items.count { it.id in present || it.id in missing }

    /**
     * Full coverage — the only state the server accepts.
     *
     * An empty bin qualifies immediately, and that is the point rather than an edge case: a bin
     * whose contents are entirely out is still worth stamping, because "checked, and it really
     * is empty" is a fact the catalog cannot otherwise hold.
     */
    val complete: Boolean get() = tote != null && decided == items.size
}

/**
 * The server's own sentence for a refused pass, or null.
 *
 * Read with kotlinx.serialization rather than through `ApiErrors.detail`, for the reason
 * `ApiErrors.conflicts` already documents beside it: `unitTests.isReturnDefaultValues = true`
 * stubs the whole `org.json` package on the JVM, so an org.json parser returns nothing in every
 * unit test and looks like it works. This sentence IS the message — it names the part of the bin
 * that was not declared — so it cannot be the untested one.
 *
 * Consumes the one-shot error body, so nothing else may read it afterwards.
 */
private fun refusal(t: Throwable): String? {
    val body = (t as? HttpException)?.response()?.errorBody()?.string() ?: return null
    return runCatching {
        Json.parseToJsonElement(body).jsonObject["detail"]!!.jsonPrimitive.content
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
