package com.tote.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.tote.data.local.CachedTote
import com.tote.data.remote.CategoryDto
import com.tote.data.remote.DraftDto
import com.tote.data.remote.PhotoUrls
import com.tote.ui.components.HazardRule
import com.tote.ui.components.ToteButton
import com.tote.ui.theme.ToteTheme
import design.pulse.ui.components.Caption
import design.pulse.ui.components.EmptyState
import design.pulse.ui.components.ErrorState
import design.pulse.ui.components.HeroPanel
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.SectionHeader

/** The conditions the server accepts, in the order a person would rank them. */
private val CONDITIONS = listOf("new", "like_new", "good", "fair", "poor")

private fun conditionLabel(value: String) = when (value) {
    "new" -> "New"
    "like_new" -> "Like new"
    "good" -> "Good"
    "fair" -> "Fair"
    "poor" -> "Poor"
    else -> value
}

/**
 * The review stack — the gate between a photograph and the catalog.
 *
 * Nothing the model produced is filed until someone taps Confirm here, and every field it filled
 * in is editable before they do. That is the house AI rule, and Tote has no exception to it.
 */
@Composable
fun ReviewScreen(
    viewModel: ReviewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ReviewContent(
        state = state,
        onEdit = viewModel::edit,
        onConfirm = { viewModel.confirm() },
        onDiscard = viewModel::discard,
        onSkip = viewModel::skip,
        onBack = viewModel::back,
        onRetry = viewModel::refresh,
    )
}

