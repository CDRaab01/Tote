package com.tote.ui.items

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tote.data.CatalogRepository
import com.tote.data.local.CachedTote
import com.tote.data.local.CatalogDao
import com.tote.data.remote.ApiService
import com.tote.data.remote.ApparelPatch
import com.tote.data.remote.CategoryDto
import com.tote.data.remote.ContainerDto
import com.tote.data.remote.ItemDto
import com.tote.data.remote.ItemUpdate
import com.tote.data.remote.MoveRequest
import com.tote.data.remote.MovementDto
import com.tote.util.ApiErrors
import com.tote.util.FeedbackBus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Which face of the sheet is showing.
 *
 * The two pickers are modes of the sheet rather than dialogs over it. A dialog inside a modal
 * bottom sheet is two stacked scrims and two Back handlers, and the Back that dismisses the picker
 * looks identical to the one that throws away the edit underneath it. Swapping the sheet's own
 * body keeps one surface, one Back, and one thing to screenshot.
 */
enum class SheetMode { View, Edit, History, PickBin, PickCategory, PickBag }

/**
 * The edit form's working copy.
 *
 * Held apart from the [ItemDto] rather than mutating a copy of it, so a half-finished edit is
 * never mistaken for what the server holds — the same discipline the review stack uses.
 *
 * `touchedApparel` decides whether the clothing block is sent at all. Untouched, it is omitted and
 * the server leaves the label's reading alone; see [ItemUpdate].
 */
data class ItemEdits(
    val name: String = "",
    val description: String = "",
    val notes: String = "",
    val quantity: Int = 1,
    val condition: String? = null,
    val categoryId: String? = null,
    /** Which bag inside the item's current bin. Null is "loose in the bin". */
    val containerId: String? = null,
    val sizeRaw: String = "",
    val department: String? = null,
    val touchedApparel: Boolean = false,
) {
    val canSave: Boolean get() = name.isNotBlank()

    companion object {
        fun from(item: ItemDto) = ItemEdits(
            name = item.name,
            description = item.description.orEmpty(),
            notes = item.notes.orEmpty(),
            quantity = item.quantity,
            condition = item.condition,
            categoryId = item.categoryId,
            containerId = item.containerId,
            sizeRaw = item.apparel?.sizeRaw.orEmpty(),
            department = item.apparel?.department,
        )
    }
}

data class ItemSheetState(
    /** Null when the sheet is closed. Everything else is only meaningful alongside it. */
    val item: ItemDto? = null,
    val mode: SheetMode = SheetMode.View,
    val edits: ItemEdits = ItemEdits(),
    val confirmingDelete: Boolean = false,
    /** The filter typed into whichever picker mode is showing. */
    val pickerQuery: String = "",
    val bins: List<CachedTote> = emptyList(),
    val categories: List<CategoryDto> = emptyList(),
    /** The bags in the bin this item is in. Empty when the bin is not subdivided. */
    val containers: List<ContainerDto> = emptyList(),
    val movements: List<MovementDto> = emptyList(),
    val historyLoaded: Boolean = false,
    /**
     * Member id -> display name, and **empty in a household of one**.
     *
     * Emptiness is the signal, not a separate flag: the history renders an actor only when it can
     * name one, so a solo catalogue never grows a column of your own name repeated down the page.
     */
    val memberNames: Map<String, String> = emptyMap(),
    val busy: Boolean = false,
) {
    /** Who did it, when that is worth saying — see [memberNames]. */
    fun actorFor(userId: String?): String? = userId?.let { memberNames[it] }

    /** What a movement's tote id is called today — see [MovementDto]. */
    fun codeFor(toteId: String?): String? =
        toteId?.let { id -> bins.firstOrNull { it.id == id }?.code }
}

