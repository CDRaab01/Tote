package com.tote.ui.totes

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tote.data.remote.CategoryDto
import com.tote.data.remote.ContainerDto
import com.tote.data.remote.ItemDto
import com.tote.data.remote.LocationDto
import com.tote.data.remote.PersonDto
import com.tote.data.remote.ToteDetailDto
import com.tote.nfc.NfcWriteSession
import com.tote.nfc.WriteState
import com.tote.nfc.hasNfc
import com.tote.ui.components.HazardRule
import com.tote.ui.components.DateField
import com.tote.ui.components.ItemRow
import com.tote.ui.components.ItemThumbnail
import com.tote.ui.components.PickerDialog
import com.tote.ui.components.PickerField
import com.tote.ui.components.PickerOption
import com.tote.ui.components.asPickerOptions
import com.tote.ui.components.ToteButton
import com.tote.ui.items.ItemSheet
import com.tote.ui.items.ItemSheetViewModel
import com.tote.ui.theme.ToteTheme
import com.tote.util.UiState
import design.pulse.ui.components.Caption
import design.pulse.ui.components.EmptyState
import design.pulse.ui.components.ErrorState
import design.pulse.ui.components.HeroPanel
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.SectionHeader

@Composable
fun ToteDetailScreen(
    onGone: () -> Unit = {},
    viewModel: ToteDetailViewModel = hiltViewModel(),
    itemSheet: ItemSheetViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val writeState by viewModel.write.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var confirmingUnpack by remember { mutableStateOf(false) }
    var lending by remember { mutableStateOf<String?>(null) }
    var showEdit by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var addingBag by remember { mutableStateOf(false) }
    var editingBag by remember { mutableStateOf<ContainerDto?>(null) }
    val people by viewModel.people.collectAsStateWithLifecycle()
    val locations by viewModel.locations.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val bins by viewModel.bins.collectAsStateWithLifecycle()
    var movingSelection by remember { mutableStateOf(false) }
    var baggingSelection by remember { mutableStateOf(false) }
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val cardIntent by viewModel.cardIntent.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // The PDF is handed to the system rather than rendered in-app: printing is the whole point,
    // and every phone already has a print/share sheet for a PDF. It arrives as a content:// URI
    // from the app's own authenticated download — handing the raw URL to a browser (as this
    // did) could only ever produce a 401, because the endpoint needs a bearer token.
    LaunchedEffect(cardIntent) {
        cardIntent?.let { intent ->
            runCatching { context.startActivity(intent) }
            viewModel.cardIntentConsumed()
        }
    }

    // Reader mode is live only while the write sheet is open, so holding the phone to a tag at
    // any other time still does the normal thing (open the tote).
    NfcWriteSession(
        enabled = writeState is WriteState.Waiting,
        onTag = viewModel::onTagPresented,
    )

    when (val s = state) {
        is UiState.Success -> {
            ToteDetailContent(
                tote = s.data,
                onAddItem = {
                    viewModel.loadCategories()
                    showAdd = true
                },
                onUnpackAll = { confirmingUnpack = true },
                onRepackAll = viewModel::repackAll,
                onTakeOut = viewModel::moveOut,
                onPutBack = viewModel::putBack,
                onWriteTag = viewModel::beginWrite,
                hasNfc = hasNfc(context),
                onPrintCard = { viewModel.printCard(s.data.code) },
                tagMismatch = viewModel.tagMismatch,
                onOpenItem = itemSheet::open,
                onEditBin = {
                    viewModel.loadLocations()
                    showEdit = true
                },
                onAddBag = { addingBag = true },
                selection = selection,
                onBeginSelecting = { viewModel.beginSelecting(it.takeIf { id -> id.isNotEmpty() }) },
                onToggleSelected = viewModel::toggleSelected,
                onSelectAll = viewModel::selectAll,
                onCancelSelecting = viewModel::cancelSelecting,
                onMoveSelected = { movingSelection = true },
                onBagSelected = { baggingSelection = true },
                onUnpackSelected = viewModel::unpackSelected,
                onPutBackSelected = viewModel::putBackSelected,
                onEditBag = { editingBag = it },
            )
            if (showEdit) {
                EditToteDialog(
                    tote = s.data,
                    locations = locations,
                    onDismiss = { showEdit = false },
                    onSave = { code, label, locationId, notes ->
                        viewModel.editTote(code, label, locationId, notes)
                        showEdit = false
                    },
                    onArchive = {
                        showEdit = false
                        viewModel.setArchived(!s.data.archived)
                    },
                    onDelete = {
                        showEdit = false
                        confirmingDelete = true
                    },
                    onNewLocation = viewModel::createLocation,
                )
            }
            if (movingSelection) {
                PickerDialog(
                    title = "Move to",
                    options = bins.filter { it.id != s.data.id }.map { t ->
                        PickerOption(id = t.id, label = t.code, detail = t.label ?: t.locationName)
                    },
                    selectedId = null,
                    onPick = { id ->
                        id?.let { viewModel.moveSelected(it) }
                        movingSelection = false
                    },
                    onDismiss = { movingSelection = false },
                    emptyMessage = "No other bins yet.",
                    searchHint = "Search bins",
                )
            }
            if (baggingSelection) {
                PickerDialog(
                    title = "Put in",
                    options = s.data.containers.map {
                        PickerOption(id = it.id, label = it.name, detail = it.notes)
                    },
                    selectedId = null,
                    onPick = {
                        viewModel.bagSelected(it)
                        baggingSelection = false
                    },
                    onDismiss = { baggingSelection = false },
                    noneLabel = "Loose in the bin",
                    emptyMessage = "This bin has no bags yet.",
                )
            }
            if (addingBag) {
                BagDialog(
                    bag = null,
                    onDismiss = { addingBag = false },
                    onSave = { name, notes ->
                        viewModel.addContainer(name, notes)
                        addingBag = false
                    },
                    onDelete = null,
                )
            }
            editingBag?.let { bag ->
                BagDialog(
                    bag = bag,
                    onDismiss = { editingBag = null },
                    onSave = { name, notes ->
                        viewModel.editContainer(bag.id, name, notes)
                        editingBag = null
                    },
                    onDelete = {
                        viewModel.deleteContainer(bag.id)
                        editingBag = null
                    },
                )
            }
            if (confirmingDelete) {
                DeleteToteDialog(
                    tote = s.data,
                    onDismiss = { confirmingDelete = false },
                    onConfirm = {
                        confirmingDelete = false
                        viewModel.deleteTote(onGone)
                    },
                )
            }
            // No `onOpenBin`: the bin is the screen behind the sheet.
            ItemSheet(
                viewModel = itemSheet,
                onChanged = viewModel::load,
                onLend = { item ->
                    viewModel.loadPeople()
                    itemSheet.close()
                    lending = item.id
                },
            )
            lending?.let { itemId ->
                LendDialog(
                    people = people,
                    onDismiss = { lending = null },
                    onLend = { personId, due ->
                        viewModel.lend(itemId, personId, due)
                        lending = null
                    },
                )
            }
            if (confirmingUnpack) {
                // One tap used to move every item in the bin with no question asked. Unpack is
                // recoverable (Repack all inverts it) but it rewrites the ledger N times — worth
                // one deliberate tap on a 37-item bin, cheap on a 2-item one.
                AlertDialog(
                    onDismissRequest = { confirmingUnpack = false },
                    title = { Text("Unpack all ${s.data.itemCount} item${if (s.data.itemCount == 1) "" else "s"}?") },
                    text = {
                        Text(
                            "Everything in this bin is marked out, one ledger entry each. " +
                                "Repack all puts them back.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                viewModel.unpackAll()
                                confirmingUnpack = false
                            },
                        ) { Text("Unpack") }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmingUnpack = false }) { Text("Cancel") }
                    },
                )
            }
            if (writeState !is WriteState.Idle) {
                WriteTagDialog(state = writeState, onDismiss = viewModel::cancelWrite)
            }
            if (showAdd) {
                AddItemDialog(
                    categories = categories,
                    onDismiss = { showAdd = false },
                    onAdd = { name, description, categoryId, qty ->
                        viewModel.addItem(name, description, categoryId, qty)
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
    onOpenItem: (ItemDto) -> Unit = {},
    modifier: Modifier = Modifier,
    onWriteTag: () -> Unit = {},
    hasNfc: Boolean = true,
    onPrintCard: () -> Unit = {},
    tagMismatch: Boolean = false,
    onEditBin: () -> Unit = {},
    onAddBag: () -> Unit = {},
    onEditBag: (ContainerDto) -> Unit = {},
    /**
     * Which rows are ticked, or **null when not selecting at all**.
     *
     * Null rather than an empty set plus a boolean, so the screen cannot be in selection mode
     * and disagree with itself about it. An empty set means selecting-with-nothing-picked, which
     * is a real and different state: the bar is up, the actions are disabled.
     */
    selection: Set<String>? = null,
    onBeginSelecting: (String) -> Unit = {},
    onToggleSelected: (String) -> Unit = {},
    onSelectAll: (List<String>) -> Unit = {},
    onCancelSelecting: () -> Unit = {},
    onMoveSelected: () -> Unit = {},
    onBagSelected: () -> Unit = {},
    onUnpackSelected: () -> Unit = {},
    onPutBackSelected: () -> Unit = {},
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
                        // The place, on the hero, because "A14" is half an answer: this screen is
                        // read while deciding whether to climb a ladder.
                        listOfNotNull(tote.label ?: "Unlabelled", tote.locationName)
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.82f),
                    )
                    if (tote.archived) {
                        Spacer(Modifier.height(spacing.xs))
                        Text(
                            "Archived — off the daily list, still in the catalogue",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    }
                    Spacer(Modifier.height(spacing.md))
                    HazardRule()
                }
            }

            // Two rows, split by what they act on: the bin itself, then everything in it. One
            // row could not hold four buttons — "Unpack all" wrapped to two lines the moment
            // "Edit bin" joined it, and a half-unpacked January bin needs all four.
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    ToteButton(text = "Add item", onClick = onAddItem, modifier = Modifier.weight(1f))
                    // Nothing about a bin was editable after creation: no label, no notes, and —
                    // the one that matters — no way to say where it physically is.
                    ToteButton(
                        text = "Edit bin",
                        onClick = onEditBin,
                        tonal = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // Both operations whenever each has something to act on. The old either/or hid
            // Repack on any bin with items still inside — so a half-unpacked Christmas bin
            // (3 in, 2 out: the normal January state) could only be repacked one row at a time.
            if (tote.itemCount > 0 || tote.outCount > 0) {
                item {
                    // FlowRow and no weights, because this row can now hold three buttons and
                    // an even three-way split wraps "Unpack all" INSIDE its own button — the
                    // same defect the selection bar was rebuilt to fix. Buttons take the width
                    // of their words and wrap as whole buttons.
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        if (tote.itemCount > 0) {
                            ToteButton(text = "Unpack all", onClick = onUnpackAll, tonal = true)
                        }
                        if (tote.outCount > 0) {
                            ToteButton(text = "Repack all", onClick = onRepackAll, tonal = true)
                        }
                        // Beside the two bulk verbs rather than in the "In this tote" header,
                        // because a selection now spans both lists — and because that header is
                        // not drawn at all once the bin is empty, which is precisely when
                        // picking several things to put back is what you came to do.
                        if (selection == null && tote.items.size + tote.itemsOut.size > 1) {
                            ToteButton(
                                text = "Select",
                                onClick = { onBeginSelecting("") },
                                tonal = true,
                            )
                        }
                    }
                }
            }

            if (tagMismatch) {
                item {
                    // The highest-consequence signal in the app: the server compares the tapped
                    // tag's hardware UID against the one recorded for this bin, and it has always
                    // computed this while the client threw it away — so a label stuck on the
                    // wrong box opened the wrong contents with total confidence, in an attic,
                    // where the whole point is not having to open the box.
                    PanelCard(channel = colors.attention.base) {
                        Text(
                            "This isn't the tag recorded for this bin",
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.attention.base,
                        )
                        Spacer(Modifier.height(spacing.xs))
                        Caption(
                            text = "The label may be on the wrong box, or this tag was rewritten " +
                                "for another bin. Check the code on the card before you trust " +
                                "what's below.",
                        )
                    }
                }
            }

            item {
                TagAndCardRow(
                    hasTag = tote.nfcTagUid != null,
                    writtenAt = tote.nfcWrittenAt,
                    cardPrinted = tote.cardPrintedAt != null,
                    hasNfc = hasNfc,
                    onWriteTag = onWriteTag,
                    onPrintCard = onPrintCard,
                )
            }

            selection?.let { picked ->
                item(key = "selection-bar") {
                    val pickedOut = tote.itemsOut.count { it.id in picked }
                    val pickedIn = tote.items.count { it.id in picked }
                    SelectionBar(
                        count = picked.size,
                        // A bag belongs to this bin, so it can only label things that are IN it.
                        canBag = tote.containers.isNotEmpty() && pickedOut == 0,
                        // The two directions are mutually exclusive verbs: something already out
                        // cannot be taken out, and something in the bin cannot be put back.
                        canTakeOut = pickedIn > 0 && pickedOut == 0,
                        canPutBack = pickedOut > 0 && pickedIn == 0,
                        onPutBack = onPutBackSelected,
                        onSelectAll = {
                            onSelectAll((tote.items + tote.itemsOut).map { it.id })
                        },
                        onCancel = onCancelSelecting,
                        onMove = onMoveSelected,
                        onBag = onBagSelected,
                        onUnpack = onUnpackSelected,
                    )
                }
            }

            // The whole "In this tote" block is skipped when the bin is empty AND things are out
            // of it: a header, an "Everything is out" card and a count, all saying what the
            // section immediately below shows in full. Three pieces of chrome between the person
            // and the rows they opened the screen to read.
            val showInSection = tote.items.isNotEmpty() || tote.itemsOut.isEmpty()
            if (showInSection) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionHeader(label = "In this tote", channel = colors.stored.base)
                    // Only offered once there is something to group. A bin with two things in it
                    // does not need subdividing, and the button would be clutter arguing it does.
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        if (tote.items.isNotEmpty() || tote.containers.isNotEmpty()) {
                            ToteButton(
                                text = "Add bag",
                                onClick = onAddBag,
                                tonal = true,
                                compact = true,
                            )
                        }
                    }
                }
            }

            if (tote.items.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Filled.Inventory2,
                        title = "Empty",
                        subtitle = "Add the first item to start cataloguing this bin.",
                    )
                }
            }
            }

            // Grouped by bag, loose things last. A real bin of baby clothes is three zip bags
            // and a blanket, and a flat list of forty garments is the shape that makes someone
            // tip the whole bin out on the floor to find one.
            tote.containers.forEach { bag ->
                val inBag = tote.items.filter { it.containerId == bag.id }
                item(key = "bag-${bag.id}") {
                    BagHeader(bag = bag, count = inBag.size, onEdit = { onEditBag(bag) })
                }
                items(inBag, key = { it.id }) { item ->
                    ItemRow(
                        item,
                        actionLabel = "Take out",
                        onAction = { onTakeOut(item.id) },
                        onOpen = { onOpenItem(item) },
                        selected = selection?.contains(item.id),
                        onToggle = { onToggleSelected(item.id) },
                        onLongPress = { onBeginSelecting(item.id) },
                    )
                }
            }

            val loose = tote.items.filter { it.containerId == null }
            if (loose.isNotEmpty()) {
                if (tote.containers.isNotEmpty()) {
                    item(key = "loose-head") {
                        // Only when there is something to contrast with. "Loose in the bin" over
                        // a bin with no bags describes nothing.
                        SectionHeader(label = "Loose in the bin", channel = colors.stored.base)
                    }
                }
                items(loose, key = { it.id }) { item ->
                    ItemRow(
                        item,
                        actionLabel = "Take out",
                        onAction = { onTakeOut(item.id) },
                        onOpen = { onOpenItem(item) },
                        selected = selection?.contains(item.id),
                        onToggle = { onToggleSelected(item.id) },
                        onLongPress = { onBeginSelecting(item.id) },
                    )
                }
            }

            // The gap, shown rather than hidden. This section is the answer to "I thought the
            // lights were in here" — the single most common reason to stop trusting a catalog.
            if (tote.itemsOut.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(spacing.sm))
                    SectionHeader(label = "Out of this tote", channel = colors.attention.base)
                }
                // Grouped by size, and only when there is more than one size to tell apart —
                // the same rule as "Loose in the bin", which appears only when there are bags to
                // contrast with. An unpacked bin of clothes is thirty rows whose useful question
                // is "which of these are the 12m ones", and a flat run ordered by name buries
                // that. `sizeOrdinal` is the SERVER's index, displayed: the ladder has one
                // implementation and it is not here.
                outBySize(tote.itemsOut).forEach { (size, group) ->
                    if (size != null) {
                        item(key = "out-size-$size") {
                            SectionHeader(label = size, channel = colors.provenance.base)
                        }
                    }
                    items(group, key = { "out-${it.id}" }) { item ->
                    ItemRow(
                        item,
                        actionLabel = "Put back",
                        onAction = { onPutBack(item.id) },
                        onOpen = { onOpenItem(item) },
                        showSize = size == null,
                        suppressRoutineStatus = true,
                        // These rows had no selection wired at all, which only bit once a bin was
                        // fully unpacked — the state this section exists to describe. With
                        // everything out there was no way to put SOME of it back: one row at a
                        // time, or Repack all. Partial repack is the actual January workflow.
                        selected = selection?.contains(item.id),
                        onToggle = { onToggleSelected(item.id) },
                        onLongPress = { onBeginSelecting(item.id) },
                    )
                    }
                }
            }
        }
    }
}

