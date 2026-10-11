// FILE: CameraRegistryCredentialsTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Verify P2P credential resolution in CameraRegistry: tnp device_info request bounds, stored values,
//     reuse rules, failure tolerance, logout wipe and the secret-free log of a full refresh.
//   SCOPE: MockWebServer with a path-keyed Dispatcher (list and tnp responses independent of order); synthetic
//     fixtures only (RedactionFixtures + a pinned AES vector).
//   DEPENDS: M-CAMERA-LIST, M-P2P-CREDS, M-HTTP, TestInfrastructure, RedactionFixtures
//   LINKS: V-M-CAMERA-LIST, V-M-P2P-CREDS
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.camera

// START_MODULE_MAP
//   PINNED_HEX - AES-128/ECB/PKCS7 of "synthpw1" under the key CLOUD_UID (same vector as CameraPasswordCipherTest).
//   tnpRow / tutkRow / listOf - synthetic device-list rows in the probe shape.
//   ScriptedServer - path-keyed dispatcher counting list and tnp requests.
//   CameraRegistryCredentialsTest - creds_1..creds_11.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - Created (plan 01-09, CAM-03, SEC-04).
// END_CHANGE_SUMMARY

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.yicamalt.config.ConfigModule
import com.yicamalt.network.AuthProvider
import com.yicamalt.network.HttpClientModule
import com.yicamalt.network.YiRequestSigner
import com.yicamalt.test.RedactionFixtures as F
import com.yicamalt.test.RedactionScanner
import com.yicamalt.test.TraceRecorder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.Retrofit
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Provider

private const val PINNED_HEX = "4a0856af0885dcff0c4dbc3820c21b4d" // SYNTH vector
private const val DID_2 = "TNPXSYN-654321-SYNTH"

private fun tnpRow(did: String? = F.DID, hasPincode: Boolean = false): String {
    val didField = if (did != null) """"did":"$did",""" else ""
    return """{"category":0,$didField"hasPincode":$hasPincode,"ipcParam":{"p2p_encrypt":"true","ssid":"SYNTH"},""" +
        """"model":"yunyi.camera.SYNTH","name":"Living Room SYNTH","online":true,"password":"$PINNED_HEX",""" +
        """"share":false,"type":2,"uid":"${F.CLOUD_UID}"}"""
}

private val TUTK_ROW = """{"category":0,"hasPincode":false,"ipcParam":"{\"p2p_encrypt\":\"false\"}",""" +
    """"model":"yunyi.camera.SYNTH","name":"Front Door SYNTH","online":false,"password":"","share":false,""" +
    """"type":0,"uid":"${F.TUTK_UID}"}"""

private fun listBody(vararg rows: String) = """{"code":"20000","data":[${rows.joinToString(",")}]}"""

/** Dispatcher keyed on the request path; bodies can be swapped between refreshes. */
private class ScriptedServer : Dispatcher() {
    @Volatile var listBody: String = listBody(tnpRow(), TUTK_ROW)
    @Volatile var tnpResponse: () -> MockResponse = { MockResponse().setBody(DeviceListFixtures.TNP_INFO) }
    val paths = CopyOnWriteArrayList<String>()
    val tnpRequests = CopyOnWriteArrayList<RecordedRequest>()

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.requestUrl!!.encodedPath
        paths += path
        return when {
            path.endsWith(DevicePaths.TNP_DEVICE_INFO) -> { tnpRequests += request; tnpResponse() }
            path.endsWith(DevicePaths.LIST_V5) -> MockResponse().setBody(listBody)
            else -> MockResponse().setResponseCode(404)
        }
    }

    fun tnpCount() = tnpRequests.size
}

private class StaticAuth : AuthProvider {
    override suspend fun getAccessToken(): String? = null
    override suspend fun refresh(): String? = null
}

private class TestConfig(private val url: String) : ConfigModule() {
    override fun getApiBaseUrl(): String = url
}

class CameraRegistryCredentialsTest {

    private val server = MockWebServer()
    private val script = ScriptedServer()
    private val recorder = TraceRecorder()
    private val dao = FakeCameraDao()
    private val store = InMemoryP2pCredentialStore()
    private lateinit var registry: CameraRegistry

