package com.tote.ui.totes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tote.data.remote.ItemDto
import com.tote.data.remote.ToteDetailDto
import com.tote.ui.components.HazardRule
import com.tote.ui.components.ToteButton
import com.tote.ui.theme.ToteTheme
import com.tote.util.UiState
import design.pulse.ui.components.Caption
import design.pulse.ui.components.EmptyState
import design.pulse.ui.components.ErrorState
import design.pulse.ui.components.HeroPanel
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.SectionHeader

@Composable
fun ToteDetailScreen(viewModel: ToteDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }

    when (val s = state) {
        is UiState.Success -> {
            ToteDetailContent(
                tote = s.data,
                onAddItem = { showAdd = true },
                onUnpackAll = viewModel::unpackAll,
                onRepackAll = viewModel::repackAll,
                onTakeOut = viewModel::moveOut,
                onPutBack = viewModel::putBack,
            )
            if (showAdd) {
                AddItemDialog(
                    onDismiss = { showAdd = false },
                    onAdd = { name, qty ->
                        viewModel.addItem(name, qty)
                        showAdd = false
                    },
                )
            }
        }
        is UiState.Error -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            ErrorState(
                icon = Icons.Filled.Inventory2,
                title = "Couldn't load this tote",
                detail = s.message,
                onRetry = viewModel::load,
            )
        }
        else -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { CircularProgressIndicator() }
        }
    }
}

@Composable
fun ToteDetailContent(
    tote: ToteDetailDto,
    onAddItem: () -> Unit,
    onUnpackAll: () -> Unit,
    onRepackAll: () -> Unit,
    onTakeOut: (String) -> Unit,
    onPutBack: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ToteTheme.colors
    val spacing = ToteTheme.spacing

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            Modifier.fillMaxSize().padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            item {
                HeroPanel {
                    Text(tote.code, style = MaterialTheme.typography.headlineMedium, color = Color.White)
                    Spacer(Modifier.height(spacing.xs))
                    Text(
                        tote.label ?: "Unlabelled",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.82f),
                    )
                    Spacer(Modifier.height(spacing.md))
                    HazardRule()
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    ToteButton(text = "Add item", onClick = onAddItem, modifier = Modifier.weight(1f))
                    // Whichever operation makes sense right now. Showing both at once invites
                    // the wrong tap on a bin that is already open on the floor.
                    if (tote.itemCount > 0) {
                        ToteButton(
                            text = "Unpack all",
                            onClick = onUnpackAll,
                            tonal = true,
                            modifier = Modifier.weight(1f),
                        )
                    } else if (tote.outCount > 0) {
                        ToteButton(
                            text = "Repack all",
                            onClick = onRepackAll,
                            tonal = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            item { SectionHeader(label = "In this tote", channel = colors.stored.base) }

            if (tote.items.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Filled.Inventory2,
                        title = if (tote.outCount > 0) "Everything is out" else "Empty",
                        subtitle = if (tote.outCount > 0) {
                            "All ${tote.outCount} of its items are out right now."
                        } else {
                            "Add the first item to start cataloguing this bin."
                        },
                    )
                }
            }

            items(tote.items, key = { it.id }) { item ->
                ItemRow(item, actionLabel = "Take out", onAction = { onTakeOut(item.id) })
            }

            // The gap, shown rather than hidden. This section is the answer to "I thought the
            // lights were in here" — the single most common reason to stop trusting a catalog.
            if (tote.itemsOut.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(spacing.sm))
                    SectionHeader(label = "Out of this tote", channel = colors.attention.base)
                }
                items(tote.itemsOut, key = { "out-${it.id}" }) { item ->
                    ItemRow(item, actionLabel = "Put back", onAction = { onPutBack(item.id) })
                }
            }
        }
    }
}

@Composable
private fun ItemRow(item: ItemDto, actionLabel: String, onAction: () -> Unit) {
    val colors = ToteTheme.colors
    val spacing = ToteTheme.spacing

    PanelCard(channel = if (item.isOverdue) colors.attention.base else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (item.quantity > 1) "${item.name} ×${item.quantity}" else item.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val sub = when {
                    item.isOverdue -> "Overdue — expected back ${item.expectedBack}"
                    item.status == "loaned" -> "Lent out"
                    item.status == "out" -> "Out since it was unpacked"
                    else -> null
                }
                if (sub != null) {
                    Spacer(Modifier.height(spacing.xs))
                    Caption(text = sub)
                }
            }
            ToteButton(text = actionLabel, onClick = onAction, tonal = true)
        }
    }
}

@Composable
private fun AddItemDialog(onDismiss: () -> Unit, onAdd: (String, Int) -> Unit) {
    var name by remember { mutableStateOf("") }
    var qty by remember { mutableStateOf("1") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add item") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("What is it?") },
                    placeholder = { Text("Pre-lit tree, 7ft") },
                    singleLine = true,
                )
                Spacer(Modifier.height(ToteTheme.spacing.md))
                OutlinedTextField(
                    value = qty,
                    onValueChange = { qty = it.filter(Char::isDigit).take(3) },
                    label = { Text("Quantity") },
                    singleLine = true,
                )
                Spacer(Modifier.height(ToteTheme.spacing.sm))
                // Quantity exists so "4× ornament box" is one row. Said here because the
                // alternative — four identical rows — is what people do without prompting.
                Caption(text = "Four identical boxes? One row, quantity 4.")
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(name, qty.toIntOrNull() ?: 1) },
                enabled = name.isNotBlank(),
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Preview(name = "Tote detail — dark")
@Composable
private fun ToteDetailPreview() {
    ToteTheme(darkTheme = true) {
        ToteDetailContent(
            tote = ToteDetailDto(
                id = "1",
                code = "A14",
                label = "Christmas decor",
                itemCount = 2,
                outCount = 1,
                items = listOf(
                    ItemDto(id = "a", name = "Pre-lit tree, 7ft", quantity = 1, status = "stored"),
                    ItemDto(id = "b", name = "Ornament box", quantity = 4, status = "stored"),
                ),
                itemsOut = listOf(
                    ItemDto(
                        id = "c", name = "Outdoor lights", quantity = 6, status = "out",
                    ),
                ),
            ),
            onAddItem = {}, onUnpackAll = {}, onRepackAll = {}, onTakeOut = {}, onPutBack = {},
        )
    }
}
