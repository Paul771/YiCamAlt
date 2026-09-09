// FILE: YiCloudAuthApi.kt
// VERSION: 0.4.0
// START_MODULE_CONTRACT
//   PURPOSE: Retrofit interface + DTOs for the Yi Cloud login endpoints (reverse-engineered).
//   SCOPE: login(request) and refresh(request); responses carry code + token payload.
//     Success envelope shape is not fully confirmed: tokens may sit under data.*, at the
//     top level, or inside a JSON-encoded data string — so data is kept as a raw JsonElement
//     and token extraction lives in AuthRepository.extractTokens (shape-tolerant).
//   DEPENDS: M-HTTP (Retrofit)
//   LINKS: M-AUTH
//   ROLE: DATA
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

// START_MODULE_MAP
//   YiCloudAuthApi - Retrofit service for /v4/users/login (GET) + /v4/users/auth_token.
//   LoginRequest/RefreshRequest - serialized request DTOs.
//   LoginResponse - code/message envelope; data kept as raw JsonElement (shape-tolerant).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.4.0 - Real login attempt returned code=20000 with a 407-byte body
//     (error bodies are 16 bytes) — likely success with tokens in an unconfirmed shape.
//     data switched from TokenData to raw JsonElement; extraction moved to
//     AuthRepository.extractTokens. Body logging (masked) added in M-HTTP for evidence.
//   LAST_CHANGE: v0.3.1 - Reverted 'login' query param to 'account': live gw-*.xiaoyi.com
//     probes show account+password is the only recognized shape (others => code 20250).
// END_CHANGE_SUMMARY

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
    @SerialName("data") val data: JsonElement? = null,
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