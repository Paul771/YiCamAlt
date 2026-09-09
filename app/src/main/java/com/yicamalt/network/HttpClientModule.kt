// FILE: HttpClientModule.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Shared HTTP client (OkHttp + Retrofit2) with TLS config, auth interceptor, redacted logging, and 401 refresh+retry.
//   SCOPE: build OkHttpClient + Retrofit; inject Bearer token; retry exactly once after a single token refresh on 401.
//   DEPENDS: M-CONFIG
//   LINKS: M-AUTH, M-CONFIG
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

// START_MODULE_MAP
//   HttpClientModule - singleton OkHttp + Retrofit provider.
//   HttpError - HTTP error hierarchy (TlsFailed, Unauthorized).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.3.2 - Log sink now masks password= and account= query values with ██:
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

    /** Optional capturing log sink for tests; production leaves it null (Timber). */
    var logSink: ((String) -> Unit)? = null

    // START_BLOCK_INIT_HTTP_CLIENT
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = false }
    private val currentToken = AtomicReference<String?>()

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
        // Login query params (password digest, account email) must never reach logs;
        // ██ (U+2588) matches the RedactionScanner allow-list convention.
        val safe = msg
            .replace(Regex("(password=)[^&\\s]+"), "$1██")
            .replace(Regex("(account=)[^&\\s]+"), "$1██")
        val sink = logSink
        if (sink != null) sink.invoke(safe) else timber.log.Timber.d(safe)
        // END_BLOCK_REDACTED_LOG
    }.apply {
        level = HttpLoggingInterceptor.Level.HEADERS
        redactHeader("Authorization")
        redactHeader("Set-Cookie")
        redactHeader("Cookie")
    }

    private val refreshGuard = AtomicBoolean(false)

    private val authenticator = Authenticator { _, response ->
        // START_BLOCK_REFRESH_RETRY
        if (response.code != 401) return@Authenticator null
        if (!refreshGuard.compareAndSet(false, true)) return@Authenticator null // only one refresh per call
        val refreshed = authProvider.get().refreshBlocking()
        refreshGuard.set(false)
        if (refreshed == null) return@Authenticator null
        response.request.newBuilder()
            .header("Authorization", "Bearer $refreshed")
            .build()
        // END_BLOCK_REFRESH_RETRY
    }

    /**
     * Captures login/auth response bodies for reverse-engineering evidence.
     * Token-shaped VALUES are masked (██); field NAMES stay readable so the envelope
     * shape can be diagnosed from device logs. Timber is a no-op tree in release builds.
     */
    private val responseBodyLogger = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        val url = response.request.url.encodedPath
        if (url.contains("/login") || url.contains("/auth_token")) {
            val bodyString = response.peekBody(4096L).string()
            val masked = bodyString
                .replace(Regex("(\"[^\"]*(?:token|password|secret|authorization)[^\"]*\"\\s*:\\s*\")([^\"]*)(\")", RegexOption.IGNORE_CASE), "$1██$3")
            TimberLog.d("[Network][body][BLOCK_REDACTED_LOG] path=$url status=${response.code} body=${masked.take(600)}")
        }
        response
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .addInterceptor(authInterceptor)
        .addInterceptor(redactedLogger)
        .addInterceptor(responseBodyLogger)
        .authenticator(authenticator)
        .build()
    // END_BLOCK_INIT_HTTP_CLIENT

    private var retrofit: Retrofit = buildRetrofit()

    private fun buildRetrofit(): Retrofit = Retrofit.Builder()
        .baseUrl(config.getApiBaseUrl())
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    init {
        TimberLog.d("[Network][init][BLOCK_INIT_HTTP_CLIENT] http client ready baseUrl=${config.getApiBaseUrl()}")
    }

    /** Build Retrofit instance with interceptors. */
    fun createRetrofit(): Retrofit = retrofit

    /** Rebuild Retrofit from the current config base URL (e.g. after the user changes the API server). */
    fun rebuild() {
        retrofit = buildRetrofit()
        TimberLog.d("[Network][rebuild][BLOCK_INIT_HTTP_CLIENT] http client rebuilt baseUrl=${config.getApiBaseUrl()}")
    }

    /** Return raw OkHttp client. */
    fun getHttpClient(): OkHttpClient = client

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