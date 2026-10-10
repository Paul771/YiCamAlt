// FILE: HttpClientModuleTest.kt
// VERSION: 0.2.0
// START_MODULE_CONTRACT
//   PURPOSE: Verify HTTP client wiring, auth header injection, 401 refresh+retry, and redacted logging.
//   SCOPE: success + failure scenarios for V-M-HTTP using MockWebServer + a fake AuthProvider.
//   DEPENDS: M-HTTP, M-CONFIG, TestInfrastructure, RedactionFixtures
//   LINKS: V-M-HTTP, SEC-01
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.network

// START_MODULE_MAP
//   FakeAuthProvider - scripted AuthProvider counting refresh calls.
//   HttpClientModuleTest - scenario_1..scenario_7 (scenario_5..7: SEC-01 query and body redaction).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.2.0 - SEC-01: scenario_5 (hmac/userid query masking), scenario_6 (encoded-data login
//     envelope), scenario_7 (/v4/tnp/device_info) using synthetic RedactionFixtures.
// END_CHANGE_SUMMARY

import com.yicamalt.config.ConfigModule
import com.yicamalt.test.RedactionFixtures
import com.yicamalt.test.RedactionScanner
import com.yicamalt.test.TraceRecorder
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.http.GET
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Provider

/** Fake AuthProvider that counts refresh calls and returns a scripted token. */
private class FakeAuthProvider(
    initialToken: String? = "initial-token",
    private val refreshedToken: String? = "refreshed-token",
) : AuthProvider {
    private val token = java.util.concurrent.atomic.AtomicReference(initialToken)
    val refreshCount = AtomicInteger(0)
    override suspend fun getAccessToken(): String? = token.get()
    override suspend fun refresh(): String? {
        refreshCount.incrementAndGet()
        token.set(refreshedToken)
        return refreshedToken
    }
}

private interface PingApi { @GET("ping") suspend fun ping(): Unit }

class HttpClientModuleTest {

    private val server = MockWebServer()
    private val recorder = TraceRecorder()
    private val redaction = RedactionScanner()
    private val logBuffer = StringBuilder()

    @BeforeEach fun setUp() {
        server.start()
        recorder.plant()
        logBuffer.clear()
    }

    @AfterEach fun tearDown() {
        recorder.unplant()
        server.shutdown()
    }

    /** Build a client pointed at the mock server with a capturing log sink. */
    private fun buildClient(auth: AuthProvider): HttpClientModule =
        buildWithBaseUrl(server.url("/").toString().replace("http://", "https://"), auth)

    private fun buildWithBaseUrl(baseUrl: String, auth: AuthProvider): HttpClientModule {
        val testConfig = TestConfig(baseUrl)
        return HttpClientModule(testConfig, Provider { auth }).apply {
            logSink = { msg -> logBuffer.appendLine(msg) }
        }
    }

    @Test
    fun `scenario_1 retrofit built with base url and auth interceptor`() {
        val client = buildWithBaseUrl("https://api.example.com/v1/", FakeAuthProvider())
        val retrofit = client.createRetrofit()
        assertTrue(retrofit.baseUrl().toString().startsWith("https://api.example.com/v1/"))
        recorder.assertMarkerAppeared("BLOCK_INIT_HTTP_CLIENT")
    }

