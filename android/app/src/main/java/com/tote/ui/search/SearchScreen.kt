package com.tote.ui.search

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tote.data.remote.ItemDto
import com.tote.ui.components.HazardRule
import com.tote.ui.components.RefreshOnResume
import com.tote.ui.components.ItemThumbnail
import com.tote.ui.theme.ToteTheme
import design.pulse.ui.components.Caption
import design.pulse.ui.components.EmptyState
import design.pulse.ui.components.HeroPanel
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.SectionHeader
import design.pulse.ui.components.StatTile

/** How many overdue rows the card names before it summarises — enough to act on, short
 *  enough that the card never pushes the search box off the screen. */
private const val OVERDUE_SHOWN = 3

@Composable
fun SearchScreen(
    onOpenTote: (String) -> Unit,
    onOpenSettings: () -> Unit = {},
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Find's counters and the overdue card were frozen at whatever they said the first time the
    // tab was opened, for the life of the process.
    RefreshOnResume(viewModel::refresh)

    SearchContent(
        state = state,
        onQueryChange = viewModel::onQueryChange,
        onOpenTote = onOpenTote,
        onOpenSettings = onOpenSettings,
    )
}

/** Stateless body — renderable in a screenshot test without Hilt or a network. */
@Composable
fun SearchContent(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onOpenTote: (String) -> Unit,
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = ToteTheme.colors
    val spacing = ToteTheme.spacing

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            Modifier.fillMaxSize().padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            item {
                HeroPanel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Tote",
                            style = MaterialTheme.typography.headlineMedium,
                            color = androidx.compose.ui.graphics.Color.White,
                            modifier = Modifier.weight(1f),
                        )
                        // The only door to Settings. It lives on the home hero rather than a
                        // sixth tab because a bottom bar carries five, and because the screen
                        // behind it is an escape hatch — reached when something is wrong, not
                        // in the course of using the app.
                        IconButton(onClick = onOpenSettings) {
                            Icon(
                                Icons.Filled.Settings,
                                contentDescription = "Settings",
                                tint = androidx.compose.ui.graphics.Color.White,
                            )
                        }
                    }
                    Spacer(Modifier.height(spacing.xs))
                    Text(
                        "What's in the bins, and which bin it's in.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.82f),
                    )
                    Spacer(Modifier.height(spacing.md))
                    HazardRule()
                }
            }

            item {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    // Clearing used to mean holding backspace through "ratchet set".
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear")
                            }
                        }
                    },
                    label = { Text("Search everything") },
                    placeholder = { Text("ratchet set, 4T, Zelda…") },
                    // The keyboard's action key says Search rather than newline-into-a-single-
                    // line-field, which does nothing.
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                )
                // `searching` was tracked and never rendered: a slow attic query looked exactly
                // like a frozen screen still showing the previous results.
                if (state.searching) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }

            // The attention card, above the stats and below the search box — idle only, for the
            // same reason as the stats: mid-search it would sit between someone and the answer
            // they came for. A lent thing is remembered by exactly one person and they are not
            // thinking about it, so this is the one thing the app volunteers unprompted.
            if (!state.searched && state.overdue.isNotEmpty()) {
                item {
                    PanelCard(channel = colors.attention.base) {
                        Text(
                            "${state.overdue.size} thing${if (state.overdue.size == 1) "" else "s"} " +
                                "out past the date",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.attention.base,
                        )
                        Spacer(Modifier.height(spacing.xs))
                        // Body text, not Caption: Pulse's caption is upper-cased and
                        // letter-spaced, which is right for a label and wrong for a sentence
                        // naming a person — it shouts, and it wraps badly at these lengths.
                        state.overdue.take(OVERDUE_SHOWN).forEach { item ->
                            Text(
                                "${item.name} · ${item.loanedTo ?: "someone"} · " +
                                    "due ${item.expectedBack}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (state.overdue.size > OVERDUE_SHOWN) {
                            Text(
                                "and ${state.overdue.size - OVERDUE_SHOWN} more",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // Stats only while idle: once someone is searching, a row of counts is noise between
            // them and the answer.
            if (!state.searched) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                        StatTile("Totes", state.totes.toString(), channel = colors.slate.base, modifier = Modifier.weight(1f))
                        StatTile("Items", state.items.toString(), channel = colors.stored.base, modifier = Modifier.weight(1f))
                        StatTile("Out", state.out.toString(), channel = colors.attention.base, modifier = Modifier.weight(1f))
                    }
                }
            }

            if (state.searched) {
                item {
                    SectionHeader(
                        label = if (state.offline) "Results · offline" else "Results",
                        channel = if (state.offline) colors.attention.base else colors.search.base,
                    )
                }
                if (state.offline) {
                    item {
                        // Said plainly rather than hidden: offline results come from a simpler
                        // match than the server's, so presenting them identically would quietly
                        // teach that search is inconsistent.
                        Caption(text = "From the last sync — simpler matching than online")
                    }
                }
            }

            if (state.searched && state.results.isEmpty() && !state.searching) {
                item {
                    EmptyState(
                        icon = Icons.Filled.Search,
                        title = "Nothing matches “${state.query}”",
                        subtitle = "Try fewer words, or check it was ever catalogued.",
                    )
                }
            }

            items(state.results, key = { it.id }) { item ->
                SearchHitRow(item = item, onClick = { item.currentToteId?.let(onOpenTote) })
            }
        }
    }
}

/**
 * One hit: what it is, which bin, and where that bin is.
 *
 * The bin and location are on the row rather than a tap away because that IS the answer — making
 * someone open a detail screen to learn it would turn a one-glance question into two taps.
 */
@Composable
private fun SearchHitRow(item: ItemDto, onClick: () -> Unit) {
    val colors = ToteTheme.colors
    val spacing = ToteTheme.spacing

    PanelCard(onClick = onClick, channel = if (item.isOverdue) colors.attention.base else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // A search result is being matched against a memory of the object, not read.
            ItemThumbnail(item)
            Spacer(Modifier.width(spacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    if (item.quantity > 1) "${item.name} ×${item.quantity}" else item.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(spacing.xs))
                val where = when {
                    item.toteCode != null && item.locationName != null ->
                        "${item.toteCode} · ${item.locationName}"
                    item.toteCode != null -> item.toteCode
                    // An item with no bin is a normal state, not an error — say what it is.
                    item.status == "loaned" -> "Lent out"
                    item.status == "out" -> "Out of its tote"
                    else -> "Not in a tote"
                }
                // The size rides on the same line as the bin rather than earning a row of its
                // own: the question is "which bin", and a second line would compete with the
                // answer. Shown verbatim — this is the tag's own words.
                Caption(
                    text = item.apparel?.sizeRaw?.let { "$where · $it" } ?: where,
                )
            }
            if (item.isOverdue) {
                Spacer(Modifier.width(spacing.sm))
                Icon(
                    Icons.Filled.Inventory2,
                    contentDescription = "Overdue",
                    tint = colors.attention.base,
                )
            }
        }
    }
}

@Preview(name = "Search — results, dark")
@Composable
private fun SearchPreview() {
    ToteTheme(darkTheme = true) {
        SearchContent(
            state = SearchUiState(
                query = "ratchet",
                searched = true,
                results = listOf(
                    ItemDto(
                        id = "1", name = "Ratchet set", quantity = 1, status = "stored",
                        toteCode = "A14", locationName = "Attic",
                    ),
                ),
            ),
            onQueryChange = {},
            onOpenTote = {},
        )
    }
}
