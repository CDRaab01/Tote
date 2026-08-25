package com.tote.ui.verify

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tote.data.remote.ItemDto
import com.tote.data.remote.ToteDetailDto
import com.tote.ui.components.ItemThumbnail
import com.tote.ui.components.ToteButton
import com.tote.ui.components.ToteGlyph
import com.tote.ui.theme.ToteTheme
import com.tote.ui.totes.STALE_AFTER_MONTHS
import com.tote.ui.totes.monthsSince
import design.pulse.ui.components.Caption
import design.pulse.ui.components.EmptyState
import design.pulse.ui.components.ErrorState
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.SectionHeader

/**
 * Checking a bin against the catalog, with the lid open.
 *
 * Its own screen rather than a mode on the bin screen, and that is the point: verifying is a
 * different posture from browsing. You are standing at an open bin holding the phone in one
 * hand, working down a list, and every other verb the bin screen offers — unpack, move, bag,
 * lend — is a way to lose your place. Here there are exactly two answers per row and one button
 * at the end.
 *
 * The pass is refused until every stored item has an answer, because a half-finished check that
 * stamped a date would make the catalog LESS trustworthy than never checking at all.
 */
@Composable
fun VerifyScreen(
    onDone: () -> Unit = {},
    viewModel: VerifyViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The pass lands after the screen has said its piece through the app-wide snackbar, so the
    // signal to leave comes from the ViewModel rather than from the button's own click.
    LaunchedEffect(viewModel) { viewModel.done.collect { onDone() } }

    VerifyContent(
        state = state,
        onMark = viewModel::mark,
        onFinish = viewModel::finish,
        onRetry = viewModel::load,
    )
}

@Composable
fun VerifyContent(
    state: VerifyUiState,
    onMark: (String, Boolean) -> Unit = { _, _ -> },
    onFinish: () -> Unit = {},
    onRetry: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = ToteTheme.colors
    val spacing = ToteTheme.spacing
    val tote = state.tote

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            state.error != null -> ErrorState(
                icon = Icons.Filled.Inventory2,
                title = "Couldn't load this bin",
                detail = state.error,
                onRetry = onRetry,
            )
            tote == null -> Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { CircularProgressIndicator() }
            else -> Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = spacing.lg,
                        end = spacing.lg,
                        top = spacing.lg,
                        // Room for the pinned footer, so the last row can be ticked.
                        bottom = 140.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    item {
                        SectionHeader(label = "Verify bin", channel = colors.slate.base)
                    }

                    item { VerifyContextCard(tote) }

                    if (state.items.isEmpty()) {
                        item {
                            // Explain why it is empty rather than showing a bare screen with a
                            // live button on it: an empty bin is a perfectly good thing to
                            // verify, and someone who does not know that will back out.
                            EmptyState(
                                icon = Icons.Filled.Inventory2,
                                title = "Nothing filed in this bin",
                                subtitle = "Verifying just stamps the date — which is worth " +
                                    "having, because \"checked, and it really is empty\" is a " +
                                    "fact the catalog can't hold any other way.",
                            )
                        }
                    } else {
                        item {
                            VerifyProgress(decided = state.decided, total = state.items.size)
                        }
                        items(state.items, key = { it.id }) { item ->
                            VerifyRow(
                                item = item,
                                here = when {
                                    item.id in state.present -> true
                                    item.id in state.missing -> false
                                    else -> null
                                },
                                onMark = { onMark(item.id, it) },
                            )
                        }
                    }
                }

                // The same pinned slot as the bin screen's verbs: the button that ends the pass
                // is under the thumb that has been ticking rows, not scrolled away below them.
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                0f to MaterialTheme.colorScheme.background.copy(alpha = 0f),
                                0.4f to MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
                                1f to MaterialTheme.colorScheme.background,
                            )
                        )
                        .padding(
                            start = spacing.lg,
                            end = spacing.lg,
                            top = spacing.xl,
                            bottom = spacing.lg,
                        ),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Caption(
                            text = "${state.present.size} here",
                            color = if (state.present.isEmpty()) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                colors.stored.base
                            },
                        )
                        // Only once there is one: a standing "0 not here" is a number people
                        // learn to skip, and this is the one that has to be read.
                        if (state.missing.isNotEmpty()) {
                            Caption(
                                text = "${state.missing.size} not here",
                                color = colors.attention.base,
                            )
                        }
                    }
                    Spacer(Modifier.height(spacing.sm))
                    ToteButton(
                        // The button says what it writes. "Done" would leave someone guessing
                        // whether the date moved.
                        text = "Finish — mark ${tote.code} verified today",
                        onClick = onFinish,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state.complete && !state.submitting,
                    )
                }
            }
        }
    }
}

