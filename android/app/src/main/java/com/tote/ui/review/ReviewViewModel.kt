package com.tote.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tote.data.local.CachedTote
import com.tote.data.CatalogRepository
import com.tote.data.CaptureQueueRepository
import com.tote.data.local.CaptureQueueEntity
import com.tote.data.local.CatalogDao
import com.tote.data.remote.ApiService
import com.tote.data.remote.ApparelPatch
import com.tote.data.remote.CategoryDto
import com.tote.data.remote.DraftConfirm
import com.tote.data.remote.DraftDto
import com.tote.util.FeedbackBus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The edits in progress on the draft currently on screen.
 *
 * Held apart from the [DraftDto] rather than mutating a copy of it, so the model's original
 * answer stays visible for comparison and a half-finished edit is never mistaken for what the
 * server holds.
 */
data class DraftEdits(
    val name: String = "",
    val description: String = "",
    val notes: String = "",
    val quantity: Int = 1,
    val condition: String? = null,
    val categoryId: String? = null,
    val toteId: String? = null,
    /**
     * The garment tag, verbatim. Null when the item is not clothing OR the label pass never ran.
     *
     * `touchedApparel` tracks whether the reviewer actually changed anything here, because an
     * untouched section must be OMITTED from the confirm body rather than sent as-is. The server
     * treats an omitted apparel block as "leave what the label read"; sending an unchanged copy
     * would work today and would silently start clearing fields the moment this form stops
     * carrying every column the row has.
     */
    val sizeRaw: String = "",
    val department: String? = null,
    val material: String = "",
    val touchedApparel: Boolean = false,
) {
    /**
     * A name is the only thing confirming actually requires.
     *
     * The destination used to be required too, which forced the bin decision at review time —
     * the one moment you are least sure, with the object already back in a closed bin. Without
     * one the item is catalogued and unfiled, which is a real state the catalogue already has.
     */
    val canConfirm: Boolean get() = name.isNotBlank()

    companion object {
        fun from(draft: DraftDto) = DraftEdits(
            name = draft.name,
            description = draft.description.orEmpty(),
            notes = draft.notes.orEmpty(),
            quantity = draft.quantity,
            condition = draft.condition,
            categoryId = draft.categoryId,
            // Pre-selected from the bin chosen at capture time, which is the whole payoff of
            // carrying it through the queue — the common case needs no tap here at all.
            toteId = draft.draftToteId,
            sizeRaw = draft.apparel?.sizeRaw.orEmpty(),
            department = draft.apparel?.department,
            material = draft.apparel?.material.orEmpty(),
        )
    }
}

