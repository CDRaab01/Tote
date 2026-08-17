package com.tote.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Re-read whenever the screen comes back.
 *
 * Every tab ViewModel used to refresh only in `init`, and `tabTo` deliberately preserves them
 * (`saveState`/`restoreState`) — so Find's counters, the overdue card, the people list and the
 * tote list were all frozen at whatever they said the first time that tab was opened, for the
 * life of the process. There was no pull-to-refresh either. The review stack hit this first and
 * fixed it locally; this is that same fix, extracted, so the next screen gets it for free.
 *
 * Refreshes must be idempotent and quiet: this fires on every tab switch, and a screen that
 * announced or reset scroll position on resume would be worse than the staleness it cures.
 */
@Composable
fun RefreshOnResume(onResume: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onResume()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}
