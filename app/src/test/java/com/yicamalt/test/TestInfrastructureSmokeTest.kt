// FILE: TestInfrastructureSmokeTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Smoke-test the shared TestInfrastructure harnesses compile and behave.
//   SCOPE: TraceRecorder, RedactionScanner, FakeYiApi, FakeAuthStore, FakeCameraStreamServer, StreamHandleFake.
//   DEPENDS: TestInfrastructure harnesses
//   LINKS: V-M-SCAFFOLD (gate evidence-1)
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.test

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TestInfrastructureSmokeTest {

    @Test
    fun `trace_recorder_captures_ordered_block_markers`() {
        val rec = TraceRecorder().apply { plant() }
        try {
            // START_BLOCK_SMOKE_TRACE
            timber.log.Timber.tag("Auth").d("[Auth][login][BLOCK_VALIDATE_CREDENTIALS] ok")
            timber.log.Timber.tag("Auth").d("[Auth][login][BLOCK_STORE_TOKEN] done")
            // END_BLOCK_SMOKE_TRACE
            rec.assertMarkerAppeared("BLOCK_VALIDATE_CREDENTIALS")
            rec.assertSequence("BLOCK_VALIDATE_CREDENTIALS", "BLOCK_STORE_TOKEN")
        } finally { rec.unplant() }
    }

    @Test
    fun `redaction_scanner_flags_secrets`() {
        val scanner = RedactionScanner()
        val hits = scanner.scan("Bearer abc123.password=test")
        assertTrue(hits.isNotEmpty(), "expected forbidden substrings to be flagged")
    }

    @Test
    fun `redaction_scanner_passes_clean_buffer`() {
        RedactionScanner().assertClean("[Auth][login][BLOCK_STORE_TOKEN] ok correlationId=abc")
    }

    @Test
    fun `fake_yi_api_returns_scripted_body`() {
        val api = FakeYiApi().apply {
            stub("/v1/account/login", FakeYiApi.Outcome.Body(loginSuccessBody("tok", "ref")))
        }
        val out = kotlinx.coroutines.runBlocking { api.respond("/v1/account/login", "{}") }
        assertTrue(out is FakeYiApi.Outcome.Body)
        assertNotNull((out as FakeYiApi.Outcome.Body).json)
    }

    @Test
    fun `fake_auth_store_round_trip`() {
        val store = FakeAuthStore()
        store.putString(FakeAuthStore.KEY_ACCESS_TOKEN, "tok")
        assertEquals("tok", store.getString(FakeAuthStore.KEY_ACCESS_TOKEN))
        store.clear()
        assertEquals(null, store.getString(FakeAuthStore.KEY_ACCESS_TOKEN))
    }

    @Test
    fun `fake_camera_stream_server_emits_encrypted_bytes`() {
        val server = FakeCameraStreamServer()
        server.start()
        try {
            assertTrue(server.emittedBytes.isNotEmpty())
            assertFalse(String(server.emittedBytes).contains("FRAME"))
        } finally { server.stop() }
    }

    @Test
    fun `stream_handle_fake_transitions_to_playing`() {
        val handle = StreamHandleFake().apply {
            enqueue(StreamHandleFake.State.CONNECTING, StreamHandleFake.State.PLAYING)
        }
        assertEquals(StreamHandleFake.State.CONNECTING, handle.tick())
        assertEquals(StreamHandleFake.State.PLAYING, handle.tick())
        assertTrue(handle.isPlaying())
        assertEquals(18, handle.fps)
    }
}
