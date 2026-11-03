// FILE: YiDatabaseTest.kt
// VERSION: 0.3.0
// START_MODULE_CONTRACT
//   PURPOSE: Verify Room cache round-trips, event feed window and thumbnail-preserving upsert, clearOlderThan, atomic replaceAll, and redaction of logs.
//   SCOPE: success scenarios for V-M-LOCAL-DB using InMemoryRoom + Robolectric; trace + redaction assertions.
//   DEPENDS: M-LOCAL-DB, TestInfrastructure (InMemoryRoom, TraceRecorder, RedactionScanner)
//   LINKS: V-M-LOCAL-DB
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.database

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.3.0 - GSD plan 05-02: schema v3 events (event_key, time_ms); feed order, thumb kept on upsert,
//     clearOlderThan, events table has no URL / key column.
//   PREV_CHANGE: v0.2.0 - GSD plan 01-12: schema v2 (transport, shared), replaceAll scenarios.
// END_CHANGE_SUMMARY

import com.yicamalt.test.InMemoryRoom
import com.yicamalt.test.RedactionScanner
import com.yicamalt.test.TraceRecorder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class YiDatabaseTest {

    private lateinit var db: YiDatabase
    private val recorder = TraceRecorder()
    private val redaction = RedactionScanner()

    @Before fun setUp() {
        recorder.plant()
        db = InMemoryRoom.build()
        // Force onCreate callback so BLOCK_INIT_DB is emitted once for the in-memory DB.
        db.openHelper.writableDatabase  // triggers creation
    }

    @After fun tearDown() {
        db.close()
        recorder.unplant()
    }

    private fun camera(id: String, online: Boolean = true) = CameraEntity(
        device_id = id, name = "Cam $id", model = "YI_HOME",
        online = online, stream_url = "https://stream/$id",
        last_seen = 1000L, cached_at = 2000L,
    )

    private fun event(key: String, device: String, ts: Long, thumb: String? = null) = EventEntity(
        eventKey = key, deviceId = device, timeMs = ts, type = 1, subType = 0, fileType = "mp4",
        hasClip = true, playTimeS = 0, thumbPath = thumb, cachedAt = ts,
    )

    @Test
    fun `scenario_1 insert camera + query returns cached row`() {
        // START_BLOCK_S1
        db.cameraDao().insertCamera(camera("cam-1"))
        val rows = db.cameraDao().getAll()
        // END_BLOCK_S1
        assertEquals(1, rows.size)
        assertEquals("cam-1", rows[0].device_id)
        recorder.assertMarkerAppeared("BLOCK_DB_WRITE")
    }

    @Test
    fun `scenario_2 feed returns the window across cameras`() {
        db.eventDao().upsertAll(listOf(event("e-1", "cam-1", 5000L), event("e-2", "cam-1", 9000L), event("e-3", "cam-2", 7000L)))
        val rows = db.eventDao().feed(fromMs = 6000L, toMs = 9000L)
        assertEquals(listOf("e-2", "e-3"), rows.map { it.eventKey })
        assertEquals(3, db.eventDao().count())
        assertEquals(9000L, db.eventDao().newestTime())
        assertEquals(listOf(9000L, 7000L), db.eventDao().timesSince(6000L))
        recorder.assertMarkerAppeared("BLOCK_DB_WRITE")
    }

    @Test
    fun `clearOlderThan removes only old rows`() {
        db.eventDao().upsertAll(listOf(event("e-old", "cam-1", 1000L, thumb = "t/old.jpg"), event("e-new", "cam-1", 9000L)))
        assertEquals(listOf("t/old.jpg"), db.eventDao().thumbPathsOlderThan(5000L))
        val removed = db.eventDao().clearOlderThan(cutoff = 5000L)
        assertEquals(1, removed)
        assertNull(db.eventDao().getByKey("e-old"))
        assertNotNull(db.eventDao().getByKey("e-new"))
    }

    @Test
    fun `upsert keeps an existing thumb path`() {
        db.eventDao().upsertAll(listOf(event("e-1", "cam-1", 5000L)))
        db.eventDao().setThumb("e-1", "thumbs/e1.jpg")
        assertEquals(emptyList<String>(), db.eventDao().missingThumbsSince(0L, 10).map { it.eventKey })
        db.eventDao().upsertAll(listOf(event("e-1", "cam-1", 5000L).copy(type = 2), event("e-2", "cam-1", 6000L)))
        val row = db.eventDao().getByKey("e-1")!!
        assertEquals("thumbs/e1.jpg", row.thumbPath)
        assertEquals(2, row.type)
        assertEquals(listOf("e-2"), db.eventDao().missingThumbsSince(0L, 10).map { it.eventKey })
    }

    @Test
    fun `observeFeed orders newest first within the window`() {
        db.eventDao().upsertAll(listOf(event("a", "cam-1", 3000L), event("b", "cam-2", 8000L), event("c", "cam-1", 5000L), event("d", "cam-1", 20000L)))
        val rows = runBlocking { db.eventDao().observeFeed(1000L, 10000L).first() }
        assertEquals(listOf("b", "c", "a"), rows.map { it.eventKey })
    }

    @Test
    fun `events table has no url or key column`() {
        val columns = mutableListOf<String>()
        db.openHelper.writableDatabase.query("PRAGMA table_info(events)").use { c ->
            val nameIdx = c.getColumnIndex("name")
            while (c.moveToNext()) columns += c.getString(nameIdx)
        }
        assertEquals(
            listOf("cached_at", "device_id", "event_key", "file_type", "has_clip", "play_time_s", "sub_type", "thumb_path", "time_ms", "type"),
            columns.sorted(),
        )
    }

    @Test
    fun `scenario_4 schema is v3 and migration stays destructive`() {
        // v3 = events re-keyed for v2/alert/list (v2 = cameras + transport, shared). fallbackToDestructiveMigration is configured in
        // DatabaseModule; the tables are a cache, so a wipe on upgrade is acceptable.
        val version = db.openHelper.writableDatabase.version
        assertEquals(3, version)
    }

    @Test
    fun `scenario_6 transport and shared round trip and default safely`() {
        db.cameraDao().insertCamera(camera("cam-1").copy(transport = "TNP", shared = true))
        db.cameraDao().insertCamera(camera("cam-2"))
        val tnp = db.cameraDao().getById("cam-1")!!
        assertEquals("TNP", tnp.transport)
        assertTrue(tnp.shared)
        val plain = db.cameraDao().getById("cam-2")!!
        assertEquals("UNKNOWN", plain.transport)
        assertEquals(false, plain.shared)
    }

    @Test
    fun `scenario_7 replaceAll swaps the whole cache`() {
        // START_BLOCK_S7
        db.cameraDao().insertCamera(camera("cam-z"))
        db.cameraDao().replaceAll(listOf(camera("cam-x"), camera("cam-y")))
        // END_BLOCK_S7
        assertEquals(listOf("cam-x", "cam-y"), db.cameraDao().getAll().map { it.device_id }.sorted())
        assertNull(db.cameraDao().getById("cam-z"))
        db.cameraDao().replaceAll(emptyList())
        assertEquals(0, db.cameraDao().count())
    }

    @Test
    fun `scenario_8 cameras table has no secret column`() {
        // SEC-04: P2P secrets live only in the encrypted store, never in Room.
        val columns = mutableListOf<String>()
        db.openHelper.writableDatabase.query("PRAGMA table_info(cameras)").use { c ->
            val nameIdx = c.getColumnIndex("name")
            while (c.moveToNext()) columns += c.getString(nameIdx)
        }
        assertEquals(
            listOf("cached_at", "device_id", "last_seen", "model", "name", "online", "shared", "stream_url", "transport"),
            columns.sorted(),
        )
    }

    @Test
    fun `scenario_5 redaction db logs do not leak secrets`() {
        // Realistic 13-digit Yi serial (SEC-01): BLOCK_DB_WRITE must not carry device_id.
        db.cameraDao().insertCamera(camera("9876543210123"))
        recorder.assertMarkerAppeared("BLOCK_DB_WRITE")
        redaction.assertClean(recorder.bufferText(), extraLiterals = listOf("9876543210123"))
    }

    @Test
    fun `trace init emits BLOCK_INIT_DB exactly once`() {
        val initMarkers = recorder.all().count { it.message.contains("BLOCK_INIT_DB") }
        assertEquals("BLOCK_INIT_DB must appear exactly once per creation", 1, initMarkers)
    }

    @Test
    fun `getById and deleteAll behave correctly`() {
        db.cameraDao().insertCamera(camera("cam-1"))
        assertNotNull(db.cameraDao().getById("cam-1"))
        db.cameraDao().deleteAll()
        assertNull(db.cameraDao().getById("cam-1"))
        assertEquals(0, db.cameraDao().count())
    }

    @Test
    fun `app_meta key value round trip`() {
        val dao = db.appMetaDao()
        dao.put(AppMetaEntity("last_sync", "12345"))
        assertEquals("12345", dao.get("last_sync"))
        dao.delete("last_sync")
        assertNull(dao.get("last_sync"))
    }
}