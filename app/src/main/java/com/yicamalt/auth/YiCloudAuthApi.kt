// FILE: YiCloudAuthApi.kt
// VERSION: 0.3.0
// START_MODULE_CONTRACT
//   PURPOSE: Retrofit interface + DTOs for the Yi Cloud login endpoints (reverse-engineered).
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
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

// START_MODULE_MAP
//   YiCloudAuthApi - Retrofit service for /v4/users/login (GET) + /v4/users/auth_token.
//   LoginRequest/LoginResponse/RefreshRequest - serialized DTOs.
// END_MODULE_MAP

@Serializable
data class LoginRequest(
    @SerialName("seq") val seq: String = "1",
    @SerialName("account") val account: String,
    @SerialName("password") val password: String,
    @SerialName("dev_name") val devName: String = "",
    @SerialName("dev_type") val devType: String = "",
    @SerialName("dev_os_version") val devOsVersion: String = "",
)

@Serializable
data class RefreshRequest(
    @SerialName("refresh_token") val refreshToken: String,
)

@Serializable
data class LoginResponse(
    @SerialName("code") val code: String = "",
    @SerialName("message") val message: String? = null,
    @SerialName("data") val data: TokenData? = null,
)

@Serializable
data class TokenData(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    @SerialName("user_id") val userId: String? = null,
)

interface YiCloudAuthApi {
    @GET("v4/users/login")
    suspend fun login(
        @Query("seq") seq: String = "1",
        @Query("account") account: String,
        @Query("password") password: String,
        @Query("dev_name") devName: String = "",
        @Query("dev_type") devType: String = "",
        @Query("dev_os_version") devOsVersion: String = "",
    ): LoginResponse

    @POST("v4/users/auth_token")
    suspend fun refresh(@Body request: RefreshRequest): LoginResponse
}