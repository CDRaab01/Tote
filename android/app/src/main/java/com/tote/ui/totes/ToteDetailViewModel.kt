package com.tote.ui.totes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tote.data.CatalogRepository
import com.tote.data.remote.ApiService
import com.tote.data.remote.ItemCreate
import com.tote.data.remote.PersonDto
import com.tote.data.remote.ToteDetailDto
import com.tote.nfc.TagIo
import com.tote.nfc.TagWriteResult
import com.tote.nfc.WriteState
import com.tote.util.ApiErrors
import com.tote.util.FeedbackBus
import com.tote.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class ToteDetailViewModel @Inject constructor(
    private val repo: CatalogRepository,
    private val api: ApiService,
    private val feedback: FeedbackBus,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val toteId: String = checkNotNull(savedState["toteId"])

    private val _state = MutableStateFlow<UiState<ToteDetailDto>>(UiState.Loading)
    val state: StateFlow<UiState<ToteDetailDto>> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            runCatching { repo.tote(toteId) }
                .onSuccess { _state.value = UiState.Success(it) }
                .onFailure {
                    // Detail is a live read, not a cached one: unpack/repack decisions are made
                    // standing in front of the bin, and acting on a stale list is how the
                    // catalog and the attic diverge.
                    _state.value = UiState.Error(ApiErrors.message(it, "Couldn't load this tote."))
                }
        }
    }

    fun addItem(name: String, quantity: Int) {
        viewModelScope.launch {
            // Every write on this screen used to swallow failure whole — offline in the
            // attic (the documented normal condition) the buttons simply looked broken. The
            // failures speak through the app-wide snackbar because the outcome may land after
            // the screen has moved on.
            runCatching {
                repo.createItem(ItemCreate(name = name.trim(), quantity = quantity, toteId = toteId))
            }
                .onSuccess { load() }
                .onFailure { feedback.say(ApiErrors.message(it, "Couldn't add that item.")) }
        }
    }

    fun unpackAll() {
        viewModelScope.launch {
            // null, not emptyList: null means "everything", and the server treats [] as an
            // explicit selection of nothing.
            runCatching { repo.unpack(toteId, itemIds = null) }
                .onSuccess { load() }
                .onFailure { feedback.say(ApiErrors.message(it, "Couldn't unpack the bin.")) }
        }
    }

    fun repackAll() {
        viewModelScope.launch {
            runCatching { repo.repack(toteId, itemIds = null) }
                .onSuccess { load() }
                .onFailure { feedback.say(ApiErrors.message(it, "Couldn't repack the bin.")) }
        }
    }

    /**
     * Who could borrow something, for the lend picker.
     *
     * Loaded lazily when the sheet opens rather than with the screen: most visits to a bin are
     * about what is in it, and a people request on every open would be a round trip nobody asked
     * for on the tab used most in a garage.
     */
    private val _people = MutableStateFlow<List<PersonDto>>(emptyList())
    val people: StateFlow<List<PersonDto>> = _people.asStateFlow()

    fun loadPeople() {
        viewModelScope.launch {
            runCatching { api.people() }.onSuccess { _people.value = it }
        }
    }

    /**
     * Lend an item out.
     *
     * `personId` is what makes this different from taking something out: the item row will know
     * it is `loaned`, but only the movement knows to whom, and "who has the drill" is answered
     * from the ledger. A null `expectedBack` is allowed and honest — plenty of lending happens
     * without a date, and inventing one would manufacture an overdue nudge nobody agreed to.
     */
    fun lend(itemId: String, personId: String, expectedBack: String?) {
        viewModelScope.launch {
            runCatching {
                repo.move(
                    itemId,
                    com.tote.data.remote.MoveRequest(
                        reason = "loaned",
                        personId = personId,
                        expectedBack = expectedBack?.takeIf { it.isNotBlank() },
                    ),
                )
            }
                .onSuccess { load() }
                .onFailure { feedback.say(ApiErrors.message(it, "Couldn't lend that out.")) }
        }
    }

    /**
     * Delete an item outright — the row, its ledger, and its photographs.
     *
     * NOT the same operation as disposing of something. "We no longer own this" is a `disposed`
     * movement and keeps the history; this is for a row that should never have existed, which in
     * practice means a duplicate or a typo. The server takes the photo files with it.
     */
    fun deleteItem(itemId: String) {
        viewModelScope.launch {
            runCatching { repo.deleteItem(itemId) }
                .onSuccess { load() }
                .onFailure { feedback.say(ApiErrors.message(it, "Couldn't delete that item.")) }
        }
    }

    fun moveOut(itemId: String) {
        viewModelScope.launch {
            runCatching {
                repo.move(itemId, com.tote.data.remote.MoveRequest(reason = "unpacked"))
            }
                .onSuccess { load() }
                .onFailure { feedback.say(ApiErrors.message(it, "Couldn't take that out.")) }
        }
    }

    private val _write = MutableStateFlow<WriteState>(WriteState.Idle)
    val write: StateFlow<WriteState> = _write.asStateFlow()

    /** The URL of this tote's printable card, for handing to a browser or a print service. */
    fun cardUrl(): String =
        com.tote.BuildConfig.SERVER_URL.trimEnd('/') + "/totes/" + toteId + "/card"

    fun beginWrite() {
        _write.value = WriteState.Waiting
    }

    fun cancelWrite() {
        _write.value = WriteState.Idle
    }

    /**
     * Called when a tag lands in the field while the write sheet is open.
     *
     * The uid is recorded on the server ONLY after the physical write succeeded. Recording first
     * would leave the database claiming a tag exists that was never written, and the person would
     * find that out in an attic holding a bin whose tag does nothing.
     */
    fun onTagPresented(tag: android.nfc.Tag) {
        val current = (_state.value as? UiState.Success)?.data ?: return
        viewModelScope.launch {
            val base = runCatching { repo.nfcBase() }.getOrNull()
            if (base == null) {
                _write.value =
                    WriteState.Problem("Couldn't reach the server to build the tag's link.")
                return@launch
            }
            val uri = base.trimEnd('/') + "/t/" + current.code
            val summary = TagIo.summaryFor(
                code = current.code,
                label = current.label,
                location = null,
                itemCount = current.itemCount,
            )
            when (val result = TagIo.write(tag, uri, summary)) {
                is TagWriteResult.Written -> {
                    runCatching { repo.recordTagWrite(toteId, result.uid) }
                        .onSuccess {
                            _write.value = WriteState.Done(result.truncatedSummary)
                            load()
                        }
                        .onFailure {
                            // The tag IS written; only the record failed. Say exactly that, so
                            // nobody rewrites a tag that is already correct.
                            _write.value = WriteState.Problem(
                                "The tag was written but couldn't be recorded. " +
                                    "Try again when you're back on the tailnet."
                            )
                        }
                }
                is TagWriteResult.ReadOnly ->
                    _write.value = WriteState.Problem("That tag is locked. Use a fresh one.")
                is TagWriteResult.TooSmall ->
                    _write.value = WriteState.Problem(
                        "That tag is too small (" + result.capacity + " bytes, needs " +
                            result.needed + ")."
                    )
                is TagWriteResult.Failed -> _write.value = WriteState.Problem(result.reason)
            }
        }
    }

    fun putBack(itemId: String) {
        viewModelScope.launch {
            runCatching {
                repo.move(
                    itemId,
                    com.tote.data.remote.MoveRequest(reason = "repacked", toToteId = toteId),
                )
            }
                .onSuccess { load() }
                .onFailure { feedback.say(ApiErrors.message(it, "Couldn't put that back.")) }
        }
    }
}
