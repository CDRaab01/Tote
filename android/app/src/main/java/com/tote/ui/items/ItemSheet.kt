package com.tote.ui.items

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.tote.data.remote.ApparelDto
import com.tote.data.remote.ItemDto
import com.tote.data.remote.MovementDto
import com.tote.data.remote.PhotoUrls
import com.tote.ui.components.CONDITIONS
import com.tote.ui.components.DEPARTMENTS
import com.tote.ui.components.PickerField
import com.tote.ui.components.PickerList
import com.tote.ui.components.PickerOption
import com.tote.ui.components.asPickerOptions
import com.tote.ui.components.SlateChip
import com.tote.ui.components.ToteButton
import com.tote.ui.components.conditionLabel
import com.tote.ui.components.departmentLabel
import com.tote.ui.theme.ToteTheme
import design.pulse.ui.components.Caption
import design.pulse.ui.components.SectionHeader

/**
 * The sheet, hosted over whatever screen opened it.
 *
 * A [ModalBottomSheet] rather than the alert dialog this grew out of: the dialog could hold a
 * photograph and a delete button and nothing else, and everything an item needs — its details, a
 * new bin, where it has been — did not fit. A sheet has the height for a form and dismisses by
 * dragging, which is the gesture for "never mind" on a screen full of fields.
 *
 * @param onOpenBin navigate to the bin the item is in. Null on the bin's own screen, where the
 *   answer is the screen behind the sheet.
 * @param onLend offered only for something actually in a bin — you cannot lend out what is
 *   already lent out, and the button would be a 422 in disguise.
 * @param extraActionLabel one caller-supplied verb. The person screen uses it for "Mark
 *   outgrown…", which needs a person and so cannot live in the sheet itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemSheet(
    viewModel: ItemSheetViewModel,
    onChanged: () -> Unit,
    onOpenBin: ((String) -> Unit)? = null,
    onLend: ((ItemDto) -> Unit)? = null,
    extraActionLabel: String? = null,
    onExtraAction: ((ItemDto) -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The screen behind re-reads after any write, because a move changes which section the row
    // belongs to, a delete removes it, and both change the counts above it.
    //
    // Collected ABOVE the early return, deliberately: a delete and a move both close the sheet and
    // *then* report, and a collector that only existed while the sheet was open would be gone by
    // the time the report arrived — the emission would land on nobody and the screen behind would
    // still be showing the row that no longer exists.
    LaunchedEffect(viewModel) { viewModel.changes.collect { onChanged() } }

    val item = state.item ?: return
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = viewModel::close,
        sheetState = sheetState,
    ) {
        ItemSheetContent(
            state = state,
            onMode = viewModel::mode,
            onEdit = viewModel::edit,
            onEditApparel = viewModel::editApparel,
            onPickerQuery = viewModel::pickerQuery,
            onPickCategory = viewModel::pickCategory,
            onPickBag = viewModel::pickBag,
            onPickBin = viewModel::moveTo,
            onSave = viewModel::save,
            onConfirmDelete = viewModel::confirmDelete,
            onDelete = viewModel::delete,
            onOpenBin = onOpenBin?.let { open -> { item.currentToteId?.let(open) } },
            onLend = onLend?.takeIf { item.status == "stored" }?.let { lend -> { lend(item) } },
            extraActionLabel = extraActionLabel,
            onExtraAction = onExtraAction?.let { action -> { action(item) } },
        )
    }
}

/**
 * The sheet's body, separated from the sheet.
 *
 * Same reason the picker's list is separate from its dialog: a modal bottom sheet renders in its
 * own window and never reaches idle under Robolectric, so a screenshot of the whole sheet times
 * out. This is the part with the layout worth verifying.
 */
