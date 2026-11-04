// FILE: AlertSyncServiceTest.kt
// VERSION: 0.2.0
// START_MODULE_CONTRACT
//   PURPOSE: Verify AlertSyncService against a recording fake AlertListApi and an in-memory Room: persisted
//     5-minute gate, signed backward paging with the 10-page cap and gap resume, de-duplication, 30-day purge,
//     typed errors without retry, memory-only media refs, plus minus 5 s media refresh with throttles, logout race,
//     preview prefetch pass outside the Mutex, preview file purge and the logout file wipe.
//   SCOPE: V-M-EVENT sync scenarios; synthetic values only; Robolectric (Room).
//   DEPENDS: M-EVENT (AlertSyncService, EventCacheCleaner), TestInfrastructure (InMemoryRoom, TraceRecorder,
//     RedactionScanner)
//   LINKS: V-M-EVENT, EVT-01, EVT-05, EVT-06
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.event

// START_MODULE_MAP
//   FakeAlertApi - AlertListApi fake: records every call, answers from a queue of handlers (envelope or throw).
//   MutableSession - SessionSource fake whose session can be removed mid-test.
//   rec / page / fullPage - synthetic alert JSON builders (fake media host, fake pwd).
//   RecordingPrefetcher - ThumbPrefetcher fake: records batches, can suspend on a gate or throw.
//   AlertSyncServiceTest - gate, paging, errors, purge, media refs, refreshMedia, logout race, log redaction,
//     prefetch pass, file purge and wipe.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.2.0 - GSD plan 05-03: prefetch / purge / wipe scenarios; service gets a fake prefetcher,
//     EventMediaFiles over temp folders and an Unconfined application scope.
//   PREV_CHANGE: v0.1.0 - GSD plan 05-02: created (RED first).
// END_CHANGE_SUMMARY

import com.yicamalt.auth.AuthMethod
import com.yicamalt.auth.AuthSession
import com.yicamalt.auth.SessionSource
import com.yicamalt.database.AppMetaEntity
import com.yicamalt.database.EventEntity
import com.yicamalt.database.YiDatabase
import com.yicamalt.network.YiRequestSigner
import com.yicamalt.test.InMemoryRoom
import com.yicamalt.test.RedactionScanner
import com.yicamalt.test.TraceRecorder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.HttpException
import retrofit2.Response
import java.io.File
import java.io.IOException
import java.time.ZoneId

internal data class AlertCall(
    val seq: String,
    val userId: String,
    val type: String,
    val subType: String,
    val fromSec: Long,
    val toSec: Long,
    val limit: Int,
    val fromDb: String,
    val expires: String,
    val hmac: String,
)

internal class FakeAlertApi : AlertListApi {
    val calls = mutableListOf<AlertCall>()
    val handlers = ArrayDeque<suspend (AlertCall) -> AlertEnvelope>()

    fun enqueue(envelope: AlertEnvelope) { handlers.addLast { envelope } }
    fun enqueue(handler: suspend (AlertCall) -> AlertEnvelope) { handlers.addLast(handler) }

    override suspend fun alertList(
        seq: String, userId: String, type: String, subType: String, fromSec: Long, toSec: Long,
        limit: Int, fromDb: String, expires: String, hmac: String,
    ): AlertEnvelope {
        val call = AlertCall(seq, userId, type, subType, fromSec, toSec, limit, fromDb, expires, hmac)
        calls += call
        val handler = handlers.removeFirstOrNull() ?: throw IOException("SYNTH no scripted response")
        return handler(call)
    }
}

internal class MutableSession(var session: AuthSession?) : SessionSource {
    override fun currentSession(): AuthSession? = session
}

internal class RecordingPrefetcher : ThumbPrefetcher {
    val batches = mutableListOf<List<AlertRecord>>()
    var gate: CompletableDeferred<Unit>? = null
    var failWith: Exception? = null
    var completed = 0

    override suspend fun prefetch(records: List<AlertRecord>) {
        batches += records
        gate?.await()
        failWith?.let { throw it }
        completed++
    }
}

