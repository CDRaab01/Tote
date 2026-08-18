package com.tote.ui.totes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tote.data.local.CachedItem
import com.tote.data.remote.ApparelDto
import com.tote.data.remote.ItemDto
import com.tote.ui.components.ItemRow
import com.tote.ui.components.PickerDialog
import com.tote.ui.components.PickerOption
import com.tote.ui.components.RefreshOnResume
import com.tote.ui.components.ToteButton
import com.tote.ui.items.ItemSheet
import com.tote.ui.items.ItemSheetViewModel
import com.tote.ui.theme.ToteTheme
import design.pulse.ui.components.Caption
import design.pulse.ui.components.EmptyState
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.SectionHeader

/**
 * Everything catalogued that is in no bin — and the one screen built for putting it away.
 *
 * It used to be a section that unfolded inside the Totes tab. With five loose ends that was
 * fine; with thirty-two it pushed every bin off the screen, so the tab could no longer do the
 * thing it is named after while doing this one badly. Browsing bins and clearing loose ends are
 * different jobs. The tab keeps the count — a signal is exactly what belongs there — and the
 * work happens here.
 *
 * Owner-reported, and the three complaints map one to one onto what this screen changes:
 * *"I don't know what the items are"* (the rows had no photograph and no description),
 * *"it's a shit of scrolling"* (thirty-two rows on top of the bins), and *"I can't multi
 * select"* (filing was one trip through the item sheet per item).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnfiledScreen(
    onOpenTote: (String) -> Unit,
    viewModel: ToteListViewModel = hiltViewModel(),
    itemSheet: ItemSheetViewModel = hiltViewModel(),
) {
    val unfiled by viewModel.unfiled.collectAsStateWithLifecycle()
    val totes by viewModel.totes.collectAsStateWithLifecycle()
    val selection by viewModel.unfiledSelection.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    var choosingBin by remember { mutableStateOf(false) }

    RefreshOnResume(viewModel::refresh)

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        UnfiledContent(
            unfiled = unfiled,
            selection = selection,
            onOpen = itemSheet::open,
            onBegin = viewModel::beginFiling,
            onToggle = viewModel::toggleUnfiled,
            onSelectAll = { viewModel.selectAllUnfiled(unfiled.map { it.id }) },
            onCancel = viewModel::cancelFiling,
            onFile = { choosingBin = true },
        )
    }

    if (choosingBin) {
        PickerDialog(
            title = "File into",
            options = totes.map { PickerOption(it.id, it.code, it.label) },
            selectedId = null,
            subtitle = "One ledger row each, so where they came from stays answerable.",
            onPick = { id ->
                choosingBin = false
                // Null cannot arrive: no `noneLabel` is offered, because "file into nowhere" is
                // the state these items are already in.
                id?.let(viewModel::fileSelected)
            },
            onDismiss = { choosingBin = false },
            emptyMessage = "No bins yet — make one on the Totes tab first.",
        )
    }

    // The sheet already knows how to put an `out` item away: its move button reads "Put it away"
    // for exactly this case. It is also where the photographs are, which is the right place to
    // settle "which of these six onesies is this one".
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
fun UnfiledContent(
    unfiled: List<CachedItem>,
    selection: Set<String>? = null,
    onOpen: (ItemDto) -> Unit = {},
    onBegin: (String?) -> Unit = {},
    onToggle: (String) -> Unit = {},
    onSelectAll: () -> Unit = {},
    onCancel: () -> Unit = {},
    onFile: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = ToteTheme.colors
    val spacing = ToteTheme.spacing

    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = spacing.lg),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(spacing.sm),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = spacing.lg),
    ) {
        item {
            SectionHeader(label = "Not in a bin", channel = colors.attention.base)
        }

        if (unfiled.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Filled.Inventory2,
                    title = "Nothing loose",
                    subtitle = "Everything catalogued is in a bin.",
                )
            }
            return@LazyColumn
        }

        // Filing is what this screen is for, and it was the one thing the old section could not
        // do in bulk: thirty-two garments meant thirty-two trips through the item sheet. Enough
        // friction to stop anybody deferring a destination again — and deferring is a feature
        // this app deliberately added.
        item(key = "bar") {
            UnfiledBar(
                total = unfiled.size,
                selection = selection,
                onBegin = { onBegin(null) },
                onSelectAll = onSelectAll,
                onCancel = onCancel,
                onFile = onFile,
            )
        }

        items(unfiled, key = { it.id }) { cached ->
            val item = cached.toItemDto()
            ItemRow(
                item = item,
                actionLabel = "File…",
                onAction = { onOpen(item) },
                onOpen = { onOpen(item) },
                selected = selection?.contains(cached.id),
                onToggle = { onToggle(cached.id) },
                onLongPress = { onBegin(cached.id) },
                // "Catalogued, not filed" under a heading reading "Not in a bin" is the heading
                // again, on every row. A loan still speaks: that one names a person.
                suppressRoutineStatus = true,
            )
        }
    }
}

/**
 * The bar over the list.
 *
 * One verb, because there is exactly one thing to do with a loose end. Pinned above the rows
 * rather than floating in, matching the bin screen — a bar that appears and disappears as you
 * tick things is a layout that jumps under your thumb.
 */
@Composable
private fun UnfiledBar(
    total: Int,
    selection: Set<String>?,
    onBegin: () -> Unit,
    onSelectAll: () -> Unit,
    onCancel: () -> Unit,
    onFile: () -> Unit,
) {
    val colors = ToteTheme.colors
    val spacing = ToteTheme.spacing

    if (selection == null) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Boxed with the weight, because `Caption` takes no modifier: unweighted it claims
            // the whole row and squeezes the button until "Select" wraps to "Sele / ct".
            Box(Modifier.weight(1f)) {
                Caption(
                    text = "$total item${if (total == 1) "" else "s"} waiting for somewhere to go",
                )
            }
            Spacer(Modifier.width(spacing.sm))
            if (total > 1) {
                ToteButton(text = "Select", onClick = onBegin, tonal = true, compact = true)
            }
        }
        return
    }
    PanelCard(channel = colors.slate.base) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (selection.isEmpty()) "Nothing ticked" else "${selection.size} of $total",
                style = MaterialTheme.typography.titleSmall,
                color = colors.slate.base,
                modifier = Modifier.weight(1f),
            )
            ToteButton(text = "All", onClick = onSelectAll, tonal = true, compact = true)
            Spacer(Modifier.width(spacing.sm))
            ToteButton(text = "Done", onClick = onCancel, tonal = true, compact = true)
        }
        Spacer(Modifier.height(spacing.sm))
        ToteButton(
            text = "File into…",
            onClick = onFile,
            tonal = true,
            enabled = selection.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The cached row the sheet speaks.
 *
 * Carries the photo count and the tag's words now, so a cache-backed row looks like an
 * API-backed one — the whole reason this list was unreadable.
 */
private fun CachedItem.toItemDto() = ItemDto(
    id = id,
    name = name,
    description = description,
    notes = notes,
    quantity = quantity,
    status = status,
    currentToteId = currentToteId,
    toteCode = toteCode,
    locationName = locationName,
    isOverdue = isOverdue,
    photoCount = photoCount,
    apparel = sizeRaw?.let { ApparelDto(sizeRaw = it) },
)
