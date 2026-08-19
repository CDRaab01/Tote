package com.tote.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tote.data.CatalogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Whether an invitation is waiting, for the mark on the door to Settings.
 *
 * Sharing shipped with no way to find out you had been invited. Settings is reached through one
 * icon on the Find hero — deliberately, since it is an escape hatch rather than a daily
 * destination — so an invitation sat behind a door nobody had a reason to open, and the only
 * working delivery mechanism was telling the person out loud.
 *
 * Every other "something needs you" state in the app already carries a badge (drafts on Review,
 * stuck uploads on Catalogue). This is that, for the one thing on Settings that somebody else
 * started and that goes stale.
 */
@HiltViewModel
class InviteBadgeViewModel @Inject constructor(
    private val repo: CatalogRepository,
) : ViewModel() {

    private val _hasInvite = MutableStateFlow(false)
    val hasInvite: StateFlow<Boolean> = _hasInvite.asStateFlow()

    init {
        refresh()
    }

    /**
     * Idempotent and quiet — it runs on every resume.
     *
     * Written on **success only**, never cleared on failure. A dropped request is not evidence
     * that an invitation went away, and a badge that blinks off whenever the tailnet hiccups is
     * how somebody concludes they imagined it.
     */
    fun refresh() {
        viewModelScope.launch {
            runCatching { repo.myInvite() }.onSuccess { _hasInvite.value = it != null }
        }
    }
}
