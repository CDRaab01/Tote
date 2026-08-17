package com.tote.ui.totes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tote.data.local.CachedTote
import com.tote.data.local.CachedItem
import com.tote.data.remote.LocationDto
import com.tote.data.remote.ItemDto
import com.tote.ui.components.PickerDialog
import com.tote.ui.components.PickerField
import com.tote.ui.components.PickerOption
import com.tote.ui.components.RefreshOnResume
import com.tote.ui.components.ToteButton
import com.tote.ui.items.ItemSheetViewModel
import com.tote.ui.items.ItemSheet
import com.tote.ui.theme.ToteTheme
import com.tote.util.UiState
import design.pulse.ui.components.Caption
import design.pulse.ui.components.EmptyState
import design.pulse.ui.components.ErrorState
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.SectionHeader

/** The heading a bin with nowhere recorded sits under. Last, always — see [byLocation]. */
internal const val NO_LOCATION = "No location yet"

@Composable
fun ToteListScreen(
    onOpenTote: (String) -> Unit,
    viewModel: ToteListViewModel = hiltViewModel(),
    itemSheet: ItemSheetViewModel = hiltViewModel(),
) {
    val totes by viewModel.totes.collectAsStateWithLifecycle()
    val archived by viewModel.archived.collectAsStateWithLifecycle()
    val unfiled by viewModel.unfiled.collectAsStateWithLifecycle()
    val locations by viewModel.locations.collectAsStateWithLifecycle()
    val createState by viewModel.create.collectAsStateWithLifecycle()
    val unreachable by viewModel.unreachable.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    var showCreate by remember { mutableStateOf(false) }

    RefreshOnResume(viewModel::refresh)

    ToteListContent(
        totes = totes,
        onOpenTote = onOpenTote,
        onNewTote = {
            viewModel.loadLocations()
            showCreate = true
        },
        archived = archived,
        unfiled = unfiled,
        onOpenUnfiled = itemSheet::open,
        unreachable = unreachable,
        loading = loading,
        refreshing = refreshing,
        onRefresh = viewModel::refresh,
        onRetry = viewModel::refresh,
    )

    // The sheet already knows how to put an `out` item away — its move button reads "Put it
    // away" for exactly this case — so filing a deferred item needs no new screen.
    ItemSheet(
        viewModel = itemSheet,
        onChanged = viewModel::refresh,
        onOpenBin = { toteId ->
            itemSheet.close()
            onOpenTote(toteId)
        },
    )

    if (showCreate) {
        NewToteDialog(
            state = createState,
            locations = locations,
            onDismiss = {
                showCreate = false
                viewModel.clearCreateState()
            },
            onCreate = viewModel::createTote,
            onNewLocation = viewModel::createLocation,
        )
    }
    // Straight to the new bin, because creating one is never the point — labelling it is, and the
    // tag and the card live on its detail screen. Closing onto the list left the screen that
    // finishes the job a scroll and a tap away, which is how a bin ends up catalogued and unlabelled.
    LaunchedEffect(createState) {
        (createState as? UiState.Success)?.let { created ->
            showCreate = false
            viewModel.clearCreateState()
            onOpenTote(created.data)
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ToteListContent(
    totes: List<CachedTote>,
    onOpenTote: (String) -> Unit,
    onNewTote: () -> Unit,
    modifier: Modifier = Modifier,
    archived: List<CachedTote> = emptyList(),
    unfiled: List<CachedItem> = emptyList(),
    onOpenUnfiled: (ItemDto) -> Unit = {},
    unreachable: Boolean = false,
    loading: Boolean = false,
    refreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    val colors = ToteTheme.colors
    val spacing = ToteTheme.spacing
    var showArchived by remember { mutableStateOf(false) }
    var showUnfiled by remember { mutableStateOf(false) }
    val groups = remember(totes) { byLocation(totes) }

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh) {
        LazyColumn(
            Modifier.fillMaxSize().padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionHeader(label = "Totes", channel = colors.slate.base)
                    ToteButton(text = "New tote", onClick = onNewTote, tonal = true)
                }
            }

            // Directly under the header, and one line when collapsed. These are loose ends the
            // person created deliberately by deferring a destination, and deferring is only
            // reasonable if the deferred things visibly accumulate somewhere they will look.
            if (unfiled.isNotEmpty()) {
                item(key = "unfiled-head") {
                    Row(
                        Modifier.fillMaxWidth().clickable { showUnfiled = !showUnfiled },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SectionHeader(
                            label = "Not in a bin (${unfiled.size})",
                            channel = colors.attention.base,
                        )
                        Spacer(Modifier.width(spacing.sm))
                        Caption(text = if (showUnfiled) "Hide" else "Show")
                    }
                }
                if (showUnfiled) {
                    items(unfiled, key = { "unfiled-${it.id}" }) { cached ->
                        UnfiledRow(cached, onClick = { onOpenUnfiled(cached.toItemDto()) })
                    }
                }
            }

            if (totes.isEmpty()) {
                item {
                    // Three states, not two. "No totes yet" over a household with fourteen bins
                    // is the lie that invites someone to create A14 twice — and it used to be
                    // shown during the FIRST load as well as on failure, because only failure
                    // was guarded.
                    if (loading) {
                        Box(Modifier.fillMaxWidth().padding(spacing.xl), Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else if (unreachable) {
                        ErrorState(
                            icon = Icons.Outlined.CloudOff,
                            title = "Can't reach Tote",
                            detail = "Nothing is cached on this phone yet, so there is nothing " +
                                "to show offline. Check you're on the tailnet.",
                            onRetry = onRetry,
                        )
                    } else {
                        EmptyState(
                            icon = Icons.Filled.Inventory2,
                            title = "No totes yet",
                            subtitle = "Create one, write its code on an index card, and start filling it.",
                        )
                    }
                }
            }

            // Grouped by where the bins physically are, because "everything in the attic" is a
            // browse entry point and one flat alphabetical run of A14, A15, B02, G01 is not a
            // list of places — it is a list of codes, which is the thing you are trying to avoid
            // having to remember.
            groups.forEach { (place, bins) ->
                item(key = "head-$place") {
                    SectionHeader(
                        label = place,
                        channel = if (place == NO_LOCATION) colors.attention.base else colors.slate.base,
                    )
                }
                items(bins, key = { it.id }) { tote ->
                    ToteRow(tote, onClick = { onOpenTote(tote.id) })
                }
            }

            if (archived.isNotEmpty()) {
                item(key = "archived-head") {
                    // Collapsed, not hidden. An archived bin is a physical box that still exists
                    // somewhere; it is off the daily list, not out of the catalog.
                    Row(
                        Modifier.fillMaxWidth().clickable { showArchived = !showArchived },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SectionHeader(
                            label = "Archived (${archived.size})",
                            channel = colors.provenance.base,
                        )
                        Spacer(Modifier.width(spacing.sm))
                        Caption(text = if (showArchived) "Hide" else "Show")
                    }
                }
                if (showArchived) {
                    items(archived, key = { "arch-${it.id}" }) { tote ->
                        ToteRow(tote, onClick = { onOpenTote(tote.id) })
                    }
                }
            }
        }
        }
    }
}

/**
 * Bins by the place they are in, alphabetically, with the placeless ones last.
 *
 * Last rather than first on purpose: a bin with no location is a loose end, and a loose end at the
 * top of the list is in the way of every browse. It still gets a heading of its own — silently
 * mixing them into the first real place would be a lie about where they are.
 */
internal fun byLocation(totes: List<CachedTote>): List<Pair<String, List<CachedTote>>> =
    totes.groupBy { it.locationName ?: NO_LOCATION }
        .toList()
        .sortedWith(compareBy({ it.first == NO_LOCATION }, { it.first.lowercase() }))
        .map { (place, bins) -> place to bins.sortedBy { it.code.lowercase() } }

@Composable
private fun ToteRow(tote: CachedTote, onClick: () -> Unit) {
    val colors = ToteTheme.colors
    val spacing = ToteTheme.spacing

    PanelCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The code is the thing written on the physical card, so it leads — that is what
            // someone is matching against a bin in front of them.
            Text(
                tote.code,
                style = ToteTheme.dataType.dataLarge,
                color = colors.slate.base,
            )
            Spacer(Modifier.width(spacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    tote.label ?: "Unlabelled",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(spacing.xs))
                val counts = buildString {
                    append("${tote.itemCount} item${if (tote.itemCount == 1) "" else "s"}")
                    // Only shown when non-zero: a permanent "0 out" would train people to ignore
                    // the field that matters when it is not zero.
                    if (tote.outCount > 0) append(" · ${tote.outCount} out")
                    // The location is the section heading now, so it is not repeated on the row.
                }
                Caption(text = counts)
            }
        }
    }
}