@Composable
fun ItemSheetContent(
    state: ItemSheetState,
    onMode: (SheetMode) -> Unit,
    onEdit: ((ItemEdits) -> ItemEdits) -> Unit,
    onEditApparel: ((ItemEdits) -> ItemEdits) -> Unit,
    onPickerQuery: (String) -> Unit,
    onPickCategory: (String?) -> Unit,
    onPickBag: (String?) -> Unit,
    onPickBin: (String) -> Unit,
    onSave: () -> Unit,
    onConfirmDelete: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onOpenBin: (() -> Unit)? = null,
    onLend: (() -> Unit)? = null,
    extraActionLabel: String? = null,
    onExtraAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val item = state.item ?: return
    val spacing = ToteTheme.spacing

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.lg)
            .padding(bottom = spacing.xl),
    ) {
        Text(item.name, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(spacing.xs))
        Caption(text = whereabouts(item))
        Spacer(Modifier.height(spacing.md))

        when (state.mode) {
            SheetMode.View -> ViewFace(
                state = state,
                onMode = onMode,
                onOpenBin = onOpenBin,
                onLend = onLend,
                onConfirmDelete = onConfirmDelete,
                onDelete = onDelete,
                extraActionLabel = extraActionLabel,
                onExtraAction = onExtraAction,
            )
            SheetMode.Edit -> EditFace(
                state = state,
                onMode = onMode,
                onEdit = onEdit,
                onEditApparel = onEditApparel,
                onSave = onSave,
            )
            SheetMode.History -> HistoryFace(state = state, onMode = onMode)
            SheetMode.PickBin -> PickerFace(
                title = if (item.status == "stored") "Move it to" else "Put it away in",
                subtitle = if (item.status == "stored") {
                    "One ledger row: it left one bin and entered another."
                } else {
                    "It is out right now. Choosing a bin puts it back."
                },
                options = state.bins
                    .filter { it.id != item.currentToteId }
                    .map { PickerOption(id = it.id, label = it.code, detail = binDetail(it.label, it.locationName)) },
                selectedId = null,
                query = state.pickerQuery,
                onQueryChange = onPickerQuery,
                onPick = { id -> id?.let(onPickBin) },
                onCancel = { onMode(SheetMode.View) },
                emptyMessage = "No other bins yet.",
            )
            SheetMode.PickBag -> PickerFace(
                title = "Which bag",
                subtitle = "Bags are groupings inside this bin. Loose is a perfectly good answer.",
                options = state.containers.map {
                    PickerOption(
                        id = it.id,
                        label = it.name,
                        detail = it.notes ?: "${it.itemCount} item${if (it.itemCount == 1) "" else "s"}",
                    )
                },
                selectedId = state.edits.containerId,
                query = state.pickerQuery,
                onQueryChange = onPickerQuery,
                onPick = onPickBag,
                onCancel = { onMode(SheetMode.Edit) },
                noneLabel = "Loose in the bin",
                emptyMessage = "This bin has no bags yet.",
            )
            SheetMode.PickCategory -> PickerFace(
                title = "Category",
                subtitle = null,
                options = state.categories.asPickerOptions(),
                selectedId = state.edits.categoryId,
                query = state.pickerQuery,
                onQueryChange = onPickerQuery,
                onPick = onPickCategory,
                onCancel = { onMode(SheetMode.Edit) },
                noneLabel = "No category",
                emptyMessage = "No categories yet.",
            )
        }
    }
}

