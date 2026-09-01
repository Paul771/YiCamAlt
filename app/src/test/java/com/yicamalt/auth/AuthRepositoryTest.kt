// FILE: AuthRepositoryTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Verify login/refresh/logout/session flows against MockWebServer + in-memory AuthStore + fake clock.
//   SCOPE: success + failure scenarios for V-M-AUTH; trace + redaction assertions.
//   DEPENDS: M-AUTH, TestInfrastructure
//   LINKS: V-M-AUTH
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.auth

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
        runBlocking { repo.login("user@example.com", "pw") }
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        val q = req.requestUrl!!
        assertTrue(q.encodedPath.endsWith("/v4/users/login"), "got " + q.encodedPath)
        assertEquals("1", q.queryParameter("seq"))
        assertEquals("user@example.com", q.queryParameter("account"))
        assertNotNull(q.queryParameter("password"))
        assertNotNull(q.queryParameter("dev_name"))
        assertNotNull(q.queryParameter("dev_type"))
        assertNotNull(q.queryParameter("dev_os_version"))
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
        recorder.assertSequence("BLOCK_VALIDATE_CREDENTIALS", "BLOCK_STORE_TOKEN")
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
}