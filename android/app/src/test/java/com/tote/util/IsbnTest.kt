package com.tote.util

import kotlin.test.assertEquals
import org.junit.Test

/** Mirrors the server's `is_valid_isbn13` table — the two gates must agree or a code the
 *  client passes gets a 422 the session list has no good sentence for. */
class IsbnTest {

    private val cases = listOf(
        "9780140328721" to true,
        "978-0-14-032872-1" to true, // hyphens are how humans type them
        "9791234567896" to true, // 979 Bookland
        "9780140328722" to false, // bad checksum
        "9771234567898" to false, // 977 = periodicals
        "5012345678900" to false, // ordinary product EAN — the soup can
        "978014032872" to false, // 12 digits
        "" to false,
        "not a number" to false,
    )

    @Test
    fun `matches the server's table`() {
        for ((code, expected) in cases) {
            assertEquals(expected, isBookEan13(code), "isBookEan13($code)")
        }
    }
}
