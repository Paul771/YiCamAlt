// FILE: EventInfo.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Domain model for a Yi camera event plus a shape-tolerant JSON parser for the
//     cloud event timeline and single-event playback payloads.
//   SCOPE: immutable EventInfo/EventPage values; EventTimelineParser maps arbitrary event
//     envelopes to EventInfo and normalises timestamps (seconds or millis) to epoch millis.
//   DEPENDS: none
//   LINKS: M-EVENT, M-LOCAL-DB, M-UI-EVENTS
//   ROLE: TYPES
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.event

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// START_MODULE_MAP
//   EventInfo - eventId/deviceId/type/timestamp/thumbnailUrl/playbackUrl value object.
//   EventPage - one page of events plus the hasMore continuation flag.
//   ParsedTimeline - parser output: events, reportedTotal, hasMore.
//   EventTimelineParser - tolerant extractor for event arrays and single-event payloads.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - Created for Phase-3 M-EVENT. Field mapping is PROVISIONAL: the real
//     cloud event endpoint has never been captured (see development-plan M-EVENT note-1), so the
//     parser enumerates candidate keys exactly like CameraListParser does for M-CAMERA-LIST.
//     A future live capture should only require adding keys, not restructuring.
//   HAZARD: device_id is intentionally absent from all log lines in this module — Yi serial
//     numbers are 10+ digit runs and would trip RedactionScanner's long-digit-run rule.
// END_CHANGE_SUMMARY

data class EventInfo(
    val eventId: String,
    val deviceId: String,
    val type: String,
    val timestamp: Long,
    val thumbnailUrl: String?,
    val playbackUrl: String?,
)

/** One page of the event timeline. [hasMore] drives the "load more" affordance in M-UI-EVENTS. */
data class EventPage(
    val events: List<EventInfo>,
    val page: Int,
    val hasMore: Boolean,
)

internal data class ParsedTimeline(
    val events: List<EventInfo>,
    val reportedTotal: Int?,
    /** True/false when the envelope states it outright; null when it must be inferred. */
    val explicitHasMore: Boolean?,
)

/**
 * Shape-tolerant event parser. Accepts `data` as a bare array of event objects OR an object
 * wrapping one (keys eventList/event_list/list/items/data/records/...).
 *
 * Tolerances baked in here because the endpoint is uncaptured:
 *  - event id under any of [ID_KEYS]; entries without a usable id are dropped, not guessed;
 *  - timestamps accepted as seconds OR millis and normalised to millis;
 *  - an explicit has-more flag is surfaced as-is; otherwise the caller infers it, because
 *    `total` is a grand total and only the caller knows the page offset.
 */
internal object EventTimelineParser {

    private val ID_KEYS = listOf(
        "event_id", "eventId", "evId", "ev_id", "alarm_id", "vid", "id",
    )
    private val TYPE_KEYS = listOf(
        "type", "event_type", "eventType", "alarm_type", "event_name", "name",
    )
    private val TIME_KEYS = listOf(
        "timestamp", "ts", "time", "start_time", "startTime", "beginTime", "begin_time", "createTime",
    )
    private val THUMB_KEYS = listOf(
        "thumbnail", "thumbnail_url", "thumbnailUrl", "thumb", "thumb_url", "cover",
        "cover_url", "snapshot", "image", "img", "pic",
    )
    private val PLAYBACK_KEYS = listOf(
        "playback_url", "playbackUrl", "play_url", "playUrl", "record_url", "recordUrl",
        "video_url", "videoUrl", "rtsp", "rtsp_url", "url",
    )
    private val LIST_KEYS = listOf(
        "eventList", "event_list", "events", "list", "items", "records", "data", "result",
    )
    private val TOTAL_KEYS = listOf("total", "totalCount", "total_count", "count", "totalNum")
    private val PAGE_SIZE_KEYS = listOf("pageSize", "page_size", "limit", "size", "pageCount", "page_count")
    private val HAS_MORE_KEYS = listOf("hasMore", "has_more", "hasNext", "has_next", "more")

    /** Values below this bound are treated as epoch seconds rather than millis. */
    private const val MILLIS_FLOOR = 100_000_000_000L

