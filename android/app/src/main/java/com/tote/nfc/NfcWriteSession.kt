package com.tote.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/** What the write UI is currently doing. */
sealed interface WriteState {
    data object Idle : WriteState
    data object Waiting : WriteState
    data class Done(val truncatedSummary: Boolean) : WriteState
    data class Problem(val message: String) : WriteState
}

/**
 * A foreground reader-mode session, active only while the write sheet is open.
 *
 * Reader mode rather than the older foreground-dispatch API: it suppresses the system's own tag
 * handling (and the notification sound), so holding the phone to a tag while this sheet is open
 * cannot bounce the user out to the landing page they are trying to write. `SKIP_NDEF_CHECK` is
 * NOT set — the check is what tells us the tag is writable before we try.
 *
 * The session is disposed with the composable, so walking away from the sheet cannot leave the
 * radio in reader mode.
 */
@Composable
fun NfcWriteSession(enabled: Boolean, onTag: (android.nfc.Tag) -> Unit) {
    val context = LocalContext.current
    DisposableEffect(enabled) {
        val activity = context as? Activity
        val adapter = activity?.let { NfcAdapter.getDefaultAdapter(it) }
        if (enabled && activity != null && adapter != null) {
            adapter.enableReaderMode(
                activity,
                { tag -> onTag(tag) },
                NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_NFC_F or
                    NfcAdapter.FLAG_READER_NFC_V or
                    NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
                null,
            )
        }
        onDispose {
            if (activity != null && adapter != null) adapter.disableReaderMode(activity)
        }
    }
}

/** Whether this device can write tags at all, so the UI can say so instead of failing silently. */
fun hasNfc(context: android.content.Context): Boolean =
    NfcAdapter.getDefaultAdapter(context) != null
