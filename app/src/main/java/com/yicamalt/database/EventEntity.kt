// FILE: EventEntity.kt
// VERSION: 0.2.0
// START_MODULE_CONTRACT
//   PURPOSE: Room entity caching one alert of the v2/alert/list feed for offline browsing (EVT-05).
//   SCOPE: event_key (PK: alert id or uid_time), device_id, time_ms, type, sub_type, file_type, has_clip,
//     play_time_s, thumb_path (relative to filesDir), cached_at. Value-free: no URL, no key.
//   DEPENDS: M-LOCAL-DB
//   LINKS: M-EVENT, M-UI-EVENTS, EVT-05
//   ROLE: DATA
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// START_MODULE_MAP
//   EventEntity - cached alert row keyed by event_key, indexed by time_ms and (device_id, time_ms).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.2.0 - GSD plan 05-02: v3 re-keyed for v2/alert/list (event_key, time_ms in ms, type /
//     sub_type ints, file_type, has_clip, play_time_s, thumb_path); the old raw JSON payload column is gone.
//   HAZARD: never add a column for media URLs or decrypt keys (SEC-04 analogue). Signed links and keys live
//     only in the in-memory MediaRefStore. play_time_s is 0 on real data (05-PROBE), kept for later use.
// END_CHANGE_SUMMARY

@Entity(
    tableName = "events",
    indices = [
        Index(value = ["time_ms"]),
        Index(value = ["device_id", "time_ms"]),
    ],
)
data class EventEntity(
    @PrimaryKey @ColumnInfo(name = "event_key") val eventKey: String,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "time_ms") val timeMs: Long,
    @ColumnInfo(name = "type") val type: Int,
    @ColumnInfo(name = "sub_type") val subType: Int,
    @ColumnInfo(name = "file_type") val fileType: String,
    @ColumnInfo(name = "has_clip") val hasClip: Boolean,
    @ColumnInfo(name = "play_time_s") val playTimeS: Int,
    @ColumnInfo(name = "thumb_path") val thumbPath: String?,
    @ColumnInfo(name = "cached_at") val cachedAt: Long,
) {
    override fun toString(): String =
        "EventEntity(type=$type, subType=$subType, fileType=$fileType, hasClip=$hasClip, hasThumb=${thumbPath != null})"
}