@RunWith(RobolectricTestRunner::class)
class AlertSyncServiceTest {

    @get:Rule val tmp = TemporaryFolder()

    private val t0 = 1_760_000_000_000L
    private var now = t0
    private val zone: ZoneId = ZoneId.of("UTC")

    private val token = "SYNTHTOKENVALUE"
    private val secret = "SYNTHSECRETVALUE"
    private val userId = "synth-user-7"
    private val device = "synth-cam-1"
    private val mediaHost = "media.synth.example"
    private val pwd = "SYNTHPWDVALUE"

    private lateinit var db: YiDatabase
    private val api = FakeAlertApi()
    private val session = MutableSession(AuthSession(token, secret, Long.MAX_VALUE, userId, AuthMethod.PASSWORD))
    private val media = MediaRefStore()
    private lateinit var cleaner: EventCacheCleaner
    private lateinit var files: EventMediaFiles
    private lateinit var filesDir: File
    private lateinit var cacheDir: File
    private val prefetcher = RecordingPrefetcher()
    private lateinit var service: AlertSyncService
    private val recorder = TraceRecorder()
    private val redaction = RedactionScanner()

    @Before fun setUp() {
        recorder.plant()
        db = InMemoryRoom.build()
        filesDir = tmp.newFolder("files")
        cacheDir = tmp.newFolder("cache")
        files = EventMediaFiles(filesDir, cacheDir) { zone }
        cleaner = EventCacheCleaner(db.eventDao(), db.appMetaDao(), media, files)
        service = newService()
    }

    @After fun tearDown() {
        db.close()
        recorder.unplant()
    }

    private fun newService() = AlertSyncService(
        api, db.eventDao(), db.appMetaDao(), session, media, cleaner,
        clock = { now }, zone = { zone }, io = Dispatchers.Unconfined,
        prefetcher = { prefetcher }, files = files, appScope = CoroutineScope(Dispatchers.Unconfined),
    )

    // START_BLOCK_FIXTURES
    private fun rec(id: String, timeMs: Long, version: String = "v1"): JsonObject = buildJsonObject {
        put("id", id)
        put("uid", device)
        put("time", timeMs)
        put("type", 1)
        put("sub_type", 0)
        put("file_type", "mp4")
        put("pic_urls", "http://$mediaHost/p/$id-$version.jpg?sig=x")
        put("pic_pwd", pwd)
        put("video_urls", "http://$mediaHost/v/$id-$version.mp4?sig=x")
        put("video_pwd", pwd)
        put("expire_time", timeMs + 86_400_000L)
    }

    private fun page(vararg items: JsonObject, code: String = AlertWire.SUCCESS) =
        AlertEnvelope(code = code, data = JsonArray(items.toList()))

    /** [count] alerts with times start, start - 1 s, ... and keys "$prefix-<n>". */
    private fun series(prefix: String, start: Long, count: Int, offset: Int = 0): Array<JsonObject> =
        Array(count) { i -> rec("$prefix-${offset + i}", start - (offset + i) * 1_000L) }

    private fun meta(key: String): Long? = db.appMetaDao().get(key)?.toLong()

    private fun row(key: String, timeMs: Long) =
        EventEntity(key, device, timeMs, 1, 0, "mp4", true, 0, null, timeMs)

    private fun refresh(): SyncResult = runBlocking { service.refresh() }

    private fun expectedHmac(call: AlertCall) =
        YiRequestSigner.sign(token, secret, userId, AlertWire.signedExtra(call.fromSec, call.toSec, AlertWire.PAGE_LIMIT), AlertWire.SEQ)
    // END_BLOCK_FIXTURES

    @Test
    fun `too early makes no request and returns TooEarly with minutes`() {
        db.appMetaDao().put(AppMetaEntity(EventMetaKeys.LAST_FETCH_MS, (now - 2 * 60_000L).toString()))
        assertEquals(SyncResult.TooEarly(2), refresh())
        assertEquals(0, api.calls.size)
        assertTrue(recorder.bufferText().contains("decision=too-early minutesAgo=2"))
    }

