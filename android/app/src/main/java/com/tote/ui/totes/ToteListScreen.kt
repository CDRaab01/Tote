package com.tote.ui.totes

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.tote.data.local.CachedTote
import com.tote.data.local.CachedItem
import com.tote.data.remote.LocationDto
import com.tote.data.remote.ItemDto
import com.tote.data.remote.PhotoUrls
import com.tote.ui.components.PickerDialog
import com.tote.ui.components.PickerField
import com.tote.ui.components.PickerOption
import com.tote.ui.components.RefreshOnResume
import com.tote.ui.components.ToteButton
import com.tote.ui.components.ToteGlyph
import com.tote.ui.items.ItemSheetViewModel
import com.tote.ui.items.ItemSheet
import com.tote.ui.theme.ToteTheme
import com.tote.util.UiState
import design.pulse.ui.components.Caption
import design.pulse.ui.components.EmptyState
import design.pulse.ui.components.ErrorState
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.SectionHeader
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** The heading a bin with nowhere recorded sits under. Last, always — see [byLocation]. */
internal const val NO_LOCATION = "No location yet"

@Composable
fun ToteListScreen(
    onOpenTote: (String) -> Unit,
    onOpenUnfiledList: () -> Unit = {},
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

    // Which place the picker was opened for. The system picker hands back a Uri and nothing
    // else, so the question it was launched to answer has to be held here — the same shape as
    // the capture flow's pending camera target. Saved rather than remembered because the picker
    // is a whole other app: this one can be killed behind it, and the result registry redelivers
    // the Uri to a process that would otherwise no longer know which shelf it was a picture of.
    var photographing by rememberSaveable { mutableStateOf<String?>(null) }
    val photoPicker = rememberLauncherForActivityResult(
        // The photo picker, not a storage permission: it hands over the one image the person
        // chose and needs no access to the rest of the gallery.
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val locationId = photographing
        photographing = null
        if (uri != null && locationId != null) viewModel.setLocationPhoto(locationId, uri)
    }

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
        onOpenUnfiledList = onOpenUnfiledList,
        onAddLocationPhoto = { locationId ->
            photographing = locationId
            photoPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        },
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
    onOpenUnfiledList: () -> Unit = {},
    /** Photograph a place, by its location id — the group headers' one write. */
    onAddLocationPhoto: (String) -> Unit = {},
    unreachable: Boolean = false,
    loading: Boolean = false,
    refreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    val colors = ToteTheme.colors
    val spacing = ToteTheme.spacing
    var showArchived by remember { mutableStateOf(false) }
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

            // Directly under the header. These are loose ends the person created deliberately by
            // deferring a destination, and deferring is only reasonable if the deferred things
            // visibly accumulate somewhere they will look.
            if (unfiled.isNotEmpty()) {
                item(key = "unfiled-head") {
                    // A signal, not a workspace. It says how many loose ends there are and
                    // opens the screen built for clearing them; it does not unfold thirty-two
                    // rows on top of the bins somebody came here to look at.
                    PanelCard(onClick = onOpenUnfiledList, channel = colors.attention.base) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Not in a bin (${unfiled.size})",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = colors.attention.base,
                                )
                                Spacer(Modifier.height(spacing.xs))
                                Caption(text = "Catalogued, waiting for somewhere to go")
                            }
                            Caption(text = "File them")
                        }
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
                    LocationHeader(
                        place = place,
                        // Every bin in a group is in the same place, so the first one carries
                        // its identity; `any` rather than `first` for the photo flag because a
                        // half-refreshed cache should still draw the banner it has.
                        locationId = bins.firstOrNull()?.locationId
                            ?.takeIf { place != NO_LOCATION },
                        hasPhoto = bins.any { it.locationHasPhoto },
                        onAddPhoto = onAddLocationPhoto,
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

/**
 * A place, as the thing you are about to go and stand in.
 *
 * With a photograph of the shelf it becomes a banner, because "the attic" is a word and the
 * corner of the attic where the bins actually are is a picture — the same argument that put
 * photographs on item rows, one level up. Without one it stays the plain header it always was:
 * a banner-shaped grey box for every place nobody has photographed would be worse than the
 * word alone.
 *
 * The camera icon is on the header rather than in a settings screen because the moment you know
 * what a place looks like is the moment you are standing in it with the phone out.
 */
@Composable
private fun LocationHeader(
    place: String,
    locationId: String?,
    hasPhoto: Boolean,
    onAddPhoto: (String) -> Unit,
) {
    val colors = ToteTheme.colors
    val spacing = ToteTheme.spacing

    if (locationId != null && hasPhoto) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(84.dp)
                .clip(RoundedCornerShape(12.dp))
                // Behind the photograph, so a banner that has not loaded yet (or at all — this
                // list is read where the Wi-Fi is worst) is a dark card with a legible name on
                // it rather than a hole in the list.
                .background(colors.panelHigh),
        ) {
            AsyncImage(
                // Authed, like every photo in this app — the OkHttp client ToteApp hands Coil
                // is what makes this a picture instead of a 401.
                model = PhotoUrls.location(locationId),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // The name has to survive whatever the photograph is, so it gets its own darkness
            // rather than trusting the picture to be dark where the words land.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))
                        )
                    )
            )
            Box(Modifier.align(Alignment.BottomStart).padding(spacing.md)) {
                Caption(text = place, color = Color.White)
            }
            IconButton(
                onClick = { onAddPhoto(locationId) },
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                // Its own darkness, for the same reason the place name has one — and more
                // urgently, because the scrim above is a vertical gradient that is fully
                // TRANSPARENT exactly here. A white glyph at the top of an arbitrary
                // photograph is invisible the moment somebody photographs a bright shelf or a
                // window, and it is invisible without ever looking broken. The disc is black at
                // 0.55, which holds white to 4.75:1 even over pure white.
                Box(
                    Modifier
                        .size(32.dp)
                        .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.AddAPhoto,
                        // The verb changes with the state, the way the hero's tag icon does.
                        contentDescription = "Replace the photo of $place",
                        tint = Color.White,
                    )
                }
            }
        }
        return
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SectionHeader(
            label = place,
            channel = if (place == NO_LOCATION) colors.attention.base else colors.slate.base,
            modifier = Modifier.weight(1f),
        )
        // Not offered on the loose-ends heading: "No location yet" is not a place, and there is
        // nothing to point a camera at.
        if (locationId != null) {
            IconButton(onClick = { onAddPhoto(locationId) }) {
                Icon(
                    Icons.Outlined.AddAPhoto,
                    contentDescription = "Add a photo of $place",
                    // `hairlineStrong` is the app's tint for a placeholder mark sitting inside a
                    // filled panel (ItemThumbnail, ItemCell); on the screen's own background it
                    // measured 1.56:1 in dark and 1.60:1 in light — invisible, and invisible on
                    // exactly the places that have no photograph, which is where the invitation
                    // is. Quiet icons that can be pressed use onSurfaceVariant here.
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * How long a verified bin stays verified.
 *
 * A year, because the things this app holds are seasonal: a bin opened every Christmas is
 * checked every Christmas, and marking it stale at six months would put a rose caption on the
 * whole attic every summer. The attention channel only works while it is rare.
 */
internal const val STALE_AFTER_MONTHS = 12L

/**
 * Whole months since an ISO stamp — null when it never happened, or cannot be read.
 *
 * Null for unparseable rather than zero: "verified this month" is a claim, and inventing it
 * from a string this client did not understand is exactly the kind of quiet lie the verify
 * pass exists to remove. Dates arrive verbatim from the server and only the day part matters,
 * so the time is dropped rather than parsed.
 */
internal fun monthsSince(iso: String?, today: LocalDate = LocalDate.now()): Long? =
    iso?.let {
        runCatching { ChronoUnit.MONTHS.between(LocalDate.parse(it.take(10)), today) }.getOrNull()
    }

@Composable
private fun ToteRow(tote: CachedTote, onClick: () -> Unit) {
    val colors = ToteTheme.colors
    val spacing = ToteTheme.spacing
    // Only a bin that WAS verified can go stale. A bin nobody has ever checked gets no mark at
    // all on this list: every bin filed before verification existed is in that state, and a
    // fourteen-row rose column says nothing about which one is actually worth opening.
    val staleMonths = remember(tote.lastVerifiedAt) {
        monthsSince(tote.lastVerifiedAt)?.takeIf { it > STALE_AFTER_MONTHS }
    }

    PanelCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The bin as a swatch, not its code in grey text: the code is what is written on the
            // physical card, and the colour is what the eye finds on the shelf before the code
            // is ever read. The glyph carries both.
            ToteGlyph(code = tote.code, colorHex = tote.colorHex)
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
                staleMonths?.let { months ->
                    Spacer(Modifier.height(spacing.xs))
                    Caption(
                        text = "Not verified in $months months",
                        color = colors.attention.base,
                    )
                }
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
                CachedTote(
                    "1", "A14", "Christmas decor", "l1", "Attic", 37, 0, false,
                    colorHex = "#7A1F2B", lastVerifiedAt = "2024-12-02",
                ),
                CachedTote(
                    "2", "A15", "Winter clothes 4T", "l1", "Attic", 12, 3, false,
                    colorHex = "#2F6F4E", lastVerifiedAt = "2026-07-19",
                ),
                CachedTote("3", "G01", "Power tools", "l2", "Garage rack B", 8, 1, false),
            ),
            onOpenTote = {},
            onNewTote = {},
        )
    }
}
