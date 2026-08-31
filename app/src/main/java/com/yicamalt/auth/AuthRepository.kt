// FILE: AuthRepository.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Yi Cloud authentication: login, refresh, logout, session access; implements AuthProvider for M-HTTP.
//   SCOPE: validate credentials, exchange for tokens, persist via AuthStore, refresh on expiry; emit trace markers.
//   DEPENDS: M-CONFIG, M-HTTP (AuthProvider), M-LOCAL-DB (AuthStore)
//   LINKS: M-HTTP, M-UI-LOGIN
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.auth

import com.yicamalt.network.AuthProvider
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

// START_MODULE_MAP
//   AuthRepository - singleton auth facade; implements AuthProvider + LoginPort.
//   Clock - injectable time source for expiry testing.
//   LoginPort - thin login contract consumed by M-UI-LOGIN.
// END_MODULE_MAP

/** Injectable clock so expiry logic is deterministic in tests. */
interface Clock { fun nowMillis(): Long }
object SystemClock : Clock { override fun nowMillis(): Long = System.currentTimeMillis() }

/** Thin login contract consumed by the login view-model so it can be tested without the full repository. */
interface LoginPort {
    suspend fun login(email: String, password: String, method: AuthMethod = AuthMethod.PASSWORD): AuthSession
}

@Singleton
class AuthRepository @Inject constructor(
    private val api: YiCloudAuthApi,
    private val store: AuthStore,
    private val clock: Clock = SystemClock,
) : AuthProvider, LoginPort {

    @Volatile private var sessionRef: AuthSession? = store.getSession()
    private val refreshInFlight = AtomicReference<Any?>(null)

    /** Authenticate with email + password. Throws AuthError on failure. */
    override suspend fun login(email: String, password: String, method: AuthMethod): AuthSession {
        // START_BLOCK_VALIDATE_CREDENTIALS
        AuthLog.d("[Auth][login][BLOCK_VALIDATE_CREDENTIALS] method=${method.name} user=${email.redact()}")
        val response = api.login(LoginRequest(email, password, method.name.lowercase()))
        if (response.code != 200 || response.data == null) {
            AuthLog.d("[Auth][login][BLOCK_VALIDATE_CREDENTIALS] rejected code=${response.code}")
            throw AuthError.InvalidCredentials
        }
        // END_BLOCK_VALIDATE_CREDENTIALS

        val data = response.data!!
        val session = AuthSession(
            accessToken = data.accessToken,
            refreshToken = data.refreshToken,
            expiresAt = clock.nowMillis() + data.expiresIn * 1000L,
            userId = data.userId,
            authMethod = method,
        )
        // START_BLOCK_STORE_TOKEN
        store.putSession(session)
        sessionRef = session
        AuthLog.d("[Auth][login][BLOCK_STORE_TOKEN] session stored user=${session.userId} expiresAt=${session.expiresAt}")
        // END_BLOCK_STORE_TOKEN
        return session
    }

    /** Refresh the access token using the stored refresh token. Returns the new token, or null on failure. */
    override suspend fun refresh(): String? = doRefresh()?.accessToken

    /** Refresh and return the full updated session, or null on failure. */
    suspend fun refreshSession(): AuthSession? = doRefresh()

    private suspend fun doRefresh(): AuthSession? {
        val current = sessionRef ?: store.getSession() ?: return null
        // START_BLOCK_REFRESH_TOKEN
        AuthLog.d("[Auth][refresh][BLOCK_REFRESH_TOKEN] refreshing user=${current.userId}")
        val response = try { api.refresh(RefreshRequest(current.refreshToken)) } catch (t: Throwable) { null }
        if (response == null || response.code != 200 || response.data == null) {
            AuthLog.d("[Auth][refresh][BLOCK_REFRESH_TOKEN] refresh failed")
            return null
        }
        val data = response.data!!
        val refreshed = current.copy(
            accessToken = data.accessToken,
            refreshToken = data.refreshToken,
            expiresAt = clock.nowMillis() + data.expiresIn * 1000L,
        )
        store.putSession(refreshed)
        sessionRef = refreshed
        AuthLog.d("[Auth][refresh][BLOCK_REFRESH_TOKEN] refreshed user=${refreshed.userId}")
        // END_BLOCK_REFRESH_TOKEN
        return refreshed
    }

    override suspend fun getAccessToken(): String? {
        val s = sessionRef ?: return null
        if (clock.nowMillis() >= s.expiresAt) {
            // Token expired: attempt one refresh; return null if it fails.
            return doRefresh()?.accessToken
        }
        return s.accessToken
    }

    /** Current session without triggering refresh. */
    fun getSession(): AuthSession? = sessionRef

    /** Log out: clear store + in-memory session. */
    fun logout() {
        sessionRef = null
        store.clear()
        AuthLog.d("[Auth][logout][BLOCK_LOGOUT] session cleared")
    }

    private fun String.redact(): String =
        if (isEmpty()) "" else this.first() + "***@" + (substringAfterLast('@', "unknown"))
}

internal object AuthLog {
    fun d(message: String) = timber.log.Timber.d(message)
}