package com.tote.ui.totes

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tote.data.CatalogRepository
import com.tote.data.local.CachedItem
import com.tote.data.local.CachedTote
import com.tote.data.remote.LocationDto
import com.tote.data.remote.ToteCreate
import com.tote.util.ApiErrors
import com.tote.util.FeedbackBus
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
    // For reading a picked photo back out of the system picker — a `content://` Uri only opens
    // through a ContentResolver, same as the capture flow's gallery path.
    private val app: Application,
    private val repo: CatalogRepository,
    // Filing a selection is a user-initiated write, so it speaks — success and failure both.
    private val feedback: FeedbackBus,
) : ViewModel() {

    /** Backed by the cache, so the list is on screen instantly and survives a dead network. */
    val totes: StateFlow<List<CachedTote>> =
        repo.cachedTotes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Catalogued, in no bin.
     *
     * Surfaced here rather than left to search because it is a to-do the person created on
     * purpose — deferring the destination at review is only reasonable if there is somewhere
     * the deferred things visibly accumulate.
     */
    val unfiled: StateFlow<List<CachedItem>> =
        repo.cachedUnfiled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Which loose ends are ticked, if any — null when not selecting, same shape as a bin's.
     *
     * Filing is the whole purpose of this list and it was the one thing it could not do in bulk.
     * Thirty-two garments catalogued without a destination meant thirty-two trips through the
     * item sheet, which is enough friction to stop anyone from deferring a destination again —
     * and deferring is a feature this app deliberately added.
     */
    private val _unfiledSelection = MutableStateFlow<Set<String>?>(null)
    val unfiledSelection: StateFlow<Set<String>?> = _unfiledSelection.asStateFlow()

    fun beginFiling(itemId: String? = null) {
        _unfiledSelection.value = setOfNotNull(itemId)
    }

    fun cancelFiling() {
        _unfiledSelection.value = null
    }

    fun toggleUnfiled(itemId: String) {
        val current = _unfiledSelection.value ?: return
        _unfiledSelection.value =
            if (itemId in current) current - itemId else current + itemId
    }

    fun selectAllUnfiled(ids: List<String>) {
        _unfiledSelection.value = ids.toSet()
    }

    /**
     * File everything ticked into one bin.
     *
     * `bulkMove`, so the server writes one ledger row per item and picks each reason itself —
     * `moved` for something that was never in a bin, which is what all of these are.
     */
    fun fileSelected(toteId: String) {
        val ids = _unfiledSelection.value.orEmpty().toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            runCatching { repo.bulkMove(ids, toToteId = toteId) }
                .onSuccess {
                    _unfiledSelection.value = null
                    refresh()
                    feedback.say("Filed ${ids.size} item${if (ids.size == 1) "" else "s"}.")
                }
                .onFailure { feedback.say(ApiErrors.message(it, "Couldn't file those.")) }
        }
    }

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
