// FILE: PpppRuntimeTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Executable contract for NativeLibs (load as a state) and PpppRuntime (init once, debug off, API hex),
//     plus the private-address reduction of AndroidPpppNative.check().
//   SCOPE: loader success / missing / error / caching / TUTK order; runtime call order, -3002, failure, once-only
//     (sequential and concurrent), hex format, value-free trace; isPrivateIpv4 on synthetic addresses.
//   DEPENDS: M-P2P-NATIVE, FakePpppNative, TraceRecorder, RedactionScanner, RedactionFixtures
//   LINKS: V-M-P2P-NATIVE
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.p2p

import com.yicamalt.test.RedactionFixtures
import com.yicamalt.test.RedactionScanner
import com.yicamalt.test.TraceRecorder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

// START_MODULE_MAP
//   RecordingLoader - fake System.loadLibrary: records names, fails for configured names.
//   libs(...) / runtime(...) - builders over FakePpppNative.
//   NativeLibs tests - loaded / missing / error / cached / TUTK order and aggregation.
//   PpppRuntime tests - unavailable, call order, -3002, failure, once-only, concurrency, apiHex, trace hygiene.
//   isPrivateIpv4 tests - five synthetic addresses (never printed).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 02-03: created.
// END_CHANGE_SUMMARY

class PpppRuntimeTest {

    private class RecordingLoader(private val failures: Map<String, Throwable> = emptyMap()) : (String) -> Unit {
        val loaded = CopyOnWriteArrayList<String>()
        override fun invoke(name: String) {
            loaded += name
            failures[name]?.let { throw it }
        }
    }

    private val recorder = TraceRecorder()

    @BeforeEach fun setUp() = recorder.plant()

    @AfterEach fun tearDown() = recorder.unplant()

    private fun runtime(fake: FakePpppNative, loader: RecordingLoader = RecordingLoader()) =
        PpppRuntime(fake, NativeLibs(loader))

    private fun ascii(s: String): ByteArray = (s.toByteArray(Charsets.US_ASCII) + 0.toByte()).copyOf(16)

    // START_BLOCK_NATIVE_LIBS_TESTS
    @Test
    fun `loader success gives Loaded`() {
        val loader = RecordingLoader()
        assertEquals(LoadState.Loaded, NativeLibs(loader).loadPppp())
        assertEquals(listOf("PPPP_API"), loader.loaded)
    }

    @Test
    fun `UnsatisfiedLinkError gives Missing and is never rethrown`() {
        val loader = RecordingLoader(mapOf("PPPP_API" to UnsatisfiedLinkError("no lib")))
        assertEquals(LoadState.Missing, NativeLibs(loader).loadPppp())
    }

    @Test
    fun `other linkage errors give Error with the class name only`() {
        val loader = RecordingLoader(mapOf("PPPP_API" to SecurityException("x")))
        assertEquals(LoadState.Error("SecurityException"), NativeLibs(loader).loadPppp())
    }

    @Test
    fun `second load returns the cached state without calling the loader`() {
        val loader = RecordingLoader(mapOf("PPPP_API" to UnsatisfiedLinkError("no lib")))
        val libs = NativeLibs(loader)
        libs.loadPppp()
        assertEquals(LoadState.Missing, libs.loadPppp())
        assertEquals(1, loader.loaded.size)
    }

    @Test
    fun `tutk loads IOTCAPIs, sCHL, AVAPIs in order and aggregates`() {
        val ok = RecordingLoader()
        assertEquals(LoadState.Loaded, NativeLibs(ok).loadTutk())
        assertEquals(listOf("IOTCAPIs", "sCHL", "AVAPIs"), ok.loaded)

        val broken = RecordingLoader(mapOf("sCHL" to UnsatisfiedLinkError("no lib")))
        val libs = NativeLibs(broken)
        assertEquals(LoadState.Missing, libs.loadTutk())
        assertEquals(listOf("IOTCAPIs", "sCHL"), broken.loaded)
        assertEquals(LoadState.Missing, libs.loadTutk())
        assertEquals(2, broken.loaded.size)
    }

    @Test
    fun `load marker is value-free`() {
        NativeLibs(RecordingLoader()).loadPppp()
        val text = recorder.bufferText()
        assertTrue(text.contains("BLOCK_LOAD_NATIVE] abi=jvm pppp=ok tutk=skipped"), text)
    }
    // END_BLOCK_NATIVE_LIBS_TESTS