    fun extract(element: JsonElement?, fallbackDeviceId: String): ParsedTimeline {
        if (element == null) return ParsedTimeline(emptyList(), null, null)
        val objects = collectEventObjects(element, depth = 0)
        val events = objects.mapNotNull { obj -> toInfo(obj, fallbackDeviceId) }
        val container = element as? JsonObject
        return ParsedTimeline(events, container?.let { firstInt(it, TOTAL_KEYS) }, explicitHasMore(container))
    }

    /** Re-parse one cached event payload. Returns null when the payload is unusable. */
    fun extractOne(payloadJson: String?, fallbackDeviceId: String): EventInfo? {
        if (payloadJson.isNullOrBlank()) return null
        val element = runCatching { Json.parseToJsonElement(payloadJson) }.getOrNull() ?: return null
        // A single-event payload is a bare event object, so try the root before walking for a list.
        (element as? JsonObject)?.let { root ->
            toInfo(root, fallbackDeviceId)?.let { return it }
        }
        return collectEventObjects(element, depth = 0).firstNotNullOfOrNull { toInfo(it, fallbackDeviceId) }
    }

    private fun toInfo(obj: JsonObject, fallbackDeviceId: String): EventInfo? {
        val id = firstString(obj, ID_KEYS)?.takeIf { it.isNotBlank() } ?: return null
        return EventInfo(
            eventId = id,
            deviceId = firstString(obj, listOf("device_id", "deviceId", "sn", "did"))
                ?.takeIf { it.isNotBlank() } ?: fallbackDeviceId,
            type = firstString(obj, TYPE_KEYS)?.takeIf { it.isNotBlank() } ?: DEFAULT_TYPE,
            timestamp = firstTimestamp(obj),
            thumbnailUrl = firstString(obj, THUMB_KEYS),
            playbackUrl = firstString(obj, PLAYBACK_KEYS),
        )
    }

    private fun collectEventObjects(element: JsonElement, depth: Int): List<JsonObject> {
        if (depth > 3) return emptyList()
        return when (element) {
            is JsonObject -> {
                val byKey = LIST_KEYS.firstNotNullOfOrNull { k ->
                    (element[k] as? JsonArray)?.takeIf { it.isNotEmpty() }
                }
                if (byKey != null) return byKey.mapNotNull { it as? JsonObject }
                val anyArray = element.values.firstOrNull { it is JsonArray } as? JsonArray
                if (anyArray != null) return anyArray.mapNotNull { it as? JsonObject }
                element.values.flatMap { collectEventObjects(it, depth + 1) }
            }
            is JsonArray -> element.mapNotNull { it as? JsonObject }
            else -> emptyList()
        }
    }

    /** True/false when the envelope carries a has-more flag; null when it does not. */
    private fun explicitHasMore(container: JsonObject?): Boolean? {
        val obj = container ?: return null
        for (k in HAS_MORE_KEYS) {
            val v = obj[k] as? JsonPrimitive ?: continue
            if (v is JsonNull) continue
            when (v.content.trim().lowercase()) {
                "1", "true", "yes" -> return true
                "0", "false", "no" -> return false
            }
        }
        return null
    }

    private fun firstString(obj: JsonObject, keys: List<String>): String? {
        for (k in keys) {
            val v = obj[k] as? JsonPrimitive ?: continue
            if (v is JsonNull || !v.isString) continue
            val s = v.content.trim()
            if (s.isNotEmpty()) return s
        }
        // Non-string primitives (numeric ids, numeric urls) are accepted as text.
        for (k in keys) {
            val v = obj[k] as? JsonPrimitive ?: continue
            if (v is JsonNull || v.isString) continue
            val s = v.content.trim()
            if (s.isNotEmpty()) return s
        }
        return null
    }

    private fun firstInt(obj: JsonObject, keys: List<String>): Int? {
        for (k in keys) {
            val v = obj[k] as? JsonPrimitive ?: continue
            if (v is JsonNull) continue
            v.content.trim().toIntOrNull()?.let { return it }
        }
        return null
    }

    /** Epoch seconds are widened to millis; anything unparseable becomes 0 rather than throwing. */
    private fun firstTimestamp(obj: JsonObject): Long {
        for (k in TIME_KEYS) {
            val v = obj[k] as? JsonPrimitive ?: continue
            if (v is JsonNull) continue
            val raw = v.content.trim()
            val numeric = raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong() ?: continue
            return if (numeric in 1 until MILLIS_FLOOR) numeric * 1000L else numeric
        }
        return 0L
    }

    private const val DEFAULT_TYPE = "unknown"
}
