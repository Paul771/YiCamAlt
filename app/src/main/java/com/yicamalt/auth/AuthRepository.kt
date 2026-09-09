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

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.4.1 - Login WORKS on gw-eu (code 20000 = success). Spinner deadlock fixed:
//     (a) token_secret now mapped as refresh token (REFRESH_KEYS),
//     (b) missing expires_in no longer yields an instantly-expired session
//         (DEFAULT_EXPIRES_SECONDS = 24h; real expiry still caught by the 401 authenticator),
//     (c) authInterceptor bypasses token lookup for auth_token refresh calls, breaking the
//         interceptor->refresh->interceptor recursion that starved the OkHttp dispatcher.
//     Deadlock regression test now uses the production HttpClientModule (with interceptors).
//   LAST_CHANGE: v0.3.2 - ROOT CAUSE FOUND: server expects HMAC-SHA256 of the password,
//     not plaintext (code 20261 = wrong password on plaintext; 20253 = account not found).
//     Reverse-engineered from official Yi Home dex (Lva/h login builder -> Lmc/d2.a):
//     Base64(NO_WRAP, HMAC-SHA256(key="KXLiUdAsO81ycDyEJAeETC$KklXdz3AC", msg=password UTF-8)).
//     hmacPassword() implements it; pin test added (AuthRepositoryTest).
//   LAST_CHANGE: v0.3.1 - Reverted unfounded 'login' query-param rename back to 'account'
//     (live-API ladder: unrecognized account param => 20250, account+password => 20253).
//     Failure log now includes server message so device logs reveal the rejection reason.
// END_CHANGE_SUMMARY

/** Injectable clock so expiry logic is deterministic in tests. */
interface Clock { fun nowMillis(): Long }
object SystemClock : Clock { override fun nowMillis(): Long = System.currentTimeMillis() }

/** HMAC key the official Yi Home app uses for the login password digest. Do not rename. */
internal const val AUTH_HMAC_ALGORITHM = "HmacSHA256"
internal const val AUTH_HMAC_KEY = "KXLiUdAsO81ycDyEJAeETC\$KklXdz3AC"

/**
 * /v4/users/login success (code 20000) carries token/token_secret but NO expires_in.
 * Treating the token as non-expiring avoids an immediate refresh loop; a real expiry
 * is still caught by the 401 authenticator path.
 */
internal const val DEFAULT_EXPIRES_SECONDS = 24L * 60 * 60

/** Thin contract consumed by M-UI login/view so tests need no Retrofit/api. */
interface LoginPort {
    suspend fun login(email: String, password: String, method: AuthMethod = AuthMethod.PASSWORD): AuthSession
}

/** Read-only session source for M-CAMERA-LIST request signing (token+secret from login). */
interface SessionSource {
    fun currentSession(): AuthSession?
}