/**
 * Which bin this is, and how long its catalog has been taken on trust.
 *
 * Rose for stale AND for never-verified here, unlike the bins list — this is the screen you are
 * on precisely because that question is being asked, so the answer is allowed to be loud. On the
 * list it would be fourteen rose rows saying nothing.
 */
@Composable
private fun VerifyContextCard(tote: ToteDetailDto) {
    val colors = ToteTheme.colors
    val spacing = ToteTheme.spacing
    val months = monthsSince(tote.lastVerifiedAt)
    val overdue = tote.lastVerifiedAt == null || (months != null && months > STALE_AFTER_MONTHS)

    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ToteGlyph(code = tote.code, colorHex = tote.colorHex)
            Spacer(Modifier.width(spacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    tote.label ?: "Unlabelled",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                tote.locationName?.let {
                    Spacer(Modifier.height(spacing.xs))
                    Caption(text = it)
                }
                Spacer(Modifier.height(spacing.xs))
                Text(
                    when {
                        tote.lastVerifiedAt == null -> "Never verified"
                        months == null -> "Verified ${tote.lastVerifiedAt.take(10)}"
                        months < 1 -> "Verified this month"
                        else -> "Verified $months month${if (months == 1L) "" else "s"} ago"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (overdue) {
                        colors.attention.base
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/** How far through the bin the pass is — the one number that says whether Finish will work. */
@Composable
private fun VerifyProgress(decided: Int, total: Int) {
    val colors = ToteTheme.colors
    val spacing = ToteTheme.spacing

    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Tick what you can see in the bin",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(spacing.sm))
            Text(
                "$decided of $total",
                style = ToteTheme.dataType.dataSmall,
                color = colors.stored.base,
            )
        }
        Spacer(Modifier.height(spacing.sm))
        LinearProgressIndicator(
            // The stored channel, because the bar fills as the bin is confirmed — it is a
            // measure of what is accounted for, not a warning.
            progress = { if (total == 0) 1f else decided.toFloat() / total },
            modifier = Modifier.fillMaxWidth(),
            color = colors.stored.base,
            trackColor = colors.panelHigh,
        )
    }
}

/**
 * One stored item, and the two things that can be true of it.
 *
 * Two chips rather than a checkbox because "not ticked" and "not there" are different answers
 * and only one of them is a decision. A checklist with a single box would let a bin be finished
 * by scrolling past everything, which is the exact failure a verify pass exists to catch.
 */
@Composable
private fun VerifyRow(item: ItemDto, here: Boolean?, onMark: (Boolean) -> Unit) {
    val colors = ToteTheme.colors
    val spacing = ToteTheme.spacing

    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Same picture as everywhere else the item is listed: "which of these six onesies"
            // is a question text cannot answer.
            ItemThumbnail(item, size = 44.dp)
            Spacer(Modifier.width(spacing.sm))
            Column(Modifier.weight(1f)) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // Verbatim, like every other row that shows a size — it is the tag's own words.
                item.apparel?.sizeRaw?.let {
                    Spacer(Modifier.height(spacing.xs))
                    Caption(text = it)
                }
            }
            Spacer(Modifier.width(spacing.sm))
            FilterChip(
                selected = here == true,
                onClick = { onMark(true) },
                label = { Text("Here") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = colors.stored.dim,
                    selectedLabelColor = colors.stored.base,
                ),
            )
            Spacer(Modifier.width(spacing.xs))
            FilterChip(
                selected = here == false,
                onClick = { onMark(false) },
                label = { Text("Not here") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = colors.attention.dim,
                    selectedLabelColor = colors.attention.base,
                ),
            )
        }
        if (here == false) {
            // Said the moment it becomes true, not in a confirm dialog at the end: marking
            // something missing writes to the ledger, and someone should know that while they
            // are still looking at the shelf they might check again.
            Spacer(Modifier.height(spacing.xs))
            Text(
                "Will be marked out with a corrected move — the ledger keeps the history.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(name = "Verify — mid-pass, dark")
@Composable
private fun VerifyPreview() {
    ToteTheme(darkTheme = true) {
        VerifyContent(
            state = VerifyUiState(
                tote = ToteDetailDto(
                    id = "1", code = "A14", label = "Christmas decor", locationName = "Attic",
                    colorHex = "#7A1F2B", lastVerifiedAt = "2024-12-02", itemCount = 3,
                    items = listOf(
                        ItemDto(id = "a", name = "Pre-lit tree, 7ft", quantity = 1, status = "stored"),
                        ItemDto(id = "b", name = "Ornament box", quantity = 4, status = "stored"),
                        ItemDto(id = "c", name = "Tree skirt", quantity = 1, status = "stored"),
                    ),
                ),
                present = setOf("a"),
                missing = setOf("b"),
            ),
        )
    }
}
