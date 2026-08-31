// FILE: YiCloudAuthApi.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Retrofit interface + DTOs for the Yi Cloud account/login and account/refresh endpoints.
//   SCOPE: login(request) and refresh(request); responses carry code + token data.
//   DEPENDS: M-HTTP (Retrofit)
//   LINKS: M-AUTH
//   ROLE: DATA
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST

// START_MODULE_MAP
//   YiCloudAuthApi - Retrofit service for account/login + account/refresh.
//   LoginRequest/LoginResponse/RefreshRequest - serialized DTOs.
// END_MODULE_MAP

@Serializable
data class LoginRequest(
    @SerialName("email") val email: String,
    @SerialName("password") val password: String,
    @SerialName("auth_method") val authMethod: String,
)

@Serializable
data class RefreshRequest(
    @SerialName("refresh_token") val refreshToken: String,
)

@Serializable
data class LoginResponse(
    @SerialName("code") val code: Int,
    @SerialName("message") val message: String? = null,
    @SerialName("data") val data: TokenData? = null,
)

@Serializable
data class TokenData(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("user_id") val userId: String,
)

interface YiCloudAuthApi {
    @POST("account/login")
    suspend fun login(@Body request: LoginRequest): LoginResponse

    @POST("account/refresh")
    suspend fun refresh(@Body request: RefreshRequest): LoginResponse
}