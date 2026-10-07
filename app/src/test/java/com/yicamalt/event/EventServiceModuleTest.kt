// FILE: EventServiceModuleTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Verify M-EVENT against MockWebServer: paginated timeline parse, cache write,
//     empty-page invariant, playback resolution, and 401/5xx/auth-code mapping.
//   SCOPE: the four V-M-EVENT scenarios plus signing, redaction, and trace-marker assertions.
//   DEPENDS: M-EVENT, TestInfrastructure
//   LINKS: V-M-EVENT
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.event

import com.yicamalt.auth.AuthMethod
import com.yicamalt.auth.AuthSession
import com.yicamalt.auth.SessionSource
import com.yicamalt.database.EventDao
import com.yicamalt.database.EventEntity
import com.yicamalt.test.RedactionScanner
import com.yicamalt.test.TraceRecorder
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.Retrofit

/** In-memory EventDao fake (no Room) so plain-JVM tests can assert cache writes. */
private class FakeEventDao : EventDao() {
    val stored = mutableListOf<EventEntity>()
    override fun insertEventRaw(entity: EventEntity) {
        stored.removeAll { it.event_id == entity.event_id }
        stored += entity
    }

    override fun getByDevice(deviceId: String) = stored.filter { it.device_id == deviceId }
    override fun getByDeviceInWindow(deviceId: String, fromTime: Long, toTime: Long) =
        stored.filter { it.device_id == deviceId && it.timestamp in fromTime..toTime }
            .sortedByDescending { it.timestamp }

    override fun getById(eventId: String) = stored.find { it.event_id == eventId }
    override fun countByDevice(deviceId: String) = stored.count { it.device_id == deviceId }

    override fun clearOlderThan(cutoff: Long): Int {
        val before = stored.size
        stored.removeAll { it.timestamp < cutoff }
        return before - stored.size
    }

    override fun deleteAll() = stored.clear()
}

private class FakeEventSession(
    private val token: String = "t-1",
    private val secret: String = "s-1",
    private val uid: String = "u-1",
) : SessionSource {
    override fun currentSession(): AuthSession? =
        AuthSession(token, secret, Long.MAX_VALUE, uid, AuthMethod.PASSWORD)
}

private class NoEventSession : SessionSource {
    override fun currentSession(): AuthSession? = null
}

class EventServiceModuleTest {

    private val server = MockWebServer()
    private val recorder = TraceRecorder()
    private val redaction = RedactionScanner()
    private val dao = FakeEventDao()
    private lateinit var service: EventServiceModule

    /** Window must actually contain the fixture events (epoch millis ~1712345600000) and stay
     *  under 10 digits wide, because the module logs windowMs and RedactionScanner forbids
     *  10+ digit runs — a raw epoch-ms timestamp would trip it. */
    private val windowStart = 1_712_340_000_000L
    private val windowEnd = windowStart + 86_400_000L

    @BeforeEach
    fun setUp() {
        server.start()
        recorder.plant()
        service = EventServiceModule(newApi(), dao, FakeEventSession())
    }

    @AfterEach
    fun tearDown() {
        recorder.unplant()
        server.shutdown()
    }

    private fun newApi(): YiCloudEventApi = Retrofit.Builder()
        .baseUrl(server.url("/v1/").toString())
        .addConverterFactory(
            Json { ignoreUnknownKeys = true; isLenient = true }
                .asConverterFactory("application/json".toMediaType()),
        )
        .build()
        .create(YiCloudEventApi::class.java)

    private fun service(session: SessionSource) = EventServiceModule(newApi(), dao, session)

    // Timestamps here are epoch millis (13 digits) but never reach a log line.
    private fun timelineBody(vararg events: String, total: Int = events.size) =
        """{"code":"20000","data":{"eventList":[${events.joinToString(",")}],"total":$total}}"""

