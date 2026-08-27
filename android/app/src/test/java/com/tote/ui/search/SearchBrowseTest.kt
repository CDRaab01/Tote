package com.tote.ui.search

import com.tote.data.CatalogRepository
import com.tote.data.CatalogStats
import com.tote.data.remote.ApiService
import com.tote.data.remote.CategoryDto
import kotlin.test.assertEquals
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
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub

/** The browse chips on Find: used categories only, and offline the section simply vanishes. */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchBrowseTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repo: CatalogRepository
    private lateinit var api: ApiService

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo = mock {
            on { cachedStats } doReturn flowOf(CatalogStats(2, 43, 6))
        }
        api = mock {
            onBlocking { overdue() } doReturn emptyList()
            onBlocking { categories() } doReturn listOf(
                CategoryDto(id = "c1", name = "Baby", icon = "🍼", itemCount = 43),
                CategoryDto(id = "c2", name = "Books", icon = "📚", itemCount = 0),
                CategoryDto(id = "c3", name = "Tools", icon = "🔧", itemCount = 0),
            )
        }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `only used categories become chips`() = runTest {
        // Eleven empty seeded rows as chips would reproduce, on the home screen, the exact
        // picker clutter this feature removes.
        val model = SearchViewModel(repo, api)
        model.refresh()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("Baby"), model.state.value.usedCategories.map { it.name })
    }

    @Test
    fun `an unreachable server means no chips, not an error`() = runTest {
        api.stub {
            onBlocking { categories() } doAnswer { throw java.io.IOException("no route") }
        }
        val model = SearchViewModel(repo, api)
        model.refresh()
        dispatcher.scheduler.advanceUntilIdle()

        // The chips are an invitation, not a report — search still works against the cache,
        // and a "couldn't load browse" banner here would be noise over a working screen.
        assertTrue(model.state.value.usedCategories.isEmpty())
    }
}
