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
        fun enc(v: String): String = java.net.URLEncoder.encode(v, "UTF-8")
        fun mac(key: String, msg: String): String {
            val m = Mac.getInstance("HmacSHA1")
            m.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA1"))
            return java.util.Base64.getEncoder().encodeToString(m.doFinal(msg.toByteArray(Charsets.UTF_8)))
        }
        val host = base()
        val canonical = "seq=1&userid=$uid"
        val hmac = enc(mac("$token&$secret", canonical))
        // paths that may carry the device list
        data class Variant(val label: String, val path: String, val extra: String, val bearer: Boolean)
        val variants = listOf(
            Variant("list-bare", "/v5/devices/list", "", false),
            Variant("list+bearer", "/v5/devices/list", "", true),
            Variant("list+token", "/v5/devices/list", "&token=$token", false),
            Variant("vas-bare", "/vas/v8/all/cloud/deviceList", "", false),
            Variant("vas+bearer", "/vas/v8/all/cloud/deviceList", "", true),
            Variant("vas+token", "/vas/v8/all/cloud/deviceList", "&token=$token", false),
            Variant("vas-cloud-bare", "/vas/v8/cloud/deviceList", "", false),
        )
        for (v in variants) {
            try {
                val rb = Request.Builder().url("$host${v.path}?$canonical&hmac=$hmac${v.extra}")
                if (v.bearer) rb.header("Authorization", "Bearer $token")
                rb.header("Accept", "application/json")
                client.newCall(rb.build()).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    val code = Regex("\"code\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: "http${resp.code}"
                    val masked = body
                        .replace(Regex("\"([^\"]*(?:token|secret)[^\"]*)\"\\s*:\\s*\"[^\"]*\"", RegexOption.IGNORE_CASE), "\"$1\":\"██\"")
                        .take(220)
                    out += "${v.label} => $code len=${body.length} body=$masked"
                    Timber.d("[Probe][run][BLOCK_PROBE] ${v.label} => $code len=${body.length}")
                }
            } catch (t: Throwable) {
                out += "${v.label} => EXC ${t.message?.take(40)}"
            }
        }
        out
    }
}