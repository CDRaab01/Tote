package com.tote.ui.people

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tote.data.local.CachedTote
import com.tote.data.remote.FitsDto
import com.tote.data.remote.ItemDto
import com.tote.data.remote.PersonDto
import com.tote.data.remote.PersonSizeDto
import com.tote.ui.components.HazardRule
import com.tote.ui.components.DateField
import com.tote.ui.components.ItemThumbnail
import com.tote.ui.components.PickerDialog
import com.tote.ui.components.PickerOption
import com.tote.ui.components.RefreshOnResume
import com.tote.ui.components.ToteButton
import com.tote.ui.theme.ToteTheme
import design.pulse.ui.components.Caption
import design.pulse.ui.components.EmptyState
import design.pulse.ui.components.ErrorState
import design.pulse.ui.components.HeroPanel
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.SectionHeader

@Composable
fun PersonDetailScreen(
    onOpenTote: (String) -> Unit,
    onGone: () -> Unit = {},
    viewModel: PersonDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    RefreshOnResume(viewModel::load)

    PersonDetailContent(
        state = state,
        onOpenTote = onOpenTote,
        onGarmentType = viewModel::setGarmentType,
        onAddSize = viewModel::addSize,
        onReturned = viewModel::markReturned,
        onOutgrown = viewModel::markOutgrown,
        onRetry = viewModel::load,
        onEditPerson = viewModel::editPerson,
        onDeletePerson = { viewModel.deletePerson(onGone) },
        onDeleteSize = viewModel::deleteSize,
    )
}

