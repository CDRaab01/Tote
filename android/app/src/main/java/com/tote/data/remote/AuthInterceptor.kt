package com.tote.data.remote

import com.tote.data.local.TokenStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Attaches the Tote session token to every request that isn't the login itself.
 *
 * `runBlocking` is correct here rather than lazy: OkHttp interceptors are synchronous by
 * contract and already run on a background dispatcher, so there is no main thread to block.
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val tokenStore: TokenStore,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // Never send a stale Tote token to the login endpoint: it takes a SUITE token in its
        // body, and an Authorization header there is at best noise and at worst confusing in a
        // failure log.
        if (request.url.encodedPath.endsWith("/auth/suite")) return chain.proceed(request)

        val token = runBlocking { tokenStore.currentAccessToken() }
        val authed = if (token.isNullOrBlank()) {
            request
        } else {
            request.newBuilder().header("Authorization", "Bearer $token").build()
        }
        return chain.proceed(authed)
    }
}
