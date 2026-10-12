// FILE: AuthRegionDiscoveryTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Pin the login region discovery by device list and its hard request bounds (CAM-02, plan 01-08 addendum).
//   SCOPE: discovery outcomes (first non-empty region wins, none => first successful login), stop rules
//     (wrong password, network error), manual server, bounds 3 logins + 3 list checks per login call,
//     never on refresh/app start, value-free logs; DeviceListCounter; HttpDeviceListCheck wire shape.
//   DEPENDS: M-AUTH, M-CONFIG, M-HTTP, TestInfrastructure
//   LINKS: V-M-AUTH, SEC-01
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.auth

// START_MODULE_MAP
//   AuthRegionDiscoveryTest - discovery_1..discovery_14, counter_1, http_check_1..2.
//   repo / allOk - builders over the scripted fakes from AuthRegionFakes.kt.
//   assertBounds - per-call bound assertion (<= 3 logins, <= 3 checks).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - Initial. Addendum scenarios: US empty -> EU devices (2+2); all empty (3+3, first kept);
//     first non-empty (1+1); wrong password on the second region (first session kept); manual server (1+0).
// END_CHANGE_SUMMARY

import com.yicamalt.config.ConfigModule
import com.yicamalt.config.InMemorySettingsStore
import com.yicamalt.config.YiRegion
import com.yicamalt.network.HttpClientModule
import com.yicamalt.network.YiRequestSigner
import com.yicamalt.test.RedactionScanner
import com.yicamalt.test.TraceRecorder
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import javax.inject.Provider

class AuthRegionDiscoveryTest {

    private val recorder = TraceRecorder()
    private val redaction = RedactionScanner()
    private val clock = RegionTestClock()
    private lateinit var store: RegionTestStore
    private lateinit var selector: FakeRegionSelector

    private val empty = ScriptedDeviceCheck.EMPTY
    private val two = ScriptedDeviceCheck.TWO
    private val allOk = mapOf<String, Any>("US" to ok("US"), "EU" to ok("EU"), "SG" to ok("SG"))

    @BeforeEach fun setUp() {
        recorder.plant()
        store = RegionTestStore()
        selector = FakeRegionSelector(YiRegion.US)
    }

    @AfterEach fun tearDown() { recorder.unplant() }

    private fun repo(api: ScriptedAuthApi, check: DeviceListCheckPort) = AuthRepository(api, store, clock, selector, check)

    private fun login(r: AuthRepository) = runBlocking { r.login(Synth.EMAIL, Synth.PASSWORD) }

    private fun assertBounds(api: ScriptedAuthApi, check: ScriptedDeviceCheck) {
        assertTrue(api.loginCalls <= MAX_LOGIN_ATTEMPTS, "logins=${api.loginCalls}")
        assertTrue(check.calls <= MAX_DISCOVERY_CALLS, "lists=${check.calls}")
        recorder.assertMaxRetries("BLOCK_REGION_FALLBACK", 3)
        recorder.assertMaxRetries("BLOCK_REGION_DISCOVERY", 3)
    }

    private fun assertLogsValueFree() = redaction.assertClean(
        recorder.bufferText(),
        extraLiterals = listOf(Synth.EMAIL, Synth.PASSWORD, Synth.USER_ID, "tok-", "sec-"),
    ).also {
        // Region lines carry the region NAME only: never a URL or a host.
        recorder.all().filter { it.message.contains("BLOCK_REGION_") || it.message.contains("BLOCK_SELECT_REGION") }
            .forEach { assertFalse(it.message.contains("://") || it.message.contains("xiaoyi"), it.message) }
    }

    // START_BLOCK_DISCOVERY_OUTCOMES
    @Test
    fun `discovery_1 US empty then EU has devices ends on EU with 2 logins and 2 list calls`() {
        val api = ScriptedAuthApi(selector, allOk)
        val check = ScriptedDeviceCheck(selector, mapOf("US" to empty, "EU" to two, "SG" to two))
        val session = login(repo(api, check))

        assertEquals(listOf("US", "EU"), api.loginRegions)
        assertEquals(listOf("US", "EU"), check.regions)
        assertEquals(2, api.loginCalls)
        assertEquals(2, check.calls)
        assertEquals(YiRegion.EU, selector.currentRegion())
        assertEquals(Synth.token("EU"), session.accessToken)
        assertEquals(Synth.token("EU"), store.getSession()?.accessToken)
        assertEquals(1, store.writes, "only the kept session is persisted")
        assertEquals(listOf(Synth.token("US"), Synth.token("EU")), check.tokens, "each check is signed by its own region session")
        recorder.assertSequence("BLOCK_REGION_FALLBACK", "BLOCK_REGION_DISCOVERY", "BLOCK_REGION_FALLBACK", "BLOCK_REGION_DISCOVERY", "BLOCK_REGION_SELECTED", "BLOCK_STORE_TOKEN")
        assertTrue(recorder.bufferText().contains("region=EU reason=devices logins=2 lists=2"))
        assertBounds(api, check)
        assertLogsValueFree()
    }

