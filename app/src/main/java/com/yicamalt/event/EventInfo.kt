// FILE: EventInfo.kt
// VERSION: 0.2.0
// START_MODULE_CONTRACT
//   PURPOSE: Value types of the real alert feed (GET v2/alert/list): one alert record, its in-memory
//     media reference and one mapped page.
//   SCOPE: AlertRecord (persistable, value-free toString), MediaRef (signed URLs and keys, memory only,
//     redacted toString, staleness rule), AlertPage (mapper output). Pure; no I/O, no logging.
//   DEPENDS: none
//   LINKS: M-EVENT, M-EVENT-PLAYBACK, M-LOCAL-DB, V-M-EVENT, EVT-01, EVT-03
//   ROLE: TYPES
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.event

// START_MODULE_MAP
//   AlertRecord - key, deviceId, timeMs, type, subType, fileType, playTimeS, hasClip; toString hides key and id.
//   MediaRef - picUrl, picPwd, videoUrl, videoPwd (https form), expireMs; isStale(nowMs); redacted toString.
//   AlertPage - code, success, records, media (key -> MediaRef), oldestTimeMs, rawCount.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.2.0 - GSD plan 05-01: re-pointed to GET v2/alert/list per 05-RESEARCH §1 and 05-PROBE.
//     Replaced the guessed EventInfo / EventPage / ParsedTimeline / timeline parser with AlertRecord,
//     MediaRef and AlertPage. 05-PROBE: times are ms, uid == CameraInfo.deviceId, play_time is absent
//     (playTimeS defaults to 0), media URLs are http and are stored rewritten to https.
//   HAZARD: MediaRef carries signed URLs and decrypt keys. Never persist it (Room) and never log its fields;
//     its toString is redacted on purpose.
// END_CHANGE_SUMMARY

/** One alert of the feed. Persistable: carries no URL and no key. */
data class AlertRecord(
    val key: String,
    val deviceId: String,
    val timeMs: Long,
    val type: Int,
    val subType: Int,
    val fileType: String,
    val playTimeS: Int,
    val hasClip: Boolean,
) {
    override fun toString(): String =
        "AlertRecord(type=$type, subType=$subType, fileType=$fileType, hasClip=$hasClip)"
}

/** Signed media URLs (https form) and keys of one alert; memory only. */
class MediaRef(
    val picUrl: String?,
    val picPwd: String?,
    val videoUrl: String?,
    val videoPwd: String?,
    val expireMs: Long,
) {
    // START_CONTRACT: isStale
    //   PURPOSE: Vendor staleness rule (Alert.d2): links are stale 10 s after expire_time.
    //   INPUTS: { nowMs: Long - wall clock ms }
    //   OUTPUTS: { Boolean - true when nowMs > expireMs + 10 s }
    //   SIDE_EFFECTS: none
    // END_CONTRACT: isStale
    fun isStale(nowMs: Long): Boolean = nowMs > expireMs + STALE_GRACE_MS

    override fun toString(): String =
        "MediaRef(hasPic=${picUrl != null}, hasClip=${videoUrl != null}, redacted)"

    private companion object {
        const val STALE_GRACE_MS = 10_000L
    }
}

/** One mapped response of v2/alert/list. */
data class AlertPage(
    val code: String,
    val success: Boolean,
    val records: List<AlertRecord>,
    val media: Map<String, MediaRef>,
    val oldestTimeMs: Long?,
    val rawCount: Int,
)
