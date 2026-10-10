// FILE: HttpClientModule.kt
// VERSION: 0.5.0
// START_MODULE_CONTRACT
//   PURPOSE: Shared HTTP client (OkHttp + Retrofit2) with TLS config, auth interceptor, redacted logging, and 401 refresh+retry.
//   SCOPE: build OkHttpClient + Retrofit (once, on a placeholder host); resolve the real base URL from
//     ConfigModule per request; add official Yi app headers; inject Bearer token; retry exactly once
//     after a single token refresh on 401; refresh-free probeClient() for the debug probe.
//   DEPENDS: M-CONFIG, LogRedactor
//   LINKS: M-AUTH, M-CONFIG, SEC-01
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.network

import com.yicamalt.config.ConfigModule
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Authenticator
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

// START_MODULE_MAP
//   HttpClientModule - singleton OkHttp + Retrofit provider (placeholder base, per-request rewrite).
//   HttpClientModule.PLACEHOLDER_HOST / PLACEHOLDER_BASE_URL - host every Retrofit API is built on.
//   HttpClientModule.probeClient - lazy client without auth interceptor/authenticator (debug probe).
//   HttpError - HTTP error hierarchy (TlsFailed, Unauthorized).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.5.0 - Retrofit is built once on placeholder https://yi-api.invalid/; baseUrlInterceptor
//     rewrites scheme/host/port(+base path) from ConfigModule on EVERY request, so all four singleton APIs
//     follow base-URL/region changes without restart (CONCERNS: stale Retrofit). appHeadersInterceptor adds
//     official Yi app headers except on /v4/users/… paths. rebuild() kept as a logging no-op for callers.
//     Authenticator releases refreshGuard in finally (CONCERNS: guard latched forever if refresh threw).
//     probeClient(): app headers + redacted logging only; Authenticator.NONE, no redirects, no refresh.
//     HAZARD: an absolute Retrofit path (/v5/alert/events) also gets the base path prefix; real bases are host-only.
//   PREV_CHANGE: v0.4.0 - SEC-01: query masking (hmac/userid/uid/token/...) and JSON-walk body masking via
//     LogRedactor; closes CONCERNS HIGH leaks (hmac logged verbatim; encoded-`data` envelope bypassed the
//     body regex). Logger level HEADERS -> BASIC: request/status lines only, no header values at all
//     (redactHeader kept as defence in depth). Body logger also covers /deviceshare and /tnp.
//   PREV_CHANGE: v0.3.2 - Log sink now masks password= and account= query values with ██:
//     the login URL carried the password in plaintext and leaked it into auth_log.txt
//     (user's real password was exposed). Matches RedactionScanner allow-list convention.
// END_CHANGE_SUMMARY

sealed class HttpError(message: String) : Error(message) {
    object TlsFailed : HttpError("HTTP_TLS_FAILED: TLS handshake requirements not met")
    object Unauthorized : HttpError("HTTP_UNAUTHORIZED: refresh failed or token rejected")
}

// START_MODULE_MAP
//   NetworkModule - Hilt module: provides Retrofit (and OkHttpClient) from HttpClientModule.
// END_MODULE_MAP

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideRetrofit(client: HttpClientModule): Retrofit = client.createRetrofit()

    @Provides
    @Singleton
    fun provideOkHttpClient(client: HttpClientModule): OkHttpClient = client.getHttpClient()
}

