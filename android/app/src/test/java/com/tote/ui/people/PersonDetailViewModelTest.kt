package com.tote.ui.people

import androidx.lifecycle.SavedStateHandle
import com.tote.data.local.CachedTote
import com.tote.data.local.CatalogDao
import com.tote.data.remote.ApiService
import com.tote.data.remote.FitsDto
import com.tote.data.remote.ItemDto
import com.tote.data.remote.MoveRequest
import com.tote.data.remote.MovementDto
import com.tote.data.remote.OutgrownIn
import com.tote.data.remote.PersonDto
import com.tote.data.remote.PersonSizeDto
import com.tote.data.remote.PersonSizeIn
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
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify

/**
 * One person's screen.
 *
 * The load-bearing assertion in here is the one about [FitsDto.answered]: "nothing we own fits
 * her" and "we have never recorded her size" are different sentences, only one of them means
 * stop looking, and collapsing them would send someone away from a bin that has exactly what
 * they came for.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PersonDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var api: ApiService
    private lateinit var dao: CatalogDao
    private val feedback = com.tote.util.FeedbackBus()

    private val bins = listOf(CachedTote("t1", "A14", "Winter 5T", null, "Attic", 12, 0, false))

    private val emma = PersonDto(
        id = "p1",
        name = "Emma",
        createdAt = "2026-01-01T00:00:00Z",
        currentSizes = listOf(
            PersonSizeDto("s1", "p1", "tops", "5T", "toddler", 5.0, "2026-08-01")
        ),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        api = mock()
        dao = mock<CatalogDao>().stub { on { totes() } doReturn flowOf(bins) }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(
        person: PersonDto = emma,
        fits: FitsDto = FitsDto(answered = true),
        onLoan: List<ItemDto> = emptyList(),
    ): PersonDetailViewModel {
        api.stub {
            onBlocking { person(any()) } doReturn person
            onBlocking { fits(any(), anyOrNull(), anyOrNull()) } doReturn fits
            onBlocking { onLoan(any()) } doReturn onLoan
            // Stubbed by default: an unstubbed suspend returning a non-null List hands back a
            // null that trips Kotlin's intrinsic check OUTSIDE the runCatching lambda, so it
            // escapes to the outer try and the whole load reports as failed.
            onBlocking { personSizes(any()) } doReturn emptyList<PersonSizeDto>()
        }
        return PersonDetailViewModel(api, dao, feedback, SavedStateHandle(mapOf("personId" to "p1")))
    }

    @Test
    fun `the screen loads the person, what fits, and what they have of ours`() = runTest {
        val model = vm(
            fits = FitsDto(
                answered = true,
                items = listOf(ItemDto(id = "i1", name = "Red coat", status = "stored", toteCode = "A14")),
            ),
            onLoan = listOf(ItemDto(id = "i2", name = "Drill", status = "loaned")),
        )
        dispatcher.scheduler.advanceUntilIdle()

        val s = model.state.value
        assertEquals("Emma", s.person?.name)
        assertEquals(1, s.fits?.items?.size)
        assertEquals(1, s.onLoan.size)
        assertFalse(s.loading)
    }

    @Test
    fun `an unanswered fits query is kept distinct from an empty one`() = runTest {
        // The server says "we cannot say" with answered=false plus a reason. If this were
        // flattened to an empty list the screen would render "nothing fits", which tells someone
        // to stop looking when the truth is "go and read a tag".
        val model = vm(fits = FitsDto(answered = false, reason = "no_sizes_recorded"))
        dispatcher.scheduler.advanceUntilIdle()

        val fits = model.state.value.fits
        assertFalse(fits!!.answered)
        assertEquals("no_sizes_recorded", fits.reason)
        assertTrue(fits.items.isEmpty())
    }

    @Test
    fun `narrowing to a garment type re-asks the server rather than filtering here`() = runTest {
        val model = vm()
        dispatcher.scheduler.advanceUntilIdle()

        model.setGarmentType("shoes")
        dispatcher.scheduler.advanceUntilIdle()

        // Matching a size against the ladder has exactly one writer, and it is not the client.
        verify(api).fits(eq("p1"), eq("shoes"), anyOrNull())
        assertEquals("shoes", model.state.value.garmentType)
    }

    @Test
    fun `recording a size sends only what the tag said`() = runTest {
        val model = vm()
        dispatcher.scheduler.advanceUntilIdle()
        api.stub {
            onBlocking { addPersonSize(any(), any()) } doReturn
                PersonSizeDto("s2", "p1", "shoes", "11", "shoe_us_child", 11.0, "2026-08-16")
        }

        model.addSize("shoes", "  11  ")
        dispatcher.scheduler.advanceUntilIdle()

        val body = argumentCaptor<PersonSizeIn>()
        verify(api).addPersonSize(eq("p1"), body.capture())
        assertEquals("11", body.firstValue.sizeRaw)
        assertEquals("shoes", body.firstValue.garmentType)
        // No system, no ordinal: a client that could set the index could file a 4T as an adult L,
        // and it would then match on every fits query forever without anyone seeing why.
        assertNull(body.firstValue.effectiveFrom)
    }

    @Test
    fun `a blank size is not recorded at all`() = runTest {
        val model = vm()
        dispatcher.scheduler.advanceUntilIdle()

        model.addSize("tops", "   ")
        dispatcher.scheduler.advanceUntilIdle()

        verify(api, org.mockito.kotlin.never()).addPersonSize(any(), any())
    }

    @Test
    fun `marking a run outgrown files it into one bin in one call`() = runTest {
        val model = vm()
        dispatcher.scheduler.advanceUntilIdle()
        api.stub { onBlocking { outgrown(any(), any()) } doReturn emptyList<MovementDto>() }

        model.markOutgrown(listOf("i1", "i2", "i3"), "t1")
        dispatcher.scheduler.advanceUntilIdle()

        val body = argumentCaptor<OutgrownIn>()
        verify(api).outgrown(eq("p1"), body.capture())
        // One call, not three: the whole run moves together or not at all, and forty separate
        // edits is the shape of flow nobody finishes.
        assertEquals(listOf("i1", "i2", "i3"), body.firstValue.itemIds)
        assertEquals("t1", body.firstValue.toteId)
    }

    @Test
    fun `returning something puts it back in a named bin`() = runTest {
        val model = vm()
        dispatcher.scheduler.advanceUntilIdle()
        api.stub {
            onBlocking { move(any(), any()) } doAnswer {
                MovementDto(id = "m1", itemId = "i2", reason = "returned", movedAt = "2026-08-16T00:00:00Z")
            }
        }

        model.markReturned("i2", "t1")
        dispatcher.scheduler.advanceUntilIdle()

        val body = argumentCaptor<MoveRequest>()
        verify(api).move(eq("i2"), body.capture())
        assertEquals("returned", body.firstValue.reason)
        // Required, not optional: the server rejects an inbound move with no destination (422),
        // and an item that is "back" but in no bin is the state the catalog exists to prevent.
        assertEquals("t1", body.firstValue.toToteId)
    }

    @Test
    fun `the bins for the destination picker come from the offline cache`() = runTest {
        // Filing outgrown clothes happens on the way back from the attic, where the Wi-Fi is at
        // its worst. A picker that needed the network would be empty exactly when it is needed.
        val model = vm()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, model.state.value.totes.size)
        assertEquals("A14", model.state.value.totes.first().code)
    }

    // ── Maintenance ──────────────────────────────────────────────────

    @Test
    fun `the whole size history loads, not just what is current`() = runTest {
        // `currentSizes` answers "what size is she now"; the history answers "what size was she
        // last winter", which is what tells you which bin to open — and it is the only way to
        // find a mistyped reading that is silently breaking every fits query.
        val model = vm()
        dispatcher.scheduler.advanceUntilIdle()
        // Re-stubbed after construction: vm() sets a default empty history for every other test.
        api.stub {
            onBlocking { personSizes(any()) } doReturn listOf(
                PersonSizeDto("s1", "p1", "tops", "5T", "toddler", 5.0, "2026-08-01"),
                PersonSizeDto("s0", "p1", "tops", "4T", "toddler", 4.0, "2025-11-14"),
            )
        }

        model.load()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, model.state.value.sizeHistory.size)
    }

    @Test
    fun `a size is deleted, never edited`() = runTest {
        // size_raw is sacred and the index is derived from it server-side, so the sanctioned fix
        // for a fat-fingered "5TT" is delete-and-re-add — which re-derives cleanly. There is
        // deliberately no patch-a-size call to make.
        val model = vm()
        dispatcher.scheduler.advanceUntilIdle()

        model.deleteSize("s1")
        dispatcher.scheduler.advanceUntilIdle()

        verify(api).deletePersonSize(eq("p1"), eq("s1"))
    }

    @Test
    fun `editing a person sends only the fields a person can correct`() = runTest {
        val model = vm()
        dispatcher.scheduler.advanceUntilIdle()
        api.stub { onBlocking { patchPerson(any(), any()) } doReturn emma }

        model.editPerson("  Emma R  ", "2021-04-09")
        dispatcher.scheduler.advanceUntilIdle()

        val body = argumentCaptor<com.tote.data.remote.PersonPatch>()
        verify(api).patchPerson(eq("p1"), body.capture())
        assertEquals("Emma R", body.firstValue.name)
        assertEquals("2021-04-09", body.firstValue.birthdate)
    }

    @Test
    fun `removing a person leaves the screen`() = runTest {
        val model = vm()
        dispatcher.scheduler.advanceUntilIdle()
        var gone = false

        model.deletePerson { gone = true }
        dispatcher.scheduler.advanceUntilIdle()

        verify(api).deletePerson(eq("p1"))
        assertTrue(gone)
    }
}
