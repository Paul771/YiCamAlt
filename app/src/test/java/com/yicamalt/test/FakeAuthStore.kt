// FILE: FakeAuthStore.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: In-memory EncryptedSharedPreferences fake for token storage tests.
//   SCOPE: get/put/clear for token + refresh token; no Android Keystore roundtrip.
//   DEPENDS: none
//   LINKS: M-AUTH
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.test

// START_MODULE_MAP
//   FakeAuthStore - map-backed secure token store fake.
// END_MODULE_MAP

class FakeAuthStore {
    private val map = mutableMapOf<String, String>()

    fun putString(key: String, value: String) { map[key] = value }
    fun getString(key: String): String? = map[key]
    fun remove(key: String) { map.remove(key) }
    fun clear() { map.clear() }
    fun keys(): Set<String> = map.keys.toSet()

    companion object {
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_AUTH_METHOD = "auth_method"
    }
}