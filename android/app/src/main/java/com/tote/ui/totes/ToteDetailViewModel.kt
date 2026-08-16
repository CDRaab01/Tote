package com.tote.ui.totes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tote.data.CatalogRepository
import com.tote.data.remote.ItemCreate
import com.tote.data.remote.ToteDetailDto
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
