// FILE: CameraRegistryModuleTest.kt
// VERSION: 0.2.0
// START_MODULE_CONTRACT
//   PURPOSE: Verify M-CAMERA-LIST against MockWebServer: probe-driven source, parse, atomic cache, distinct outcomes.
//   SCOPE: success + failure scenarios for V-M-CAMERA-LIST; trace marker assertions; synthetic fixtures only.
//   DEPENDS: M-CAMERA-LIST, TestInfrastructure, DeviceListFixtures
//   LINKS: V-M-CAMERA-LIST
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.camera

// START_MODULE_MAP
//   FakeCameraDao - in-memory CameraDao (no Room); can be told to throw on reads.
//   FakeSession / NoSession - SessionSource fakes.
//   CameraRegistryModuleTest - scenario_1..scenario_13.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.2.0 - plan 01-09: path pinned to DeviceSourcePlan.OWNED_PATHS.first(); streamUrl is
//     null after a refresh (the list has none); 20200/empty clears cache + credential store; IO -> FetchFailed,
//     non-success code -> ServerRejected (both leave cache + store untouched); shared rows and dedupe;
//     transport mapping; cachedCameras; concurrent refreshes.
//   PREV_CHANGE: v0.1.0 - created.
// END_CHANGE_SUMMARY

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.yicamalt.database.CameraDao
import com.yicamalt.database.CameraEntity
import com.yicamalt.test.RedactionFixtures
import com.yicamalt.test.RedactionScanner
import com.yicamalt.test.TraceRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.Retrofit

/** In-memory CameraDao fake (no Room) so plain-JVM tests can capture cache writes. */
internal class FakeCameraDao : CameraDao() {
    val stored = mutableListOf<CameraEntity>()
    @Volatile var failReads = false
    @Synchronized override fun getAll(): List<CameraEntity> {
        if (failReads) throw IllegalStateException("SYNTH db failure")
        return stored.toList()
    }
    @Synchronized override fun getById(deviceId: String): CameraEntity? = stored.find { it.device_id == deviceId }
    @Synchronized override fun deleteAll() { stored.clear() }
    @Synchronized override fun count(): Int = stored.size
    @Synchronized override fun insertCameraRaw(entity: CameraEntity) {
        // Mirror OnConflictStrategy.REPLACE (Room would upsert on device_id PK).
        stored.removeAll { it.device_id == entity.device_id }
        stored += entity
    }
}

internal class FakeSession(
    private val token: String = "t-1",
    private val secret: String = "s-1",
    private val uid: String = "u-1",
) : com.yicamalt.auth.SessionSource {
    override fun currentSession(): com.yicamalt.auth.AuthSession? =
        com.yicamalt.auth.AuthSession(token, secret, Long.MAX_VALUE, uid, com.yicamalt.auth.AuthMethod.PASSWORD)
}

private class NoSession : com.yicamalt.auth.SessionSource {
    override fun currentSession(): com.yicamalt.auth.AuthSession? = null
}

class CameraRegistryModuleTest {

    private val server = MockWebServer()
    private val recorder = TraceRecorder()
    private val redaction = RedactionScanner()
    private val dao = FakeCameraDao()
    private val store = InMemoryP2pCredentialStore()
    private lateinit var api: YiCloudDeviceApi
    private lateinit var registry: CameraRegistry

    private val sentinel = P2pCredentials(
        deviceId = "old-cam", p2pId = "old-p2p", transport = TransportType.TUTK, password = "x",
        initString = null, licenseKey = null, p2pEncrypt = true, wakeup = false, needsPincode = false,
    )

    @BeforeEach
    fun setUp() {
        server.start()
        recorder.plant()
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        api = Retrofit.Builder()
            .baseUrl(server.url("/v1/").toString())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(YiCloudDeviceApi::class.java)
        registry = CameraRegistry(api, dao, FakeSession(), store)
    }

    @AfterEach
    fun tearDown() {
        recorder.unplant()
        server.shutdown()
    }

    private fun deviceListBody() =
        """{"code":"20000","data":{"device_list":[{"device_id":"cam-1","name":"Living Room","model":"YI_HOME","online":true,"stream_url":"https://stream/cam-1"},{"device_id":"cam-2","name":"Front Door","model":"YI_DOME","online":false,"stream_url":null}]}}"""

    /** Puts a previous successful state in place so tests can prove it is (or is not) cleared. */
    private fun seedPreviousState() {
        dao.insertCameraRaw(CameraEntity("old-cam", "Old", "M", true, null, 1, 1, "TUTK", false))
        store.replaceAll(listOf(sentinel))
    }