/**
 * A catalogued item that is in no bin.
 *
 * The cached row is enough to draw it, but the item sheet speaks [ItemDto] — so this converts.
 * Only the fields the sheet reads for an unfiled item are carried; the rest are defaults, which
 * is honest because a cache row genuinely does not hold them. The sheet re-reads nothing: it
 * takes what it is given, and for an item with no bin that is all there is to know locally.
 */
private fun CachedItem.toItemDto() = ItemDto(
    id = id,
    name = name,
    description = description,
    notes = notes,
    quantity = quantity,
    status = status,
    currentToteId = currentToteId,
    toteCode = toteCode,
    locationName = locationName,
    isOverdue = isOverdue,
)

@Composable
private fun UnfiledRow(item: CachedItem, onClick: () -> Unit) {
    val spacing = ToteTheme.spacing
    PanelCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (item.quantity > 1) "${item.name} ×${item.quantity}" else item.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(spacing.xs))
                Caption(
                    text = listOfNotNull(
                        item.sizeRaw,
                        // Named, because "not in a bin" covers two different histories and the
                        // difference decides what to do: one needs filing, the other came out
                        // of a bin on purpose and may be meant to stay out.
                        if (item.status == "loaned") "Lent out" else "Catalogued, not filed",
                    ).joinToString(" · "),
                )
            }
        }
    }
}

