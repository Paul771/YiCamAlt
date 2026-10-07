// FILE: EventServiceModule.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Fetch, cache, and serve the Yi camera event timeline; resolve event playback URLs.
//   SCOPE: getEventTimeline (paged cloud fetch + Room cache), getEventById, getLocalTimeline,
//     getPlaybackUrl, syncEventsToDb, getLocalEventCount; emits BLOCK_FETCH_EVENTS /
//     BLOCK_SYNC_TO_DB / BLOCK_RESOLVE_PLAYBACK_URL trace markers.
//   DEPENDS: M-HTTP (YiCloudEventApi), M-AUTH (SessionSource), M-LOCAL-DB (EventDao)
//   LINKS: M-EVENT, M-UI-EVENTS, M-EVENT-PLAYBACK
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.event

import com.yicamalt.auth.SessionSource
import com.yicamalt.database.EventDao
import com.yicamalt.database.EventEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import retrofit2.HttpException

// START_MODULE_MAP
//   EventServiceModule - facade over the cloud event API + Room cache.
//   EventError - typed errors (FetchFailed, NoRecording, Unauthorized, NotFound).
//   EventTimelinePort - thin read contract for the events UI so it needs no network/DAO.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - Created for Phase-3 M-EVENT against the planned contract exports.
//     DEVIATION (added exports): getLocalTimeline and EventTimelinePort were added because the
//     contract's syncEventsToDb / getLocalEventCount are otherwise write-only — a cache with no
//     read path cannot serve the offline timeline the module purpose promises.
//     DEVIATION (added errors): Unauthorized and NotFound were added alongside the planned
//     EVENT_FETCH_FAILED / EVENT_NO_RECORDING, because the request is hmac-signed and therefore
//     has an unauthenticated branch, and single-event lookups need a miss case.
//     INVARIANT: an empty timeline must NOT reach the cache — V-M-EVENT asserts that an empty
//     result emits no BLOCK_SYNC_TO_DB marker. This also protects the cache from being wiped by
//     a transient empty page during pagination.
//   HAZARD: no log line in this module carries device_id or event_id — Yi serial numbers are
//     10+ digit runs and would trip RedactionScanner's long-digit-run rule.
// END_CHANGE_SUMMARY

sealed class EventError(message: String) : Error(message) {
    object FetchFailed : EventError("EVENT_FETCH_FAILED: cloud event timeline unavailable")
    object NoRecording : EventError("EVENT_NO_RECORDING: no recording available for this event")
    object Unauthorized : EventError("EVENT_UNAUTHORIZED: session rejected by event API")
    object NotFound : EventError("EVENT_NOT_FOUND: no such event id")
}

/** Read contract consumed by the events UI; keeps the view-model free of network/DAO types. */
interface EventTimelinePort {
    suspend fun getEventTimeline(
        deviceId: String,
        startTime: Long = 0L,
        endTime: Long = 0L,
        page: Int = 1,
    ): EventPage
}

