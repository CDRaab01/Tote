package com.tote.ui.components

import kotlin.test.assertEquals
import org.junit.Test

/**
 * The picker replaced a horizontally-scrolling chip strip, which stopped being usable the moment
 * the catalog grew — and growing is the product. What matters here is that finding a bin among
 * thirty works the way someone actually remembers it.
 */
class PickerTest {

    private val bins = listOf(
        PickerOption("t1", "A14 · Christmas decor", "Attic"),
        PickerOption("t2", "A15 · Winter 5T", "Attic"),
        PickerOption("t3", "G01 · Power tools", "Garage rack B"),
        PickerOption("t4", "D1 · Blankets"),
    )

    @Test
    fun `an empty query is every option, not none`() {
        assertEquals(bins, matchOptions(bins, ""))
        assertEquals(bins, matchOptions(bins, "   "))
    }

    @Test
    fun `matching is case-insensitive, because nobody types a bin code in caps`() {
        assertEquals(listOf("t3"), matchOptions(bins, "g01").map { it.id })
        assertEquals(listOf("t3"), matchOptions(bins, "G01").map { it.id })
    }

    @Test
    fun `the detail line matches too`() {
        // Someone hunting for a bin thinks "the one in the garage" as readily as "G01". A search
        // that only looked at the label would appear to work right up until it didn't.
        assertEquals(listOf("t3"), matchOptions(bins, "garage").map { it.id })
        assertEquals(listOf("t1", "t2"), matchOptions(bins, "attic").map { it.id })
    }

    @Test
    fun `a word from the middle of a label matches`() {
        // Prefix-only matching would mean "blankets" finds nothing, because every label starts
        // with a bin code — which is the one part someone is least likely to remember.
        assertEquals(listOf("t4"), matchOptions(bins, "blank").map { it.id })
    }

    @Test
    fun `no match is an empty list, not everything`() {
        assertEquals(emptyList(), matchOptions(bins, "banjo"))
    }
}
