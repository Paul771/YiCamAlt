// FILE: CameraRegistryCredentialsTest.kt
// VERSION: 0.3.0
// START_MODULE_CONTRACT
//   PURPOSE: Verify P2P credential resolution in CameraRegistry: vendor transport rule (device_info for every
//     unverified camera), request bounds, stored values, reuse rules, failure tolerance, logout wipe and the
//     secret-free log of a full refresh.
//   SCOPE: MockWebServer with a path-keyed Dispatcher (list and tnp responses independent of order; device_info
//     answered per `uid` query parameter); synthetic
//     fixtures only (RedactionFixtures + a pinned AES vector).
//   DEPENDS: M-CAMERA-LIST, M-P2P-CREDS, M-HTTP, TestInfrastructure, RedactionFixtures
//   LINKS: V-M-CAMERA-LIST, V-M-P2P-CREDS
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.camera

// START_MODULE_MAP
//   PINNED_HEX - AES-128/ECB/PKCS7 of "synthpw1" under the key CLOUD_UID (same vector as CameraPasswordCipherTest).
//   tnpRow / tutkRow / listBody - synthetic device-list rows in the probe shape (list `type` configurable).
//   ScriptedServer - path-keyed dispatcher counting list and tnp requests; device_info answered per uid.
//   phase1Record - a Phase 1 store record (transport guessed from the list type, transportVerified = false).
//   CameraRegistryCredentialsTest - creds_1..creds_12 (11b..11e: logout cleanup and start-up purge).
//   authWithRegistry - AuthRepository whose logout runs the registry cleanup (Hilt wiring in miniature).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.3.0 - GSD plan 02-01: vendor transport rule. device_info is asked for EVERY unverified camera
//     (creds_1 now expects two requests); list type/did never decide (creds_2b/2c); rejected / incomplete /
//     network-failure outcomes (creds_4..4c, creds_5..5c); verified records reused (creds_6, creds_8);
//     Phase 1 records asked exactly once (creds_7b); credentialsFor port (creds_3); via=/listType= markers.
//   PREV_CHANGE: v0.2.0 - SEC-04 fail-closed cleanup: logout through a real AuthRepository wipes cache + store
//     even when the calling coroutine is cancelled (creds_11b); a refresh in flight writes nothing after logout
//     (creds_11c); start-up purge without a session (creds_11d/e).
//   PREV_CHANGE: v0.1.0 - Created (plan 01-09, CAM-03, SEC-04).
// END_CHANGE_SUMMARY

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.yicamalt.config.ConfigModule
import com.yicamalt.network.AuthProvider
import com.yicamalt.network.HttpClientModule
import com.yicamalt.network.YiRequestSigner
import com.yicamalt.test.RedactionFixtures as F
import com.yicamalt.test.RedactionScanner
import com.yicamalt.test.TraceRecorder
import com.yicamalt.auth.AuthMethod
import com.yicamalt.auth.AuthRepository
import com.yicamalt.auth.AuthSession
import com.yicamalt.auth.LogoutCleanup
import com.yicamalt.auth.RegionTestClock
import com.yicamalt.auth.RegionTestStore
import com.yicamalt.auth.ScriptedAuthApi
import com.yicamalt.auth.SessionSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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

private fun tnpRow(did: String? = F.DID, hasPincode: Boolean = false, type: Int = 2): String {
    val didField = if (did != null) """"did":"$did",""" else ""
    return """{"category":0,$didField"hasPincode":$hasPincode,"ipcParam":{"p2p_encrypt":"true","ssid":"SYNTH"},""" +
        """"model":"yunyi.camera.SYNTH","name":"Living Room SYNTH","online":true,"password":"$PINNED_HEX",""" +
        """"share":false,"type":$type,"uid":"${F.CLOUD_UID}"}"""
}

private fun tutkRow(type: Int = 0) = """{"category":0,"hasPincode":false,"ipcParam":"{\"p2p_encrypt\":\"false\"}",""" +
    """"model":"yunyi.camera.SYNTH","name":"Front Door SYNTH","online":false,"password":"","share":false,""" +
    """"type":$type,"uid":"${F.TUTK_UID}"}"""

private val TUTK_ROW = tutkRow()

private fun listBody(vararg rows: String) = """{"code":"20000","data":[${rows.joinToString(",")}]}"""

