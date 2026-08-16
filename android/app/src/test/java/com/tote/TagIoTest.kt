package com.tote

import com.tote.nfc.TagIo
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The pure half of the NFC path.
 *
 * Reading and writing a physical tag cannot be tested without hardware — not on CI, not on an
 * emulator — so the parts that CAN be tested carry more weight than usual. Parsing is where a
 * silent bug would strand a real tag in an attic with no way to tell it was ever wrong.
 */
class TagIoTest {

    @Test
    fun `a code is extracted from the tag URI`() {
        assertEquals(
            "A14",
            TagIo.codeFromUri("https://dragonfly.tail2ce561.ts.net:8448/t/A14"),
        )
    }

    @Test
    fun `the host and port are not baked into parsing`() {
        // A tag written before the URL changed must still resolve. The server honours /t/<code>
        // regardless of host precisely so that already-written tags survive a move, and the
        // client parser has to be equally forgiving or it defeats that.
        assertEquals("A14", TagIo.codeFromUri("https://somewhere-else.example/t/A14"))
        assertEquals("A14", TagIo.codeFromUri("http://192.168.4.34:8008/t/A14"))
    }

    @Test
    fun `trailing path and query are ignored`() {
        assertEquals("A14", TagIo.codeFromUri("https://h/t/A14/"))
        assertEquals("A14", TagIo.codeFromUri("https://h/t/A14?from=qr"))
    }

    @Test
    fun `a URI that is not one of ours yields null`() {
        // Phones tap all sorts of tags. A transit card or a poster must not open a random bin.
        assertNull(TagIo.codeFromUri("https://example.com/products/A14"))
        assertNull(TagIo.codeFromUri("tel:+15550100"))
        assertNull(TagIo.codeFromUri(null))
    }

    @Test
    fun `an empty code yields null rather than an empty lookup`() {
        assertNull(TagIo.codeFromUri("https://h/t/"))
        assertNull(TagIo.codeFromUri("https://h/t//"))
    }

    @Test
    fun `the cached summary stays small enough for a cheap tag`() {
        // NTAG213 — the commonest sticker — holds about 130 bytes of NDEF. The URI record must
        // always fit, so the summary has to stay well under that even with a long label and
        // location. If this grows, cheap tags silently lose the half that makes them work.
        val summary = TagIo.summaryFor(
            code = "A14",
            label = "Christmas decorations and the outdoor lights",
            location = "Attic, above the stairs, left side",
            itemCount = 37,
        )
        assertTrue(
            summary.toByteArray().size < 100,
            "summary is ${summary.toByteArray().size} bytes: $summary",
        )
    }

    @Test
    fun `the summary leads with the code`() {
        // On a phone without Tote, the stock NFC reader shows this text and nothing else. The
        // code is the only part that identifies the bin, so it goes first.
        assertTrue(TagIo.summaryFor("A14", "Christmas", "Attic", 37).startsWith("TOTE A14"))
    }

    @Test
    fun `the summary survives missing label and location`() {
        val summary = TagIo.summaryFor("B02", null, null, 0)
        assertTrue(summary.contains("B02"))
        assertTrue(summary.contains("0 items"))
    }

    @Test
    fun `one item is not pluralised`() {
        assertTrue(TagIo.summaryFor("C03", null, null, 1).endsWith("1 item"))
    }
}
