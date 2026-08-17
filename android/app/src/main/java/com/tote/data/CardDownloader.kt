package com.tote.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.tote.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fetches a tote's printable index card and hands it to the phone's PDF viewer.
 *
 * It used to `ACTION_VIEW` the card URL directly. That could never have worked: the endpoint
 * requires a bearer token, the token is attached only by this app's own OkHttp interceptor, and
 * an external browser has neither — so the tap opened a browser on a 401 while the bin screen
 * went on saying "no card printed" (the server only stamps `card_printed_at` on a successful
 * render). Photos were routed through the authenticated client for exactly this reason; the PDF
 * was not.
 *
 * So: download with the app's client, write it into cache, and share a `content://` URI. The
 * system viewer then provides print and share for free, which is the actual goal — the card is
 * meant to end up on paper, taped to a bin.
 */
@Singleton
class CardDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
) {

    /**
     * @return an intent that opens the card, or null if it could not be fetched — the caller
     *   says so out loud rather than launching something that will fail on its own.
     */
    suspend fun open(toteId: String, code: String): Intent? = withContext(Dispatchers.IO) {
        val url = "${BuildConfig.SERVER_URL.trimEnd('/')}/totes/$toteId/card"
        val bytes = runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.bytes()
            }
        }.getOrNull() ?: return@withContext null

        val dir = File(context.cacheDir, "cards").apply { mkdirs() }
        // Named by the human code, because this filename is what the print dialog and any
        // share target will show — "A14.pdf" beats a uuid on a sheet of paper.
        val file = File(dir, "$code.pdf").apply { writeBytes(bytes) }
        // The camera's existing provider, not a second one: a duplicate authority is an
        // install-time conflict, and the paths file already scopes what is shareable.
        val uri = FileProvider.getUriForFile(context, "com.tote.fileprovider", file)

        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
