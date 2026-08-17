package com.tote.ui.components

import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import com.tote.ui.theme.ToteTheme

/**
 * The controlled vocabularies an item's editable fields draw from, and the chip that renders them.
 *
 * Lifted out of the review screen the moment a second screen needed them. Review was the only
 * place an item could be described, so its private copies were fine; now the item sheet edits the
 * same columns after filing, and two independent lists of the conditions the server accepts is a
 * 422 waiting to be written — the sort that surfaces months later on one screen only.
 *
 * These mirror the server's `ITEM_CONDITIONS` and apparel departments. They are short and fixed,
 * which is exactly the case where chips beat a picker: five options that never grow read as a set
 * and are compared side by side rather than looked up.
 */

/** The conditions the server accepts, in the order a person would rank them. */
internal val CONDITIONS = listOf("new", "like_new", "good", "fair", "poor")

internal fun conditionLabel(value: String) = when (value) {
    "new" -> "New"
    "like_new" -> "Like new"
    "good" -> "Good"
    "fair" -> "Fair"
    "poor" -> "Poor"
    else -> value
}

/** The departments the server accepts, in the order a household picks from them. */
internal val DEPARTMENTS = listOf("girls", "boys", "womens", "mens", "unisex")

internal fun departmentLabel(value: String) = when (value) {
    "girls" -> "Girls"
    "boys" -> "Boys"
    "womens" -> "Women's"
    "mens" -> "Men's"
    "unisex" -> "Unisex"
    else -> value
}

/** Selection in Tote's own channel — see the note in the capture screen's chip. */
@Composable
internal fun SlateChip(selected: Boolean, label: String, onClick: () -> Unit) {
    val slate = ToteTheme.colors.slate
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = slate.dim,
            selectedLabelColor = slate.base,
        ),
    )
}
