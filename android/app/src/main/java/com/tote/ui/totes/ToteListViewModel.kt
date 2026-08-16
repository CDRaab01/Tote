package com.tote.ui.totes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tote.data.CatalogRepository
import com.tote.data.local.CachedTote
import com.tote.data.remote.ToteCreate
import com.tote.util.ApiErrors
import com.tote.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ToteListViewModel @Inject constructor(
    private val repo: CatalogRepository,
) : ViewModel() {

    /** Backed by the cache, so the list is on screen instantly and survives a dead network. */
    val totes: StateFlow<List<CachedTote>> =
        repo.cachedTotes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _create = MutableStateFlow<UiState<Unit>>(UiState.Idle)
    val create: StateFlow<UiState<Unit>> = _create.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
            runCatching { repo.refresh() }
            _refreshing.value = false
        }
    }

    fun createTote(code: String, label: String?) {
        viewModelScope.launch {
            _create.value = UiState.Loading
            runCatching { repo.createTote(ToteCreate(code = code.trim(), label = label?.ifBlank { null })) }
                .onSuccess { _create.value = UiState.Success(Unit) }
                .onFailure {
                    // 409 is the case worth naming: the code is printed on a physical card, so a
                    // duplicate is a real-world ambiguity and "already exists" is the useful thing
                    // to say, not "HTTP 409".
                    _create.value = UiState.Error(
                        if (ApiErrors.statusOf(it) == 409) "A tote with that code already exists."
                        else ApiErrors.message(it, "Couldn't create the tote.")
                    )
                }
        }
    }

    fun clearCreateState() {
        _create.value = UiState.Idle
    }
}
