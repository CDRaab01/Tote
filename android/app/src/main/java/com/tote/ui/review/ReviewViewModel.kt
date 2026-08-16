package com.tote.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tote.data.local.CachedTote
import com.tote.data.local.CatalogDao
import com.tote.data.remote.ApiService
import com.tote.data.remote.CategoryDto
import com.tote.data.remote.DraftConfirm
import com.tote.data.remote.DraftDto
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) {
    /** Filing needs a destination and a name; everything else may legitimately be blank. */
    val canConfirm: Boolean get() = name.isNotBlank() && toteId != null

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
        )
    }
}

data class ReviewUiState(
    val drafts: List<DraftDto> = emptyList(),
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
) : ViewModel() {

    private val _state = MutableStateFlow(ReviewUiState())
    val state: StateFlow<ReviewUiState> = _state.asStateFlow()

    init {
        refresh()
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

    /** Leave this one for later without deciding about it. */
    fun skip() = moveTo(_state.value.index + 1)

    fun back() = moveTo(_state.value.index - 1)

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
        val toteId = s.edits.toteId ?: return
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
                    ),
                )
                dropCurrent()
                onFiled(item.name)
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
