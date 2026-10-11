// FILE: AuthRepositoryTest.kt
// VERSION: 0.3.0
// START_MODULE_CONTRACT
//   PURPOSE: Verify login/refresh/logout/session flows against MockWebServer + in-memory AuthStore + fake clock.
//   SCOPE: success + failure scenarios for V-M-AUTH; trace + redaction assertions.
//   DEPENDS: M-AUTH, TestInfrastructure
//   LINKS: V-M-AUTH
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.auth

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.3.0 - CAM-02: region_1..region_9 pin the login region fallback (hop only on code 20253,
//     at most 3 login requests per call, original server restored when the account is found nowhere,
//     found region kept on a wrong password, no hop on a network error or a custom server).
//     Region discovery by device list is pinned in AuthRegionDiscoveryTest.
//   PREV_CHANGE: v0.2.0 - refresh bound tests: 5x getAccessToken + 3x refresh on an expired session with a
//     failing refresh API = exactly 1 auth_token call; a new login re-arms exactly one more attempt.
// END_CHANGE_SUMMARY

import com.yicamalt.test.RedactionScanner
import com.yicamalt.test.TraceRecorder
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
import com.yicamalt.config.YiRegion
import java.io.IOException
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.atomic.AtomicLong

/** In-memory AuthStore for tests. */
private class TestAuthStore : AuthStore {
    private var held: AuthSession? = null
    override fun putSession(session: AuthSession) { held = session }
    override fun getSession(): AuthSession? = held
    override fun clear() { held = null }
}

/** Controllable clock. */
private class FakeClock : Clock {
    private val t = AtomicLong(1_000_000L)
    fun advance(ms: Long) { t.addAndGet(ms) }
    override fun nowMillis(): Long = t.get()
}

class AuthRepositoryTest {

    private val server = MockWebServer()
    private val recorder = TraceRecorder()
    private val redaction = RedactionScanner()
    private lateinit var api: YiCloudAuthApi
    private lateinit var store: TestAuthStore
    private lateinit var clock: FakeClock

    @BeforeEach fun setUp() {
        server.start()
        recorder.plant()
        store = TestAuthStore()
        clock = FakeClock()
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        api = Retrofit.Builder()
            .baseUrl(server.url("/v1/").toString())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(YiCloudAuthApi::class.java)
    }

    @AfterEach fun tearDown() {
        recorder.unplant()
        server.shutdown()
    }

    private val repo get() = AuthRepository(api, store, clock)

    private fun loginBody(access: String, refresh: String, expiresIn: Long = 3600, user: String = "u-1") =
        """{"code":"200","data":{"access_token":"$access","refresh_token":"$refresh","expires_in":$expiresIn,"user_id":"$user"}}"""

    private fun invalidBody() = """{"code":"401","message":"invalid credentials"}"""

    @Test
    fun `login sends GET with account seq and device params`() {
        server.enqueue(MockResponse().setBody(loginBody("acc-1", "ref-1")))
        runBlocking { repo.login("[EMAIL]", "pw") }
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        val q = req.requestUrl!!
        assertTrue(q.encodedPath.endsWith("/v4/users/login"), "got " + q.encodedPath)
        assertEquals("1", q.queryParameter("seq"))
        assertEquals("[EMAIL]", q.queryParameter("account"))
        assertNotNull(q.queryParameter("password"))
        assertNotNull(q.queryParameter("dev_name"))
        assertNotNull(q.queryParameter("dev_type"))
        assertNotNull(q.queryParameter("dev_os_version"))
    }

    @Test
    fun `login password is HMAC-SHA256 of the raw password, Base64 no-wrap`() {
        server.enqueue(MockResponse().setBody(loginBody("acc-1", "ref-1")))
        runBlocking { repo.login("[EMAIL]", "pw") }
        val q = server.takeRequest().requestUrl!!
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec("KXLiUdAsO81ycDyEJAeETC\$KklXdz3AC".toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val expected = java.util.Base64.getEncoder()
            .encodeToString(mac.doFinal("pw".toByteArray(Charsets.UTF_8)))
        assertEquals(expected, q.queryParameter("password"))
    }

    @Test
    fun `scenario_6 real 20000 envelope with token and token_secret is accepted`() {
        // Observed live response shape (gw-eu, 2026-09-08): code 20000, token/token_secret, no expires_in.
        val realBody =
            """{"code":"20000","data":{"userid":167315,"account":"[EMAIL]","name":"Pavel Van","mobile":"","userMobileRegion":"","email":"[EMAIL]","img":"","token":"acc-real-1","token_secret":"sec-real-1","birthday":"","first_name":"Pavel","last_name":"Van","user_mobile":"","flag":false,"openId":"abc","register_time":"1496125022000"}}"""
        server.enqueue(MockResponse().setBody(realBody))
        val session = runBlocking { repo.login("[EMAIL]", "pw") }
        assertEquals("acc-real-1", session.accessToken, "data.token must be used as access token")
        assertEquals("sec-real-1", session.refreshToken, "data.token_secret must be used as refresh token")
        assertEquals("167315", session.userId, "data.userid must be used as user id")
        assertTrue(session.expiresAt > clock.nowMillis(), "missing expires_in must NOT produce an already-expired session")
        assertNotNull(store.getSession())
        recorder.assertMarkerAppeared("BLOCK_STORE_TOKEN")
        recorder.assertSequence("BLOCK_VALIDATE_CREDENTIALS", "BLOCK_REGION_FALLBACK", "BLOCK_REGION_SELECTED", "BLOCK_STORE_TOKEN")
    }

    @Test
    fun `second login after success does not recurse into refresh deadlock`() {
        // Uses the PRODUCTION http client (with authInterceptor) — the deadlock only
        // reproduces when the refresh path goes through the interceptor chain.
        val config = com.yicamalt.config.ConfigModule(com.yicamalt.config.InMemorySettingsStore())
        config.setApiBaseUrl(server.url("/v1/").toString())
        lateinit var repo2: AuthRepository
        val httpClient = com.yicamalt.network.HttpClientModule(
            config,
            javax.inject.Provider { repo2 },
        )
        val api2 = httpClient.createRetrofit().create(YiCloudAuthApi::class.java)
        repo2 = AuthRepository(api2, TestAuthStore(), clock)

        // Keep the http log sink quiet: route http logs into a disposable buffer.
        httpClient.logSink = { /* drop */ }

        server.enqueue(MockResponse().setBody(loginBody("acc-1", "ref-1", expiresIn = 1)))
        server.enqueue(MockResponse().setBody(loginBody("acc-2", "ref-2", expiresIn = 3600)))
        server.enqueue(MockResponse().setBody(loginBody("acc-3", "ref-3", expiresIn = 3600)))
        runBlocking { repo2.login("[EMAIL]", "pw") }
        // Expire the session, then perform a full login again; must complete (no dispatcher deadlock).
        clock.advance(2_000L)
        val s2 = runBlocking { repo2.login("[EMAIL]", "pw") }
        assertEquals("acc-3", s2.accessToken, "second login must use the 3rd queued response")
        assertEquals(3, server.requestCount, "login + one refresh + login, no recursion")
    }

    @Test
    fun `scenario_1 valid login returns session and stores token`() {
        server.enqueue(MockResponse().setBody(loginBody("acc-1", "ref-1")))
        val session = runBlocking { repo.login("user@example.com", "pw") }
        assertEquals("acc-1", session.accessToken)
        assertEquals("ref-1", session.refreshToken)
        assertEquals("u-1", session.userId)
        assertNotNull(store.getSession())
        recorder.assertMarkerAppeared("BLOCK_VALIDATE_CREDENTIALS")
        recorder.assertMarkerAppeared("BLOCK_STORE_TOKEN")
        recorder.assertSequence("BLOCK_VALIDATE_CREDENTIALS", "BLOCK_REGION_FALLBACK", "BLOCK_REGION_SELECTED", "BLOCK_STORE_TOKEN")
    }

    @Test
    fun `scenario_2 invalid credentials throws AUTH_INVALID_CREDENTIALS`() {
        server.enqueue(MockResponse().setBody(invalidBody()))
        val err = assertThrows(AuthError.InvalidCredentials::class.java) {
            runBlocking { repo.login("user@example.com", "wrong") }
        }
        assertTrue(err.message!!.contains("AUTH_INVALID_CREDENTIALS"))
        assertNull(store.getSession())
    }

    @Test
    fun `scenario_3 refresh updates stored token`() {
        server.enqueue(MockResponse().setBody(loginBody("acc-1", "ref-1")))
        server.enqueue(MockResponse().setBody(loginBody("acc-2", "ref-2")))
        val r = repo
        runBlocking { r.login("user@example.com", "pw") }
        val refreshed = runBlocking { r.refreshSession() }
        assertNotNull(refreshed)
        assertEquals("acc-2", refreshed!!.accessToken)
        assertEquals("acc-2", store.getSession()?.accessToken)
        recorder.assertMarkerAppeared("BLOCK_REFRESH_TOKEN")
    }

    @Test
    fun `scenario_3b refresh failure returns null and keeps old session`() {
        server.enqueue(MockResponse().setBody(loginBody("acc-1", "ref-1")))
        server.enqueue(MockResponse().setBody(invalidBody()))
        val r = repo
        runBlocking { r.login("user@example.com", "pw") }
        val refreshed = runBlocking { r.refreshSession() }
        assertNull(refreshed)
        assertEquals("acc-1", store.getSession()?.accessToken, "old session must remain")
    }

    @Test
    fun `scenario_4 logout clears session`() {
        server.enqueue(MockResponse().setBody(loginBody("acc-1", "ref-1")))
        val r = repo
        runBlocking { r.login("user@example.com", "pw") }
        assertNotNull(r.getSession())
        r.logout()
        assertNull(r.getSession())
        assertNull(store.getSession())
        recorder.assertMarkerAppeared("BLOCK_LOGOUT")
    }

    @Test
    fun `scenario_5 redaction tokens never appear in logs`() {
        server.enqueue(MockResponse().setBody(loginBody("acc-secret-1", "ref-secret-2")))
        val r = repo
        runBlocking { r.login("user@example.com", "pw") }
        redaction.assertClean(
            recorder.bufferText(),
            extraLiterals = listOf("acc-secret-1", "ref-secret-2"),
        )
    }

    @Test
    fun `redaction auth logs carry no user id or epoch`() {
        // Realistic wall clock so expiresAt is a 13-digit epoch-millis value the scanner would catch.
        clock.advance(1_760_000_000_000L)
        server.enqueue(MockResponse().setBody(loginBody("acc-1", "ref-1", user = "167315")))
        server.enqueue(MockResponse().setBody(loginBody("acc-2", "ref-2", user = "167315")))
        val r = repo
        val session = runBlocking { r.login("user@example.com", "pw") }
        assertNotNull(runBlocking { r.refreshSession() })
        recorder.assertMarkerAppeared("BLOCK_STORE_TOKEN")
        recorder.assertMarkerAppeared("BLOCK_REFRESH_TOKEN")
        redaction.assertClean(
            recorder.bufferText(),
            extraLiterals = listOf("167315", session.expiresAt.toString()),
        )
    }

    @Test
    fun `trace refresh called exactly once on expiry`() {
        server.enqueue(MockResponse().setBody(loginBody("acc-1", "ref-1", expiresIn = 1)))
        server.enqueue(MockResponse().setBody(loginBody("acc-2", "ref-2", expiresIn = 3600)))
        val r = repo
        runBlocking { r.login("user@example.com", "pw") }
        // Advance past expiry; getAccessToken() must trigger exactly one refresh.
        clock.advance(2_000L)
        val token = runBlocking { r.getAccessToken() }
        assertEquals("acc-2", token)
        val refreshMarkers = recorder.all().count { it.message.contains("BLOCK_REFRESH_TOKEN") }
        assertEquals(2, refreshMarkers, "refresh marker appears on refresh() start + success lines")
        // Verify only one refresh network call hit the server.
        assertEquals(2, server.requestCount, "login + one refresh only")
    }

    @Test
    fun `refresh bound at most one auth_token call per session expiry`() {
        // login #1 (expires in 1s), then every further response is a failing refresh body.
        server.enqueue(MockResponse().setBody(loginBody("acc-1", "ref-1", expiresIn = 1)))
        repeat(10) { server.enqueue(MockResponse().setBody("""{"code":"20201"}""")) }
        val r = repo
        runBlocking { r.login("user@example.com", "pw") }
        clock.advance(2_000L)
        runBlocking {
            repeat(5) { assertEquals("acc-1", r.getAccessToken(), "stale token returned after failed refresh") }
            repeat(3) { assertNull(r.refresh(), "authenticator path sees the same memo") }
        }
        assertEquals(2, server.requestCount, "login + exactly ONE auth_token call")
        recorder.assertMarkerAppeared("BLOCK_REFRESH_ONCE_PER_EXPIRY")
    }

    @Test
    fun `refresh bound re-arms after a new successful login`() {
        server.enqueue(MockResponse().setBody(loginBody("acc-1", "ref-1", expiresIn = 1)))
        server.enqueue(MockResponse().setBody("""{"code":"20201"}"""))
        val r = repo
        runBlocking { r.login("user@example.com", "pw") }
        clock.advance(2_000L)
        runBlocking { repeat(3) { r.getAccessToken() } }
        assertEquals(2, server.requestCount)
        // New login -> new expiresAt -> exactly one more refresh attempt on the next expiry.
        server.enqueue(MockResponse().setBody(loginBody("acc-2", "ref-2", expiresIn = 1)))
        repeat(5) { server.enqueue(MockResponse().setBody("""{"code":"20201"}""")) }
        runBlocking { r.login("user@example.com", "pw") }
        clock.advance(2_000L)
        runBlocking {
            repeat(4) { r.getAccessToken() }
            repeat(2) { r.refresh() }
        }
        assertEquals(4, server.requestCount, "login, refresh, login, ONE more refresh")
    }

    // START_BLOCK_REGION_FALLBACK_TESTS
    private val regionStore = RegionTestStore()
    private val hasDevices = ScriptedDeviceCheck.TWO

    private fun regionRepo(selector: FakeRegionSelector, api: ScriptedAuthApi, check: DeviceListCheckPort) =
        AuthRepository(api, regionStore, clock, selector, check)

    private fun regionLogin(r: AuthRepository) = runBlocking { r.login(Synth.EMAIL, Synth.PASSWORD) }

    private fun assertRegionTraceClean() {
        recorder.assertMaxRetries("BLOCK_REGION_FALLBACK", 3)
        redaction.assertClean(recorder.bufferText(), extraLiterals = listOf(Synth.EMAIL, Synth.PASSWORD, Synth.USER_ID))
    }

    @Test
    fun `region_1 account not found on US then accepted on EU makes 2 login calls and ends on EU`() {
        val selector = FakeRegionSelector(YiRegion.US)
        val api = ScriptedAuthApi(selector, queue = listOf(rejected("20253"), ok("EU")))
        val check = ScriptedDeviceCheck(selector, mapOf("EU" to hasDevices))
        val session = regionLogin(regionRepo(selector, api, check))
        assertEquals(listOf("US", "EU"), api.loginRegions)
        assertEquals(YiRegion.EU, selector.currentRegion())
        assertEquals(Synth.token("EU"), session.accessToken)
        assertEquals(Synth.token("EU"), regionStore.getSession()?.accessToken)
        assertEquals(1, check.calls)
        recorder.assertMarkerAppeared("BLOCK_REGION_FALLBACK")
        assertRegionTraceClean()
    }

    @Test
    fun `region_2 account found on the third gateway makes 3 login calls and ends on SG`() {
        val selector = FakeRegionSelector(YiRegion.US)
        val api = ScriptedAuthApi(selector, queue = listOf(rejected("20253"), rejected("20253"), ok("SG")))
        val check = ScriptedDeviceCheck(selector, mapOf("SG" to hasDevices))
        regionLogin(regionRepo(selector, api, check))
        assertEquals(listOf("US", "EU", "SG"), api.loginRegions)
        assertEquals(YiRegion.SG, selector.currentRegion())
        assertEquals(1, check.calls)
        assertRegionTraceClean()
    }

    @Test
    fun `region_3 account found nowhere makes exactly 3 login calls and restores the original server`() {
        val selector = FakeRegionSelector(YiRegion.EU)
        val api = ScriptedAuthApi(selector, queue = List(10) { rejected("20253") })
        val check = ScriptedDeviceCheck(selector, emptyMap())
        assertThrows(AuthError.InvalidCredentials::class.java) { regionLogin(regionRepo(selector, api, check)) }
        assertEquals(listOf("EU", "US", "SG"), api.loginRegions, "each region exactly once, starting on the current one")
        assertEquals(3, api.loginCalls)
        assertEquals(0, check.calls)
        assertEquals(listOf(YiRegion.EU.baseUrl), selector.restores)
        assertEquals(YiRegion.EU, selector.currentRegion())
        assertEquals(YiRegion.EU.baseUrl, selector.currentBaseUrl())
        assertNull(regionStore.getSession())
        assertRegionTraceClean()
    }

    @Test
    fun `region_4 wrong password on a later gateway keeps that gateway`() {
        val selector = FakeRegionSelector(YiRegion.US)
        val api = ScriptedAuthApi(selector, queue = listOf(rejected("20253"), rejected("20261")))
        val check = ScriptedDeviceCheck(selector, emptyMap())
        assertThrows(AuthError.InvalidCredentials::class.java) { regionLogin(regionRepo(selector, api, check)) }
        assertEquals(2, api.loginCalls)
        assertEquals(YiRegion.EU, selector.currentRegion(), "the account lives on EU: the next attempt starts there")
        assertTrue(selector.restores.isEmpty())
        assertEquals(0, check.calls)
        assertRegionTraceClean()
    }

    @Test
    fun `region_5 wrong password on the first gateway makes exactly 1 call and never hops`() {
        val selector = FakeRegionSelector(YiRegion.US)
        val api = ScriptedAuthApi(selector, queue = listOf(rejected("20261")))
        val check = ScriptedDeviceCheck(selector, emptyMap())
        assertThrows(AuthError.InvalidCredentials::class.java) { regionLogin(regionRepo(selector, api, check)) }
        assertEquals(1, api.loginCalls)
        assertTrue(selector.selections.isEmpty())
        assertTrue(selector.restores.isEmpty())
        assertEquals(0, check.calls)
        assertRegionTraceClean()
    }

    @Test
    fun `region_6 network error on the first call propagates after exactly 1 call with the server unchanged`() {
        val selector = FakeRegionSelector(YiRegion.SG)
        val api = ScriptedAuthApi(selector, queue = listOf(IOException("synthetic")))
        val check = ScriptedDeviceCheck(selector, emptyMap())
        assertThrows(IOException::class.java) { regionLogin(regionRepo(selector, api, check)) }
        assertEquals(1, api.loginCalls)
        assertTrue(selector.selections.isEmpty())
        assertEquals(YiRegion.SG.baseUrl, selector.currentBaseUrl())
        assertEquals(0, check.calls)
        assertNull(regionStore.getSession())
    }

    @Test
    fun `region_7 network error after a hop puts the original server back`() {
        val selector = FakeRegionSelector(YiRegion.US)
        val api = ScriptedAuthApi(selector, queue = listOf(rejected("20253"), IOException("synthetic")))
        val check = ScriptedDeviceCheck(selector, emptyMap())
        assertThrows(IOException::class.java) { regionLogin(regionRepo(selector, api, check)) }
        assertEquals(2, api.loginCalls, "no retry and no third gateway after a network error")
        assertEquals(YiRegion.US, selector.currentRegion())
        assertEquals(0, check.calls)
    }

    @Test
    fun `region_8 custom server makes exactly 1 call regardless of the code`() {
        listOf(rejected("20253"), rejected("20261"), ok("X")).forEach { answer ->
            val selector = FakeRegionSelector(null)
            val api = ScriptedAuthApi(selector, queue = listOf(answer))
            val check = ScriptedDeviceCheck(selector, emptyMap())
            runCatching { regionLogin(regionRepo(selector, api, check)) }
            assertEquals(1, api.loginCalls)
            assertEquals(0, check.calls)
            assertTrue(selector.selections.isEmpty())
            assertTrue(selector.restores.isEmpty())
            assertEquals(Synth.CUSTOM_URL, selector.currentBaseUrl())
        }
    }

    @Test
    fun `region_9 three-argument repository makes exactly 1 call and never hops`() {
        val api = ScriptedAuthApi(null, queue = listOf(rejected("20253"), ok("X")))
        assertThrows(AuthError.InvalidCredentials::class.java) {
            runBlocking { AuthRepository(api, regionStore, clock).login(Synth.EMAIL, Synth.PASSWORD) }
        }
        assertEquals(1, api.loginCalls)
        recorder.assertMaxRetries("BLOCK_REGION_FALLBACK", 1)
    }
    // END_BLOCK_REGION_FALLBACK_TESTS
}