    // START_BLOCK_RUNTIME_TESTS
    @Test
    fun `missing libs give Unavailable with zero native calls`() {
        val fake = FakePpppNative()
        val rt = runtime(fake, RecordingLoader(mapOf("PPPP_API" to UnsatisfiedLinkError("no lib"))))
        assertEquals(RuntimeState.Unavailable, rt.ensureInitialized())
        assertEquals(RuntimeState.Unavailable, rt.state.value)
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun `loaded libs init in vendor order with debug off`() {
        val fake = FakePpppNative()
        val state = runtime(fake).ensureInitialized()
        assertEquals(RuntimeState.Ready(0xA2050401.toInt()), state)
        assertEquals(
            listOf("initialize maxSessions=12", "configDebug enable=0 level=4194311", "apiVersion"),
            fake.calls.toList(),
        )
    }

    @Test
    fun `already initialized counts as ready`() {
        val fake = FakePpppNative().apply { initResult = PpppCodes.ALREADY_INITIALIZED }
        assertTrue(runtime(fake).ensureInitialized() is RuntimeState.Ready)
    }

    @Test
    fun `negative init result fails without configDebug or apiVersion`() {
        val fake = FakePpppNative().apply { initResult = PpppCodes.INVALID_PARAMETER }
        val rt = runtime(fake)
        assertEquals(RuntimeState.InitFailed(PpppCodes.INVALID_PARAMETER), rt.ensureInitialized())
        assertEquals(listOf("initialize maxSessions=12"), fake.calls.toList())
        assertEquals(RuntimeState.InitFailed(PpppCodes.INVALID_PARAMETER), rt.ensureInitialized())
        assertEquals(1, fake.calls.size)
    }

    @Test
    fun `second call issues zero further native calls`() {
        val fake = FakePpppNative()
        val rt = runtime(fake)
        rt.ensureInitialized()
        val first = fake.calls.size
        assertEquals(RuntimeState.Ready(0xA2050401.toInt()), rt.ensureInitialized())
        assertEquals(first, fake.calls.size)
    }

    @Test
    fun `concurrent callers initialise once`() {
        val fake = FakePpppNative()
        val rt = runtime(fake)
        val start = CountDownLatch(1)
        val results = CopyOnWriteArrayList<RuntimeState>()
        val threads = (1..2).map { thread { start.await(); results += rt.ensureInitialized() } }
        start.countDown()
        threads.forEach { it.join(5_000) }
        assertEquals(2, results.size)
        assertTrue(results.all { it == RuntimeState.Ready(0xA2050401.toInt()) })
        assertEquals(3, fake.calls.size)
    }

    @Test
    fun `apiHex is upper-case 8-digit hex`() {
        assertEquals("0xA2050401", RuntimeState.Ready(0xA2050401.toInt()).apiHex)
        assertEquals("0x00000001", RuntimeState.Ready(1).apiHex)
    }

    @Test
    fun `init trace is value-free`() {
        runtime(FakePpppNative()).ensureInitialized()
        val text = recorder.bufferText()
        assertTrue(text.contains("init=0 api=0xA2050401 debug=off"), text)
        RedactionScanner().assertClean(text, RedactionFixtures.ALL_SECRETS)
    }
    // END_BLOCK_RUNTIME_TESTS

    // START_BLOCK_PRIVATE_ADDRESS_TESTS
    @Test
    fun `isPrivateIpv4 classifies synthetic addresses`() {
        assertTrue(AndroidPpppNative.isPrivateIpv4(ascii("192.168.1.23")))
        assertTrue(AndroidPpppNative.isPrivateIpv4(ascii("10.0.0.1")))
        assertTrue(AndroidPpppNative.isPrivateIpv4(ascii("172.20.1.1")))
        assertFalse(AndroidPpppNative.isPrivateIpv4(ascii("203.0.113.7")))
        assertFalse(AndroidPpppNative.isPrivateIpv4(ascii("not.an.ip")))
        assertFalse(AndroidPpppNative.isPrivateIpv4(ByteArray(16)))
    }
    // END_BLOCK_PRIVATE_ADDRESS_TESTS
}
