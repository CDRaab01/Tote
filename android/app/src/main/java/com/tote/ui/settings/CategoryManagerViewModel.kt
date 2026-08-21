package com.tote.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tote.data.remote.ApiService
import com.tote.data.remote.CategoryCreate
import com.tote.data.remote.CategoryDto
import com.tote.data.remote.CategoryPatch
import com.tote.util.ApiErrors
import com.tote.util.FeedbackBus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the editor dialog holds. `id == null` means a new category. */
data class CategoryEdit(
    val id: String? = null,
    val name: String = "",
    val icon: String? = null,
)

data class CategoryManagerState(
    val categories: List<CategoryDto> = emptyList(),
    val loaded: Boolean = false,
    val unreachable: Boolean = false,
    val editing: CategoryEdit? = null,
    /** The category a delete confirmation is open for. */
    val deleting: CategoryDto? = null,
    val busy: Boolean = false,
)

/**
 * First caller of POST/PATCH/DELETE /categories — endpoints live since Phase 2.
 *
 * The one rule worth a sentence: **PATCH always sends both fields** (`CategoryPatch(name, icon)`),
 * the TotePatch discipline — `encodeDefaults` plus the server's `exclude_unset` means a sparse
 * body clears what it omits, and a rename that silently stripped every icon would be found one
 * screen at a time. `busy` guards the double-tap (#25's two-DELETEs-and-a-404 lesson).
 */
@HiltViewModel
class CategoryManagerViewModel @Inject constructor(
    private val api: ApiService,
    private val feedback: FeedbackBus,
) : ViewModel() {

    private val _state = MutableStateFlow(CategoryManagerState())
    val state: StateFlow<CategoryManagerState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { api.categories() }
                .onSuccess {
                    _state.value = _state.value.copy(
                        categories = it, loaded = true, unreachable = false
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(loaded = true, unreachable = true)
                }
        }
    }

    fun startAdd() {
        _state.value = _state.value.copy(editing = CategoryEdit())
    }

    fun startEdit(category: CategoryDto) {
        _state.value = _state.value.copy(
            editing = CategoryEdit(id = category.id, name = category.name, icon = category.icon)
        )
    }

    fun setName(value: String) {
        _state.value = _state.value.copy(editing = _state.value.editing?.copy(name = value))
    }

    fun setIcon(value: String?) {
        _state.value = _state.value.copy(editing = _state.value.editing?.copy(icon = value))
    }

    fun dismissEditor() {
        _state.value = _state.value.copy(editing = null)
    }

    fun save() {
        val editing = _state.value.editing ?: return
        val name = editing.name.trim()
        if (name.isEmpty() || _state.value.busy) return
        // Set BEFORE the launch, not inside it: two taps land synchronously, and a guard that
        // only trips once the coroutine runs lets both through — the exact #25 bug.
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            runCatching {
                if (editing.id == null) {
                    api.createCategory(CategoryCreate(name = name, icon = editing.icon))
                } else {
                    api.patchCategory(editing.id, CategoryPatch(name = name, icon = editing.icon))
                }
            }.onSuccess {
                _state.value = _state.value.copy(editing = null)
                refresh()
            }.onFailure {
                feedback.say(ApiErrors.detail(it) ?: ApiErrors.message(it, "Couldn't save that."))
            }
            _state.value = _state.value.copy(busy = false)
        }
    }

    fun askDelete() {
        val editing = _state.value.editing ?: return
        val category = _state.value.categories.firstOrNull { it.id == editing.id } ?: return
        _state.value = _state.value.copy(deleting = category)
    }

    fun dismissDelete() {
        _state.value = _state.value.copy(deleting = null)
    }

    fun delete() {
        val deleting = _state.value.deleting ?: return
        if (_state.value.busy) return
        // Synchronous, same reason as save().
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            runCatching { api.deleteCategory(deleting.id) }
                .onSuccess {
                    _state.value = _state.value.copy(deleting = null, editing = null)
                    feedback.say("Deleted. Its items keep their bins — they just lost the label.")
                    refresh()
                }
                .onFailure {
                    feedback.say(ApiErrors.message(it, "Couldn't delete that."))
                }
            _state.value = _state.value.copy(busy = false)
        }
    }
}
