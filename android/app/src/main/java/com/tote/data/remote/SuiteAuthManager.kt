package com.tote.data.remote

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.tote.data.local.TokenStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenRequest
import net.openid.appauth.TokenResponse as AppAuthTokenResponse

/**
 * "Sign in with Dragonfly" — OpenID Connect authorization-code + PKCE via AppAuth against the
 * suite identity server. The flow: launch a Custom Tab to the identity server, get a suite
 * token, then trade it at Tote's own `/auth/suite` for a Tote session. Because the Custom Tab
 * shares the system browser's session, once you have signed in there for one suite app the
 * others skip the login — that is the actual single sign-on. Tote is SSO-only: this is the ONLY
 * way in.
 *
 * The `tote` client id and the `com.tote:/oauth2redirect` URI are registered in dragonfly-id
 * (PR #31); changing either here without changing it there fails at the browser with an opaque
 * "invalid client" rather than anywhere useful.
 */
@Singleton
class SuiteAuthManager @Inject constructor(
    @ApplicationContext context: Context,
    private val api: ApiService,
    private val tokenStore: TokenStore,
) {
    private val serviceConfig = AuthorizationServiceConfiguration(
        Uri.parse("$ISSUER/authorize"),
        Uri.parse("$ISSUER/token"),
    )
    private val authService = AuthorizationService(context)

    /** Intent that launches the Dragonfly sign-in; launch it via an ActivityResult contract. */
    fun authorizeIntent(): Intent {
        val request = AuthorizationRequest.Builder(
            serviceConfig,
            CLIENT_ID,
            ResponseTypeValues.CODE,
            Uri.parse(REDIRECT_URI),
        ).setScopes("openid", "email").build()
        return authService.getAuthorizationRequestIntent(request)
    }

    /**
     * Handle the redirect result: exchange the code for a suite token (PKCE), trade that at
     * Tote's `/auth/suite` for a Tote session, and persist it. Throws on cancel/failure.
     */
    suspend fun complete(data: Intent?) {
        requireNotNull(data) { "Sign-in was canceled" }
        val response = AuthorizationResponse.fromIntent(data)
        val error = AuthorizationException.fromIntent(data)
        if (response == null) throw error ?: IllegalStateException("Sign-in was canceled")

        val suiteTokens = exchange(response.createTokenExchangeRequest())
        val suiteAccess = suiteTokens.accessToken
            ?: throw IllegalStateException("No suite token returned")
        val session = api.suiteLogin(SuiteLoginRequest(suiteAccess))
        tokenStore.save(session.accessToken, session.refreshToken)
    }

    private suspend fun exchange(request: TokenRequest): AppAuthTokenResponse =
        suspendCancellableCoroutine { cont ->
            // Public client (no secret) → AppAuth uses NoClientAuthentication for this overload.
            authService.performTokenRequest(request) { resp, ex ->
                if (resp != null) cont.resume(resp)
                else cont.resumeWithException(ex ?: IllegalStateException("Token exchange failed"))
            }
        }

    private companion object {
        const val ISSUER = "https://id.dragonflymedia.org"
        const val CLIENT_ID = "tote"
        const val REDIRECT_URI = "com.tote:/oauth2redirect"
    }
}
