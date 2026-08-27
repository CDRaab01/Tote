@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.tote.ui.search

import androidx.compose.foundation.layout.Arrangement
import com.tote.data.remote.SeasonalToteDto
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.IconButton
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tote.data.remote.ItemDto
import com.tote.data.remote.NextSizeCardDto
import com.tote.data.remote.SeasonalCardDto
import com.tote.ui.components.HazardRule
import com.tote.ui.components.RefreshOnResume
import com.tote.ui.components.ItemThumbnail
import com.tote.ui.components.ToteGlyph
import com.tote.ui.items.ItemSheet
import com.tote.ui.items.ItemSheetViewModel
import com.tote.ui.theme.ToteTheme
import design.pulse.ui.components.Caption
import design.pulse.ui.components.EmptyState
import design.pulse.ui.components.HeroPanel
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.SectionHeader
import design.pulse.ui.components.StatTile
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How many overdue rows the card names before it summarises — enough to act on, short
 *  enough that the card never pushes the search box off the screen. */
private const val OVERDUE_SHOWN = 3

@Composable
fun SearchScreen(
    onOpenTote: (String) -> Unit,
    onOpenSettings: () -> Unit = {},
    hasInvite: Boolean = false,
    onOpenCategory: (String, String) -> Unit = { _, _ -> },
    onOpenPerson: (String) -> Unit = {},
    /** The bins tab, from the Totes tile. */
    onOpenTotes: () -> Unit = {},
    /** The loose ends, from the Not-in-a-bin tile. */
    onOpenNotInABin: () -> Unit = {},
    viewModel: SearchViewModel = hiltViewModel(),
    itemSheet: ItemSheetViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Find's counters and the overdue card were frozen at whatever they said the first time the
    // tab was opened, for the life of the process.
    RefreshOnResume(viewModel::refresh)

    SearchContent(
        state = state,
        onQueryChange = viewModel::onQueryChange,
        onOpenItem = itemSheet::open,
        onOpenSettings = onOpenSettings,
        hasInvite = hasInvite,
        onOpenCategory = onOpenCategory,
        onSizeSelect = viewModel::onSizeSelect,
        onOpenPerson = onOpenPerson,
        onOpenTotes = onOpenTotes,
        onOpenNotInABin = onOpenNotInABin,
        onOpenTote = onOpenTote,
    )

    // A hit opens the item, not the bin. Tapping one used to be guarded on `currentToteId`, so a
    // row for anything lent out or unpacked — exactly the things you search for because you
    // cannot find them — did nothing at all, silently. The sheet answers "what is this and where
    // is it" for every status, and offers the bin as a button when there is one.
    ItemSheet(
        viewModel = itemSheet,
        onChanged = viewModel::refresh,
        onOpenBin = { toteId ->
            itemSheet.close()
            onOpenTote(toteId)
        },
    )
}