    @Test
    fun `gate survives a new service instance`() {
        api.enqueue(page(*series("a", t0 - 60_000L, 3)))
        assertTrue(refresh() is SyncResult.Fetched)
        service = newService()
        now += 60_000L
        assertEquals(SyncResult.TooEarly(1), refresh())
        assertEquals(1, api.calls.size)
    }

    @Test
    fun `one page writes rows and the fetch mark`() {
        api.enqueue(page(*series("a", t0 - 60_000L, 3)))
        assertEquals(SyncResult.Fetched(newCount = 3, pages = 1, capped = false), refresh())
        assertEquals(3, db.eventDao().count())
        assertEquals(now, meta(EventMetaKeys.LAST_FETCH_MS))
        assertNull(meta(EventMetaKeys.GAP_TO_SEC))
        val window = AlertWire.window(now, zone)
        assertEquals(window.first, api.calls[0].fromSec)
        assertEquals(window.second, api.calls[0].toSec)
        assertNull(db.eventDao().getByKey("a-0")!!.thumbPath)
    }

    @Test
    fun `paging requests until a short page with signed params in vendor order`() {
        val p1 = series("p", t0 - 1_000L, 100)
        val p2 = series("p", t0 - 1_000L, 100, offset = 100)
        val p3 = series("p", t0 - 1_000L, 20, offset = 200)
        api.enqueue(page(*p1)); api.enqueue(page(*p2)); api.enqueue(page(*p3))
        assertEquals(SyncResult.Fetched(newCount = 220, pages = 3, capped = false), refresh())
        assertEquals(3, api.calls.size)
        api.calls.forEach { c ->
            assertEquals("0", c.seq)
            assertEquals(userId, c.userId)
            assertEquals("", c.type)
            assertEquals("", c.subType)
            assertEquals(100, c.limit)
            assertEquals("true", c.fromDb)
            assertEquals("10", c.expires)
            assertEquals(expectedHmac(c), c.hmac)
        }
        val window = AlertWire.window(now, zone)
        assertTrue(api.calls.all { it.fromSec == window.first })
        assertEquals((t0 - 1_000L - 99 * 1_000L) / 1000, api.calls[1].toSec)
        assertEquals((t0 - 1_000L - 199 * 1_000L) / 1000, api.calls[2].toSec)
    }

    @Test
    fun `duplicate boundary item is stored once`() {
        val p1 = series("d", t0 - 1_000L, 100)
        // The next page starts with the boundary item of page 1 (same id, same time).
        val p2 = series("d", t0 - 1_000L, 30, offset = 99)
        api.enqueue(page(*p1)); api.enqueue(page(*p2))
        val result = refresh() as SyncResult.Fetched
        assertEquals(129, db.eventDao().count())
        assertEquals(129, result.newCount)
        assertEquals(2, result.pages)
    }

    @Test
    fun `incremental refresh starts from the newest cached time`() {
        val newest = t0 - 3_600_000L
        db.eventDao().upsertAll(listOf(row("old", newest)))
        api.enqueue(page(rec("new", t0 - 1_000L)))
        assertEquals(SyncResult.Fetched(newCount = 1, pages = 1, capped = false), refresh())
        assertEquals((newest - 5_000L) / 1000, api.calls[0].fromSec)
    }

