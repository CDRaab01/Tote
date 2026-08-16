package com.tote.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SuiteLoginRequest(
    @SerialName("suite_token") val suiteToken: String,
)

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
)

@Serializable
data class UserDto(
    val id: String,
    val email: String,
    val name: String,
)
