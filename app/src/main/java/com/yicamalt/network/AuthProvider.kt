// FILE: AuthProvider.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Token provider contract consumed by the HTTP layer to attach + refresh credentials.
//   SCOPE: read current access token; refresh on 401. Implemented by M-AUTH.
//   DEPENDS: none (pure contract)
//   LINKS: M-HTTP, M-AUTH
//   ROLE: TYPES
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.network

// START_MODULE_MAP
//   AuthProvider - interface for token access + refresh.
// END_MODULE_MAP

interface AuthProvider {
    /** Current access token, or null when unauthenticated. */
    suspend fun getAccessToken(): String?

    /** Refresh and return a new access token, or null on failure. Called by the 401 authenticator. */
    suspend fun refresh(): String?
}