    @Test
    fun `discovery_2 all three regions empty stays on the first with exactly 3 logins and 3 list calls`() {
        val api = ScriptedAuthApi(selector, allOk)
        val check = ScriptedDeviceCheck(selector, mapOf("US" to empty, "EU" to empty, "SG" to empty))
        val session = login(repo(api, check))

        assertEquals(listOf("US", "EU", "SG"), api.loginRegions)
        assertEquals(listOf("US", "EU", "SG"), check.regions)
        assertEquals(3, api.loginCalls)
        assertEquals(3, check.calls)
        assertEquals(YiRegion.US, selector.currentRegion(), "no region has devices: the first successful login is kept")
        assertEquals(Synth.token("US"), session.accessToken)
        assertEquals(Synth.token("US"), store.getSession()?.accessToken)
        assertEquals(1, store.writes)
        assertTrue(recorder.bufferText().contains("region=US reason=first-login logins=3 lists=3"))
        assertBounds(api, check)
        assertLogsValueFree()
    }

    @Test
    fun `discovery_3 first region non-empty makes 1 login and 1 list call`() {
        val api = ScriptedAuthApi(selector, allOk)
        val check = ScriptedDeviceCheck(selector, mapOf("US" to two))
        val session = login(repo(api, check))

        assertEquals(1, api.loginCalls)
        assertEquals(1, check.calls)
        assertEquals(YiRegion.US, selector.currentRegion())
        assertTrue(selector.selections.isEmpty(), "no region switch at all")
        assertTrue(selector.restores.isEmpty())
        assertEquals(Synth.token("US"), session.accessToken)
        assertBounds(api, check)
        assertLogsValueFree()
    }

    @Test
    fun `discovery_4 wrong password on the second region stops and keeps the first session`() {
        val api = ScriptedAuthApi(selector, mapOf("US" to ok("US"), "EU" to rejected("20261"), "SG" to ok("SG")))
        val check = ScriptedDeviceCheck(selector, mapOf("US" to empty, "SG" to two))
        val session = login(repo(api, check))

        assertEquals(listOf("US", "EU"), api.loginRegions, "20261 stops the loop at once: SG is never asked")
        assertEquals(1, check.calls)
        assertEquals(YiRegion.US, selector.currentRegion())
        assertEquals(Synth.token("US"), session.accessToken)
        assertEquals(Synth.token("US"), store.getSession()?.accessToken)
        assertBounds(api, check)
        assertLogsValueFree()
    }

    @Test
    fun `discovery_5 manual server makes 1 login and 0 discovery calls`() {
        // Real ConfigModule: the user typed a server that IS one of the gateways.
        val config = ConfigModule(InMemorySettingsStore())
        config.setManualBaseUrl("https://gw-eu.xiaoyi.com/")
        val api = ScriptedAuthApi(config, mapOf("custom" to ok("EU")))
        val check = ScriptedDeviceCheck(config, mapOf("custom" to empty, "EU" to empty))
        val session = runBlocking { AuthRepository(api, store, clock, config, check).login(Synth.EMAIL, Synth.PASSWORD) }

        assertEquals(1, api.loginCalls)
        assertEquals(0, check.calls)
        assertEquals("https://gw-eu.xiaoyi.com/", config.getApiBaseUrl(), "the manual server is never changed")
        assertTrue(config.isManualBaseUrl())
        assertEquals(Synth.token("EU"), session.accessToken)
        recorder.assertMarkerAbsent("BLOCK_REGION_DISCOVERY")
        recorder.assertMarkerAbsent("BLOCK_SELECT_REGION")
    }

