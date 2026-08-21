package com.tote.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tote.data.remote.ItemDto
import com.tote.ui.theme.ToteTheme
import design.pulse.ui.components.PanelCard

/**
 * One item, wherever it is listed.
 *
 * Shared rather than private to the bin screen because the same row now appears in three places
 * — a bin's contents, a bin's out-list, and "Not in a bin" on the Totes tab — and they had
 * drifted. The Totes tab drew its own stripped-down version with no photograph and no
 * description, which is exactly the list where two rows are hardest to tell apart: no bin, no
 * location, and six things honestly called "Onesie 12m".
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ItemRow(
    item: ItemDto,
    actionLabel: String,
    onAction: () -> Unit,
    onOpen: (() -> Unit)? = null,
    /** Null when the screen is not selecting; true/false when it is. */
    selected: Boolean? = null,
    onToggle: () -> Unit = {},
    onLongPress: () -> Unit = {},
    /** False when a heading immediately above already carries the size. */
    showSize: Boolean = true,
    /**
     * True inside "Out of this tote", where "Out since it was unpacked" is the section's own
     * title repeated on every row. A loan still speaks — that one names a person.
     */
    suppressRoutineStatus: Boolean = false,
) {
    val colors = ToteTheme.colors
    val spacing = ToteTheme.spacing

    // ONE clickable, on the Row, carrying both gestures.
    //
    // It was two: `PanelCard(onClick = …)` for the tap and a `combinedClickable` on the Row for
    // the long press. The inner one wins — it lies on top of the Surface's content and consumes
    // the pointer — so with `onClick = {}` every tap was swallowed and **nothing opened the item
    // sheet from a bin**, while long-press still worked, which is what made it look like only
    // half the screen was broken. Reported from use.
    //
    // Hence `onClick = null` on the PanelCard: a second clickable Surface underneath cannot be
    // reached and would only re-create the same trap. The card's own padding moves inside the
    // Row so the whole panel stays tappable rather than just the content within it.
    val tap: (() -> Unit)? = when {
        // While selecting, a tap ticks rather than opens. Two meanings for one gesture on the
        // same screen would make every tap a gamble.
        selected != null -> onToggle
        else -> onOpen
    }
    PanelCard(
        onClick = null,
        contentPadding = 0.dp,
        channel = when {
            selected == true -> colors.slate.base
            item.isOverdue -> colors.attention.base
            else -> null
        },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(
                    if (tap != null) {
                        Modifier.combinedClickable(onClick = tap, onLongClick = onLongPress)
                    } else {
                        Modifier
                    }
                )
                .padding(spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected != null) {
                Checkbox(checked = selected, onCheckedChange = { onToggle() })
                Spacer(Modifier.width(spacing.sm))
            }
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
                    item.status == "out" && !suppressRoutineStatus -> "Out since it was unpacked"
                    else -> null
                }
                // The description, on the row at last.
                //
                // It was carried on every DTO and shown only inside the item sheet, which is fine
                // until a bin holds six garments all honestly called "Shirt". As text those rows
                // were identical and only the thumbnail told them apart; the sentence that DOES
                // tell them apart — "yellow and green construction digger" — was one tap away on
                // each of them. Found on the owner's first real bin of baby clothes.
                //
                // Two lines, for the same reason the name gets two: the thumbnail, the size and the
                // action leave this column about twenty characters wide, and one line of that is
                // "Navy blue sleeves, li…" against "Navy blue with white s…" — which is the failure
                // this line exists to prevent, just moved a few words later. It only costs height
                // on the rows whose descriptions are long enough to need it.
                val detail = listOfNotNull(status, item.description).joinToString(" · ")
                if (detail.isNotEmpty()) {
                    Spacer(Modifier.height(spacing.xs))
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // The size, as its own mark rather than the first word of a caption.
            //
            // For a bin of children's clothing this is the single most-read fact on the screen —
            // the whole app exists to answer "which bin has the 4T coats" — and it was competing
            // with the loan status in a dim grey run-on line. Prominent enough here that the size
            // does not need repeating in the name, which is what was happening: "Shirt 12m" over
            // a caption reading "12M".
            item.apparel?.sizeRaw?.takeIf { showSize }?.let { size ->
                Spacer(Modifier.width(spacing.sm))
                Text(
                    size,
                    style = ToteTheme.dataType.dataSmall,
                    color = colors.provenance.base,
                    maxLines = 1,
                )
            }

            // ONE action on the row — the everyday one. Lending and deleting live in the
            // sheet behind a tap: a second button here costs the name the width it needs, and
            // a destructive action next to an everyday one is a mis-tap away from deleting a
            // photograph that cannot be retaken.
            // Blank means none: the category browse rows have no everyday action — the tap
            // opens the sheet, which owns all the verbs — and an empty pill would render as a
            // mystery button.
            if (selected == null && actionLabel.isNotEmpty()) {
                ToteButton(text = actionLabel, onClick = onAction, tonal = true, compact = true)
            }
        }
    }
}

