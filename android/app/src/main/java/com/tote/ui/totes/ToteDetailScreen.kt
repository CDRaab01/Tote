package com.tote.ui.totes

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
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
import com.tote.ui.components.PickerDialog
import com.tote.ui.components.PickerField
import com.tote.ui.components.PickerOption
import com.tote.ui.components.RefreshOnResume
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
    /** Open the verify pass for this bin, by id — wired to the route by the nav host. */
    onVerify: (String) -> Unit = {},
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

    // Re-read on the way back, the same as the person screen. A verify pass happens on its own
    // screen and can move several items out of this bin at once, so returning to a snapshot
    // taken before it would show, in detail, a bin that no longer exists — and this screen is
    // read live for exactly that reason.
    RefreshOnResume(viewModel::load)

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
                onVerify = { onVerify(s.data.id) },
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
    /** Start a verify pass — the second line under the hero, beside the labelling one. */
    onVerify: () -> Unit = {},
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
        Box(Modifier.fillMaxSize()) {
        // The screen leads with what is IN the bin. The old layout stacked two button rows, the
        // labelling panel and the selection bar between the hero and the first item, so on the
        // flagship path — tap the tag, read the contents — the contents started below the fold
        // behind management chrome that is used once per bin. The management verbs now live as
        // icons on the hero (edit, tag, card), the labelling state is one line, and the bulk
        // verbs are pinned at the bottom where the thumb is.
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = spacing.lg,
                end = spacing.lg,
                top = spacing.lg,
                // Room for the pinned bar, so the last cells scroll clear of it.
                bottom = 120.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            item {
                BinHero(
                    tote = tote,
                    hasNfc = hasNfc,
                    onEditBin = onEditBin,
                    onWriteTag = onWriteTag,
                    onPrintCard = onPrintCard,
                )
            }

            item {
                LabellingLine(
                    hasTag = tote.nfcTagUid != null,
                    writtenAt = tote.nfcWrittenAt,
                    cardPrinted = tote.cardPrintedAt != null,
                    hasNfc = hasNfc,
                    onWriteTag = onWriteTag,
                    onPrintCard = onPrintCard,
                )
            }

            item {
                VerifyLine(lastVerifiedAt = tote.lastVerifiedAt, onVerify = onVerify)
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
                        // Sentence case, not the caps caption: four lines of letterspaced
                        // uppercase made the most safety-critical paragraph in the app the least
                        // readable text on the screen.
                        Text(
                            "The label may be on the wrong box, or this tag was rewritten " +
                                "for another bin. Check the code on the card before you trust " +
                                "what's below.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Select lives in the header of whichever section is drawn first, so it is still
            // reachable on a fully unpacked bin (where "In this tote" is skipped entirely).
            val canSelect = selection == null && tote.items.size + tote.itemsOut.size > 1

            // The whole "In this tote" block is skipped when the bin is empty AND things are out
            // of it: a header, an "Everything is out" card and a count, all saying what the
            // section immediately below shows in full. Three pieces of chrome between the person
            // and the rows they opened the screen to read.
            val showInSection = tote.items.isNotEmpty() || tote.itemsOut.isEmpty()
            if (showInSection) {
                item {
                    SectionHeader(
                        label = "In this tote",
                        channel = colors.stored.base,
                        trailing = {
                            // Quiet text verbs, not tonal pills: the header's job is to label the
                            // grid, and two ochre buttons beside it outshouted every photograph.
                            // Add bag is only offered once there is something to group.
                            if (tote.items.isNotEmpty() || tote.containers.isNotEmpty()) {
                                TextButton(onClick = onAddBag) { Text("Add bag") }
                            }
                            if (canSelect) {
                                TextButton(onClick = { onBeginSelecting("") }) { Text("Select") }
                            }
                        },
                    )
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
                itemCellRows(
                    items = inBag,
                    keyPrefix = "in",
                    onOpenItem = onOpenItem,
                    actionLabel = "Take out",
                    onAction = onTakeOut,
                    selection = selection,
                    onToggleSelected = onToggleSelected,
                    onBeginSelecting = onBeginSelecting,
                )
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
                itemCellRows(
                    items = loose,
                    keyPrefix = "in",
                    onOpenItem = onOpenItem,
                    actionLabel = "Take out",
                    onAction = onTakeOut,
                    selection = selection,
                    onToggleSelected = onToggleSelected,
                    onBeginSelecting = onBeginSelecting,
                )
            }

            // The gap, shown rather than hidden. This section is the answer to "I thought the
            // lights were in here" — the single most common reason to stop trusting a catalog.
            if (tote.itemsOut.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(spacing.sm))
                    SectionHeader(
                        label = "Out of this tote",
                        channel = colors.attention.base,
                        trailing = {
                            if (!showInSection && canSelect) {
                                TextButton(onClick = { onBeginSelecting("") }) { Text("Select") }
                            }
                        },
                    )
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
                    itemCellRows(
                        items = group,
                        keyPrefix = "out",
                        onOpenItem = onOpenItem,
                        actionLabel = "Put back",
                        onAction = onPutBack,
                        out = true,
                        showSize = size == null,
                        suppressRoutineStatus = true,
                        // These cells had no selection wired at all, which only bit once a bin
                        // was fully unpacked — the state this section exists to describe. With
                        // everything out there was no way to put SOME of it back: one row at a
                        // time, or Repack all. Partial repack is the actual January workflow.
                        selection = selection,
                        onToggleSelected = onToggleSelected,
                        onBeginSelecting = onBeginSelecting,
                    )
                }
            }
        }

        // The pinned verbs: one primary, always in reach, with the bulk operations folded behind
        // it — and the selection bar takes the same slot while selecting, so the verbs for what
        // is ticked are under the thumb doing the ticking rather than scrolled away above.
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
                .padding(start = spacing.lg, end = spacing.lg, top = spacing.xl, bottom = spacing.lg),
        ) {
            if (selection != null) {
                val pickedOut = tote.itemsOut.count { it.id in selection }
                val pickedIn = tote.items.count { it.id in selection }
                SelectionBar(
                    count = selection.size,
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
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    ToteButton(
                        text = "Add item",
                        onClick = onAddItem,
                        modifier = Modifier.weight(1f),
                    )
                    // Both bulk operations whenever each has something to act on, one menu tap
                    // away — a half-unpacked Christmas bin (3 in, 2 out: the normal January
                    // state) offers both.
                    if (tote.itemCount > 0 || tote.outCount > 0) {
                        Box {
                            var bulkOpen by remember { mutableStateOf(false) }
                            OutlinedButton(
                                onClick = { bulkOpen = true },
                                modifier = Modifier.heightIn(min = 52.dp),
                            ) {
                                Text(if (tote.itemCount > 0) "Unpack…" else "Repack…")
                            }
                            DropdownMenu(
                                expanded = bulkOpen,
                                onDismissRequest = { bulkOpen = false },
                            ) {
                                if (tote.itemCount > 0) {
                                    DropdownMenuItem(
                                        text = { Text("Unpack all (${tote.itemCount})") },
                                        onClick = {
                                            bulkOpen = false
                                            onUnpackAll()
                                        },
                                    )
                                }
                                if (tote.outCount > 0) {
                                    DropdownMenuItem(
                                        text = { Text("Repack all (${tote.outCount})") },
                                        onClick = {
                                            bulkOpen = false
                                            onRepackAll()
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

/**
 * A section's items as photograph cells, two to a row.
 *
 * Chunked into full-width rows rather than a nested lazy grid, because the screen is one
 * LazyColumn with headers, bag panels and cells interleaved — and a grid that cannot host
 * arbitrary full-width rows would force every heading out of the scroll.
 */
private fun LazyListScope.itemCellRows(
    items: List<ItemDto>,
    keyPrefix: String,
    onOpenItem: (ItemDto) -> Unit,
    actionLabel: String,
    onAction: (String) -> Unit,
    selection: Set<String>?,
    onToggleSelected: (String) -> Unit,
    onBeginSelecting: (String) -> Unit,
    out: Boolean = false,
    showSize: Boolean = true,
    suppressRoutineStatus: Boolean = false,
) {
    items.chunked(2).forEach { pair ->
        item(key = "$keyPrefix-${pair.first().id}") {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ToteTheme.spacing.md),
            ) {
                pair.forEach { cellItem ->
                    ItemCell(
                        item = cellItem,
                        onOpen = { onOpenItem(cellItem) },
                        modifier = Modifier.weight(1f),
                        actionLabel = actionLabel,
                        onAction = { onAction(cellItem.id) },
                        out = out,
                        selected = selection?.contains(cellItem.id),
                        onToggle = { onToggleSelected(cellItem.id) },
                        onLongPress = { onBeginSelecting(cellItem.id) },
                        showSize = showSize,
                        suppressRoutineStatus = suppressRoutineStatus,
                    )
                }
                if (pair.size == 1) {
                    Spacer(Modifier.weight(1f))
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
 * The bin's identity, with its management verbs as icons.
 *
 * Edit, write-tag and print-card used to be five buttons and a panel between the hero and the
 * first item. They are setup-time work on a screen whose every open is read-time — an NFC tap
 * exists so the contents are one gesture away — so they ride the hero as icons and the contents
 * start directly below. The counts are on the hero because "A14 · Christmas decor" is half an
 * answer: this screen is read while deciding whether to climb a ladder.
 */
@Composable
private fun BinHero(
    tote: ToteDetailDto,
    hasNfc: Boolean,
    onEditBin: () -> Unit,
    onWriteTag: () -> Unit,
    onPrintCard: () -> Unit,
) {
    val spacing = ToteTheme.spacing

    HeroPanel(contentPadding = spacing.lg) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tote.code,
                style = ToteTheme.dataType.dataMedium,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onEditBin) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = "Edit bin",
                    tint = Color.White.copy(alpha = 0.8f),
                )
            }
            // Disabled with the reason said on the labelling line, not hidden: the write button
            // used to be drawn and enabled on every phone, with the NFC check buried in the
            // click handler — tapping it on a phone without NFC read as a frozen app.
            IconButton(onClick = onWriteTag, enabled = hasNfc) {
                Icon(
                    Icons.Filled.Nfc,
                    contentDescription = if (tote.nfcTagUid != null) "Rewrite tag" else "Write tag",
                    tint = Color.White.copy(alpha = if (hasNfc) 0.8f else 0.35f),
                )
            }
            IconButton(onClick = onPrintCard) {
                Icon(
                    Icons.Filled.Print,
                    contentDescription = "Print card",
                    tint = Color.White.copy(alpha = 0.8f),
                )
            }
        }
        Text(
            listOfNotNull(tote.label ?: "Unlabelled", tote.locationName).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.82f),
        )
        Spacer(Modifier.height(spacing.xs))
        Text(
            buildList {
                add("${tote.itemCount} item${if (tote.itemCount == 1) "" else "s"}")
                if (tote.outCount > 0) add("${tote.outCount} out")
            }.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.62f),
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

/**
 * Whether this bin has been physically labelled yet — one line, not a panel.
 *
 * Surfaced rather than buried in a menu because an unlabelled bin is the failure the whole app
 * is built to prevent: a catalogued tote with no tag and no card is a bin you can only find by
 * opening it. But the old panel with two full-width buttons was a card the size of three item
 * rows sitting permanently between the hero and the contents. One quiet sentence with one verb
 * carries the same fact; the rose dot marks only the fully unlabelled state, because a bin with
 * a tag OR a card can already be found and the attention channel must stay rare to stay loud.
 */
@Composable
private fun LabellingLine(
    hasTag: Boolean,
    writtenAt: String?,
    cardPrinted: Boolean,
    hasNfc: Boolean,
    onWriteTag: () -> Unit,
    onPrintCard: () -> Unit,
) {
    val colors = ToteTheme.colors
    val spacing = ToteTheme.spacing

    // Settled bins get one dated line and no verbs — rewrite and reprint live on the hero icons.
    // The date matters: a tag written before the bin was renamed carries the old text, and the
    // date is the only way to know that from the app.
    if (hasTag && cardPrinted) {
        Text(
            if (writtenAt != null) {
                "Tagged and labelled · ${writtenAt.take(10)}"
            } else {
                "Tagged and labelled"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val urgent = !hasTag && !cardPrinted
    val message = when {
        urgent && !hasNfc -> "Not labelled yet — this phone has no NFC, so print a card"
        urgent -> "No tag or card on this bin yet, so it can only be found by opening it"
        hasTag && writtenAt != null -> "Tag written ${writtenAt.take(10)} · no card printed"
        hasTag -> "Tagged · no card printed"
        else -> "Card printed · no tag yet"
    }
    // Whichever half is missing and possible: writing the tag needs NFC, printing never does.
    val verb: Pair<String, () -> Unit>? = when {
        !hasTag && hasNfc -> "Write tag" to onWriteTag
        !cardPrinted -> "Print card" to onPrintCard
        else -> null
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (urgent) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(colors.attention.base)
            )
            Spacer(Modifier.width(spacing.sm))
        }
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        verb?.let { (label, action) ->
            TextButton(
                onClick = action,
                colors = if (urgent) {
                    ButtonDefaults.textButtonColors(contentColor = colors.attention.base)
                } else {
                    ButtonDefaults.textButtonColors()
                },
            ) { Text(label) }
        }
    }
}

/**
 * Whether what the catalog says is in this bin has ever been checked against the bin — one
 * line, in the same voice as the labelling one directly above it.
 *
 * The two lines answer the two halves of trusting a catalogue: the first says whether the bin
 * can be FOUND, this one says whether its contents can be BELIEVED. A bin nobody has verified
 * is the ordinary state — every bin filed before this feature existed is in it — so it says so
 * plainly and stays out of the attention channel. Rose is kept for a bin that WAS checked and
 * has since gone a year without, which is the only case where the catalog is making a claim old
 * enough to have quietly stopped being true.
 */
@Composable
private fun VerifyLine(lastVerifiedAt: String?, onVerify: () -> Unit) {
    val colors = ToteTheme.colors
    val months = remember(lastVerifiedAt) { monthsSince(lastVerifiedAt) }
    val stale = months != null && months > STALE_AFTER_MONTHS

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            when {
                // Verbatim date, like the labelling line's — the two sit together and one of
                // them formatting its date differently would read as two different facts.
                lastVerifiedAt == null -> "Never verified"
                stale -> "Verified ${lastVerifiedAt.take(10)} · $months months ago"
                else -> "Verified ${lastVerifiedAt.take(10)}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (stale) colors.attention.base else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = onVerify,
            colors = if (stale) {
                ButtonDefaults.textButtonColors(contentColor = colors.attention.base)
            } else {
                ButtonDefaults.textButtonColors()
            },
        ) { Text("Verify contents") }
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