@Composable
fun PersonDetailContent(
    state: PersonDetailState,
    onOpenTote: (String) -> Unit,
    onGarmentType: (String?) -> Unit,
    onAddSize: (String, String) -> Unit,
    onReturned: (String, String) -> Unit,
    onOutgrown: (List<String>, String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onEditPerson: (String, String?) -> Unit = { _, _ -> },
    onDeletePerson: () -> Unit = {},
    onDeleteSize: (String) -> Unit = {},
) {
    val colors = ToteTheme.colors
    val spacing = ToteTheme.spacing
    var showAddSize by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var returning by remember { mutableStateOf<ItemDto?>(null) }
    var outgrowing by remember { mutableStateOf<List<String>?>(null) }

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            Modifier.fillMaxSize().padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            item {
                HeroPanel {
                    Text(
                        state.person?.name ?: "…",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(spacing.xs))
                    Text(
                        sizesSummary(state.person?.currentSizes.orEmpty()),
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
                    ToteButton(
                        text = "Edit",
                        onClick = { showEdit = true },
                        tonal = true,
                        enabled = state.person != null && !state.busy,
                        modifier = Modifier.weight(1f),
                    )
                    ToteButton(
                        text = "Remove",
                        onClick = { confirmingDelete = true },
                        tonal = true,
                        enabled = state.person != null && !state.busy,
                        channel = MaterialTheme.colorScheme.error,
                        dimChannel = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (state.loading && state.person == null) {
                item {
                    Box(Modifier.fillMaxWidth().padding(spacing.xl), Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                return@LazyColumn
            }

            if (state.error != null) {
                item {
                    ErrorState(
                        icon = Icons.Outlined.CloudOff,
                        title = "Couldn't load this person",
                        detail = state.error,
                        onRetry = onRetry,
                    )
                }
                return@LazyColumn
            }

            // ── Sizes ────────────────────────────────────────────────────────
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionHeader(label = "Sizes now", channel = colors.slate.base)
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        if (state.sizeHistory.isNotEmpty()) {
                            ToteButton(
                                text = "History",
                                onClick = { showHistory = true },
                                tonal = true,
                                compact = true,
                                enabled = !state.busy,
                            )
                        }
                        ToteButton(
                            text = "Record size",
                            onClick = { showAddSize = true },
                            tonal = true,
                            compact = true,
                            enabled = !state.busy,
                        )
                    }
                }
            }
            item {
                PanelCard {
                    val sizes = state.person?.currentSizes.orEmpty()
                    if (sizes.isEmpty()) {
                        Caption(
                            text = "Nothing recorded. Read a tag on something that fits her now " +
                                "and put those exact words in — Tote never guesses a size."
                        )
                    } else {
                        sizes.forEach { size ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = spacing.xs),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(garmentLabel(size.garmentType), style = MaterialTheme.typography.bodyMedium)
                                // The tag's own words, always. The ordinal behind them is an
                                // index for querying and is never shown.
                                Text(size.sizeRaw, style = ToteTheme.dataType.dataMedium, color = colors.slate.base)
                            }
                        }
                    }
                }
            }

            // ── What fits ────────────────────────────────────────────────────
            item { SectionHeader(label = "What fits right now", channel = colors.search.base) }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    FilterChip(
                        selected = state.garmentType == null,
                        onClick = { onGarmentType(null) },
                        label = { Text("Everything") },
                    )
                    GARMENT_TYPES.forEach { type ->
                        FilterChip(
                            selected = state.garmentType == type,
                            onClick = { onGarmentType(type) },
                            label = { Text(garmentLabel(type).replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }
            }
            fitsSection(state, onOpenTote, onOutgrown = { outgrowing = it })

            // ── On loan ──────────────────────────────────────────────────────
            if (state.onLoan.isNotEmpty()) {
                item { SectionHeader(label = "Has of ours", channel = colors.attention.base) }
                items(state.onLoan, key = { "loan-${it.id}" }) { item ->
                    PanelCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ItemThumbnail(item)
                            Spacer(Modifier.width(spacing.md))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    item.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(spacing.xs))
                                Caption(text = dueText(item))
                            }
                            ToteButton(
                                text = "Returned",
                                onClick = { returning = item },
                                tonal = true,
                                compact = true,
                            )
                        }
                    }
                }
            }
        }
    }


    if (showEdit) {
        var name by remember { mutableStateOf(state.person?.name.orEmpty()) }
        var birthdate by remember { mutableStateOf(state.person?.birthdate.orEmpty()) }
        AlertDialog(
            onDismissRequest = { showEdit = false },
            title = { Text("Edit person") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(ToteTheme.spacing.md))
                    DateField(
                        value = birthdate,
                        onValueChange = { birthdate = it },
                        label = "Birthdate (optional)",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onEditPerson(name, birthdate)
                        showEdit = false
                    },
                    enabled = name.isNotBlank(),
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showEdit = false }) { Text("Cancel") } },
        )
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Remove ${state.person?.name ?: "this person"}?") },
            text = {
                Column {
                    Text(
                        "Their recorded sizes go with them.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(ToteTheme.spacing.sm))
                    // The ledger keeps every loan; only the name attached to it is lost. Said
                    // plainly because "who has the drill" is one of the two questions this
                    // table exists to answer, and this is the tap that gives it up.
                    Caption(
                        text = "Loans they were part of stay in each item's history, but those " +
                            "rows will no longer name anyone."
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeletePerson()
                        confirmingDelete = false
                    },
                ) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("Keep") }
            },
        )
    }

    if (showHistory) {
        AlertDialog(
            onDismissRequest = { showHistory = false },
            title = { Text("Size history") },
            text = {
                Column {
                    Caption(
                        text = "Newest first. A size can be removed but never edited — the tag's " +
                            "words are kept verbatim, and the index is derived from them."
                    )
                    Spacer(Modifier.height(ToteTheme.spacing.sm))
                    state.sizeHistory.forEach { size ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = ToteTheme.spacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${size.sizeRaw} · ${garmentLabel(size.garmentType)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Caption(
                                    text = buildString {
                                        append("from ${size.effectiveFrom}")
                                        // An unplaceable reading is exactly what makes `fits`
                                        // say "we can't say", so it is named here.
                                        if (size.sizeSystem == null) append(" · not on the ladder")
                                    }
                                )
                            }
                            TextButton(
                                onClick = { onDeleteSize(size.id) },
                                enabled = !state.busy,
                            ) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showHistory = false }) { Text("Close") }
            },
        )
    }

    if (showAddSize) {
        RecordSizeDialog(
            onDismiss = { showAddSize = false },
            onAdd = { type, raw ->
                onAddSize(type, raw)
                showAddSize = false
            },
        )
    }
    returning?.let { item ->
        PickToteDialog(
            title = "Where does it go back?",
            subtitle = "\"Returned\" puts it in a bin. An item that is back but in no bin is the " +
                "state this catalog exists to prevent.",
            totes = state.totes,
            onDismiss = { returning = null },
            onPick = { toteId ->
                onReturned(item.id, toteId)
                returning = null
            },
        )
    }
    outgrowing?.let { ids ->
        PickToteDialog(
            title = "File ${ids.size} outgrown item${if (ids.size == 1) "" else "s"}",
            subtitle = "They move out of the wearing pile and into this bin, with one ledger " +
                "entry each, in a single action.",
            totes = state.totes,
            onDismiss = { outgrowing = null },
            onPick = { toteId ->
                onOutgrown(ids, toteId)
                outgrowing = null
            },
        )
    }
}

