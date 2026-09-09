// FILE: CameraRegistryModuleTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Verify M-CAMERA-LIST against MockWebServer: list parse, cache write, 401/5xx mapping.
//   SCOPE: success + failure scenarios for V-M-CAMERA-LIST; trace marker assertions.
//   DEPENDS: M-CAMERA-LIST, TestInfrastructure
//   LINKS: V-M-CAMERA-LIST
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.camera

import com.yicamalt.database.CameraDao
import com.yicamalt.database.CameraEntity
import com.yicamalt.test.RedactionScanner
import com.yicamalt.test.TraceRecorder
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
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory

/** In-memory CameraDao fake (no Room) so plain-JVM tests can capture cache writes. */
private class FakeCameraDao : CameraDao() {
    val stored = mutableListOf<CameraEntity>()
    override fun getAll(): List<CameraEntity> = stored.toList()
    override fun getById(deviceId: String): CameraEntity? = stored.find { it.device_id == deviceId }
    override fun deleteAll() { stored.clear() }
    override fun count(): Int = stored.size
    override fun insertCameraRaw(entity: CameraEntity) {
        // Mirror OnConflictStrategy.REPLACE (Room would upsert on device_id PK).
        stored.removeAll { it.device_id == entity.device_id }
        stored += entity
    }
}

class CameraRegistryModuleTest {

    private val server = MockWebServer()
    private val recorder = TraceRecorder()
    private val redaction = RedactionScanner()
    private val dao = FakeCameraDao()
    private lateinit var registry: CameraRegistry

    @BeforeEach
    fun setUp() {
        server.start()
        recorder.plant()
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val api = Retrofit.Builder()
            .baseUrl(server.url("/v1/").toString())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(YiCloudDeviceApi::class.java)
        registry = CameraRegistry(api, dao)
    }

    @AfterEach
    fun tearDown() {
        recorder.unplant()
        server.shutdown()
    }

    private fun deviceListBody(vararg extra: Pair<String, String>) =
        """{"code":"20000","data":{"device_list":[{"device_id":"cam-1","name":"Living Room","model":"YI_HOME","online":true,"stream_url":"https://stream/cam-1"},{"device_id":"cam-2","name":"Front Door","model":"YI_DOME","online":false,"stream_url":null}]}}"""

    @Test
    fun `scenario_1 list returned with correct fields and cached`() {
        server.enqueue(MockResponse().setBody(deviceListBody()))
        val cameras = runBlocking { registry.getCameraList() }
        assertEquals(2, cameras.size)
        val first = cameras.first()
        assertEquals("cam-1", first.deviceId)
        assertEquals("Living Room", first.name)
        assertEquals("YI_HOME", first.model)
        assertTrue(first.online)
        assertEquals("https://stream/cam-1", first.streamUrl)
        // cached to the DAO
        assertEquals(2, dao.count())
        assertNotNull(dao.getById("cam-1"))
        recorder.assertMarkerAppeared("BLOCK_FETCH_CAMERA_LIST")
        recorder.assertMarkerAppeared("BLOCK_DB_WRITE")
    }

    @Test
    fun `scenario_1b data may be a bare array or use sn and list keys`() {
        server.enqueue(
            MockResponse().setBody(
                """{"code":"20000","data":[{"sn":"s1","nick_name":"Porch","model_name":"YI_360","online":1,"live_url":"rtsp://x/live"}]}""",
            ),
        )
        val cameras = runBlocking { registry.getCameraList() }
        assertEquals(1, cameras.size)
        val cam = cameras.first()
        assertEquals("s1", cam.deviceId)
        assertEquals("Porch", cam.name)
        assertEquals("YI_360", cam.model)
        assertTrue(cam.online)
        assertEquals("rtsp://x/live", cam.streamUrl)
    }

    @Test
    fun `scenario_2 empty list is not an error`() {
        server.enqueue(MockResponse().setBody("""{"code":"20000","data":{"device_list":[]}}"""))
        val cameras = runBlocking { registry.getCameraList() }
        assertTrue(cameras.isEmpty())
        assertEquals(0, dao.count(), "empty remote list must not touch the cache")
    }

    @Test
    fun `scenario_3 cache lookup and status update`() {
        server.enqueue(MockResponse().setBody(deviceListBody()))
        runBlocking { registry.getCameraList() }
        val cam = runBlocking { registry.getCameraById("cam-2") }
        assertNotNull(cam)
        assertEquals("cam-2", cam!!.deviceId)
        assertTrue(!cam.online)
        runBlocking { registry.updateCameraStatus("cam-2", online = true) }
        assertEquals(true, runBlocking { registry.getCameraById("cam-2") }!!.online)
    }

    @Test
    fun `scenario_4 401 maps to CameraUnauthorized`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        val err = assertThrows(CameraError.Unauthorized::class.java) {
            runBlocking { registry.getCameraList() }
        }
        assertTrue(err.message!!.contains("CAMERA_UNAUTHORIZED"))
    }

    @Test
    fun `scenario_5 5xx maps to FetchFailed`() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("oops"))
        val err = assertThrows(CameraError.FetchFailed::class.java) {
            runBlocking { registry.getCameraList() }
        }
        assertTrue(err.message!!.contains("CAMERA_FETCH_FAILED"))
    }

    @Test
    fun `scenario_6 live url resolution returns stored url`() {
        server.enqueue(MockResponse().setBody(deviceListBody()))
        runBlocking { registry.getCameraList() }
        val url = runBlocking { registry.getLiveStreamUrl("cam-1") }
        assertEquals("https://stream/cam-1", url)
        recorder.assertMarkerAppeared("BLOCK_RESOLVE_STREAM_URL")
    }

    @Test
    fun `scenario_7 live url missing for offline camera throws NoStreamUrl`() {
        server.enqueue(MockResponse().setBody(deviceListBody()))
        runBlocking { registry.getCameraList() }
        assertThrows(CameraError.NoStreamUrl::class.java) {
            runBlocking { registry.getLiveStreamUrl("cam-2") }
        }
    }

    @Test
    fun `scenario_8 redaction no camera or stream data in logs`() {
        server.enqueue(MockResponse().setBody(deviceListBody()))
        runBlocking { registry.getCameraList() }
        redaction.assertClean(
            recorder.bufferText(),
            extraLiterals = listOf("Living Room", "https://stream/cam-1"),
        )
    }
}