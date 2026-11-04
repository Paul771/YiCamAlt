// FILE: AlertProbeShape.kt
// VERSION: 0.2.0
// START_MODULE_CONTRACT
//   PURPOSE: Value-free shape analyzer and line formatter for the debug alert probe (v2/alert/list + media).
//   SCOPE: analyzeList (code, count, field names, digit counts, expire minutes, uid match, ext / file_type
//     classes, password lengths, per-camera counts, oldest age, first media candidate; URL field forms for
//     pic_urls / video_urls / video02_urls; uid vs every known identifier type; field value kinds; video02
//     shape), formatList, formatUrlFields, formatUid, formatFields, formatMedia (http, size bucket, decrypt,
//     magic, codec, tar entry), schemeOf, httpsVariant. Pure; no I/O, no logging.
//   DEPENDS: M-EVENT-PLAYBACK (MediaCrypto classes)
//   LINKS: M-DEBUG-PROBE, V-M-DEBUG-PROBE, SEC-01, EVT-01, EVT-03
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.debug

import com.yicamalt.event.AlertWire
import com.yicamalt.event.MediaCrypto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// START_MODULE_MAP
//   MediaCandidate - in-memory URLs and keys of the first event with media; toString prints only classes.
//   CameraIds - one known camera: shared flag + identifier values by type tag (dev, p2p, ...); in memory only.
//   UrlFieldShape - value-free form counters of one URL field (types, elems, schemes, host labels, query, ext).
//   IdTypeShape - value-free uid match counters against one identifier type.
//   ListShape - value-free counters of one alert list response + the in-memory candidate.
//   AlertProbeShape - object: analyzeList, formatList, formatUrlFields, formatUid, formatFields, formatMedia,
//     picExtOf, schemeOf, httpsVariant.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.2.0 - 05-00 follow-up after the first device run (URL fields are plural, media GETs died on
//     cleartext, uid matched no deviceId): URL field forms (string / array / object, elems, scheme, host label
//     count, ip host, query, same base domain as the API host, path ext), uid vs every identifier type
//     (eq / case-insensitive / prefix / suffix / substring, lengths, char class), field value kinds, video02
//     keys and key-equality counts, distinct host count, https variant of an http media URL. Array-shaped URL
//     and pwd fields now feed the media candidate too.
//   PREVIOUS: v0.1.0 - GSD plan 05-00: initial analyzer for the one-shot alert probe.
//     INVARIANT: formatted lines carry only counts, codes, classes, digit counts, lengths and minute / day
//     deltas; never an id, uid, URL, host, key or time value. Field names that do not look like plain
//     identifiers are counted as oddKeys, never printed. file_type values outside [a-z0-9]{1,8} are "other".
//   HAZARD: MediaCandidate and CameraIds hold signed URLs, keys and ids in memory only; never log their fields.
//     Identifier type tags must not be scanner keywords (did, p2pid, serial, pwd ...): use dev / p2p / user.
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

internal data class CameraIds(val shared: Boolean, val ids: Map<String, String>) {
    override fun toString(): String = "CameraIds(shared=$shared, types=${ids.keys.sorted()})"
}

internal data class UrlFieldShape(
    val field: String,
    val types: Map<String, Int>,
    val elems: Set<Int>,
    val urls: Int,
    val schemes: Map<String, Int>,
    val hostLabels: Set<Int>,
    val ipHost: Int,
    val withQuery: Int,
    val apiBase: Int?,
    val ext: Map<String, Int>,
)

internal data class IdTypeShape(
    val type: String,
    val have: Int,
    val total: Int,
    val lens: Set<Int>,
    val eq: Int,
    val ci: Int,
    val prefix: Int,
    val suffix: Int,
    val substr: Int,
)

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
    val urlFields: List<UrlFieldShape> = emptyList(),
    val uidLens: Set<Int> = emptySet(),
    val uidClasses: Map<String, Int> = emptyMap(),
    val idTypes: List<IdTypeShape> = emptyList(),
    val uidAnyCi: Int = 0,
    val kinds: Map<String, Set<String>> = emptyMap(),
    val video02Keys: List<String> = emptyList(),
    val video02Present: Int = 0,
    val video02PwdLen: Set<Int> = emptySet(),
    val samePicVideoKey: Int = 0,
    val sameVideo02VideoKey: Int = 0,
    val distinctHosts: Int = 0,
) {
    override fun toString(): String = "ListShape(code=$code, count=$count)"
}

