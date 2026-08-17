package com.tote.ui.totes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tote.data.CatalogRepository
import com.tote.data.local.CachedTote
import com.tote.data.remote.LocationDto
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

    /** Put away, not thrown away. Kept apart from the live list and collapsed by default. */
    val archived: StateFlow<List<CachedTote>> =
        repo.cachedArchivedTotes
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * On success this carries the **new bin's id**, because creating one is never the goal.
     *
     * The goal is a labelled bin, and everything that labels it — write the tag, print the card —
     * lives on its detail screen. The dialog used to close onto the list, leaving the one screen
     * that finishes the job a scroll and a tap away, which is how a bin ends up catalogued and
     * unlabelled: the exact failure the app exists to prevent.
     */
    private val _create = MutableStateFlow<UiState<String>>(UiState.Idle)
    val create: StateFlow<UiState<String>> = _create.asStateFlow()

    /** Places, for the create dialog's picker. Also the list an inline "New location…" grows. */
    private val _locations = MutableStateFlow<List<LocationDto>>(emptyList())
    val locations: StateFlow<List<LocationDto>> = _locations.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /**
     * Whether the last sync failed, so an empty cache can tell the truth about WHY it is empty.
     *
     * Only meaningful alongside an empty list. With bins in the cache a failed refresh is not
     * worth saying — the screen still answers the question it was opened for. With none, the
     * difference matters: "no totes yet" over a household with fourteen bins is a lie that
     * invites someone to create A14 for the second time. The same shape as the review tab that
     * read "Nothing waiting" over a badge of 4.
     */
    private val _unreachable = MutableStateFlow(false)
    val unreachable: StateFlow<Boolean> = _unreachable.asStateFlow()

    /**
     * True until the first sync settles, so a cold start does not accuse an empty cache of being
     * an empty catalog.
     *
     * The unreachable flag above only covers FAILURE; while the first request is still in flight
     * the list was empty, `unreachable` was false, and the screen confidently said "No totes yet"
     * — the exact lie that invites someone to create A14 for the second time. Loading is the
     * third state, and it is not the same as either neighbour.
     */
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
            _unreachable.value = runCatching { repo.refresh() }.isFailure
            _refreshing.value = false
            _loading.value = false
        }
    }

    fun loadLocations() {
        viewModelScope.launch {
            runCatching { repo.locations() }.onSuccess { _locations.value = it }
        }
    }

    /** Add a place without leaving the dialog, and select it. */
    fun createLocation(name: String, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { repo.createLocation(name) }
                .onSuccess {
                    _locations.value = _locations.value + it
                    onCreated(it.id)
                }
                .onFailure { _create.value = UiState.Error(ApiErrors.message(it, "Couldn't add that place.")) }
        }
    }

    fun createTote(code: String, label: String?, locationId: String?) {
        viewModelScope.launch {
            _create.value = UiState.Loading
            runCatching {
                repo.createTote(
                    ToteCreate(
                        code = code.trim(),
                        label = label?.ifBlank { null },
                        locationId = locationId,
                    )
                )
            }
                .onSuccess { _create.value = UiState.Success(it.id) }
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
