package com.tote.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tote.data.CaptureQueueRepository
import com.tote.data.remote.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The count on the Review tab: how much photographed-but-not-filed work is outstanding.
 *
 * It exists because an uncatalogued draft is work the person believes is finished and is not —
 * they photographed the thing, the bin is taped shut, and nothing says the item never made it
 * into the catalog. Rose attention channel, per the app's channel map.
 *
 * Deliberately **not** part of `ReviewViewModel`: that one is scoped to the Review screen, and a
 * badge that only appears once you visit the tab it is on is a badge that does nothing.
 *
 * Refreshed when the local queue changes — an upload finishing is the event that creates a draft —
 * and on a slow ticker as a backstop, because a draft can also appear from another device.
 */
@HiltViewModel
class DraftBadgeViewModel @Inject constructor(
    private val api: ApiService,
    queue: CaptureQueueRepository,
) : ViewModel() {

    private val _pending = MutableStateFlow(0)
    val pending: StateFlow<Int> = _pending.asStateFlow()

    /**
     * Captures stuck on this phone, for the Catalogue tab.
     *
     * Two badges meaning two different things: Review counts drafts waiting on a decision from
     * you; Catalogue counts uploads that cannot proceed without one. Both halves of the loop can
     * silently stall, and only one of them was ever visible.
     */
    val stuck: StateFlow<Int> = queue.stuckCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    init {
        viewModelScope.launch {
            queue.pendingCount.collectLatest { refresh() }
        }
        // The interval poll is NOT started here. `viewModelScope` is not lifecycle-aware, so a
        // `while (true)` in it kept asking the server every minute for the life of the process —
        // including while the app was backgrounded, which is radio and battery spent on a badge
        // nobody can see. ToteNav drives it inside `repeatOnLifecycle(RESUMED)` instead.
    }

    /** One poll. Public so the lifecycle-aware loop in ToteNav can drive it. */
    suspend fun refresh() {
        // Silent on failure: this is decoration on a nav bar. A tailnet blip must not raise an
        // error over whatever screen the person is actually using.
        runCatching { api.drafts().size }.onSuccess { _pending.value = it }
    }

    companion object {
        const val REFRESH_INTERVAL_MS = 60_000L
    }
}
