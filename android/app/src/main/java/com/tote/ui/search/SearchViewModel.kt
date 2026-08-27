package com.tote.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tote.data.CatalogRepository
import com.tote.data.remote.ApiService
import com.tote.data.remote.CategoryDto
import com.tote.data.remote.ItemDto
import com.tote.data.remote.NextSizeCardDto
import com.tote.data.remote.SeasonalCardDto
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Chip-row cap. A drawer of mixed hand-me-downs can carry a dozen distinct tag readings; eight
 *  chips is about two rows on a narrow phone — enough to narrow with, few enough that the row
 *  never crowds out the results it exists to narrow. */
private const val SIZE_CHIP_CAP = 8

data class SearchUiState(
    val query: String = "",
    val results: List<ItemDto> = emptyList(),
    /**
     * The server's trigram near-misses — "wellies" for "welles" — non-empty ONLY when [results]
     * is empty (the server adds them precisely because full-text found nothing, so the two never
     * compete for the screen), and always empty offline. Rendered under their own header so a
     * near-miss can never masquerade as the thing that was typed.
     */
    val close: List<ItemDto> = emptyList(),
    val searching: Boolean = false,
    /** True when the results came from the offline snapshot rather than the server. */
    val offline: Boolean = false,
    /** Null until a search has actually run — so the empty state can distinguish "no query yet"
     *  from "nothing matched", which are different things to say to someone. */
    val searched: Boolean = false,
    /**
     * The distinct sizes present in the current UNFILTERED exact hits, first-appearance order,
     * capped at [SIZE_CHIP_CAP] — the chip row's vocabulary. Deliberately not re-derived from a
     * filtered response: narrowing to 4T must not collapse the row to the one chip just chosen,
     * with no way back to the others. Empty offline — the fallback cannot filter through the
     * ladder, and a chip that silently does nothing teaches distrust of every other one.
     */
    val sizes: List<String> = emptyList(),
    /** The size currently narrowing the results, null for "Any size". A new query clears it —
     *  a filter chosen against the old hits would silently narrow a new question. */
    val sizeFilter: String? = null,
    val totes: Int = 0,
    val items: Int = 0,
    /**
     * Catalogued, in no bin — the same set the Totes tab calls "Not in a bin" and the Unfiled
     * screen lists, which is why it is named for that rather than for "out".
     */
    val notInABin: Int = 0,
    /**
     * The browse entry point: categories that actually hold something, most-used first (the
     * server's order). Empty both when the household has filed nothing into any category AND
     * when the server was unreachable — deliberately, because the chips are an invitation, not
     * a report, and an offline "couldn't load browse" banner on the search screen would be
     * noise over a screen that still works against the cache.
     */
    val usedCategories: List<CategoryDto> = emptyList(),
    /**
     * Things out past the date they were due back.
     *
     * On the home screen rather than behind the People tab, because a lent thing is only
     * remembered by the person who lent it and they are not thinking about it — that is the whole
     * failure mode. Computed server-side against the household's local today, so this card and
     * the ntfy nudge can never disagree about what "overdue" means.
     */
    val overdue: List<ItemDto> = emptyList(),
    /**
     * The two forward-looking home cards: the bins that got unpacked around this time last year,
     * and the wearer closest to their next size. Either is null when the server has nothing
     * worth saying — and both are CLEARED when it cannot be asked, unlike [overdue]: each card
     * is an invitation to go open specific bins, and an invitation the server no longer stands
     * behind is worse than none.
     */
    val seasonal: SeasonalCardDto? = null,
    val nextSize: NextSizeCardDto? = null,
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repo: CatalogRepository,
    private val api: ApiService,
    savedState: SavedStateHandle,
) : ViewModel() {

    /**
     * A bin code handed over by a tag that resolved to nothing.
     *
     * `Routes.SEARCH` has declared a `q` argument since #23 and the dead-tag path has always
     * passed one — and nothing ever read it, so the documented "an unresolvable tag pre-fills
     * search with the code instead of discarding it" has never once happened. The code was
     * spoken in a snackbar and dropped, which is exactly the information someone standing in
     * front of an unlabelled bin has and cannot retype.
     */
    private val handedOver: String = savedState.get<String>("q").orEmpty()

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        // Collected, not sampled. The counts follow the cache for the ViewModel's whole life, so
        // a refresh that lands later — or one that returned early because another already held
        // the lock — still reaches the tiles.
        viewModelScope.launch {
            repo.cachedStats.collect { stats ->
                _state.value = _state.value.copy(
                    totes = stats.totes,
                    items = stats.items,
                    notInABin = stats.notInABin,
                )
            }
        }
        refresh()
        if (handedOver.isNotBlank()) {
            // Straight to the answer, no debounce: this query was not typed, so there is no
            // next keystroke to wait for.
            _state.value = _state.value.copy(query = handedOver)
            runSearch(handedOver, size = null, debounce = false)
        }
    }

    /**
     * The keyboard's Search key.
     *
     * `keyboardOptions` set the action and nothing handled it, so the key drew a magnifier and
     * did nothing — an affordance that lies. Skips the debounce, because pressing Search IS the
     * statement that you have finished typing.
     */
    fun onSearchAction() {
        val s = _state.value
        if (s.query.isBlank()) return
        runSearch(s.query, size = s.sizeFilter, debounce = false)
    }

    fun refresh() {
        viewModelScope.launch {
            // A failed refresh is not an error the user needs to see here: the cache still has
            // the last good snapshot, and this screen's job is to answer questions, not to
            // report on connectivity. The offline flag on a search result is where that gets said.
            runCatching { repo.refresh() }
            // Silent on failure, like the refresh above: this screen answers questions, and an
            // empty overdue list reads the same as a healthy one — which is honest, because an
            // unreachable server genuinely cannot tell you that anything is late.
            runCatching { api.overdue() }.onSuccess {
                _state.value = _state.value.copy(overdue = it)
            }
            // Used categories only: eleven empty seeded rows as chips would be the picker
            // clutter this feature exists to remove, reproduced on the home screen.
            runCatching { api.categories() }.onSuccess { categories ->
                _state.value = _state.value.copy(
                    usedCategories = categories.filter { it.itemCount > 0 }
                )
            }
            // Both cards from one answer — and, unlike the overdue list above, cleared rather
            // than left standing when the server cannot be asked (see the state's KDoc).
            val home = runCatching { repo.home() }.getOrNull()
            _state.value = _state.value.copy(seasonal = home?.seasonal, nextSize = home?.nextSize)
        }
    }

    fun onQueryChange(q: String) {
        // A new query clears the size filter: the chip was chosen against the OLD hits, and
        // carrying it forward would silently narrow a question nobody has asked yet.
        _state.value = _state.value.copy(query = q, sizeFilter = null)
        searchJob?.cancel()
        if (q.isBlank()) {
            _state.value = _state.value.copy(
                results = emptyList(),
                close = emptyList(),
                sizes = emptyList(),
                searched = false,
                searching = false,
            )
            return
        }
        runSearch(q, size = null, debounce = true)
    }

    /**
     * Narrow (or widen, with null — "Any size") the current results by one of the chip row's
     * sizes. Re-asks the server rather than filtering locally: the ladder has one writer, and
     * "4T" must match a garment the way the server says it does, not by string equality. No
     * debounce — a chip tap is one deliberate gesture, not a keystroke mid-word.
     */
    fun onSizeSelect(size: String?) {
        val q = _state.value.query
        if (q.isBlank()) return
        _state.value = _state.value.copy(sizeFilter = size)
        runSearch(q, size = size, debounce = false)
    }

    private fun runSearch(q: String, size: String?, debounce: Boolean) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (debounce) {
                // Debounce: someone typing "ratchet" would otherwise fire seven searches, and
                // the answers can arrive out of order. Cancelling the previous job also
                // guarantees the last query wins rather than the fastest.
                delay(250)
            }
            _state.value = _state.value.copy(searching = true)
            val result = repo.search(q, size = size)
            _state.value = _state.value.copy(
                results = result.items,
                close = result.close,
                offline = result.offline,
                searching = false,
                searched = true,
                // The chip vocabulary comes from the UNFILTERED hits only, and empties out
                // offline — see the field's KDoc for both halves of that rule.
                sizes = when {
                    result.offline -> emptyList()
                    size == null ->
                        result.items.mapNotNull { it.apparel?.sizeRaw }
                            .distinct()
                            .take(SIZE_CHIP_CAP)
                    else -> _state.value.sizes
                },
                // The offline fallback ignored the filter, so a selected chip over its results
                // would claim a narrowing that never happened.
                sizeFilter = if (result.offline) null else size,
            )
        }
    }
}