@Composable
private fun NewToteDialog(
    state: UiState<String>,
    locations: List<LocationDto>,
    onDismiss: () -> Unit,
    onCreate: (String, String?, String?) -> Unit,
    onNewLocation: (String, (String) -> Unit) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var locationId by remember { mutableStateOf<String?>(null) }
    var showLocationPicker by remember { mutableStateOf(false) }
    var newLocationName by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New tote") },
        text = {
            Column {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Code") },
                    placeholder = { Text("A14") },
                    singleLine = true,
                )
                Spacer(Modifier.height(ToteTheme.spacing.sm))
                // Said up front, because the code is about to be written on a card in permanent
                // marker and codes are compared case-insensitively.
                Caption(text = "Write this on the index card. Case doesn't matter.")
                Spacer(Modifier.height(ToteTheme.spacing.md))
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label (optional)") },
                    placeholder = { Text("Christmas decor") },
                    singleLine = true,
                )
                Spacer(Modifier.height(ToteTheme.spacing.md))
                // Asked here rather than left for later, because "where is it" is the question the
                // app exists to answer and a bin created without one starts life in the loose-ends
                // section — which nobody goes back to.
                PickerField(
                    label = "Where it lives",
                    selected = locations.firstOrNull { it.id == locationId }?.name,
                    placeholder = "No location yet",
                    onClick = { showLocationPicker = true },
                )
                if (state is UiState.Error) {
                    Spacer(Modifier.height(ToteTheme.spacing.sm))
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(code, label, locationId) },
                enabled = code.isNotBlank() && state !is UiState.Loading,
            ) { Text(if (state is UiState.Loading) "Creating…" else "Create") }
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
        NewLocationDialog(
            value = typed,
            onValueChange = { newLocationName = it },
            onDismiss = { newLocationName = null },
            onCreate = {
                onNewLocation(typed) { created -> locationId = created }
                newLocationName = null
            },
        )
    }
}

/**
 * Pick a place, or make one.
 *
 * "New location…" is a row in the list rather than a separate button, because the moment you
 * discover you need "Basement closet" is the moment you are looking for it and not finding it.
 * Locations CRUD existed on the server from Phase 2 and had no caller anywhere in the app, which
 * is why every bin in the catalog reads "A14" with no place after it.
 */
@Composable
internal fun LocationPicker(
    locations: List<LocationDto>,
    selectedId: String?,
    onPick: (String?) -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    val newId = "__new__"
    PickerDialog(
        title = "Where it lives",
        options = locations.map { PickerOption(id = it.id, label = it.name) } +
            PickerOption(id = newId, label = "New location…", detail = "Attic, Garage rack B…"),
        selectedId = selectedId,
        onPick = { if (it == newId) onNew() else onPick(it) },
        onDismiss = onDismiss,
        noneLabel = "No location yet",
        emptyMessage = "No places recorded yet.",
    )
}

@Composable
private fun NewLocationDialog(
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onCreate: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New location") },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    label = { Text("Name") },
                    placeholder = { Text("Basement closet") },
                    singleLine = true,
                )
                Spacer(Modifier.height(ToteTheme.spacing.sm))
                // Said because free text is exactly what a locations table exists to prevent:
                // "attic", "Attic" and "the attic" are three places to browse instead of one.
                Caption(text = "Name it the way you'd say it out loud. Bins group under it.")
            }
        },
        confirmButton = {
            TextButton(onClick = onCreate, enabled = value.isNotBlank()) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Preview(name = "Totes — dark")
@Composable
private fun ToteListPreview() {
    ToteTheme(darkTheme = true) {
        ToteListContent(
            totes = listOf(
                CachedTote("1", "A14", "Christmas decor", null, "Attic", 37, 0, false),
                CachedTote("2", "A15", "Winter clothes 4T", null, "Attic", 12, 3, false),
                CachedTote("3", "G01", "Power tools", null, "Garage rack B", 8, 1, false),
            ),
            onOpenTote = {},
            onNewTote = {},
        )
    }
}
