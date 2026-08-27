package com.tote.ui.search

import androidx.lifecycle.SavedStateHandle
import com.tote.data.CatalogRepository
import com.tote.data.local.CatalogDao
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
    private lateinit var dao: CatalogDao

    private val lights = ItemDto(id = "i1", name = "Fairy lights", quantity = 1, status = "stored")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        dao = mock()
        api = mock {
            onBlocking {
                items(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
            } doReturn listOf(lights)
        }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = CategoryItemsViewModel(
        CatalogRepository(api, dao),
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
            onBlocking { items(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()) } doAnswer {
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
        api.stub { onBlocking { items(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()) } doReturn emptyList() }
        val model = vm()
        model.refresh()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(model.state.value.loaded)
        assertTrue(model.state.value.items.isEmpty())
        assertFalse(model.state.value.unreachable)
    }

    @Test
    fun `every item in the category is fetched, not the first page`() = runTest {
        // The chip that opens this screen carries an UNCAPPED server count while the screen
        // itself listed at most 200 rows — a contradiction one tap apart, and the same
        // truncation that hollowed out the offline cache. Both walk the same helper now.
        api.stub {
            onBlocking { items(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()) }
                .doAnswer { call ->
                    val offset = call.getArgument<Int?>(3) ?: 0
                    val from = if (offset == 0) 0 else 500
                    val size = if (offset == 0) 500 else 40
                    (from until from + size).map {
                        ItemDto(id = "i$it", name = "Bauble $it", quantity = 1, status = "stored")
                    }
                }
        }

        val model = vm()
        model.refresh()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(540, model.state.value.items.size)
    }
}
