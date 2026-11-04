// FILE: PlainMediaFetcherTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Verify the real PlainMediaFetcher over MediaHttp.plainClient against a local MockWebServer: 2xx body,
//     non-2xx status, size cap, error class on CODE_IO, and a GET after the CDN dropped the idle pooled connection.
//   SCOPE: V-M-EVENT-PLAYBACK network edge; plain JVM; loopback http only (no Yi cloud, no real CDN).
//   DEPENDS: M-EVENT-PLAYBACK (PlainMediaFetcher, MediaHttp), okhttp3 mockwebserver
//   LINKS: V-M-EVENT-PLAYBACK, EVT-03
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT

// START_MODULE_MAP
//   PlainMediaFetcherTest - real fetcher scenarios; every other media test uses FakeFetcher.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - Phase 5 device bug A: an event clip failed with status=-1 within ~10 ms when the previous
//     clip download had left a pooled keep-alive connection that the CDN had since closed. No test used the real
//     fetcher (FakeFetcher everywhere), so the client configuration (retryOnConnectionFailure=false) was never run.
// END_CHANGE_SUMMARY
package com.yicamalt.event

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class PlainMediaFetcherTest {

    private lateinit var server: MockWebServer
    private val fetcher = PlainMediaFetcher()
    private val body = ByteArray(4_096) { (it % 251).toByte() }

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        MediaHttp.plainClient().connectionPool.evictAll()
    }

    @After fun tearDown() {
        MediaHttp.plainClient().connectionPool.evictAll()
        server.shutdown()
    }

    private fun url(name: String) = server.url("/v/$name.mp4?sig=x").toString()

    private fun ok() = MockResponse().setResponseCode(200).setBody(Buffer().write(body))

    @Test
    fun `2xx returns the body and no error`() = runBlocking {
        server.enqueue(ok())
        val r = fetcher.get(url("a"), 1 shl 20)
        assertEquals(200, r.code)
        assertArrayEquals(body, r.body)
        assertNull(r.error)
    }

    @Test
    fun `non 2xx returns the status without a body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))
        val r = fetcher.get(url("a"), 1 shl 20)
        assertEquals(403, r.code)
        assertNull(r.body)
    }

    @Test
    fun `a body above the cap is CODE_TOO_LARGE`() = runBlocking {
        server.enqueue(ok())
        assertEquals(FetchResponse.CODE_TOO_LARGE, fetcher.get(url("a"), 1_024).code)
    }

    @Test
    fun `an unreachable host is CODE_IO with the exception class name only`() = runBlocking {
        val port = server.port
        server.shutdown()
        val r = fetcher.get("http://127.0.0.1:$port/v/a.mp4?sig=x", 1 shl 20)
        assertEquals(FetchResponse.CODE_IO, r.code)
        assertNotNull(r.error)
        assertEquals(false, r.error!!.contains("127.0.0.1") || r.error!!.contains("sig"))
    }

    @Test
    fun `a GET after the server dropped the pooled connection still succeeds`() = runBlocking {
        // First clip: keep-alive answer, then the server closes the socket without telling the pool (CDN idle close).
        server.enqueue(ok().setSocketPolicy(SocketPolicy.DISCONNECT_AT_END))
        server.enqueue(ok())
        assertEquals(200, fetcher.get(url("first"), 1 shl 20).code)
        Thread.sleep(200)
        val second = fetcher.get(url("second"), 1 shl 20)
        assertEquals("second clip after a dropped pooled connection, error=${second.error}", 200, second.code)
        assertArrayEquals(body, second.body)
    }
}
