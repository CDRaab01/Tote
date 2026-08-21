package com.tote.ui.books

import com.tote.data.CatalogRepository
import com.tote.data.remote.ApiService
import com.tote.data.remote.DraftDto
import com.tote.data.remote.ScanIsbnRequest
import com.tote.data.remote.ScanIsbnResponse
import com.tote.util.FeedbackBus
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.times
import org.mockito.kotlin.verifyBlocking

/**
 * The shelf session, driven through a scripted scanner.
 *
 * The real scanner is GMS code that cannot run under Robolectric at all — the
 * [BookBarcodeScanner] seam exists precisely so these tests can hand the loop a sequence of
 * barcodes and assert what the session does with them. The guards (product barcode, in-session
 * duplicate) were checked against a guard-less VM before being kept, per the house rule.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookScanViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var api: ApiService
    private lateinit var repo: CatalogRepository

    private val fox = "9780140328721"
    private val matilda = "9780140327595"

    private fun draft(id: String = "i1", name: String = "Fantastic Mr. Fox", photos: Int = 1) =
        DraftDto(
            id = id,
            name = name,
            description = "by Roald Dahl · Puffin, 1988",
            quantity = 1,
            photoCount = photos,
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        api = mock {
            onBlocking { scanIsbn(any()) } doReturn ScanIsbnResponse(
                found = true, source = "openlibrary", item = draft()
            )
        }
        repo = mock {
            on { cachedTotes } doReturn emptyFlow()
        }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(scanner: BookBarcodeScanner = BookBarcodeScanner { null }) =
        BookScanViewModel(api, repo, FeedbackBus(), scanner)

    @Test
    fun `a scanned book files and the row fills in`() = runTest {
        val model = vm()
        model.onBarcode(fox)
        dispatcher.scheduler.advanceUntilIdle()

        val row = model.state.value.rows.single()
        assertEquals(BookRowStatus.FILED, row.status)
        assertEquals("Fantastic Mr. Fox", row.title)
        assertTrue(row.hasCover)
        // Bin counts follow, quietly.
        verifyBlocking(repo) { refresh() }
    }

    @Test
    fun `a product barcode never reaches the network`() = runTest {
        val model = vm()
        model.onBarcode("5012345678900") // valid EAN-13, not Bookland

        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.state.value.rows.isEmpty())
        verifyBlocking(api, never()) { scanIsbn(any()) }
    }

    @Test
    fun `scanning the same book twice in a session is skipped`() = runTest {
        val model = vm()
        model.onBarcode(fox)
        dispatcher.scheduler.advanceUntilIdle()
        model.onBarcode(fox)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, model.state.value.rows.size)
        verifyBlocking(api, times(1)) { scanIsbn(any()) }
    }

    @Test
    fun `a miss is a NOT_FOUND row, not a failure`() = runTest {
        api.stub {
            onBlocking { scanIsbn(any()) } doReturn ScanIsbnResponse(
                found = false, item = draft(name = "Unidentified book", photos = 0)
            )
        }
        val model = vm()
        model.onBarcode(fox)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(BookRowStatus.NOT_FOUND, model.state.value.rows.single().status)
    }

    @Test
    fun `a failed call is retryable with the SAME capture id`() = runTest {
        // The idempotency contract: a retry that minted a new id could file the book twice.
        var fail = true
        api.stub {
            onBlocking { scanIsbn(any()) } doAnswer {
                if (fail) throw java.io.IOException("no route")
                ScanIsbnResponse(found = true, item = draft())
            }
        }
        val model = vm()
        model.onBarcode(fox)
        dispatcher.scheduler.advanceUntilIdle()
        val failedRow = model.state.value.rows.single()
        assertEquals(BookRowStatus.FAILED, failedRow.status)

        fail = false
        model.retry(failedRow.captureId)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(BookRowStatus.FILED, model.state.value.rows.single().status)
        val bodies = argumentCaptor<ScanIsbnRequest>()
        verifyBlocking(api, times(2)) { scanIsbn(bodies.capture()) }
        assertEquals(
            bodies.firstValue.captureId,
            bodies.secondValue.captureId,
            "a retry must reuse the capture id, or a lost response files the book twice",
        )
    }

    @Test
    fun `the scan loop keeps going until the scanner is dismissed`() = runTest {
        val script = ArrayDeque(listOf(fox, matilda))
        val model = vm(scanner = BookBarcodeScanner { script.removeFirstOrNull() })

        model.startScanning()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, model.state.value.rows.size)
        assertTrue(!model.state.value.scanning, "the loop ends when the scanner returns null")
    }

    @Test
    fun `the destination rides on every request`() = runTest {
        val model = vm()
        model.setDestination("t9", "A14")
        model.onBarcode(fox)
        dispatcher.scheduler.advanceUntilIdle()

        val body = argumentCaptor<ScanIsbnRequest>()
        verifyBlocking(api) { scanIsbn(body.capture()) }
        assertEquals("t9", body.firstValue.toteId)
    }
}
