package com.tote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tote.ui.theme.ToteTheme
import design.pulse.ui.components.Caption

/**
 * Choosing one thing out of a list that grows.
 *
 * Replaces a horizontally-scrolling chip strip, which is the wrong shape for this the moment the
 * list stops being tiny: the options run off the edge of the screen, you cannot see how many
 * there are, and finding "G07" among thirty bins means dragging sideways past twenty-nine others.
 * The catalog is *supposed* to grow to fourteen bins and beyond — that is the product — so the
 * picker has to scale with it.
 *
 * **Chips are still right for a short, fixed vocabulary** and are deliberately kept for
 * condition, department, and garment type: five options that never change are faster to compare
 * side by side than behind a tap, and they read as a set rather than a lookup. The rule is about
 * *user-grown* lists — bins, categories, people — not about chips being bad.
 *
 * A search field appears once there are enough options to be worth filtering; below that it is
 * chrome in the way of a five-item list.
 */
data class PickerOption(
    val id: String,
    val label: String,
    /** Secondary line — where a bin is, what a person's sizes are. Optional. */
    val detail: String? = null,
    /** A leading emoji, fixed-width so labels stay aligned. Categories carry one; bins don't. */
    val icon: String? = null,
)

/** The number of options past which the picker offers a search box. */
private const val SEARCH_THRESHOLD = 8

/** How tall the option list may get before it scrolls, leaving the dialog usable one-handed. */
private val LIST_MAX_HEIGHT = 340.dp

/**
 * The closed state: a tappable field showing what is currently chosen.
 *
 * Reads as a value, not a control, because most of the time it is being *read* — someone glancing
 * at the capture screen to confirm the bin is right before shooting twenty photographs into it.
 */
@Composable
fun PickerField(
    /** Omitted when the screen already names the field with a SectionHeader above it. */
    label: String? = null,
    selected: String?,
    placeholder: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ToteTheme.colors
    val spacing = ToteTheme.spacing

    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.panel)
            .clickable(onClick = onClick)
            .padding(horizontal = spacing.md, vertical = spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            if (label != null) {
                Caption(text = label)
                Spacer(Modifier.height(spacing.xs))
            }
            Text(
                selected ?: placeholder,
                style = MaterialTheme.typography.titleMedium,
                // The unchosen state is dimmer than a real value: a placeholder that looks like
                // a selection is how something gets filed into the wrong bin.
                color = if (selected != null) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(spacing.sm))
        Icon(
            Icons.Filled.UnfoldMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The open state: a vertical, searchable list.
 *
 * @param noneLabel when set, an explicit "no choice" row at the top ("Decide later", "No
 *   category"). It has to be a row rather than a second tap on the selected option, because
 *   discovering "tap it again to clear it" requires already knowing it.
 */
@Composable
fun PickerDialog(
    title: String,
    options: List<PickerOption>,
    selectedId: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
    /** Why this choice is being asked for. Shown above the list, not only when it is empty. */
    subtitle: String? = null,
    noneLabel: String? = null,
    searchHint: String = "Search",
    emptyMessage: String? = null,
) {
    var query by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            PickerList(
                options = options,
                selectedId = selectedId,
                onPick = onPick,
                query = query,
                onQueryChange = { query = it },
                subtitle = subtitle,
                noneLabel = noneLabel,
                searchHint = searchHint,
                emptyMessage = emptyMessage,
            )
        },
        // No confirm button: picking IS the confirmation. A list where every row is a decision
        // and there is still an OK to press is a list you have to tap twice for no reason.
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The dialog's body, separated from the dialog.
 *
 * Not decomposition for its own sake: an `AlertDialog` renders in its own window, and a
 * Robolectric screenshot of one never reaches idle — it times out after 60 s of composition
 * attempts. The list is the part with the layout worth verifying, so it is the part that can be
 * rendered on its own.
 */
@Composable
fun PickerList(
    options: List<PickerOption>,
    selectedId: String?,
    onPick: (String?) -> Unit,
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    subtitle: String? = null,
    noneLabel: String? = null,
    searchHint: String = "Search",
    emptyMessage: String? = null,
) {
    val spacing = ToteTheme.spacing
    val matches = remember(query, options) { matchOptions(options, query) }

    Column {
        if (subtitle != null) {
            Caption(text = subtitle)
            Spacer(Modifier.height(spacing.sm))
        }
        if (options.size >= SEARCH_THRESHOLD) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(searchHint) },
            )
            Spacer(Modifier.height(spacing.sm))
        }

        if (options.isEmpty() && emptyMessage != null) {
            Caption(text = emptyMessage)
        } else if (matches.isEmpty()) {
            Caption(text = "Nothing matches “$query”.")
        } else {
            LazyColumn(
                Modifier.heightIn(max = LIST_MAX_HEIGHT),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                if (noneLabel != null && query.isBlank()) {
                    item {
                        PickerRow(
                            label = noneLabel,
                            detail = null,
                            selected = selectedId == null,
                            onClick = { onPick(null) },
                        )
                    }
                }
                items(matches, key = { it.id }) { option ->
                    PickerRow(
                        label = option.label,
                        detail = option.detail,
                        selected = option.id == selectedId,
                        onClick = { onPick(option.id) },
                        icon = option.icon,
                    )
                }
            }
        }
    }
}

@Composable
private fun PickerRow(
    label: String,
    detail: String?,
    selected: Boolean,
    onClick: () -> Unit,
    icon: String? = null,
) {
    val colors = ToteTheme.colors
    val spacing = ToteTheme.spacing

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) colors.panelHigh else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = spacing.sm, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            // Fixed width, not intrinsic: emoji vary in advance width and a ragged left edge on
            // the labels reads as misalignment, not decoration.
            Text(icon, modifier = Modifier.width(32.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) colors.slate.base else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) {
                Caption(text = detail)
            }
        }
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = colors.slate.base)
        }
    }
}

/**
 * Which options a query matches.
 *
 * Pure and separate from the composable so it can be tested: the rule is that a query matches the
 * **detail line as well as the label**, because someone hunting for a bin thinks "the one in the
 * attic" as readily as they think "A15" — and a search that silently only looks at one of the two
 * is a search that appears to work until the day it does not.
 */
internal fun matchOptions(options: List<PickerOption>, query: String): List<PickerOption> =
    if (query.isBlank()) {
        options
    } else {
        options.filter {
            it.label.contains(query, ignoreCase = true) ||
                it.detail?.contains(query, ignoreCase = true) == true
        }
    }
