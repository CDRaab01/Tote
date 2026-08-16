package com.tote.util

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * The app's one voice for the outcome of a write.
 *
 * Until this existed, filing an item said nothing: the form was replaced by the next draft and
 * the only evidence twenty minutes of cataloguing had landed anywhere was opening another tab —
 * which showed stale counts. And every write on the tote screen swallowed failure whole, so
 * offline in the attic (the documented normal condition) the buttons simply looked broken.
 *
 * A process-wide bus rather than per-screen snackbar state because the moment that needs it
 * most is precisely when the screen is gone: a queued upload fails after you've moved on, a
 * confirm lands as the review stack advances. The single [ToteNav] Scaffold is the one place a
 * snackbar can render, so it is the one collector.
 *
 * **The rule: only user-initiated writes speak.** A passive refresh that fails stays silent —
 * the screens carry their own offline states, and a snackbar per failed poll would turn a bad
 * Wi-Fi day into a notification stream that teaches dismissal.
 */
@Singleton
class FeedbackBus @Inject constructor() {

    // Buffered so a message emitted while no collector is resumed (mid-navigation) is not
    // dropped — DROP_OLDEST under pressure, because the newest outcome is the one that matters.
    private val _messages = MutableSharedFlow<String>(
        extraBufferCapacity = 8,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val messages: SharedFlow<String> = _messages

    /** Fire-and-forget: emission never suspends and never blocks a ViewModel. */
    fun say(message: String) {
        _messages.tryEmit(message)
    }
}


/** Hands the singleton bus to the one Compose scope that renders it (the nav Scaffold). */
@HiltViewModel
class FeedbackViewModel @Inject constructor(val bus: FeedbackBus) : ViewModel()
