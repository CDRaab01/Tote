package com.tote.ui.totes

import androidx.lifecycle.SavedStateHandle
import com.tote.data.CatalogRepository
import com.tote.data.remote.ApiService
import com.tote.util.FeedbackBus
import com.tote.data.remote.ItemDto
import com.tote.data.remote.ToteDetailDto
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import com.tote.util.UiState
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

/**
 * Deleting a row that should never have existed.
 *
 * The catalog gets things wrong in exactly one unrecoverable-by-editing way: a duplicate. Two
 * rows both reading "Toddler Bed Comforter" over one physical comforter is a bin that lies, and
 * a bin that lies once stops being believed. Disposal is a different operation and keeps its
 * history; this is for the row that was a mistake.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeleteItemTest {

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
            onBlocking { tote(any()) } doReturn ToteDetailDto(
                id = "t1",
                code = "D1",
                items = listOf(ItemDto(id = "i1", name = "Toddler Bed Comforter", status = "stored")),
            )
        }
        return ToteDetailViewModel(repo, api, FeedbackBus(), SavedStateHandle(mapOf("toteId" to "t1")))
    }

    @Test
    fun `deleting an item removes it and reloads the bin`() = runTest {
        val model = vm()
        dispatcher.scheduler.advanceUntilIdle()

        model.deleteItem("i1")
        dispatcher.scheduler.advanceUntilIdle()

        verify(repo).deleteItem(eq("i1"))
        // Reloaded rather than removed locally: the tote's item count and its "out of this tote"
        // section are both server-computed, and a locally-pruned list would disagree with them.
        verify(repo, times(2)).tote(eq("t1"))
    }

    @Test
    fun `a failed delete leaves the bin exactly as it was`() = runTest {
        val model = vm()
        dispatcher.scheduler.advanceUntilIdle()
        repo.stub {
            onBlocking { deleteItem(any()) } doAnswer { throw java.io.IOException("no route") }
        }

        model.deleteItem("i1")
        dispatcher.scheduler.advanceUntilIdle()

        // No reload, and no optimistic removal either: the item is still in the bin, which is
        // the truth. A list that drops a row the server still holds is a catalog that disagrees
        // with itself the next time anyone opens the screen.
        verify(repo, times(1)).tote(eq("t1"))
        val shown = (model.state.value as UiState.Success).data.items
        assertEquals(1, shown.size)
    }
}
