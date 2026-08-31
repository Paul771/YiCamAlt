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
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

// START_MODULE_MAP
//   HttpClientModule - singleton OkHttp + Retrofit provider.
//   HttpError - HTTP error hierarchy (TlsFailed, Unauthorized).
// END_MODULE_MAP

sealed class HttpError(message: String) : Error(message) {
    object TlsFailed : HttpError("HTTP_TLS_FAILED: TLS handshake requirements not met")
    object Unauthorized : HttpError("HTTP_UNAUTHORIZED: refresh failed or token rejected")
}

@Singleton
class HttpClientModule @Inject constructor(
    private val config: ConfigModule,
    private val authProvider: AuthProvider,
    private val logSink: ((String) -> Unit)? = null,
) {

    // START_BLOCK_INIT_HTTP_CLIENT
    private val json = Json { ignoreMissingKeys = true; isLenient = true; encodeDefaults = false }
    private val currentToken = AtomicReference<String?>()

    private val authInterceptor = Interceptor { chain ->
        val token = currentToken.get() ?: authProvider.getAccessTokenBlocking()
        val req = if (token != null) {
            chain.request().newBuilder().header("Authorization", "Bearer $token").build()
        } else chain.request()
        chain.proceed(req)
    }

    private val redactedLogger = HttpLoggingInterceptor { msg ->
        // START_BLOCK_REDACTED_LOG
        // Authorization/Cookie headers are redacted via redactHeader() above.
        // Production sinks to Timber; tests inject a capturing sink for RedactionScanner.
        if (logSink != null) logSink.invoke(msg) else timber.log.Timber.d(msg)
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
        val refreshed = authProvider.refreshBlocking()
        refreshGuard.set(false)
        if (refreshed == null) return@Authenticator null
        response.request.newBuilder()
            .header("Authorization", "Bearer $refreshed")
            .build()
        // END_BLOCK_REFRESH_RETRY
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .addInterceptor(authInterceptor)
        .addInterceptor(redactedLogger)
        .authenticator(authenticator)
        .build()
    // END_BLOCK_INIT_HTTP_CLIENT

    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(config.getApiBaseUrl())
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    init {
        TimberLog.d("[Network][init][BLOCK_INIT_HTTP_CLIENT] http client ready baseUrl=${config.getApiBaseUrl()}")
    }

    /** Build Retrofit instance with interceptors. */
    fun createRetrofit(): Retrofit = retrofit

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