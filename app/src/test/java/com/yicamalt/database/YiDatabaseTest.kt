// FILE: YiDatabaseTest.kt
// VERSION: 0.2.0
// START_MODULE_CONTRACT
//   PURPOSE: Verify Room cache round-trips, event query by device, clearOldData, atomic replaceAll, and redaction of logs.
//   SCOPE: success scenarios for V-M-LOCAL-DB using InMemoryRoom + Robolectric; trace + redaction assertions.
//   DEPENDS: M-LOCAL-DB, TestInfrastructure (InMemoryRoom, TraceRecorder, RedactionScanner)
//   LINKS: V-M-LOCAL-DB
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.database

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.2.0 - GSD plan 01-12: schema v2 (transport, shared), replaceAll scenarios.
// END_CHANGE_SUMMARY

import com.yicamalt.test.InMemoryRoom
import com.yicamalt.test.RedactionScanner
import com.yicamalt.test.TraceRecorder
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

    private fun event(id: String, device: String, ts: Long, type: String = "motion") = EventEntity(
        event_id = id, device_id = device, type = type,
        timestamp = ts, payload_json = "{}", cached_at = ts,
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
    fun `scenario_2 insert event + query by cameraId returns it`() {
        db.cameraDao().insertCamera(camera("cam-1"))
        db.eventDao().insertEvent(event("e-1", "cam-1", ts = 5000L))
        db.eventDao().insertEvent(event("e-2", "cam-1", ts = 9000L))
        db.eventDao().insertEvent(event("e-3", "cam-2", ts = 7000L))
        val forCam1 = db.eventDao().getByDevice("cam-1")
        assertEquals(2, forCam1.size)
        // ordered DESC by timestamp
        assertEquals("e-2", forCam1[0].event_id)
        assertEquals(1, db.eventDao().countByDevice("cam-2"))
    }

    @Test
    fun `scenario_3 clearOldData removes only old events`() {
        db.eventDao().insertEvent(event("e-old", "cam-1", ts = 1000L))
        db.eventDao().insertEvent(event("e-new", "cam-1", ts = 9000L))
        val removed = db.eventDao().clearOlderThan(cutoff = 5000L)
        assertEquals(1, removed)
        val remaining = db.eventDao().getByDevice("cam-1")
        assertEquals(1, remaining.size)
        assertEquals("e-new", remaining[0].event_id)
    }

    @Test
    fun `scenario_4 schema is v2 and migration stays destructive`() {
        // v2 = cameras + transport, shared. fallbackToDestructiveMigration is configured in
        // DatabaseModule; the tables are a cache, so a wipe on upgrade is acceptable.
        val version = db.openHelper.writableDatabase.version
        assertEquals(2, version)
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