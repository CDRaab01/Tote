package com.tote.ui.verify

import androidx.lifecycle.SavedStateHandle
import com.tote.data.CatalogRepository
import com.tote.data.remote.ItemDto
import com.tote.data.remote.ToteDetailDto
import com.tote.data.remote.VerifyOutDto
import com.tote.util.FeedbackBus
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import retrofit2.HttpException
import retrofit2.Response

/**
 * The verify pass — the one write in this app that can mark something missing.
 *
 * The assertions are about the contract with the server, which no screenshot can show: that a
 * partial pass never reaches it (it would be refused, and asking costs a round trip in an
 * attic), that each item lands in exactly one list, and that a refusal reaches the person as the
 * server's own sentence rather than a status code.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VerifyViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repo: CatalogRepository
    private lateinit var bus: FeedbackBus
    private val said = mutableListOf<String>()
    private var wentBack = 0

    private fun stored(id: String, name: String) =
        ItemDto(id = id, name = name, quantity = 1, status = "stored")

    private val bin = ToteDetailDto(
        id = "t1", code = "A14", label = "Christmas decor", itemCount = 2, outCount = 1,
        items = listOf(stored("i1", "Pre-lit tree, 7ft"), stored("i2", "Ornament box")),
        // Already out of the bin, so it is not part of the answer sheet — nobody should be asked
        // to confirm the absence of something the catalog already says is absent.
        itemsOut = listOf(ItemDto(id = "i9", name = "Outdoor lights", quantity = 6, status = "out")),
    )

    /** A real Retrofit 422 carrying the server's coverage sentence. */
    private fun refused(): HttpException = HttpException(
        Response.error<Any>(
            422,
            """{"detail":"A14 has 2 stored items; 1 was declared."}"""
                .toResponseBody("application/json".toMediaType()),
        )
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        said.clear()
        wentBack = 0
        bus = FeedbackBus()
        CoroutineScope(dispatcher).launch { bus.messages.collect { said += it } }
        repo = mock {
            onBlocking { tote(any()) } doReturn bin
            onBlocking { verifyTote(any(), any(), any()) } doReturn
                VerifyOutDto(presentCount = 2, missingCount = 0, lastVerifiedAt = "2026-08-25")
        }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun ready(): VerifyViewModel {
        val model = VerifyViewModel(repo, bus, SavedStateHandle(mapOf("toteId" to "t1")))
        CoroutineScope(dispatcher).launch { model.done.collect { wentBack++ } }
        dispatcher.scheduler.advanceUntilIdle()
        return model
    }

    // ---- coverage ---------------------------------------------------------------------------

    @Test
    fun `finish is refused until every stored item has an answer`() = runTest {
        val model = ready()
        model.mark("i1", here = true)
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(model.state.value.complete)
        model.finish()
        dispatcher.scheduler.advanceUntilIdle()
        // Not merely disabled on screen: a half-finished pass never becomes a request, because
        // the server would refuse it and the client already knows that.
        verify(repo, never()).verifyTote(any(), any(), any())
        assertEquals(0, wentBack)

        // The item that is already OUT of the bin is not part of coverage — answering the two
        // stored ones completes the pass.
        model.mark("i2", here = false)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.state.value.complete)
    }

    @Test
    fun `an empty bin can be verified without ticking anything`() = runTest {
        repo.stub { onBlocking { tote(any()) } doReturn bin.copy(items = emptyList(), itemCount = 0) }
        val model = ready()

        // The point, not an edge case: "checked, and it really is empty" is a fact the catalog
        // cannot hold any other way.
        assertTrue(model.state.value.complete)
        model.finish()
        dispatcher.scheduler.advanceUntilIdle()

        verify(repo).verifyTote("t1", emptyList(), emptyList())
    }

    // ---- what goes on the wire --------------------------------------------------------------

    @Test
    fun `the payload splits what is in the bin from what is not`() = runTest {
        val model = ready()
        model.mark("i1", here = true)
        model.mark("i2", here = false)
        dispatcher.scheduler.advanceUntilIdle()
        model.finish()
        dispatcher.scheduler.advanceUntilIdle()

        verify(repo).verifyTote("t1", listOf("i1"), listOf("i2"))
    }

    @Test
    fun `changing your mind about an item moves it, it does not appear twice`() = runTest {
        val model = ready()
        model.mark("i1", here = false)
        model.mark("i1", here = true)
        model.mark("i2", here = true)
        dispatcher.scheduler.advanceUntilIdle()
        model.finish()
        dispatcher.scheduler.advanceUntilIdle()

        // The server refuses an item that appears in both lists, so the two sets have to stay
        // disjoint here rather than being reconciled on the wire.
        verify(repo).verifyTote("t1", listOf("i1", "i2"), emptyList())
    }

    // ---- what the person hears --------------------------------------------------------------

    @Test
    fun `a clean pass says so and sends the screen back`() = runTest {
        val model = ready()
        model.mark("i1", here = true)
        model.mark("i2", here = true)
        dispatcher.scheduler.advanceUntilIdle()
        model.finish()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("Verified A14 — everything accounted for"), said)
        assertEquals(1, wentBack)
    }

    @Test
    fun `a pass that lost something names how many`() = runTest {
        repo.stub {
            onBlocking { verifyTote(any(), any(), any()) } doReturn
                VerifyOutDto(presentCount = 1, missingCount = 1, lastVerifiedAt = "2026-08-25")
        }
        val model = ready()
        model.mark("i1", here = true)
        model.mark("i2", here = false)
        dispatcher.scheduler.advanceUntilIdle()
        model.finish()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("Verified A14 — 1 marked missing"), said)
        assertEquals(1, wentBack)
    }

    @Test
    fun `a refusal is repeated in the server's own words`() = runTest {
        repo.stub { onBlocking { verifyTote(any(), any(), any()) } doThrow refused() }
        val model = ready()
        model.mark("i1", here = true)
        model.mark("i2", here = true)
        dispatcher.scheduler.advanceUntilIdle()
        model.finish()
        dispatcher.scheduler.advanceUntilIdle()

        // The sentence names WHICH part of the bin was not declared; "HTTP 422" names nothing,
        // and a generic fallback would send the diagnosis to the network.
        assertEquals(listOf("A14 has 2 stored items; 1 was declared."), said)
        // The screen stays put, with the ticks intact, so the pass can be finished.
        assertEquals(0, wentBack)
        assertFalse(model.state.value.submitting)
        assertTrue(model.state.value.complete)
    }
}
