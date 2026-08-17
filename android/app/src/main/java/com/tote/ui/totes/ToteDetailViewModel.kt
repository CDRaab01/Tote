package com.tote.ui.totes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tote.data.CardDownloader
import com.tote.data.CatalogRepository
import com.tote.data.remote.ApiService
import com.tote.data.remote.CategoryDto
import com.tote.data.remote.ItemCreate
import com.tote.data.remote.LocationDto
import com.tote.data.remote.TotePatch
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
    private val cards: CardDownloader,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val toteId: String = checkNotNull(savedState["toteId"])

    /**
     * True when the tapped tag's UID is not the one recorded for this bin.
     *
     * A fact about THIS opening, not about the bin — which is why it arrives as a nav argument
     * rather than living in state. The server has always computed it and the client always threw
     * it away, so the one scenario the stored UID exists for ("this tag belongs to A14 but is
     * stuck on a different box") opened the wrong contents with total confidence.
     */
    val tagMismatch: Boolean = savedState["mismatch"] ?: false

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

    /**
     * The vocabulary the add dialog picks from.
     *
     * Loaded when the dialog opens rather than with the screen, for the same reason as people: a
     * bin is opened to look at, not to add to, and this tab is used in a garage.
     */
    private val _categories = MutableStateFlow<List<CategoryDto>>(emptyList())
    val categories: StateFlow<List<CategoryDto>> = _categories.asStateFlow()

    fun loadCategories() {
        viewModelScope.launch {
            runCatching { repo.categories() }.onSuccess { _categories.value = it }
        }
    }

    fun addItem(name: String, description: String?, categoryId: String?, quantity: Int) {
        viewModelScope.launch {
            // Every write on this screen used to swallow failure whole — offline in the
            // attic (the documented normal condition) the buttons simply looked broken. The
            // failures speak through the app-wide snackbar because the outcome may land after
            // the screen has moved on.
            runCatching {
                repo.createItem(
                    ItemCreate(
                        name = name.trim(),
                        description = description,
                        categoryId = categoryId,
                        quantity = quantity,
                        toteId = toteId,
                    )
                )
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

    private val _cardIntent = MutableStateFlow<android.content.Intent?>(null)
    val cardIntent: StateFlow<android.content.Intent?> = _cardIntent.asStateFlow()

    /**
     * Fetch the printable card and hand it to the phone's PDF viewer.
     *
     * Was a bare `ACTION_VIEW` of the card URL, which could never have worked: the endpoint
     * needs a bearer token and an external browser has none, so the tap opened a 401 while this
     * screen kept saying "no card printed". Downloaded with the app's own authenticated client
     * instead — see CardDownloader.
     */
    fun printCard(code: String) {
        viewModelScope.launch {
            val intent = cards.open(toteId, code)
            if (intent != null) _cardIntent.value = intent
            else feedback.say("Couldn't fetch the card. Check you're on the tailnet.")
        }
    }

    fun cardIntentConsumed() {
        _cardIntent.value = null
    }

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
                // The spec always said the tag's text record carries the location; the call
                // always passed null. That record is the whole point of writing text at all —
                // it is what a stock NFC reader on a phone without Tote shows someone holding
                // a bin, and "A14 — Christmas decor" without a place is the half of the answer
                // they already had in their hands.
                location = current.locationName,
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

    /** Places, for the edit sheet's picker. Loaded when the sheet opens, like the categories. */
    private val _locations = MutableStateFlow<List<LocationDto>>(emptyList())
    val locations: StateFlow<List<LocationDto>> = _locations.asStateFlow()

    fun loadLocations() {
        viewModelScope.launch {
            runCatching { repo.locations() }.onSuccess { _locations.value = it }
        }
    }

    fun createLocation(name: String, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { repo.createLocation(name) }
                .onSuccess {
                    _locations.value = _locations.value + it
                    onCreated(it.id)
                }
                .onFailure { feedback.say(ApiErrors.message(it, "Couldn't add that place.")) }
        }
    }

    /**
     * Edit the bin itself.
     *
     * The body names every field this form owns — see [TotePatch]. `archived` is carried through
     * unchanged here so that editing a bin can never quietly un-archive it.
     */
    fun editTote(code: String, label: String?, locationId: String?, notes: String?) {
        val current = (_state.value as? UiState.Success)?.data ?: return
        viewModelScope.launch {
            runCatching {
                repo.patchTote(
                    toteId,
                    TotePatch(
                        code = code.trim(),
                        label = label?.takeIf { it.isNotBlank() },
                        locationId = locationId,
                        categoryId = current.categoryId,
                        notes = notes?.takeIf { it.isNotBlank() },
                        archived = current.archived,
                    ),
                )
            }
                .onSuccess {
                    load()
                    feedback.say("Saved.")
                }
                .onFailure {
                    feedback.say(
                        if (ApiErrors.statusOf(it) == 409) "A tote with that code already exists."
                        else ApiErrors.message(it, "Couldn't save the bin.")
                    )
                }
        }
    }

    /**
     * Put the bin away, or bring it back.
     *
     * Archiving is the reversible half of the pair and the one that should be reached for: the
     * bin, its items and its ledger all survive, it simply stops appearing in the daily list.
     */
    fun setArchived(archived: Boolean) {
        val current = (_state.value as? UiState.Success)?.data ?: return
        viewModelScope.launch {
            runCatching {
                repo.patchTote(
                    toteId,
                    TotePatch(
                        code = current.code,
                        label = current.label,
                        locationId = current.locationId,
                        categoryId = current.categoryId,
                        notes = current.notes,
                        archived = archived,
                    ),
                )
            }
                .onSuccess {
                    load()
                    feedback.say(if (archived) "Archived ${current.code}." else "Back on the list.")
                }
                .onFailure { feedback.say(ApiErrors.message(it, "Couldn't change that.")) }
        }
    }

    /**
     * Delete the bin. Its items survive, unfiled.
     *
     * `ON DELETE SET NULL` on the server, deliberately: throwing a box away must not erase the
     * record of what was in it. The confirmation says so, because "delete the bin" reads like
     * "delete its contents" to everyone who has not read the schema.
     */
    fun deleteTote(onGone: () -> Unit) {
        viewModelScope.launch {
            runCatching { repo.deleteTote(toteId) }
                .onSuccess {
                    feedback.say("Bin deleted. Its items are still catalogued, in no bin.")
                    onGone()
                }
                .onFailure { feedback.say(ApiErrors.message(it, "Couldn't delete the bin.")) }
        }
    }

    /**
     * Add a bag to this bin.
     *
     * There is no "move a bag" here and there never will be: a container belongs to the tote it
     * was created in, because a bag that could change bins would be a second source of truth for
     * where a thing is. Its ITEMS move, one ledger row each, like everything else.
     */
    fun addContainer(name: String, notes: String?) {
        viewModelScope.launch {
            runCatching { repo.createContainer(toteId, name, notes?.takeIf { it.isNotBlank() }) }
                .onSuccess { load() }
                .onFailure { feedback.say(ApiErrors.message(it, "Couldn't add that bag.")) }
        }
    }

    fun editContainer(containerId: String, name: String, notes: String?) {
        viewModelScope.launch {
            runCatching {
                repo.patchContainer(toteId, containerId, name, notes?.takeIf { it.isNotBlank() })
            }
                .onSuccess { load() }
                .onFailure { feedback.say(ApiErrors.message(it, "Couldn't save that bag.")) }
        }
    }

    /**
     * Undo the grouping, never the contents.
     *
     * `items.container_id` is `ON DELETE SET NULL` on the server, so everything that was in the
     * bag stays exactly where it is — in this bin, loose. That is why this needs no confirmation
     * dialog: nothing is destroyed but the label.
     */
    fun deleteContainer(containerId: String) {
        viewModelScope.launch {
            runCatching { repo.deleteContainer(toteId, containerId) }
                .onSuccess {
                    load()
                    feedback.say("Bag removed. What was in it is still in this bin.")
                }
                .onFailure { feedback.say(ApiErrors.message(it, "Couldn't remove that bag.")) }
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
