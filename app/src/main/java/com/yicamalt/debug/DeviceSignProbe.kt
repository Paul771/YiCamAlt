// FILE: DeviceSignProbe.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Debug-only, on-device oracle for reverse-engineering the signed device API.
//   SCOPE: tries candidate request shapes for GET /v5/devices/list using the live session
//     token/token_secret, logs ONLY response codes (never tokens/hmac values).
//   DEPENDS: M-AUTH (session), M-CONFIG (base url)
//   LINKS: M-CAMERA-LIST
//   ROLE: RUNTIME
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.debug

import com.yicamalt.auth.AuthRepository
import com.yicamalt.config.ConfigModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

// START_MODULE_MAP
//   DeviceSignProbe - tries candidate signed /v5/devices/list shapes; logs codes only.
// END_MODULE_MAP

@Singleton
class DeviceSignProbe @Inject constructor(
    private val config: ConfigModule,
    private val auth: AuthRepository,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun base(): String = config.getApiBaseUrl().trimEnd('/')

    suspend fun runAll(): List<String> = withContext(Dispatchers.IO) {
        val session = auth.getSession() ?: return@withContext listOf("no-session")
        val token = session.accessToken
        val secret = session.refreshToken
        val uid = session.userId
        Timber.d("[Probe][run][BLOCK_PROBE] uid=$uid len=${token.length}/${secret.length}")
        val out = mutableListOf<String>()
        // canonical params in insertion order
        fun canonical() = "seq=1&userid=$uid"
        fun hmacOrderA(): String { // key=canonical, msg=token&secret
            return mac(canonical(), "$token&$secret")
        }
        fun hmacOrderB(): String { // key=token&secret, msg=canonical
            return mac("$token&$secret", canonical())
        }
        val host = base()
        // candidate request builders -> (needsBearerHeader, suffix)
        data class Combo(val label: String, val bearer: Boolean, val suffix: String)
        val combos = listOf(
            Combo("plain-noauth", false, "?seq=1&userid=$uid"),
            Combo("bearer-only", true, "?seq=1&userid=$uid"),
            Combo("token-query", true, "?seq=1&userid=$uid&token=$token"),
            Combo("hmacA-query", false, "?seq=1&userid=$uid&hmac=${hmacOrderA()}"),
            Combo("hmacB-query", false, "?seq=1&userid=$uid&hmac=${hmacOrderB()}"),
            Combo("hmacA-bearer", true, "?seq=1&userid=$uid&hmac=${hmacOrderA()}"),
            Combo("hmacB-bearer", true, "?seq=1&userid=$uid&hmac=${hmacOrderB()}"),
        )
        for (c in combos) {
            try {
                val rb = Request.Builder().url("$host/v5/devices/list${c.suffix}")
                if (c.bearer) rb.header("Authorization", "Bearer $token")
                rb.header("Accept", "application/json")
                client.newCall(rb.build()).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    val code = Regex("\"code\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: "http${resp.code}"
                    out += "${c.label} => $code"
                    Timber.d("[Probe][run][BLOCK_PROBE] ${c.label} => $code")
                }
            } catch (t: Throwable) {
                out += "${c.label} => EXC ${t.message?.take(40)}"
            }
        }
        out
    }

    private fun mac(key: String, msg: String): String {
        val m = Mac.getInstance("HmacSHA1")
        m.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        return java.util.Base64.getEncoder().encodeToString(m.doFinal(msg.toByteArray(Charsets.UTF_8)))
    }
}