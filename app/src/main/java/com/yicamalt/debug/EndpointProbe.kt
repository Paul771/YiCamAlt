// FILE: EndpointProbe.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Debug-only one-shot probe matrix runner (research Conflict A).
//   SCOPE: one press = 12 signed list GETs (4 paths x US/EU/SG) + at most 1 tnp device_info GET, sequential,
//     no retries, no auth calls; returns and logs only value-free ProbeMatrix lines.
//   DEPENDS: M-HTTP, M-AUTH
//   LINKS: M-DEBUG-PROBE, V-M-DEBUG-PROBE
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.debug

import com.yicamalt.BuildConfig
import com.yicamalt.auth.AuthRepository
import com.yicamalt.network.HttpClientModule
import com.yicamalt.network.YiRequestSigner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request
import timber.log.Timber
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

// START_MODULE_MAP
//   EndpointProbe - runOnce(): debug-only matrix run on http.probeClient(); value-free lines out.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - Replaces the deleted debug sign probe: shared redacting client, no token in URLs, no retries,
//     ≤13 requests per press. probeClient() has no auth interceptor/authenticator, so the hmac computed once
//     at the start cannot be invalidated by a token refresh mid-run. Expired session = zero requests.
//   HAZARD: never log s.userId, the cloud uid, the hmac or a body; only ProbeMatrix.format() lines.
// END_CHANGE_SUMMARY

@Singleton
class EndpointProbe @Inject constructor(
    private val http: HttpClientModule,
    private val auth: AuthRepository,
) {
    private val running = AtomicBoolean(false)

    // START_CONTRACT: runOnce
    //   PURPOSE: Run the probe matrix once and return the formatted (value-free) lines.
    //   INPUTS: none (session from AuthRepository)
    //   OUTPUTS: { List<String> - one line per request + summary; or a single reason line }
    //   SIDE_EFFECTS: ≤13 GET requests on the Yi gateways; Timber debug lines
    //   LINKS: M-DEBUG-PROBE, V-M-DEBUG-PROBE
    // END_CONTRACT: runOnce
    suspend fun runOnce(): List<String> = withContext(Dispatchers.IO) {
        // START_BLOCK_PROBE_MATRIX
        if (!BuildConfig.DEBUG) return@withContext listOf("disabled-in-release")
        if (!running.compareAndSet(false, true)) return@withContext listOf("already-running")
        try {
            val s = auth.getSession()
            if (s == null) {
                Timber.d("[Probe][run][BLOCK_PROBE_MATRIX] skipped reason=no-session")
                return@withContext listOf("no-session: log in first")
            }
            if (System.currentTimeMillis() >= s.expiresAt) {
                Timber.d("[Probe][run][BLOCK_PROBE_MATRIX] skipped reason=session-expired")
                return@withContext listOf("session-expired: log out and log in again, then press once")
            }
            fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")
            val hmac = enc(YiRequestSigner.sign(s.accessToken, s.refreshToken, s.userId))
            val uidQ = enc(s.userId)
            val client = http.probeClient()
            val lines = mutableListOf<String>()
            val results = mutableListOf<ProbeResult>()
            var cloudUid: String? = null
            var uidTarget: ProbeTarget? = null

            fun record(r: ProbeResult) {
                results += r
                val line = ProbeMatrix.format(r)
                Timber.d("[Probe][run][BLOCK_PROBE_MATRIX] $line")
                lines += line
            }

            ProbeMatrix.targets().forEachIndexed { i, t ->
                if (i > 0) delay(300)
                val r = try {
                    val req = Request.Builder().url("${t.baseUrl}${t.path}?seq=1&userid=$uidQ&hmac=$hmac").get().build()
                    client.newCall(req).execute().use { resp ->
                        val body = resp.body?.string()
                        val analyzed = ProbeMatrix.analyze(t, resp.code, body)
                        if (cloudUid == null && (analyzed.dataCount ?: 0) > 0 &&
                            ProbeMatrix.isDeviceSource(t.path) && analyzed.looksLikeDevices
                        ) {
                            ProbeMatrix.firstDeviceUid(body)?.let { cloudUid = it; uidTarget = t }
                        }
                        analyzed
                    }
                } catch (e: Exception) {
                    ProbeMatrix.failed(t, e)
                }
                record(r)
            }

            val uid = cloudUid
            val src = uidTarget
            if (uid != null && src != null) {
                delay(300)
                val tnp = ProbeTarget(src.region, src.baseUrl, ProbeMatrix.TNP_INFO_PATH)
                val tnpHmac = enc(YiRequestSigner.sign(s.accessToken, s.refreshToken, s.userId, extra = listOf("uid" to uid)))
                val r = try {
                    val req = Request.Builder()
                        .url("${tnp.baseUrl}${tnp.path}?seq=1&userid=$uidQ&uid=${enc(uid)}&hmac=$tnpHmac")
                        .get().build()
                    client.newCall(req).execute().use { resp -> ProbeMatrix.analyze(tnp, resp.code, resp.body?.string()) }
                } catch (e: Exception) {
                    ProbeMatrix.failed(tnp, e)
                }
                record(r)
            } else {
                Timber.d("[Probe][run][BLOCK_PROBE_MATRIX] tnp=skipped reason=no-uid-field")
                lines += "tnp=skipped reason=no-uid-field"
            }
            // END_BLOCK_PROBE_MATRIX

            // START_BLOCK_PROBE_SUMMARY
            val withData = results.count { (it.dataCount ?: 0) > 0 }
            val authCodes = results.count { it.code in AUTH_CODES }
            val summary = "requests=${results.size} withData=$withData authCodes=$authCodes"
            Timber.d("[Probe][run][BLOCK_PROBE_SUMMARY] $summary")
            lines += summary
            // END_BLOCK_PROBE_SUMMARY
            lines
        } finally {
            running.set(false)
        }
    }

    private companion object {
        /** 20201 no signature, 20202 bad signature, 20203 signature-related rejection. */
        val AUTH_CODES = setOf("20201", "20202", "20203")
    }
}