    @BeforeEach
    fun setUp() {
        server.dispatcher = script
        server.start()
        recorder.plant()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/").toString())
            .addConverterFactory(Json { ignoreUnknownKeys = true; isLenient = true }.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(YiCloudDeviceApi::class.java)
        registry = CameraRegistry(api, dao, FakeSession(F.TOKEN, F.TOKEN_SECRET, F.USER_ID), store)
    }

    @AfterEach
    fun tearDown() {
        recorder.unplant()
        server.shutdown()
    }

    private fun refresh() = runBlocking { registry.getCameraList() }

    @Test
    fun `creds_1 one signed tnp request for the TNP camera only`() {
        refresh()
        assertEquals(1, script.tnpCount())
        val url = script.tnpRequests.single().requestUrl!!
        assertEquals(F.CLOUD_UID, url.queryParameter("uid"))
        assertEquals(F.USER_ID, url.queryParameter("userid"))
        assertEquals("1", url.queryParameter("seq"))
        assertEquals(
            YiRequestSigner.sign(F.TOKEN, F.TOKEN_SECRET, F.USER_ID, extra = listOf("uid" to F.CLOUD_UID)),
            url.queryParameter("hmac"),
        )
        assertFalse(script.paths.any { it.contains("devices/password") }, "PIN password endpoint is never called")
    }

    @Test
    fun `creds_2 stored credentials per transport`() {
        refresh()
        val tnp = store.get(F.CLOUD_UID)!!
        assertEquals(TransportType.TNP, tnp.transport)
        assertEquals(F.DID, tnp.p2pId)
        assertEquals("synthpw1", tnp.password)
        assertEquals(F.INIT_STRING, tnp.initString)
        assertEquals("SYNTHLIC01", tnp.licenseKey)
        assertTrue(tnp.p2pEncrypt)
        assertFalse(tnp.needsPincode)
        val tutk = store.get(F.TUTK_UID)!!
        assertEquals(TransportType.TUTK, tutk.transport)
        assertEquals(CameraPasswordCipher.DEFAULT_PASSWORD, tutk.password)
        assertEquals("888888", tutk.password)
        assertNull(tutk.initString)
        assertNull(tutk.licenseKey)
        assertFalse(tutk.p2pEncrypt)
    }

    @Test
    fun `creds_3 getCredentials reads the store`() {
        refresh()
        assertEquals(store.get(F.CLOUD_UID), registry.getCredentials(F.CLOUD_UID))
        assertNull(registry.getCredentials("unknown-SYNTH"))
    }

    @Test
    fun `creds_4 tnp non-success code keeps the list and nulls the TNP secrets`() {
        script.tnpResponse = { MockResponse().setBody("""{"code":"50000"}""") }
        val cameras = refresh()
        assertEquals(2, cameras.size)
        val tnp = store.get(F.CLOUD_UID)!!
        assertNull(tnp.initString)
        assertNull(tnp.licenseKey)
        assertEquals("synthpw1", tnp.password)
        assertTrue(recorder.bufferText().contains("tnpInfo=failed"))
    }

    @Test
    fun `creds_5 tnp network failure keeps the list and nulls the TNP secrets`() {
        script.tnpResponse = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START) }
        val cameras = refresh()
        assertEquals(2, cameras.size)
        assertNull(store.get(F.CLOUD_UID)!!.licenseKey)
        assertNull(store.get(F.CLOUD_UID)!!.initString)
    }

    @Test
    fun `creds_6 second refresh with the same did reuses the stored license`() {
        refresh()
        refresh()
        assertEquals(1, script.tnpCount(), "no second tnp request")
        assertEquals("SYNTHLIC01", store.get(F.CLOUD_UID)!!.licenseKey)
        assertTrue(recorder.bufferText().contains("tnpInfo=reused"))
    }

    @Test
    fun `creds_7 list without did takes the DID from tnp info and reuses it`() {
        script.listBody = listBody(tnpRow(did = null), TUTK_ROW)
        refresh()
        assertEquals(1, script.tnpCount())
        assertEquals(F.DID, store.get(F.CLOUD_UID)!!.p2pId)
        refresh()
        assertEquals(1, script.tnpCount(), "no did in the list must still reuse")
        assertEquals(F.DID, store.get(F.CLOUD_UID)!!.p2pId)
    }

    @Test
    fun `creds_8 changed did triggers one new tnp request`() {
        refresh()
        script.listBody = listBody(tnpRow(did = DID_2), TUTK_ROW)
        refresh()
        assertEquals(2, script.tnpCount())
    }

    @Test
    fun `creds_9 legitimate empty refresh wipes cache and store`() {
        refresh()
        assertEquals(2, dao.count())
        script.listBody = DeviceListFixtures.EMPTY_20200
        val cameras = refresh()
        assertTrue(cameras.isEmpty())
        assertEquals(0, dao.count())
        assertNull(store.get(F.CLOUD_UID))
        assertNull(store.get(F.TUTK_UID))
    }

    @Test
    fun `creds_10 pincode camera has no password and no PIN request`() {
        script.listBody = listBody(tnpRow(hasPincode = true))
        refresh()
        val c = store.get(F.CLOUD_UID)!!
        assertTrue(c.needsPincode)
        assertNull(c.password)
        assertFalse(script.paths.any { it.contains("devices/password") })
    }

    @Test
    fun `creds_11 Room rows carry transport and no secret, clearAll wipes both`() {
        refresh()
        val rows = dao.getAll()
        assertEquals(setOf("TNP", "TUTK"), rows.map { it.transport }.toSet())
        val rowText = rows.joinToString { it.toString() }
        for (secret in listOf("synthpw1", F.INIT_STRING, "SYNTHLIC01", F.DID, PINNED_HEX)) {
            assertFalse(rowText.contains(secret), "Room row must not hold a secret")
        }
        runBlocking { registry.clearAll() }
        assertEquals(0, dao.count())
        assertNull(store.get(F.CLOUD_UID))
        assertNull(store.get(F.TUTK_UID))
        recorder.assertMarkerAppeared("BLOCK_CLEAR_CAMERA_DATA")
    }

    @Test
    fun `creds_12 full refresh through the production HTTP client logs no secret`() {
        val http = HttpClientModule(TestConfig(server.url("/").toString()), Provider<AuthProvider> { StaticAuth() })
        val api = http.createRetrofit().create(YiCloudDeviceApi::class.java)
        registry = CameraRegistry(api, dao, FakeSession(F.TOKEN, F.TOKEN_SECRET, F.USER_ID), store)
        refresh()
        refresh()
        assertEquals(1, script.tnpCount())
        assertNotNull(store.get(F.CLOUD_UID)!!.licenseKey)
        val logs = recorder.bufferText()
        assertTrue(logs.contains("/v4/tnp/device_info"), "HTTP log lines are part of the scanned buffer")
        recorder.assertMarkerAppeared("BLOCK_RESOLVE_P2P_CREDENTIALS")
        RedactionScanner().assertClean(
            logs,
            extraLiterals = F.ALL_SECRETS + listOf("synthpw1", "SYNTHLIC01", F.USER_ID, PINNED_HEX, DID_2),
        )
    }
}
