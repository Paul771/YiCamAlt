// FILE: AlertProbeShape.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Value-free shape analyzer and line formatter for the debug alert probe (v2/alert/list + media).
//   SCOPE: analyzeList (code, count, field names, digit counts, expire minutes, uid match, ext / file_type
//     classes, password lengths, per-camera counts, oldest age, first media candidate), formatList,
//     formatMedia (http, size bucket, decrypt, magic, codec, tar entry). Pure; no I/O, no logging.
//   DEPENDS: M-EVENT-PLAYBACK (MediaCrypto classes)
//   LINKS: M-DEBUG-PROBE, V-M-DEBUG-PROBE, SEC-01, EVT-01
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.debug

import com.yicamalt.event.AlertWire
import com.yicamalt.event.MediaCrypto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// START_MODULE_MAP
//   MediaCandidate - in-memory URLs and keys of the first event with media; toString prints only classes.
//   ListShape - value-free counters of one alert list response + the in-memory candidate.
//   AlertProbeShape - object: analyzeList, formatList, formatMedia, picExtOf.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 05-00: initial analyzer for the one-shot alert probe.
//     INVARIANT: formatted lines carry only counts, codes, classes, digit counts, lengths and minute / day
//     deltas; never an id, uid, URL, host, key or time value. Field names that do not look like plain
//     identifiers are counted as oddKeys, never printed. file_type values outside [a-z0-9]{1,8} are "other".
//   HAZARD: MediaCandidate holds signed URLs and keys for the next request only; never log its fields.
// END_CHANGE_SUMMARY

internal class MediaCandidate(
    val picUrl: String,
    val picPwd: String,
    val videoUrl: String,
    val videoPwd: String,
    val fileType: String,
) {
    /** Preview container class from the URL path: jpg, tar, yitar or other. */
    val picExt: String get() = AlertProbeShape.picExtOf(picUrl)

    override fun toString(): String = "MediaCandidate(picExt=$picExt, hasClip=${videoUrl.isNotBlank()})"
}

internal data class ListShape(
    val code: String,
    val count: Int,
    val keys: List<String>,
    val oddKeys: Int,
    val timeDigits: Set<Int>,
    val expireDigits: Set<Int>,
    val expireInMin: LongRange?,
    val uidMatch: Int,
    val idBlank: Int,
    val oldestAgeDays: Long?,
    val picExt: Map<String, Int>,
    val fileType: Map<String, Int>,
    val picPwdLen: Set<Int>,
    val videoPwdLen: Set<Int>,
    val cameraCounts: List<Pair<Boolean, Int>>,
    val unknownCamera: Int,
    val candidate: MediaCandidate?,
) {
    override fun toString(): String = "ListShape(code=$code, count=$count)"
}

internal object AlertProbeShape {

    private val json = Json { isLenient = true }
    private val SAFE_KEY = Regex("^[A-Za-z_][A-Za-z0-9_]{0,31}$")
    private val DIGIT_RUN = Regex("\\d{5,}")
    private val SAFE_FILE_TYPE = Regex("^[a-z0-9]{1,8}$")
    private val SAFE_CODE = Regex("^[A-Za-z0-9_-]{1,12}$")
    private const val MINUTE_MS = 60_000L
    private const val DAY_MS = 86_400_000L

