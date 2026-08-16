package com.tote.ui.review

import com.tote.data.local.CachedTote
import com.tote.data.local.CatalogDao
import com.tote.data.remote.ApiService
import com.tote.data.remote.ApparelDto
import com.tote.data.remote.CategoryDto
import com.tote.data.remote.DraftConfirm
import com.tote.data.remote.DraftDto
import com.tote.data.remote.ItemDto
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
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
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify

/**
 * The review stack's behaviour, which is mostly about *position*.
 *
 * A batch of twenty photographs is only reviewed to the end if deciding about one lands you on
 * the next one. Every re-fetch or index reset in here is a person dropped back at the top of a
 * stack they were ten items into, and that is how a catalog ends up half-true.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var api: ApiService
    private lateinit var dao: CatalogDao

    private val bins = listOf(
        CachedTote("t1", "A14", "Christmas decor", null, "Attic", 37, 0, false),
        CachedTote("t2", "G01", "Power tools", null, "Garage", 8, 0, false),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        api = mock()
        dao = mock<CatalogDao>().stub { on { totes() } doReturn flowOf(bins) }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun draft(id: String, name: String, toteId: String? = null) =
        DraftDto(id = id, name = name, draftToteId = toteId, photoCount = 2)

    private suspend fun vmWith(vararg drafts: DraftDto): ReviewViewModel {
        api.stub {
            onBlocking { drafts() } doReturn drafts.toList()
            onBlocking { categories() } doReturn listOf(CategoryDto("c1", "Seasonal decor"))
        }
        return ReviewViewModel(api, dao)
    }

    @Test
    fun `the stack loads with the first draft's own values in the editor`() = runTest {
        val vm = vmWith(draft("d1", "Red storage box", toteId = "t1"))
        dispatcher.scheduler.advanceUntilIdle()

        val s = vm.state.value
        assertEquals(1, s.drafts.size)
        assertEquals("Red storage box", s.edits.name)
        // Pre-selected from the bin chosen at capture time — the payoff of carrying it through
        // the queue is that the common case needs no tap here at all.
        assertEquals("t1", s.edits.toteId)
        assertTrue(s.edits.canConfirm)
    }

    @Test
    fun `a draft with no destination cannot be filed until a bin is chosen`() = runTest {
        val vm = vmWith(draft("d1", "Ratchet set"))
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.state.value.edits.toteId)
        assertFalse(vm.state.value.edits.canConfirm)

        vm.edit { it.copy(toteId = "t2") }
        assertTrue(vm.state.value.edits.canConfirm)
    }

    @Test
    fun `a blank name cannot be filed`() = runTest {
        val vm = vmWith(draft("d1", "Ratchet set", toteId = "t1"))
        dispatcher.scheduler.advanceUntilIdle()

        vm.edit { it.copy(name = "   ") }
        assertFalse(vm.state.value.edits.canConfirm)
    }

    @Test
    fun `confirming sends the human's edits, not the model's answer`() = runTest {
        val vm = vmWith(draft("d1", "Red storage box", toteId = "t1"))
        api.stub {
            onBlocking { confirmDraft(any(), any()) } doAnswer {
                ItemDto(id = "i1", name = "Ornament box", status = "stored")
            }
        }
        dispatcher.scheduler.advanceUntilIdle()

        vm.edit { it.copy(name = "Ornament box", quantity = 4, condition = "good") }
        vm.confirm()
        dispatcher.scheduler.advanceUntilIdle()

        verify(api).confirmDraft(
            eq("d1"),
            eq(
                DraftConfirm(
                    toteId = "t1",
                    name = "Ornament box",
                    quantity = 4,
                    condition = "good",
                )
            ),
        )
    }

    @Test
    fun `filing one draft lands on the next without re-fetching the stack`() = runTest {
        val vm = vmWith(
            draft("d1", "First", toteId = "t1"),
            draft("d2", "Second", toteId = "t1"),
            draft("d3", "Third", toteId = "t1"),
        )
        api.stub {
            onBlocking { confirmDraft(any(), any()) } doAnswer {
                ItemDto(id = "i1", name = "First", status = "stored")
            }
        }
        dispatcher.scheduler.advanceUntilIdle()

        vm.skip()
        assertEquals("Second", vm.state.value.current?.name)

        vm.confirm()
        dispatcher.scheduler.advanceUntilIdle()

        val s = vm.state.value
        assertEquals(2, s.drafts.size)
        // Still at index 1, which is now the third draft — the position is kept, not reset.
        assertEquals("Third", s.current?.name)
        assertEquals("Third", s.edits.name)
        // One initial load only: a re-fetch here is what would send someone back to the top.
        verify(api, org.mockito.kotlin.times(1)).drafts()
    }

    @Test
    fun `filing the last draft leaves an empty stack rather than an out-of-range index`() =
        runTest {
            val vm = vmWith(draft("d1", "Only one", toteId = "t1"))
            api.stub {
                onBlocking { confirmDraft(any(), any()) } doAnswer {
                    ItemDto(id = "i1", name = "Only one", status = "stored")
                }
            }
            dispatcher.scheduler.advanceUntilIdle()

            vm.confirm()
            dispatcher.scheduler.advanceUntilIdle()

            val s = vm.state.value
            assertTrue(s.drafts.isEmpty())
            assertEquals(0, s.index)
            assertNull(s.current)
        }

    @Test
    fun `moving between drafts does not carry one item's edits onto the next`() = runTest {
        val vm = vmWith(
            draft("d1", "First", toteId = "t1"),
            draft("d2", "Second", toteId = "t1"),
        )
        dispatcher.scheduler.advanceUntilIdle()

        vm.edit { it.copy(name = "Corrected first", quantity = 9) }
        vm.skip()

        // Carrying them would silently apply one item's corrected name to the next photograph.
        assertEquals("Second", vm.state.value.edits.name)
        assertEquals(1, vm.state.value.edits.quantity)
    }

    @Test
    fun `an untouched clothing section is omitted from the confirm body`() = runTest {
        """Omitted means "leave what the label read" on the server. Sending an unchanged copy
        would work today and would silently start clearing fields the moment this form stops
        carrying every column the row has."""
        val draft = DraftDto(
            id = "d1", name = "Winter coat", draftToteId = "t1", photoCount = 1,
            apparel = ApparelDto(sizeRaw = "4T", sizeSystem = "toddler", department = "girls"),
        )
        val vm = vmWith(draft)
        api.stub {
            onBlocking { confirmDraft(any(), any()) } doAnswer {
                ItemDto(id = "i1", name = "Winter coat", status = "stored")
            }
        }
        dispatcher.scheduler.advanceUntilIdle()

        // The reviewer edits the NAME only and files it.
        vm.edit { it.copy(name = "Snow coat") }
        vm.confirm()
        dispatcher.scheduler.advanceUntilIdle()

        argumentCaptor<DraftConfirm>().apply {
            verify(api).confirmDraft(eq("d1"), capture())
            assertNull(firstValue.apparel, "apparel must be omitted when untouched")
            assertEquals("Snow coat", firstValue.name)
        }
    }

    @Test
    fun `touching the clothing section sends it, and the server re-derives the index`() = runTest {
        val draft = DraftDto(
            id = "d1", name = "Winter coat", draftToteId = "t1", photoCount = 1,
            apparel = ApparelDto(sizeRaw = "4T", sizeSystem = "toddler"),
        )
        val vm = vmWith(draft)
        api.stub {
            onBlocking { confirmDraft(any(), any()) } doAnswer {
                ItemDto(id = "i1", name = "Winter coat", status = "stored")
            }
        }
        dispatcher.scheduler.advanceUntilIdle()

        vm.editApparel { it.copy(sizeRaw = "6X", department = "girls") }
        vm.confirm()
        dispatcher.scheduler.advanceUntilIdle()

        argumentCaptor<DraftConfirm>().apply {
            verify(api).confirmDraft(eq("d1"), capture())
            assertEquals("6X", firstValue.apparel?.sizeRaw)
            assertEquals("girls", firstValue.apparel?.department)
        }
    }

    @Test
    fun `the clothing section starts from what the label read`() = runTest {
        val draft = DraftDto(
            id = "d1", name = "Coat", draftToteId = "t1", photoCount = 1,
            apparel = ApparelDto(sizeRaw = "4T", department = "girls", material = "Fleece"),
        )
        val vm = vmWith(draft)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("4T", vm.state.value.edits.sizeRaw)
        assertEquals("girls", vm.state.value.edits.department)
        assertEquals("Fleece", vm.state.value.edits.material)
        // And it is not "touched" merely by being populated.
        assertEquals(false, vm.state.value.edits.touchedApparel)
    }

    @Test
    fun `discarding removes the draft from the stack`() = runTest {
        val vm = vmWith(draft("d1", "First", toteId = "t1"), draft("d2", "Second", toteId = "t1"))
        dispatcher.scheduler.advanceUntilIdle()

        vm.discard()
        dispatcher.scheduler.advanceUntilIdle()

        verify(api).discardDraft("d1")
        assertEquals(1, vm.state.value.drafts.size)
        assertEquals("Second", vm.state.value.current?.name)
    }

    @Test
    fun `a failed filing keeps the draft and says why`() = runTest {
        val vm = vmWith(draft("d1", "First", toteId = "t1"))
        api.stub {
            onBlocking { confirmDraft(any(), any()) } doAnswer { throw java.io.IOException("offline") }
        }
        dispatcher.scheduler.advanceUntilIdle()

        vm.confirm()
        dispatcher.scheduler.advanceUntilIdle()

        val s = vm.state.value
        // The photographs and the identification are still here; nothing is lost by a bad tap
        // at the end of a session in a garage.
        assertEquals(1, s.drafts.size)
        assertFalse(s.saving)
        assertEquals("offline", s.error)
    }

    @Test
    fun `the bin list survives an unreachable server`() = runTest {
        api.stub {
            onBlocking { drafts() } doAnswer { throw java.io.IOException("offline") }
        }
        val vm = ReviewViewModel(api, dao)
        dispatcher.scheduler.advanceUntilIdle()

        // Reviewing happens on the way back from the garage, so the destination list comes from
        // the Room cache and must be there even when the stack itself could not load.
        assertEquals(2, vm.state.value.totes.size)
        assertEquals("offline", vm.state.value.error)
    }

    // ── Resuming the screen ──────────────────────────────────────────────────
    //
    // `refresh()` used to run only in `init`, and this ViewModel outlives a tab switch. A draft
    // that finished uploading while the app was open therefore never appeared: the tab badge
    // polls and said "4" over a screen that said "Nothing waiting", and the drafts only showed
    // up after the app was killed and reopened. Observed in production 2026-08-16.

    @Test
    fun `resuming picks up a draft that landed while the screen was open`() = runTest {
        val vm = vmWith()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(0, vm.state.value.drafts.size)

        api.stub { onBlocking { drafts() } doReturn listOf(draft("d1", "Plaid baseball cap")) }
        vm.syncPreservingPosition()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, vm.state.value.drafts.size)
        assertEquals("Plaid baseball cap", vm.state.value.edits.name)
    }

    @Test
    fun `resuming keeps the person on the draft they were looking at`() = runTest {
        val vm = vmWith(draft("d1", "One"), draft("d2", "Two"), draft("d3", "Three"))
        dispatcher.scheduler.advanceUntilIdle()
        vm.skip()
        assertEquals("d2", vm.state.value.current?.id)

        // A new draft arrives at the END of the stack (oldest first), so a naive re-fetch that
        // reset the index would drop them back to d1 every time they glanced at another app.
        api.stub {
            onBlocking { drafts() } doReturn
                listOf(draft("d1", "One"), draft("d2", "Two"), draft("d3", "Three"), draft("d4", "Four"))
        }
        vm.syncPreservingPosition()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("d2", vm.state.value.current?.id)
        assertEquals(4, vm.state.value.drafts.size)
    }

    @Test
    fun `resuming does not discard a half-typed correction`() = runTest {
        val vm = vmWith(draft("d1", "Plad basebal cap"))
        dispatcher.scheduler.advanceUntilIdle()
        vm.edit { it.copy(name = "Plaid baseball cap", quantity = 2) }

        vm.syncPreservingPosition()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Plaid baseball cap", vm.state.value.edits.name)
        assertEquals(2, vm.state.value.edits.quantity)
    }

    @Test
    fun `a resume that cannot reach the server changes nothing`() = runTest {
        val vm = vmWith(draft("d1", "One"))
        dispatcher.scheduler.advanceUntilIdle()

        api.stub { onBlocking { drafts() } doAnswer { throw java.io.IOException("no route") } }
        vm.syncPreservingPosition()
        dispatcher.scheduler.advanceUntilIdle()

        // The stack on screen is still usable offline — blanking it on a failed poll would take
        // the review away from someone standing in a garage with the bin still open.
        assertEquals(1, vm.state.value.drafts.size)
        assertNull(vm.state.value.error)
    }
}
