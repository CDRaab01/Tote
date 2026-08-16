package com.tote.data.remote

import com.tote.data.local.SessionTokens
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * Renews the Tote session when the server says the access token is no longer good.
 *
 * Access tokens live 30 minutes. Before this existed there was no renewal path at all — the
 * client stored a refresh token it never used and the server had no endpoint to redeem it — so
 * half an hour after signing in every call 401'd forever, the app still considered itself signed
 * in (a stored token string is not a valid one), and the UI blamed the tailnet. That is a real
 * production incident (2026-08-16), not a hypothetical.
 *
 * OkHttp calls this only on a 401, and only on a request that already carried credentials.
 *
 * Three rules encoded below, each of which is a bug if dropped:
 *  - **Give up after one attempt.** Returning a request from here re-runs it, so a token the
 *    server keeps rejecting would loop forever.
 *  - **Only sign out on a 4xx.** A dead refresh token is unrecoverable and dropping to the login
 *    screen is right; an unreachable server is not, and clearing tokens there would log the user
 *    out for a Wi-Fi blip in the exact place this app is used — a garage with bad signal.
 *  - **One renewal at a time.** Refresh tokens rotate, so parallel calls 401ing together would
 *    otherwise race: the loser redeems an already-rotated token, gets a 401, and signs the user
 *    out mid-session. Whoever loses the lock re-reads the store and reuses the fresh token.
 */
@Singleton
class TokenAuthenticator @Inject constructor(
    private val tokenStore: SessionTokens,
    private val refreshApi: RefreshApi,
) : Authenticator {

    private val mutex = Mutex()

    override fun authenticate(route: Route?, response: Response): Request? {
        // `priorResponse` chains every retry OkHttp has already made for this call.
        if (response.priorResponse != null) return null

        val staleToken = response.request.header(AUTHORIZATION)?.removePrefix(BEARER)

        // runBlocking is correct here for the same reason it is in AuthInterceptor: OkHttp's
        // authenticator contract is synchronous and already off the main thread.
        return runBlocking {
            mutex.withLock {
                val current = tokenStore.currentAccessToken()
                if (current.isNullOrBlank()) return@withLock null
                // Someone else already renewed while this call waited for the lock.
                if (current != staleToken) return@withLock response.request.signedWith(current)

                val refreshToken = tokenStore.currentRefreshToken()
                if (refreshToken.isNullOrBlank()) {
                    tokenStore.clear()
                    return@withLock null
                }

                // A thrown exception here is network-shaped (the HTTP failure arrives as an
                // unsuccessful Response, not a throw): keep the credentials and fail this one
                // call. The next attempt once the tailnet is back renews normally.
                val renewed = runCatching { refreshApi.refresh(RefreshRequest(refreshToken)) }
                    .getOrNull() ?: return@withLock null

                val body = renewed.body()
                if (!renewed.isSuccessful || body == null) {
                    // The refresh token itself is dead (expired past its 7 days, or the server's
                    // signing key rotated). Clearing is what returns the app to the sign-in
                    // screen — `signedIn` is derived from the stored access token.
                    if (renewed.code() in 400..499) tokenStore.clear()
                    return@withLock null
                }

                tokenStore.save(body.accessToken, body.refreshToken)
                response.request.signedWith(body.accessToken)
            }
        }
    }

    private fun Request.signedWith(token: String): Request =
        newBuilder().header(AUTHORIZATION, "$BEARER$token").build()

    private companion object {
        const val AUTHORIZATION = "Authorization"
        const val BEARER = "Bearer "
    }
}