    @Test
    fun `scenario_2 auth header injected when token present`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val auth = FakeAuthProvider(initialToken = "tok-123")
        val client = buildClient(auth)
        // Use the raw client to hit the server so we can inspect the recorded request.
        val req = okhttp3.Request.Builder().url(server.url("/ping")).build()
        client.getHttpClient().newCall(req).execute().use { resp ->
            assertEquals(200, resp.code)
        }
        val recorded: RecordedRequest = server.takeRequest()
        val authHeader = recorded.getHeader("Authorization")
        assertNotNull(authHeader)
        assertTrue(authHeader!!.startsWith("Bearer "))
        // Token value must not leak into interceptor logs.
        redaction.assertClean(logBuffer.toString(), extraLiterals = listOf("tok-123"))
    }

    @Test
    fun `scenario_3 401 triggers exactly one refresh and one retry`() {
        // First call: 401 -> authenticator refreshes -> retry gets 200
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val auth = FakeAuthProvider(initialToken = "old", refreshedToken = "new")
        val client = buildClient(auth)
        val req = okhttp3.Request.Builder().url(server.url("/secure")).build()
        client.getHttpClient().newCall(req).execute().use { resp ->
            assertEquals(200, resp.code, "retried request should succeed")
        }
        assertEquals(1, auth.refreshCount.get(), "refresh must be called exactly once")
        // Second request (the retry) must carry the refreshed token.
        server.takeRequest() // initial 401 request
        val retry = server.takeRequest()
        assertEquals("Bearer new", retry.getHeader("Authorization"))
    }

    @Test
    fun `scenario_3b 401 with refresh failure returns 401 and does not loop`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}")) // still 401 after retry attempt
        val auth = FakeAuthProvider(initialToken = "old", refreshedToken = null)
        val client = buildClient(auth)
        val req = okhttp3.Request.Builder().url(server.url("/secure")).build()
        client.getHttpClient().newCall(req).execute().use { resp ->
            assertEquals(401, resp.code)
        }
        // refresh attempted once; authenticator returns null and stops.
        assertEquals(1, auth.refreshCount.get())
    }

    @Test
    fun `scenario_4 authorization header redacted from logs`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val client = buildClient(FakeAuthProvider(initialToken = "secret-token-value"))
        val req = okhttp3.Request.Builder().url(server.url("/ping")).build()
        client.getHttpClient().newCall(req).execute().close()
        // The literal token must not appear anywhere in interceptor output.
        redaction.assertClean(logBuffer.toString(), extraLiterals = listOf("secret-token-value"))
        // And the Authorization header line must show redaction, not the bearer value.
        assertTrue(!logBuffer.contains("secret-token-value"))
    }

    @Test
    fun `setAuthToken updates header on subsequent calls`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val client = buildClient(FakeAuthProvider(initialToken = null))
        client.setAuthToken("manual-token")
        val req = okhttp3.Request.Builder().url(server.url("/ping")).build()
        client.getHttpClient().newCall(req).execute().close()
        assertEquals("Bearer manual-token", server.takeRequest().getHeader("Authorization"))
    }

    // START_BLOCK_SEC01_SCENARIOS
    @Test
    fun `scenario_5 hmac userid query values masked in request log`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val client = buildClient(FakeAuthProvider())
        val url = server.url("/v5/devices/list?seq=1&userid=${RedactionFixtures.USER_ID}&hmac=${RedactionFixtures.HMAC}")
        client.getHttpClient().newCall(okhttp3.Request.Builder().url(url).build()).execute().close()
        val logged = logBuffer.toString()
        assertTrue(logged.contains("hmac=██"), "request line must show hmac=██")
        assertTrue(logged.contains("userid=██"), "request line must show userid=██")
        assertTrue(logged.contains("seq=1"), "non-secret params stay readable")
        redaction.assertClean(logged, extraLiterals = RedactionFixtures.ALL_SECRETS + RedactionFixtures.USER_ID)
    }

    @Test
    fun `scenario_6 login encoded-data envelope body log is scanner-clean`() {
        val body = """{"code":"20000","data":"{\"access_token\":\"${RedactionFixtures.TOKEN}\",""" +
            """\"token_secret\":\"${RedactionFixtures.TOKEN_SECRET}\",\"userid\":${RedactionFixtures.USER_ID}}"}"""
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))
        val client = buildClient(FakeAuthProvider())
        client.getHttpClient().newCall(okhttp3.Request.Builder().url(server.url("/v4/users/login")).build()).execute().close()
        val trace = recorder.bufferText()
        recorder.assertMarkerAppeared("BLOCK_REDACTED_LOG")
        assertTrue(trace.contains("access_token") && trace.contains("token_secret"), "key names stay readable")
        redaction.assertClean(trace, extraLiterals = RedactionFixtures.ALL_SECRETS)
        redaction.assertClean(logBuffer.toString(), extraLiterals = RedactionFixtures.ALL_SECRETS)
    }

    @Test
    fun `scenario_7 tnp device_info body log is scanner-clean`() {
        val body = """{"code":"20000","data":{"DID":"${RedactionFixtures.DID}","InitString":"${RedactionFixtures.INIT_STRING}",""" +
            """"License":"${RedactionFixtures.LICENSE}"}}"""
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))
        val client = buildClient(FakeAuthProvider())
        client.getHttpClient().newCall(okhttp3.Request.Builder().url(server.url("/v4/tnp/device_info")).build()).execute().close()
        val trace = recorder.bufferText()
        assertTrue(trace.contains("[Network][body][BLOCK_REDACTED_LOG] path=/v4/tnp/device_info"), trace)
        assertTrue(trace.contains("\"DID\":\"██\""), "DID value masked")
        redaction.assertClean(trace, extraLiterals = RedactionFixtures.ALL_SECRETS)
    }
    // END_BLOCK_SEC01_SCENARIOS

    /** ConfigModule subclass that lets the test point at the mock server. */
    private open class TestConfig(private val baseUrl: String) : ConfigModule() {
        override fun getApiBaseUrl(): String = baseUrl
    }
}