internal object AlertProbeShape {

    private val json = Json { isLenient = true }
    private val SAFE_KEY = Regex("^[A-Za-z_][A-Za-z0-9_]{0,31}$")
    private val DIGIT_RUN = Regex("\\d{5,}")
    private val SAFE_FILE_TYPE = Regex("^[a-z0-9]{1,8}$")
    private val SAFE_CODE = Regex("^[A-Za-z0-9_-]{1,12}$")
    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private const val MINUTE_MS = 60_000L
    private const val DAY_MS = 86_400_000L
    private const val MIN_PARTIAL_LEN = 4

    /** URL fields whose form is reported, in output order. */
    val URL_FIELDS = listOf("pic_urls", "video_urls", "video02_urls")

    /** Camera identifier type tag used for per-camera counts. */
    const val ID_DEV = "dev"

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

    // START_CONTRACT: schemeOf
    //   PURPOSE: Scheme class of a URL: http, https or other (case-insensitive).
    //   INPUTS: { url: String }
    //   OUTPUTS: { String - class only }
    //   SIDE_EFFECTS: none
    // END_CONTRACT: schemeOf
    fun schemeOf(url: String): String = when (url.trim().substringBefore("://", "").lowercase()) {
        "http" -> "http"
        "https" -> "https"
        else -> "other"
    }

    // START_CONTRACT: httpsVariant
    //   PURPOSE: Same URL with scheme https (path, query and non-default port kept; default port 80 dropped).
    //   INPUTS: { url: String }
    //   OUTPUTS: { String? - null when the URL is not http or does not parse }
    //   SIDE_EFFECTS: none
    // END_CONTRACT: httpsVariant
    fun httpsVariant(url: String): String? {
        val u = url.trim().toHttpUrlOrNull() ?: return null
        if (u.scheme != "http") return null
        return u.newBuilder().scheme("https").build().toString()
    }

    // START_CONTRACT: analyzeList
    //   PURPOSE: Reduce a v2/alert/list body to value-free counters and pick the first media candidate
    //     (legacy entry: cameras as deviceId + shared pairs, no account ids, no API host).
    //   INPUTS: { body: String? - raw body; nowMs: Long; cameras: List<Pair<deviceId, shared>> - known cameras }
    //   OUTPUTS: { ListShape - code "no-body" / "parse-error" on unusable input }
    //   SIDE_EFFECTS: none
    //   LINKS: M-DEBUG-PROBE
    // END_CONTRACT: analyzeList
    fun analyzeList(body: String?, nowMs: Long, cameras: List<Pair<String, Boolean>>): ListShape =
        analyzeList(body, nowMs, cameras.map { (id, shared) -> CameraIds(shared, mapOf(ID_DEV to id)) }, emptyMap(), null)