    private val motionEvent =
        """{"event_id":"ev-1","type":"motion","timestamp":1712345678000,""" +
            """"thumbnail_url":"https://thumb.invalid/ev-1.jpg","playback_url":"https://play.invalid/ev-1.mp4"}"""

    private val soundEvent =
        """{"event_id":"ev-2","type":"sound","timestamp":1712345600000,""" +
            """"thumbnail_url":"https://thumb.invalid/ev-2.jpg"}"""

    // ---- scenario_1: paginated event list returned with thumbnails ----

    @Test
    fun `scenario_1 paginated list parsed with thumbnails and paging hint`() {
        server.enqueue(MockResponse().setBody(timelineBody(motionEvent, soundEvent, total = 3)))
        val page = runBlocking {
            service.getEventTimeline("cam-1", windowStart, windowEnd, page = 1)
        }
        assertEquals(2, page.events.size)
        assertEquals(1, page.page)
        assertTrue(page.hasMore, "total=3 with 2 returned means another page exists")

        val first = page.events.first()
        assertEquals("ev-1", first.eventId)
        assertEquals("cam-1", first.deviceId)
        assertEquals("motion", first.type)
        assertEquals(1712345678000L, first.timestamp)
        assertEquals("https://thumb.invalid/ev-1.jpg", first.thumbnailUrl)
        assertEquals("https://play.invalid/ev-1.mp4", first.playbackUrl)

        val second = page.events[1]
        assertEquals("ev-2", second.eventId)
        assertEquals("sound", second.type)
        assertNull(second.playbackUrl, "event without a playback key must resolve to null, not a guess")
        recorder.assertMarkerAppeared("BLOCK_FETCH_EVENTS")
    }

    @Test
    fun `scenario_1b second page is requested and hasMore clears at the end`() {
        server.enqueue(MockResponse().setBody(timelineBody(motionEvent, soundEvent, total = 3)))
        server.enqueue(MockResponse().setBody(timelineBody(motionEvent, total = 3)))
        val first = runBlocking { service.getEventTimeline("cam-1", windowStart, windowEnd, page = 1) }
        val second = runBlocking { service.getEventTimeline("cam-1", windowStart, windowEnd, page = 2) }
        assertTrue(first.hasMore)
        assertEquals(2, second.page)
        assertTrue(!second.hasMore, "the page that consumed the total must not advertise more")

        val requested = (0 until server.requestCount).map { server.takeRequest().requestUrl!! }
        assertEquals("1", requested[0].queryParameter("page"))
        assertEquals("2", requested[1].queryParameter("page"))
    }

    @Test
    fun `scenario_1c hasMore derives from a full page when no total is reported`() {
        val full = (1..EventServiceModule.PAGE_SIZE).joinToString(",") { i ->
            """{"event_id":"ev-$i","type":"motion","timestamp":${1712345678000L + i}}"""
        }
        server.enqueue(MockResponse().setBody("""{"code":"20000","data":{"eventList":[$full]}}"""))
        val page = runBlocking { service.getEventTimeline("cam-1", windowStart, windowEnd, page = 1) }
        assertEquals(EventServiceModule.PAGE_SIZE, page.events.size)
        assertTrue(page.hasMore, "a completely full page with no total implies more data")
    }