    private fun assertPreviousStateUntouched() {
        assertEquals(1, dao.count())
        assertNotNull(dao.getById("old-cam"))
        assertNotNull(store.get("old-cam"))
    }

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
        assertNull(first.streamUrl, "the device list carries no stream url")
        assertEquals(2, dao.count())
        assertNotNull(dao.getById("cam-1"))
        recorder.assertMarkerAppeared("BLOCK_FETCH_SOURCE")
        recorder.assertMarkerAppeared("BLOCK_FETCH_CAMERA_LIST")
        recorder.assertMarkerAppeared("BLOCK_DB_WRITE")
    }

    @Test
    fun `scenario_1c signed request goes to the probe-proven path with seq userid and hmac`() {
        server.enqueue(MockResponse().setBody(deviceListBody()))
        runBlocking { registry.getCameraList() }
        assertEquals(1, server.requestCount, "data on the first path: no further list request")
        val q = server.takeRequest().requestUrl!!
        assertEquals(DeviceSourcePlan.OWNED_PATHS.first(), q.encodedPath)
        assertEquals("/v5/devices/list", q.encodedPath)
        assertEquals("1", q.queryParameter("seq"))
        assertEquals("u-1", q.queryParameter("userid"))
        val mac = javax.crypto.Mac.getInstance("HmacSHA1")
        mac.init(javax.crypto.spec.SecretKeySpec("t-1&s-1".toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val expected = java.util.Base64.getEncoder()
            .encodeToString(mac.doFinal("seq=1&userid=u-1".toByteArray(Charsets.UTF_8)))
        assertEquals(expected, q.queryParameter("hmac"))
        assertEquals(com.yicamalt.network.YiRequestSigner.sign("t-1", "s-1", "u-1"), q.queryParameter("hmac"))
    }

    @Test
    fun `scenario_1d no session maps to Unauthorized without a request`() {
        registry = CameraRegistry(api, dao, NoSession(), store)
        val err = assertThrows(CameraError.Unauthorized::class.java) {
            runBlocking { registry.getCameraList() }
        }
        assertTrue(err.message!!.contains("CAMERA_UNAUTHORIZED"))
        assertEquals(0, server.requestCount)
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
    }

    @Test
    fun `scenario_2 empty list is no cameras and clears cache and credential store`() {
        seedPreviousState()
        server.enqueue(MockResponse().setBody("""{"code":"20000","data":{"device_list":[]}}"""))
        val cameras = runBlocking { registry.getCameraList() }
        assertTrue(cameras.isEmpty())
        assertEquals(0, dao.count(), "removed cameras must not linger")
        assertNull(store.get("old-cam"), "removed cameras' secrets must not linger")
        assertTrue(recorder.bufferText().contains("result=no-devices"))
    }

    @Test
    fun `scenario_2b code 20200 is an empty list after exactly one request`() {
        seedPreviousState()
        server.enqueue(MockResponse().setBody(DeviceListFixtures.EMPTY_20200))
        val cameras = runBlocking { registry.getCameraList() }
        assertTrue(cameras.isEmpty())
        assertEquals(1, server.requestCount, "no fallback path, no other region")
        assertEquals(0, dao.count())
        assertNull(store.get("old-cam"))
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
    fun `scenario_4 401 maps to CameraUnauthorized and leaves the cache`() {
        seedPreviousState()
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        val err = assertThrows(CameraError.Unauthorized::class.java) {
            runBlocking { registry.getCameraList() }
        }
        assertTrue(err.message!!.contains("CAMERA_UNAUTHORIZED"))
        assertPreviousStateUntouched()
    }

    @Test
    fun `scenario_4b app-level auth codes map to CameraUnauthorized`() {
        for (code in listOf("20201", "20202", "20203")) {
            seedPreviousState()
            server.enqueue(MockResponse().setBody("""{"code":"$code"}"""))
            assertThrows(CameraError.Unauthorized::class.java) {
                runBlocking { registry.getCameraList() }
            }
            assertPreviousStateUntouched()
        }
        assertEquals(3, server.requestCount, "one request per refresh, no retry")
    }

    @Test
    fun `scenario_5 5xx maps to FetchFailed`() {
        seedPreviousState()
        server.enqueue(MockResponse().setResponseCode(503).setBody("oops"))
        val err = assertThrows(CameraError.FetchFailed::class.java) {
            runBlocking { registry.getCameraList() }
        }
        assertTrue(err.message!!.contains("CAMERA_FETCH_FAILED"))
        assertPreviousStateUntouched()
    }

    @Test
    fun `scenario_5b network failure maps to FetchFailed and leaves cache and store`() {
        seedPreviousState()
        // A server that is shut down: connection refused -> IOException.
        val down = MockWebServer().apply { start() }
        val downUrl = down.url("/v1/").toString()
        down.shutdown()
        val downApi = Retrofit.Builder()
            .baseUrl(downUrl)
            .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(YiCloudDeviceApi::class.java)
        registry = CameraRegistry(downApi, dao, FakeSession(), store)
        assertThrows(CameraError.FetchFailed::class.java) {
            runBlocking { registry.getCameraList() }
        }
        assertPreviousStateUntouched()
        assertTrue(recorder.bufferText().contains("result=io-failure"))
    }

    @Test
    fun `scenario_5d malformed body maps to FetchFailed and leaves cache and store`() {
        seedPreviousState()
        server.enqueue(MockResponse().setBody("<html>SYNTH gateway page</html>"))
        assertThrows(CameraError.FetchFailed::class.java) {
            runBlocking { registry.getCameraList() }
        }
        assertPreviousStateUntouched()
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `scenario_5c non-success code maps to ServerRejected and leaves cache and store`() {
        seedPreviousState()
        server.enqueue(MockResponse().setBody("""{"code":"50000"}"""))
        val err = assertThrows(CameraError.ServerRejected::class.java) {
            runBlocking { registry.getCameraList() }
        }
        assertTrue(err.message!!.contains("CAMERA_SERVER_ERROR"))
        assertPreviousStateUntouched()
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `scenario_6 live url resolution returns stored url`() {
        dao.insertCameraRaw(CameraEntity("cam-1", "Living Room", "YI_HOME", true, "https://stream/cam-1", 1, 1))
        val url = runBlocking { registry.getLiveStreamUrl("cam-1") }
        assertEquals("https://stream/cam-1", url)
        recorder.assertMarkerAppeared("BLOCK_RESOLVE_STREAM_URL")
    }

    @Test
    fun `scenario_7 live url missing after refresh throws NoStreamUrl`() {
        server.enqueue(MockResponse().setBody(deviceListBody()))
        runBlocking { registry.getCameraList() }
        assertThrows(CameraError.NoStreamUrl::class.java) {
            runBlocking { registry.getLiveStreamUrl("cam-2") }
        }
        assertThrows(CameraError.NotFound::class.java) {
            runBlocking { registry.getLiveStreamUrl("nope") }
        }
    }

    @Test
    fun `scenario_8 redaction no camera or stream data in logs`() {
        server.enqueue(MockResponse().setBody(deviceListBody()))
        runBlocking { registry.getCameraList() }
        redaction.assertClean(
            recorder.bufferText(),
            extraLiterals = listOf("Living Room", "https://stream/cam-1", "cam-1", "u-1"),
        )
    }

    @Test
    fun `scenario_9 shared rows from the same list are kept and an owned duplicate wins`() {
        val body = """{"code":"20000","data":[""" +
            """{"uid":"A-SYNTH","name":"A","category":1,"type":0},""" +
            """{"uid":"A-SYNTH","name":"A","category":0,"type":0},""" +
            """{"uid":"B-SYNTH","name":"B","category":1,"type":0}]}"""
        server.enqueue(MockResponse().setBody(body))
        val cameras = runBlocking { registry.getCameraList() }
        assertEquals(2, cameras.size)
        assertFalse(cameras.single { it.deviceId == "A-SYNTH" }.shared, "owned wins on duplicates")
        assertTrue(cameras.single { it.deviceId == "B-SYNTH" }.shared, "shared-only keeps shared = true")
        assertTrue(dao.getById("B-SYNTH")!!.shared)
    }

    @Test
    fun `scenario_10 transport from the probe-shaped fixture and the row without id dropped`() {
        server.enqueue(MockResponse().setBody(DeviceListFixtures.V4_LIST))
        val cameras = runBlocking { registry.getCameraList() }
        assertEquals(2, cameras.size)
        assertEquals(TransportType.TNP, cameras.single { it.deviceId == RedactionFixtures.CLOUD_UID }.transport)
        assertEquals(TransportType.TUTK, cameras.single { it.deviceId == RedactionFixtures.TUTK_UID }.transport)
        assertEquals("TNP", dao.getById(RedactionFixtures.CLOUD_UID)!!.transport)
        assertEquals("TUTK", dao.getById(RedactionFixtures.TUTK_UID)!!.transport)
        assertTrue(recorder.bufferText().contains("devices=2 shared=0 tnp=1 tutk=1"))
    }

    @Test
    fun `scenario_11 cachedCameras reads Room only and never throws`() {
        assertTrue(runBlocking { registry.cachedCameras() }.isEmpty(), "fresh DAO")
        server.enqueue(MockResponse().setBody(DeviceListFixtures.V4_LIST))
        runBlocking { registry.getCameraList() }
        val requestsAfterRefresh = server.requestCount
        val cached = runBlocking { registry.cachedCameras() }
        assertEquals(2, cached.size)
        assertEquals(TransportType.TNP, cached.single { it.deviceId == RedactionFixtures.CLOUD_UID }.transport)
        assertEquals(requestsAfterRefresh, server.requestCount, "no network")
        dao.failReads = true
        assertTrue(runBlocking { registry.cachedCameras() }.isEmpty())
    }

    @Test
    fun `scenario_12 concurrent refreshes do not interleave cache writes`() {
        repeat(2) { server.enqueue(MockResponse().setBody(DeviceListFixtures.V4_LIST)) }
        val results = runBlocking {
            (1..2).map { async(Dispatchers.Default) { registry.getCameraList() } }.awaitAll()
        }
        assertTrue(results.all { it.size == 2 })
        assertEquals(2, dao.count())
        assertEquals(2, server.requestCount)
    }
}
