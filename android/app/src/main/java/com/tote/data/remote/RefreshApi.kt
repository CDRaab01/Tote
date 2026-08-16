package com.tote.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * The session-renewal call, deliberately its own tiny interface on its own OkHttp client.
 *
 * It must not go through [AuthInterceptor] or [TokenAuthenticator]: a 401 from a renewal
 * attempted *because of* a 401 would recurse, and attaching the dead access token to the renewal
 * would be pointless noise.
 *
 * Returns a raw [Response] rather than the body, because the authenticator's decision hinges on
 * *why* it failed — a 4xx means the refresh token is dead and the user must sign in again, an
 * IOException means the tailnet is unreachable and the tokens must be kept. Collapsing those
 * two into "it threw" would sign the user out every time the Wi-Fi dropped in the garage.
 */
interface RefreshApi {
    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): Response<TokenResponse>
}
