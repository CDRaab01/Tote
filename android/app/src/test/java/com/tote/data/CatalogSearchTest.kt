package com.tote.data

import com.tote.data.local.CachedItem
import com.tote.data.local.CatalogDao
import com.tote.data.remote.ApiService
import com.tote.data.remote.ItemDto
import com.tote.data.remote.SearchHitDto
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.verifyBlocking

/**
 * How a search answers, online and off.
 *
 * The half that earns a test file: the offline fallback rebuilds [ItemDto] from the cache, and
 * it used to rebuild it WITHOUT the photo count and the size even though the cache carried both
 * — so exactly where rows are hardest to tell apart (no signal, similar names) they carried the
 * least to tell them apart by. The reconstruction now carries every field the cache holds, and
 * these tests are what keep the next new field from reopening the gap.
 */
class CatalogSearchTest {

    private lateinit var api: ApiService
    private lateinit var dao: CatalogDao

    private fun repo() = CatalogRepository(api, dao)

    private fun hit(id: String, close: Boolean) = SearchHitDto(
        item = ItemDto(id = id, name = "Wellies", status = "stored"),
        rank = 0.5f,
        closeMatch = close,
    )

    @Before
    fun setUp() {
        dao = mock()
        api = mock()
    }

    @Test
    fun `exact hits arrive as items, with close matches empty`() = runTest {
        api.stub {
            onBlocking { search(any(), anyOrNull(), any()) } doReturn listOf(hit("i1", close = false))
        }

        val result = repo().search("wellies")

        assertEquals(listOf("i1"), result.items.map { it.id })
        assertTrue(result.close.isEmpty())
        assertEquals(false, result.offline)
    }

    @Test
    fun `close matches arrive split out, never mixed into the exact list`() = runTest {
        // The server only sends close matches when full-text found nothing, so this is the
        // whole response — and the UI's "only when exact is empty" rule leans on the split.
        api.stub {
            onBlocking { search(any(), anyOrNull(), any()) } doReturn listOf(hit("i2", close = true))
        }

        val result = repo().search("welles")

        assertTrue(result.items.isEmpty())
        assertEquals(listOf("i2"), result.close.map { it.id })
    }

    @Test
    fun `a size filter is the server's to apply`() = runTest {
        api.stub {
            onBlocking { search(any(), anyOrNull(), any()) } doReturn emptyList()
        }

        repo().search("coat", size = "4T")

        verifyBlocking(api) { search(eq("coat"), eq("4T"), any()) }
    }

    @Test
    fun `the offline fallback carries everything the cache holds`() = runTest {
        api.stub {
            onBlocking { search(any(), anyOrNull(), any()) } doSuspendableAnswer {
                throw IOException("no route")
            }
        }
        dao.stub {
            onBlocking { search(any()) } doReturn listOf(
                CachedItem(
                    id = "i3",
                    name = "Winter coat",
                    description = null,
                    notes = null,
                    quantity = 1,
                    status = "stored",
                    currentToteId = "t1",
                    toteCode = "A14",
                    locationName = "Attic",
                    isOverdue = false,
                    sizeRaw = "4T",
                    photoCount = 2,
                    toteColorHex = "#B03030",
                ),
            )
        }

        val result = repo().search("coat")

        val item = result.items.single()
        assertEquals(true, result.offline)
        assertEquals(2, item.photoCount)
        assertEquals("4T", item.apparel?.sizeRaw)
        assertEquals("#B03030", item.toteColorHex)
        assertTrue(result.close.isEmpty(), "the cache has no trigram index to be close with")
    }
}
