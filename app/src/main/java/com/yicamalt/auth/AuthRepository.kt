// FILE: AuthRepository.kt
// VERSION: 0.6.0
// START_MODULE_CONTRACT
//   PURPOSE: Yi Cloud authentication: login, refresh, logout, session access; implements AuthProvider for M-HTTP.
//   SCOPE: validate credentials, exchange for tokens, persist via AuthStore, refresh on expiry; emit trace markers.
//     Explicit login also finds the account's gateway (CAM-02): hop on code 20253, then region discovery by
//     device list; hard bound per login call: 3 login requests + 3 device-list requests, no retries.
//   DEPENDS: M-CONFIG (RegionSelector, YiRegion), M-HTTP (AuthProvider), M-LOCAL-DB (AuthStore)
//   LINKS: M-HTTP, M-UI-LOGIN, M-CONFIG, V-M-AUTH
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.auth

import com.yicamalt.config.RegionSelector
import com.yicamalt.config.YiRegion
import com.yicamalt.network.AuthProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

// START_MODULE_MAP
//   AuthRepository - singleton auth facade; implements AuthProvider + LoginPort.
//   Clock - injectable time source for expiry testing.
//   LoginPort - thin login contract consumed by M-UI-LOGIN.
//   SessionSource - read-only session access for request signing.
//   NoRegionSelector - default RegionSelector: no region, so no hop and no discovery.
//   ACCOUNT_NOT_FOUND_CODE / MAX_LOGIN_ATTEMPTS / MAX_DISCOVERY_CALLS - login region constants.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.6.0 - CAM-02: login region fallback on code 20253 (BLOCK_REGION_FALLBACK) and region
//     discovery by device list (BLOCK_REGION_DISCOVERY), both inside the explicit login call only.
//     Probe 2026-10-11: the same credentials log in on all three gateways and only one holds the cameras,
//     so after a successful login on R one signed device-list check runs on R; code 20200 / empty list =>
//     log in on the next untried region; first region with devices wins; none => first successful login.
//     BOUNDS per login call: each region is visited at most once (<= 3 logins, <= 3 list checks), counted
//     and capped by MAX_LOGIN_ATTEMPTS / MAX_DISCOVERY_CALLS, no retries, no timers; a rejection other
//     than 20253 stops the loop; an I/O error stops the loop and keeps the last good session; a manual or
//     unknown server (RegionSelector.currentRegion() == null) means exactly 1 login and 0 checks.
//     Refresh, getAccessToken and construction never call login or the check; a token refresh is
//     suppressed while the check runs. Only the final session is persisted. Logs: region name, code, count.
//   PREV_CHANGE: v0.5.0 - at most one refresh attempt per session expiry: refreshFailedForExpiresAt
//     memo (BLOCK_REFRESH_ONCE_PER_EXPIRY) shared by getAccessToken(), refresh() and refreshSession().
//     Before: every request on an expired session triggered POST /v4/users/auth_token. After a failed
//     refresh getAccessToken() returns the stale token without network; a new login re-arms the memo.
//   PREV_CHANGE: v0.4.2 - SEC-01: auth logs carry hasUser/expiresInSec instead of user id and epoch millis
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

/** Login code: the account does not exist on this gateway (the only code that makes login hop). */
internal const val ACCOUNT_NOT_FOUND_CODE = "20253"

/** Hard cap of GET /v4/users/login requests per login call. */
internal const val MAX_LOGIN_ATTEMPTS = 3

/** Hard cap of device-list checks per login call. */
internal const val MAX_DISCOVERY_CALLS = 3

/** Default selector: no known region, so login makes exactly one request and never changes the server. */
internal object NoRegionSelector : RegionSelector {
    override fun currentRegion(): YiRegion? = null
    override fun selectRegion(region: YiRegion) = Unit
    override fun currentBaseUrl(): String = ""
    override fun restoreBaseUrl(url: String) = Unit
}