/** Stateless body — renderable in a screenshot test without Hilt or a network. */
@Composable
fun ReviewContent(
    state: ReviewUiState,
    onEdit: ((DraftEdits) -> DraftEdits) -> Unit,
    onConfirm: () -> Unit,
    onDiscard: () -> Unit,
    onSkip: () -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    photoUrlFor: (String, Int) -> String = { id, order -> PhotoUrls.item(id, order) },
) {
    val colors = ToteTheme.colors
    val spacing = ToteTheme.spacing
    val draft = state.current

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            Modifier.fillMaxSize().padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            item {
                HeroPanel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Review",
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.White,
                            modifier = Modifier.weight(1f),
                        )
                        if (state.drafts.isNotEmpty()) {
                            Text(
                                "${state.position} of ${state.drafts.size}",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White.copy(alpha = 0.82f),
                            )
                        }
                    }
                    Spacer(Modifier.height(spacing.xs))
                    Text(
                        "Nothing is in a bin until you say it is.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.82f),
                    )
                    Spacer(Modifier.height(spacing.md))
                    HazardRule()
                }
            }

            if (state.error != null && draft == null) {
                item {
                    ErrorState(
                        icon = Icons.Outlined.CloudOff,
                        title = "Couldn't load the review stack",
                        detail = state.error,
                        onRetry = onRetry,
                    )
                }
            } else if (draft == null && !state.loading) {
                item {
                    EmptyState(
                        icon = Icons.Outlined.CheckCircle,
                        title = "Nothing waiting",
                        subtitle = "Drafts land here once a capture has uploaded and been " +
                            "identified. Photograph something on the Catalogue tab.",
                    )
                }
            } else if (draft != null) {
                // ── The photographs ──────────────────────────────────────────
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        items(draft.photoCount) { order ->
                            Box(
                                Modifier
                                    .size(160.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(colors.panelHigh)
                            ) {
                                AsyncImage(
                                    model = photoUrlFor(draft.id, order),
                                    contentDescription = "Photo ${order + 1} of the item",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }

                // ── How much to trust what is below ──────────────────────────
                scanNotice(draft)?.let { notice ->
                    item {
                        PanelCard(channel = colors.attention.base) {
                            Text(
                                notice,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }

                // ── What it is ───────────────────────────────────────────────
                item { SectionHeader(label = "What it is", channel = colors.slate.base) }
                item {
                    OutlinedTextField(
                        value = state.edits.name,
                        onValueChange = { v -> onEdit { it.copy(name = v) } },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = state.edits.name.isBlank(),
                        label = { Text("Name") },
                    )
                }
                item {
                    OutlinedTextField(
                        value = state.edits.description,
                        onValueChange = { v -> onEdit { it.copy(description = v) } },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        label = { Text("Description") },
                    )
                }
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // A tote holds "4x ornament box"; forcing four rows is worse than a count.
                        QuantityStepper(
                            quantity = state.edits.quantity,
                            onChange = { v -> onEdit { it.copy(quantity = v) } },
                        )
                    }
                }

                if (state.categories.isNotEmpty()) {
                    item { SectionHeader(label = "Category", channel = colors.search.base) }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                            items(state.categories, key = { it.id }) { category ->
                                SlateChip(
                                    selected = state.edits.categoryId == category.id,
                                    label = category.name,
                                    // Tapping the selected one clears it: a wrong category is
                                    // worse than none, and there must be a way back to none.
                                    onClick = {
                                        onEdit {
                                            it.copy(
                                                categoryId =
                                                    if (it.categoryId == category.id) null
                                                    else category.id
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                item { SectionHeader(label = "Condition", channel = colors.stored.base) }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        items(CONDITIONS) { condition ->
                            SlateChip(
                                selected = state.edits.condition == condition,
                                label = conditionLabel(condition),
                                onClick = {
                                    onEdit {
                                        it.copy(
                                            condition =
                                                if (it.condition == condition) null else condition
                                        )
                                    }
                                },
                            )
                        }
                    }
                }

                // ── Which bin ────────────────────────────────────────────────
                item {
                    SectionHeader(
                        label = "Into which bin",
                        channel = if (state.edits.toteId == null) {
                            colors.attention.base
                        } else {
                            colors.slate.base
                        },
                    )
                }
                item {
                    if (state.totes.isEmpty()) {
                        Caption(
                            text = "No bins cached. Open the Totes tab while connected, then " +
                                "come back — filing needs a destination.",
                        )
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                            items(state.totes, key = { it.id }) { tote ->
                                SlateChip(
                                    selected = state.edits.toteId == tote.id,
                                    label = tote.label?.let { "${tote.code} · $it" } ?: tote.code,
                                    onClick = { onEdit { it.copy(toteId = tote.id) } },
                                )
                            }
                        }
                    }
                }

                if (state.error != null) {
                    item {
                        Text(
                            state.error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                // ── The decision ─────────────────────────────────────────────
                item {
                    ToteButton(
                        text = when {
                            state.saving -> "Filing…"
                            state.edits.toteId == null -> "Choose a bin to file it"
                            else -> "File it"
                        },
                        onClick = onConfirm,
                        enabled = state.edits.canConfirm && !state.saving,
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Inventory2,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                        ToteButton(
                            text = "Back",
                            onClick = onBack,
                            tonal = true,
                            enabled = state.index > 0 && !state.saving,
                            modifier = Modifier.weight(1f),
                        )
                        ToteButton(
                            text = "Skip",
                            onClick = onSkip,
                            tonal = true,
                            enabled = state.index < state.drafts.lastIndex && !state.saving,
                            modifier = Modifier.weight(1f),
                        )
                        // The error voice, not the accent. This deletes the photographs, which
                        // are the one artefact in this app that cannot be recreated — three
                        // identical-looking buttons where the third is unrecoverable is a row
                        // designed for the wrong tap.
                        ToteButton(
                            text = "Discard",
                            onClick = onDiscard,
                            tonal = true,
                            enabled = !state.saving,
                            channel = MaterialTheme.colorScheme.error,
                            dimChannel = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                item {
                    Caption(
                        text = "Discarding deletes the photographs too — they exist nowhere else " +
                            "once the queue has uploaded them.",
                    )
                }
            }
        }
    }
}

/**
 * What to say about the identification, when there is something worth saying.
 *
 * The two cases are kept apart deliberately, because they are the same screen with completely
 * different meanings: `identify_unavailable` means the model could not be reached and nobody
 * looked at this photograph at all, while a low confidence means it looked and found the photo
 * hard. Collapsing them into "check this" would send someone to reshoot a perfectly good picture
 * during a server outage.
 */
private fun scanNotice(draft: DraftDto): String? = when {
    draft.scanError == "identify_unavailable" ->
        "Nothing was identified — the vision model could not be reached when this uploaded. " +
            "The photographs are safe; fill the details in yourself, or file it and edit later."
    draft.scanConfidence == "low" ->
        "Low confidence. The photograph was hard to read, so check the name and category before " +
            "filing."
    else -> null
}

/** Selection in Tote's own channel — see the note in the capture screen's chip. */
@Composable
private fun SlateChip(selected: Boolean, label: String, onClick: () -> Unit) {
    val slate = ToteTheme.colors.slate
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = slate.dim,
            selectedLabelColor = slate.base,
        ),
    )
}

@Composable
private fun QuantityStepper(quantity: Int, onChange: (Int) -> Unit) {
    val spacing = ToteTheme.spacing
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Text("Quantity", style = MaterialTheme.typography.bodyLarge)
        ToteButton(
            text = "−",
            onClick = { onChange((quantity - 1).coerceAtLeast(1)) },
            tonal = true,
            compact = true,
            enabled = quantity > 1,
        )
        Text(
            quantity.toString(),
            style = ToteTheme.dataType.dataSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        ToteButton(
            text = "+",
            onClick = { onChange(quantity + 1) },
            tonal = true,
            compact = true,
        )
    }
}

@Preview(name = "Review — dark")
@Composable
private fun ReviewPreview() {
    ToteTheme(darkTheme = true) {
        ReviewContent(
            state = ReviewUiState(
                drafts = listOf(
                    DraftDto(
                        id = "1", name = "Red storage box", scanConfidence = "low", photoCount = 2,
                    )
                ),
                edits = DraftEdits(name = "Red storage box", toteId = "1"),
                totes = listOf(
                    CachedTote("1", "A14", "Christmas decor", null, "Attic", 37, 0, false),
                ),
                categories = listOf(CategoryDto("c1", "Seasonal decor")),
                loading = false,
            ),
            onEdit = {}, onConfirm = {}, onDiscard = {}, onSkip = {}, onBack = {}, onRetry = {},
            photoUrlFor = { _, _ -> "" },
        )
    }
}
