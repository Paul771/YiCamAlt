// FILE: AlertProbe.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Debug-only one-shot probe of the real alert feed: one signed GET v2/alert/list (30 days, limit 20),
//     then at most one preview GET and one clip GET, decrypted in memory; returns value-free shape lines.
//   SCOPE: session checks, signed list request on http.probeClient(), media GETs on MediaHttp.plainClient(),
//     AlertProbeShape formatting. Hard cap 3 requests per press, no retries, no auth calls.
//   DEPENDS: M-HTTP, M-AUTH, M-CONFIG, M-CAMERA-LIST, M-EVENT (AlertWire), M-EVENT-PLAYBACK (MediaCrypto, MediaHttp)
//   LINKS: M-DEBUG-PROBE, V-M-DEBUG-PROBE, EVT-01, EVT-03, EVT-06
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.debug

import com.yicamalt.BuildConfig
import com.yicamalt.auth.AuthRepository
import com.yicamalt.camera.CameraListPort
import com.yicamalt.config.ConfigModule
import com.yicamalt.event.AlertWire
import com.yicamalt.event.MediaCrypto
import com.yicamalt.event.MediaHttp
import com.yicamalt.network.HttpClientModule
import com.yicamalt.network.YiRequestSigner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

// START_MODULE_MAP
//   AlertProbe - runOnce(): debug-only, <=3 requests (list + preview + clip); value-free lines out.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 05-00: one-shot alert feed probe for the Phase 5 open questions (expires,
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
) {
    private val running = AtomicBoolean(false)

    // START_CONTRACT: runOnce
    //   PURPOSE: Run the alert probe once and return the formatted (value-free) lines.
    //   INPUTS: none (session from AuthRepository, region from ConfigModule, known cameras from Room cache)
    //   OUTPUTS: { List<String> - list line, optional preview / clip lines, summary; or a single reason line }
    //   SIDE_EFFECTS: <=3 GET requests (1 Yi gateway, <=2 media CDN); Timber debug lines
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
            val known = cameras.cachedCameras().map { it.deviceId to it.shared }
            val shape = try {
                val url = config.getApiBaseUrl().toHttpUrl().newBuilder()
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
                AlertProbeShape.analyzeList(body?.toString(Charsets.UTF_8), now, known)
            } catch (e: Exception) {
                emit("list http=-1 error=${e.javaClass.simpleName}")
                null
            }
            shape?.let { emit(AlertProbeShape.formatList(it)) }
            // END_BLOCK_ALERT_PROBE_LIST

            // START_BLOCK_ALERT_PROBE_MEDIA
            val candidate = shape?.candidate
            if (shape?.code == AlertWire.SUCCESS && candidate != null) {
                val media = MediaHttp.plainClient()
                if (candidate.picExt == "yitar") {
                    emit("preview skipped=yitar")
                } else {
                    emit(probeMedia("preview", candidate.picPwd, candidate.picExt == "tar") { get(media, candidate.picUrl) })
                }
                delay(300)
                emit(probeMedia("clip", candidate.videoPwd, tar = false) { get(media, candidate.videoUrl) })
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
        const val MAX_REQUESTS = 3
    }
}