    // START_CONTRACT: analyzeListFull
    //   PURPOSE: Full analyzer: legacy counters + URL field forms, uid vs every identifier type, field kinds,
    //     video02 shape and distinct host count.
    //   INPUTS: { body: String?; nowMs: Long; cameras: List<CameraIds> - ids by type tag ("dev" drives the
    //     per-camera counts); accountIds: Map<tag, value> - account-level ids (e.g. "user");
    //     apiHost: String? - Yi API host for the same-base-domain check (null = not checked) }
    //   OUTPUTS: { ListShape }
    //   SIDE_EFFECTS: none
    //   LINKS: M-DEBUG-PROBE
    // END_CONTRACT: analyzeListFull
    fun analyzeList(
        body: String?,
        nowMs: Long,
        cameras: List<CameraIds>,
        accountIds: Map<String, String>,
        apiHost: String?,
    ): ListShape {
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
        val knownIndex = cameras.mapIndexedNotNull { i, c -> c.ids[ID_DEV]?.let { it to i } }.toMap()

        val urlAcc = URL_FIELDS.associateWith { UrlAcc(it) }
        val apiBase = apiHost?.lowercase()?.let { baseOf(it) }
        val hosts = mutableSetOf<String>()
        val uidAcc = UidAcc(cameras, accountIds)
        val kinds = sortedMapOf<String, MutableSet<String>>()
        var v02Present = 0
        val v02PwdLen = sortedSetOf<Int>()
        var samePicVideo = 0
        var sameV02Video = 0

        items.forEach { o ->
            o.keys.forEach { k ->
                if (SAFE_KEY.matches(k) && !DIGIT_RUN.containsMatchIn(k)) {
                    keys += k
                    kinds.getOrPut(k) { sortedSetOf() } += kindOf(o[k])
                } else if (oddSeen.add(k)) oddKeys++
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
            uidAcc.add(uid)
            if (o.str("id").isNullOrBlank()) idBlank++

            URL_FIELDS.forEach { f -> urlAcc.getValue(f).add(o[f], apiBase, hosts) }

            val picUrls = elemsOf(o["pic_urls"]).orEmpty()
            val picPwds = elemsOf(o["pic_pwd"]).orEmpty()
            val vidUrls = elemsOf(o["video_urls"]).orEmpty()
            val vidPwds = elemsOf(o["video_pwd"]).orEmpty()
            val pi = picUrls.indexOfFirst { it.isNotBlank() }
            val vi = vidUrls.indexOfFirst { it.isNotBlank() }
            val picUrl = if (pi >= 0) picUrls[pi] else ""
            val picPwd = if (pi >= 0) picPwds.getOrNull(pi).orEmpty() else ""
            val vidUrl = if (vi >= 0) vidUrls[vi] else ""
            val vidPwd = if (vi >= 0) vidPwds.getOrNull(vi).orEmpty() else ""
            if (picUrl.isNotEmpty()) {
                picExt.merge(picExtOf(picUrl), 1, Int::plus)
                picPwdLen += picPwd.length
            }
            if (vidUrl.isNotEmpty()) videoPwdLen += vidPwd.length

            // video02 + key equality (first non-blank element of each pwd field)
            if (elemsOf(o["video02_urls"]).orEmpty().any { it.isNotBlank() }) v02Present++
            val p1 = firstNonBlank(o["pic_pwd"])
            val p2 = firstNonBlank(o["video_pwd"])
            val p3 = firstNonBlank(o["video02_pwd"])
            p3?.let { v02PwdLen += it.length }
            if (p1 != null && p1 == p2) samePicVideo++
            if (p3 != null && p3 == p2) sameV02Video++

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
            cameraCounts = cameras.mapIndexed { i, c -> c.shared to camCounts[i] },
            unknownCamera = unknown,
            candidate = if (code == AlertWire.SUCCESS) full ?: urlsOnly else null,
            urlFields = URL_FIELDS.map { urlAcc.getValue(it).build(apiBase != null) },
            uidLens = uidAcc.lens,
            uidClasses = uidAcc.classes,
            idTypes = uidAcc.build(),
            uidAnyCi = uidAcc.anyCi,
            kinds = kinds,
            video02Keys = keys.filter { it.startsWith("video02") },
            video02Present = v02Present,
            video02PwdLen = v02PwdLen,
            samePicVideoKey = samePicVideo,
            sameVideo02VideoKey = sameV02Video,
            distinctHosts = hosts.size,
        )
        // END_BLOCK_ANALYZE_LIST
    }

    // START_BLOCK_URL_FORM_HELPERS
    /** Elements of a string (comma split) or array field; empty for a blank string; null for other kinds. */
    private fun elemsOf(e: JsonElement?): List<String>? = when (e) {
        null, JsonNull -> null
        is JsonPrimitive -> if (!e.isString) null else if (e.content.isBlank()) emptyList()
        else e.content.split(',').map { it.trim() }
        is JsonArray -> e.map { el -> (el as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.trim().orEmpty() }
        is JsonObject -> null
    }

    private fun firstNonBlank(e: JsonElement?): String? = elemsOf(e)?.firstOrNull { it.isNotBlank() }

    private fun typeOf(e: JsonElement?): String = when (e) {
        null -> "missing"
        JsonNull -> "null"
        is JsonPrimitive -> when {
            e.isString -> "string"
            e.booleanOrNull != null -> "bool"
            else -> "number"
        }
        is JsonArray -> "array"
        is JsonObject -> "object"
    }

    private fun kindOf(e: JsonElement?): String = when (typeOf(e)) {
        "string" -> "str"
        "number" -> "num"
        "array" -> "arr"
        "object" -> "obj"
        else -> typeOf(e)
    }

    private fun hostOf(url: String): String? {
        val rest = url.substringAfter("://", "")
        if (rest.isEmpty()) return null
        val authority = rest.takeWhile { it != '/' && it != '?' && it != '#' }.substringAfterLast('@')
        val h = if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')
        return h.lowercase().takeIf { it.isNotEmpty() }
    }

    private fun pathOf(url: String): String {
        val rest = url.substringAfter("://", url)
        val slash = rest.indexOf('/')
        return if (slash < 0) "" else rest.substring(slash).substringBefore('?').substringBefore('#').lowercase()
    }

    private fun isIpHost(host: String) = host.startsWith("[") || IPV4.matches(host)

    private fun baseOf(host: String): String = host.split('.').takeLast(2).joinToString(".")

    private fun extOf(url: String): String {
        val p = pathOf(url)
        return when {
            p.contains(".yitar") -> "yitar"
            p.contains(".tar") -> "tar"
            p.endsWith(".jpg") || p.endsWith(".jpeg") -> "jpg"
            p.endsWith(".png") -> "png"
            p.endsWith(".mp4") -> "mp4"
            p.endsWith(".m3u8") -> "m3u8"
            p.endsWith(".ts") -> "ts"
            else -> "other"
        }
    }

    private class UrlAcc(val field: String) {
        val types = sortedMapOf<String, Int>()
        val elems = sortedSetOf<Int>()
        var urls = 0
        val schemes = linkedMapOf("http" to 0, "https" to 0, "other" to 0)
        val labels = sortedSetOf<Int>()
        var ip = 0
        var query = 0
        var api = 0
        val ext = sortedMapOf<String, Int>()

        fun add(e: JsonElement?, apiBase: String?, hosts: MutableSet<String>) {
            types.merge(typeOf(e), 1, Int::plus)
            val list = elemsOf(e)
            elems += list?.size ?: 0
            list.orEmpty().filter { it.isNotBlank() }.forEach { u ->
                urls++
                schemes.merge(schemeOf(u), 1, Int::plus)
                hostOf(u)?.let { h ->
                    hosts += h
                    if (isIpHost(h)) ip++ else labels += h.split('.').size
                    if (apiBase != null && !isIpHost(h) && baseOf(h) == apiBase) api++
                }
                if (u.substringAfter('?', "").isNotEmpty()) query++
                ext.merge(extOf(u), 1, Int::plus)
            }
        }

        fun build(apiChecked: Boolean) = UrlFieldShape(
            field, types, elems, urls, schemes, labels, ip, query, if (apiChecked) api else null, ext,
        )
    }
    // END_BLOCK_URL_FORM_HELPERS

    // START_BLOCK_UID_MATCH
    private fun charClass(s: String): String = when {
        s.isEmpty() -> "empty"
        s.all { it.isDigit() } -> "digits"
        s.all { it in '0'..'9' || it in 'a'..'f' } -> "hex-lower"
        s.all { it in '0'..'9' || it in 'A'..'F' } -> "hex-upper"
        s.all { it in '0'..'9' || it in 'A'..'Z' } -> "upper-alnum"
        s.all { it in '0'..'9' || it in 'a'..'z' } -> "lower-alnum"
        s.all { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' } -> "mixed-alnum"
        else -> "other"
    }

    private class TypeAcc(val type: String, val values: List<String>, val total: Int) {
        var eq = 0
        var ci = 0
        var pre = 0
        var suf = 0
        var sub = 0

        fun add(uid: String): Boolean {
            if (uid.isEmpty()) return false
            val partial = values.filter { it != uid && minOf(it.length, uid.length) >= MIN_PARTIAL_LEN }
            val isCi = values.any { it.equals(uid, ignoreCase = true) }
            if (values.any { it == uid }) eq++
            if (isCi) ci++
            if (partial.any { it.startsWith(uid) || uid.startsWith(it) }) pre++
            if (partial.any { it.endsWith(uid) || uid.endsWith(it) }) suf++
            if (partial.any { it.contains(uid) || uid.contains(it) }) sub++
            return isCi
        }

        fun build() = IdTypeShape(type, values.size, total, values.map { it.length }.toSortedSet(), eq, ci, pre, suf, sub)
    }

    private class UidAcc(cameras: List<CameraIds>, accountIds: Map<String, String>) {
        val lens = sortedSetOf<Int>()
        val classes = sortedMapOf<String, Int>()
        var anyCi = 0
        private val types: List<TypeAcc> = buildList {
            val camTypes = linkedSetOf<String>()
            cameras.forEach { camTypes += it.ids.keys }
            camTypes.forEach { t ->
                add(TypeAcc(t, cameras.mapNotNull { c -> c.ids[t]?.takeIf { it.isNotBlank() } }, cameras.size))
            }
            accountIds.forEach { (t, v) -> add(TypeAcc(t, listOfNotNull(v.takeIf { it.isNotBlank() }), 1)) }
        }

        fun add(uid: String) {
            lens += uid.length
            classes.merge(charClass(uid), 1, Int::plus)
            var hit = false
            types.forEach { if (it.add(uid)) hit = true }
            if (hit) anyCi++
        }

        fun build(): List<IdTypeShape> = types.map { it.build() }
    }
    // END_BLOCK_UID_MATCH

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    private fun empty(code: String, cameras: List<CameraIds>) = ListShape(
        code = code, count = 0, keys = emptyList(), oddKeys = 0, timeDigits = emptySet(), expireDigits = emptySet(),
        expireInMin = null, uidMatch = 0, idBlank = 0, oldestAgeDays = null,
        picExt = linkedMapOf("jpg" to 0, "tar" to 0, "yitar" to 0, "other" to 0),
        fileType = linkedMapOf("mp4" to 0, "ts" to 0, "empty" to 0, "other" to 0),
        picPwdLen = emptySet(), videoPwdLen = emptySet(),
        cameraCounts = cameras.map { it.shared to 0 }, unknownCamera = 0, candidate = null,
    )

    private fun set(s: Set<Int>) = if (s.isEmpty()) "-" else s.sorted().joinToString(",")
    private fun map(m: Map<String, Int>) = if (m.isEmpty()) "-" else m.entries.joinToString(",") { "${it.key}:${it.value}" }

    // START_CONTRACT: formatList
    //   PURPOSE: One value-free line for the list response.
    //   INPUTS: { shape: ListShape }
    //   OUTPUTS: { String - "list code=.. count=.. keys=[..] oddKeys=.. timeDigits=.. expireDigits=.. expireInMin=a..b
    //     uidMatch=k/n idBlank=.. oldestAgeDays=.. picExt=.. fileType=.. picPwdLen=.. videoPwdLen=.. cameras=.." }
    //   SIDE_EFFECTS: none
    // END_CONTRACT: formatList
    fun formatList(shape: ListShape): String {
        // START_BLOCK_FORMAT_LIST
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

    // START_CONTRACT: formatUrlFields
    //   PURPOSE: One value-free line per URL field (pic_urls, video_urls, video02_urls).
    //   INPUTS: { shape: ListShape }
    //   OUTPUTS: { List<String> - "urlshape field=.. types=.. elems=.. urls=.. schemes=http:a,https:b,other:c
    //     hostLabels=.. ipHost=.. query=.. apiBase=<n|-> ext=.." }
    //   SIDE_EFFECTS: none
    // END_CONTRACT: formatUrlFields
    fun formatUrlFields(shape: ListShape): List<String> = shape.urlFields.map { u ->
        // START_BLOCK_FORMAT_URL_FIELDS
        "urlshape field=${u.field} types=${map(u.types)} elems=${set(u.elems)} urls=${u.urls}" +
            " schemes=${map(u.schemes)} hostLabels=${set(u.hostLabels)} ipHost=${u.ipHost} query=${u.withQuery}" +
            " apiBase=${u.apiBase ?: "-"} ext=${map(u.ext)}"
        // END_BLOCK_FORMAT_URL_FIELDS
    }

    // START_CONTRACT: formatUid
    //   PURPOSE: One value-free line: uid lengths / char classes and per identifier type match counts.
    //   INPUTS: { shape: ListShape }
    //   OUTPUTS: { String - "uid n=.. lens=.. class=.. <type>[have=k/n lens=.. eq=.. ci=.. pre=.. suf=.. sub=..] ..
    //     any=k/n" (any = case-insensitive equal to some identifier of some type) }
    //   SIDE_EFFECTS: none
    // END_CONTRACT: formatUid
    fun formatUid(shape: ListShape): String {
        // START_BLOCK_FORMAT_UID
        val types = shape.idTypes.joinToString(" ") { t ->
            "${t.type}[have=${t.have}/${t.total} lens=${set(t.lens)} eq=${t.eq} ci=${t.ci} pre=${t.prefix}" +
                " suf=${t.suffix} sub=${t.substr}]"
        }
        return "uid n=${shape.count} lens=${set(shape.uidLens)} class=${map(shape.uidClasses)}" +
            (if (types.isEmpty()) "" else " $types") + " any=${shape.uidAnyCi}/${shape.count}"
        // END_BLOCK_FORMAT_UID
    }

    // START_CONTRACT: formatFields
    //   PURPOSE: One value-free line: value kind per field name, video02 keys / presence / pwd length,
    //     key-equality counts and the distinct media host count.
    //   INPUTS: { shape: ListShape }
    //   OUTPUTS: { String - "fields n=.. kinds=[k:str/num,..] video02Keys=[..] video02Present=.. video02PwdLen=..
    //     sameKey=picVideo:a,video02Video:b hosts=.." }
    //   SIDE_EFFECTS: none
    // END_CONTRACT: formatFields
    fun formatFields(shape: ListShape): String {
        // START_BLOCK_FORMAT_FIELDS
        val kinds = shape.kinds.entries.joinToString(",") { "${it.key}:${it.value.joinToString("/")}" }
        return "fields n=${shape.count} kinds=[$kinds] video02Keys=[${shape.video02Keys.joinToString(",")}]" +
            " video02Present=${shape.video02Present} video02PwdLen=${set(shape.video02PwdLen)}" +
            " sameKey=picVideo:${shape.samePicVideoKey},video02Video:${shape.sameVideo02VideoKey}" +
            " hosts=${shape.distinctHosts}"
        // END_BLOCK_FORMAT_FIELDS
    }

    // START_CONTRACT: formatMedia
    //   PURPOSE: One value-free line for a media download.
    //   INPUTS: { kind: String - preview | clip (optionally suffixed with attempt / scheme / cleartext fields);
    //     http: Int? (null = -1); bytes: Long?; decryptOk: Boolean? (null = raw, no key); magic, codec: classes or
    //     null; tarEntry: Boolean?; error: exception simple name }
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