/**
 * The out list, grouped by the words on the tag.
 *
 * Ordered by the server's `size_ordinal` so 6m precedes 12m precedes 18m — an alphabetical sort
 * puts 12m first, which is exactly the confusion the ladder exists to remove. Anything unsized
 * goes last under its own heading rather than being scattered through, and a list with fewer than
 * two distinct sizes is left flat because a heading that never contrasts with anything is noise.
 */
internal fun outBySize(itemsOut: List<ItemDto>): List<Pair<String?, List<ItemDto>>> {
    if (itemsOut.mapNotNull { it.apparel?.sizeRaw }.distinct().size < 2) {
        return listOf(null to itemsOut)
    }
    return itemsOut
        .groupBy { it.apparel?.sizeRaw }
        .entries
        .sortedWith(
            compareBy(
                { entry -> entry.key == null },
                { entry -> entry.value.firstNotNullOfOrNull { it.apparel?.sizeOrdinal } ?: 0f },
                { entry -> entry.key ?: "" },
            )
        )
        .map { entry -> entry.key to entry.value.sortedBy { it.name } }
}

/**
 * Whether this bin has been physically labelled yet.
 *
 * Surfaced rather than buried in a menu because an unlabelled bin is the failure the whole app
 * is built to prevent: a catalogued tote with no tag and no card is a bin you can only find by
 * opening it. Both are shown, and both are offered, because they are redundant on purpose — a
 * tag can die under packing tape, and the card's QR still works.
 */
