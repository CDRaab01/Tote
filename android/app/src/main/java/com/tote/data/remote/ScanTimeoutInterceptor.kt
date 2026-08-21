package com.tote.data.remote

import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Gives `POST /items/scan` — and only that call — a timeout long enough to survive it.
 *
 * Tote's scan endpoint is **synchronous**. It persists the photos, runs rembg/Pillow cleanup on
 * each one, sends the originals to the vision model and saves the draft, all before it writes a
 * response. A single photo measured **35.5 s** against the live model on the host; cleanup is
 * sequential per photo, so a full eight-photo item can run several times longer, and the server's
 * own model timeout is 60 s on top of the cleanup.
 *
 * OkHttp's default read timeout is 10 s. Without this, *every* scan fails — and it fails as a
 * `SocketTimeoutException`, which the capture queue is obliged to treat as "nobody knows whether
 * it landed", so the queue would fill with unresolvable rows for uploads the server was quietly
 * completing all along. The symptom would read as a broken camera.
 *
 * Scoped to the one path rather than raised globally on purpose: a four-minute read timeout on
 * `GET /search` would turn a dead tailnet connection into a screen that hangs for four minutes
 * instead of failing fast and falling back to the offline cache.
 */
@Singleton
class ScanTimeoutInterceptor @Inject constructor() : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath
        // NOTE: scan-isbn does NOT end with "/items/scan" — the two paths need their own cases.
        if (path.endsWith(ISBN_PATH)) {
            return chain
                .withReadTimeout(ISBN_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .proceed(request)
        }
        if (!path.endsWith(SCAN_PATH)) return chain.proceed(request)
        return chain
            .withReadTimeout(SCAN_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .withWriteTimeout(SCAN_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .proceed(request)
    }

    companion object {
        const val SCAN_PATH = "/items/scan"
        const val ISBN_PATH = "/items/scan-isbn"

        /** Server-side worst case is roughly eight cleanups plus a 60 s model call; this is that
         *  with room to spare, so a timeout here means something is genuinely wrong. */
        const val SCAN_READ_TIMEOUT_SECONDS = 240

        /** Eight downscaled JPEGs over the sort of Wi-Fi a garage has. */
        const val SCAN_WRITE_TIMEOUT_SECONDS = 120

        /** The server bounds the whole lookup at 30 s (`asyncio.timeout` in services/books.py);
         *  this must sit OUTSIDE that or a cold OpenLibrary chain manufactures a FAILED row
         *  over a filing that succeeded. OkHttp's 10 s default would trip on every cold call. */
        const val ISBN_READ_TIMEOUT_SECONDS = 45
    }
}
