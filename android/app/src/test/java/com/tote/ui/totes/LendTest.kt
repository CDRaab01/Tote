package com.tote.ui.totes

import androidx.lifecycle.SavedStateHandle
import com.tote.data.CatalogRepository
import com.tote.data.remote.ApiService
import com.tote.util.FeedbackBus
import com.tote.data.remote.MoveRequest
import com.tote.data.remote.MovementDto
import com.tote.data.remote.PersonDto
import com.tote.data.remote.ToteDetailDto
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
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
 * Lending, from the bin the thing is in.
 *
 * The `personId` is the whole point. Without it the ledger records that something left and not
 * who took it, and "who has the drill" — one of the two questions the people table exists for —
 * becomes unanswerable from data the app already had in its hands.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LendTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repo: CatalogRepository
    private lateinit var api: ApiService

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo = mock()
        api = mock()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(): ToteDetailViewModel {
        repo.stub {
            onBlocking { tote(any()) } doReturn ToteDetailDto(id = "t1", code = "G01")
            onBlocking { move(any(), any()) } doReturn
                MovementDto(id = "m1", itemId = "i1", reason = "loaned", movedAt = "2026-08-16T00:00:00Z")
        }
        return ToteDetailViewModel(
            repo,
            api,
            FeedbackBus(),
            mock(),
            SavedStateHandle(mapOf("toteId" to "t1")),
        )
    }

    @Test
    fun `lending records who took it and when it is due`() = runTest {
        val model = vm()
        dispatcher.scheduler.advanceUntilIdle()

        model.lend("i1", personId = "p9", expectedBack = "2026-09-30")
        dispatcher.scheduler.advanceUntilIdle()

        val body = argumentCaptor<MoveRequest>()
        verify(repo).move(eq("i1"), body.capture())
        assertEquals("loaned", body.firstValue.reason)
        assertEquals("p9", body.firstValue.personId)
        assertEquals("2026-09-30", body.firstValue.expectedBack)
        // Outbound: the server rejects a `loaned` move that also names a destination, because an
        // item cannot be lent out and in a bin at once.
        assertNull(body.firstValue.toToteId)
    }

    @Test
    fun `lending without a date is allowed and sends no date at all`() = runTest {
        // A blank field must not become an empty string the server has to interpret, and it must
        // not become an invented date either: a manufactured due date produces an overdue nudge
        // nobody agreed to, which is how a notification channel gets muted for good.
        val model = vm()
        dispatcher.scheduler.advanceUntilIdle()

        model.lend("i1", personId = "p9", expectedBack = "   ")
        dispatcher.scheduler.advanceUntilIdle()

        val body = argumentCaptor<MoveRequest>()
        verify(repo).move(eq("i1"), body.capture())
        assertNull(body.firstValue.expectedBack)
    }

    @Test
    fun `the people list is fetched only when the lend sheet is opened`() = runTest {
        api.stub { onBlocking { people() } doReturn listOf(PersonDto("p9", "Dave", createdAt = "x")) }
        val model = vm()
        dispatcher.scheduler.advanceUntilIdle()

        // Most visits to a bin are about what is in it; a people request on every open would be
        // a round trip nobody asked for on the tab used most in a garage.
        verify(api, org.mockito.kotlin.never()).people()

        model.loadPeople()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Dave", model.people.value.single().name)
    }
}
