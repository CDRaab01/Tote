package com.tote.ui.settings

import com.tote.data.CatalogRepository
import com.tote.data.remote.InviteDto
import com.tote.data.remote.MergePreviewDto
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub

/** The mark on the door to Settings — see [InviteBadgeViewModel]. */
@OptIn(ExperimentalCoroutinesApi::class)
class InviteBadgeViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repo: CatalogRepository

    private val waiting = InviteDto(
        householdId = "h2",
        invitedByName = "Sam",
        invitedByEmail = "sam@example.com",
        preview = MergePreviewDto(totes = 1, items = 2, people = 0),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo = mock { onBlocking { myInvite() } doReturn null }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `no invitation, no mark`() = runTest {
        val model = InviteBadgeViewModel(repo)
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(model.hasInvite.value)
    }

    @Test
    fun `an invitation raises the mark`() = runTest {
        repo.stub { onBlocking { myInvite() } doReturn waiting }
        val model = InviteBadgeViewModel(repo)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.hasInvite.value)
    }

    @Test
    fun `a dropped request does not clear the mark`() = runTest {
        // A badge that blinks off on every tailnet hiccup is how somebody concludes they
        // imagined the invitation.
        var reachable = true
        repo.stub {
            onBlocking { myInvite() } doAnswer {
                if (reachable) waiting else throw java.io.IOException("no route")
            }
        }
        val model = InviteBadgeViewModel(repo)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.hasInvite.value)

        reachable = false
        model.refresh()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(model.hasInvite.value)
    }
}
