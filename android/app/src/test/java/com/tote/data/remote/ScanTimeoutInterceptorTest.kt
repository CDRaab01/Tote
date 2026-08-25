package com.tote.data.remote

import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The scan call gets a long read timeout; nothing else does.
 *
 * Both halves matter. Without the raise, every scan fails — `/items/scan` is synchronous and was
 * measured at 35.5 s for a single photo against a 10 s default, and the failure arrives as a
 * timeout, which the capture queue must treat as "unknown", so the queue would fill with
 * unresolvable rows for uploads that actually succeeded. Without the narrowing, a dead tailnet
 * connection would hang a search screen for four minutes instead of failing fast into the
 * offline cache.
 */
class ScanTimeoutInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    /** Records the timeouts the chain reports at the point the request is proceeded with. */
    private var observedReadMillis = -1
    private var observedWriteMillis = -1

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        client = OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(ScanTimeoutInterceptor())
            .addInterceptor { chain ->
                observedReadMillis = chain.readTimeoutMillis()
                observedWriteMillis = chain.writeTimeoutMillis()
                chain.proceed(chain.request())
            }
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun call(path: String) {
        server.enqueue(MockResponse().setBody("{}"))
        client.newCall(Request.Builder().url(server.url(path)).build()).execute().close()
    }

    @Test
    fun `the scan path gets the long timeouts`() {
        call("/items/scan")

        assertEquals(
            ScanTimeoutInterceptor.SCAN_READ_TIMEOUT_SECONDS * 1000,
            observedReadMillis,
        )
        assertEquals(
            ScanTimeoutInterceptor.SCAN_WRITE_TIMEOUT_SECONDS * 1000,
            observedWriteMillis,
        )
    }

    @Test
    fun `the rescan path gets the long read timeout`() {
        // The same pair of model calls as a scan, so the same read budget. This is exactly the
        // trap the isbn case above documents: "/drafts/{id}/rescan" does NOT end with
        // "/items/scan", so without its own case it would fall through to the client default and
        // every re-scan would fail at 10 s while the server answered normally.
        call("/drafts/8a1f/rescan")

        assertEquals(
            ScanTimeoutInterceptor.SCAN_READ_TIMEOUT_SECONDS * 1000,
            observedReadMillis,
        )
        // No upload: the request body is empty, which is the entire point of the endpoint.
        assertEquals(30_000, observedWriteMillis)
    }

    @Test
    fun `an ordinary call keeps the client's own timeouts`() {
        call("/search?q=ratchet")

        assertEquals(30_000, observedReadMillis)
        assertEquals(30_000, observedWriteMillis)
    }

    @Test
    fun `the isbn path gets its own read timeout`() {
        call("/items/scan-isbn")

        assertEquals(
            ScanTimeoutInterceptor.ISBN_READ_TIMEOUT_SECONDS * 1000,
            observedReadMillis,
        )
        // A JSON body has nothing slow to write.
        assertEquals(30_000, observedWriteMillis)
    }

    @Test
    fun `the isbn path is not mistaken for the photo path`() {
        // "/items/scan-isbn".endsWith("/items/scan") is false, but this pins it: the two paths
        // must never inherit each other's budgets, in either direction.
        call("/items/scan-isbn")
        assertTrue(observedReadMillis != ScanTimeoutInterceptor.SCAN_READ_TIMEOUT_SECONDS * 1000)
    }

    @Test
    fun `the isbn timeout sits outside the server's own budget`() {
        // The server bounds the whole lookup at 30 s (services/books.py OVERALL_BUDGET_SECONDS).
        // A client timeout inside that would manufacture FAILED rows over filings that
        // succeeded — the row would say "failed" while the book sits in the bin.
        assertTrue(ScanTimeoutInterceptor.ISBN_READ_TIMEOUT_SECONDS > 30)
    }

    @Test
    fun `the long timeout is long enough for a measured worst case`() {
        // A single photo measured 35.5 s on the live host, cleanup is sequential per photo, and
        // the server's own model timeout is 60 s on top. Anything at or below a minute would be
        // a cap that fires on ordinary use rather than on a fault.
        assertTrue(
            ScanTimeoutInterceptor.SCAN_READ_TIMEOUT_SECONDS >= 180,
            "the scan timeout must cover an eight-photo item, not a one-photo one",
        )
    }
}
