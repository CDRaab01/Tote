package com.tote.ui.people

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tote.data.local.CachedTote
import com.tote.data.local.CatalogDao
import com.tote.data.remote.ApiService
import com.tote.data.remote.FitsDto
import com.tote.data.remote.ItemDto
import com.tote.data.remote.OutgrownIn
import com.tote.data.remote.PersonDto
import com.tote.data.remote.PersonSizeIn
import com.tote.util.ApiErrors
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The garment types the server accepts, in the order a person would think of them. */
val GARMENT_TYPES = listOf("tops", "bottoms", "shoes", "outerwear")

data class PersonDetailState(
    val person: PersonDto? = null,
    val fits: FitsDto? = null,
    val onLoan: List<ItemDto> = emptyList(),
    val totes: List<CachedTote> = emptyList(),
    val garmentType: String? = null,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
)

/**
 * One person: what size they are, what we own that fits, and what they have of ours.
 *
 * **The fits query is answered server-side and only displayed here.** Matching a size against
 * the ladder is exactly the kind of derived logic that has one writer in this app — a client
 * that computed its own answer would eventually disagree with the ntfy nudge and the catalog,
 * and the person in the attic has no way to tell which of the two lied.
 */
@HiltViewModel
class PersonDetailViewModel @Inject constructor(
    private val api: ApiService,
    private val catalogDao: CatalogDao,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val personId: String = checkNotNull(savedStateHandle["personId"])

    private val _state = MutableStateFlow(PersonDetailState())
    val state: StateFlow<PersonDetailState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            // Bins from the Room cache, so the outgrown destination picker is populated even
            // when this is being done on the way back from the garage.
            val totes = runCatching { catalogDao.totes().first() }.getOrDefault(emptyList())
            try {
                val person = api.person(personId)
                val fits = runCatching { api.fits(personId, _state.value.garmentType) }.getOrNull()
                val onLoan = runCatching { api.onLoan(personId) }.getOrDefault(emptyList())
                _state.value = _state.value.copy(
                    person = person,
                    fits = fits,
                    onLoan = onLoan,
                    totes = totes,
                    loading = false,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    totes = totes,
                    error = ApiErrors.message(e, "Couldn't load this person."),
                )
            }
        }
    }

    /** Narrow the fits query to one garment type, or back to all of them with null. */
    fun setGarmentType(type: String?) {
        _state.value = _state.value.copy(garmentType = type)
        viewModelScope.launch {
            val fits = runCatching { api.fits(personId, type) }.getOrNull() ?: return@launch
            _state.value = _state.value.copy(fits = fits)
        }
    }

    /**
     * Record a size reading.
     *
     * Only `sizeRaw` is sent. The system and ordinal are derived server-side from it, and a
     * client that could set them could file a 4T indexed as an adult L — which would then match
     * on every fits query forever, silently.
     */
    fun addSize(garmentType: String, sizeRaw: String) {
        if (sizeRaw.isBlank()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            runCatching {
                api.addPersonSize(
                    personId,
                    PersonSizeIn(garmentType = garmentType, sizeRaw = sizeRaw.trim()),
                )
            }
                .onSuccess {
                    _state.value = _state.value.copy(busy = false)
                    load()
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        busy = false,
                        error = ApiErrors.message(it, "Couldn't record that size."),
                    )
                }
        }
    }

    /** File a run of outgrown items into a bin — one action, one transaction, one ledger entry each. */
    fun markOutgrown(itemIds: List<String>, toteId: String) {
        if (itemIds.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            runCatching { api.outgrown(personId, OutgrownIn(itemIds = itemIds, toteId = toteId)) }
                .onSuccess {
                    _state.value = _state.value.copy(busy = false)
                    load()
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        busy = false,
                        error = ApiErrors.message(it, "Couldn't file those away."),
                    )
                }
        }
    }

    /**
     * A borrowed thing came home — into a specific bin.
     *
     * The destination is required, not optional: `returned` is an inbound reason and the server
     * rejects it without one (422). That is the right rule and the UI honours it rather than
     * working around it — an item that is "back" but in no bin is exactly the state the whole
     * catalog exists to make impossible.
     */
    fun markReturned(itemId: String, toteId: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            runCatching {
                api.move(
                    itemId,
                    com.tote.data.remote.MoveRequest(reason = "returned", toToteId = toteId),
                )
            }
                .onSuccess {
                    _state.value = _state.value.copy(busy = false)
                    load()
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        busy = false,
                        error = ApiErrors.message(it, "Couldn't mark that returned."),
                    )
                }
        }
    }
}
