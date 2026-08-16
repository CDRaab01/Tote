package com.tote.nfc

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable

/** What went wrong writing a tag, in terms a person standing at a bin can act on. */
sealed interface TagWriteResult {
    data class Written(val uid: String, val truncatedSummary: Boolean) : TagWriteResult
    data object ReadOnly : TagWriteResult
    data class TooSmall(val needed: Int, val capacity: Int) : TagWriteResult
    data class Failed(val reason: String) : TagWriteResult
}

/**
 * NDEF payloads for a tote tag.
 *
 * Two records, deliberately:
 *
 * 1. **A URI** — the machine-readable half. The app's NDEF_DISCOVERED filter matches it and opens
 *    the tote directly; a phone without Tote follows it to the landing page.
 * 2. **A text summary** — the human half. Any phone's stock NFC reader shows it with no app
 *    installed, so a tap tells you *something* about the bin even on a guest's phone.
 *
 * The summary is a CACHE, written once. It goes stale as contents change, and that is fine: the
 * app never reads it, and the alternative — rewriting every tag whenever an item moves — would
 * mean a tag is wrong far more often than it is right.
 */
object TagIo {

    fun buildMessage(uri: String, summary: String): NdefMessage = NdefMessage(
        arrayOf(
            NdefRecord.createUri(uri),
            NdefRecord.createTextRecord("en", summary),
        )
    )

    fun uidOf(tag: Tag): String = tag.id.joinToString("") { "%02X".format(it) }

    /**
     * Write a tote onto a physical tag.
     *
     * Handles the three failure modes that actually happen, because each needs a different
     * response from the person holding the phone:
     *
     * * **read-only** — the tag was locked; nothing to do but use another one.
     * * **too small** — retried with the summary dropped, since the URI is the half that matters.
     *   A NTAG213 holds ~130 bytes and a long label plus a location will not fit.
     * * **moved away mid-write** — an IOException. The tag may be half-written, which is why the
     *   caller only records the uid on success.
     */
    fun write(tag: Tag, uri: String, summary: String): TagWriteResult {
        val uid = uidOf(tag)
        val full = buildMessage(uri, summary)
        val uriOnly = NdefMessage(arrayOf(NdefRecord.createUri(uri)))

        val ndef = Ndef.get(tag)
        if (ndef != null) {
            return try {
                ndef.connect()
                if (!ndef.isWritable) return TagWriteResult.ReadOnly
                val capacity = ndef.maxSize
                when {
                    full.toByteArray().size <= capacity -> {
                        ndef.writeNdefMessage(full)
                        TagWriteResult.Written(uid, truncatedSummary = false)
                    }
                    uriOnly.toByteArray().size <= capacity -> {
                        // Drop the summary, never the URI: the URI is what makes the tag work.
                        ndef.writeNdefMessage(uriOnly)
                        TagWriteResult.Written(uid, truncatedSummary = true)
                    }
                    else -> TagWriteResult.TooSmall(uriOnly.toByteArray().size, capacity)
                }
            } catch (e: Exception) {
                TagWriteResult.Failed(e.message ?: "The tag moved away before writing finished.")
            } finally {
                runCatching { ndef.close() }
            }
        }

        val formatable = NdefFormatable.get(tag)
            ?: return TagWriteResult.Failed("This tag can't store NDEF data.")
        return try {
            formatable.connect()
            formatable.format(full)
            TagWriteResult.Written(uid, truncatedSummary = false)
        } catch (e: Exception) {
            TagWriteResult.Failed(e.message ?: "The tag moved away before writing finished.")
        } finally {
            runCatching { formatable.close() }
        }
    }

    /** The code out of a tapped tag's URI, or null if it is not one of ours. */
    fun codeFromUri(uri: String?): String? {
        if (uri == null) return null
        val marker = "/t/"
        val i = uri.indexOf(marker)
        if (i < 0) return null
        return uri.substring(i + marker.length).substringBefore('/').substringBefore('?')
            .takeIf { it.isNotBlank() }
    }

    /**
     * The cached human summary.
     *
     * Kept short on purpose: small tags are common, and a summary that pushes the message past
     * capacity costs the URI record on the cheapest tags.
     */
    fun summaryFor(code: String, label: String?, location: String?, itemCount: Int): String =
        buildString {
            append("TOTE ").append(code)
            label?.takeIf { it.isNotBlank() }?.let { append(" — ").append(it.take(40)) }
            append("\n")
            location?.takeIf { it.isNotBlank() }?.let { append(it.take(30)).append(" · ") }
            append(itemCount).append(if (itemCount == 1) " item" else " items")
        }
}
