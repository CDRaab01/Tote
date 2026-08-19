package com.tote.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tote.data.CatalogRepository
import com.tote.data.remote.HouseholdDto
import com.tote.data.remote.InviteDto
import com.tote.util.ApiErrors
import com.tote.util.FeedbackBus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HouseholdState(
    /** Null until the server answers. Never guessed — see [loaded]. */
    val household: HouseholdDto? = null,
    /** An invitation waiting for THIS account, with its live merge preview. */
    val invite: InviteDto? = null,
    val loaded: Boolean = false,
    /** False when the last refresh could not reach the server, so the screen can say so rather
     *  than presenting what it happens to still be holding as current. */
    val reachable: Boolean = true,
    /** Set when the server refused a merge. Names the physical things blocking it. */
    val conflicts: Map<String, List<String>> = emptyMap(),
    val inviteEmail: String = "",
    val busy: Boolean = false,
    val confirmingLeave: Boolean = false,
    val confirmingAccept: Boolean = false,
)

/**
 * Settings -> Household.
 *
 * Almost all of the care here is around one call. `accept()` merges two catalogues and there is no
 * undo, so it is the only action in the app behind a confirmation that states a consequence rather
 * than asking "are you sure": the person is handing over their bins, and after the merge the two
 * of them have been moving each other's things, so there is no seam left to split along.
 */
@HiltViewModel
class HouseholdViewModel @Inject constructor(
    private val repo: CatalogRepository,
    private val feedback: FeedbackBus,
) : ViewModel() {

    private val _state = MutableStateFlow(HouseholdState())
    val state: StateFlow<HouseholdState> = _state.asStateFlow()

    init {
        refresh()
    }

    /**
     * Re-read both halves.
     *
     * The invite's conflict list is recomputed server-side on every read, which is the whole
     * reason this is a refresh and not a one-time load: renaming a colliding bin has to actually
     * clear the block, and a cached refusal would keep refusing a merge that is now fine.
     *
     * **A failure is not an answer.** This used to defend the household half and assign the
     * invitation half unconditionally, so one dropped request deleted a real, outstanding
     * invitation off the screen — and since this runs on every resume it was easy to hit and
     * healed itself on the next success, which made it read as a flicker rather than an error.
     * That is the app's oldest rule, broken again: a screen must never report "nothing" when it
     * means "could not find out".
     */
    fun refresh() {
        viewModelScope.launch {
            val household = runCatching { repo.household() }
            val invite = runCatching { repo.myInvite() }
            _state.value = _state.value.copy(
                household = household.getOrNull() ?: _state.value.household,
                invite = if (invite.isSuccess) invite.getOrNull() else _state.value.invite,
                loaded = true,
                reachable = household.isSuccess && invite.isSuccess,
            )
        }
    }

    fun onInviteEmail(value: String) {
        _state.value = _state.value.copy(inviteEmail = value)
    }

    fun invite() {
        val email = _state.value.inviteEmail.trim()
        if (email.isEmpty() || _state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            runCatching { repo.invite(email) }
                .onSuccess {
                    _state.value = _state.value.copy(household = it, inviteEmail = "")
                    feedback.say("Invited $email. Nothing is shared until they accept.")
                }
                .onFailure { feedback.say(ApiErrors.message(it, "Couldn't send that invite.")) }
            _state.value = _state.value.copy(busy = false)
        }
    }

    fun askAccept() {
        _state.value = _state.value.copy(confirmingAccept = true)
    }

    fun askLeave() {
        _state.value = _state.value.copy(confirmingLeave = true)
    }

    fun dismissDialogs() {
        _state.value = _state.value.copy(confirmingAccept = false, confirmingLeave = false)
    }

    /** Merge this catalogue into theirs. Irreversible; the cache is rebuilt from scratch after. */
    fun accept() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, confirmingAccept = false, conflicts = emptyMap())
            runCatching { repo.acceptInvite() }
                .onSuccess {
                    _state.value = _state.value.copy(household = it, invite = null)
                    feedback.say("You now share one catalogue.")
                }
                .onFailure { error ->
                    // A 409 here is an ANSWER, not a failure to retry: two bins in an attic are
                    // both called A14 and somebody has to go and look. Kept on screen rather than
                    // thrown at the snackbar, which would take the codes away with it.
                    _state.value = _state.value.copy(conflicts = ApiErrors.conflicts(error))
                    if (_state.value.conflicts.isEmpty()) {
                        feedback.say(ApiErrors.message(error, "Couldn't join that household."))
                    }
                    refresh()
                }
            _state.value = _state.value.copy(busy = false)
        }
    }

    fun decline() {
        viewModelScope.launch {
            runCatching { repo.declineInvite() }
                .onSuccess {
                    _state.value = _state.value.copy(invite = null, conflicts = emptyMap())
                    feedback.say("Invitation declined.")
                }
                .onFailure { feedback.say(ApiErrors.message(it, "Couldn't decline that.")) }
        }
    }

    fun remove(userId: String) {
        viewModelScope.launch {
            runCatching { repo.removeMember(userId) }
                .onSuccess {
                    feedback.say("Removed. The bins stay here.")
                    refresh()
                }
                .onFailure { feedback.say(ApiErrors.message(it, "Couldn't remove them.")) }
        }
    }

    fun transfer(userId: String) {
        viewModelScope.launch {
            runCatching { repo.transferOwnership(userId) }
                .onSuccess {
                    _state.value = _state.value.copy(household = it)
                    feedback.say("They own the household now.")
                }
                .onFailure { feedback.say(ApiErrors.message(it, "Couldn't transfer ownership.")) }
        }
    }

    fun leave() {
        viewModelScope.launch {
            _state.value = _state.value.copy(confirmingLeave = false, busy = true)
            runCatching { repo.leaveHousehold() }
                .onSuccess {
                    feedback.say("You've left. Your catalogue is empty again.")
                    refresh()
                }
                .onFailure { feedback.say(ApiErrors.message(it, "Couldn't leave.")) }
            _state.value = _state.value.copy(busy = false)
        }
    }
}
