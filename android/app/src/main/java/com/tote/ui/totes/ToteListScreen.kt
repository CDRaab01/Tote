package com.tote.ui.totes

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tote.data.local.CachedTote
import com.tote.ui.components.RefreshOnResume
import com.tote.ui.components.ToteButton
import com.tote.ui.theme.ToteTheme
import com.tote.util.UiState
import design.pulse.ui.components.Caption
import design.pulse.ui.components.EmptyState
import design.pulse.ui.components.ErrorState
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.SectionHeader

@Composable
fun ToteListScreen(
    onOpenTote: (String) -> Unit,
    viewModel: ToteListViewModel = hiltViewModel(),
) {
    val totes by viewModel.totes.collectAsStateWithLifecycle()
    val createState by viewModel.create.collectAsStateWithLifecycle()
    val unreachable by viewModel.unreachable.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    var showCreate by remember { mutableStateOf(false) }

    RefreshOnResume(viewModel::refresh)

    ToteListContent(
        totes = totes,
        onOpenTote = onOpenTote,
        onNewTote = { showCreate = true },
        unreachable = unreachable,
        loading = loading,
        refreshing = refreshing,
        onRefresh = viewModel::refresh,
        onRetry = viewModel::refresh,
    )

    if (showCreate) {
        NewToteDialog(
            state = createState,
            onDismiss = {
                showCreate = false
                viewModel.clearCreateState()
            },
            onCreate = viewModel::createTote,
        )
    }
    if (createState is UiState.Success) {
        showCreate = false
        viewModel.clearCreateState()
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ToteListContent(
    totes: List<CachedTote>,
    onOpenTote: (String) -> Unit,
    onNewTote: () -> Unit,
    modifier: Modifier = Modifier,
    unreachable: Boolean = false,
    loading: Boolean = false,
    refreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    val spacing = ToteTheme.spacing
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh) {
        LazyColumn(
            Modifier.fillMaxSize().padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionHeader(label = "Totes", channel = ToteTheme.colors.slate.base)
                    ToteButton(text = "New tote", onClick = onNewTote, tonal = true)
                }
            }

            if (totes.isEmpty()) {
                item {
                    // Three states, not two. "No totes yet" over a household with fourteen bins
                    // is the lie that invites someone to create A14 twice — and it used to be
                    // shown during the FIRST load as well as on failure, because only failure
                    // was guarded.
                    if (loading) {
                        Box(Modifier.fillMaxWidth().padding(spacing.xl), Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else if (unreachable) {
                        ErrorState(
                            icon = Icons.Outlined.CloudOff,
                            title = "Can't reach Tote",
                            detail = "Nothing is cached on this phone yet, so there is nothing " +
                                "to show offline. Check you're on the tailnet.",
                            onRetry = onRetry,
                        )
                    } else {
                        EmptyState(
                            icon = Icons.Filled.Inventory2,
                            title = "No totes yet",
                            subtitle = "Create one, write its code on an index card, and start filling it.",
                        )
                    }
                }
            }

            items(totes, key = { it.id }) { tote -> ToteRow(tote, onClick = { onOpenTote(tote.id) }) }
        }
        }
    }
}

@Composable
private fun ToteRow(tote: CachedTote, onClick: () -> Unit) {
    val colors = ToteTheme.colors
    val spacing = ToteTheme.spacing

    PanelCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The code is the thing written on the physical card, so it leads — that is what
            // someone is matching against a bin in front of them.
            Text(
                tote.code,
                style = ToteTheme.dataType.dataLarge,
                color = colors.slate.base,
            )
            Spacer(Modifier.width(spacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    tote.label ?: "Unlabelled",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(spacing.xs))
                val counts = buildString {
                    append("${tote.itemCount} item${if (tote.itemCount == 1) "" else "s"}")
                    // Only shown when non-zero: a permanent "0 out" would train people to ignore
                    // the field that matters when it is not zero.
                    if (tote.outCount > 0) append(" · ${tote.outCount} out")
                    tote.locationName?.let { append(" · $it") }
                }
                Caption(text = counts)
            }
        }
    }
}

@Composable
private fun NewToteDialog(
    state: UiState<Unit>,
    onDismiss: () -> Unit,
    onCreate: (String, String?) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New tote") },
        text = {
            Column {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Code") },
                    placeholder = { Text("A14") },
                    singleLine = true,
                )
                Spacer(Modifier.height(ToteTheme.spacing.sm))
                // Said up front, because the code is about to be written on a card in permanent
                // marker and codes are compared case-insensitively.
                Caption(text = "Write this on the index card. Case doesn't matter.")
                Spacer(Modifier.height(ToteTheme.spacing.md))
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label (optional)") },
                    placeholder = { Text("Christmas decor") },
                    singleLine = true,
                )
                if (state is UiState.Error) {
                    Spacer(Modifier.height(ToteTheme.spacing.sm))
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(code, label) },
                enabled = code.isNotBlank() && state !is UiState.Loading,
            ) { Text(if (state is UiState.Loading) "Creating…" else "Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Preview(name = "Totes — dark")
@Composable
private fun ToteListPreview() {
    ToteTheme(darkTheme = true) {
        ToteListContent(
            totes = listOf(
                CachedTote("1", "A14", "Christmas decor", null, "Attic", 37, 0, false),
                CachedTote("2", "A15", "Winter clothes 4T", null, "Attic", 12, 3, false),
                CachedTote("3", "G01", "Power tools", null, "Garage rack B", 8, 1, false),
            ),
            onOpenTote = {},
            onNewTote = {},
        )
    }
}
