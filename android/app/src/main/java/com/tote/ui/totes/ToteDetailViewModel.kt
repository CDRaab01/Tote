package com.tote.ui.totes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tote.data.CatalogRepository
import com.tote.data.remote.ItemCreate
import com.tote.data.remote.ToteDetailDto
import com.tote.nfc.TagIo
import com.tote.nfc.TagWriteResult
import com.tote.nfc.WriteState
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
                    _state.value = UiState.Error("Couldn't load this tote. Check you're on the tailnet.")
                }
        }
    }

    fun addItem(name: String, quantity: Int) {
        viewModelScope.launch {
            runCatching {
                repo.createItem(ItemCreate(name = name.trim(), quantity = quantity, toteId = toteId))
            }.onSuccess { load() }
        }
    }

    fun unpackAll() {
        viewModelScope.launch {
            // null, not emptyList: null means "everything", and the server treats [] as an
            // explicit selection of nothing.
            runCatching { repo.unpack(toteId, itemIds = null) }.onSuccess { load() }
        }
    }

    fun repackAll() {
        viewModelScope.launch {
            runCatching { repo.repack(toteId, itemIds = null) }.onSuccess { load() }
        }
    }

    fun moveOut(itemId: String) {
        viewModelScope.launch {
            runCatching {
                repo.move(itemId, com.tote.data.remote.MoveRequest(reason = "unpacked"))
            }.onSuccess { load() }
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
            }.onSuccess { load() }
        }
    }
}
