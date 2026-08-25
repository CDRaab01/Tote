package com.tote.ui.totes

import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

/**
 * When a verified bin stops counting as verified.
 *
 * One rule, read by three screens — the bins list's rose caption, the bin screen's verify line
 * and the verify screen's own context card — so it lives in one place and is asserted at its
 * edges. Both edges matter and they fail in opposite directions: too eager and every bin in the
 * attic wears the attention channel every summer, which teaches people to ignore it; too lax and
 * the mark never appears at all.
 */
class StaleBinTest {

    private val today = LocalDate.of(2026, 8, 25)

    @Test
    fun `a bin nobody ever verified has no age at all`() {
        // Null, not zero: "verified this month" is a claim, and inventing it for a bin filed
        // before the feature existed would be exactly the quiet lie verifying exists to remove.
        assertNull(monthsSince(null, today))
    }

    @Test
    fun `a date this client cannot read is not turned into a number`() {
        assertNull(monthsSince("last Christmas", today))
        assertNull(monthsSince("", today))
    }

    @Test
    fun `the day part is enough — the server's full stamp reads the same as a bare date`() {
        assertEquals(
            monthsSince("2026-02-25", today),
            monthsSince("2026-02-25T14:03:11.518Z", today),
        )
    }

    @Test
    fun `twelve months is not yet stale, thirteen is`() {
        // A year exactly is the seasonal case: a bin opened every Christmas is checked every
        // Christmas, and it should not go rose on the way to the attic.
        assertEquals(12L, monthsSince("2025-08-25", today))
        assertEquals(false, monthsSince("2025-08-25", today)!! > STALE_AFTER_MONTHS)
        assertEquals(true, monthsSince("2025-07-25", today)!! > STALE_AFTER_MONTHS)
    }
}