    @Test
    fun `discovery_6 manual server with account-not-found makes exactly 1 login and no hop`() {
        val config = ConfigModule(InMemorySettingsStore())
        config.setManualBaseUrl("https://gw-sg.xiaoyi.com/")
        val api = ScriptedAuthApi(config, mapOf("custom" to rejected("20253")))
        val check = ScriptedDeviceCheck(config, emptyMap())
        assertThrows(AuthError.InvalidCredentials::class.java) {
            runBlocking { AuthRepository(api, store, clock, config, check).login(Synth.EMAIL, Synth.PASSWORD) }
        }
        assertEquals(1, api.loginCalls)
        assertEquals(0, check.calls)
        assertEquals("https://gw-sg.xiaoyi.com/", config.getApiBaseUrl())
    }

    @Test
    fun `discovery_7 list code 20000 with an empty array and unknown codes count as no devices`() {
        val api = ScriptedAuthApi(selector, allOk)
        val check = ScriptedDeviceCheck(
            selector,
            mapOf("US" to DeviceListCheck("20000", 0), "EU" to DeviceListCheck("20202", 0), "SG" to DeviceListCheck(null, 0)),
        )
        login(repo(api, check))
        assertEquals(3, api.loginCalls)
        assertEquals(3, check.calls)
        assertEquals(YiRegion.US, selector.currentRegion())
        assertBounds(api, check)
    }

    @Test
    fun `discovery_8 account missing on the first region then found with devices on the second`() {
        val api = ScriptedAuthApi(selector, mapOf("US" to rejected("20253"), "EU" to ok("EU")))
        val check = ScriptedDeviceCheck(selector, mapOf("EU" to two))
        val session = login(repo(api, check))
        assertEquals(listOf("US", "EU"), api.loginRegions)
        assertEquals(listOf("EU"), check.regions)
        assertEquals(YiRegion.EU, selector.currentRegion())
        assertEquals(Synth.token("EU"), session.accessToken)
        assertBounds(api, check)
    }
    // END_BLOCK_DISCOVERY_OUTCOMES

    // START_BLOCK_DISCOVERY_STOP_RULES
    @Test
    fun `discovery_9 network error on a list call stops the loop and keeps the last good session`() {
        val api = ScriptedAuthApi(selector, allOk)
        val check = ScriptedDeviceCheck(selector, mapOf("US" to empty, "EU" to IOException("synthetic"), "SG" to two))
        val session = login(repo(api, check))

        assertEquals(listOf("US", "EU"), api.loginRegions, "no retry and no further region after the error")
        assertEquals(2, check.calls)
        assertEquals(YiRegion.EU, selector.currentRegion())
        assertEquals(Synth.token("EU"), session.accessToken)
        assertEquals(Synth.token("EU"), store.getSession()?.accessToken)
        assertTrue(recorder.bufferText().contains("reason=network-error"))
        assertBounds(api, check)
        assertLogsValueFree()
    }

    @Test
    fun `discovery_10 network error on a later login stops the loop and keeps the last good session`() {
        val api = ScriptedAuthApi(selector, mapOf("US" to ok("US"), "EU" to IOException("synthetic"), "SG" to ok("SG")))
        val check = ScriptedDeviceCheck(selector, mapOf("US" to empty, "SG" to two))
        val session = login(repo(api, check))

        assertEquals(listOf("US", "EU"), api.loginRegions)
        assertEquals(1, check.calls)
        assertEquals(YiRegion.US, selector.currentRegion(), "back on the region of the kept session")
        assertEquals(Synth.token("US"), session.accessToken)
        assertBounds(api, check)
    }

    @Test
    fun `discovery_11 network error on the first list call keeps that login and makes no further request`() {
        val api = ScriptedAuthApi(selector, allOk)
        val check = ScriptedDeviceCheck(selector, mapOf("US" to IOException("synthetic")))
        val session = login(repo(api, check))
        assertEquals(1, api.loginCalls)
        assertEquals(1, check.calls)
        assertEquals(YiRegion.US, selector.currentRegion())
        assertEquals(Synth.token("US"), session.accessToken)
    }
    // END_BLOCK_DISCOVERY_STOP_RULES

