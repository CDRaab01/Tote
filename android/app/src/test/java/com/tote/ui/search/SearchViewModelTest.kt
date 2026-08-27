package com.tote.ui.search

import com.tote.data.CatalogRepository
import com.tote.data.CatalogStats
import com.tote.data.SearchResult
import com.tote.data.remote.ApiService
import com.tote.data.remote.ApparelDto
import com.tote.data.remote.HomeDto
import com.tote.data.remote.ItemDto
import com.tote.data.remote.NextSizeCardDto
import com.tote.data.remote.SeasonalCardDto
import com.tote.data.remote.SeasonalToteDto
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify

/**
 * The Find tab's ViewModel: size-chip narrowing, the close-match fallback, and the two
 * forward-looking home cards.
 *
 * The assertions are about the contracts a screenshot cannot see: which question actually goes
 * to the server (the chips re-ask rather than filter locally — the ladder has one writer), and
 * which parts of the state survive a failure versus vanish with it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repo: CatalogRepository
    private lateinit var api: ApiService

    private fun coat(id: String, size: String? = null) = ItemDto(
        id = id, name = "Winter coat $id", quantity = 1, status = "stored",
        toteCode = "A14", toteColorHex = "#7A1F2B",
        apparel = size?.let { ApparelDto(sizeRaw = it) },
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo = mock {
            on { cachedStats } doReturn flowOf(CatalogStats(0, 0, 0))
        }
        api = mock {
            onBlocking { overdue() } doReturn emptyList()
            onBlocking { categories() } doReturn emptyList()
        }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = SearchViewModel(repo, api)

    // ---- size chips -------------------------------------------------------------------------

    @Test
    fun `chips derive from the unfiltered hits and a new query resets the filter`() = runTest {
        repo.stub {
            onBlocking { search(eq("coat"), anyOrNull()) } doReturn SearchResult(
                items = listOf(coat("i1", "4T"), coat("i2", "4T"), coat("i3", "5T"), coat("i4")),
                offline = false,
            )
            onBlocking { search(eq("drill"), anyOrNull()) } doReturn SearchResult(
                items = listOf(coat("i9")),
                offline = false,
            )
        }
        val model = vm()
        model.onQueryChange("coat")
        dispatcher.scheduler.advanceUntilIdle()

        // Distinct sizes in first-appearance order — a vocabulary, not a histogram.
        assertEquals(listOf("4T", "5T"), model.state.value.sizes)

        model.onSizeSelect("4T")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("4T", model.state.value.sizeFilter)

        // A new query is a new question: the chip was chosen against the old hits, and carrying
        // it forward would silently narrow an answer nobody asked to narrow.
        model.onQueryChange("drill")
        dispatcher.scheduler.advanceUntilIdle()
        assertNull(model.state.value.sizeFilter)
        assertTrue(model.state.value.sizes.isEmpty())
    }

    @Test
    fun `selecting a size re-asks the server rather than filtering locally`() = runTest {
        repo.stub {
            onBlocking { search(eq("coat"), isNull()) } doReturn SearchResult(
                items = listOf(coat("i1", "4T"), coat("i2", "5T")),
                offline = false,
            )
            onBlocking { search(eq("coat"), eq("4T")) } doReturn SearchResult(
                items = listOf(coat("i1", "4T")),
                offline = false,
            )
        }
        val model = vm()
        model.onQueryChange("coat")
        dispatcher.scheduler.advanceUntilIdle()

        model.onSizeSelect("4T")
        dispatcher.scheduler.advanceUntilIdle()

        // The narrowing happens through the ladder, server-side — "4T" matching "4T / GIRLS" is
        // the server's judgement, not string equality here.
        verify(repo).search("coat", "4T")
        assertEquals(listOf("i1"), model.state.value.results.map { it.id })
        // And the chips stay derived from the UNFILTERED hits: re-deriving from the filtered
        // response would collapse the row to the one chip just chosen, with no way back to 5T.
        assertEquals(listOf("4T", "5T"), model.state.value.sizes)
    }

    @Test
    fun `offline hits grow no chips — the fallback cannot filter`() = runTest {
        repo.stub {
            onBlocking { search(eq("coat"), anyOrNull()) } doReturn SearchResult(
                items = listOf(coat("i1", "4T")),
                offline = true,
            )
        }
        val model = vm()
        model.onQueryChange("coat")
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(model.state.value.offline)
        // The cached hit still carries its size — the caption shows it — but a chip over
        // offline results would be a filter the LIKE fallback silently ignores.
        assertTrue(model.state.value.sizes.isEmpty())
    }

    // ---- close matches ----------------------------------------------------------------------

    @Test
    fun `close matches arrive only when nothing matched exactly`() = runTest {
        repo.stub {
            onBlocking { search(eq("welles"), anyOrNull()) } doReturn SearchResult(
                items = emptyList(),
                offline = false,
                close = listOf(coat("i5")),
            )
            onBlocking { search(eq("wellies"), anyOrNull()) } doReturn SearchResult(
                items = listOf(coat("i5")),
                offline = false,
            )
        }
        val model = vm()
        model.onQueryChange("welles")
        dispatcher.scheduler.advanceUntilIdle()

        // The near-misses live in their own list, never mixed into results — the screen keys
        // its "Close matches" section on exactly this split.
        assertTrue(model.state.value.results.isEmpty())
        assertEquals(listOf("i5"), model.state.value.close.map { it.id })

        // An exact answer clears them: the two kinds never share a response.
        model.onQueryChange("wellies")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("i5"), model.state.value.results.map { it.id })
        assertTrue(model.state.value.close.isEmpty())
    }

    // ---- home cards -------------------------------------------------------------------------

    @Test
    fun `home cards populate from the server and vanish when it cannot answer`() = runTest {
        repo.stub {
            onBlocking { home() } doReturn HomeDto(
                seasonal = SeasonalCardDto(
                    totes = listOf(SeasonalToteDto(id = "t1", code = "A14", colorHex = "#7A1F2B")),
                    locationName = "Attic",
                    unpackedOn = "2025-11-28",
                    itemCount = 37,
                    categoryName = "Christmas / decor",
                ),
                nextSize = NextSizeCardDto(
                    personId = "p1", personName = "Emma", nextLabel = "5T", garmentCount = 12,
                    totes = listOf(SeasonalToteDto(id = "t2", code = "B03")),
                ),
            )
        }
        val model = vm() // init { refresh() }
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Christmas / decor", model.state.value.seasonal?.categoryName)
        assertEquals("Emma", model.state.value.nextSize?.personName)

        // Unlike the overdue list, the cards VANISH on a failed refresh rather than standing:
        // each is an invitation to go open specific bins, and an invitation the server no
        // longer stands behind is worse than none.
        repo.stub {
            onBlocking { home() } doAnswer { throw java.io.IOException("no route") }
        }
        model.refresh()
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(model.state.value.seasonal)
        assertNull(model.state.value.nextSize)
    }

    @Test
    fun `the counts follow the cache rather than a one-shot read`() = runTest {
        // They used to be sampled once, straight after `refresh()` — which had two faults at
        // once. A later cache write never reached them, and `refresh(force = false)` returns
        // immediately when another refresh already holds the lock, so the sample could be taken
        // from the snapshot that was about to be replaced.
        val counts = MutableStateFlow(CatalogStats(0, 0, 0))
        repo.stub { on { cachedStats } doReturn counts }

        val model = vm()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(0, model.state.value.items)

        // No refresh() here on purpose: the write to the cache is the whole signal.
        counts.value = CatalogStats(totes = 7, items = 578, notInABin = 20)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(7, model.state.value.totes)
        assertEquals(578, model.state.value.items)
        assertEquals(20, model.state.value.notInABin)
    }
}
