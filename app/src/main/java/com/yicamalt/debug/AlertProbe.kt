// FILE: AlertProbe.kt
// VERSION: 0.2.0
// START_MODULE_CONTRACT
//   PURPOSE: Debug-only one-shot probe of the real alert feed: one signed GET v2/alert/list (30 days, limit 20),
//     then one preview GET and one clip GET (each plus one https retry of the same path when the URL is http),
//     decrypted in memory; returns value-free shape lines.
//   SCOPE: session checks, signed list request on http.probeClient(), media GETs on MediaHttp.plainClient(),
//     cleartext policy check per media host, AlertProbeShape formatting. Hard cap 5 requests per press
//     (1 Yi + <=4 media), no other retries, no auth calls, no credential refresh.
//   DEPENDS: M-HTTP, M-AUTH, M-CONFIG, M-CAMERA-LIST, M-STREAM-CORE (P2pCredentialsSource, cached only),
//     M-EVENT (AlertWire), M-EVENT-PLAYBACK (MediaCrypto, MediaHttp)
//   LINKS: M-DEBUG-PROBE, V-M-DEBUG-PROBE, EVT-01, EVT-03, EVT-06
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.debug

import android.security.NetworkSecurityPolicy
import com.yicamalt.BuildConfig
import com.yicamalt.auth.AuthRepository
import com.yicamalt.camera.CameraListPort
import com.yicamalt.config.ConfigModule
import com.yicamalt.event.AlertWire
import com.yicamalt.event.MediaCrypto
import com.yicamalt.event.MediaHttp
import com.yicamalt.network.HttpClientModule
import com.yicamalt.network.YiRequestSigner
import com.yicamalt.stream.P2pCredentialsSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

// START_MODULE_MAP
//   AlertProbe - runOnce(): debug-only, <=5 requests (list + preview/clip, each + optional https retry);
//     value-free lines out (list, urlshape x3, uid, fields, media attempts, requests).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.2.0 - 05-00 follow-up after the first device run (URL fields plural, media GETs failed with
//     UnknownServiceException = cleartext blocked, uid matched no deviceId): emits URL field form lines, uid vs
//     dev / p2p (cached P2P credentials only, never refreshed) / user ids, field kinds; each media GET logs
//     attempt / scheme / per-host cleartext permission, and an http URL gets one https retry on the same path.
//     Cap raised 3 -> 5. The app network policy is NOT changed (cleartext stays off); the probe only reports.
//   PREVIOUS: v0.1.0 - GSD plan 05-00: one-shot alert feed probe for the Phase 5 open questions (expires,
//     retention, uid == deviceId, shared cameras, clip codec, HLS clips, tar previews).
//   HAZARD: probeClient's responseBodyLogger does not match `/alert`, so the alert JSON body is never logged;
//     keep it that way. Media GETs go through MediaHttp.plainClient() (no auth header, no logger). Never log or
//     return URLs, hosts, keys, ids, userid, hmac, the body or time values; exceptions are reported by simple
//     class name only (messages may carry the URL). Expired session = zero requests.
// END_CHANGE_SUMMARY

