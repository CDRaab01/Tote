package com.tote.ui.totes

import com.tote.data.remote.ApparelDto
import com.tote.data.remote.ItemDto
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

/**
 * How an unpacked bin's contents are grouped for reading.
 *
 * The whole reason this is not a plain `sortedBy(name)`: **alphabetically, 12m comes before 6m.**
 * A bin emptied onto the floor sorted that way is the confusion the size ladder exists to remove,
 * reintroduced one layer up in the UI. The order here is the server's `size_ordinal` — displayed,
 * never computed, because the ladder has exactly one implementation and it is not the client.
 */
class OutBySizeTest {

    private fun garment(id: String, name: String, size: String?, ordinal: Float?) = ItemDto(
        id = id,
        name = name,
        quantity = 1,
        status = "out",
        apparel = size?.let { ApparelDto(sizeRaw = it, sizeOrdinal = ordinal) },
    )

    @Test
    fun `groups run up the ladder, not up the alphabet`() {
        val out = listOf(
            garment("a", "Swim shirts", "18m", 1.5f),
            garment("b", "Shorts", "6m", 0.5f),
            garment("c", "Onesie", "12m", 1.0f),
        )

        assertEquals(listOf("6m", "12m", "18m"), outBySize(out).map { it.first })
    }

    @Test
    fun `things with no size go last, under their own heading`() {
        val out = listOf(
            garment("a", "Cordless drill", null, null),
            garment("b", "Shorts", "6m", 0.5f),
            garment("c", "Onesie", "12m", 1.0f),
        )

        val groups = outBySize(out)
        // Not scattered through the sized ones, and not silently dropped: a bin of clothes with a
        // drill in it is an ordinary bin, and the drill still has to be findable.
        assertEquals(listOf("6m", "12m", null), groups.map { it.first })
        assertEquals("Cordless drill", groups.last().second.single().name)
    }

    @Test
    fun `one size is left flat, because a heading that contrasts with nothing is noise`() {
        val out = listOf(
            garment("a", "Shorts", "6m", 0.5f),
            garment("b", "Onesie", "6m", 0.5f),
        )

        val groups = outBySize(out)
        assertEquals(1, groups.size)
        assertNull(groups.single().first, "a lone group carries no heading")
        assertEquals(2, groups.single().second.size)
    }

    @Test
    fun `a bin with no clothing in it is left flat too`() {
        val out = listOf(
            garment("a", "Pre-lit tree", null, null),
            garment("b", "Ornament box", null, null),
        )

        assertEquals(listOf<String?>(null), outBySize(out).map { it.first })
    }

    @Test
    fun `within a size, items read alphabetically`() {
        val out = listOf(
            garment("a", "Shorts", "12m", 1.0f),
            garment("b", "Onesie", "12m", 1.0f),
            garment("c", "Bib", "6m", 0.5f),
        )

        assertEquals(
            listOf("Onesie", "Shorts"),
            outBySize(out).first { it.first == "12m" }.second.map { it.name },
        )
    }
}
