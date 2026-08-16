package com.tote.ui.totes

import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import android.content.Intent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.tote.data.remote.PhotoUrls
import com.tote.data.remote.ItemDto
import com.tote.data.remote.PersonDto
import com.tote.data.remote.ToteDetailDto
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.tote.nfc.NfcWriteSession
import com.tote.nfc.WriteState
import com.tote.nfc.hasNfc
import com.tote.ui.components.HazardRule
import com.tote.ui.components.ItemThumbnail
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
    val writeState by viewModel.write.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var lending by remember { mutableStateOf<String?>(null) }
    var openItem by remember { mutableStateOf<ItemDto?>(null) }
    var cardUrl by remember { mutableStateOf<String?>(null) }
    val people by viewModel.people.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // The PDF is handed to the system rather than rendered in-app: printing is the whole point,
    // and every phone already has a print/share path for a PDF URL.
    LaunchedEffect(cardUrl) {
        cardUrl?.let { url ->
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
            cardUrl = null
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
                onAddItem = { showAdd = true },
                onUnpackAll = viewModel::unpackAll,
                onRepackAll = viewModel::repackAll,
                onTakeOut = viewModel::moveOut,
                onPutBack = viewModel::putBack,
                onWriteTag = { if (hasNfc(context)) viewModel.beginWrite() },
                onPrintCard = { cardUrl = viewModel.cardUrl() },
                onOpenItem = { openItem = it },
            )
            openItem?.let { item ->
                ItemSheet(
                    item = item,
                    onDismiss = { openItem = null },
                    onDelete = {
                        viewModel.deleteItem(item.id)
                        openItem = null
                    },
                    // Only offered for something that is actually in the bin: you cannot lend
                    // out what is already lent out, and offering it would be a 422 dressed as
                    // a button.
                    onLend = if (item.status == "stored") {
                        {
                            viewModel.loadPeople()
                            openItem = null
                            lending = item.id
                        }
                    } else {
                        null
                    },
                )
            }
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
            if (writeState !is WriteState.Idle) {
                WriteTagDialog(state = writeState, onDismiss = viewModel::cancelWrite)
            }
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
    onOpenItem: (ItemDto) -> Unit = {},
    modifier: Modifier = Modifier,
    onWriteTag: () -> Unit = {},
    onPrintCard: () -> Unit = {},
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

            item {
                TagAndCardRow(
                    hasTag = tote.nfcTagUid != null,
                    cardPrinted = tote.cardPrintedAt != null,
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
                    text = if (hasTag || cardPrinted) {
                        "Tap the tag or scan the card to open this bin"
                    } else {
                        "Write a tag or print a card so this bin can be found"
                    },
                )
            }
            Column {
                ToteButton(text = if (hasTag) "Rewrite" else "Write tag", onClick = onWriteTag, tonal = true)
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
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(ToteTheme.spacing.sm)) {
                        items(people, key = { it.id }) { person ->
                            FilterChip(
                                selected = personId == person.id,
                                onClick = { personId = person.id },
                                label = { Text(person.name) },
                            )
                        }
                    }
                    Spacer(Modifier.height(ToteTheme.spacing.md))
                    OutlinedTextField(
                        value = due,
                        onValueChange = { due = it },
                        label = { Text("Back by (optional)") },
                        placeholder = { Text("2026-09-30") },
                        singleLine = true,
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
}


/**
 * One item, up close — and the only place it can be deleted.
 *
 * Deleting exists because the catalog gets things wrong in exactly one recoverable way: a row
 * that should never have existed. A duplicate, a typo, a photograph of the wrong thing. Without
 * it the only fix is to live with a bin that claims two comforters when it holds one, which is
 * how a catalog stops being believed.
 *
 * It is deliberately NOT on the row. "Take out" and "Lend" are everyday taps and a destructive
 * action sitting beside them is a mis-tap away from taking the photographs with it — and they
 * are the one artefact here that cannot be recreated once the bin is taped shut. It lives one
 * tap deeper, behind its own confirmation, in the app's error voice rather than its accent.
 *
 * Disposing of something is a different operation and stays a `disposed` movement: "we no longer
 * own this" is history worth keeping, and this is not that.
 */
@Composable
private fun ItemSheet(
    item: ItemDto,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onLend: (() -> Unit)? = null,
) {
    var confirming by remember { mutableStateOf(false) }
    val spacing = ToteTheme.spacing

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (confirming) "Delete this item?" else item.name) },
        text = {
            Column {
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
                            model = PhotoUrls.item(item.id, 0),
                            contentDescription = "Photo of ${item.name}",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().height(200.dp),
                        )
                    }
                    Spacer(Modifier.height(spacing.md))
                }
                if (confirming) {
                    Text(
                        if (item.photoCount > 0) {
                            "This removes the item, its history, and its photograph. There is no undo."
                        } else {
                            "This removes the item and its history. There is no undo."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(spacing.sm))
                    // Said plainly, because the two are easy to confuse and only one is
                    // recoverable.
                    Caption(text = "If you still own it and it is just not here, take it out instead.")
                } else {
                    if (onLend != null) {
                        ToteButton(
                            text = "Lend it out",
                            onClick = onLend,
                            tonal = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(spacing.md))
                    }
                    val facts = listOfNotNull(
                        if (item.quantity > 1) "${item.quantity} of them" else null,
                        item.apparel?.sizeRaw?.let { "Size $it" },
                        item.toteCode?.let { code -> listOfNotNull(code, item.locationName).joinToString(" · ") },
                        item.description,
                    )
                    facts.forEach {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(spacing.xs))
                    }
                }
            }
        },
        confirmButton = {
            if (confirming) {
                TextButton(onClick = onDelete) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            } else {
                TextButton(onClick = { confirming = true }) {
                    Text("Delete item", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (confirming) confirming = false else onDismiss() }) {
                Text(if (confirming) "Keep it" else "Close")
            }
        },
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