/** Dispatcher keyed on the request path; bodies can be swapped between refreshes. */
private class ScriptedServer : Dispatcher() {
    @Volatile var listBody: String = listBody(tnpRow(), TUTK_ROW)
    @Volatile var listDelayMs: Long = 0
    /** device_info answer per `uid` query parameter: the TNP camera gets TNP_INFO, the other a non-TNP DID. */
    @Volatile var tnpResponse: (String?) -> MockResponse = { uid ->
        when (uid) {
            F.CLOUD_UID -> MockResponse().setBody(DeviceListFixtures.TNP_INFO)
            F.TUTK_UID -> MockResponse().setBody(DeviceListFixtures.TNP_INFO_NOT_TNP)
            else -> MockResponse().setBody(DeviceListFixtures.TNP_INFO_REJECTED)
        }
    }
    val paths = CopyOnWriteArrayList<String>()
    val tnpRequests = CopyOnWriteArrayList<RecordedRequest>()

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.requestUrl!!.encodedPath
        paths += path
        return when {
            path.endsWith(DevicePaths.TNP_DEVICE_INFO) -> {
                tnpRequests += request
                tnpResponse(request.requestUrl!!.queryParameter("uid"))
            }
            path.endsWith(DevicePaths.LIST_V5) -> MockResponse().setBody(listBody)
                .setBodyDelay(listDelayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            else -> MockResponse().setResponseCode(404)
        }
    }

    fun tnpCount() = tnpRequests.size

    fun tnpCountFor(uid: String) = tnpRequests.count { it.requestUrl!!.queryParameter("uid") == uid }
}

