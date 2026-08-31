// FILE: AuthSession.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Auth session value object + auth error hierarchy for M-AUTH.
//   SCOPE: session fields (access/refresh token, expiry, userId, authMethod); typed errors.
//   DEPENDS: none
//   LINKS: M-AUTH, M-HTTP, M-LOCAL-DB
//   ROLE: TYPES
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.auth

// START_MODULE_MAP
//   AuthSession - authenticated session: tokens, expiry, user id, method.
//   AuthError - InvalidCredentials, TokenExpired, RefreshFailed, StoreFailed.
// END_MODULE_MAP

data class AuthSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,           // epoch millis
    val userId: String,
    val authMethod: AuthMethod,
)

enum class AuthMethod { PASSWORD, CLOUD_OAUTH }

sealed class AuthError(message: String) : Error(message) {
    object InvalidCredentials : AuthError("AUTH_INVALID_CREDENTIALS: credentials rejected by server")
    object TokenExpired : AuthError("AUTH_TOKEN_EXPIRED: session expired and refresh unavailable")
    object RefreshFailed : AuthError("AUTH_REFRESH_FAILED: refresh token rejected")
    object StoreFailed : AuthError("AUTH_STORE_FAILED: could not persist session to secure store")
}