package com.tote.ui.components

import com.tote.data.remote.CategoryDto

/**
 * THE mapping from a category to a picker option — all four category pickers use this one
 * function (capture, review, item sheet, manual add), which is what makes the ordering and the
 * icons a single change.
 *
 * Two rules live here rather than in any screen:
 *
 * - **The server owns the order.** `GET /categories` returns most-used-first (empty seeded
 *   categories sink), and no call site sorts. A client-side sort would be the drift: four
 *   screens, four chances to disagree about what "first" means.
 * - **The count is the detail line.** "43 items" beside a category is the ordering made
 *   legible — without it, used-first just looks unsorted to anyone who remembers the old
 *   alphabet. Zero is left blank rather than shouting "0 items" down the tail of the list.
 */
fun List<CategoryDto>.asPickerOptions(): List<PickerOption> = map { category ->
    PickerOption(
        id = category.id,
        label = category.name,
        detail = when {
            category.itemCount == 1 -> "1 item"
            category.itemCount > 1 -> "${category.itemCount} items"
            else -> null
        },
        icon = category.icon,
    )
}