@Composable
private fun TagAndCardRow(
    hasTag: Boolean,
    writtenAt: String?,
    cardPrinted: Boolean,
    hasNfc: Boolean,
    onWriteTag: () -> Unit,
    onPrintCard: () -> Unit,
) {
    val colors = ToteTheme.colors
    val spacing = ToteTheme.spacing

    // Settled bins get one line, not a panel.
    //
    // "Tagged and labelled" with two full-width buttons under it is a card the size of three
    // item rows, sitting permanently between the hero and the contents — and it is finished
    // work. Rewriting a tag is rare and printing a second card rarer. The loud version stays
    // for every unfinished state, where it is an attention signal rather than furniture.
    if (hasTag && cardPrinted) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                Caption(
                    text = if (writtenAt != null) {
                        "Tagged and labelled · ${writtenAt.take(10)}"
                    } else {
                        "Tagged and labelled"
                    },
                )
            }
            ToteButton(
                text = "Rewrite",
                onClick = onWriteTag,
                tonal = true,
                compact = true,
                enabled = hasNfc,
            )
            Spacer(Modifier.width(spacing.sm))
            ToteButton(text = "Card", onClick = onPrintCard, tonal = true, compact = true)
        }
        return
    }

    PanelCard(channel = if (!hasTag && !cardPrinted) colors.attention.base else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        hasTag && cardPrinted -> "Tagged and labelled"
                        hasTag -> "Tagged, no card printed"
                        cardPrinted -> "Card printed, no tag"
                        else -> "Not labelled yet"
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(spacing.xs))
                Caption(
                    text = when {
                        // Said, rather than left to a button that silently does nothing. The
                        // write button used to be drawn and enabled on every phone, with the
                        // NFC check hidden in the click handler — tapping it on a phone without
                        // NFC was indistinguishable from a frozen app.
                        !hasNfc -> "This phone has no NFC — print a card instead"
                        // Dated, because a tag written before the bin was renamed carries the old
                        // text — and the date is the only way to know that from the app.
                        hasTag && writtenAt != null -> "Tag written ${writtenAt.take(10)}"
                        hasTag || cardPrinted -> "Tap the tag or scan the card to open this bin"
                        else -> "Write a tag or print a card so this bin can be found"
                    },
                )
            }
            Column {
                ToteButton(
                    text = if (hasTag) "Rewrite" else "Write tag",
                    onClick = onWriteTag,
                    tonal = true,
                    enabled = hasNfc,
                )
                Spacer(Modifier.height(spacing.xs))
                ToteButton(text = "Print card", onClick = onPrintCard, tonal = true)
            }
        }
    }
}


