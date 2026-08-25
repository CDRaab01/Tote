package com.tote.data

import com.tote.data.local.CachedItem
import com.tote.data.local.CachedTote
import com.tote.data.local.CatalogDao
import com.tote.data.remote.ApiService
import com.tote.data.remote.ItemDto
import com.tote.data.remote.LocationDto
import com.tote.data.remote.ToteDto
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking

/**
 * How the catalogue snapshot is fetched.
 *
 * Two properties, both measured problems rather than theory. The three calls run **concurrently**
 * — in series they were three times the latency on a path that runs after every single write. And
 * concurrent callers **collapse into one**: every tab refreshes in its ViewModel's `init` and
 * again on its first resume, so opening a screen fetched the whole catalogue twice within
 * milliseconds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogRefreshTest {

    private lateinit var api: ApiService
    private lateinit var dao: CatalogDao

    private fun repo() = CatalogRepository(api, dao)

    @Before
    fun setUp() {
        dao = mock()
        api = mock {
            onBlocking { totes(anyOrNull(), any()) } doReturn listOf(ToteDto(id = "t1", code = "A14"))
            onBlocking { items(anyOrNull(), anyOrNull()) } doReturn emptyList()
            onBlocking { locations() } doReturn listOf(LocationDto(id = "l1", name = "Attic"))
        }
    }

    @Test
    fun `the three calls are made concurrently, not in series`() = runTest {
        // Each call parks until released. If they ran in series, the second would never be
        // reached while the first is still parked — so all three being in flight at once is the
        // assertion.
        val totesGate = CompletableDeferred<Unit>()
        val started = mutableListOf<String>()
        api.stub {
            onBlocking { totes(anyOrNull(), any()) } doSuspendableAnswer {
                started += "totes"
                totesGate.await()
                listOf(ToteDto(id = "t1", code = "A14"))
            }
            onBlocking { items(anyOrNull(), anyOrNull()) } doSuspendableAnswer {
                started += "items"
                emptyList()
            }
            onBlocking { locations() } doSuspendableAnswer {
                started += "locations"
                listOf(LocationDto(id = "l1", name = "Attic"))
            }
        }

        val repo = repo()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repo.refresh() }
        testScheduler.advanceUntilIdle()

        // `items` and `locations` got going while `totes` was still parked.
        assertEquals(setOf("totes", "items", "locations"), started.toSet())

        totesGate.complete(Unit)
        job.join()
    }

    @Test
    fun `a second refresh while one is running does not fetch again`() = runTest {
        val gate = CompletableDeferred<Unit>()
        api.stub {
            onBlocking { totes(anyOrNull(), any()) } doSuspendableAnswer {
                gate.await()
                listOf(ToteDto(id = "t1", code = "A14"))
            }
        }
        val repo = repo()

        val first = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.refresh()
        }
        testScheduler.advanceUntilIdle()
        // The init-then-resume double, which is what this collapses.
        repo.refresh()

        gate.complete(Unit)
        first.join()

        verifyBlocking(api) { totes(anyOrNull(), any()) }
        verify(dao).replaceAll(any(), any())
    }

    @Test
    fun `a write waits its turn rather than skipping`() = runTest {
        // A skipped refresh after a write would leave the person looking at a catalogue that
        // does not contain the change they just made.
        val repo = repo()
        repo.refresh()
        repo.refresh(force = true)

        verify(dao, org.mockito.kotlin.times(2)).replaceAll(any(), any())
    }

    @Test
    fun `the snapshot carries the bin's colour, its verify stamp, and the place's photo flag`() =
        runTest {
            // Every new contract field must survive the sync, or the offline catalogue quietly
            // becomes a stripped-down copy of the online one — the reconstruction gap, again.
            api.stub {
                onBlocking { totes(anyOrNull(), any()) } doReturn listOf(
                    ToteDto(
                        id = "t1",
                        code = "A14",
                        locationId = "l1",
                        colorHex = "#B03030",
                        lastVerifiedAt = "2026-08-01T10:00:00Z",
                    ),
                )
                onBlocking { items(anyOrNull(), anyOrNull()) } doReturn listOf(
                    ItemDto(
                        id = "i1",
                        name = "Lights",
                        status = "stored",
                        currentToteId = "t1",
                        toteColorHex = "#B03030",
                    ),
                )
                onBlocking { locations() } doReturn listOf(
                    LocationDto(id = "l1", name = "Attic", hasPhoto = true),
                )
            }

            repo().refresh()

            val totes = argumentCaptor<List<CachedTote>>()
            val items = argumentCaptor<List<CachedItem>>()
            verifyBlocking(dao) { replaceAll(totes.capture(), items.capture()) }
            val tote = totes.firstValue.single()
            assertEquals("#B03030", tote.colorHex)
            assertEquals("2026-08-01T10:00:00Z", tote.lastVerifiedAt)
            assertTrue(tote.locationHasPhoto, "the location's photo flag should reach its bins")
            assertEquals("#B03030", items.firstValue.single().toteColorHex)
        }

    @Test
    fun `an unreachable locations call does not take the catalogue down with it`() = runTest {
        api.stub {
            onBlocking { locations() } doSuspendableAnswer { throw java.io.IOException("no route") }
        }
        val repo = repo()

        repo.refresh()

        // The bins and items still landed; only the name fallback was lost.
        verify(dao).replaceAll(any(), any())
    }
}
