package com.tote.ui.auth

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tote.data.local.TokenStore
import com.tote.data.remote.SuiteAuthManager
import com.tote.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val suiteAuth: SuiteAuthManager,
    private val tokenStore: TokenStore,
) : ViewModel() {

    /**
     * null while the stored token is still being read.
     *
     * The tri-state matters: treating "not yet loaded" as "signed out" would flash the login
     * screen on every cold start for an already-signed-in user.
     */
    val signedIn: StateFlow<Boolean?> = tokenStore.accessToken
        .map { !it.isNullOrBlank() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _signInState = MutableStateFlow<UiState<Unit>>(UiState.Idle)
    val signInState: StateFlow<UiState<Unit>> = _signInState.asStateFlow()

    fun authorizeIntent(): Intent = suiteAuth.authorizeIntent()

    fun onSignInStarted() {
        _signInState.value = UiState.Loading
    }

    fun onRedirect(data: Intent?) {
        viewModelScope.launch {
            _signInState.value = UiState.Loading
            runCatching { suiteAuth.complete(data) }
                .onSuccess { _signInState.value = UiState.Success(Unit) }
                .onFailure {
                    // The message is shown to a human standing in a garage, so it has to say
                    // what to do, not what threw.
                    _signInState.value = UiState.Error(
                        it.message ?: "Sign-in failed. Check you are on the tailnet and retry."
                    )
                }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            tokenStore.clear()
            _signInState.value = UiState.Idle
        }
    }
}
