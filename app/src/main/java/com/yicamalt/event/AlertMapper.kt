// FILE: AlertMapper.kt
// VERSION: 0.2.0
// START_MODULE_CONTRACT
//   PURPOSE: Strict, opt-tolerant mapping of a v2/alert/list envelope to AlertRecord + MediaRef.
//   SCOPE: page(envelope) - success check, item validation (uid and time required), key id or uid_time,
//     first non-empty comma element of pic_urls / video_urls with its aligned pwd, http -> https rewrite of
//     media URLs, per-page de-duplication, oldest time, raw count.
//   DEPENDS: M-EVENT (AlertWire, AlertEnvelope, EventInfo types), M-EVENT-PLAYBACK (MediaHttp.secureUrl)
//   LINKS: M-EVENT, V-M-EVENT, EVT-01, EVT-03
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.event

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import timber.log.Timber

// START_MODULE_MAP
//   AlertMapper - object: page(envelope) -> AlertPage.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.2.0 - GSD plan 05-01: re-pointed to GET v2/alert/list per 05-RESEARCH §1 and 05-PROBE.
//     05-PROBE: field names are PLURAL (pic_urls, video_urls) and each value is ONE string (not a JSON
//     array); the comma split stays for vendor tolerance. Times are 13-digit ms (kept as is). play_time is
//     absent on real data -> playTimeS 0. Media URLs arrive as http and are stored as https (MediaHttp.secureUrl).
//   HAZARD: never log ids, uids, URLs, keys or times here; the single log line carries counts only.
// END_CHANGE_SUMMARY

internal object AlertMapper {

    private const val F_ID = "id"
    private const val F_UID = "uid"
    private const val F_TIME = "time"
    private const val F_TYPE = "type"
    private const val F_SUB_TYPE = "sub_type"
    private const val F_PIC_URLS = "pic_urls"
    private const val F_PIC_PWD = "pic_pwd"
    private const val F_VIDEO_URLS = "video_urls"
    private const val F_VIDEO_PWD = "video_pwd"
    private const val F_EXPIRE = "expire_time"
    private const val F_FILE_TYPE = "file_type"
    private const val F_PLAY_TIME = "play_time"

    // START_CONTRACT: page
    //   PURPOSE: Map one v2/alert/list response.
    //   INPUTS: { envelope: AlertEnvelope - code (string or number), data (expected array of objects) }
    //   OUTPUTS: { AlertPage - success only when code == AlertWire.SUCCESS; records in response order, first
    //     occurrence of a key wins; media keyed like records; oldestTimeMs = min kept time; rawCount = array size }
    //   SIDE_EFFECTS: one value-free Timber.d line (code, raw, kept)
    //   LINKS: AlertWire.SUCCESS, MediaHttp.secureUrl
    // END_CONTRACT: page
    fun page(envelope: AlertEnvelope): AlertPage {
        // START_BLOCK_MAP_ALERTS
        val code = envelope.code
        if (code != AlertWire.SUCCESS) {
            Timber.d("[Event][map][BLOCK_MAP_ALERTS] code=%s raw=%d kept=%d", code, 0, 0)
            return AlertPage(code, false, emptyList(), emptyMap(), null, 0)
        }
        val items = envelope.data as? JsonArray ?: JsonArray(emptyList())
        val records = LinkedHashMap<String, AlertRecord>()
        val media = HashMap<String, MediaRef>()
        for (element in items) {
            val obj = element as? JsonObject ?: continue
            val uid = obj.str(F_UID)
            val time = obj.long(F_TIME)
            if (uid.isEmpty() || time <= 0L) continue
            val id = obj.str(F_ID)
            val key = if (id.isNotEmpty()) id else "${uid}_$time"
            if (key in records) continue
            val (picUrl, picPwd) = firstAligned(obj.str(F_PIC_URLS), obj.str(F_PIC_PWD))
            val (videoUrl, videoPwd) = firstAligned(obj.str(F_VIDEO_URLS), obj.str(F_VIDEO_PWD))
            records[key] = AlertRecord(
                key = key,
                deviceId = uid,
                timeMs = time,
                type = obj.long(F_TYPE).toInt(),
                subType = obj.long(F_SUB_TYPE).toInt(),
                fileType = obj.str(F_FILE_TYPE),
                playTimeS = obj.long(F_PLAY_TIME).toInt(),
                hasClip = videoUrl != null,
            )
            media[key] = MediaRef(
                picUrl = picUrl?.let(MediaHttp::secureUrl),
                picPwd = picPwd,
                videoUrl = videoUrl?.let(MediaHttp::secureUrl),
                videoPwd = videoPwd,
                expireMs = obj.long(F_EXPIRE),
            )
        }
        Timber.d("[Event][map][BLOCK_MAP_ALERTS] code=%s raw=%d kept=%d", code, items.size, records.size)
        return AlertPage(
            code = code,
            success = true,
            records = records.values.toList(),
            media = media,
            oldestTimeMs = records.values.minOfOrNull { it.timeMs },
            rawCount = items.size,
        )
        // END_BLOCK_MAP_ALERTS
    }

    // START_CONTRACT: firstAligned
    //   PURPOSE: First non-blank element of a comma list with the pwd at the same index.
    //   INPUTS: { urls: String - comma list (05-PROBE: one element); pwds: String - aligned comma list }
    //   OUTPUTS: { Pair<String?, String?> - (url, pwd or null when blank / missing); (null, null) if no url }
    //   SIDE_EFFECTS: none
    // END_CONTRACT: firstAligned
    private fun firstAligned(urls: String, pwds: String): Pair<String?, String?> {
        // START_BLOCK_ALIGN_MEDIA
        val u = urls.split(',')
        val index = u.indexOfFirst { it.isNotBlank() }
        if (index < 0) return null to null
        val pwd = pwds.split(',').getOrNull(index)?.trim()?.takeIf { it.isNotEmpty() }
        return u[index].trim() to pwd
        // END_BLOCK_ALIGN_MEDIA
    }

    private fun JsonObject.prim(name: String): JsonPrimitive? =
        (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }

    private fun JsonObject.str(name: String): String = prim(name)?.content?.trim().orEmpty()

    private fun JsonObject.long(name: String): Long {
        val c = prim(name)?.content?.trim() ?: return 0L
        return c.toLongOrNull() ?: c.toDoubleOrNull()?.toLong() ?: 0L
    }
}