/** A record written by Phase 1: transport guessed from the list type, never verified by device_info. */
private fun phase1Record(uid: String, transport: TransportType) = P2pCredentials(
    deviceId = uid, p2pId = uid, transport = transport, password = "synthpw1", initString = null,
    licenseKey = null, p2pEncrypt = false, wakeup = false, needsPincode = false,
)

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

    // START_BLOCK_VENDOR_RULE_SCENARIOS
    @Test
    fun `creds_1 one signed device_info request per camera, whatever the list type`() {
        refresh()
        assertEquals(2, script.tnpCount())
        for (uid in listOf(F.CLOUD_UID, F.TUTK_UID)) {
            val url = script.tnpRequests.single { it.requestUrl!!.queryParameter("uid") == uid }.requestUrl!!
            assertEquals(F.USER_ID, url.queryParameter("userid"))
            assertEquals("1", url.queryParameter("seq"))
            assertEquals(
                YiRequestSigner.sign(F.TOKEN, F.TOKEN_SECRET, F.USER_ID, extra = listOf("uid" to uid)),
                url.queryParameter("hmac"),
            )
        }
        assertFalse(script.paths.any { it.contains("devices/password") }, "PIN password endpoint is never called")
    }

    @Test
    fun `creds_2 stored credentials follow the vendor rule`() {
        refresh()
        val tnp = store.get(F.CLOUD_UID)!!
        assertEquals(TransportType.TNP, tnp.transport)
        assertEquals(F.DID, tnp.p2pId)
        assertEquals("synthpw1", tnp.password)
        assertEquals(F.INIT_STRING, tnp.initString)
        assertEquals("SYNTHLIC01", tnp.licenseKey)
        assertTrue(tnp.p2pEncrypt)
        assertFalse(tnp.needsPincode)
        assertTrue(tnp.transportVerified)
        val tutk = store.get(F.TUTK_UID)!!
        assertEquals(TransportType.TUTK, tutk.transport, "mixed-case uid without a TNP DID is TUTK")
        assertEquals(F.TUTK_UID, tutk.p2pId, "non-TNP cameras dial the cloud uid")
        assertEquals(CameraPasswordCipher.DEFAULT_PASSWORD, tutk.password)
        assertEquals("888888", tutk.password)
        assertNull(tutk.initString)
        assertNull(tutk.licenseKey)
        assertFalse(tutk.p2pEncrypt)
        assertTrue(tutk.transportVerified)
        val logs = recorder.bufferText()
        assertFalse(logs.contains("mismatch="))
        assertTrue(logs.contains("transport=TNP via=deviceInfo listType=2 tnpInfo=ok"))
        assertTrue(logs.contains("transport=TUTK via=uid listType=0 tnpInfo=notTnp"))
        assertTrue(logs.contains("verified=2"))
    }

    @Test
    fun `creds_2b swapped list types give the same result`() {
        script.listBody = listBody(tnpRow(type = 0), tutkRow(type = 2))
        refresh()
        assertEquals(TransportType.TNP, store.get(F.CLOUD_UID)!!.transport)
        assertEquals(F.DID, store.get(F.CLOUD_UID)!!.p2pId)
        assertEquals(TransportType.TUTK, store.get(F.TUTK_UID)!!.transport)
        assertEquals(F.TUTK_UID, store.get(F.TUTK_UID)!!.p2pId)
    }

    @Test
    fun `creds_2c TNP DID for the camera whose list type is 0 makes it TNP`() {
        script.tnpResponse = { MockResponse().setBody(DeviceListFixtures.TNP_INFO) }
        refresh()
        val c = store.get(F.TUTK_UID)!!
        assertEquals(TransportType.TNP, c.transport)
        assertEquals(F.DID, c.p2pId)
        assertEquals("SYNTHLIC01", c.licenseKey)
        assertTrue(recorder.bufferText().contains("transport=TNP via=deviceInfo listType=0 tnpInfo=ok"))
    }

    @Test
    fun `creds_3 getCredentials and credentialsFor read the store`() {
        refresh()
        assertEquals(store.get(F.CLOUD_UID), registry.getCredentials(F.CLOUD_UID))
        assertEquals(registry.getCredentials(F.CLOUD_UID), registry.credentialsFor(F.CLOUD_UID))
        assertEquals(registry.getCredentials(F.TUTK_UID), registry.credentialsFor(F.TUTK_UID))
        assertNull(registry.getCredentials("unknown-SYNTH"))
        assertNull(registry.credentialsFor("unknown-SYNTH"))
    }

    @Test
    fun `creds_4 rejected device_info falls back to the uid rule and counts as verified`() {
        script.tnpResponse = { MockResponse().setBody(DeviceListFixtures.TNP_INFO_REJECTED) }
        val cameras = refresh()
        assertEquals(2, cameras.size)
        val c = store.get(F.CLOUD_UID)!!
        assertEquals(TransportType.TUTK, c.transport, "mixed-case cloud uid")
        assertEquals(F.CLOUD_UID, c.p2pId)
        assertNull(c.initString)
        assertNull(c.licenseKey)
        assertEquals("synthpw1", c.password)
        assertTrue(c.transportVerified)
        assertTrue(recorder.bufferText().contains("via=uid listType=2 tnpInfo=rejected"))
    }

    @Test
    fun `creds_4b TNP DID without InitString or License is incomplete and asked again`() {
        script.tnpResponse = { uid ->
            if (uid == F.CLOUD_UID) MockResponse().setBody(DeviceListFixtures.TNP_INFO_INCOMPLETE)
            else MockResponse().setBody(DeviceListFixtures.TNP_INFO_NOT_TNP)
        }
        refresh()
        val c = store.get(F.CLOUD_UID)!!
        assertEquals(TransportType.TNP, c.transport)
        assertEquals(F.DID, c.p2pId)
        assertNull(c.initString)
        assertNull(c.licenseKey)
        assertFalse(c.transportVerified)
        assertTrue(recorder.bufferText().contains("tnpInfo=incomplete"))
        refresh()
        assertEquals(2, script.tnpCountFor(F.CLOUD_UID), "incomplete camera is asked again")
        assertEquals(1, script.tnpCountFor(F.TUTK_UID), "verified camera is not")
    }

    @Test
    fun `creds_4c incomplete answer keeps a stored verified record`() {
        val verified = P2pCredentials(
            deviceId = F.CLOUD_UID, p2pId = DID_2, transport = TransportType.TNP, password = "synthpw1",
            initString = null, licenseKey = null, p2pEncrypt = true, wakeup = false, needsPincode = false,
            transportVerified = true,
        )
        store.replaceAll(listOf(verified))
        script.tnpResponse = { MockResponse().setBody(DeviceListFixtures.TNP_INFO_INCOMPLETE) }
        refresh()
        val c = store.get(F.CLOUD_UID)!!
        assertEquals(DID_2, c.p2pId)
        assertEquals(TransportType.TNP, c.transport)
        assertTrue(c.transportVerified)
    }

    @Test
    fun `creds_5 network failure without a stored record leaves the camera UNKNOWN and unverified`() {
        script.tnpResponse = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START) }
        val cameras = refresh()
        assertEquals(2, cameras.size, "the list refresh still succeeds")
        for (uid in listOf(F.CLOUD_UID, F.TUTK_UID)) {
            val c = store.get(uid)!!
            assertEquals(TransportType.UNKNOWN, c.transport)
            assertEquals(uid, c.p2pId)
            assertFalse(c.transportVerified)
            assertNull(c.licenseKey)
            assertNull(c.initString)
        }
        assertTrue(recorder.bufferText().contains("transport=UNKNOWN via=none listType=0 tnpInfo=failed"))
    }

    @Test
    fun `creds_5b network failure with a stored verified record asks nothing and keeps it`() {
        refresh()
        val before = script.tnpCount()
        val kept = store.get(F.CLOUD_UID)
        script.tnpResponse = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START) }
        refresh()
        assertEquals(before, script.tnpCount())
        assertEquals(kept, store.get(F.CLOUD_UID))
        assertEquals(TransportType.TUTK, store.get(F.TUTK_UID)!!.transport)
    }

    @Test
    fun `creds_5c network failure never keeps a Phase 1 guess`() {
        store.replaceAll(listOf(phase1Record(F.TUTK_UID, TransportType.TUTK)))
        script.tnpResponse = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START) }
        refresh()
        val c = store.get(F.TUTK_UID)!!
        assertEquals(TransportType.UNKNOWN, c.transport, "TUTK-by-type guess must not survive a network error")
        assertFalse(c.transportVerified)
        assertTrue(recorder.bufferText().contains("transport=UNKNOWN via=none listType=0 tnpInfo=failed"))
    }

    @Test
    fun `creds_6 second refresh after a verified first asks nothing`() {
        refresh()
        assertEquals(2, script.tnpCount())
        refresh()
        assertEquals(2, script.tnpCount(), "zero device_info requests in steady state")
        assertEquals("SYNTHLIC01", store.get(F.CLOUD_UID)!!.licenseKey)
        val reused = Regex("tnpInfo=reused").findAll(recorder.bufferText()).count()
        assertEquals(2, reused)
    }

    @Test
    fun `creds_7 list without did takes the DID from device_info and reuses it`() {
        script.listBody = listBody(tnpRow(did = null), TUTK_ROW)
        refresh()
        assertEquals(2, script.tnpCount())
        assertEquals(F.DID, store.get(F.CLOUD_UID)!!.p2pId)
        refresh()
        assertEquals(2, script.tnpCount(), "no did in the list must still reuse")
        assertEquals(F.DID, store.get(F.CLOUD_UID)!!.p2pId)
    }

    @Test
    fun `creds_7b Phase 1 store - every camera asked exactly once, then never`() {
        store.replaceAll(
            listOf(phase1Record(F.CLOUD_UID, TransportType.TNP), phase1Record(F.TUTK_UID, TransportType.TUTK)),
        )
        refresh()
        assertEquals(1, script.tnpCountFor(F.CLOUD_UID))
        assertEquals(1, script.tnpCountFor(F.TUTK_UID))
        refresh()
        assertEquals(2, script.tnpCount())
        assertTrue(store.get(F.CLOUD_UID)!!.transportVerified)
        assertTrue(store.get(F.TUTK_UID)!!.transportVerified)
    }

    @Test
    fun `creds_8 a changed list did does not trigger a new request - the list did never decides`() {
        refresh()
        script.listBody = listBody(tnpRow(did = DID_2), TUTK_ROW)
        refresh()
        assertEquals(2, script.tnpCount())
        assertEquals(F.DID, store.get(F.CLOUD_UID)!!.p2pId, "the dialled id is the device_info DID")
    }
    // END_BLOCK_VENDOR_RULE_SCENARIOS

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
    fun `creds_11 Room rows carry transport and no secret, logout cleanup wipes both`() {
        refresh()
        val rows = dao.getAll()
        assertEquals(setOf("TNP", "TUTK"), rows.map { it.transport }.toSet())
        val rowText = rows.joinToString { it.toString() }
        for (secret in listOf("synthpw1", F.INIT_STRING, "SYNTHLIC01", F.DID, PINNED_HEX)) {
            assertFalse(rowText.contains(secret), "Room row must not hold a secret")
        }
        registry.clearSignedOutData()
        assertEquals(0, dao.count())
        assertNull(store.get(F.CLOUD_UID))
        assertNull(store.get(F.TUTK_UID))
        recorder.assertMarkerAppeared("BLOCK_CLEAR_CAMERA_DATA")
    }

    // START_BLOCK_LOGOUT_CLEANUP_SCENARIOS
    private val signedIn = AuthSession(F.TOKEN, F.TOKEN_SECRET, Long.MAX_VALUE, F.USER_ID, AuthMethod.PASSWORD)

    /** Real AuthRepository whose logout runs the registry's cleanup, as wired by Hilt. */
    private fun authWithRegistry(): Pair<AuthRepository, CameraRegistry> {
        lateinit var reg: CameraRegistry
        val auth = AuthRepository(
            api = ScriptedAuthApi(selector = null),
            store = RegionTestStore(signedIn),
            clock = RegionTestClock(),
            logoutCleanup = Provider<LogoutCleanup> { reg },
        )
        val api = Retrofit.Builder()
            .baseUrl(server.url("/").toString())
            .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(YiCloudDeviceApi::class.java)
        reg = CameraRegistry(api, dao, auth, store)
        return auth to reg
    }

    @Test
    fun `creds_11b logout from a coroutine cancelled right away still wipes cache and store`() {
        val (auth, reg) = authWithRegistry()
        runBlocking { reg.getCameraList() }
        assertEquals(2, dao.count())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            auth.logout()
            awaitCancellation()
        }
        scope.cancel()
        assertNull(auth.getSession())
        assertEquals(0, dao.count())
        assertNull(store.get(F.CLOUD_UID))
        assertNull(store.get(F.TUTK_UID))
        recorder.assertMarkerAppeared("BLOCK_LOGOUT_CLEANUP")
    }

    @Test
    fun `creds_11c logout during a refresh in flight - nothing is written afterwards`() {
        val (auth, reg) = authWithRegistry()
        script.listDelayMs = 400
        val failure = runBlocking {
            val refresh = async(Dispatchers.Default) { runCatching { reg.getCameraList() } }
            withContext(Dispatchers.Default) { while (script.paths.isEmpty()) delay(5) }
            auth.logout()
            refresh.await().exceptionOrNull()
        }
        assertTrue(failure is CameraError.Unauthorized, "refresh ending after logout must not succeed: $failure")
        assertEquals(0, dao.count())
        assertNull(store.get(F.CLOUD_UID))
        assertTrue(recorder.bufferText().contains("result=session-ended written=false"))
    }

    @Test
    fun `creds_11d no session but leftover data - start-up purge wipes it`() {
        refresh()
        assertEquals(2, dao.count())
        val signedOut = CameraRegistry(
            Retrofit.Builder().baseUrl(server.url("/").toString())
                .addConverterFactory(Json.asConverterFactory("application/json".toMediaType()))
                .build().create(YiCloudDeviceApi::class.java),
            dao,
            object : SessionSource { override fun currentSession(): AuthSession? = null },
            store,
        )
        val requestsBefore = server.requestCount
        assertTrue(signedOut.purgeIfSignedOut())
        assertEquals(0, dao.count())
        assertNull(store.get(F.CLOUD_UID))
        assertNull(store.get(F.TUTK_UID))
        assertEquals(requestsBefore, server.requestCount, "purge is local only")
    }

    @Test
    fun `creds_11e start-up purge leaves data alone while signed in`() {
        refresh()
        assertFalse(registry.purgeIfSignedOut())
        assertEquals(2, dao.count())
        assertNotNull(store.get(F.CLOUD_UID))
    }
    // END_BLOCK_LOGOUT_CLEANUP_SCENARIOS

    @Test
    fun `creds_12 full refresh through the production HTTP client logs no secret`() {
        val http = HttpClientModule(TestConfig(server.url("/").toString()), Provider<AuthProvider> { StaticAuth() })
        val api = http.createRetrofit().create(YiCloudDeviceApi::class.java)
        registry = CameraRegistry(api, dao, FakeSession(F.TOKEN, F.TOKEN_SECRET, F.USER_ID), store)
        refresh()
        refresh()
        assertEquals(2, script.tnpCount())
        assertNotNull(store.get(F.CLOUD_UID)!!.licenseKey)
        val logs = recorder.bufferText()
        assertTrue(logs.contains("/v4/tnp/device_info"), "HTTP log lines are part of the scanned buffer")
        recorder.assertMarkerAppeared("BLOCK_RESOLVE_P2P_CREDENTIALS")
        assertFalse(logs.contains("mismatch="))
        assertTrue(logs.contains("via="))
        assertTrue(logs.contains("listType="))
        RedactionScanner().assertClean(
            logs,
            extraLiterals = F.ALL_SECRETS + listOf("synthpw1", "SYNTHLIC01", F.USER_ID, PINNED_HEX, DID_2),
        )
    }
}
