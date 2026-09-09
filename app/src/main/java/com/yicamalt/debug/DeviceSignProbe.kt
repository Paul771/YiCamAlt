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
        val p = "seq=1&userid=$uid"
        val pTok = "seq=1&userid=$uid&token=$token"
        val ts = "$token&$secret"
        // (label, params-no-hmac, hmacValue or null, putTokenInParams)
        data class Combo(val label: String, val params: String, val hmac: String?)
        val combos = mutableListOf<Combo>()
        combos += Combo("ctrl-token-param", pTok, null)
        combos += Combo("A key=p msg=ts", p, mac(p, ts))
        combos += Combo("B key=ts msg=p", p, mac(ts, p))
        combos += Combo("C key=secret msg=p", p, mac(secret, p))
        combos += Combo("D key=p msg=secret", p, mac(p, secret))
        combos += Combo("E key=secret msg=ts", p, mac(secret, ts))
        combos += Combo("F key=ts msg=secret", p, mac(ts, secret))
        combos += Combo("A2 key=pTok msg=ts", pTok, mac(pTok, ts))
        combos += Combo("B2 key=ts msg=pTok", pTok, mac(ts, pTok))
        combos += Combo("C2 key=secret msg=pTok", pTok, mac(secret, pTok))
        combos += Combo("D2 key=pTok msg=secret", pTok, mac(pTok, secret))
        combos += Combo("E2 key=secret msg=ts", pTok, mac(secret, ts))
        combos += Combo("F2 key=ts msg=secret", pTok, mac(ts, secret))
        val host = base()
        for (c in combos) {
            try {
                val suffix = if (c.hmac == null) "?${c.params}" else "?${c.params}&hmac=${enc(c.hmac)}"
                val rb = Request.Builder().url("$host/v5/devices/list$suffix")
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
}