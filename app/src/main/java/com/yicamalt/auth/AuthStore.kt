// FILE: AuthStore.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Secure session persistence contract + EncryptedSharedPreferences production implementation.
//   SCOPE: persist/retrieve/clear an AuthSession in Android Keystore-backed encrypted prefs.
//   DEPENDS: M-AUTH (AuthSession)
//   LINKS: M-LOCAL-DB
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

// START_MODULE_MAP
//   AuthStore - interface for session persistence.
//   EncryptedAuthStore - production impl using EncryptedSharedPreferences.
// END_MODULE_MAP

interface AuthStore {
    fun putSession(session: AuthSession)
    fun getSession(): AuthSession?
    fun clear()
}

@Singleton
class EncryptedAuthStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : AuthStore {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, "yicamalt_auth", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    // START_BLOCK_STORE_SESSION
    override fun putSession(session: AuthSession) {
        prefs.edit()
            .putString(KEY_ACCESS, session.accessToken)
            .putString(KEY_REFRESH, session.refreshToken)
            .putLong(KEY_EXPIRES, session.expiresAt)
            .putString(KEY_USER, session.userId)
            .putString(KEY_METHOD, session.authMethod.name)
            .apply()
    }
    // END_BLOCK_STORE_SESSION

    override fun getSession(): AuthSession? {
        val access = prefs.getString(KEY_ACCESS, null) ?: return null
        val refresh = prefs.getString(KEY_REFRESH, null) ?: return null
        val expires = prefs.getLong(KEY_EXPIRES, 0L)
        val user = prefs.getString(KEY_USER, null) ?: return null
        val method = AuthMethod.valueOf(prefs.getString(KEY_METHOD, AuthMethod.PASSWORD.name)!!)
        return AuthSession(access, refresh, expires, user, method)
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_EXPIRES = "expires_at"
        private const val KEY_USER = "user_id"
        private const val KEY_METHOD = "auth_method"
    }
}