@Singleton
class EventServiceModule @Inject constructor(
    private val api: YiCloudEventApi,
    private val eventDao: EventDao,
    private val session: SessionSource,
) : EventTimelinePort {

    /** Fetch one page of the cloud event timeline and refresh the Room cache. */
    override suspend fun getEventTimeline(
        deviceId: String,
        startTime: Long,
        endTime: Long,
        page: Int,
    ): EventPage = withContext(Dispatchers.IO) {
        // START_BLOCK_FETCH_EVENTS
        val safePage = page.coerceAtLeast(1)
        val now = System.currentTimeMillis()
        val to = if (endTime > 0L) endTime else now
        val from = if (startTime > 0L) startTime else to - DEFAULT_WINDOW_MS
        Timber.d("[Event][getEventTimeline][BLOCK_FETCH_EVENTS] page=$safePage windowMs=${to - from}")

        val s = session.currentSession() ?: throw EventError.Unauthorized
        val hmac = EventSigner.hmacSha1Base64(
            key = s.accessToken + "&" + s.refreshToken,
            msg = EventSigner.canonicalMessage(s.userId),
        )
        val envelope = try {
            api.eventList(
                userId = s.userId,
                hmac = hmac,
                deviceId = deviceId,
                startTime = from,
                endTime = to,
                page = safePage,
                pageSize = PAGE_SIZE,
            )
        } catch (e: HttpException) {
            if (e.code() == 401) throw EventError.Unauthorized
            throw EventError.FetchFailed
        } catch (e: IOException) {
            throw EventError.FetchFailed
        }
        if (envelope.code in AUTH_ERROR_CODES) throw EventError.Unauthorized
        if (envelope.code !in SUCCESS_CODES) throw EventError.FetchFailed

        val parsed = EventTimelineParser.extract(envelope.data, deviceId)
        val hasMore = resolveHasMore(parsed, safePage)
        Timber.d(
            "[Event][getEventTimeline][BLOCK_FETCH_EVENTS] page=$safePage count=${parsed.events.size} " +
                "hasMore=$hasMore code=${envelope.code}",
        )

        // V-M-EVENT invariant: an empty page must not touch the cache.
        if (parsed.events.isNotEmpty()) syncToDb(deviceId, parsed.events)
        EventPage(parsed.events, safePage, hasMore)
        // END_BLOCK_FETCH_EVENTS
    }

    /**
     * `total` is a grand total, so continuation depends on the page offset — a page-2 response
     * with 1 of 3 items still has more. Falls back to "this page came back full" when the
     * envelope reports neither a flag nor a total.
     */
    private fun resolveHasMore(parsed: ParsedTimeline, page: Int): Boolean {
        parsed.explicitHasMore?.let { return it }
        parsed.reportedTotal?.let { total ->
            val consumed = (page - 1) * PAGE_SIZE + parsed.events.size
            return consumed < total
        }
        return parsed.events.size >= PAGE_SIZE
    }

    /** Look up a single event, cache first, so playback works offline. */
    suspend fun getEventById(eventId: String): EventInfo = withContext(Dispatchers.IO) {
        val row = eventDao.getById(eventId) ?: throw EventError.NotFound
        EventTimelineParser.extractOne(row.payload_json, row.device_id) ?: throw EventError.NotFound
    }

    /** Read the cached timeline for a device/window without touching the network. */
    suspend fun getLocalTimeline(deviceId: String, startTime: Long, endTime: Long): List<EventInfo> =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val to = if (endTime > 0L) endTime else now
            val from = if (startTime > 0L) startTime else to - DEFAULT_WINDOW_MS
            eventDao.getByDeviceInWindow(deviceId, from, to).mapNotNull { row ->
                EventTimelineParser.extractOne(row.payload_json, row.device_id)
            }
        }

    /** Resolve a playback URL for an event, reusing the cached one when already known. */
    suspend fun getPlaybackUrl(eventId: String): String = withContext(Dispatchers.IO) {
        // START_BLOCK_RESOLVE_PLAYBACK_URL
        val row = eventDao.getById(eventId) ?: throw EventError.NotFound
        val cached = EventTimelineParser.extractOne(row.payload_json, row.device_id)
        cached?.playbackUrl?.takeIf { it.isNotBlank() }?.let { url ->
            Timber.d("[Event][resolveUrl][BLOCK_RESOLVE_PLAYBACK_URL] source=cache")
            return@withContext url
        }

        val s = session.currentSession() ?: throw EventError.Unauthorized
        val hmac = EventSigner.hmacSha1Base64(
            key = s.accessToken + "&" + s.refreshToken,
            msg = EventSigner.canonicalMessage(s.userId),
        )
        val envelope = try {
            api.playbackUrl(userId = s.userId, hmac = hmac, eventId = eventId)
        } catch (e: HttpException) {
            if (e.code() == 401) throw EventError.Unauthorized
            throw EventError.FetchFailed
        } catch (e: IOException) {
            throw EventError.FetchFailed
        }
        if (envelope.code in AUTH_ERROR_CODES) throw EventError.Unauthorized
        if (envelope.code !in SUCCESS_CODES) throw EventError.FetchFailed

        val resolved = EventTimelineParser.extractOne(
            envelope.data?.let { Json.encodeToString(JsonElement.serializer(), it) },
            row.device_id,
        )?.playbackUrl?.takeIf { it.isNotBlank() }
            ?: throw EventError.NoRecording

        // Persist the resolved URL so the next open is a pure cache hit.
        eventDao.insertEvent(row.copy(payload_json = withPlaybackUrl(row.payload_json, resolved)))
        Timber.d("[Event][resolveUrl][BLOCK_RESOLVE_PLAYBACK_URL] source=remote cached=true")
        resolved
        // END_BLOCK_RESOLVE_PLAYBACK_URL
    }

    /** Write events to the Room cache. Public so a push-driven sync can reuse the same path. */
    suspend fun syncEventsToDb(deviceId: String, events: List<EventInfo>) = withContext(Dispatchers.IO) {
        syncToDb(deviceId, events)
    }

    /** Count cached events for a device, so the UI can advertise offline availability. */
    suspend fun getLocalEventCount(deviceId: String): Int = withContext(Dispatchers.IO) {
        eventDao.countByDevice(deviceId)
    }

    // START_BLOCK_SYNC_TO_DB
    private suspend fun syncToDb(deviceId: String, events: List<EventInfo>) {
        if (events.isEmpty()) return
        val now = System.currentTimeMillis()
        val written = events.count { event ->
            runCatching {
                eventDao.insertEvent(
                    EventEntity(
                        event_id = event.eventId,
                        device_id = if (event.deviceId.isNotBlank()) event.deviceId else deviceId,
                        type = event.type,
                        timestamp = event.timestamp,
                        payload_json = event.toPayloadJson(),
                        cached_at = now,
                    ),
                )
            }.isSuccess
        }
        Timber.d("[Event][syncToDb][BLOCK_SYNC_TO_DB] written=$written of=${events.size}")
    }
    // END_BLOCK_SYNC_TO_DB

    /** Lossless round-trip: the raw event object is what gets cached and re-parsed on read. */
    private fun EventInfo.toPayloadJson(): String = buildString {
        append('{')
        append("\"event_id\":").append(quote(eventId))
        append(",\"device_id\":").append(quote(deviceId))
        append(",\"type\":").append(quote(type))
        append(",\"timestamp\":").append(timestamp)
        thumbnailUrl?.let { append(",\"thumbnail_url\":").append(quote(it)) }
        playbackUrl?.let { append(",\"playback_url\":").append(quote(it)) }
        append('}')
    }

    private fun withPlaybackUrl(payloadJson: String, url: String): String {
        val element = runCatching { Json.parseToJsonElement(payloadJson) }.getOrNull()
        val obj = element as? JsonObject ?: return payloadJson
        return Json.encodeToString(JsonElement.serializer(), JsonObject(obj + ("playback_url" to JsonPrimitive(url))))
    }

    private fun quote(value: String): String = Json.encodeToString(String.serializer(), value)

    companion object {
        const val PAGE_SIZE = 20
        const val DEFAULT_WINDOW_MS = 24L * 60L * 60L * 1000L
        internal val AUTH_ERROR_CODES = setOf("20201", "20202", "20203", "20205", "20253", "40110")
        internal val SUCCESS_CODES = setOf("20000", "20200")
    }
}
