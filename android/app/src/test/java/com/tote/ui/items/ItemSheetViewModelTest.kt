package com.tote.ui.items

import com.tote.data.CatalogRepository
import com.tote.data.local.CatalogDao
import com.tote.data.remote.ApiService
import com.tote.data.remote.ItemDto
import com.tote.data.remote.ItemUpdate
import com.tote.data.remote.MovementDto
import com.tote.util.FeedbackBus
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
 * The one place a single item can be acted on.
 *
 * Three of these behaviours are load-bearing in ways a screenshot cannot show: the PATCH body
 * must name every field the form owns (a sparse one blanks the rest — `encodeDefaults` is on and
 * the server treats a present null as "clear this"), the clothing block must be OMITTED unless
 * somebody touched it, and moving must never go through PATCH at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ItemSheetViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repo: CatalogRepository
    private lateinit var api: ApiService
    private lateinit var dao: CatalogDao

    private val comforter = ItemDto(
        id = "i1",
        name = "Toddler Bed Comforter",
        description = "Grey, stars",
        quantity = 1,
        condition = "good",
        status = "stored",
        currentToteId = "t1",
        toteCode = "A14",
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo = mock()
        api = mock()
        dao = mock()
        dao.stub { on { totes() } doReturn flowOf(emptyList()) }
        api.stub { onBlocking { categories() } doReturn emptyList() }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = ItemSheetViewModel(repo, api, dao, FeedbackBus())

    @Test
    fun `saving sends every field the form owns, and no apparel when untouched`() = runTest {
        repo.stub { onBlocking { patchItem(any(), any()) } doReturn comforter.copy(name = "Comforter") }
        val model = vm()
        model.open(comforter)
        dispatcher.scheduler.advanceUntilIdle()

        model.edit { it.copy(name = "Comforter") }
        model.save()
        dispatcher.scheduler.advanceUntilIdle()

        val body = argumentCaptor<ItemUpdate>()
        verify(repo).patchItem(eq("i1"), body.capture())
        assertEquals("Comforter", body.firstValue.name)
        // Not just the changed field: a body naming only `name` would send explicit nulls for
        // the rest and the server would clear them — quantity against a NOT NULL column.
        assertEquals("Grey, stars", body.firstValue.description)
        assertEquals("good", body.firstValue.condition)
        assertEquals(1, body.firstValue.quantity)
        // The reading of a tag now sealed in a bin. Nobody opened that section, so it is not sent.
        assertNull(body.firstValue.apparel)
    }

    @Test
    fun `touching the clothing section sends it`() = runTest {
        repo.stub { onBlocking { patchItem(any(), any()) } doReturn comforter }
        val model = vm()
        model.open(comforter)
        dispatcher.scheduler.advanceUntilIdle()

        model.editApparel { it.copy(sizeRaw = "4T") }
        model.save()
        dispatcher.scheduler.advanceUntilIdle()

        val body = argumentCaptor<ItemUpdate>()
        verify(repo).patchItem(eq("i1"), body.capture())
        assertEquals("4T", body.firstValue.apparel?.sizeRaw)
    }

    @Test
    fun `moving goes through the ledger, never through PATCH`() = runTest {
        repo.stub { onBlocking { move(any(), any()) } doReturn movement("moved") }
        val model = vm()
        model.open(comforter)
        dispatcher.scheduler.advanceUntilIdle()

        model.moveTo("t2")
        dispatcher.scheduler.advanceUntilIdle()

        val body = argumentCaptor<com.tote.data.remote.MoveRequest>()
        verify(repo).move(eq("i1"), body.capture())
        // `moved` because it was stored: the ledger distinguishes changing bins from coming back,
        // and a year later that is the difference between a readable history and a soup.
        assertEquals("moved", body.firstValue.reason)
        assertEquals("t2", body.firstValue.toToteId)
        verify(repo, never()).patchItem(any(), any())
        // The sheet closes onto a screen that is about to re-read: the row it was showing now
        // belongs to a different bin.
        assertNull(model.state.value.item)
    }

    @Test
    fun `putting away something that is out is a repack`() = runTest {
        repo.stub { onBlocking { move(any(), any()) } doReturn movement("repacked") }
        val model = vm()
        model.open(comforter.copy(status = "out", currentToteId = null))
        dispatcher.scheduler.advanceUntilIdle()

        model.moveTo("t2")
        dispatcher.scheduler.advanceUntilIdle()

        val body = argumentCaptor<com.tote.data.remote.MoveRequest>()
        verify(repo).move(eq("i1"), body.capture())
        assertEquals("repacked", body.firstValue.reason)
    }

    @Test
    fun `putting away something a person had is a return, not a repack`() = runTest {
        // The sheet is reachable from a person's loans, so this is the ordinary way a borrowed
        // thing comes home. Recorded as `repacked` it reads identically to reshelving after an
        // unpack, and the loan has no ending anywhere in the ledger.
        repo.stub { onBlocking { move(any(), any()) } doReturn movement("returned") }
        val model = vm()
        model.open(comforter.copy(status = "loaned", currentToteId = null))
        dispatcher.scheduler.advanceUntilIdle()

        model.moveTo("t2")
        dispatcher.scheduler.advanceUntilIdle()

        val body = argumentCaptor<com.tote.data.remote.MoveRequest>()
        verify(repo).move(eq("i1"), body.capture())
        assertEquals("returned", body.firstValue.reason)
        assertEquals("t2", body.firstValue.toToteId)
    }

    @Test
    fun `history is read once, on demand`() = runTest {
        repo.stub { onBlocking { movements(any()) } doReturn listOf(movement("initial")) }
        val model = vm()
        model.open(comforter)
        dispatcher.scheduler.advanceUntilIdle()
        // Not fetched with the sheet: most opens never ask.
        verify(repo, never()).movements(any())

        model.mode(SheetMode.History)
        dispatcher.scheduler.advanceUntilIdle()
        model.mode(SheetMode.View)
        model.mode(SheetMode.History)
        dispatcher.scheduler.advanceUntilIdle()

        verify(repo).movements(eq("i1"))
        assertEquals(1, model.state.value.movements.size)
        assertTrue(model.state.value.historyLoaded)
    }

    @Test
    fun `a failed delete leaves the sheet open on the item`() = runTest {
        repo.stub { onBlocking { deleteItem(any()) } doAnswer { throw java.io.IOException("no route") } }
        val model = vm()
        model.open(comforter)
        dispatcher.scheduler.advanceUntilIdle()

        model.delete()
        dispatcher.scheduler.advanceUntilIdle()

        // Still there, and not busy: closing on a failure would read exactly like a success and
        // the row would still be in the bin the next time the screen loaded.
        assertEquals("i1", model.state.value.item?.id)
        assertEquals(false, model.state.value.busy)
    }

    @Test
    fun `a delete still reports the change, even though it closes the sheet first`() = runTest {
        repo.stub { onBlocking { deleteItem(any()) } doReturn Unit }
        val model = vm()
        val seen = mutableListOf<Unit>()
        val job = kotlinx.coroutines.CoroutineScope(dispatcher).launch {
            model.changes.collect { seen += it }
        }
        model.open(comforter)
        dispatcher.scheduler.advanceUntilIdle()

        model.delete()
        dispatcher.scheduler.advanceUntilIdle()

        // The screen behind has to hear this. It closes first and reports second, so a listener
        // that only lived while the sheet was open would miss it and go on showing a deleted row.
        assertEquals(1, seen.size)
        job.cancel()
    }

    @Test
    fun `a movement reads as what happened, with bins named by their code today`() {
        val codes = mapOf("t1" to "A14", "t2" to "B02")
        assertEquals(
            "Moved from A14 to B02",
            movementLine(
                movement("moved").copy(fromToteId = "t1", toToteId = "t2"),
            ) { codes[it] },
        )
        assertEquals(
            "Unpacked from A14",
            movementLine(movement("unpacked").copy(fromToteId = "t1")) { codes[it] },
        )
        // A bin that has since been deleted still leaves a readable row.
        assertEquals(
            "Catalogued into a bin",
            movementLine(movement("initial").copy(toToteId = "gone")) { codes[it] },
        )
    }

    private fun movement(reason: String) = MovementDto(
        id = "m1",
        itemId = "i1",
        reason = reason,
        movedAt = "2026-08-16T10:00:00Z",
    )
}