@Singleton
class AuthRepository @Inject constructor(
    private val api: YiCloudAuthApi,
    private val store: AuthStore,
    private val clock: Clock = SystemClock,
) : AuthProvider, LoginPort, SessionSource {

    @Volatile private var sessionRef: AuthSession? = store.getSession()
    private val refreshInFlight = AtomicReference<Any?>(null)

    /** Authenticate with email + password. Throws AuthError on failure. */
    override suspend fun login(email: String, password: String, method: AuthMethod): AuthSession {
        // START_BLOCK_VALIDATE_CREDENTIALS
        AuthLog.d("[Auth][login][BLOCK_VALIDATE_CREDENTIALS] method=${method.name} user=${email.redact()}")
        val response = api.login(
            seq = "1",
            account = email,
            password = hmacPassword(password),
            devName = android.os.Build.BRAND ?: "",
            devType = android.os.Build.MODEL ?: "",
            devOsVersion = "Android " + (android.os.Build.VERSION.RELEASE ?: ""),
        )
        val tokens = extractTokens(response.data)
        if (tokens.accessToken.isNullOrBlank()) {
            AuthLog.d(
                "[Auth][login][BLOCK_VALIDATE_CREDENTIALS] rejected code=${response.code} " +
                    "message=${response.message ?: "-"}",
            )
            throw AuthError.InvalidCredentials
        }
        // END_BLOCK_VALIDATE_CREDENTIALS

        val session = AuthSession(
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken ?: "",
            expiresAt = clock.nowMillis() + (tokens.expiresIn ?: DEFAULT_EXPIRES_SECONDS) * 1000L,
            userId = tokens.userId ?: "",
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
        val tokens = response?.let { extractTokens(it.data) }
        if (tokens?.accessToken.isNullOrBlank()) {
            AuthLog.d("[Auth][refresh][BLOCK_REFRESH_TOKEN] refresh failed")
            return null
        }
        val refreshed = current.copy(
            accessToken = tokens?.accessToken ?: return null,
            refreshToken = tokens?.refreshToken ?: current.refreshToken,
            expiresAt = clock.nowMillis() + (tokens?.expiresIn ?: DEFAULT_EXPIRES_SECONDS) * 1000L,
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

    /** SessionSource impl for M-CAMERA-LIST request signing. */
    override fun currentSession(): AuthSession? = sessionRef

    /** Log out: clear store + in-memory session. */
    fun logout() {
        sessionRef = null
        store.clear()
        AuthLog.d("[Auth][logout][BLOCK_LOGOUT] session cleared")
    }

    private fun String.redact(): String =
        if (isEmpty()) "" else this.first() + "***@" + (substringAfterLast('@', "unknown"))

    /** Shape-tolerant token extraction; see ResponseTokens for accepted envelopes. */
    private fun extractTokens(element: kotlinx.serialization.json.JsonElement?): Tokens =
        ResponseTokens.extract(element)

    /**
     * Yi Cloud sends only the HMAC-SHA256 digest of the password (reverse-engineered
     * from the official Yi Home app: Lmc/d2.a — Mac("HmacSHA256") + Base64 NO_WRAP).
     * internal-visible so the exact wire format stays pinned by tests.
     */
    internal fun hmacPassword(password: String): String {
        val mac = javax.crypto.Mac.getInstance(AUTH_HMAC_ALGORITHM)
        mac.init(javax.crypto.spec.SecretKeySpec(AUTH_HMAC_KEY.toByteArray(Charsets.UTF_8), AUTH_HMAC_ALGORITHM))
        val digest = mac.doFinal(password.toByteArray(Charsets.UTF_8))
        return java.util.Base64.getEncoder().encodeToString(digest)
    }
}

/** Token bundle extracted from an arbitrary Yi Cloud response envelope. */
internal data class Tokens(
    val accessToken: String?,
    val refreshToken: String?,
    val expiresIn: Long?,
    val userId: String?,
)

/**
 * Shape-tolerant token extraction. The success envelope of /v4/users/login is not fully
 * confirmed (code 20000 + 407-byte body was observed; error bodies are 16 bytes), so this
 * walker accepts: data.* tokens, top-level tokens, and JSON-encoded data strings.
 * START_BLOCK_EXTRACT_TOKENS
 */
internal object ResponseTokens {

    private val ACCESS_KEYS = setOf("access_token", "accessToken", "token", "at")
    private val REFRESH_KEYS = setOf("refresh_token", "refreshToken", "rt", "token_secret", "tokenSecret")
    private val EXPIRES_KEYS = setOf("expires_in", "expiresIn", "expire_in", "expires")
    private val USER_KEYS = setOf("user_id", "userId", "uid", "userid", "userid")

    fun extract(element: kotlinx.serialization.json.JsonElement?): Tokens {
        val access = mutableListOf<String>()
        val refresh = mutableListOf<String>()
        var expiresIn: Long? = null
        var userId: String? = null
        walk(element, access, refresh, 0) { k, v ->
            when {
                k in EXPIRES_KEYS -> expiresIn = v.toLongOrNull() ?: expiresIn
                k in USER_KEYS -> if (userId.isNullOrBlank()) userId = v
            }
        }
        return Tokens(access.firstOrNull(), refresh.firstOrNull(), expiresIn, userId)
    }

    private fun walk(
        element: kotlinx.serialization.json.JsonElement?,
        access: MutableList<String>,
        refresh: MutableList<String>,
        depth: Int,
        onScalar: (String, String) -> Unit,
    ) {
        if (element == null || depth > 4) return
        when (element) {
            is kotlinx.serialization.json.JsonObject -> {
                element.forEach { (key, value) ->
                    if (key in ACCESS_KEYS && value.isStringOrPrimitive()) {
                        access += value.primitiveContent()
                    } else if (key in REFRESH_KEYS && value.isStringOrPrimitive()) {
                        refresh += value.primitiveContent()
                    } else if (!value.isStringOrPrimitive() && value !is kotlinx.serialization.json.JsonArray) {
                        walk(value, access, refresh, depth + 1, onScalar)
                    } else if (value.isStringOrPrimitive()) {
                        onScalar(key, value.primitiveContent())
                    }
                }
            }
            is kotlinx.serialization.json.JsonPrimitive -> {
                // data may be a JSON-encoded string: {"data":"{\"access_token\":...}"}
                val content = element.content
                if (content.trim().startsWith("{")) {
                    runCatching {
                        walk(kotlinx.serialization.json.Json.parseToJsonElement(content), access, refresh, depth + 1, onScalar)
                    }
                }
            }
            is kotlinx.serialization.json.JsonArray -> {
                element.forEach { walk(it, access, refresh, depth + 1, onScalar) }
            }
        }
    }

    private fun kotlinx.serialization.json.JsonElement.isStringOrPrimitive(): Boolean =
        this is kotlinx.serialization.json.JsonPrimitive

    private fun kotlinx.serialization.json.JsonElement.primitiveContent(): String =
        (this as kotlinx.serialization.json.JsonPrimitive).content
}
// END_BLOCK_EXTRACT_TOKENS

internal object AuthLog {
    fun d(message: String) = timber.log.Timber.d(message)
}