package com.tote.ui.totes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.tote.data.remote.ItemDto
import com.tote.data.remote.PhotoUrls
import com.tote.ui.theme.ToteTheme

/**
 * One item in a bin, as a photograph with a caption — the bin screen's grid cell.
 *
 * The bin screen used to list its contents as [com.tote.ui.components.ItemRow]s, which lead with
 * a 52dp thumbnail and carry the photograph as an afterthought. But a bin's contents are
 * recognised by SIGHT — someone scrolling this screen is matching pictures against a memory of
 * the thing — and the photographs are the one part of the catalogue that cost real work to
 * capture. The cell inverts the row: the photo takes the full column width and the words caption
 * it. `ItemRow` stays as-is everywhere an item is listed *across* bins (search, unfiled, a
 * person's fits), where the tote code and location matter more than the picture is big.
 *
 * Gesture rules carried over from the row, verbatim, because both shipped as bugs there:
 * **one `combinedClickable` on one modifier** carries tap and long-press (a second clickable
 * underneath can never be reached), and while selecting a tap ticks rather than opens.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ItemCell(
    item: ItemDto,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    /** The everyday verb, or blank for none. Quiet text, not a pill — the cell is the button. */
    actionLabel: String = "",
    onAction: () -> Unit = {},
    /** True inside "Out of this tote": dims the photo and adds the Out mark. */
    out: Boolean = false,
    /** Null when the screen is not selecting; true/false when it is. */
    selected: Boolean? = null,
    onToggle: () -> Unit = {},
    onLongPress: () -> Unit = {},
    /** False when a heading immediately above already carries the size. */
    showSize: Boolean = true,
    /** True where "out since it was unpacked" is the section title repeated on every cell. */
    suppressRoutineStatus: Boolean = false,
) {
    val colors = ToteTheme.colors
    val spacing = ToteTheme.spacing
    val shape = MaterialTheme.shapes.medium

    val tap: () -> Unit = if (selected != null) onToggle else onOpen

    Column(
        modifier
            .clip(shape)
            .combinedClickable(onClick = tap, onLongClick = onLongPress),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(shape)
                .background(colors.panelHigh)
                .then(
                    if (selected == true) {
                        Modifier.border(2.dp, colors.slate.base, shape)
                    } else {
                        Modifier
                    }
                ),
        ) {
            // The photograph, or the same placeholder the row used — constant cell size either
            // way, because a grid that reflows as images resolve cannot be scanned.
            if (item.photoCount > 0) {
                AsyncImage(
                    model = PhotoUrls.item(item.id, 0),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(spacing.sm)
                        .alpha(if (out) 0.55f else 1f),
                )
            } else {
                Icon(
                    Icons.Outlined.Inventory2,
                    contentDescription = null,
                    tint = colors.hairlineStrong,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .alpha(if (out) 0.55f else 1f),
                )
            }
            if (selected != null) {
                // Redundant with the cell tap on purpose: the tick is the affordance that says
                // "selecting is happening", not a second gesture surface.
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggle() },
                    modifier = Modifier.align(Alignment.TopStart),
                )
            } else if (out) {
                Text(
                    "Out",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.attention.base,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(spacing.sm)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(colors.attention.base.copy(alpha = 0.16f))
                        .padding(horizontal = spacing.sm, vertical = 3.dp),
                )
            }
            if (item.quantity > 1) {
                // On the photo rather than appended to the name, so the name keeps its width —
                // scrim-dark with white text because it sits over a photograph in both themes.
                Text(
                    "×${item.quantity}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(spacing.sm)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = spacing.sm, vertical = 3.dp),
                )
            }
        }
        Spacer(Modifier.height(spacing.xs))
        Text(
            item.name,
            style = MaterialTheme.typography.titleSmall,
            color = if (out) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val status = when {
            item.isOverdue ->
                "Overdue — ${item.loanedTo ?: "someone"} has had it since ${item.expectedBack}"
            item.status == "loaned" -> "Lent to ${item.loanedTo ?: "someone"}"
            item.status == "out" && !suppressRoutineStatus -> "Out since it was unpacked"
            else -> null
        }
        val detail = listOfNotNull(status, item.description).joinToString(" · ")
        if (detail.isNotEmpty()) {
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (item.isOverdue) colors.attention.base else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val size = item.apparel?.sizeRaw?.takeIf { showSize }
        val showAction = selected == null && actionLabel.isNotEmpty()
        if (size != null || showAction) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (size != null) {
                    Text(
                        size,
                        style = ToteTheme.dataType.dataSmall,
                        color = colors.provenance.base,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (showAction) {
                    // Quiet text, not a tonal pill: the everyday verb should not outshout the
                    // photograph it belongs to, and ten of them must not read as a wall.
                    TextButton(
                        onClick = onAction,
                        contentPadding = ButtonDefaults.TextButtonContentPadding,
                    ) { Text(actionLabel) }
                }
            }
        }
    }
}