/**
 * A bag's heading inside a bin.
 *
 * The notes line is the reason a bag is worth modelling at all: a bag is often only
 * *approximately* catalogued — "mostly 3-6M onesies, some vests" — and knowing that is the
 * difference between reaching for the right one and opening all three.
 */

/**
 * What you can do to a ticked selection.
 *
 * Three verbs, and they are the three that are painful one at a time after a batch: move them all
 * to another bin, drop them all in a bag, take them all out. Everything else stays per-item —
 * a bulk delete is not offered at all, because the one destructive action in this app removes
 * photographs that cannot be retaken and doing that to twenty rows behind one tap is a mis-tap
 * with no undo.
 *
 * Disabled rather than hidden at zero: a bar that appears and disappears as you tick things is a
 * layout that jumps under your thumb.
 *
 * **The verbs shown are the ones that fit what is ticked.** A selection can now span both lists,
 * and the two directions are mutually exclusive: something already out cannot be taken out,
 * something in the bin cannot be put back. Rather than showing both and letting one fail against
 * the server, the bar shows what applies — and for a selection spanning both, only **Move**, the
 * one verb well defined for every item in it (the server matches the reason per item).
 */
@Composable
private fun SelectionBar(
    count: Int,
    canBag: Boolean,
    canTakeOut: Boolean,
    canPutBack: Boolean,
    onSelectAll: () -> Unit,
    onCancel: () -> Unit,
    onMove: () -> Unit,
    onBag: () -> Unit,
    onUnpack: () -> Unit,
    onPutBack: () -> Unit,
) {
    val colors = ToteTheme.colors
    val spacing = ToteTheme.spacing

    PanelCard(channel = colors.slate.base) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (count == 0) "Nothing ticked" else "$count selected",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.slate.base,
                    modifier = Modifier.weight(1f),
                )
                ToteButton(text = "All", onClick = onSelectAll, tonal = true, compact = true)
                Spacer(Modifier.width(spacing.sm))
                ToteButton(text = "Done", onClick = onCancel, tonal = true, compact = true)
            }
            Spacer(Modifier.height(spacing.sm))
            // FlowRow, because three verbs plus a bag button do not fit one line on a phone and
            // "Take out" wrapped inside its own button rather than onto the next row.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                ToteButton(text = "Move…", onClick = onMove, tonal = true, enabled = count > 0)
                if (canBag) {
                    ToteButton(text = "Bag…", onClick = onBag, tonal = true, enabled = count > 0)
                }
                if (canTakeOut) {
                    ToteButton(text = "Take out", onClick = onUnpack, tonal = true)
                }
                if (canPutBack) {
                    ToteButton(text = "Put back", onClick = onPutBack, tonal = true)
                }
            }
            if (count > 0 && !canTakeOut && !canPutBack) {
                Spacer(Modifier.height(spacing.sm))
                // Said out loud rather than left as two missing buttons, which reads as a bug.
                Text(
                    "Some of these are in the bin and some are out, so only moving them fits.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BagHeader(bag: ContainerDto, count: Int, onEdit: () -> Unit) {
    val colors = ToteTheme.colors
    val spacing = ToteTheme.spacing

    PanelCard(onClick = onEdit, channel = colors.provenance.base) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    bag.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.provenance.base,
                )
                Spacer(Modifier.height(spacing.xs))
                Caption(
                    text = listOfNotNull(
                        "$count item${if (count == 1) "" else "s"} catalogued",
                        bag.notes,
                    ).joinToString(" · "),
                )
            }
        }
    }
}

