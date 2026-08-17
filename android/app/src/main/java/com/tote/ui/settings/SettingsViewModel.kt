package com.tote.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tote.BuildConfig
import com.tote.data.local.TokenStore
import com.tote.data.remote.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsState(
    /** Null until the server answers, or if it cannot be reached — never guessed. */
    val email: String? = null,
    val version: String = BuildConfig.VERSION_NAME,
    val serverUrl: String = BuildConfig.SERVER_URL,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val api: ApiService,
    private val tokenStore: TokenStore,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // The first caller `users/me` has ever had. Silent on failure: this screen's job is
            // to be reachable when things are broken, so it must render offline — the version
            // and server URL are the two facts someone actually needs then, and both are local.
            runCatching { api.me() }.onSuccess { _state.value = _state.value.copy(email = it.email) }
        }
    }

    /**
     * Clear the session.
     *
     * `signedIn` is derived from the stored access token, so clearing it is what returns the app
     * to the sign-in screen — no navigation needed here. The capture queue is a separate Room
     * database and is deliberately untouched: those photographs exist nowhere else, and losing
     * them to a sign-out would make this button more dangerous than the problem it solves.
     */
    fun signOut() {
        viewModelScope.launch { tokenStore.clear() }
    }
}
