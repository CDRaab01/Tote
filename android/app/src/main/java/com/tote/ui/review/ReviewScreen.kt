package com.tote.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.tote.data.local.CachedTote
import com.tote.data.remote.CategoryDto
import com.tote.data.remote.DraftDto
import com.tote.data.remote.PhotoUrls
import com.tote.ui.components.HazardRule
import com.tote.ui.components.PickerDialog
import com.tote.ui.components.PickerField
import com.tote.ui.components.CONDITIONS
import com.tote.ui.components.DEPARTMENTS
import com.tote.ui.components.PickerOption
import com.tote.ui.components.SlateChip
import com.tote.ui.components.conditionLabel
import com.tote.ui.components.departmentLabel
import com.tote.ui.components.ToteButton
import com.tote.ui.theme.ToteTheme
import design.pulse.ui.components.Caption
import design.pulse.ui.components.EmptyState
import design.pulse.ui.components.ErrorState
import design.pulse.ui.components.HeroPanel
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.SectionHeader

/**
 * The review stack — the gate between a photograph and the catalog.
 *
 * Nothing the model produced is filed until someone taps Confirm here, and every field it filled
 * in is editable before they do. That is the house AI rule, and Tote has no exception to it.
 */
@Composable
fun ReviewScreen(
    onPhotographSomething: () -> Unit = {},
    viewModel: ReviewViewModel = hiltViewModel(),
) {
    var confirmingDiscard by remember { mutableStateOf(false) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Re-read on every resume, not just on first composition. This ViewModel survives a tab
    // switch, so without this a draft that finished uploading while the app was open stayed
    // invisible until the app was killed — while the tab badge, which polls, counted it. See
    // ReviewViewModel.syncPreservingPosition.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.syncPreservingPosition()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    ReviewContent(
        state = state,
        onEdit = viewModel::edit,
        onEditApparel = viewModel::editApparel,
        onConfirm = { viewModel.confirm() },
        onDiscard = { confirmingDiscard = true },
        onSkip = viewModel::skip,
        onPhotographSomething = onPhotographSomething,
        onBack = viewModel::back,
        onRetry = viewModel::refresh,
    )

    if (confirmingDiscard) {
        // Two steps, because this is one of the two photo-destroying actions in the app — and it
        // sat one mis-tap from Skip in a row of equal-weight buttons. The recoverable delete of
        // a FILED item already had a confirm; the unrecoverable one did not.
        AlertDialog(
            onDismissRequest = { confirmingDiscard = false },
            title = { Text("Discard this draft?") },
            text = {
                Text(
                    "This deletes the photographs too — they exist nowhere else once the queue " +
                        "has uploaded them. There is no undo.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.discard()
                        confirmingDiscard = false
                    },
                ) { Text("Discard", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDiscard = false }) { Text("Keep it") }
            },
        )
    }
}