    // START_CONTRACT: picExtOf
    //   PURPOSE: Classify a preview URL by its path (query ignored): yitar, tar, jpg, other.
    //   INPUTS: { url: String }
    //   OUTPUTS: { String - class name only }
    //   SIDE_EFFECTS: none
    // END_CONTRACT: picExtOf
    fun picExtOf(url: String): String {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.contains(".yitar") -> "yitar"
            path.contains(".tar") -> "tar"
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> "jpg"
            else -> "other"
        }
    }

    // START_CONTRACT: analyzeList
    //   PURPOSE: Reduce a v2/alert/list body to value-free counters and pick the first media candidate.
    //   INPUTS: { body: String? - raw body; nowMs: Long; cameras: List<Pair<deviceId, shared>> - known cameras }
    //   OUTPUTS: { ListShape - code "no-body" / "parse-error" on unusable input }
    //   SIDE_EFFECTS: none
    //   LINKS: M-DEBUG-PROBE
    // END_CONTRACT: analyzeList
    fun analyzeList(body: String?, nowMs: Long, cameras: List<Pair<String, Boolean>>): ListShape {
        // START_BLOCK_ANALYZE_LIST
        if (body == null) return empty("no-body", cameras)
        val root = try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return empty("parse-error", cameras)
        val rawCode = (root["code"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
        val code = when {
            rawCode == null -> "missing"
            SAFE_CODE.matches(rawCode) -> rawCode
            else -> "odd"
        }
        val items = (root["data"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

        val keys = sortedSetOf<String>()
        var oddKeys = 0
        val oddSeen = mutableSetOf<String>()
        val timeDigits = sortedSetOf<Int>()
        val expireDigits = sortedSetOf<Int>()
        var expMin: Long? = null
        var expMax: Long? = null
        var uidMatch = 0
        var idBlank = 0
        var oldestMs: Long? = null
        val picExt = linkedMapOf("jpg" to 0, "tar" to 0, "yitar" to 0, "other" to 0)
        val fileTypeNamed = sortedMapOf<String, Int>()
        var ftMp4 = 0
        var ftTs = 0
        var ftEmpty = 0
        var ftOther = 0
        val picPwdLen = sortedSetOf<Int>()
        val videoPwdLen = sortedSetOf<Int>()
        val camCounts = IntArray(cameras.size)
        var unknown = 0
        var full: MediaCandidate? = null
        var urlsOnly: MediaCandidate? = null
        val knownIndex = cameras.mapIndexed { i, (id, _) -> id to i }.toMap()

        items.forEach { o ->
            o.keys.forEach { k ->
                if (SAFE_KEY.matches(k) && !DIGIT_RUN.containsMatchIn(k)) keys += k
                else if (oddSeen.add(k)) oddKeys++
            }
            val time = o.str("time")?.toLongOrNull()
            time?.let { t ->
                timeDigits += t.toString().length
                val ms = if (t < 100_000_000_000L) t * 1000 else t
                oldestMs = oldestMs?.let { minOf(it, ms) } ?: ms
            }
            o.str("expire_time")?.toLongOrNull()?.let { e ->
                expireDigits += e.toString().length
                val ms = if (e < 100_000_000_000L) e * 1000 else e
                val m = Math.floorDiv(ms - nowMs, MINUTE_MS)
                expMin = expMin?.let { minOf(it, m) } ?: m
                expMax = expMax?.let { maxOf(it, m) } ?: m
            }
            val uid = o.str("uid").orEmpty()
            val idx = knownIndex[uid]
            if (idx != null) { uidMatch++; camCounts[idx]++ } else unknown++
            if (o.str("id").isNullOrBlank()) idBlank++

            val picUrls = o.str("pic_urls").orEmpty().split(',')
            val picPwds = o.str("pic_pwd").orEmpty().split(',')
            val vidUrls = o.str("video_urls").orEmpty().split(',')
            val vidPwds = o.str("video_pwd").orEmpty().split(',')
            val pi = picUrls.indexOfFirst { it.isNotBlank() }
            val vi = vidUrls.indexOfFirst { it.isNotBlank() }
            val picUrl = if (pi >= 0) picUrls[pi].trim() else ""
            val picPwd = if (pi >= 0) picPwds.getOrNull(pi).orEmpty().trim() else ""
            val vidUrl = if (vi >= 0) vidUrls[vi].trim() else ""
            val vidPwd = if (vi >= 0) vidPwds.getOrNull(vi).orEmpty().trim() else ""
            if (picUrl.isNotEmpty()) {
                picExt.merge(picExtOf(picUrl), 1, Int::plus)
                picPwdLen += picPwd.length
            }
            if (vidUrl.isNotEmpty()) videoPwdLen += vidPwd.length

            val ft = o.str("file_type").orEmpty()
            when {
                ft.isBlank() -> ftEmpty++
                ft == "mp4" -> ftMp4++
                ft == "ts" -> ftTs++
                SAFE_FILE_TYPE.matches(ft) -> fileTypeNamed.merge(ft, 1, Int::plus)
                else -> ftOther++
            }

            if (picUrl.isNotEmpty() && vidUrl.isNotEmpty()) {
                val c = MediaCandidate(picUrl, picPwd, vidUrl, vidPwd, ft)
                if (full == null && picPwd.isNotEmpty() && vidPwd.isNotEmpty()) full = c
                if (urlsOnly == null) urlsOnly = c
            }
        }

        val fileType = linkedMapOf("mp4" to ftMp4, "ts" to ftTs)
        fileType.putAll(fileTypeNamed)
        fileType["empty"] = ftEmpty
        fileType["other"] = ftOther

        val e1 = expMin
        val e2 = expMax
        return ListShape(
            code = code,
            count = items.size,
            keys = keys.toList(),
            oddKeys = oddKeys,
            timeDigits = timeDigits,
            expireDigits = expireDigits,
            expireInMin = if (e1 != null && e2 != null) e1..e2 else null,
            uidMatch = uidMatch,
            idBlank = idBlank,
            oldestAgeDays = oldestMs?.let { (nowMs - it) / DAY_MS },
            picExt = picExt,
            fileType = fileType,
            picPwdLen = picPwdLen,
            videoPwdLen = videoPwdLen,
            cameraCounts = cameras.mapIndexed { i, (_, shared) -> shared to camCounts[i] },
            unknownCamera = unknown,
            candidate = if (code == AlertWire.SUCCESS) full ?: urlsOnly else null,
        )
        // END_BLOCK_ANALYZE_LIST
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    private fun empty(code: String, cameras: List<Pair<String, Boolean>>) = ListShape(
        code = code, count = 0, keys = emptyList(), oddKeys = 0, timeDigits = emptySet(), expireDigits = emptySet(),
        expireInMin = null, uidMatch = 0, idBlank = 0, oldestAgeDays = null,
        picExt = linkedMapOf("jpg" to 0, "tar" to 0, "yitar" to 0, "other" to 0),
        fileType = linkedMapOf("mp4" to 0, "ts" to 0, "empty" to 0, "other" to 0),
        picPwdLen = emptySet(), videoPwdLen = emptySet(),
        cameraCounts = cameras.map { (_, shared) -> shared to 0 }, unknownCamera = 0, candidate = null,
    )

    // START_CONTRACT: formatList
    //   PURPOSE: One value-free line for the list response.
    //   INPUTS: { shape: ListShape }
    //   OUTPUTS: { String - "list code=.. count=.. keys=[..] oddKeys=.. timeDigits=.. expireDigits=.. expireInMin=a..b
    //     uidMatch=k/n idBlank=.. oldestAgeDays=.. picExt=.. fileType=.. picPwdLen=.. videoPwdLen=.. cameras=.." }
    //   SIDE_EFFECTS: none
    // END_CONTRACT: formatList
    fun formatList(shape: ListShape): String {
        // START_BLOCK_FORMAT_LIST
        fun set(s: Set<Int>) = if (s.isEmpty()) "-" else s.sorted().joinToString(",")
        fun map(m: Map<String, Int>) = m.entries.joinToString(",") { "${it.key}:${it.value}" }
        val cams = shape.cameraCounts.mapIndexed { i, (shared, n) -> "c$i(shared=$shared):$n" } +
            "unknown:${shape.unknownCamera}"
        return buildString {
            append("list code=").append(shape.code)
            append(" count=").append(shape.count)
            append(" keys=[").append(shape.keys.joinToString(",")).append(']')
            append(" oddKeys=").append(shape.oddKeys)
            append(" timeDigits=").append(set(shape.timeDigits))
            append(" expireDigits=").append(set(shape.expireDigits))
            append(" expireInMin=").append(shape.expireInMin?.let { "${it.first}..${it.last}" } ?: "-")
            append(" uidMatch=").append(shape.uidMatch).append('/').append(shape.count)
            append(" idBlank=").append(shape.idBlank)
            append(" oldestAgeDays=").append(shape.oldestAgeDays ?: "-")
            append(" picExt=").append(map(shape.picExt))
            append(" fileType=").append(map(shape.fileType))
            append(" picPwdLen=").append(set(shape.picPwdLen))
            append(" videoPwdLen=").append(set(shape.videoPwdLen))
            append(" cameras=").append(cams.joinToString(","))
        }
        // END_BLOCK_FORMAT_LIST
    }

    // START_CONTRACT: formatMedia
    //   PURPOSE: One value-free line for a media download.
    //   INPUTS: { kind: String - preview | clip; http: Int? (null = -1); bytes: Long?; decryptOk: Boolean?
    //     (null = raw, no key); magic, codec: classes or null; tarEntry: Boolean?; error: exception simple name }
    //   OUTPUTS: { String - "<kind> http=.. sizeBucket=.. decrypt=ok|fail|raw magic=.. codec=.. tarEntry=..[ error=..]" }
    //   SIDE_EFFECTS: none
    // END_CONTRACT: formatMedia
    fun formatMedia(
        kind: String,
        http: Int?,
        bytes: Long?,
        decryptOk: Boolean?,
        magic: MediaCrypto.Magic?,
        codec: MediaCrypto.Codec?,
        tarEntry: Boolean?,
        error: String? = null,
    ): String {
        // START_BLOCK_FORMAT_MEDIA
        val decrypt = when (decryptOk) { true -> "ok"; false -> "fail"; null -> "raw" }
        val line = "$kind http=${http ?: -1} sizeBucket=${sizeBucket(bytes)} decrypt=$decrypt" +
            " magic=${magic?.name?.lowercase() ?: "-"} codec=${codec?.name?.lowercase() ?: "-"}" +
            " tarEntry=${when (tarEntry) { true -> "yes"; false -> "no"; null -> "-" }}"
        return if (error != null) "$line error=$error" else line
        // END_BLOCK_FORMAT_MEDIA
    }

    private fun sizeBucket(bytes: Long?): String = when {
        bytes == null -> "-"
        bytes < 16 * 1024 -> "<16KB"
        bytes < 64 * 1024 -> "16-64KB"
        bytes < 256 * 1024 -> "64-256KB"
        bytes < 1024 * 1024 -> "256KB-1MB"
        bytes < 4 * 1024 * 1024 -> "1-4MB"
        else -> ">4MB"
    }
}