    @Test
    fun `concurrent refresh calls share one run`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        api.enqueue { gate.await(); page(rec("c", t0 - 1_000L)) }
        val first = async(start = CoroutineStart.UNDISPATCHED) { service.refresh() }
        assertEquals(SyncResult.InProgress, service.refresh())
        gate.complete(Unit)
        assertTrue(first.await() is SyncResult.Fetched)
        assertEquals(1, api.calls.size)
    }

    @Test
    fun `failed code blocks the next refresh for 5 minutes`() {
        api.enqueue(page(code = "50000"))
        assertEquals(SyncResult.Failed("50000"), refresh())
        assertEquals(now, meta(EventMetaKeys.LAST_ATTEMPT_MS))
        assertNull(meta(EventMetaKeys.LAST_FETCH_MS))
        now += 4 * 60_000L
        assertEquals(SyncResult.TooEarly(4), refresh())
        assertEquals(1, api.calls.size)
        now += 60_000L
        api.enqueue(page(rec("f", t0)))
        assertTrue(refresh() is SyncResult.Fetched)
        assertEquals(2, api.calls.size)
    }

    @Test
    fun `unauthorized also sets the attempt mark`() {
        api.enqueue(page(code = "20201"))
        assertEquals(SyncResult.Unauthorized, refresh())
        assertEquals(now, meta(EventMetaKeys.LAST_ATTEMPT_MS))
        now += 60_000L
        assertEquals(SyncResult.TooEarly(1), refresh())
        assertEquals(1, api.calls.size)
    }

    @Test
    fun `http 401 maps to Unauthorized without retry`() {
        api.enqueue { throw HttpException(Response.error<Any>(401, "".toResponseBody())) }
        assertEquals(SyncResult.Unauthorized, refresh())
        assertEquals(1, api.calls.size)
        assertEquals(now, meta(EventMetaKeys.LAST_ATTEMPT_MS))
    }

    @Test
    fun `offline does not set the attempt mark`() {
        api.enqueue { throw IOException("SYNTH offline") }
        assertEquals(SyncResult.Offline, refresh())
        assertNull(meta(EventMetaKeys.LAST_ATTEMPT_MS))
        api.enqueue(page(rec("o", t0)))
        assertTrue(refresh() is SyncResult.Fetched)
        assertEquals(2, api.calls.size)
    }

    @Test
    fun `auth code returns Unauthorized without retry and keeps the cache`() {
        db.eventDao().upsertAll(listOf(row("keep", t0 - 60_000L)))
        api.enqueue(page(code = "20202"))
        assertEquals(SyncResult.Unauthorized, refresh())
        assertEquals(1, api.calls.size)
        assertEquals(1, db.eventDao().count())
        assertNull(meta(EventMetaKeys.LAST_FETCH_MS))
    }

    @Test
    fun `IOException returns Offline and keeps the cache and does not move the fetch mark`() {
        db.eventDao().upsertAll(listOf(row("keep", t0 - 60_000L)))
        val earlier = now - 10 * 60_000L
        db.appMetaDao().put(AppMetaEntity(EventMetaKeys.LAST_FETCH_MS, earlier.toString()))
        api.enqueue { throw IOException("SYNTH offline") }
        assertEquals(SyncResult.Offline, refresh())
        assertEquals(1, api.calls.size)
        assertEquals(1, db.eventDao().count())
        assertEquals(earlier, meta(EventMetaKeys.LAST_FETCH_MS))
    }

    @Test
    fun `non success code returns Failed with the code`() {
        api.enqueue(page(code = "40003"))
        assertEquals(SyncResult.Failed("40003"), refresh())
        assertEquals(1, api.calls.size)
    }

    @Test
    fun `no session makes no request`() {
        session.session = null
        assertEquals(SyncResult.NoSession, refresh())
        assertEquals(0, api.calls.size)
    }

    @Test
    fun `rows older than 30 days are purged on a successful refresh`() {
        db.eventDao().upsertAll(listOf(row("ancient", t0 - 31L * 86_400_000L), row("recent", t0 - 29L * 86_400_000L)))
        api.enqueue(page(rec("n", t0 - 1_000L)))
        assertTrue(refresh() is SyncResult.Fetched)
        assertNull(db.eventDao().getByKey("ancient"))
        assertNotNull(db.eventDao().getByKey("recent"))
        assertTrue(recorder.bufferText().contains("purged=1"))
    }

    @Test
    fun `ten pages cap saves the gap and the next refresh resumes and clears it`() {
        repeat(10) { p -> api.enqueue(page(*series("g", t0 - 1_000L, 100, offset = p * 100))) }
        val first = refresh() as SyncResult.Fetched
        assertEquals(10, first.pages)
        assertTrue(first.capped)
        val gap = (t0 - 1_000L - 999 * 1_000L) / 1000
        assertEquals(gap, meta(EventMetaKeys.GAP_TO_SEC))
        assertEquals(10, api.calls.size)

        now += 5 * 60_000L
        api.enqueue(page(rec("newer", now - 1_000L)))
        api.enqueue(page(*series("g", t0 - 1_000L, 5, offset = 1000)))
        val second = refresh() as SyncResult.Fetched
        assertEquals(2, second.pages)
        assertFalse(second.capped)
        assertEquals(12, api.calls.size)
        val window = AlertWire.window(now, zone)
        assertEquals(window.first, api.calls[11].fromSec)
        assertEquals(gap, api.calls[11].toSec)
        assertNull(meta(EventMetaKeys.GAP_TO_SEC))
        assertEquals(1006, db.eventDao().count())
    }

    @Test
    fun `media refs are kept in memory only`() {
        api.enqueue(page(*series("m", t0 - 1_000L, 3)))
        assertTrue(refresh() is SyncResult.Fetched)
        val ref = service.mediaFor("m-0")!!
        assertTrue(ref.picUrl!!.startsWith("https://$mediaHost/"))
        assertEquals(pwd, ref.videoPwd)
        val stored = StringBuilder()
        db.openHelper.readableDatabase.query("SELECT * FROM events").use { c ->
            while (c.moveToNext()) for (i in 0 until c.columnCount) stored.append(c.getString(i)).append('|')
        }
        db.appMetaDao().all().forEach { stored.append(it.key).append('=').append(it.value).append('|') }
        assertFalse(stored.contains(mediaHost))
        assertFalse(stored.contains(pwd))
        assertFalse(stored.contains("sig="))
    }

    @Test
    fun `refreshMedia uses the plus minus 5 s window and is throttled`() {
        val time = t0 - 1_000L
        api.enqueue(page(rec("r", time)))
        assertTrue(refresh() is SyncResult.Fetched)
        val fetchMark = meta(EventMetaKeys.LAST_FETCH_MS)
        now += 10_000L
        api.enqueue(page(rec("other", time + 2_000L, "v2"), rec("r", time, "v2")))
        val fresh = runBlocking { service.refreshMedia("r") }!!
        assertEquals(2, api.calls.size)
        val call = api.calls[1]
        assertEquals(AlertWire.refreshWindow(time), call.fromSec to call.toSec)
        assertEquals(AlertWire.PAGE_LIMIT, call.limit)
        assertEquals(expectedHmac(call), call.hmac)
        assertTrue(fresh.picUrl!!.contains("r-v2"))
        assertSame(fresh, service.mediaFor("r"))

        now += 30_000L
        val again = runBlocking { service.refreshMedia("r") }
        assertEquals(2, api.calls.size)
        assertSame(fresh, again)
        assertEquals(fetchMark, meta(EventMetaKeys.LAST_FETCH_MS))
    }

    @Test
    fun `refreshMedia global cap is 6 per minute`() {
        db.eventDao().upsertAll((0 until 8).map { row("k-$it", t0 - it * 60_000L) })
        repeat(7) { i -> api.enqueue { c -> page(rec("k-$i", t0 - i * 60_000L)).also { check(c.limit == 100) } } }
        val results = (0 until 7).map { i -> now += 1_000L; runBlocking { service.refreshMedia("k-$i") } }
        assertEquals(6, api.calls.size)
        assertTrue(results.take(6).all { it != null })
        assertNull(results[6])
        assertTrue(recorder.bufferText().contains("result=throttled"))
        now += 60_000L
        assertNotNull(runBlocking { service.refreshMedia("k-6") })
        assertEquals(7, api.calls.size)
    }

    @Test
    fun `refreshMedia of an unknown key makes no request`() {
        assertNull(runBlocking { service.refreshMedia("missing") })
        assertEquals(0, api.calls.size)
    }

    @Test
    fun `a logout during a refresh writes nothing afterwards`() {
        api.enqueue {
            session.session = null
            cleaner.wipeAll()
            page(rec("late", t0 - 1_000L))
        }
        assertEquals(SyncResult.NoSession, refresh())
        assertEquals(0, db.eventDao().count())
        assertNull(meta(EventMetaKeys.LAST_FETCH_MS))
        assertNull(service.mediaFor("late"))
    }

    @Test
    fun `lastFetchMs reads the persisted mark`() {
        assertNull(runBlocking { service.lastFetchMs() })
        api.enqueue(page(rec("l", t0 - 1_000L)))
        refresh()
        assertEquals(now, runBlocking { service.lastFetchMs() })
    }

    @Test
    fun `logs pass RedactionScanner`() {
        api.enqueue(page(*series("s", t0 - 1_000L, 100)))
        api.enqueue(page(*series("s", t0 - 1_000L, 10, offset = 100)))
        assertTrue(refresh() is SyncResult.Fetched)
        now += 10_000L
        api.enqueue(page(rec("s-3", t0 - 4_000L, "v2")))
        runBlocking { service.refreshMedia("s-3") }
        val logs = recorder.bufferText()
        assertTrue(logs.contains("BLOCK_REFRESH_GATE"))
        assertTrue(logs.contains("[Event][sync][BLOCK_FETCH_PAGE] page=2 size=10 step=done"))
        assertTrue(logs.contains("BLOCK_SYNC_RESULT"))
        assertTrue(logs.contains("[Event][media][BLOCK_REFRESH_MEDIA] result=ok"))
        redaction.assertClean(
            logs,
            extraLiterals = listOf(token, secret, userId, device, mediaHost, pwd, "s-3", (t0 / 1000).toString().take(8)),
        )
    }

    private fun keys(batch: List<AlertRecord>) = batch.map { it.key }

    private fun mediaRef() = MediaRef("https://$mediaHost/p/x.jpg?sig=x", pwd, "https://$mediaHost/v/x.mp4?sig=x", pwd, t0 + 3_600_000L)

    @Test
    fun `successful refresh prefetches new records`() {
        api.enqueue(page(*series("a", t0 - 60_000L, 3)))
        assertTrue(refresh() is SyncResult.Fetched)
        assertEquals(1, prefetcher.batches.size)
        assertEquals(listOf("a-0", "a-1", "a-2"), keys(prefetcher.batches[0]))
        assertEquals(1, prefetcher.completed)
        assertTrue(recorder.bufferText().contains("[Event][sync][BLOCK_PREFETCH_LAUNCH] started=true records=3"))
    }

    @Test
    fun `prefetch also fills cached rows without a thumb that still have a media ref`() {
        db.eventDao().upsertAll(
            listOf(
                row("gap", t0 - 7_200_000L),
                row("noref", t0 - 3_600_000L),
                row("has", t0 - 1_800_000L).copy(thumbPath = "event_thumbs/20250101/x.jpg"),
            ),
        )
        media.put("gap", mediaRef())
        media.put("has", mediaRef())
        api.enqueue(page(rec("n", t0 - 1_000L)))
        assertTrue(refresh() is SyncResult.Fetched)
        assertEquals(listOf("n", "gap"), keys(prefetcher.batches.single()))
    }

    @Test
    fun `refresh returns before prefetch completes`() {
        prefetcher.gate = CompletableDeferred()
        api.enqueue(page(*series("b", t0 - 60_000L, 2)))
        assertTrue(refresh() is SyncResult.Fetched)
        assertEquals(1, prefetcher.batches.size)
        assertEquals(0, prefetcher.completed)
        prefetcher.gate!!.complete(Unit)
        assertEquals(1, prefetcher.completed)
    }

    @Test
    fun `refreshMedia is not blocked by prefetch`() {
        prefetcher.gate = CompletableDeferred()
        val time = t0 - 1_000L
        api.enqueue(page(rec("r", time)))
        assertTrue(refresh() is SyncResult.Fetched)
        assertEquals(0, prefetcher.completed)
        now += 10_000L
        api.enqueue(page(rec("r", time, "v2")))
        val fresh = runBlocking { kotlinx.coroutines.withTimeout(5_000L) { service.refreshMedia("r") } }
        assertNotNull(fresh)
        assertTrue(fresh!!.picUrl!!.contains("r-v2"))
        prefetcher.gate!!.complete(Unit)
    }

    @Test
    fun `a running prefetch pass is not started twice`() {
        prefetcher.gate = CompletableDeferred()
        api.enqueue(page(rec("x1", t0 - 1_000L)))
        assertTrue(refresh() is SyncResult.Fetched)
        now += 5 * 60_000L
        api.enqueue(page(rec("x2", now - 1_000L)))
        assertTrue(refresh() is SyncResult.Fetched)
        assertEquals(1, prefetcher.batches.size)
        assertTrue(recorder.bufferText().contains("[Event][sync][BLOCK_PREFETCH_LAUNCH] started=false reason=running"))
        prefetcher.gate!!.complete(Unit)
        prefetcher.gate = null
        now += 5 * 60_000L
        api.enqueue(page(rec("x3", now - 1_000L)))
        assertTrue(refresh() is SyncResult.Fetched)
        assertEquals(2, prefetcher.batches.size)
    }

    @Test
    fun `prefetch pass is capped at 100 records newest first`() {
        api.enqueue(page(*series("p", t0 - 1_000L, 100)))
        api.enqueue(page(*series("p", t0 - 1_000L, 50, offset = 100)))
        assertTrue(refresh() is SyncResult.Fetched)
        val batch = prefetcher.batches.single()
        assertEquals(AlertSyncService.PREFETCH_MAX, batch.size)
        assertEquals((0 until 100).map { "p-$it" }, keys(batch))
        assertEquals(batch.sortedByDescending { it.timeMs }, batch)
    }

    @Test
    fun `prefetch failure does not change the result`() {
        prefetcher.failWith = IllegalStateException("SYNTH prefetch failure")
        api.enqueue(page(rec("f1", t0 - 1_000L)))
        assertEquals(SyncResult.Fetched(newCount = 1, pages = 1, capped = false), refresh())
        val logs = recorder.bufferText()
        assertTrue(logs.contains("[Event][sync][BLOCK_PREFETCH_LAUNCH] error=IllegalStateException"))
        assertFalse(logs.contains("SYNTH prefetch failure"))
        prefetcher.failWith = null
        now += 5 * 60_000L
        api.enqueue(page(rec("f2", now - 1_000L)))
        assertTrue(refresh() is SyncResult.Fetched)
        assertEquals(2, prefetcher.batches.size)
    }

    @Test
    fun `purge removes old thumb files`() {
        val oldTime = t0 - 31L * 86_400_000L
        val newTime = t0 - 29L * 86_400_000L
        val oldRel = files.thumbRelPath("ancient", oldTime)
        val newRel = files.thumbRelPath("recent", newTime)
        assertTrue(files.writeAtomic(files.thumbFile(oldRel)!!, byteArrayOf(1, 2, 3)))
        assertTrue(files.writeAtomic(files.thumbFile(newRel)!!, byteArrayOf(4, 5, 6)))
        db.eventDao().upsertAll(listOf(row("ancient", oldTime).copy(thumbPath = oldRel), row("recent", newTime).copy(thumbPath = newRel)))
        api.enqueue(page(rec("n", t0 - 1_000L)))
        assertTrue(refresh() is SyncResult.Fetched)
        assertFalse(File(filesDir, oldRel).exists())
        assertTrue(File(filesDir, newRel).exists())
        assertNull(db.eventDao().getByKey("ancient"))
        assertTrue(recorder.bufferText().contains("[Event][sync][BLOCK_PURGE_FILES] files=1"))
    }

    @Test
    fun `logout wipe deletes media files`() {
        val rel = files.thumbRelPath("w", t0)
        assertTrue(files.writeAtomic(files.thumbFile(rel)!!, byteArrayOf(1)))
        assertTrue(files.writeAtomic(files.clipFile("w"), byteArrayOf(2)))
        media.put("w", mediaRef())
        cleaner.wipeAll()
        assertFalse(File(filesDir, "event_thumbs").exists())
        assertFalse(File(cacheDir, "event_clips").exists())
        assertNull(media.get("w"))
        assertTrue(recorder.bufferText().contains("[Event][wipe][BLOCK_WIPE_FILES] rows=ok files=ok"))
    }
}