@Singleton
class HttpClientModule @Inject constructor(
    private val config: ConfigModule,
    private val authProvider: Provider<AuthProvider>,
) {

    companion object {
        const val PLACEHOLDER_HOST = "yi-api.invalid"
        const val PLACEHOLDER_BASE_URL = "https://yi-api.invalid/"
    }

    /** Optional capturing log sink for tests; production leaves it null (Timber). */
    var logSink: ((String) -> Unit)? = null

    // START_BLOCK_INIT_HTTP_CLIENT
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = false }
    private val currentToken = AtomicReference<String?>()

    /** Official Yi app identity headers on device/deviceshare/tnp calls; login (/v4/users/…) untouched. */
    private val appHeadersInterceptor = Interceptor { chain ->
        val req = chain.request()
        if (!YiAppHeaders.appliesTo(req.url.host, req.url.encodedPath)) return@Interceptor chain.proceed(req)
        val b = req.newBuilder()
        YiAppHeaders.headers().forEach { (name, value) -> b.header(name, value) }
        chain.proceed(b.build())
    }

    /** Rewrites the placeholder host to the CURRENT ConfigModule base URL on every request. */
    private val baseUrlInterceptor = Interceptor { chain ->
        // START_BLOCK_RESOLVE_BASE_URL
        val req = chain.request()
        if (req.url.host != PLACEHOLDER_HOST) return@Interceptor chain.proceed(req)
        val base = config.getApiBaseUrl().toHttpUrlOrNull()
            ?: throw IOException("CONFIG_BAD_BASE_URL: base url is not a valid http(s) url")
        val url = req.url.newBuilder()
            .scheme(base.scheme)
            .host(base.host)
            .port(base.port)
            .encodedPath(base.encodedPath.trimEnd('/') + req.url.encodedPath)
            .build()
        chain.proceed(req.newBuilder().url(url).build())
        // END_BLOCK_RESOLVE_BASE_URL
    }

    private val authInterceptor = Interceptor { chain ->
        val request = chain.request()
        // Refresh calls must bypass the token interceptor: getAccessTokenBlocking()
        // can itself trigger a refresh, which would recurse into the same client
        // and deadlock the OkHttp dispatcher (observed as an infinite login spinner).
        val isRefreshCall = request.url.encodedPath.contains("auth_token")
        val token = if (isRefreshCall) null else currentToken.get() ?: authProvider.get().getAccessTokenBlocking()
        val req = if (token != null) {
            request.newBuilder().header("Authorization", "Bearer $token").build()
        } else request
        chain.proceed(req)
    }

    private val redactedLogger = HttpLoggingInterceptor { msg ->
        // START_BLOCK_REDACTED_LOG
        // Authorization/Cookie headers are redacted via redactHeader() above.
        // Secret query params (password digest, account, hmac, userid, uid, token, ...) must never
        // reach logs; ██ (U+2588) matches the RedactionScanner allow-list convention.
        val safe = LogRedactor.maskQuery(msg)
        val sink = logSink
        if (sink != null) sink.invoke(safe) else timber.log.Timber.d(safe)
        // END_BLOCK_REDACTED_LOG
    }.apply {
        // BASIC: request line + response status line only; header values are never logged.
        level = HttpLoggingInterceptor.Level.BASIC
        redactHeader("Authorization")
        redactHeader("Set-Cookie")
        redactHeader("Cookie")
    }

    private val refreshGuard = AtomicBoolean(false)

    private val authenticator = Authenticator { _, response ->
        // START_BLOCK_REFRESH_RETRY
        if (response.code != 401) return@Authenticator null
        if (!refreshGuard.compareAndSet(false, true)) return@Authenticator null // only one refresh per call
        val refreshed = try {
            authProvider.get().refreshBlocking()
        } finally {
            refreshGuard.set(false)
        }
        if (refreshed == null) return@Authenticator null
        response.request.newBuilder()
            .header("Authorization", "Bearer $refreshed")
            .build()
        // END_BLOCK_REFRESH_RETRY
    }

    /**
     * Captures login/auth response bodies for reverse-engineering evidence.
     * Shape-only: every string/number VALUE is masked (██) except `code`, booleans and null;
     * field NAMES stay readable so the envelope shape can be diagnosed from device logs
     * (see LogRedactor.maskJsonBody). Timber is a no-op tree in release builds.
     */
    private val responseBodyLogger = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        val url = response.request.url.encodedPath
        if (url.contains("/login") || url.contains("/auth_token") ||
            url.contains("/devices") || url.contains("/device") ||
            url.contains("/deviceshare") || url.contains("/tnp")
        ) {
            val bodyString = response.peekBody(4096L).string()
            val masked = LogRedactor.maskJsonBody(bodyString)
            val safePath = LogRedactor.maskQuery(url)
            TimberLog.d("[Network][body][BLOCK_REDACTED_LOG] path=$safePath status=${response.code} body=${masked.take(600)}")
        }
        response
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .addInterceptor(appHeadersInterceptor)
        .addInterceptor(baseUrlInterceptor)
        .addInterceptor(authInterceptor)
        .addInterceptor(redactedLogger)
        .addInterceptor(responseBodyLogger)
        .authenticator(authenticator)
        .build()
    // END_BLOCK_INIT_HTTP_CLIENT

    /**
     * Refresh-free client for the debug endpoint probe: app headers + redacted logging only.
     * No auth interceptor and Authenticator.NONE, so nothing can refresh the token mid-run
     * (a refresh would invalidate the hmac precomputed from token/token_secret).
     */
    private val probeHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(appHeadersInterceptor)
            .addInterceptor(redactedLogger)
            .addInterceptor(responseBodyLogger)
            .authenticator(Authenticator.NONE)
            .followRedirects(false)
            .build()
    }

    private val retrofit: Retrofit = buildRetrofit()

    private fun buildRetrofit(): Retrofit = Retrofit.Builder()
        .baseUrl(PLACEHOLDER_BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    init {
        TimberLog.d("[Network][init][BLOCK_INIT_HTTP_CLIENT] http client ready baseUrl=${LogRedactor.maskQuery(config.getApiBaseUrl())}")
    }

    /** Retrofit built once on PLACEHOLDER_BASE_URL; the real base URL is resolved per request. */
    fun createRetrofit(): Retrofit = retrofit

    /** Kept for callers (SettingsViewModel.saveBaseUrl): a logging no-op, the base URL is resolved per request. */
    fun rebuild() {
        TimberLog.d("[Network][rebuild][BLOCK_INIT_HTTP_CLIENT] base url resolved per request")
    }

    /** Return raw OkHttp client. */
    fun getHttpClient(): OkHttpClient = client

    /** Refresh-free client (no Authorization, Authenticator.NONE, no redirects) for the debug probe. */
    fun probeClient(): OkHttpClient = probeHttpClient

    /** Update auth header token via interceptor (thread-safe). */
    fun setAuthToken(token: String?) { currentToken.set(token) }

    /** Bridges suspending AuthProvider.getAccessToken() for the synchronous interceptor path. */
    private fun AuthProvider.getAccessTokenBlocking(): String? =
        kotlinx.coroutines.runBlocking { getAccessToken() }

    /** Bridges suspending AuthProvider.refresh() for the synchronous authenticator path. */
    private fun AuthProvider.refreshBlocking(): String? =
        kotlinx.coroutines.runBlocking { refresh() }
}

internal object TimberLog {
    fun d(message: String) = timber.log.Timber.d(message)
}