    @Test
    fun `scenario_1d request carries the signed parameter shape and the window`() {
        server.enqueue(MockResponse().setBody(timelineBody(motionEvent)))
        runBlocking { service.getEventTimeline("cam-1", windowStart, windowEnd, page = 2) }
        val q = server.takeRequest().requestUrl!!
        assertEquals("1", q.queryParameter("seq"))
        assertEquals("u-1", q.queryParameter("userid"))
        assertEquals("cam-1", q.queryParameter("device_id"))
        assertEquals(windowStart.toString(), q.queryParameter("start_time"))
        assertEquals(windowEnd.toString(), q.queryParameter("end_time"))
        assertEquals("2", q.queryParameter("page"))
        assertEquals(EventServiceModule.PAGE_SIZE.toString(), q.queryParameter("page_size"))
        val mac = javax.crypto.Mac.getInstance("HmacSHA1")
        mac.init(javax.crypto.spec.SecretKeySpec("t-1&s-1".toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val expected = java.util.Base64.getEncoder()
            .encodeToString(mac.doFinal("seq=1&userid=u-1".toByteArray(Charsets.UTF_8)))
        assertEquals(expected, q.queryParameter("hmac"))
    }

    @Test
    fun `scenario_1e event objects are accepted under alternative key names`() {
        server.enqueue(
            MockResponse().setBody(
                """{"code":"20000","data":{"list":[{"id":9001,"alarm_type":"pir","start_time":1712345678,"cover":"https://c.invalid/a.jpg"}]}}""",
            ),
        )
        val page = runBlocking { service.getEventTimeline("cam-1", windowStart, windowEnd, page = 1) }
        assertEquals(1, page.events.size)
        val e = page.events.first()
        assertEquals("9001", e.eventId)
        assertEquals("pir", e.type)
        assertEquals(1712345678000L, e.timestamp, "epoch seconds must be widened to millis")
        assertEquals("https://c.invalid/a.jpg", e.thumbnailUrl)
    }

    // ---- scenario_2: events synced to Room ----

    @Test
    fun `scenario_2 events synced to the cache and readable offline`() {
        server.enqueue(MockResponse().setBody(timelineBody(motionEvent, soundEvent)))
        runBlocking { service.getEventTimeline("cam-1", windowStart, windowEnd, page = 1) }

        assertEquals(2, dao.countByDevice("cam-1"))
        assertEquals(2, runBlocking { service.getLocalEventCount("cam-1") })
        recorder.assertMarkerAppeared("BLOCK_SYNC_TO_DB")
        recorder.assertMarkerAppeared("BLOCK_DB_WRITE")

        val cached = runBlocking { service.getEventById("ev-1") }
        assertEquals("ev-1", cached.eventId)
        assertEquals("motion", cached.type)
        assertEquals(1712345678000L, cached.timestamp)
        assertEquals("https://play.invalid/ev-1.mp4", cached.playbackUrl)

        val local = runBlocking { service.getLocalTimeline("cam-1", windowStart, windowEnd) }
        assertEquals(listOf("ev-1", "ev-2"), local.map { it.eventId }, "newest first")
    }

    @Test
    fun `scenario_2b local timeline honours the requested window`() {
        server.enqueue(MockResponse().setBody(timelineBody(motionEvent, soundEvent)))
        runBlocking { service.getEventTimeline("cam-1", windowStart, windowEnd, page = 1) }
        val inWindow = runBlocking { service.getLocalTimeline("cam-1", windowStart, windowEnd) }
        assertEquals(2, inWindow.size)
        // A window that brackets only the newer event must exclude the older one.
        val narrowed = runBlocking {
            service.getLocalTimeline("cam-1", 1_712_345_677_999L, 1_712_345_678_001L)
        }
        assertEquals(listOf("ev-1"), narrowed.map { it.eventId }, "an event outside the window must be excluded")
    }

    @Test
    fun `scenario_2c cache is refreshed idempotently across overlapping pages`() {
        server.enqueue(MockResponse().setBody(timelineBody(motionEvent, soundEvent, total = 3)))
        runBlocking { service.getEventTimeline("cam-1", windowStart, windowEnd, page = 1) }
        server.enqueue(MockResponse().setBody(timelineBody(motionEvent, soundEvent, total = 3)))
        runBlocking { service.getEventTimeline("cam-1", windowStart, windowEnd, page = 1) }
        assertEquals(2, dao.countByDevice("cam-1"), "re-fetching a page must not duplicate rows")
    }

    // ---- scenario_3: playback URL resolved ----

    @Test
    fun `scenario_3 cached playback url is served without a network call`() {
        server.enqueue(MockResponse().setBody(timelineBody(motionEvent)))
        runBlocking { service.getEventTimeline("cam-1", windowStart, windowEnd, page = 1) }
        val afterFetch = server.requestCount
        val url = runBlocking { service.getPlaybackUrl("ev-1") }
        assertEquals("https://play.invalid/ev-1.mp4", url)
        assertEquals(afterFetch, server.requestCount, "a cached URL must not trigger another request")
        recorder.assertMarkerAppeared("BLOCK_RESOLVE_PLAYBACK_URL")
    }

    @Test
    fun `scenario_3b unresolved playback url is fetched remotely and then cached`() {
        server.enqueue(MockResponse().setBody(timelineBody(soundEvent)))
        runBlocking { service.getEventTimeline("cam-1", windowStart, windowEnd, page = 1) }
        assertNull(dao.getById("ev-2")!!.payload_json.takeIf { it.contains("playback_url") })

        server.enqueue(
            MockResponse().setBody(
                """{"code":"20000","data":{"event_id":"ev-2","playback_url":"rtsp://resolved.invalid/ev-2"}}""",
            ),
        )
        val url = runBlocking { service.getPlaybackUrl("ev-2") }
        assertEquals("rtsp://resolved.invalid/ev-2", url)
        assertTrue(dao.getById("ev-2")!!.payload_json.contains("playback_url"), "resolved URL must be persisted")

        val afterResolve = server.requestCount
        val second = runBlocking { service.getPlaybackUrl("ev-2") }
        assertEquals(url, second)
        assertEquals(afterResolve, server.requestCount, "the second call must be a pure cache hit")
    }

    // ---- scenario_4: no recordings returns empty list, not an error ----

    @Test
    fun `scenario_4 no recordings returns an empty page and never touches the cache`() {
        server.enqueue(MockResponse().setBody("""{"code":"20000","data":{"eventList":[],"total":0}}"""))
        val page = runBlocking { service.getEventTimeline("cam-1", windowStart, windowEnd, page = 1) }
        assertTrue(page.events.isEmpty())
        assertTrue(!page.hasMore)
        assertEquals(0, dao.countByDevice("cam-1"))
        recorder.assertMarkerAppeared("BLOCK_FETCH_EVENTS")
        recorder.assertMarkerAbsent("BLOCK_SYNC_TO_DB")
        recorder.assertMarkerAbsent("BLOCK_DB_WRITE")
    }

    @Test
    fun `scenario_4b null data is also an empty page, not an error`() {
        server.enqueue(MockResponse().setBody("""{"code":"20000"}"""))
        val page = runBlocking { service.getEventTimeline("cam-1", windowStart, windowEnd, page = 1) }
        assertTrue(page.events.isEmpty())
        recorder.assertMarkerAbsent("BLOCK_SYNC_TO_DB")
    }

    @Test
    fun `scenario_4c empty result does not evict previously cached events`() {
        server.enqueue(MockResponse().setBody(timelineBody(motionEvent)))
        runBlocking { service.getEventTimeline("cam-1", windowStart, windowEnd, page = 1) }
        assertEquals(1, dao.countByDevice("cam-1"))
        server.enqueue(MockResponse().setBody("""{"code":"20000","data":{"eventList":[],"total":0}}"""))
        runBlocking { service.getEventTimeline("cam-1", windowStart, windowEnd, page = 2) }
        assertEquals(1, dao.countByDevice("cam-1"), "an empty later page must not wipe the cache")
    }

    @Test
    fun `scenario_4d event without a recording maps to NoRecording`() {
        server.enqueue(MockResponse().setBody(timelineBody(soundEvent)))
        runBlocking { service.getEventTimeline("cam-1", windowStart, windowEnd, page = 1) }
        server.enqueue(MockResponse().setBody("""{"code":"20000","data":{"event_id":"ev-2"}}"""))
        val err = assertThrows(EventError.NoRecording::class.java) {
            runBlocking { service.getPlaybackUrl("ev-2") }
        }
        assertTrue(err.message!!.contains("EVENT_NO_RECORDING"))
    }

    // ---- failure paths ----

    @Test
    fun `scenario_5 401 maps to Unauthorized`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        val err = assertThrows(EventError.Unauthorized::class.java) {
            runBlocking { service.getEventTimeline("cam-1", windowStart, windowEnd, page = 1) }
        }
        assertTrue(err.message!!.contains("EVENT_UNAUTHORIZED"))
    }

    @Test
    fun `scenario_5b app-level auth code maps to Unauthorized`() {
        server.enqueue(MockResponse().setBody("""{"code":"20201"}"""))
        assertThrows(EventError.Unauthorized::class.java) {
            runBlocking { service.getEventTimeline("cam-1", windowStart, windowEnd, page = 1) }
        }
    }

    @Test
    fun `scenario_5c 5xx maps to FetchFailed`() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("oops"))
        val err = assertThrows(EventError.FetchFailed::class.java) {
            runBlocking { service.getEventTimeline("cam-1", windowStart, windowEnd, page = 1) }
        }
        assertTrue(err.message!!.contains("EVENT_FETCH_FAILED"))
    }

    @Test
    fun `scenario_5d unknown success code maps to FetchFailed`() {
        server.enqueue(MockResponse().setBody("""{"code":"99999"}"""))
        assertThrows(EventError.FetchFailed::class.java) {
            runBlocking { service.getEventTimeline("cam-1", windowStart, windowEnd, page = 1) }
        }
    }

    @Test
    fun `scenario_5e missing session maps to Unauthorized before any request`() {
        val err = assertThrows(EventError.Unauthorized::class.java) {
            runBlocking { service(NoEventSession()).getEventTimeline("cam-1", windowStart, windowEnd, 1) }
        }
        assertTrue(err.message!!.contains("EVENT_UNAUTHORIZED"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `scenario_5f unknown event id maps to NotFound`() {
        assertThrows(EventError.NotFound::class.java) {
            runBlocking { service.getEventById("missing") }
        }
        assertThrows(EventError.NotFound::class.java) {
            runBlocking { service.getPlaybackUrl("missing") }
        }
    }

    @Test
    fun `scenario_5g page numbers below one are coerced instead of rejected`() {
        server.enqueue(MockResponse().setBody(timelineBody(motionEvent)))
        val page = runBlocking { service.getEventTimeline("cam-1", windowStart, windowEnd, page = 0) }
        assertEquals(1, page.page)
        assertEquals("1", server.takeRequest().requestUrl!!.queryParameter("page"))
    }

    // ---- redaction ----

    @Test
    fun `scenario_6 redaction keeps ids and urls out of the logs`() {
        server.enqueue(MockResponse().setBody(timelineBody(motionEvent)))
        runBlocking { service.getEventTimeline("cam-1", windowStart, windowEnd, page = 1) }
        server.enqueue(MockResponse().setBody(timelineBody(soundEvent)))
        runBlocking { service.getEventTimeline("cam-1", windowStart, windowEnd, page = 2) }
        redaction.assertClean(
            recorder.bufferText(),
            extraLiterals = listOf("ev-1", "ev-2", "cam-1", "t-1", "s-1"),
        )
    }

    @Test
    fun `scenario_7 timeline fetch emits the fetch then the cache-write markers in order`() {
        server.enqueue(MockResponse().setBody(timelineBody(motionEvent)))
        runBlocking { service.getEventTimeline("cam-1", windowStart, windowEnd, page = 1) }
        // BLOCK_DB_WRITE is emitted per row by EventDao.insertEvent, before the sync summary.
        recorder.assertSequence("BLOCK_FETCH_EVENTS", "BLOCK_DB_WRITE", "BLOCK_SYNC_TO_DB")
    }
}