    // START_BLOCK_DISCOVERY_BOUNDS
    @Test
    fun `discovery_12 every login call is bounded to 3 logins and 3 list calls and the next call starts on the kept region`() {
        val api = ScriptedAuthApi(selector, allOk)
        val answers = mutableMapOf<String, Any>("US" to empty, "EU" to empty, "SG" to empty)
        val check = ScriptedDeviceCheck(selector, answers)
        val r = repo(api, check)

        // Worst case repeated: each press costs exactly 3 + 3, never more.
        repeat(4) { press ->
            login(r)
            assertEquals(3 * (press + 1), api.loginCalls)
            assertEquals(3 * (press + 1), check.calls)
            assertEquals(YiRegion.US, selector.currentRegion())
        }
        // Devices appear on SG: found on the third region, then every later press is 1 + 1 on SG.
        answers["SG"] = two
        login(r)
        assertEquals(15, api.loginCalls)
        assertEquals(15, check.calls)
        assertEquals(YiRegion.SG, selector.currentRegion())
        login(r)
        assertEquals(16, api.loginCalls)
        assertEquals(16, check.calls)
        assertEquals("SG", api.loginRegions.last())
        assertEquals(0, api.refreshCalls)
    }

    @Test
    fun `discovery_13 never runs on app start or token refresh`() {
        val stored = AuthSession(
            accessToken = Synth.token("OLD"), refreshToken = Synth.secret("OLD"),
            expiresAt = clock.nowMillis() + 1_000L, userId = Synth.USER_ID, authMethod = AuthMethod.PASSWORD,
        )
        store = RegionTestStore(stored)
        val api = ScriptedAuthApi(selector, allOk)
        val check = ScriptedDeviceCheck(selector, mapOf("US" to empty, "EU" to empty, "SG" to empty))
        val r = repo(api, check)                      // app start with a stored session
        clock.advance(5_000L)                         // session expired
        runBlocking {
            repeat(3) { r.getAccessToken() }
            r.refresh()
            r.refreshSession()
        }
        assertEquals(0, api.loginCalls, "no login outside an explicit login call")
        assertEquals(0, check.calls, "no device-list discovery outside an explicit login call")
        assertEquals(1, api.refreshCalls, "refresh keeps its own once-per-expiry bound")
        assertTrue(selector.selections.isEmpty())
        assertTrue(selector.restores.isEmpty())
        assertEquals(YiRegion.US, selector.currentRegion())
        recorder.assertMarkerAbsent("BLOCK_REGION_FALLBACK")
        recorder.assertMarkerAbsent("BLOCK_REGION_DISCOVERY")
    }

    @Test
    fun `discovery_14 a token refresh requested while the check runs is suppressed`() {
        val api = ScriptedAuthApi(selector, allOk)
        lateinit var r: AuthRepository
        var refreshedDuringCheck: String? = "unset"
        var tokenDuringCheck: String? = null
        val check = ScriptedDeviceCheck(selector, mapOf("US" to two)) {
            tokenDuringCheck = r.getAccessToken()       // what the shared client's auth interceptor would read
            refreshedDuringCheck = r.refresh()          // what its 401 authenticator would call
        }
        r = repo(api, check)
        login(r)
        assertEquals(Synth.token("US"), tokenDuringCheck, "the check runs with the session of the login just made")
        assertNull(refreshedDuringCheck)
        assertEquals(0, api.refreshCalls, "no POST auth_token during the login discovery")
        assertEquals(1, api.loginCalls)
        assertEquals(1, check.calls)
    }

    @Test
    fun `discovery_15 without a device check port login keeps the first accepting region and makes no list call`() {
        val api = ScriptedAuthApi(selector, allOk)
        val session = runBlocking { AuthRepository(api, store, clock, selector).login(Synth.EMAIL, Synth.PASSWORD) }
        assertEquals(1, api.loginCalls)
        assertEquals(YiRegion.US, selector.currentRegion())
        assertEquals(Synth.token("US"), session.accessToken)
        recorder.assertMarkerAbsent("BLOCK_REGION_DISCOVERY")
    }
    // END_BLOCK_DISCOVERY_BOUNDS

