package com.tote.data.remote

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface ApiService {
    /** The only login path — Tote is SSO-only and has no register/password endpoints. */
    @POST("auth/suite")
    suspend fun suiteLogin(@Body body: SuiteLoginRequest): TokenResponse

    @GET("users/me")
    suspend fun me(): UserDto
}