/** Stateless body — renderable in a screenshot test without Hilt or a network. */
@Composable
fun ReviewContent(
    state: ReviewUiState,
    onEdit: ((DraftEdits) -> DraftEdits) -> Unit,
    onEditApparel: ((DraftEdits) -> DraftEdits) -> Unit = onEdit,
    onConfirm: () -> Unit,
    onDiscard: () -> Unit,
    onSkip: () -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onPhotographSomething: () -> Unit = {},
    modifier: Modifier = Modifier,
    photoUrlFor: (String, Int) -> String = { id, order -> PhotoUrls.item(id, order) },
) {
    val colors = ToteTheme.colors
    val spacing = ToteTheme.spacing
    val draft = state.current
    var showTotePicker by remember { mutableStateOf(false) }
    var showCategoryPicker by remember { mutableStateOf(false) }

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
            } else if (draft == null && state.loading) {
                // Neither branch matched while loading, so the screen was a hero panel over
                // empty space with no spinner — indistinguishable from an empty stack.
                item {
                    Box(Modifier.fillMaxWidth().padding(spacing.xl), Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else if (draft == null) {
                item {
                    Column {
                        EmptyState(
                            icon = Icons.Outlined.CheckCircle,
                            title = "Nothing waiting",
                            subtitle = "Drafts land here once a capture has uploaded and been " +
                                "identified.",
                        )
                        Spacer(Modifier.height(spacing.md))
                        // A button, not prose naming a tab. The empty review stack is the
                        // natural end of one batch and the natural start of the next.
                        ToteButton(
                            text = "Photograph something",
                            onClick = onPhotographSomething,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
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
                        PickerField(
                            selected = state.categories
                                .firstOrNull { it.id == state.edits.categoryId }?.name,
                            placeholder = "No category",
                            onClick = { showCategoryPicker = true },
                        )
                    }
                }

                item { SectionHeader(label = "Condition", channel = colors.stored.base) }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        CONDITIONS.forEach { condition ->
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

                // ── Clothing, if this is clothing ────────────────────────────
                item {
                    SectionHeader(
                        label = "If it's clothing",
                        channel = colors.provenance.base,
                    )
                }
                item {
                    OutlinedTextField(
                        value = state.edits.sizeRaw,
                        onValueChange = { v -> onEditApparel { it.copy(sizeRaw = v) } },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Size, exactly as the tag reads") },
                        placeholder = { Text("4T, 6X, 32x30, M/L…") },
                        // The derived reading sits UNDER the raw value rather than in the
                        // section header's trailing slot, where it was clipped — and this is the
                        // better place for it anyway: it describes this field, and it has the
                        // room to say which of the two outcomes happened rather than only one.
                        supportingText = { Text(sizeSupportingText(draft)) },
                    )
                }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        DEPARTMENTS.forEach { dept ->
                            SlateChip(
                                selected = state.edits.department == dept,
                                label = departmentLabel(dept),
                                onClick = {
                                    onEditApparel {
                                        it.copy(
                                            department =
                                                if (it.department == dept) null else dept
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
                item {
                    // Not decoration: a bare "8" cannot be placed on the ladder without one.
                    // Kept to one line — Caption renders in caps, and three lines of it shouts.
                    Caption(text = "Needed to tell a youth 8 from a women's 8.")
                }
                item {
                    OutlinedTextField(
                        value = state.edits.material,
                        onValueChange = { v -> onEditApparel { it.copy(material = v) } },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Material") },
                    )
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
                        PickerField(
                            selected = state.totes
                                .firstOrNull { it.id == state.edits.toteId }
                                ?.let { tote -> tote.label?.let { "${tote.code} · $it" } ?: tote.code },
                            placeholder = "Choose a bin",
                            onClick = { showTotePicker = true },
                        )
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
                            // Wraps past the end (see ReviewViewModel.skip), so only a single-draft stack pins you.
                            enabled = state.drafts.size > 1 && !state.saving,
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

    if (showTotePicker) {
        PickerDialog(
            title = "Filing into",
            options = state.totes.map { tote ->
                PickerOption(
                    id = tote.id,
                    label = tote.label?.let { "${tote.code} · $it" } ?: tote.code,
                    detail = tote.locationName,
                )
            },
            selectedId = state.edits.toteId,
            onPick = { id ->
                onEdit { it.copy(toteId = id) }
                showTotePicker = false
            },
            onDismiss = { showTotePicker = false },
            // No "none" row: filing REQUIRES a bin. An item that is confirmed into the catalog
            // and in no bin is indistinguishable from a bug.
            searchHint = "Search bins",
        )
    }
    if (showCategoryPicker) {
        PickerDialog(
            title = "Category",
            options = state.categories.map { PickerOption(id = it.id, label = it.name) },
            selectedId = state.edits.categoryId,
            onPick = { id ->
                onEdit { it.copy(categoryId = id) }
                showCategoryPicker = false
            },
            onDismiss = { showCategoryPicker = false },
            // A wrong category is worse than none, so there is always a way back to none — and
            // as an explicit row, because "tap the selected chip again" only works if you
            // already knew it.
            noneLabel = "No category",
            searchHint = "Search categories",
        )
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

/**
 * What to say under the size field.
 *
 * Both outcomes are stated, and neither is phrased as a fault. A string the ladder could not
 * place is the **designed** result — the reading survives verbatim and a human reads it — so
 * calling it "unrecognised" would turn a working outcome into a chore the reviewer thinks they
 * have to fix.
 */
private fun sizeSupportingText(draft: DraftDto): String {
    val system = draft.apparel?.sizeSystem
        ?: return "Kept word for word. Not placed on the size ladder, which is fine — " +
            "nothing is ever guessed."
    val ladder = when (system) {
        "infant_months" -> "infant sizing"
        "toddler" -> "toddler sizing"
        "youth_numeric", "youth_alpha" -> "youth sizing"
        "adult_alpha" -> "adult sizing"
        "womens_numeric" -> "women's sizing"
        "mens_waist" -> "men's waist sizing"
        "shoe_us_child", "shoe_us_adult" -> "shoe sizing"
        else -> system
    }
    return "Kept word for word, and placed on the ladder as $ladder."
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
            onEdit = {}, onEditApparel = {}, onConfirm = {}, onDiscard = {}, onSkip = {},
            onBack = {}, onRetry = {}, photoUrlFor = { _, _ -> "" },
        )
    }
}
