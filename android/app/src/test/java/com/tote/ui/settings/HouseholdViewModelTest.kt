package com.tote.ui.settings

import com.tote.data.CatalogRepository
import com.tote.data.remote.HouseholdDto
import com.tote.data.remote.HouseholdMemberDto
import com.tote.data.remote.InviteDto
import com.tote.data.remote.MergePreviewDto
import com.tote.util.FeedbackBus
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import retrofit2.HttpException
import retrofit2.Response

/**
 * Settings -> Household.
 *
 * What is worth testing here is not the happy path — it is that a **refused merge stays on the
 * screen**. `/household/accept` answers a 409 whose body names the bin codes somebody has to go
 * and look at in an attic, and the app's normal error handling would turn that into a four-second
 * snackbar reading "Couldn't join that household", taking the codes with it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HouseholdViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repo: CatalogRepository

    private val solo = HouseholdDto(
        householdId = "h1",
        members = listOf(HouseholdMemberDto("u1", "Owner", "owner@example.com", isOwner = true)),
        youAreOwner = true,
        shared = false,
    )

    private fun invite(conflicts: Map<String, List<String>> = emptyMap()) = InviteDto(
        householdId = "h2",
        invitedByName = "Partner",
        invitedByEmail = "partner@example.com",
        preview = MergePreviewDto(totes = 3, items = 12, people = 1, conflicts = conflicts),
    )

    /** A real Retrofit 409 carrying the server's structured conflict body. */
    private fun conflict409(): HttpException = HttpException(
        Response.error<Any>(
            409,
            ("""{"detail":{"message":"Both catalogues use the same bin codes or tags.",""" +
                """"conflicts":{"tote_codes":["a14","b02"]}}}""")
                .toResponseBody("application/json".toMediaType()),
        )
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo = mock {
            onBlocking { household() } doReturn solo
            onBlocking { myInvite() } doReturn null
        }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = HouseholdViewModel(repo, FeedbackBus())

    @Test
    fun `a solo account reports itself as not shared`() = runTest {
        val model = vm()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(false, model.state.value.household?.shared)
        assertNull(model.state.value.invite)
        assertTrue(model.state.value.loaded)
    }

    @Test
    fun `an invitation arrives with the count of what it would move`() = runTest {
        repo.stub { onBlocking { myInvite() } doReturn invite() }
        val model = vm()
        dispatcher.scheduler.advanceUntilIdle()

        val preview = model.state.value.invite?.preview
        assertEquals(3, preview?.totes)
        assertEquals(12, preview?.items)
        assertTrue(preview?.conflicts.isNullOrEmpty())
    }

    @Test
    fun `a refused merge keeps the blocking bin codes on screen`() = runTest {
        repo.stub {
            onBlocking { myInvite() } doReturn invite()
            onBlocking { acceptInvite() } doAnswer { throw conflict409() }
        }
        val model = vm()
        dispatcher.scheduler.advanceUntilIdle()

        model.accept()
        dispatcher.scheduler.advanceUntilIdle()

        // The codes survive, because "conflict" is not actionable and "A14" is.
        assertEquals(listOf("a14", "b02"), model.state.value.conflicts["tote_codes"])
        // And nothing pretends the merge happened.
        assertEquals(false, model.state.value.household?.shared)
    }

    @Test
    fun `accepting is never one tap`() = runTest {
        repo.stub { onBlocking { myInvite() } doReturn invite() }
        val model = vm()
        dispatcher.scheduler.advanceUntilIdle()

        model.askAccept()
        dispatcher.scheduler.advanceUntilIdle()

        // The merge is irreversible, so asking must not perform it.
        verify(repo, never()).acceptInvite()
        assertTrue(model.state.value.confirmingAccept)
    }

    @Test
    fun `leaving is never one tap either`() = runTest {
        val model = vm()
        dispatcher.scheduler.advanceUntilIdle()

        model.askLeave()
        dispatcher.scheduler.advanceUntilIdle()

        verify(repo, never()).leaveHousehold()
        assertTrue(model.state.value.confirmingLeave)
    }

    @Test
    fun `a stale conflict is cleared before a fresh attempt`() = runTest {
        val shared = solo.copy(
            members = solo.members + HouseholdMemberDto("u2", "Partner", "p@example.com", false),
            shared = true,
        )
        // Fails once, then succeeds — re-stubbing mid-test would call the mock to record the new
        // answer and trip the old one, which is exactly the throw being replaced.
        var stillColliding = true
        repo.stub {
            onBlocking { myInvite() } doReturn invite()
            onBlocking { acceptInvite() } doAnswer {
                if (stillColliding) throw conflict409() else shared
            }
        }
        val model = vm()
        dispatcher.scheduler.advanceUntilIdle()
        model.accept()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.state.value.conflicts.isNotEmpty())

        // They rename the bin and try again. A conflict list left over from the previous attempt
        // would keep the notice on screen over a household that is now perfectly mergeable.
        stillColliding = false
        model.accept()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(model.state.value.conflicts.isEmpty())
        assertEquals(true, model.state.value.household?.shared)
        assertNull(model.state.value.invite)
    }

    @Test
    fun `an empty email cannot be invited`() = runTest {
        val model = vm()
        dispatcher.scheduler.advanceUntilIdle()

        model.onInviteEmail("   ")
        model.invite()
        dispatcher.scheduler.advanceUntilIdle()

        verify(repo, never()).invite(org.mockito.kotlin.any())
        assertFalse(model.state.value.busy)
    }
}