    // START_BLOCK_DEVICE_LIST_COUNTER
    @Test
    fun `counter_1 reduces a device-list body to code and count only`() {
        val item = """{"uid":"SYNTHUID0001","name":"Cam SYNTH"}"""
        assertEquals(DeviceListCheck("20000", 2), DeviceListCounter.reduce("""{"code":"20000","data":[$item,$item]}"""))
        assertEquals(DeviceListCheck("20000", 2), DeviceListCounter.reduce("""{"code":20000,"data":[$item,$item]}"""))
        assertEquals(DeviceListCheck("20000", 0), DeviceListCounter.reduce("""{"code":"20000","data":[]}"""))
        assertEquals(DeviceListCheck("20200", 0), DeviceListCounter.reduce("""{"code":"20200"}"""))
        assertEquals(DeviceListCheck("20200", 0), DeviceListCounter.reduce("""{"code":"20200","data":null}"""))
        assertEquals(DeviceListCheck("20000", 1), DeviceListCounter.reduce("""{"code":"20000","data":{"list":[$item]}}"""))
        assertEquals(DeviceListCheck("20000", 0), DeviceListCounter.reduce("""{"code":"20000","data":{"total":0}}"""))
        assertEquals(
            DeviceListCheck("20000", 1),
            DeviceListCounter.reduce("""{"code":"20000","data":"[{\"uid\":\"SYNTHUID0001\"}]"}"""),
        )
        assertEquals(DeviceListCheck("-10003", 0), DeviceListCounter.reduce("""{"code":"-10003"}"""))
        assertEquals(DeviceListCheck("?", 0), DeviceListCounter.reduce("""{"code":"not a code value"}"""))
        assertEquals(DeviceListCheck(null, 0), DeviceListCounter.reduce("<html>405</html>"))
        assertEquals(DeviceListCheck(null, 0), DeviceListCounter.reduce(""))
        assertEquals(DeviceListCheck(null, 0), DeviceListCounter.reduce(null))

        assertTrue(DeviceListCheck("20000", 2).hasDevices)
        assertFalse(DeviceListCheck("20000", 0).hasDevices)
        assertFalse(DeviceListCheck("20200", 0).hasDevices)
        assertFalse(DeviceListCheck("20202", 3).hasDevices, "only a success envelope counts")
        assertFalse(DeviceListCheck(null, 0).hasDevices)
    }
    // END_BLOCK_DEVICE_LIST_COUNTER

    // START_BLOCK_HTTP_DEVICE_LIST_CHECK
    private fun httpCheck(server: MockWebServer): Pair<HttpDeviceListCheck, AuthSession> {
        val config = ConfigModule(InMemorySettingsStore())
        config.setApiBaseUrl(server.url("/").toString())
        val session = AuthSession(
            accessToken = Synth.token("US"), refreshToken = Synth.secret("US"),
            expiresAt = System.currentTimeMillis() + 3_600_000L, userId = Synth.USER_ID, authMethod = AuthMethod.PASSWORD,
        )
        val provider = object : com.yicamalt.network.AuthProvider {
            var refreshes = 0
            override suspend fun getAccessToken(): String? = session.accessToken
            override suspend fun refresh(): String? { refreshes++; return null }
        }
        val http = HttpClientModule(config, Provider { provider }).apply { logSink = { } }
        return HttpDeviceListCheck(http) to session
    }

    @Test
    fun `http_check_1 sends one signed GET v5 devices list to the current server and returns code and count`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"code":"20000","data":[{"uid":"SYNTHUID0001"},{"uid":"SYNTHUID0002"}]}"""))
            val (check, session) = httpCheck(server)
            val result = runBlocking { check.check(session) }

            assertEquals(DeviceListCheck("20000", 2), result)
            assertEquals(1, server.requestCount)
            val req = server.takeRequest()
            assertEquals("GET", req.method)
            val url = req.requestUrl!!
            assertEquals("/v5/devices/list", url.encodedPath)
            assertEquals(setOf("seq", "userid", "hmac"), url.queryParameterNames)
            assertEquals("1", url.queryParameter("seq"))
            assertEquals(Synth.USER_ID, url.queryParameter("userid"))
            assertEquals(
                YiRequestSigner.sign(session.accessToken, session.refreshToken, session.userId),
                url.queryParameter("hmac"),
            )
            assertLogsValueFree()
        } finally { server.shutdown() }
    }

    @Test
    fun `http_check_2 code 20200 is an empty list and a transport failure propagates without a retry`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"code":"20200"}"""))
            val (check, session) = httpCheck(server)
            assertEquals(DeviceListCheck("20200", 0), runBlocking { check.check(session) })
            assertEquals(1, server.requestCount)

            server.shutdown()
            assertThrows(IOException::class.java) { runBlocking { check.check(session) } }
        } finally { runCatching { server.shutdown() } }
    }
    // END_BLOCK_HTTP_DEVICE_LIST_CHECK
}
