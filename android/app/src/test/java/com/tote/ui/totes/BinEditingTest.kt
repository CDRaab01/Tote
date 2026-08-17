package com.tote.ui.totes

import androidx.lifecycle.SavedStateHandle
import com.tote.data.CatalogRepository
import com.tote.data.local.CatalogDao
import com.tote.data.local.CachedTote
import com.tote.data.remote.ApiService
import com.tote.data.remote.ToteDetailDto
import com.tote.data.remote.ToteDto
import com.tote.data.remote.TotePatch
import com.tote.util.FeedbackBus
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify

/**
 * Editing the bin itself.
 *
 * The behaviour worth a test rather than a screenshot is the **shape of the PATCH body**. It is
 * the same trap the item sheet documents: `encodeDefaults` is on, so anything omitted is sent as
 * an explicit null, and the server reads a present null as "clear this". A `TotePatch` built from
 * defaults would set `code` null against a NOT NULL column — so every write here has to carry the
 * fields it is not changing, and the two writes that are *not* edits (archive, unarchive) have to
 * carry all of them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BinEditingTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repo: CatalogRepository
    private lateinit var api: ApiService

    private val bin = ToteDetailDto(
        id = "t1",
        code = "A14",
        label = "Christmas decor",
        categoryId = "c1",
        locationId = "l1",
        locationName = "Attic",
        notes = "Second shelf",
        archived = false,
        itemCount = 3,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo = mock()
        api = mock()
        repo.stub {
            onBlocking { tote(any()) } doReturn bin
            onBlocking { patchTote(any(), any()) } doReturn ToteDto(id = "t1", code = "A14")
        }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(): ToteDetailViewModel = ToteDetailViewModel(
        repo,
        api,
        FeedbackBus(),
        mock(),
        mock<CatalogDao>().stub { on { totes() } doReturn flowOf(emptyList()) },
        SavedStateHandle(mapOf("toteId" to "t1")),
    )

    @Test
    fun `an edit carries every field, including the ones it is not changing`() = runTest {
        val model = vm()
        dispatcher.scheduler.advanceUntilIdle()

        model.editTote(code = "A14", label = "Christmas", locationId = "l2", notes = null)
        dispatcher.scheduler.advanceUntilIdle()

        val body = argumentCaptor<TotePatch>()
        verify(repo).patchTote(eq("t1"), body.capture())
        assertEquals("A14", body.firstValue.code)
        assertEquals("Christmas", body.firstValue.label)
        assertEquals("l2", body.firstValue.locationId)
        // Not touched by this form, and carried through rather than sent as a null that would
        // clear the bin's category.
        assertEquals("c1", body.firstValue.categoryId)
        // An edit must never quietly un-archive a bin.
        assertEquals(false, body.firstValue.archived)
        // Blank means cleared here, which is what a person emptying the field means.
        assertEquals(null, body.firstValue.notes)
    }

    @Test
    fun `archiving keeps the bin's identity intact`() = runTest {
        val model = vm()
        dispatcher.scheduler.advanceUntilIdle()

        model.setArchived(true)
        dispatcher.scheduler.advanceUntilIdle()

        val body = argumentCaptor<TotePatch>()
        verify(repo).patchTote(eq("t1"), body.capture())
        assertTrue(body.firstValue.archived)
        // The whole point: this is a one-field change and every other field still has to be here.
        assertEquals("A14", body.firstValue.code)
        assertEquals("Christmas decor", body.firstValue.label)
        assertEquals("l1", body.firstValue.locationId)
        assertEquals("Second shelf", body.firstValue.notes)
    }

    @Test
    fun `deleting the bin navigates away rather than sitting on a screen that no longer exists`() =
        runTest {
            val model = vm()
            dispatcher.scheduler.advanceUntilIdle()
            var gone = false

            model.deleteTote { gone = true }
            dispatcher.scheduler.advanceUntilIdle()

            verify(repo).deleteTote(eq("t1"))
            assertTrue(gone)
        }
}

/**
 * How the bin list is grouped.
 *
 * A flat alphabetical run of A14, A15, B02, G01 is a list of codes, and remembering codes is the
 * job the app exists to take away. Grouping by place turns it into "everything in the attic",
 * which is one of the three documented browse entry points.
 */
class ToteGroupingTest {

    private fun bin(code: String, place: String?) =
        CachedTote(code, code, null, null, place, 0, 0, false)

    @Test
    fun `bins group by place, alphabetically, with the placeless ones last`() {
        val groups = byLocation(
            listOf(
                bin("G01", "Garage rack B"),
                bin("X9", null),
                bin("A15", "Attic"),
                bin("A14", "Attic"),
            )
        )

        assertEquals(listOf("Attic", "Garage rack B", NO_LOCATION), groups.map { it.first })
        assertEquals(listOf("A14", "A15"), groups[0].second.map { it.code })
    }

    @Test
    fun `a placeless bin gets its own heading rather than being folded into a real place`() {
        val groups = byLocation(listOf(bin("A14", "Attic"), bin("X9", null)))
        // Folding it in would be a lie about where it is, and the loose end is exactly the thing
        // worth seeing — it is the bin nobody will find.
        assertEquals(1, groups.first { it.first == NO_LOCATION }.second.size)
    }
}
