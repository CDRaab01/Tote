package com.tote.ui.review

import com.tote.data.local.CachedTote
import com.tote.data.CaptureQueueRepository
import com.tote.data.CatalogRepository
import com.tote.data.local.CatalogDao
import com.tote.data.remote.ApiService
import com.tote.data.remote.ApparelDto
import com.tote.data.remote.CategoryDto
import com.tote.data.remote.DraftConfirm
import com.tote.data.remote.DraftDto
import com.tote.data.remote.ItemDto
import com.tote.util.FeedbackBus
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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
    private lateinit var repo: CatalogRepository
    private lateinit var captureQueue: CaptureQueueRepository
    private val feedback = FeedbackBus()

    private val bins = listOf(
        CachedTote("t1", "A14", "Christmas decor", null, "Attic", 37, 0, false),
        CachedTote("t2", "G01", "Power tools", null, "Garage", 8, 0, false),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        api = mock()
        dao = mock<CatalogDao>().stub { on { totes() } doReturn flowOf(bins) }
        repo = mock()
        captureQueue = mock()
        captureQueue.stub { on { queue } doReturn MutableStateFlow(emptyList()) }
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
        return ReviewViewModel(api, dao, repo, feedback, captureQueue)
    }

    // ── Re-scan ─────────────────────────────────────────────────────────

    @Test
    fun `a re-scan replaces the draft and reseats the editor from it`() = runTest {
        val vm = vmWith(
            DraftDto(id = "d1", name = "Unidentified item", scanError = "identify_unavailable"),
            draft("d2", "Ratchet set"),
        )
        dispatcher.scheduler.advanceUntilIdle()

        api.stub {
            onBlocking { rescanDraft("d1") } doReturn
                DraftDto(id = "d1", name = "Cordless drill", scanConfidence = "high")
        }

        vm.rescan()
        dispatcher.scheduler.advanceUntilIdle()

        val s = vm.state.value
        // The answer lands on the draft AND in the form. Leaving the editor showing
        // "Unidentified item" over a draft that now says "Cordless drill" would file the
        // placeholder, because the confirm body is built from the edits.
        assertEquals("Cordless drill", s.drafts[0].name)
        assertNull(s.drafts[0].scanError)
        assertEquals("Cordless drill", s.edits.name)
        // Position held: the stack is not re-fetched, so somebody ten items in stays there.
        assertEquals(0, s.index)
        assertEquals(2, s.drafts.size)
        assertFalse(s.saving)
    }

    @Test
    fun `a re-scan writes to the draft it asked about, not to the current position`() = runTest {
        // A re-scan takes as long as a scan does, and Skip is one tap. Writing by index would
        // land the drill's answer on whichever photograph happened to be on screen when it
        // returned — silently, and on a screen whose whole job is being trusted.
        val vm = vmWith(draft("d1", "Unidentified item"), draft("d2", "Ratchet set"))
        dispatcher.scheduler.advanceUntilIdle()

        api.stub {
            onBlocking { rescanDraft("d1") } doReturn DraftDto(id = "d1", name = "Cordless drill")
        }

        vm.rescan()
        vm.skip()
        dispatcher.scheduler.advanceUntilIdle()

        val s = vm.state.value
        assertEquals("Cordless drill", s.drafts[0].name)
        assertEquals("Ratchet set", s.drafts[1].name)
        // Moved on, so the editor belongs to d2 and must not have been reseated from d1.
        assertEquals(1, s.index)
        assertEquals("Ratchet set", s.edits.name)
    }

    @Test
    fun `a re-scan against a model that is still down leaves the draft alone and says why`() =
        runTest {
            val vm = vmWith(
                DraftDto(
                    id = "d1",
                    name = "Unidentified item",
                    scanError = "identify_unavailable",
                )
            )
            dispatcher.scheduler.advanceUntilIdle()

            api.stub {
                onBlocking { rescanDraft("d1") } doAnswer {
                    throw retrofit2.HttpException(
                        retrofit2.Response.error<Any>(
                            503,
                            okhttp3.ResponseBody.create(null, ""),
                        )
                    )
                }
            }

            vm.rescan()
            dispatcher.scheduler.advanceUntilIdle()

            val s = vm.state.value
            // Untouched — the server does not write on a 503 either, so the two agree.
            assertEquals("Unidentified item", s.drafts[0].name)
            assertEquals("identify_unavailable", s.drafts[0].scanError)
            assertFalse(s.saving)
            // Names the thing to check. "HTTP 503" sends somebody to look at the phone, and the
            // fault is on the host.
            assertTrue(s.error!!.contains("LM Studio"), s.error!!)
        }

    @Test
    fun `a second tap while one re-scan is in flight is ignored`() = runTest {
        // The call takes tens of seconds and the button stays on screen throughout. Two in
        // flight would spend the GPU twice and let the loser's answer overwrite the winner's.
        val vm = vmWith(draft("d1", "Unidentified item"))
        dispatcher.scheduler.advanceUntilIdle()

        api.stub {
            onBlocking { rescanDraft("d1") } doReturn DraftDto(id = "d1", name = "Cordless drill")
        }

        vm.rescan()
        vm.rescan()
        dispatcher.scheduler.advanceUntilIdle()

        verify(api, org.mockito.kotlin.times(1)).rescanDraft("d1")
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
    fun `a draft with no destination can be confirmed, and lands unfiled`() = runTest {
        // This test used to assert the opposite: no bin, no confirm. That forced the
        // destination decision at review time — the moment you are least sure, with the object
        // already back in a closed bin — so null is a legitimate answer now. The server records
        // it as `catalogued` rather than `initial`, and the item shows up under "Not in a bin".
        val vm = vmWith(draft("d1", "Ratchet set"))
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.state.value.edits.toteId)
        assertTrue(vm.state.value.edits.canConfirm)

        vm.confirm()
        dispatcher.scheduler.advanceUntilIdle()

        val body = argumentCaptor<DraftConfirm>()
        verify(api).confirmDraft(eq("d1"), body.capture())
        // Explicitly null, not omitted and not a placeholder bin.
        assertNull(body.firstValue.toteId)
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
        val vm = ReviewViewModel(api, dao, repo, feedback, captureQueue)
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

    // ── The filing loop closes ────────────────────────────────────────

    @Test
    fun `confirming refreshes the catalog and says where the item went`() = runTest {
        // The two halves of the old silence: file twenty items and (a) nothing was said, and
        // (b) the tote list and stat tiles kept their stale counts because this ViewModel
        // bypasses CatalogRepository and nothing else knew a write had happened.
        val vm = vmWith(draft("d1", "Ratchet set", toteId = "t1"))
        api.stub {
            onBlocking { confirmDraft(any(), any()) } doAnswer {
                ItemDto(id = "i1", name = "Ratchet set", status = "stored")
            }
        }
        val heard = mutableListOf<String>()
        val listener = kotlinx.coroutines.CoroutineScope(dispatcher)
            .launch { feedback.messages.collect { heard += it } }
        dispatcher.scheduler.advanceUntilIdle()

        vm.confirm()
        dispatcher.scheduler.advanceUntilIdle()

        verify(repo).refresh()
        // Named by bin CODE — what is written on the physical box.
        assertEquals(listOf("Filed Ratchet set into A14"), heard)
        listener.cancel()
    }

    @Test
    fun `skip wraps past the last draft instead of trapping it`() = runTest {
        // The last draft used to be a trap: Skip disabled, so the only exits were File it
        // (demands a bin) and Discard (deletes the photographs). "I don't know where this goes
        // yet" had no answer.
        val vm = vmWith(draft("d1", "One"), draft("d2", "Two"))
        dispatcher.scheduler.advanceUntilIdle()
        vm.skip()
        assertEquals("d2", vm.state.value.current?.id)

        vm.skip()
        assertEquals("d1", vm.state.value.current?.id)
    }

    @Test
    fun `a failed confirm does not claim success`() = runTest {
        val vm = vmWith(draft("d1", "Ratchet set", toteId = "t1"))
        api.stub {
            onBlocking { confirmDraft(any(), any()) } doAnswer { throw java.io.IOException("no route") }
        }
        val heard = mutableListOf<String>()
        val listener = kotlinx.coroutines.CoroutineScope(dispatcher)
            .launch { feedback.messages.collect { heard += it } }
        dispatcher.scheduler.advanceUntilIdle()

        vm.confirm()
        dispatcher.scheduler.advanceUntilIdle()

        // No refresh, no "Filed …" — the error renders inline on the screen instead.
        verify(repo, org.mockito.kotlin.never()).refresh()
        assertEquals(emptyList<String>(), heard)
        assertEquals(1, vm.state.value.drafts.size)
        listener.cancel()
    }
}