@Singleton
class AuthRepository @Inject constructor(
    private val api: YiCloudAuthApi,
    private val store: AuthStore,
    private val clock: Clock = SystemClock,
    private val regions: RegionSelector = NoRegionSelector,
    private val devices: DeviceListCheckPort = NoDeviceListCheck,
) : AuthProvider, LoginPort, SessionSource {

    @Volatile private var sessionRef: AuthSession? = store.getSession()
    private val refreshInFlight = AtomicReference<Any?>(null)

    /** One login call at a time, so the per-call request bounds also hold for concurrent presses. */
    private val loginMutex = Mutex()

    /** True only while the discovery device-list check runs; a token refresh is suppressed meanwhile. */
    @Volatile private var discoveryInFlight = false

    /** A login that was accepted on [region] (null = manual or unknown server). */
    private class RegionLogin(val region: YiRegion?, val session: AuthSession)

    // START_CONTRACT: login
    //   PURPOSE: Authenticate with email + password and settle on the gateway that holds the account's devices.
    //   INPUTS: { email: String, password: String - sent only as an HMAC digest, method: AuthMethod }
    //   OUTPUTS: { AuthSession - the session of the kept region; throws AuthError.InvalidCredentials or the I/O error }
    //   SIDE_EFFECTS: <= 3 GET /v4/users/login + <= 3 GET /v5/devices/list; may switch the region through
    //     RegionSelector; persists exactly one session (the kept one)
    //   LINKS: M-CONFIG, M-HTTP, V-M-AUTH
    // END_CONTRACT: login
    /** Authenticate with email + password. Throws AuthError on failure. */
    override suspend fun login(email: String, password: String, method: AuthMethod): AuthSession = loginMutex.withLock {
        // START_BLOCK_VALIDATE_CREDENTIALS
        AuthLog.d("[Auth][login][BLOCK_VALIDATE_CREDENTIALS] method=${method.name} user=${email.redact()}")
        val digest = hmacPassword(password)
        val start = regions.currentRegion()
        // null start = manual or unknown server: one request, no hop, no discovery.
        val order: List<YiRegion?> =
            if (start == null) listOf(null) else YiRegion.orderStartingWith(start).take(MAX_LOGIN_ATTEMPTS)
        val discover = start != null && devices !== NoDeviceListCheck
        val originalBaseUrl = regions.currentBaseUrl()
        val previousSession = sessionRef
        var selected: YiRegion? = start
        var logins = 0
        var lists = 0
        var firstGood: RegionLogin? = null
        var lastGood: RegionLogin? = null
        var kept: RegionLogin? = null
        var reason = "first-login"
        var lastResponse: LoginResponse? = null

        // START_BLOCK_REGION_FALLBACK
        try {
            for (region in order) {
                if (logins >= MAX_LOGIN_ATTEMPTS) break
                if (region != null && region != selected) {
                    regions.selectRegion(region)
                    selected = region
                }
                logins++
                val response = api.login(
                    seq = "1",
                    account = email,
                    password = digest,
                    devName = android.os.Build.BRAND ?: "",
                    devType = android.os.Build.MODEL ?: "",
                    devOsVersion = "Android " + (android.os.Build.VERSION.RELEASE ?: ""),
                )
                lastResponse = response
                AuthLog.d(
                    "[Auth][login][BLOCK_REGION_FALLBACK] region=${region?.name ?: "custom"} " +
                        "attempt=$logins code=${safeCode(response.code)}",
                )
                val tokens = extractTokens(response.data)
                if (tokens.accessToken.isNullOrBlank()) {
                    // 20253 = no such account here: try the next gateway. Any other rejection
                    // (wrong password, malformed request) stops at once: no hop, no further request.
                    if (response.code == ACCOUNT_NOT_FOUND_CODE) continue
                    break
                }
                val accepted = RegionLogin(region, tokens.toSession(method))
                if (firstGood == null) firstGood = accepted
                lastGood = accepted
                if (!discover) {
                    kept = accepted
                    reason = "no-discovery"
                    break
                }

                // START_BLOCK_REGION_DISCOVERY
                if (lists >= MAX_DISCOVERY_CALLS) break
                lists++
                // In-memory only: the shared client attaches this region's own token to the check.
                sessionRef = accepted.session
                discoveryInFlight = true
                val check = try {
                    devices.check(accepted.session)
                } finally {
                    discoveryInFlight = false
                }
                AuthLog.d(
                    "[Auth][login][BLOCK_REGION_DISCOVERY] region=${region?.name ?: "custom"} " +
                        "code=${safeCode(check.code)} count=${check.count}",
                )
                if (check.hasDevices) {
                    kept = accepted
                    reason = "devices"
                    break
                }
                // END_BLOCK_REGION_DISCOVERY
            }
        } catch (c: CancellationException) {
            sessionRef = previousSession
            if (start != null) regions.restoreBaseUrl(originalBaseUrl)
            throw c
        } catch (t: Throwable) {
            // Network error: stop the loop (no retry). Without any accepted login the error propagates
            // and the original server is put back; otherwise the last good session is kept.
            val good = lastGood
            if (good == null) {
                sessionRef = previousSession
                if (start != null) regions.restoreBaseUrl(originalBaseUrl)
                throw t
            }
            kept = good
            reason = "network-error"
            AuthLog.d("[Auth][login][BLOCK_REGION_SELECTED] loop stopped error=${t::class.simpleName ?: "Throwable"}")
        }
        // END_BLOCK_REGION_FALLBACK

        // No region had devices: keep the FIRST region where login succeeded.
        val winner = kept ?: firstGood
        if (winner == null) {
            sessionRef = previousSession
            // Without an accepted login the loop continues only on 20253, so a last code of 20253 means
            // the account was found nowhere: put the original server back. Any other last code keeps the
            // region of that attempt (the account lives there; the next attempt starts there).
            if (start != null && lastResponse?.code == ACCOUNT_NOT_FOUND_CODE) regions.restoreBaseUrl(originalBaseUrl)
            AuthLog.d(
                "[Auth][login][BLOCK_VALIDATE_CREDENTIALS] rejected code=${safeCode(lastResponse?.code)} " +
                    "message=${lastResponse?.message ?: "-"}",
            )
            throw AuthError.InvalidCredentials
        }
        // END_BLOCK_VALIDATE_CREDENTIALS

        // START_BLOCK_REGION_SELECTED
        val winnerRegion = winner.region
        if (winnerRegion != null && winnerRegion != selected) regions.selectRegion(winnerRegion)
        AuthLog.d(
            "[Auth][login][BLOCK_REGION_SELECTED] region=${winnerRegion?.name ?: "custom"} " +
                "reason=$reason logins=$logins lists=$lists",
        )
        // END_BLOCK_REGION_SELECTED

        val session = winner.session
        // START_BLOCK_STORE_TOKEN
        store.putSession(session)
        sessionRef = session
        AuthLog.d("[Auth][login][BLOCK_STORE_TOKEN] session stored hasUser=${session.userId.isNotBlank()} expiresInSec=${(session.expiresAt - clock.nowMillis()) / 1000}")
        // END_BLOCK_STORE_TOKEN
        session
    }

    private fun Tokens.toSession(method: AuthMethod): AuthSession = AuthSession(
        accessToken = accessToken ?: "",
        refreshToken = refreshToken ?: "",
        expiresAt = clock.nowMillis() + (expiresIn ?: DEFAULT_EXPIRES_SECONDS) * 1000L,
        userId = userId ?: "",
        authMethod = method,
    )

    /** Response codes are short digit strings; anything else is logged as "?" (logs stay value-free). */
    private fun safeCode(code: String?): String = when {
        code.isNullOrEmpty() -> "-"
        SAFE_CODE.matches(code) -> code
        else -> "?"
    }

    // START_BLOCK_REFRESH_ONCE_PER_EXPIRY
    /**
     * expiresAt of the session whose refresh already failed; -1 = none. A new login or a
     * successful refresh produces a new expiresAt, which re-arms exactly one more attempt.
     * Bounds POST /v4/users/auth_token to at most one call per session expiry.
     */
    @Volatile private var refreshFailedForExpiresAt: Long = -1

    private fun refreshAlreadyFailed(s: AuthSession?): Boolean =
        s != null && s.expiresAt == refreshFailedForExpiresAt

    /** doRefresh() guarded by the per-expiry memo; records the failure for the current expiresAt. */
    private suspend fun refreshOncePerExpiry(): AuthSession? {
        if (discoveryInFlight) {
            // The login discovery check must not trigger POST /v4/users/auth_token (bounded login).
            AuthLog.d("[Auth][refresh][BLOCK_REFRESH_ONCE_PER_EXPIRY] skipped reason=login-discovery")
            return null
        }
        val current = sessionRef ?: store.getSession() ?: return null
        if (refreshAlreadyFailed(current)) {
            AuthLog.d("[Auth][refresh][BLOCK_REFRESH_ONCE_PER_EXPIRY] skipped reason=already-failed-for-this-expiry")
            return null
        }
        val refreshed = doRefresh()
        if (refreshed == null) refreshFailedForExpiresAt = current.expiresAt
        return refreshed
    }
    // END_BLOCK_REFRESH_ONCE_PER_EXPIRY

    /** Refresh the access token using the stored refresh token. Returns the new token, or null on failure. */
    override suspend fun refresh(): String? = refreshOncePerExpiry()?.accessToken

    /** Refresh and return the full updated session, or null on failure (same once-per-expiry bound). */
    suspend fun refreshSession(): AuthSession? = refreshOncePerExpiry()

    private suspend fun doRefresh(): AuthSession? {
        val current = sessionRef ?: store.getSession() ?: return null
        // START_BLOCK_REFRESH_TOKEN
        AuthLog.d("[Auth][refresh][BLOCK_REFRESH_TOKEN] refreshing hasUser=true")
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
        AuthLog.d("[Auth][refresh][BLOCK_REFRESH_TOKEN] refreshed hasUser=true")
        // END_BLOCK_REFRESH_TOKEN
        return refreshed
    }

    override suspend fun getAccessToken(): String? {
        val s = sessionRef ?: return null
        if (clock.nowMillis() >= s.expiresAt) {
            // Token expired: at most ONE refresh per expiry. After a failure the stale token is
            // returned without touching the network (the device API ignores Bearer; a real 401
            // is handled once by the authenticator, which hits the same memo).
            if (refreshAlreadyFailed(s)) return s.accessToken
            return refreshOncePerExpiry()?.accessToken ?: s.accessToken
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

    private companion object {
        val SAFE_CODE = Regex("^-?[0-9]{1,6}$")
    }

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