/**
 * The write sheet.
 *
 * It stays open after success rather than dismissing itself, because the useful next action is
 * "stick it on the bin and check the tap works" — and auto-dismissing would hide the one line
 * that says whether the summary had to be dropped to fit a small tag.
 */
@Composable
private fun WriteTagDialog(state: WriteState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (state) {
                    is WriteState.Waiting -> "Hold the tag to the phone"
                    is WriteState.Done -> "Tag written"
                    is WriteState.Problem -> "Couldn't write the tag"
                    WriteState.Idle -> ""
                }
            )
        },
        text = {
            Column {
                when (state) {
                    is WriteState.Waiting ->
                        Caption(text = "Keep it still until this says it is done.")
                    is WriteState.Done -> {
                        Caption(text = "Stick it on the bin and tap it to check.")
                        if (state.truncatedSummary) {
                            Spacer(Modifier.height(ToteTheme.spacing.sm))
                            // The tag still works; what is lost is the human summary a stock
                            // reader would show on a phone without Tote. Worth saying out loud.
                            Caption(
                                text = "The tag was too small for the summary, so only the " +
                                    "link was written. It still opens the tote.",
                            )
                        }
                    }
                    is WriteState.Problem -> Text(
                        state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    WriteState.Idle -> Unit
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/**
 * Add something by hand.
 *
 * It used to collect a name and a quantity, full stop — so anything typed in here was
 * permanently uncategorised, and a hand-added item was a poorer record than a photographed one
 * for no reason anybody had chosen. Category and description are here now; everything else
 * (condition, the clothing block) is a tap away in the item sheet, where a filed item is edited.
 */
@Composable
private fun AddItemDialog(
    categories: List<CategoryDto>,
    onDismiss: () -> Unit,
    onAdd: (String, String?, String?, Int) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var categoryId by remember { mutableStateOf<String?>(null) }
    var qty by remember { mutableStateOf("1") }
    var showCategoryPicker by remember { mutableStateOf(false) }

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
                Spacer(Modifier.height(ToteTheme.spacing.sm))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    placeholder = { Text("Green, pre-lit, in the original box") },
                    singleLine = true,
                )
                Spacer(Modifier.height(ToteTheme.spacing.sm))
                PickerField(
                    label = "Category",
                    selected = categories.firstOrNull { it.id == categoryId }?.name,
                    placeholder = "No category",
                    onClick = { showCategoryPicker = true },
                )
                Spacer(Modifier.height(ToteTheme.spacing.sm))
                OutlinedTextField(
                    value = qty,
                    onValueChange = { qty = it.filter(Char::isDigit).take(3) },
                    label = { Text("Quantity") },
                    singleLine = true,
                    // A number field showed a full QWERTY keyboard and silently dropped every
                    // non-digit as you typed — which reads as the keyboard being broken.
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Spacer(Modifier.height(ToteTheme.spacing.sm))
                // Quantity exists so "4× ornament box" is one row. Said here because the
                // alternative — four identical rows — is what people do without prompting.
                Caption(text = "Four identical boxes? One row, quantity 4.")
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onAdd(
                        name,
                        description.trim().takeIf { it.isNotEmpty() },
                        categoryId,
                        qty.toIntOrNull() ?: 1,
                    )
                },
                enabled = name.isNotBlank(),
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (showCategoryPicker) {
        PickerDialog(
            title = "Category",
            options = categories.asPickerOptions(),
            selectedId = categoryId,
            onPick = {
                categoryId = it
                showCategoryPicker = false
            },
            onDismiss = { showCategoryPicker = false },
            noneLabel = "No category",
            emptyMessage = "No categories yet.",
        )
    }
}


/**
 * Lend an item to someone, optionally by a date.
 *
 * The date is optional on purpose. Plenty of lending genuinely happens without one, and a
 * required field here would either be lied to or would manufacture an overdue nudge nobody
 * agreed to — which is how a notification channel gets muted and stops working for the loans
 * that did have a date.
 */
@Composable
private fun LendDialog(
    people: List<PersonDto>,
    onDismiss: () -> Unit,
    onLend: (String, String?) -> Unit,
) {
    var personId by remember { mutableStateOf<String?>(null) }
    var due by remember { mutableStateOf("") }
    var showPeoplePicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lend it out") },
        text = {
            Column {
                if (people.isEmpty()) {
                    Text(
                        "Nobody to lend to yet — add someone on the People tab first.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    PickerField(
                        label = "Lending to",
                        selected = people.firstOrNull { it.id == personId }?.name,
                        placeholder = "Choose a person",
                        onClick = { showPeoplePicker = true },
                    )
                    Spacer(Modifier.height(ToteTheme.spacing.md))
                    DateField(
                        value = due,
                        onValueChange = { due = it },
                        label = "Back by (optional)",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(ToteTheme.spacing.sm))
                    Caption(text = "With a date, it nags on the day after. Without one, it just remembers who has it.")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { personId?.let { onLend(it, due.takeIf { d -> d.isNotBlank() }) } },
                enabled = personId != null,
            ) { Text("Lend") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    // The picker itself, which was never rendered: the field set `showPeoplePicker` and nothing
    // read it, so tapping "Lending to" did nothing at all and the Lend button — enabled only
    // once a person is chosen — could never become enabled. Lending was unreachable from the
    // day the chip strip was replaced.
    if (showPeoplePicker) {
        PickerDialog(
            title = "Lending to",
            options = people.map { PickerOption(id = it.id, label = it.name) },
            selectedId = personId,
            onPick = {
                personId = it
                showPeoplePicker = false
            },
            onDismiss = { showPeoplePicker = false },
            emptyMessage = "Nobody on the People tab yet.",
        )
    }
}



/**
 * Edit the bin itself — and the one screen in the app that carries a warning about the physical
 * world.
 *
 * Changing the **code** is not like changing a label. The code is written on an index card in
 * permanent marker, encoded in the QR on that card, and written into the NFC tag's URI as
 * `/t/A14`. The server resolves that path by code, so renaming the bin does not update the tag —
 * it makes the tag stop resolving, and a tap in an attic lands on "no such bin" over a box that
 * is sitting right there. So it is said before the edit, not after.
 */
@Composable
private fun EditToteDialog(
    tote: ToteDetailDto,
    locations: List<LocationDto>,
    onDismiss: () -> Unit,
    onSave: (String, String?, String?, String?) -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onNewLocation: (String, (String) -> Unit) -> Unit,
) {
    var code by remember { mutableStateOf(tote.code) }
    var label by remember { mutableStateOf(tote.label.orEmpty()) }
    var notes by remember { mutableStateOf(tote.notes.orEmpty()) }
    var locationId by remember { mutableStateOf(tote.locationId) }
    var showLocationPicker by remember { mutableStateOf(false) }
    var newLocationName by remember { mutableStateOf<String?>(null) }
    val spacing = ToteTheme.spacing

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit bin") },
        text = {
            Column {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Code") },
                    singleLine = true,
                )
                if (code.trim() != tote.code && tote.nfcTagUid != null) {
                    Spacer(Modifier.height(spacing.xs))
                    Text(
                        "The tag on this bin still says ${tote.code} and will stop opening it. " +
                            "Rewrite the tag and reprint the card after saving.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ToteTheme.colors.attention.base,
                    )
                }
                Spacer(Modifier.height(spacing.md))
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    placeholder = { Text("Christmas decor") },
                    singleLine = true,
                )
                Spacer(Modifier.height(spacing.md))
                PickerField(
                    label = "Where it lives",
                    selected = locations.firstOrNull { it.id == locationId }?.name
                        ?: tote.locationName,
                    placeholder = "No location yet",
                    onClick = { showLocationPicker = true },
                )
                Spacer(Modifier.height(spacing.md))
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes") },
                    placeholder = { Text("Second shelf, behind the tree box") },
                )
                Spacer(Modifier.height(spacing.md))
                // The reversible half of the pair, offered first and deliberately louder than
                // delete: a bin you have stopped using is almost never a bin you want erased.
                ToteButton(
                    text = if (tote.archived) "Put it back on the list" else "Archive this bin",
                    onClick = onArchive,
                    tonal = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(spacing.sm))
                ToteButton(
                    text = "Delete this bin",
                    onClick = onDelete,
                    tonal = true,
                    channel = MaterialTheme.colorScheme.error,
                    dimChannel = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(code, label, locationId, notes) },
                enabled = code.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (showLocationPicker) {
        LocationPicker(
            locations = locations,
            selectedId = locationId,
            onPick = {
                locationId = it
                showLocationPicker = false
            },
            onNew = {
                showLocationPicker = false
                newLocationName = ""
            },
            onDismiss = { showLocationPicker = false },
        )
    }
    newLocationName?.let { typed ->
        AlertDialog(
            onDismissRequest = { newLocationName = null },
            title = { Text("New location") },
            text = {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { newLocationName = it },
                    label = { Text("Name") },
                    placeholder = { Text("Basement closet") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onNewLocation(typed) { created -> locationId = created }
                        newLocationName = null
                    },
                    enabled = typed.isNotBlank(),
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { newLocationName = null }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Deleting a bin, and saying what that actually does.
 *
 * "Delete the bin" reads as "delete everything in it" to anyone who has not read the schema. It
 * does not: `ON DELETE SET NULL` leaves every item catalogued and in no bin, because throwing a
 * box away must never erase the record of what was in it. Archiving is offered alongside, because
 * it is almost always the operation actually wanted.
 */
@Composable
private fun DeleteToteDialog(
    tote: ToteDetailDto,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete ${tote.code}?") },
        text = {
            Column {
                Text(
                    if (tote.itemCount > 0) {
                        "Its ${tote.itemCount} item${if (tote.itemCount == 1) "" else "s"} stay " +
                            "in the catalogue, in no bin — you would file them again one by one."
                    } else {
                        "The bin goes. It holds nothing, so nothing else changes."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(ToteTheme.spacing.sm))
                Caption(text = "Still own the box? Archive it instead — it comes back whenever you want.")
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep it") } },
    )
}


/**
 * Name a bag, and say roughly what is in it.
 *
 * Removing one needs no confirmation, unlike almost every other delete in this app: the server
 * clears `items.container_id` rather than cascading, so nothing is destroyed but the label and
 * everything that was in the bag stays exactly where it is. The copy says so, because "delete"
 * reads as "delete the contents" to everyone who has not read the schema.
 */
@Composable
private fun BagDialog(
    bag: ContainerDto?,
    onDismiss: () -> Unit,
    onSave: (String, String?) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(bag?.name.orEmpty()) }
    var notes by remember { mutableStateOf(bag?.notes.orEmpty()) }
    val spacing = ToteTheme.spacing

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (bag == null) "Add a bag" else "Edit bag") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("What is it") },
                    placeholder = { Text("3-6M onesies") },
                    singleLine = true,
                )
                Spacer(Modifier.height(spacing.md))
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Roughly what's inside") },
                    placeholder = { Text("mostly onesies, some vests") },
                )
                Spacer(Modifier.height(spacing.sm))
                // The whole point of the notes field: a bag you never itemise is still worth
                // describing, and this is what you read instead of opening it.
                Caption(text = "Worth filling in even if you never catalogue the bag's contents.")
                if (onDelete != null) {
                    Spacer(Modifier.height(spacing.md))
                    ToteButton(
                        text = "Remove this bag",
                        onClick = onDelete,
                        tonal = true,
                        channel = MaterialTheme.colorScheme.error,
                        dimChannel = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(spacing.xs))
                    Caption(text = "Everything in it stays in this bin, just loose.")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, notes.takeIf { it.isNotBlank() }) },
                enabled = name.isNotBlank(),
            ) { Text("Save") }
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
                nfcTagUid = "04A2B3C4D5E6",
            ),
            onAddItem = {}, onUnpackAll = {}, onRepackAll = {}, onTakeOut = {}, onPutBack = {},
        )
    }
}