@Composable
private fun ViewFace(
    state: ItemSheetState,
    onMode: (SheetMode) -> Unit,
    onOpenBin: (() -> Unit)?,
    onLend: (() -> Unit)?,
    onConfirmDelete: (Boolean) -> Unit,
    onDelete: () -> Unit,
    extraActionLabel: String?,
    onExtraAction: (() -> Unit)?,
) {
    val item = state.item ?: return
    val spacing = ToteTheme.spacing

    if (item.photoCount > 0) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(ToteTheme.colors.panelHigh),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = PhotoUrls.item(item.id, 0, w = 1024),
                contentDescription = "Photo of ${item.name}",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().height(200.dp),
            )
        }
        Spacer(Modifier.height(spacing.md))
    }

    if (state.confirmingDelete) {
        Text(
            if (item.photoCount > 0) {
                "This removes the item, its history, and its photograph. There is no undo."
            } else {
                "This removes the item and its history. There is no undo."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(spacing.sm))
        // Said plainly, because the two are easy to confuse and only one is recoverable.
        Caption(text = "If you still own it and it is just not here, take it out instead.")
        Spacer(Modifier.height(spacing.md))
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            ToteButton(
                text = "Keep it",
                onClick = { onConfirmDelete(false) },
                tonal = true,
                modifier = Modifier.weight(1f),
            )
            ToteButton(
                text = "Delete",
                onClick = onDelete,
                tonal = true,
                enabled = !state.busy,
                channel = MaterialTheme.colorScheme.error,
                dimChannel = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.weight(1f),
            )
        }
        return
    }

    // Labelled, not stacked bare. Three unlabelled sentences — a condition, a description and a
    // note — read as one paragraph written by nobody, and the note in particular ("Washed before
    // it went in") is only useful if you can tell it apart from the description.
    listOfNotNull(
        if (item.quantity > 1) "Quantity" to "${item.quantity}" else null,
        item.condition?.let { "Condition" to conditionLabel(it) },
        item.description?.let { "Description" to it },
        // Notes were carried on every DTO and rendered nowhere in the app, so anything typed into
        // them at capture time went into a field nobody could read again.
        item.notes?.let { "Note" to it },
        item.apparel?.takeIf { it.hasAnything }?.let { "Clothing" to apparelLine(it) },
    ).forEach { (label, value) ->
        Caption(text = label)
        Text(value, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(spacing.sm))
    }

    Spacer(Modifier.height(spacing.sm))
    // Paired rather than stacked full-width. Six identical yellow bars is a wall with no shape to
    // it: nothing leads, and the only thing separating the one that destroys photographs from the
    // five that do not is the colour of its label.
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            ToteButton(
                text = "Edit details",
                onClick = { onMode(SheetMode.Edit) },
                tonal = true,
                enabled = !state.busy,
                modifier = Modifier.weight(1f),
            )
            ToteButton(
                // The core verb of a bin app, and until now it had no button at all: relocating a
                // thing meant deleting it and retyping it, which threw away its photographs and
                // its whole ledger.
                text = if (item.status == "stored") "Move it" else "Put it away",
                onClick = { onMode(SheetMode.PickBin) },
                tonal = true,
                enabled = !state.busy,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            if (onLend != null) {
                ToteButton(
                    text = "Lend it out",
                    onClick = onLend,
                    tonal = true,
                    modifier = Modifier.weight(1f),
                )
            }
            if (extraActionLabel != null && onExtraAction != null) {
                ToteButton(
                    text = extraActionLabel,
                    onClick = onExtraAction,
                    tonal = true,
                    modifier = Modifier.weight(1f),
                )
            }
            ToteButton(
                text = "Where it's been",
                onClick = { onMode(SheetMode.History) },
                tonal = true,
                modifier = Modifier.weight(1f),
            )
        }
        if (onOpenBin != null && item.currentToteId != null) {
            ToteButton(
                text = "Open ${listOfNotNull(item.toteCode, item.locationName).joinToString(" · ")}",
                onClick = onOpenBin,
                tonal = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(spacing.md))
        ToteButton(
            text = "Delete item",
            onClick = { onConfirmDelete(true) },
            tonal = true,
            channel = MaterialTheme.colorScheme.error,
            dimChannel = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun EditFace(
    state: ItemSheetState,
    onMode: (SheetMode) -> Unit,
    onEdit: ((ItemEdits) -> ItemEdits) -> Unit,
    onEditApparel: ((ItemEdits) -> ItemEdits) -> Unit,
    onSave: () -> Unit,
) {
    val colors = ToteTheme.colors
    val spacing = ToteTheme.spacing
    val edits = state.edits

    OutlinedTextField(
        value = edits.name,
        onValueChange = { v -> onEdit { it.copy(name = v) } },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("What is it?") },
    )
    Spacer(Modifier.height(spacing.sm))
    OutlinedTextField(
        value = edits.description,
        onValueChange = { v -> onEdit { it.copy(description = v) } },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Description") },
    )
    Spacer(Modifier.height(spacing.sm))
    OutlinedTextField(
        value = edits.notes,
        onValueChange = { v -> onEdit { it.copy(notes = v) } },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Notes") },
    )
    Spacer(Modifier.height(spacing.sm))
    OutlinedTextField(
        value = edits.quantity.toString(),
        onValueChange = { v ->
            val n = v.filter(Char::isDigit).take(3).toIntOrNull() ?: 1
            onEdit { it.copy(quantity = n.coerceAtLeast(1)) }
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("Quantity") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )

    Spacer(Modifier.height(spacing.md))
    SectionHeader(label = "Category", channel = colors.search.base)
    Spacer(Modifier.height(spacing.sm))
    PickerField(
        selected = state.categories.firstOrNull { it.id == edits.categoryId }?.name,
        placeholder = "No category",
        onClick = { onMode(SheetMode.PickCategory) },
    )

    if (state.containers.isNotEmpty()) {
        Spacer(Modifier.height(spacing.md))
        SectionHeader(label = "Which bag", channel = colors.provenance.base)
        Spacer(Modifier.height(spacing.sm))
        PickerField(
            selected = state.containers.firstOrNull { it.id == edits.containerId }?.name,
            placeholder = "Loose in the bin",
            onClick = { onMode(SheetMode.PickBag) },
        )
    }

    Spacer(Modifier.height(spacing.md))
    SectionHeader(label = "Condition", channel = colors.stored.base)
    Spacer(Modifier.height(spacing.sm))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        CONDITIONS.forEach { condition ->
            SlateChip(
                selected = edits.condition == condition,
                label = conditionLabel(condition),
                onClick = {
                    onEdit { it.copy(condition = if (it.condition == condition) null else condition) }
                },
            )
        }
    }

    Spacer(Modifier.height(spacing.md))
    SectionHeader(label = "If it's clothing", channel = colors.provenance.base)
    Spacer(Modifier.height(spacing.sm))
    OutlinedTextField(
        value = edits.sizeRaw,
        onValueChange = { v -> onEditApparel { it.copy(sizeRaw = v) } },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("Size, exactly as the tag reads") },
        placeholder = { Text("4T, 6X, 32x30, M/L…") },
    )
    Spacer(Modifier.height(spacing.sm))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        DEPARTMENTS.forEach { dept ->
            SlateChip(
                selected = edits.department == dept,
                label = departmentLabel(dept),
                onClick = {
                    onEditApparel { it.copy(department = if (it.department == dept) null else dept) }
                },
            )
        }
    }
    Spacer(Modifier.height(spacing.xs))
    // Left untouched, the whole block is omitted and the server keeps what the label pass read —
    // which is the only reading of a tag now sealed in a bin.
    Caption(text = "Leave this alone and the tag's reading stays exactly as it was.")

    Spacer(Modifier.height(spacing.lg))
    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
        ToteButton(
            text = "Cancel",
            onClick = { onMode(SheetMode.View) },
            tonal = true,
            modifier = Modifier.weight(1f),
        )
        ToteButton(
            text = "Save",
            onClick = onSave,
            enabled = edits.canSave && !state.busy,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Where this thing has been.
 *
 * The ledger, read back. "Where was this last year" was answerable by the database and by nothing
 * a person could tap — every movement row the app has been carefully writing since Phase 2 was
 * write-only until now.
 */
@Composable
private fun HistoryFace(state: ItemSheetState, onMode: (SheetMode) -> Unit) {
    val spacing = ToteTheme.spacing

    SectionHeader(label = "Where it's been", channel = ToteTheme.colors.provenance.base)
    Spacer(Modifier.height(spacing.sm))

    if (!state.historyLoaded) {
        Caption(text = "Reading the ledger…")
    } else if (state.movements.isEmpty()) {
        // Distinguished from "we could not read it": a failure speaks through the snackbar and
        // leaves this saying "reading", so an empty list here really is an empty history.
        Caption(text = "Nothing recorded — it has not moved since it was catalogued.")
    } else {
        Column(
            Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            state.movements.forEach { movement ->
                Column {
                    Text(
                        movementLine(movement, state::codeFor),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Caption(
                        text = state.actorFor(movement.movedByUserId)
                            ?.let { "${'$'}{movement.movedAt.take(10)} · ${'$'}it" }
                            ?: movement.movedAt.take(10)
                    )
                    if (movement.note != null) {
                        Text(movement.note, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(spacing.lg))
    ToteButton(
        text = "Back",
        onClick = { onMode(SheetMode.View) },
        tonal = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PickerFace(
    title: String,
    subtitle: String?,
    options: List<PickerOption>,
    selectedId: String?,
    query: String,
    onQueryChange: (String) -> Unit,
    onPick: (String?) -> Unit,
    onCancel: () -> Unit,
    noneLabel: String? = null,
    emptyMessage: String? = null,
) {
    val spacing = ToteTheme.spacing

    SectionHeader(label = title, channel = ToteTheme.colors.slate.base)
    Spacer(Modifier.height(spacing.sm))
    PickerList(
        options = options,
        selectedId = selectedId,
        onPick = onPick,
        query = query,
        onQueryChange = onQueryChange,
        subtitle = subtitle,
        noneLabel = noneLabel,
        emptyMessage = emptyMessage,
    )
    Spacer(Modifier.height(spacing.lg))
    ToteButton(text = "Cancel", onClick = onCancel, tonal = true, modifier = Modifier.fillMaxWidth())
}

/** "In A14 · Attic", or the honest truth that it is not in a bin. */
private fun whereabouts(item: ItemDto): String = when {
    item.isOverdue ->
        "Overdue — ${item.loanedTo ?: "someone"} has had it since ${item.expectedBack}"
    item.status == "loaned" -> "Lent to ${item.loanedTo ?: "someone"}"
    item.status == "out" -> "Out of its bin"
    item.toteCode != null ->
        "In " + listOfNotNull(item.toteCode, item.locationName).joinToString(" · ")
    else -> "Not in a bin"
}

private fun binDetail(label: String?, locationName: String?): String? =
    listOfNotNull(label, locationName).joinToString(" · ").takeIf { it.isNotEmpty() }

/** The tag's own words first, then anything else recorded about the garment. */
private fun apparelLine(apparel: ApparelDto): String = listOfNotNull(
    apparel.sizeRaw?.let { "Size $it" },
    apparel.department?.let(::departmentLabel),
    apparel.material,
    apparel.season,
).joinToString(" · ")

/**
 * One ledger row as a sentence.
 *
 * Named by the reason rather than by the arrow between two bins, because the reason is the part a
 * person remembers — "we unpacked it at Christmas" is the memory, not "A14 → nothing".
 */
internal fun movementLine(movement: MovementDto, codeFor: (String?) -> String?): String {
    val from = codeFor(movement.fromToteId)
    val to = codeFor(movement.toToteId)
    return when (movement.reason) {
        "initial" -> "Catalogued into ${to ?: "a bin"}"
        "moved" -> "Moved from ${from ?: "nowhere"} to ${to ?: "nowhere"}"
        "unpacked" -> "Unpacked from ${from ?: "its bin"}"
        "repacked" -> "Put back into ${to ?: "a bin"}"
        "outgrown" -> "Outgrown, filed into ${to ?: "a bin"}"
        "loaned" -> "Lent out"
        "returned" -> "Returned into ${to ?: "a bin"}"
        "disposed" -> "Disposed of"
        "corrected" -> "Corrected"
        // `from` is deliberately not read here: `movements.from_tote_id` is SET NULL when the
        // tote goes, so it is always null on this row by the time anyone reads it. The bin's
        // code survives in the note, which the history renders on the line below.
        "bin_deleted" -> "Its bin was deleted"
        "catalogued" -> "Catalogued, no bin yet"
        else -> movement.reason.replaceFirstChar { it.uppercase() }
    }
}

@Preview(name = "Item sheet — dark")
@Composable
private fun ItemSheetPreview() {
    ToteTheme(darkTheme = true) {
        ItemSheetContent(
            state = ItemSheetState(
                item = ItemDto(
                    id = "1",
                    name = "Toddler bed comforter",
                    description = "Grey, with the star pattern",
                    quantity = 1,
                    condition = "good",
                    status = "stored",
                    currentToteId = "t1",
                    toteCode = "A14",
                    locationName = "Attic",
                ),
            ),
            onMode = {}, onEdit = {}, onEditApparel = {}, onPickerQuery = {},
            onPickCategory = {}, onPickBag = {}, onPickBin = {}, onSave = {},
            onConfirmDelete = {}, onDelete = {},
        )
    }
}
