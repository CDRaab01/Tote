package com.tote.ui.totes

import androidx.lifecycle.SavedStateHandle
import com.tote.data.CardDownloader
import com.tote.data.CatalogRepository
import com.tote.data.local.CatalogDao
import com.tote.data.remote.ApiService
import com.tote.data.remote.ItemDto
import com.tote.data.remote.MoveRequest
import com.tote.data.remote.MovementDto
import com.tote.data.remote.ToteDetailDto
import com.tote.data.remote.TotePatch
import com.tote.util.FeedbackBus
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify

/**
 * The bin screen's ViewModel — the largest in the app, and until now the largest with no tests at
 * all. It owns nearly every write somebody makes while standing in front of an open bin.
 *
 * The assertions are about the two things neither a screenshot nor the compiler can see: what
 * exactly goes on the wire, and what the ledger will say about it a year from now.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ToteDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repo: CatalogRepository
    private lateinit var api: ApiService
    private lateinit var cards: CardDownloader
    private lateinit var bus: FeedbackBus
    private val said = mutableListOf<String>()

    private val lentDrill = ItemDto(
        id = "i9", name = "Cordless drill", quantity = 1,
        status = "loaned", loanedTo = "Dave",
    )
    private val unpackedLights = ItemDto(
        id = "i8", name = "Outdoor lights", quantity = 1, status = "out",
    )
    private val bin = ToteDetailDto(
        id = "t1", code = "A14", label = "Christmas decor", archived = false,
        items = listOf(ItemDto(id = "i1", name = "Pre-lit tree", quantity = 1, status = "stored")),
        itemsOut = listOf(lentDrill, unpackedLights),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo = mock()
        api = mock()
        cards = mock()
        bus = FeedbackBus()
        said.clear()
        CoroutineScope(dispatcher).launch { bus.messages.collect { said += it } }
        repo.stub {
            onBlocking { tote(any()) } doReturn bin
            onBlocking { move(any(), any()) } doReturn
                MovementDto(id = "m1", itemId = "i1", reason = "x", movedAt = "2026-08-17T00:00:00Z")
        }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(): ToteDetailViewModel {
        val dao = mock<CatalogDao>().stub { on { totes() } doReturn flowOf(emptyList()) }
        return ToteDetailViewModel(
            repo, api, bus, cards, dao,
            SavedStateHandle(mapOf("toteId" to "t1")),
        )
    }

    private fun ready(): ToteDetailViewModel =
        vm().also { dispatcher.scheduler.advanceUntilIdle() }

    private suspend fun sentMove(itemId: String): MoveRequest {
        val body = argumentCaptor<MoveRequest>()
        verify(repo).move(eq(itemId), body.capture())
        return body.firstValue
    }

    // ---- the ledger's vocabulary ------------------------------------------------------------

    @Test
    fun `putting back something that was unpacked is a repack`() = runTest {
        val model = ready()
        model.putBack("i8")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("repacked", sentMove("i8").reason)
        assertEquals("t1", sentMove("i8").toToteId)
    }

    @Test
    fun `putting back something that was LENT OUT is a return, not a repack`() = runTest {
        // `returned` is in the server's inbound set and the item sheet already renders it as
        // "Returned into A14" — but only the person screen has ever sent it. From the bin, a lent
        // item sits under "Out of this tote" with a Put back button like anything else, so Dave
        // handing the drill back is recorded as though it had merely been unpacked and reshelved.
        // A year later, "who had this and did it come back" is unanswerable from the ledger that
        // exists to answer exactly that.
        val model = ready()
        model.putBack("i9")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("returned", sentMove("i9").reason)
    }

    // ---- bodies that must name every field ---------------------------------------------------

    @Test
    fun `editing a bin carries archived through unchanged`() = runTest {
        repo.stub { onBlocking { patchTote(any(), any()) } doReturn mock() }
        val model = ready()
        model.editTote(code = "A14", label = "Xmas", locationId = "L1", notes = null)
        dispatcher.scheduler.advanceUntilIdle()

        val body = argumentCaptor<TotePatch>()
        verify(repo).patchTote(eq("t1"), body.capture())
        // TotePatch has no defaults on purpose: encodeDefaults plus exclude_unset means a field
        // left out arrives as an explicit null and clears what it omitted.
        assertEquals(false, body.firstValue.archived)
        assertEquals("A14", body.firstValue.code)
    }

    @Test
    fun `archiving carries the code, which is NOT NULL on the server`() = runTest {
        repo.stub { onBlocking { patchTote(any(), any()) } doReturn mock() }
        val model = ready()
        model.setArchived(true)
        dispatcher.scheduler.advanceUntilIdle()

        val body = argumentCaptor<TotePatch>()
        verify(repo).patchTote(eq("t1"), body.capture())
        assertEquals("A14", body.firstValue.code)
        assertEquals(true, body.firstValue.archived)
    }

    // ---- selection ---------------------------------------------------------------------------

    @Test
    fun `unpack all means everything, which is null and never an empty list`() = runTest {
        repo.stub { onBlocking { unpack(any(), any()) } doReturn emptyList() }
        val model = ready()
        model.unpackAll()
        dispatcher.scheduler.advanceUntilIdle()

        // The server reads [] as "an explicit selection of nothing" — a silent no-op that looks
        // exactly like success.
        verify(repo).unpack(eq("t1"), eq(null))
    }

    @Test
    fun `unpacking a selection sends the ticked ids and clears the selection`() = runTest {
        repo.stub { onBlocking { unpack(any(), any()) } doReturn emptyList() }
        val model = ready()
        model.beginSelecting("i1")
        model.toggleSelected("i8")
        model.unpackSelected()
        dispatcher.scheduler.advanceUntilIdle()

        verify(repo).unpack(eq("t1"), eq(listOf("i1", "i8")))
        assertNull(model.selection.value)
        assertTrue(said.any { it.contains("2") }, "the count is the confirmation; said=" + said)
    }

    @Test
    fun `a tap while not selecting cannot tick anything`() = runTest {
        val model = ready()
        model.toggleSelected("i1")
        assertNull(model.selection.value, "null means not selecting; a tick must not start a mode")
    }

    @Test
    fun `bulk actions do nothing at all when nothing is ticked`() = runTest {
        val model = ready()
        model.beginSelecting(null)
        model.moveSelected("t2")
        model.bagSelected("c1")
        model.unpackSelected()
        dispatcher.scheduler.advanceUntilIdle()

        model.putBackSelected()
        dispatcher.scheduler.advanceUntilIdle()
        verify(repo, never()).bulkMove(any(), any(), any())
        verify(repo, never()).bulkBag(any(), any())
        verify(repo, never()).unpack(any(), any())
    }

    @Test
    fun `putting a selection back sends them to THIS bin, in one request`() = runTest {
        repo.stub { onBlocking { bulkMove(any(), any(), any()) } doReturn emptyList() }
        val model = ready()
        model.beginSelecting("i8")
        model.toggleSelected("i9")
        model.putBackSelected()
        dispatcher.scheduler.advanceUntilIdle()

        // bulkMove into this same tote rather than `repack`, because the server picks each item's
        // inbound reason from its own status — which is how the lent drill in this selection gets
        // `returned` and the unpacked lights get `repacked`. One request, so one reload: thirty
        // Put back taps used to be thirty full re-reads of the bin, which is what made the list
        // move under your finger.
        verify(repo).bulkMove(eq(listOf("i8", "i9")), eq("t1"), eq(null))
        assertNull(model.selection.value)
    }

    // ---- failure is never silence ------------------------------------------------------------

    @Test
    fun `a failed write says so instead of looking like it worked`() = runTest {
        repo.stub {
            onBlocking { move(any(), any()) } doAnswer { throw java.io.IOException("no route") }
        }
        val model = ready()
        model.moveOut("i1")
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(said.isNotEmpty(), "offline in the attic is the documented normal condition")
    }

    @Test
    fun `deleting the bin only navigates away once the server agreed`() = runTest {
        repo.stub {
            onBlocking { deleteTote(any()) } doAnswer { throw java.io.IOException("no route") }
        }
        var gone = false
        val model = ready()
        model.deleteTote { gone = true }
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(!gone, "leaving the screen on a failure reads exactly like success")
        assertTrue(said.isNotEmpty())
    }
}