/**
 * The fits result, and the distinction the whole endpoint is built around.
 *
 * `answered = false` means there is no indexed size to match against — it is **not** an empty
 * result, and rendering it as "nothing fits" would tell someone to stop looking when the real
 * answer is "go and read a tag". The server sends the two apart; so does this.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.fitsSection(
    state: PersonDetailState,
    onOpenTote: (String) -> Unit,
    onOutgrown: (List<String>) -> Unit,
) {
    val fits = state.fits
    when {
        fits == null && state.loading -> Unit
        fits == null -> item {
            Caption(text = "Couldn't check what fits just now.")
        }
        !fits.answered -> item {
            EmptyState(
                icon = Icons.Outlined.HelpOutline,
                title = "We can't say yet",
                subtitle = when (fits.reason) {
                    "no_sizes_recorded" ->
                        "No size is recorded for her, so there is nothing to match against. " +
                            "Record one above and this fills in."
                    else ->
                        "A recorded size couldn't be placed on the ladder, so matching would " +
                            "be a guess. Check History above — a mistyped reading (“5TT”) " +
                            "reads exactly like this, and deleting it fixes it."
                },
            )
        }
        fits.items.isEmpty() -> item {
            EmptyState(
                icon = Icons.Filled.Checkroom,
                title = "Nothing in that size",
                subtitle = "We checked against her recorded size and own nothing that matches.",
            )
        }
        else -> {
            items(fits.items, key = { "fit-${it.id}" }) { item ->
                PanelCard(onClick = { item.currentToteId?.let(onOpenTote) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ItemThumbnail(item)
                        Spacer(Modifier.width(ToteTheme.spacing.md))
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.name,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(ToteTheme.spacing.xs))
                            Caption(text = whereText(item))
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    ToteButton(
                        text = "Mark these outgrown",
                        onClick = { onOutgrown(fits.items.map { it.id }) },
                        tonal = true,
                        compact = true,
                    )
                }
            }
            item {
                // Said once, near the results, because the ladder crosses systems: 4T and youth
                // 4 are not the same garment and the app must never assert that they are.
                Caption(text = "Sizes across systems are approximate — worth an eye before you pack.")
            }
        }
    }
}

/** "A14 · Attic", or the honest truth that it is not in a bin. */
internal fun whereText(item: ItemDto): String = buildString {
    if (item.toteCode != null) {
        append(item.toteCode)
        item.locationName?.let { append(" · $it") }
    } else {
        append("Not in a bin")
    }
    item.apparel?.sizeRaw?.let { append(" · $it") }
}

/** Due wording, with overdue taken from the server rather than computed from the phone's clock. */
internal fun dueText(item: ItemDto): String = when {
    item.isOverdue && item.expectedBack != null -> "Overdue — was due ${item.expectedBack}"
    item.expectedBack != null -> "Due back ${item.expectedBack}"
    else -> "No date agreed"
}

@Composable
private fun RecordSizeDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var garmentType by remember { mutableStateOf(GARMENT_TYPES.first()) }
    var raw by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Record a size") },
        text = {
            Column {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(ToteTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(ToteTheme.spacing.sm)) {
                    GARMENT_TYPES.forEach { type ->
                        FilterChip(
                            selected = garmentType == type,
                            onClick = { garmentType = type },
                            label = { Text(garmentLabel(type).replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }
                Spacer(Modifier.height(ToteTheme.spacing.md))
                OutlinedTextField(
                    value = raw,
                    onValueChange = { raw = it },
                    label = { Text("What the tag says") },
                    placeholder = { Text("5T") },
                    singleLine = true,
                )
                Spacer(Modifier.height(ToteTheme.spacing.sm))
                // The rule, stated where it is being relied on: type the tag's words, not an
                // interpretation of them. The index is derived from this and never the reverse.
                Caption(text = "Exactly as printed. Tote indexes it; it never rewrites it.")
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(garmentType, raw) }, enabled = raw.isNotBlank()) {
                Text("Record")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PickToteDialog(
    title: String,
    subtitle: String,
    totes: List<CachedTote>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    PickerDialog(
        title = title,
        options = totes.map { tote ->
            PickerOption(
                id = tote.id,
                label = tote.label?.let { "${tote.code} · $it" } ?: tote.code,
                detail = tote.locationName,
            )
        },
        selectedId = null,
        // Non-null by construction: this dialog has no "none" row, because both flows that
        // open it (outgrown, returned) REQUIRE a destination — a pile on the floor that the
        // catalog claims is nowhere is the state it exists to prevent.
        onPick = { id -> id?.let(onPick) },
        onDismiss = onDismiss,
        subtitle = subtitle,
        searchHint = "Search bins",
        emptyMessage = "No bins yet — make one on the Totes tab first.",
    )
}

@Preview(name = "Person — fits, dark")
@Composable
private fun PersonDetailPreview() {
    ToteTheme(darkTheme = true) {
        PersonDetailContent(
            state = PersonDetailState(
                person = PersonDto(
                    id = "p1",
                    name = "Emma",
                    createdAt = "2026-01-01T00:00:00Z",
                    currentSizes = listOf(
                        PersonSizeDto("s1", "p1", "tops", "5T", "toddler", 5.0, "2026-08-01"),
                    ),
                ),
                fits = FitsDto(
                    answered = true,
                    items = listOf(
                        ItemDto(
                            id = "i1",
                            name = "Red winter coat",
                            status = "stored",
                            toteCode = "A15",
                            locationName = "Attic",
                        ),
                    ),
                ),
                onLoan = emptyList(),
                loading = false,
            ),
            onOpenTote = {},
            onGarmentType = {},
            onAddSize = { _, _ -> },
            onReturned = { _, _ -> },
            onOutgrown = { _, _ -> },
            onRetry = {},
        )
    }
}