/**
 * One item, up close — and every operation that acts on a single item.
 *
 * It exists once and is opened from three screens (a bin's contents, a search hit, a person's fits
 * and loans) because they were previously three dead ends: a search hit could only open the bin it
 * was in — and did nothing at all for anything lent out or unpacked, since the tap was guarded on
 * `currentToteId` — and a person's list could not act on a row at all.
 *
 * It takes the [ItemDto] the caller already holds rather than re-fetching by id. Every one of those
 * screens has the full row in hand, so a fetch would be a spinner over data already on screen; the
 * write paths return the updated item, so the sheet stays true without one.
 *
 * **Editing and moving are different verbs and stay different calls.** `PATCH` covers what the
 * thing *is*; where it is goes through `POST /items/{id}/move` so every relocation leaves a ledger
 * row. That split is the reason [changes] exists — the host screen re-reads after any of them,
 * because counts, sections and the row itself all move.
 */
@HiltViewModel
class ItemSheetViewModel @Inject constructor(
    private val repo: CatalogRepository,
    private val api: ApiService,
    private val catalogDao: CatalogDao,
    private val feedback: FeedbackBus,
) : ViewModel() {

    private val _state = MutableStateFlow(ItemSheetState())
    val state: StateFlow<ItemSheetState> = _state.asStateFlow()

    /**
     * Emitted after any write that the screen behind the sheet would render differently.
     *
     * A SharedFlow rather than a callback because the outcome can land after the sheet is gone,
     * and a screen that missed it would sit on a stale list — the exact staleness the feedback
     * round was about.
     */
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    fun open(item: ItemDto) {
        _state.value = ItemSheetState(item = item, edits = ItemEdits.from(item))
        viewModelScope.launch {
            // Bins from the Room cache so the move picker is populated in a garage with no
            // signal — which is where things actually get moved between bins.
            val bins = runCatching { catalogDao.totes().first() }.getOrDefault(emptyList())
            val categories = runCatching { api.categories() }.getOrDefault(emptyList())
            // Only the bags of the bin this item is actually in: a bag belonging to any other
            // tote is not a choice, it is the contradiction the server refuses.
            val containers = item.currentToteId?.let { toteId ->
                runCatching { api.tote(toteId).containers }.getOrDefault(emptyList())
            }.orEmpty()
            _state.value =
                _state.value.copy(bins = bins, categories = categories, containers = containers)
        }
    }

    fun close() {
        _state.value = ItemSheetState()
    }

    fun mode(mode: SheetMode) {
        _state.value = _state.value.copy(mode = mode, confirmingDelete = false, pickerQuery = "")
        if (mode == SheetMode.History) loadHistory()
    }

    fun pickerQuery(query: String) {
        _state.value = _state.value.copy(pickerQuery = query)
    }

    /** Chosen from the bag picker; returns to the edit form that asked for it. */
    fun pickBag(containerId: String?) {
        _state.value = _state.value.copy(
            edits = _state.value.edits.copy(containerId = containerId),
            mode = SheetMode.Edit,
            pickerQuery = "",
        )
    }

    /** Chosen from the category picker; returns to whichever form asked for it. */
    fun pickCategory(categoryId: String?) {
        _state.value = _state.value.copy(
            edits = _state.value.edits.copy(categoryId = categoryId),
            mode = SheetMode.Edit,
            pickerQuery = "",
        )
    }

    fun edit(transform: (ItemEdits) -> ItemEdits) {
        _state.value = _state.value.copy(edits = transform(_state.value.edits))
    }

    /** Any change inside the clothing block marks it touched, so it gets sent. */
    fun editApparel(transform: (ItemEdits) -> ItemEdits) {
        _state.value = _state.value.copy(edits = transform(_state.value.edits).copy(touchedApparel = true))
    }

    fun confirmDelete(confirming: Boolean) {
        _state.value = _state.value.copy(confirmingDelete = confirming)
    }

    /**
     * Where this thing has been.
     *
     * Loaded on demand, not with the sheet: most opens are to lend, move or read, and a second
     * round trip on every tap of an item row would be paid by everyone for the few who ask.
     */
    fun loadHistory() {
        val item = _state.value.item ?: return
        if (_state.value.historyLoaded) return
        viewModelScope.launch {
            runCatching { repo.movements(item.id) }
                .onSuccess {
                    _state.value = _state.value.copy(movements = it, historyLoaded = true)
                }
                .onFailure { feedback.say(ApiErrors.message(it, "Couldn't load its history.")) }
            // Fetched alongside rather than at construction: a name is only needed on the one
            // face that shows it, and paying a round trip on every tap of an item row would
            // charge everybody for the few who open the history. Silent on failure — a missing
            // name costs a caption, not the history.
            runCatching { repo.household() }
                .getOrNull()
                ?.takeIf { it.shared }
                ?.let { household ->
                    _state.value = _state.value.copy(
                        memberNames = household.members.associate { it.userId to it.name }
                    )
                }
        }
    }

    /**
     * Save the item's own attributes.
     *
     * The body names every field the form owns rather than only what changed — see [ItemUpdate]
     * for why a sparse one would quietly blank the rest.
     */
    fun save() {
        val item = _state.value.item ?: return
        val edits = _state.value.edits
        if (!edits.canSave) return
        write("Couldn't save that.") {
            val updated = repo.patchItem(
                item.id,
                ItemUpdate(
                    name = edits.name.trim(),
                    description = edits.description.trim().takeIf { it.isNotEmpty() },
                    notes = edits.notes.trim().takeIf { it.isNotEmpty() },
                    categoryId = edits.categoryId,
                    // Sent on every save like the rest of the body, so clearing it is simply
                    // choosing "loose in the bin".
                    containerId = edits.containerId,
                    quantity = edits.quantity,
                    condition = edits.condition,
                    apparel = if (edits.touchedApparel) {
                        ApparelPatch(
                            sizeRaw = edits.sizeRaw.trim().takeIf { it.isNotEmpty() },
                            department = edits.department,
                        )
                    } else {
                        null
                    },
                ),
            )
            _state.value = _state.value.copy(
                item = updated,
                edits = ItemEdits.from(updated),
                mode = SheetMode.View,
            )
            feedback.say("Saved.")
        }
    }

    /**
     * Put this in a different bin.
     *
     * `moved` for something already stored, `repacked` for something that is out, and
     * **`returned` for something a person had** — the ledger distinguishes "it changed bins" from
     * "it came back" from "Dave gave it back", and collapsing them would make "unpack the
     * Christmas bin, then put half of it somewhere else" unreadable a year later. The loan case is
     * the one that costs most: it is the only record that a lend ever ended.
     */
    fun moveTo(toteId: String) {
        val item = _state.value.item ?: return
        val reason = when (item.status) {
            "stored" -> "moved"
            "loaned" -> "returned"
            else -> "repacked"
        }
        val code = _state.value.codeFor(toteId) ?: "the bin"
        write("Couldn't move that.") {
            repo.move(item.id, MoveRequest(reason = reason, toToteId = toteId))
            // The row on screen is now wrong in two ways the server would have to be asked
            // about, so the sheet closes onto a screen that is about to re-read.
            _state.value = ItemSheetState()
            feedback.say("Moved ${item.name} into $code.")
        }
    }

    /**
     * Delete outright — the row, its ledger, and its photographs.
     *
     * Not the same operation as disposing of something: "we no longer own this" is a `disposed`
     * movement and keeps the history. This is for a row that should never have existed.
     */
    fun delete() {
        val item = _state.value.item ?: return
        write("Couldn't delete that item.") {
            repo.deleteItem(item.id)
            _state.value = ItemSheetState()
            feedback.say("Deleted ${item.name}.")
        }
    }

    /** One write shape: busy on, call, tell the screen behind, speak on failure. */
    private fun write(failure: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            runCatching { block() }
                .onSuccess {
                    _state.value = _state.value.copy(busy = false)
                    _changes.tryEmit(Unit)
                }
                .onFailure {
                    _state.value = _state.value.copy(busy = false)
                    feedback.say(ApiErrors.message(it, failure))
                }
        }
    }
}
