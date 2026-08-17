package com.tote.ui.totes

import android.content.Intent
import androidx.compose.foundation.background
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tote.data.remote.CategoryDto
import com.tote.data.remote.ItemDto
import com.tote.data.remote.PersonDto
import com.tote.data.remote.ToteDetailDto
import com.tote.nfc.NfcWriteSession
import com.tote.nfc.WriteState
import com.tote.nfc.hasNfc
import com.tote.ui.components.HazardRule
import com.tote.ui.components.DateField
import com.tote.ui.components.ItemThumbnail
import com.tote.ui.components.PickerDialog
import com.tote.ui.components.PickerField
import com.tote.ui.components.PickerOption
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
    viewModel: ToteDetailViewModel = hiltViewModel(),
    itemSheet: ItemSheetViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val writeState by viewModel.write.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var confirmingUnpack by remember { mutableStateOf(false) }
    var lending by remember { mutableStateOf<String?>(null) }
    val people by viewModel.people.collectAsStateWithLifecycle()
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
            )
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
                    // Both operations whenever each has something to act on. The old either/or
                    // hid Repack on any bin with items still inside — so a half-unpacked
                    // Christmas bin (3 in, 2 out: the normal January state) could only be
                    // repacked one row at a time.
                    if (tote.itemCount > 0) {
                        ToteButton(
                            text = "Unpack all",
                            onClick = onUnpackAll,
                            tonal = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (tote.outCount > 0) {
                        ToteButton(
                            text = "Repack all",
                            onClick = onRepackAll,
                            tonal = true,
                            modifier = Modifier.weight(1f),
                        )
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
                    cardPrinted = tote.cardPrintedAt != null,
                    hasNfc = hasNfc,
                    onWriteTag = onWriteTag,
                    onPrintCard = onPrintCard,
                )
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
                ItemRow(
                    item,
                    actionLabel = "Take out",
                    onAction = { onTakeOut(item.id) },
                    onOpen = { onOpenItem(item) },
                )
            }

            // The gap, shown rather than hidden. This section is the answer to "I thought the
            // lights were in here" — the single most common reason to stop trusting a catalog.
            if (tote.itemsOut.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(spacing.sm))
                    SectionHeader(label = "Out of this tote", channel = colors.attention.base)
                }
                items(tote.itemsOut, key = { "out-${it.id}" }) { item ->
                    ItemRow(
                        item,
                        actionLabel = "Put back",
                        onAction = { onPutBack(item.id) },
                        onOpen = { onOpenItem(item) },
                    )
                }
            }
        }
    }
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
    cardPrinted: Boolean,
    hasNfc: Boolean,
    onWriteTag: () -> Unit,
    onPrintCard: () -> Unit,
) {
    val colors = ToteTheme.colors
    val spacing = ToteTheme.spacing

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

@Composable
private fun ItemRow(
    item: ItemDto,
    actionLabel: String,
    onAction: () -> Unit,
    onOpen: (() -> Unit)? = null,
) {
    val colors = ToteTheme.colors
    val spacing = ToteTheme.spacing

    PanelCard(
        onClick = onOpen,
        channel = if (item.isOverdue) colors.attention.base else null,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The picture leads. A bin's contents are recognised by sight long before they are
            // read, and two rows both saying "Toddler Bed Comforter" are indistinguishable as
            // text and obviously different as photographs.
            ItemThumbnail(item)
            Spacer(Modifier.width(spacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    if (item.quantity > 1) "${item.name} ×${item.quantity}" else item.name,
                    style = MaterialTheme.typography.titleMedium,
                    // Two lines, not one. With a thumbnail and an action on the row there is not
                    // enough width for a real item name on one line — "Toddler Bed Comforter"
                    // truncated to "Toddler Be…" is a row that has failed at its only job, and
                    // two of them are indistinguishable exactly when it matters most.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val status = when {
                    // Naming the borrower is the entire point of recording the loan: "lent out"
                    // and "Dave has it" are the same fact and only one of them gets it back.
                    item.isOverdue ->
                        "Overdue — ${item.loanedTo ?: "someone"} has had it since ${item.expectedBack}"
                    item.status == "loaned" -> "Lent to ${item.loanedTo ?: "someone"}"
                    item.status == "out" -> "Out since it was unpacked"
                    else -> null
                }
                // The tag's own words, joined to the status rather than stacked under it. This
                // is the line someone reads while holding an open bin, and every extra row on it
                // costs a garment's worth of scrolling.
                val sub = listOfNotNull(item.apparel?.sizeRaw, status).joinToString(" · ")
                if (sub.isNotEmpty()) {
                    Spacer(Modifier.height(spacing.xs))
                    Caption(text = sub)
                }
            }
            // ONE action on the row — the everyday one. Lending and deleting live in the
            // sheet behind a tap: a second button here costs the name the width it needs, and
            // a destructive action next to an everyday one is a mis-tap away from deleting a
            // photograph that cannot be retaken.
            ToteButton(text = actionLabel, onClick = onAction, tonal = true, compact = true)
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
            options = categories.map { PickerOption(id = it.id, label = it.name) },
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
