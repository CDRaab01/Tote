package com.tote.ui.search

import androidx.lifecycle.SavedStateHandle
import com.tote.data.remote.ApiService
import com.tote.data.remote.ItemDto
import kotlin.test.assertEquals
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
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub

/** Loaded, empty and unreachable are three different screens — the house rule, asserted. */
@OptIn(ExperimentalCoroutinesApi::class)
class CategoryItemsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var api: ApiService

    private val lights = ItemDto(id = "i1", name = "Fairy lights", quantity = 1, status = "stored")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        api = mock { onBlocking { items(anyOrNull(), anyOrNull()) } doReturn listOf(lights) }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = CategoryItemsViewModel(
        api,
        SavedStateHandle(mapOf("categoryId" to "c1", "name" to "Christmas")),
    )

    @Test
    fun `loads the category's items and carries its name`() = runTest {
        val model = vm()
        model.refresh()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Christmas", model.state.value.name)
        assertEquals(listOf("Fairy lights"), model.state.value.items.map { it.name })
        assertFalse(model.state.value.unreachable)
    }

    @Test
    fun `unreachable keeps the last answer and says so`() = runTest {
        var reachable = true
        api.stub {
            onBlocking { items(anyOrNull(), anyOrNull()) } doAnswer {
                if (reachable) listOf(lights) else throw java.io.IOException("no route")
            }
        }
        val model = vm()
        model.refresh()
        dispatcher.scheduler.advanceUntilIdle()

        reachable = false
        model.refresh()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(model.state.value.unreachable)
        assertEquals(1, model.state.value.items.size, "the last good answer must survive")
    }

    @Test
    fun `empty is a real answer, not unreachable`() = runTest {
        api.stub { onBlocking { items(anyOrNull(), anyOrNull()) } doReturn emptyList() }
        val model = vm()
        model.refresh()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(model.state.value.loaded)
        assertTrue(model.state.value.items.isEmpty())
        assertFalse(model.state.value.unreachable)
    }
}
