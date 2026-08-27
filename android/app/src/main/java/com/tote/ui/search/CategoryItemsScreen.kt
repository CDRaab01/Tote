package com.tote.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tote.data.CatalogRepository
import com.tote.data.remote.ItemDto
import com.tote.ui.components.HazardRule
import com.tote.ui.components.RefreshOnResume
import com.tote.ui.items.ItemSheet
import com.tote.ui.items.ItemSheetViewModel
import com.tote.ui.theme.ToteTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import design.pulse.ui.components.Caption
import design.pulse.ui.components.EmptyState
import design.pulse.ui.components.HeroPanel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Everything under one category, across every bin — "all Christmas", finally.
 *
 * §1 promised three browse entry points; location and person shipped, this one never did. The
 * list is server-fetched (`GET /items?category_id=`, an endpoint live since Phase 2 with no
 * caller until now) rather than cache-filtered, because the cache does not carry category ids —
 * and that is also why this screen is online-only, which the unreachable state says out loud.
 */
data class CategoryItemsState(
    val name: String = "",
    val items: List<ItemDto> = emptyList(),
    val loaded: Boolean = false,
    /** Loaded-and-empty and could-not-load are different screens (house rule). */
    val unreachable: Boolean = false,
)

@HiltViewModel
class CategoryItemsViewModel @Inject constructor(
    private val repo: CatalogRepository,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val categoryId: String = checkNotNull(savedState["categoryId"])

    private val _state = MutableStateFlow(
        CategoryItemsState(name = savedState["name"] ?: "")
    )
    val state: StateFlow<CategoryItemsState> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            // Paged, via the same walker the snapshot uses. Unpaged, this listed at most 200
            // rows under a chip carrying an uncapped server count — a contradiction one tap
            // apart, and the same truncation that hollowed out the offline cache.
            runCatching { repo.allItems(categoryId = categoryId) }
                .onSuccess {
                    _state.value = _state.value.copy(
                        items = it, loaded = true, unreachable = false
                    )
                }
                .onFailure {
                    // Keep whatever was last shown; say so. An empty list here would claim the
                    // category emptied itself the moment the Wi-Fi blinked.
                    _state.value = _state.value.copy(loaded = true, unreachable = true)
                }
        }
    }
}

@Composable
fun CategoryItemsScreen(
    onOpenTote: (String) -> Unit,
    viewModel: CategoryItemsViewModel = hiltViewModel(),
    itemSheet: ItemSheetViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    RefreshOnResume(viewModel::refresh)

    CategoryItemsContent(state = state, onOpenItem = itemSheet::open)

    ItemSheet(
        viewModel = itemSheet,
        onChanged = viewModel::refresh,
        onOpenBin = { toteId ->
            itemSheet.close()
            onOpenTote(toteId)
        },
    )
}

@Composable
fun CategoryItemsContent(
    state: CategoryItemsState,
    onOpenItem: (ItemDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = ToteTheme.spacing

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            Modifier.fillMaxSize().padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            item {
                HeroPanel {
                    Text(
                        state.name.ifBlank { "Category" },
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(spacing.xs))
                    Text(
                        "Everything with this label, wherever it is.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.82f),
                    )
                    Spacer(Modifier.height(spacing.md))
                    HazardRule()
                }
            }

            when {
                !state.loaded -> item { Caption(text = "Reading…") }
                state.unreachable && state.items.isEmpty() -> item {
                    EmptyState(
                        icon = Icons.Filled.CloudOff,
                        title = "Can't reach Tote",
                        subtitle = "Browsing by category needs the server — the offline cache " +
                            "doesn't carry labels. Check you're on the tailnet.",
                    )
                }
                state.items.isEmpty() -> item {
                    EmptyState(
                        icon = Icons.Filled.Category,
                        title = "Nothing here",
                        subtitle = "Nothing carries this label right now. Things gain it at " +
                            "review, or from their item sheet.",
                    )
                }
                else -> {
                    if (state.unreachable) {
                        item { Caption(text = "Can't reach Tote — showing what was last read.") }
                    }
                    items(state.items, key = { it.id }) { item ->
                        // The search hit's row, not ItemRow: browse's whole question is "which
                        // bin", and this is the one row in the app whose second line answers it
                        // ("A14 · Attic" / "Out of its tote").
                        SearchHitRow(item = item, onClick = { onOpenItem(item) })
                    }
                }
            }
        }
    }
}
