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
import com.tote.data.remote.PersonPatch
import com.tote.data.remote.PersonSizeDto
import com.tote.data.remote.PersonSizeIn
import com.tote.util.ApiErrors
import com.tote.util.FeedbackBus
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
    /**
     * Every recorded size, newest first — not just what is current.
     *
     * `person.currentSizes` answers "what size is she now"; this answers "what size was she last
     * winter", which is what tells you which bin to open. It is also the ONLY way to correct a
     * fat-fingered reading: an unparseable "5TT" sits in current sizes forever and makes every
     * `fits` query answer "we can't say", with no path from the symptom to the cause.
     */
    val sizeHistory: List<PersonSizeDto> = emptyList(),
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
    private val feedback: FeedbackBus,
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
                val history = runCatching { api.personSizes(personId) }.getOrDefault(emptyList())
                _state.value = _state.value.copy(
                    person = person,
                    fits = fits,
                    onLoan = onLoan,
                    sizeHistory = history,
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

    /** Correct a name or a birthdate. Sizes are not editable here — see [deleteSize]. */
    fun editPerson(name: String, birthdate: String?) {
        if (name.isBlank()) return
        write("Couldn't save that change.") {
            api.patchPerson(
                personId,
                PersonPatch(name = name.trim(), birthdate = birthdate?.takeIf { it.isNotBlank() }),
            )
        }
    }

    /**
     * Remove a person.
     *
     * The server keeps every `movements` row they appear on and nulls `person_id` — a loan that
     * happened still happened, and erasing it to tidy a contact list would put a hole in the one
     * record this app promises never to have holes in. What IS lost is the answer to "who has
     * this", so the confirmation says so.
     */
    fun deletePerson(onGone: () -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            runCatching { api.deletePerson(personId) }
                .onSuccess {
                    feedback.say("Removed ${_state.value.person?.name ?: "that person"}")
                    onGone()
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        busy = false,
                        error = ApiErrors.message(it, "Couldn't remove them."),
                    )
                }
        }
    }

    /**
     * Delete a recorded size.
     *
     * Sizes are deletable, never editable: `size_raw` is sacred and the system/ordinal index is
     * derived from it server-side on every write. The sanctioned fix for a fat-fingered "5TT" is
     * therefore delete-and-re-add, which re-derives cleanly — editing in place would mean either
     * a client-set index (which could file a 4T as an adult L) or a silent re-parse that changes
     * what the tag said.
     */
    fun deleteSize(sizeId: String) {
        write("Couldn't remove that size.") { api.deletePersonSize(personId, sizeId) }
    }

    /** One write shape: busy on, call, reload on success, speak on failure. */
    private fun write(failure: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            runCatching { block() }
                .onSuccess {
                    _state.value = _state.value.copy(busy = false)
                    load()
                }
                .onFailure {
                    _state.value = _state.value.copy(busy = false)
                    feedback.say(ApiErrors.message(it, failure))
                }
        }
    }
}
