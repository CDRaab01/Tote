package com.tote.nfc

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tote.data.CatalogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where a tap should send the user, once the code has been resolved against the server. */
sealed interface TapTarget {
    data class Tote(val id: String, val tagMismatch: Boolean) : TapTarget
    /** A tag whose code no longer matches any tote — a bin that was deleted, or someone else's. */
    data class Unknown(val code: String) : TapTarget
}

/**
 * Turns an NFC launch intent into a destination.
 *
 * The tag is only ever a *pointer*. Its URI gives a code, and the code is resolved against the
 * server for the real contents — so a tag written a year ago still works after the bin has been
 * renamed, relabelled, moved and refilled. Trusting the tag's cached text instead would mean
 * every content change silently invalidated a physical object in an attic.
 */
@HiltViewModel
class TapRouter @Inject constructor(
    private val repo: CatalogRepository,
) : ViewModel() {

    private val _target = MutableStateFlow<TapTarget?>(null)
    val target: StateFlow<TapTarget?> = _target.asStateFlow()

    fun onIntent(intent: Intent?) {
        val code = codeFrom(intent) ?: return
        viewModelScope.launch {
            val uid = intent?.let { tagUid(it) }
            runCatching { repo.resolveCode(code, uid) }
                .onSuccess { r ->
                    _target.value = r.toteId
                        ?.let { TapTarget.Tote(it, r.tagMismatch) }
                        ?: TapTarget.Unknown(code)
                }
                // Offline, the tap still shouldn't be a dead end — but without the server there
                // is no id to open, so say which bin was tapped and let search take over.
                .onFailure { _target.value = TapTarget.Unknown(code) }
        }
    }

    fun consumed() {
        _target.value = null
    }

    private fun tagUid(intent: Intent): String? {
        @Suppress("DEPRECATION")
        val tag = intent.getParcelableExtra<android.nfc.Tag>(NfcAdapter.EXTRA_TAG)
        return tag?.let(TagIo::uidOf)
    }

    companion object {
        /**
         * The code a launch intent points at.
         *
         * Reads the intent DATA first (what the manifest filter matched) and only then the raw
         * NDEF payload. They are normally the same; the payload is the fallback for a tag whose
         * first record is not the URI.
         */
        fun codeFrom(intent: Intent?): String? {
            if (intent == null) return null
            TagIo.codeFromUri(intent.dataString)?.let { return it }

            @Suppress("DEPRECATION")
            val raw = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES) ?: return null
            return raw.filterIsInstance<NdefMessage>()
                .flatMap { it.records.asList() }
                .firstNotNullOfOrNull { TagIo.codeFromUri(runCatching { it.toUri()?.toString() }.getOrNull()) }
        }
    }
}