@Singleton
class AlertProbe @Inject constructor(
    private val http: HttpClientModule,
    private val auth: AuthRepository,
    private val config: ConfigModule,
    private val cameras: CameraListPort,
    private val credentials: P2pCredentialsSource,
) {
    private val running = AtomicBoolean(false)

    // START_CONTRACT: runOnce
    //   PURPOSE: Run the alert probe once and return the formatted (value-free) lines.
    //   INPUTS: none (session from AuthRepository, region from ConfigModule, known cameras from Room cache)
    //   OUTPUTS: { List<String> - list line, optional preview / clip lines, summary; or a single reason line }
    //   SIDE_EFFECTS: <=5 GET requests (1 Yi gateway, <=4 media CDN); Timber debug lines
    //   LINKS: M-DEBUG-PROBE, V-M-DEBUG-PROBE
    // END_CONTRACT: runOnce
    suspend fun runOnce(): List<String> = withContext(Dispatchers.IO) {
        if (!BuildConfig.DEBUG) return@withContext listOf("disabled-in-release")
        if (!running.compareAndSet(false, true)) return@withContext listOf("already-running")
        try {
            // START_BLOCK_ALERT_PROBE_SESSION
            val s = auth.getSession()
            if (s == null) {
                Timber.d("[EventProbe][run][BLOCK_ALERT_PROBE] skipped reason=no-session")
                return@withContext listOf("no-session: log in first")
            }
            if (System.currentTimeMillis() >= s.expiresAt) {
                Timber.d("[EventProbe][run][BLOCK_ALERT_PROBE] skipped reason=session-expired")
                return@withContext listOf("session-expired: log out and log in again, then press once")
            }
            // END_BLOCK_ALERT_PROBE_SESSION

            val lines = mutableListOf<String>()
            var requests = 0
            fun emit(line: String) {
                Timber.d("[EventProbe][run][BLOCK_ALERT_PROBE] %s", line)
                lines += line
            }
            fun get(client: OkHttpClient, url: String): Pair<Int, ByteArray?> {
                check(requests < MAX_REQUESTS) { "request cap" }
                requests++
                val req = Request.Builder().url(url).get().build()
                return client.newCall(req).execute().use { resp -> resp.code to resp.body?.bytes() }
            }

            // START_BLOCK_ALERT_PROBE_LIST
            val now = System.currentTimeMillis()
            val (from, to) = AlertWire.window(now, ZoneId.systemDefault())
            val hmac = YiRequestSigner.sign(
                s.accessToken, s.refreshToken, s.userId,
                extra = AlertWire.signedExtra(from, to, LIMIT),
                seq = AlertWire.SEQ,
            )
            val known = cameras.cachedCameras().map { c ->
                val ids = linkedMapOf(AlertProbeShape.ID_DEV to c.deviceId)
                credentials.credentialsFor(c.deviceId)?.p2pId?.takeIf { it.isNotBlank() }?.let { ids["p2p"] = it }
                CameraIds(c.shared, ids)
            }
            val account = mapOf("user" to s.userId)
            val shape = try {
                val base = config.getApiBaseUrl().toHttpUrl()
                val url = base.newBuilder()
                    .addPathSegments(AlertWire.LIST_PATH)
                    .addQueryParameter("seq", AlertWire.SEQ)
                    .addQueryParameter("userid", s.userId)
                    .addQueryParameter("type", "")
                    .addQueryParameter("sub_type", "")
                    .addQueryParameter("from", from.toString())
                    .addQueryParameter("to", to.toString())
                    .addQueryParameter("limit", LIMIT.toString())
                    .addQueryParameter("fromDB", "true")
                    .addQueryParameter("expires", AlertWire.EXPIRES)
                    .addQueryParameter("hmac", hmac)
                    .build()
                    .toString()
                val (code, body) = get(http.probeClient(), url)
                emit("list http=$code")
                AlertProbeShape.analyzeList(body?.toString(Charsets.UTF_8), now, known, account, base.host)
            } catch (e: Exception) {
                emit("list http=-1 error=${e.javaClass.simpleName}")
                null
            }
            shape?.let {
                emit(AlertProbeShape.formatList(it))
                AlertProbeShape.formatUrlFields(it).forEach(::emit)
                emit(AlertProbeShape.formatUid(it))
                emit(AlertProbeShape.formatFields(it))
            }
            // END_BLOCK_ALERT_PROBE_LIST

            // START_BLOCK_ALERT_PROBE_MEDIA
            val candidate = shape?.candidate
            if (shape?.code == AlertWire.SUCCESS && candidate != null) {
                val media = MediaHttp.plainClient()
                emit("media cleartextDefault=${cleartextPermitted(null)}")
                if (candidate.picExt == "yitar") {
                    emit("preview skipped=yitar")
                } else {
                    mediaAttempts("preview", candidate.picUrl).forEach { (label, u) ->
                        if (u == null) emit(label)
                        else emit(probeMedia(label, candidate.picPwd, candidate.picExt == "tar") { get(media, u) })
                        delay(300)
                    }
                }
                mediaAttempts("clip", candidate.videoUrl).forEachIndexed { i, (label, u) ->
                    if (i > 0) delay(300)
                    if (u == null) emit(label)
                    else emit(probeMedia(label, candidate.videoPwd, tar = false) { get(media, u) })
                }
            } else {
                emit("media skipped=${if (shape?.code == AlertWire.SUCCESS) "no-candidate" else "no-list"}")
            }
            // END_BLOCK_ALERT_PROBE_MEDIA

            emit("requests=$requests")
            lines
        } finally {
            running.set(false)
        }
    }

    // START_CONTRACT: mediaAttempts
    //   PURPOSE: Attempt plan for one media URL: the original URL, plus its https variant when the URL is http.
    //     Labels carry only attempt / scheme / per-host cleartext permission (no URL, no host).
    //   INPUTS: { kind: String - preview | clip; url: String - signed media URL (in memory only) }
    //   OUTPUTS: { List<Pair<label, url?>> - 1 or 2 attempts; url null = a skip line to emit as is }
    //   SIDE_EFFECTS: none
    //   LINKS: M-DEBUG-PROBE
    // END_CONTRACT: mediaAttempts
    private fun mediaAttempts(kind: String, url: String): List<Pair<String, String?>> {
        // START_BLOCK_MEDIA_ATTEMPTS
        val scheme = AlertProbeShape.schemeOf(url)
        val cleartext = url.trim().toHttpUrlOrNull()?.host?.let { cleartextPermitted(it) }
        val out = mutableListOf<Pair<String, String?>>(
            "$kind attempt=orig scheme=$scheme cleartext=${cleartext ?: "-"}" to url,
        )
        if (scheme == "http") {
            val https = AlertProbeShape.httpsVariant(url)
            out += if (https != null) "$kind attempt=https-retry scheme=https" to https
            else "$kind attempt=https-retry skipped=unparseable" to null
        }
        return out
        // END_BLOCK_MEDIA_ATTEMPTS
    }

    // START_CONTRACT: cleartextPermitted
    //   PURPOSE: Network security policy answer for cleartext to a host (null host = app default). Boolean only.
    //   INPUTS: { host: String? }
    //   OUTPUTS: { Boolean }
    //   SIDE_EFFECTS: none
    // END_CONTRACT: cleartextPermitted
    private fun cleartextPermitted(host: String?): Boolean {
        val policy = NetworkSecurityPolicy.getInstance()
        return if (host == null) policy.isCleartextTrafficPermitted else policy.isCleartextTrafficPermitted(host)
    }

    // START_CONTRACT: probeMedia
    //   PURPOSE: One media GET (no retry) reduced to a value-free line: http, size bucket, decrypt, magic, codec, tar.
    //   INPUTS: { kind: String; pwd: String - empty = raw body; tar: Boolean - pick the preview entry; fetch: the GET }
    //   OUTPUTS: { String - AlertProbeShape.formatMedia line; "http=-1 error=<SimpleName>" on any exception }
    //   SIDE_EFFECTS: the GET performed by fetch
    // END_CONTRACT: probeMedia
    private inline fun probeMedia(kind: String, pwd: String, tar: Boolean, fetch: () -> Pair<Int, ByteArray?>): String =
        try {
            val (code, body) = fetch()
            if (body == null || code !in 200..299) {
                AlertProbeShape.formatMedia(kind, code, body?.size?.toLong(), null, null, null, null)
            } else {
                val plain = if (pwd.isEmpty()) body else MediaCrypto.decrypt(body, pwd)
                val decryptOk = if (pwd.isEmpty()) null else plain != null
                var magic = plain?.let { MediaCrypto.magic(it) }
                var tarEntry: Boolean? = null
                if (tar && magic == MediaCrypto.Magic.TAR && plain != null) {
                    val entry = MediaCrypto.tarPreview(plain)
                    tarEntry = entry != null
                    magic = entry?.let { MediaCrypto.magic(it) } ?: magic
                }
                val codec = if (magic == MediaCrypto.Magic.MP4 && plain != null) MediaCrypto.codec(plain) else null
                AlertProbeShape.formatMedia(kind, code, body.size.toLong(), decryptOk, magic, codec, tarEntry)
            }
        } catch (e: Exception) {
            AlertProbeShape.formatMedia(kind, null, null, null, null, null, null, error = e.javaClass.simpleName)
        }

    private companion object {
        const val LIMIT = 20
        const val MAX_REQUESTS = 5
    }
}