data class ReviewUiState(
    val drafts: List<DraftDto> = emptyList(),
    /** Captures still on their way. Merged in by the screen — see [ReviewViewModel.queue]. */
    val queue: List<CaptureQueueEntity> = emptyList(),
    val index: Int = 0,
    val edits: DraftEdits = DraftEdits(),
    val totes: List<CachedTote> = emptyList(),
    val categories: List<CategoryDto> = emptyList(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    val error: String? = null,
) {
    val current: DraftDto? get() = drafts.getOrNull(index)
    val position: Int get() = if (drafts.isEmpty()) 0 else index + 1
}

/**
 * The review stack: one draft at a time, out of the order they were shot in.
 *
 * One at a time rather than a scrolling list because this is the tail of a batch — twenty items
 * photographed in one pass — and a list of twenty expandable cards is a screen someone abandons
 * halfway through, which leaves the catalog half-true.
 *
 * **There is no polling here**, unlike the sibling app this pattern came from. Tote's
 * `/items/scan` is synchronous: it identifies before it answers, so a draft that exists is
 * already processed. Polling would be asking a question whose answer cannot change.
 */
@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val api: ApiService,
    private val catalogDao: CatalogDao,
    private val repo: CatalogRepository,
    private val feedback: FeedbackBus,
    captureQueue: CaptureQueueRepository,
) : ViewModel() {

    /**
     * What is still on its way here.
     *
     * Review used to know only about drafts, which meant it could say "Nothing waiting" over a
     * queue of uploads in flight — the same lie the empty-state rule exists to prevent, and one
     * that costs more than confusion: not seeing that a capture had sent, the owner photographed
     * the object a second time and filed a duplicate. A scan takes ~35 s, so the window where
     * this screen knows nothing is wide and completely ordinary.
     *
     * Collected here rather than passed in, because it is the *screen's* truth, not a decision:
     * nothing on review acts on the queue.
     */
    val queue: StateFlow<List<CaptureQueueEntity>> = captureQueue.queue
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _state = MutableStateFlow(ReviewUiState())
    val state: StateFlow<ReviewUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /**
     * Re-read the stack without moving the person off the draft they are looking at.
     *
     * Called every time the screen is resumed, and the reason is a real bug: `refresh()` used to
     * run **only** in `init`, and this ViewModel outlives a tab switch — so a draft that finished
     * uploading while the app was open never appeared. The badge polls, so it said "4" over a
     * screen that said "Nothing waiting", and the drafts only showed up after the app was killed
     * and reopened. A count that disagrees with the list is worse than either being wrong alone:
     * it makes the person doubt the catalog.
     *
     * Position and in-progress edits are preserved by id. A plain [refresh] here would reset to
     * the top of the stack and discard a half-typed correction every time the person glanced at
     * another app — the exact behaviour the one-at-a-time review was designed to avoid.
     */
    fun syncPreservingPosition() {
        val before = _state.value
        if (before.saving) return
        viewModelScope.launch {
            val drafts = runCatching { api.drafts() }.getOrNull() ?: return@launch
            val currentId = before.current?.id
            val index = drafts.indexOfFirst { it.id == currentId }
                .takeIf { it >= 0 }
                ?: before.index.coerceIn(0, (drafts.size - 1).coerceAtLeast(0))
            _state.value = before.copy(
                drafts = drafts,
                index = index,
                loading = false,
                // Keep the edits only if the same draft is still under the cursor; otherwise the
                // person's typing would land on someone else's photograph.
                edits = if (drafts.getOrNull(index)?.id == currentId) {
                    before.edits
                } else {
                    drafts.getOrNull(index)?.let(DraftEdits::from) ?: DraftEdits()
                },
            )
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            // Bins come from the Room cache so the destination picker is populated even when the
            // review happens on the way back from the garage, before the Wi-Fi is good again.
            val totes = runCatching { catalogDao.totes().first() }.getOrDefault(emptyList())
            try {
                val drafts = api.drafts()
                val categories = runCatching { api.categories() }.getOrDefault(emptyList())
                _state.value = ReviewUiState(
                    drafts = drafts,
                    index = 0,
                    edits = drafts.firstOrNull()?.let(DraftEdits::from) ?: DraftEdits(),
                    totes = totes,
                    categories = categories,
                    loading = false,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    totes = totes,
                    error = e.message ?: "Couldn't load the review stack",
                )
            }
        }
    }

    fun edit(transform: (DraftEdits) -> DraftEdits) {
        _state.value = _state.value.copy(edits = transform(_state.value.edits))
    }

    /** Any edit inside the clothing section, which is what makes it get sent at all. */
    fun editApparel(transform: (DraftEdits) -> DraftEdits) {
        _state.value = _state.value.copy(
            edits = transform(_state.value.edits).copy(touchedApparel = true)
        )
    }

    /**
     * Leave this one for later without deciding about it.
     *
     * Wraps: on the last draft, Skip returns to the first. Without the wrap the final draft was
     * a trap — its only enabled exits were File it (which demands a bin) and Discard (which
     * deletes the photographs), so "I don't know where this goes yet" had no answer.
     */
    fun skip() {
        val s = _state.value
        if (s.drafts.size < 2) return
        moveTo((s.index + 1) % s.drafts.size)
    }

    fun back() = moveTo(_state.value.index - 1)

    /**
     * Go straight to one draft, by position.
     *
     * The stack is still reviewed one at a time — a screen of twenty expandable cards is one
     * somebody abandons halfway through, which leaves the catalogue half-true — but the ORDER
     * was never the point. It was oldest-first because that is the order they were shot in, and
     * being unable to leave that order made the stack a queue you had to serve rather than a pile
     * you could work.
     *
     * Reuses `moveTo`, so a jump resets the edits exactly like Skip does: carrying them would
     * apply one item's corrected name to a different photograph.
     */
    fun jumpTo(index: Int) = moveTo(index)

    private fun moveTo(target: Int) {
        val s = _state.value
        if (s.drafts.isEmpty()) return
        val index = target.coerceIn(0, s.drafts.lastIndex)
        _state.value = s.copy(
            index = index,
            // Edits are per-draft and reset on the move. Carrying them would silently apply one
            // item's corrected name to the next photograph in the stack.
            edits = DraftEdits.from(s.drafts[index]),
            error = null,
        )
    }

    /** The human's decision: this becomes a real item in a real bin, with an `initial` ledger row. */
    fun confirm(onFiled: (String) -> Unit = {}) {
        val s = _state.value
        val draft = s.current ?: return
        // No longer a guard: null is a legitimate answer meaning "catalogued, decide the
        // bin later", and the server records it as `catalogued` rather than `initial`.
        val toteId = s.edits.toteId
        if (!s.edits.canConfirm || s.saving) return

        viewModelScope.launch {
            _state.value = s.copy(saving = true, error = null)
            try {
                val item = api.confirmDraft(
                    draft.id,
                    DraftConfirm(
                        toteId = toteId,
                        name = s.edits.name.trim(),
                        description = s.edits.description.takeIf { it.isNotBlank() },
                        notes = s.edits.notes.takeIf { it.isNotBlank() },
                        categoryId = s.edits.categoryId,
                        quantity = s.edits.quantity,
                        condition = s.edits.condition,
                        // Omitted unless the reviewer touched it — see DraftEdits.touchedApparel.
                        apparel = if (s.edits.touchedApparel) {
                            ApparelPatch(
                                sizeRaw = s.edits.sizeRaw.takeIf { it.isNotBlank() },
                                department = s.edits.department,
                                material = s.edits.material.takeIf { it.isNotBlank() },
                            )
                        } else {
                            null
                        },
                    ),
                )
                dropCurrent()
                onFiled(item.name)
                // Close the loop out loud. Before this, File-it's only visible effect was the
                // form being replaced by the next draft — twenty minutes of cataloguing with no
                // evidence any of it landed. Named by bin CODE because that is what is written
                // on the physical box the person is about to walk away from.
                val binCode = toteId?.let { id -> s.totes.firstOrNull { it.id == id }?.code }
                feedback.say(
                    // Three sentences, because they are three different outcomes and the last
                    // one is a promise to come back to: an item nobody ever files is exactly
                    // the loose end deferring the choice risks creating.
                    when {
                        binCode != null -> "Filed ${item.name} into $binCode"
                        toteId != null -> "Filed ${item.name}"
                        else -> "Saved ${item.name} — no bin yet, it's on the Totes tab"
                    }
                )
                // And make the rest of the app agree. This ViewModel bypasses CatalogRepository
                // for reads (drafts are not catalog), but a confirm WRITES to the catalog —
                // without this refresh the tote list and the Find tiles kept their stale counts
                // until something else happened to write.
                runCatching { repo.refresh() }
            } catch (e: Exception) {
                _state.value = _state.value.copy(saving = false, error = filingError(e))
            }
        }
    }

    fun discard() {
        val draft = _state.value.current ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(saving = true, error = null)
            try {
                api.discardDraft(draft.id)
                dropCurrent()
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    saving = false,
                    error = e.message ?: "Couldn't discard that draft",
                )
            }
        }
    }

    /**
     * Remove the decided draft and land on the next one.
     *
     * Removing rather than re-fetching keeps the position: a refresh would drop the person back
     * at the top of a stack they are ten items into, which on a twenty-item batch is the thing
     * that makes people stop reviewing.
     */
    private fun dropCurrent() {
        val s = _state.value
        val remaining = s.drafts.filterIndexed { i, _ -> i != s.index }
        val index = s.index.coerceAtMost((remaining.size - 1).coerceAtLeast(0))
        _state.value = s.copy(
            drafts = remaining,
            index = index,
            edits = remaining.getOrNull(index)?.let(DraftEdits::from) ?: DraftEdits(),
            saving = false,
            error = null,
        )
    }

    private fun filingError(e: Exception): String = when {
        e is retrofit2.HttpException && e.code() == 404 ->
            "That draft is gone — it may have been filed on another device."
        else -> e.message ?: "Couldn't file that item"
    }
}
