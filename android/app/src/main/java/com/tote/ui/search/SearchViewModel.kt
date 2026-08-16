package com.tote.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tote.data.CatalogRepository
import com.tote.data.remote.ApiService
import com.tote.data.remote.ItemDto
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val results: List<ItemDto> = emptyList(),
    val searching: Boolean = false,
    /** True when the results came from the offline snapshot rather than the server. */
    val offline: Boolean = false,
    /** Null until a search has actually run — so the empty state can distinguish "no query yet"
     *  from "nothing matched", which are different things to say to someone. */
    val searched: Boolean = false,
    val totes: Int = 0,
    val items: Int = 0,
    val out: Int = 0,
    /**
     * Things out past the date they were due back.
     *
     * On the home screen rather than behind the People tab, because a lent thing is only
     * remembered by the person who lent it and they are not thinking about it — that is the whole
     * failure mode. Computed server-side against the household's local today, so this card and
     * the ntfy nudge can never disagree about what "overdue" means.
     */
    val overdue: List<ItemDto> = emptyList(),
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repo: CatalogRepository,
    private val api: ApiService,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            // A failed refresh is not an error the user needs to see here: the cache still has
            // the last good snapshot, and this screen's job is to answer questions, not to
            // report on connectivity. The offline flag on a search result is where that gets said.
            runCatching { repo.refresh() }
            val (t, i, o) = repo.stats()
            _state.value = _state.value.copy(totes = t, items = i, out = o)
            // Silent on failure, like the refresh above: this screen answers questions, and an
            // empty overdue list reads the same as a healthy one — which is honest, because an
            // unreachable server genuinely cannot tell you that anything is late.
            runCatching { api.overdue() }.onSuccess {
                _state.value = _state.value.copy(overdue = it)
            }
        }
    }

    fun onQueryChange(q: String) {
        _state.value = _state.value.copy(query = q)
        searchJob?.cancel()
        if (q.isBlank()) {
            _state.value = _state.value.copy(results = emptyList(), searched = false, searching = false)
            return
        }
        searchJob = viewModelScope.launch {
            // Debounce: someone typing "ratchet" would otherwise fire seven searches, and the
            // answers can arrive out of order. Cancelling the previous job also guarantees the
            // last query wins rather than the fastest.
            delay(250)
            _state.value = _state.value.copy(searching = true)
            val result = repo.search(q)
            _state.value = _state.value.copy(
                results = result.items,
                offline = result.offline,
                searching = false,
                searched = true,
            )
        }
    }
}