/** Stateless body — renderable in a screenshot test without Hilt or a network. */
@Composable
fun SearchContent(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onOpenItem: (ItemDto) -> Unit,
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
    hasInvite: Boolean = false,
    onOpenCategory: (String, String) -> Unit = { _, _ -> },
    onSizeSelect: (String?) -> Unit = {},
    onOpenPerson: (String) -> Unit = {},
    onOpenTotes: () -> Unit = {},
    onOpenNotInABin: () -> Unit = {},
    /** One bin, from a card's swatch. A search HIT still opens the item sheet, not the bin. */
    onOpenTote: (String) -> Unit = {},
) {
    val colors = ToteTheme.colors
    val spacing = ToteTheme.spacing

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            Modifier.fillMaxSize().padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            item {
                HeroPanel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Tote",
                            style = MaterialTheme.typography.headlineMedium,
                            color = androidx.compose.ui.graphics.Color.White,
                            modifier = Modifier.weight(1f),
                        )
                        // The only door to Settings. It lives on the home hero rather than a
                        // sixth tab because a bottom bar carries five, and because the screen
                        // behind it is an escape hatch — reached when something is wrong, not
                        // in the course of using the app.
                        BadgedBox(
                            badge = {
                                // Rose, the attention channel, like drafts and stuck uploads —
                                // an invitation is the one thing behind this door that somebody
                                // else started and that goes stale while it waits.
                                if (hasInvite) {
                                    Badge(
                                        containerColor = ToteTheme.colors.attention.base,
                                        contentColor = ToteTheme.colors.attention.on,
                                    )
                                }
                            }
                        ) {
                            IconButton(onClick = onOpenSettings) {
                                Icon(
                                    Icons.Filled.Settings,
                                    contentDescription =
                                        if (hasInvite) "Settings — an invitation is waiting"
                                        else "Settings",
                                    tint = androidx.compose.ui.graphics.Color.White,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(spacing.xs))
                    Text(
                        "What's in the bins, and which bin it's in.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.82f),
                    )
                    Spacer(Modifier.height(spacing.md))
                    HazardRule()
                }
            }

            item {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    // Clearing used to mean holding backspace through "ratchet set".
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear")
                            }
                        }
                    },
                    label = { Text("Search everything") },
                    placeholder = { Text("ratchet set, 4T, Zelda…") },
                    // The keyboard's action key says Search rather than newline-into-a-single-
                    // line-field, which does nothing.
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                )
                // `searching` was tracked and never rendered: a slow attic query looked exactly
                // like a frozen screen still showing the previous results.
                if (state.searching) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }

            // Narrow by size, between the field and the results. The vocabulary is the sizes
            // present in the unfiltered hits — a chip for a size with no hits would be an
            // invitation to an empty screen — and "Any size" is the way back out. Hidden
            // offline: the fallback cannot filter through the ladder, and a chip that silently
            // does nothing teaches distrust of every other one.
            if (state.searched && !state.offline && state.sizes.isNotEmpty()) {
                item {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(spacing.xs),
                    ) {
                        FilterChip(
                            selected = state.sizeFilter == null,
                            onClick = { onSizeSelect(null) },
                            label = { Text("Any size") },
                        )
                        state.sizes.forEach { size ->
                            FilterChip(
                                selected = state.sizeFilter == size,
                                onClick = { onSizeSelect(size) },
                                // Verbatim — this is the tag's own words, same as on the rows.
                                label = { Text(size) },
                            )
                        }
                    }
                }
            }

            // The attention card, above the stats and below the search box — idle only, for the
            // same reason as the stats: mid-search it would sit between someone and the answer
            // they came for. A lent thing is remembered by exactly one person and they are not
            // thinking about it, so this is the one thing the app volunteers unprompted.
            if (!state.searched && state.overdue.isNotEmpty()) {
                item {
                    PanelCard(channel = colors.attention.base) {
                        Text(
                            "${state.overdue.size} thing${if (state.overdue.size == 1) "" else "s"} " +
                                "out past the date",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.attention.base,
                        )
                        Spacer(Modifier.height(spacing.xs))
                        // Body text, not Caption: Pulse's caption is upper-cased and
                        // letter-spaced, which is right for a label and wrong for a sentence
                        // naming a person — it shouts, and it wraps badly at these lengths.
                        // Each named row OPENS that item, where Return lives. The card used
                        // to name the drill, the person and the date and then do nothing, so
                        // acting on it meant retyping "drill" into the search box above it.
                        // A surface that names a problem has to open it.
                        state.overdue.take(OVERDUE_SHOWN).forEach { item ->
                            Text(
                                "${item.name} · ${item.loanedTo ?: "someone"} · " +
                                    "due ${formatDue(item.expectedBack)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 44.dp)
                                    .clickable { onOpenItem(item) }
                                    .padding(vertical = spacing.xs),
                            )
                        }
                        if (state.overdue.size > OVERDUE_SHOWN) {
                            Text(
                                "and ${state.overdue.size - OVERDUE_SHOWN} more",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // Stats only while idle: once someone is searching, a row of counts is noise between
            // them and the answer.
            if (!state.searched) {
                // The two forward-looking cards, after the attention card and before the counts:
                // urgency first, invitations second, furniture last. Each is simply absent when
                // the server had nothing to say — or could not be asked (the 0-out rule).
                state.seasonal?.let { card ->
                    item { SeasonalCard(card, onOpenTote = onOpenTote) }
                }
                state.nextSize?.let { card ->
                    item { NextSizeCard(card, onOpenPerson = onOpenPerson, onOpenTote = onOpenTote) }
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                        StatTile(
                            "Totes",
                            state.totes.toString(),
                            channel = colors.slate.base,
                            onClick = onOpenTotes,
                            modifier = Modifier.weight(1f),
                        )
                        // Deliberately NOT tappable. The rule is that a surface naming a
                        // *problem* must open it; a count of everything is not a problem, and
                        // there is no all-items screen to open. A tile that invites a tap and
                        // goes nowhere is worse than one that never invited it — so please do
                        // not "finish the job" by wiring this to something approximate.
                        StatTile(
                            "Items",
                            state.items.toString(),
                            channel = colors.stored.base,
                            modifier = Modifier.weight(1f),
                        )
                        // "Not in a bin", not "Out", and the electric-blue channel rather than
                        // rose. Two corrections in one tile:
                        //
                        // The LABEL, because this counts exactly the rows the Totes tab already
                        // calls "Not in a bin" and the Unfiled screen already lists — the
                        // movement invariant guarantees it — so calling it something else here
                        // made one fact look like two, with two numbers on two tabs. It now
                        // opens that list, which until this change nothing in the app could
                        // reach: the count was truncated to 0, so the door was invisible.
                        //
                        // The CHANNEL, because rose means *needs you* and most of these are
                        // deliberate — a bin unpacked for the season. This is a
                        // cross-reference, which is what electric blue is for.
                        // "No bin", not "Not in a bin": at a third of the width the longer
                        // phrase wraps to two lines and makes this tile taller than the two
                        // beside it. The screen it opens says the whole sentence.
                        StatTile(
                            "No bin",
                            state.notInABin.toString(),
                            channel = colors.search.base,
                            onClick = onOpenNotInABin,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                // Browse — the third entry point (§1), only now built. Used categories only:
                // the eleven empty seeded rows as chips would reproduce the picker clutter
                // this feature removes. Server order (most-used first), FlowRow so nothing is
                // ever clipped at the screen edge, no cap — used-only bounds the set.
                if (state.usedCategories.isNotEmpty()) {
                    item { SectionHeader(label = "Browse", channel = colors.search.base) }
                    item {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(spacing.xs),
                        ) {
                            state.usedCategories.forEach { category ->
                                AssistChip(
                                    onClick = { onOpenCategory(category.id, category.name) },
                                    label = {
                                        Text(
                                            listOfNotNull(
                                                category.icon,
                                                category.name,
                                                "· ${category.itemCount}",
                                            ).joinToString(" ")
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // The near-miss state: nothing exact, but the server's trigram fallback has
            // candidates (never offline — the cache has no trigram index to be close with).
            // The "Results" header is suppressed for it: a header announcing results over zero
            // rows, stacked on the "Close matches" header, would be furniture, and the caption
            // below already says what the empty state would have said.
            val closeOnly = state.searched && state.results.isEmpty() && state.close.isNotEmpty()

            if (state.searched && !closeOnly) {
                item {
                    SectionHeader(
                        label = if (state.offline) "Results · offline" else "Results",
                        channel = if (state.offline) colors.attention.base else colors.search.base,
                    )
                }
                if (state.offline) {
                    item {
                        // Said plainly rather than hidden: offline results come from a simpler
                        // match than the server's, so presenting them identically would quietly
                        // teach that search is inconsistent.
                        Caption(text = "From the last sync — simpler matching than online")
                    }
                }
            }

            if (state.searched && state.results.isEmpty() && state.close.isEmpty() &&
                !state.searching
            ) {
                item {
                    EmptyState(
                        icon = Icons.Filled.Search,
                        title = "Nothing matches “${state.query}”",
                        subtitle = "Try fewer words, or check it was ever catalogued.",
                    )
                }
            }

            items(state.results, key = { it.id }) { item ->
                SearchHitRow(item = item, onClick = { onOpenItem(item) })
            }

            if (closeOnly) {
                item { SectionHeader(label = "Close matches", channel = colors.search.base) }
                item {
                    // A near-miss must say it is one: rendered as ordinary results, "wellies"
                    // for "welles" would quietly teach that search returns things nobody typed.
                    Caption(
                        text = "Nothing matches “${state.query}” exactly — these are spelled " +
                            "almost the same.",
                    )
                }
                items(state.close, key = { "close-${it.id}" }) { item ->
                    SearchHitRow(item = item, onClick = { onOpenItem(item) })
                }
            }
        }
    }
}

/**
 * One hit: what it is, which bin, and where that bin is.
 *
 * The bin and location are on the row rather than a tap away because that IS the answer — making
 * someone open a detail screen to learn it would turn a one-glance question into two taps.
 */
@Composable
internal fun SearchHitRow(item: ItemDto, onClick: () -> Unit) {
    val colors = ToteTheme.colors
    val spacing = ToteTheme.spacing

    PanelCard(onClick = onClick, channel = if (item.isOverdue) colors.attention.base else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // A search result is being matched against a memory of the object, not read.
            ItemThumbnail(item)
            Spacer(Modifier.width(spacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    if (item.quantity > 1) "${item.name} ×${item.quantity}" else item.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(spacing.xs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The bin as a swatch, not a code in grey text: "the red one" is matched
                    // by sight before A14 is ever read, and the answer to "which bin" should
                    // look like the bin. Absent when the item is in none — there is no object
                    // to miniature, and the words below already say what the state is.
                    if (item.toteCode != null) {
                        ToteGlyph(code = item.toteCode, colorHex = item.toteColorHex, compact = true)
                        Spacer(Modifier.width(spacing.sm))
                    }
                    val where = when {
                        // The glyph carries the code now; the words carry the place.
                        item.toteCode != null -> item.locationName
                        // An item with no bin is a normal state, not an error — say what it is.
                        item.status == "loaned" -> "Lent out"
                        item.status == "out" -> "Out of its tote"
                        else -> "Not in a tote"
                    }
                    // The size rides on the same line as the bin rather than earning a row of
                    // its own: the question is "which bin", and a second line would compete
                    // with the answer. Shown verbatim — this is the tag's own words.
                    val caption = listOfNotNull(where, item.apparel?.sizeRaw).joinToString(" · ")
                    if (caption.isNotEmpty()) {
                        Caption(text = caption)
                    }
                }
            }
            if (item.isOverdue) {
                Spacer(Modifier.width(spacing.sm))
                Icon(
                    Icons.Filled.Inventory2,
                    contentDescription = "Overdue",
                    tint = colors.attention.base,
                )
            }
        }
    }
}

/**
 * The seasonal invitation: the bins that were unpacked around this time last year, surfaced
 * before anyone has to remember they exist. Slate, not attention — this is tote identity
 * speaking ("your Christmas bins"), and nothing here is late or wrong.
 */
/**
 * A row of bin swatches, each opening its bin, with an honest overflow mark.
 *
 * Both cards count household-wide and show a capped handful of bins, so without the "+N" the
 * sentence and the swatches describe different sets — "58 items" over six glyphs when the items
 * live across nine bins sends somebody to count the wrong shelf.
 */
@Composable
private fun ToteGlyphRow(
    totes: List<SeasonalToteDto>,
    toteCount: Int,
    onOpenTote: (String) -> Unit,
) {
    val spacing = ToteTheme.spacing
    FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
        totes.forEach { tote ->
            Box(
                Modifier
                    // The glyph itself is 42dp compact; the tap target must not be.
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .clickable { onOpenTote(tote.id) },
                contentAlignment = Alignment.Center,
            ) {
                ToteGlyph(code = tote.code, colorHex = tote.colorHex, compact = true)
            }
        }
        if (toteCount > totes.size) {
            Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                Text(
                    "+${toteCount - totes.size}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SeasonalCard(card: SeasonalCardDto, onOpenTote: (String) -> Unit) {
    val colors = ToteTheme.colors
    val spacing = ToteTheme.spacing

    PanelCard(channel = colors.slate.base) {
        Text(
            card.categoryName ?: "Seasonal bins",
            style = MaterialTheme.typography.titleMedium,
            color = colors.slate.base,
        )
        Spacer(Modifier.height(spacing.xs))
        // Body text, not Caption — same reason as the overdue card: this is a sentence, and
        // Pulse's caption is upper-cased and letter-spaced, which is right for a label.
        Text(
            buildString {
                append("Last year you unpacked these on ${formatDay(card.unpackedOn)}.")
                card.locationName?.let { append(" They're in $it.") }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(spacing.sm))
        // The bins themselves, as swatches: the card's job is to be matched against a memory
        // of coloured boxes on an attic shelf, and a list of codes cannot do that. Each one
        // opens its bin now — the card named the boxes and then went nowhere.
        ToteGlyphRow(card.totes, card.toteCount, onOpenTote)
        Spacer(Modifier.height(spacing.xs))
        Caption(text = "${card.itemCount} items")
    }
}

/**
 * The wearer closest to outgrowing what they wear, and where the next size already waits.
 * Provenance — the ladder's channel, same as the size mark on an item row. The whole card is
 * the door: it opens the person screen, which holds the full fits list this summarises.
 */
@Composable
private fun NextSizeCard(
    card: NextSizeCardDto,
    onOpenPerson: (String) -> Unit,
    onOpenTote: (String) -> Unit,
) {
    val colors = ToteTheme.colors
    val spacing = ToteTheme.spacing

    PanelCard(onClick = { onOpenPerson(card.personId) }, channel = colors.provenance.base) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${card.personName} is nearly into",
                style = MaterialTheme.typography.titleMedium,
                color = colors.provenance.base,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(spacing.sm))
            // The label as a mono mark rather than words in the sentence — the same voice as
            // the size on an item row, because it is the same kind of fact.
            Text(
                card.nextLabel,
                style = ToteTheme.dataType.dataMedium,
                color = colors.provenance.base,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(spacing.xs))
        // The number is ATTRIBUTED to the size it counted. It used to read "58 garments already
        // catalogued" beside a label naming a different rung entirely, so the sentence and the
        // mark above it described two different piles and nothing on screen said which was
        // which. Naming the tag inside the sentence makes the count checkable against a bin.
        Text(
            "${card.garmentCount} garments tagged ${card.nextLabel} are already in bins — " +
                "one trip.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(spacing.sm))
        // The swatches are the sharper action: the card body opens the person (whose fits list
        // answers a deliberately wider question), while a glyph opens a bin that actually holds
        // some of the garments just counted.
        ToteGlyphRow(card.totes, card.toteCount, onOpenTote)
    }
}

/**
 * `2025-11-28` → "November 28". The sentence around it already says which year, and a month
 * name reads at a glance where an ISO date has to be decoded. Anything unparseable renders
 * verbatim — a raw date is still a date, and a formatting crash would take the card with it.
 */
private fun formatDay(iso: String): String =
    runCatching {
        LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("MMMM d", Locale.getDefault()))
    }.getOrDefault(iso)

/**
 * `2026-08-01` → "Aug 1", for dates that sit inside a running sentence.
 *
 * Shorter than [formatDay] because this one appears mid-line after a person's name, where a
 * full month name pushes the row to wrap. The copy voice is warm everywhere else in the app and
 * an ISO date in the middle of it reads like a debug build. Same forgiving shape: an
 * unparseable string renders verbatim rather than taking the card down with it.
 */
private fun formatDue(iso: String?): String =
    iso?.let {
        runCatching {
            LocalDate.parse(it).format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()))
        }.getOrDefault(it)
    } ?: "no date"

@Preview(name = "Search — results, dark")
@Composable
private fun SearchPreview() {
    ToteTheme(darkTheme = true) {
        SearchContent(
            state = SearchUiState(
                query = "ratchet",
                searched = true,
                results = listOf(
                    ItemDto(
                        id = "1", name = "Ratchet set", quantity = 1, status = "stored",
                        toteCode = "A14", locationName = "Attic",
                    ),
                ),
            ),
            onQueryChange = {},
            onOpenItem = {},
        )
    }
}
