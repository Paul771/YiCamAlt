// FILE: TutkRuntimeTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Executable contract for TutkRuntime (load as a state, init once per process on "tutk-runtime",
//     already-initialised codes, failures, timeout adoption) and TutkCodes (names, classify).
//   SCOPE: runtime over FakeTutkNative + NativeLibs with a recording loader; TutkCodes table checks; trace hygiene.
//   DEPENDS: M-TUTK-LINK, M-P2P-NATIVE, FakeTutkNative, TraceRecorder, RedactionScanner, RedactionFixtures
//   LINKS: V-M-TUTK-LINK
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.p2p.tutk

import com.yicamalt.p2p.LoadState
import com.yicamalt.p2p.NativeLibs
import com.yicamalt.stream.LinkErrorKind
import com.yicamalt.test.RedactionFixtures
import com.yicamalt.test.RedactionScanner
import com.yicamalt.test.TraceRecorder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

// START_MODULE_MAP
//   RecordingLoader - fake System.loadLibrary: records names, fails for configured names.
//   runtime(...) - TutkRuntime over FakeTutkNative and NativeLibs(loader).
//   runtime tests - unavailable (missing / error), order + thread, -3 / -20031, failures, once-only, concurrency,
//     timeout then adoption, hex, trace hygiene.
//   TutkCodes tests - classify table, name of known and unknown codes.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 02-10: created.
// END_CHANGE_SUMMARY

class TutkRuntimeTest {

    private class RecordingLoader(private val failures: Map<String, Throwable> = emptyMap()) : (String) -> Unit {
        val loaded = CopyOnWriteArrayList<String>()
        override fun invoke(name: String) {
            loaded += name
            failures[name]?.let { throw it }
        }
    }

    private val recorder = TraceRecorder()

    @BeforeEach fun setUp() = recorder.plant()

    @AfterEach fun tearDown() {
        RedactionScanner().assertClean(recorder.bufferText(), RedactionFixtures.ALL_SECRETS)
        recorder.unplant()
    }

    private fun runtime(fake: FakeTutkNative, loader: RecordingLoader = RecordingLoader()) =
        TutkRuntime(fake, NativeLibs(loader))

    // START_BLOCK_TUTK_RUNTIME_TESTS
    @Test
    fun `missing TUTK library gives Unavailable with zero native calls`() {
        val fake = FakeTutkNative()
        val loader = RecordingLoader(mapOf("sCHL" to UnsatisfiedLinkError("no lib")))
        val rt = runtime(fake, loader)
        assertEquals(TutkRuntimeState.Unavailable, rt.ensureInitialized())
        assertEquals(listOf("IOTCAPIs", "sCHL"), loader.loaded)
        assertEquals(emptyList<String>(), fake.calls)
        assertEquals(TutkRuntimeState.Unavailable, rt.state.value)
        assertTrue(recorder.bufferText().contains("[P2P][tutk][BLOCK_INIT_TUTK] result=unavailable reason=libs"))
    }

    @Test
    fun `library load error is Unavailable as well and is cached`() {
        val fake = FakeTutkNative()
        val loader = RecordingLoader(mapOf("IOTCAPIs" to SecurityException("x")))
        val rt = runtime(fake, loader)
        assertEquals(TutkRuntimeState.Unavailable, rt.ensureInitialized())
        assertEquals(TutkRuntimeState.Unavailable, rt.ensureInitialized())
        assertEquals(listOf("IOTCAPIs"), loader.loaded)
        assertEquals(emptyList<String>(), fake.calls)
    }

    @Test
    fun `init runs IOTC then AV(32) then versions, all on the tutk-runtime thread`() {
        val fake = FakeTutkNative()
        val loader = RecordingLoader()
        val state = runtime(fake, loader).ensureInitialized()
        assertEquals(listOf("IOTCAPIs", "sCHL", "AVAPIs"), loader.loaded)
        assertEquals(listOf("initialize", "avInitialize 32", "iotcVersion", "avVersion"), fake.calls)
        assertEquals(setOf(TutkRuntime.THREAD_NAME), fake.threads.toSet())
        assertEquals(TutkRuntimeState.Ready(0x01020304, 0x04030201), state)
        val ready = state as TutkRuntimeState.Ready
        assertEquals("0x01020304", ready.iotcVersionHex)
        assertEquals("0x04030201", ready.avVersionHex)
        assertTrue(
            recorder.bufferText().contains(
                "[P2P][tutk][BLOCK_INIT_TUTK] iotc=0 av=32 iotcVer=0x01020304 avVer=0x04030201 result=ready",
            ),
        )
    }

    @Test
    fun `already-initialised codes of IOTC (-3) and AV (-20031) are Ready`() {
        val fake = FakeTutkNative().apply {
            initResult = TutkCodes.IOTC_ALREADY_INITIALIZED
            avInitResult = TutkCodes.AV_ALREADY_INITIALIZED
        }
        assertTrue(runtime(fake).ensureInitialized() is TutkRuntimeState.Ready)
        assertEquals(4, fake.calls.size)
    }

    @Test
    fun `IOTC init failure is InitFailed and AV is never initialised`() {
        val fake = FakeTutkNative().apply { initResult = TutkCodes.IOTC_FAIL_CREATE_SOCKET }
        assertEquals(TutkRuntimeState.InitFailed(-6), runtime(fake).ensureInitialized())
        assertEquals(listOf("initialize"), fake.calls)
        assertTrue(recorder.bufferText().contains("iotc=-6 name=IOTC_ER_FAIL_CREATE_SOCKET result=failed"))
    }

    @Test
    fun `AV init failure is InitFailed with the AV code and no versions are read`() {
        val fake = FakeTutkNative().apply { avInitResult = TutkCodes.AV_MEM_INSUFF }
        assertEquals(TutkRuntimeState.InitFailed(-20003), runtime(fake).ensureInitialized())
        assertEquals(listOf("initialize", "avInitialize 32"), fake.calls)
    }

    @Test
    fun `later calls make no native call and return the same state`() {
        val fake = FakeTutkNative()
        val loader = RecordingLoader()
        val rt = runtime(fake, loader)
        val first = rt.ensureInitialized()
        val second = rt.ensureInitialized()
        val third = rt.ensureInitialized()
        assertEquals(first, second)
        assertEquals(first, third)
        assertEquals(1, fake.callCount("initialize"))
        assertEquals(1, fake.callCount("avInitialize"))
        assertEquals(3, loader.loaded.size)
    }

    @Test
    fun `failed init is terminal and is never retried`() {
        val fake = FakeTutkNative().apply { initResult = TutkCodes.IOTC_FAIL_CREATE_THREAD }
        val rt = runtime(fake)
        rt.ensureInitialized()
        fake.initResult = TutkCodes.OK
        assertEquals(TutkRuntimeState.InitFailed(-5), rt.ensureInitialized())
        assertEquals(1, fake.callCount("initialize"))
    }

    @Test
    fun `concurrent callers initialise once`() {
        val fake = FakeTutkNative()
        val rt = runtime(fake)
        val start = CountDownLatch(1)
        val results = CopyOnWriteArrayList<TutkRuntimeState>()
        val threads = (1..2).map { thread { start.await(); results += rt.ensureInitialized() } }
        start.countDown()
        threads.forEach { it.join(5_000) }
        assertEquals(2, results.size)
        assertEquals(1, results.toSet().size)
        assertTrue(results[0] is TutkRuntimeState.Ready)
        assertEquals(1, fake.callCount("initialize"))
        assertEquals(1, fake.callCount("avInitialize"))
    }

    @Test
    fun `slow init times out as IOTC_ER_TIMEOUT and the late result is adopted on the next call`() {
        val gate = CountDownLatch(1)
        val fake = FakeTutkNative().apply { initGate = gate }
        val rt = runtime(fake).apply { initTimeoutMs = 100 }
        assertEquals(TutkRuntimeState.InitFailed(TutkCodes.IOTC_TIMEOUT), rt.ensureInitialized())
        assertTrue(recorder.bufferText().contains("[P2P][tutk][BLOCK_INIT_TUTK] result=timeout boundMs=100"))
        gate.countDown()
        val deadline = System.currentTimeMillis() + 5_000
        var state = rt.ensureInitialized()
        while (state !is TutkRuntimeState.Ready && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            state = rt.ensureInitialized()
        }
        assertTrue(state is TutkRuntimeState.Ready)
        assertEquals(1, fake.callCount("initialize"))
    }
    // END_BLOCK_TUTK_RUNTIME_TESTS

    // START_BLOCK_TUTK_CODES_TESTS
    @Test
    fun `classify maps wrong password, remote close and unknown codes`() {
        assertEquals(LinkErrorKind.WRONG_PASSWORD, TutkCodes.classify(-20009))
        assertEquals(LinkErrorKind.REMOTE_CLOSED, TutkCodes.classify(-20015))
        assertEquals(LinkErrorKind.REMOTE_CLOSED, TutkCodes.classify(-20016))
        assertEquals(LinkErrorKind.REMOTE_CLOSED, TutkCodes.classify(-20010))
        assertEquals(LinkErrorKind.INTERNAL, TutkCodes.classify(-12345))
        assertEquals(LinkErrorKind.INTERNAL, TutkCodes.classify(-20012))
        assertEquals(LinkErrorKind.OFFLINE, TutkCodes.classify(-90))
        assertEquals(LinkErrorKind.NETWORK, TutkCodes.classify(-13))
        assertEquals(LinkErrorKind.BUSY, TutkCodes.classify(-48))
        assertEquals(LinkErrorKind.CREDENTIALS, TutkCodes.classify(-15))
        assertEquals(LinkErrorKind.TIMEOUT, TutkCodes.classify(-20011))
    }

    @Test
    fun `name returns vendor constant names and UNKNOWN for codes outside the table`() {
        assertEquals("AV_ER_WRONG_VIEWACCorPWD", TutkCodes.name(-20009))
        assertEquals("AV_ER_DATA_NOREADY", TutkCodes.name(-20012))
        assertEquals("AV_ER_SESSION_CLOSE_BY_REMOTE", TutkCodes.name(-20015))
        assertEquals("IOTC_ER_ALREADY_INITIALIZED", TutkCodes.name(-3))
        assertEquals("IOTC_ER_DEVICE_OFFLINE", TutkCodes.name(-90))
        assertEquals("OK", TutkCodes.name(0))
        assertEquals("UNKNOWN_-777", TutkCodes.name(-777))
    }

    @Test
    fun `init-ok helpers accept success and already-initialised only`() {
        assertTrue(TutkCodes.iotcInitOk(0))
        assertTrue(TutkCodes.iotcInitOk(-3))
        assertTrue(!TutkCodes.iotcInitOk(-12))
        assertTrue(TutkCodes.avInitOk(32))
        assertTrue(TutkCodes.avInitOk(-20031))
        assertTrue(!TutkCodes.avInitOk(-20019))
    }
    // END_BLOCK_TUTK_CODES_TESTS

    @Test
    fun `load state of NativeLibs is shared with the runtime result`() {
        val libs = NativeLibs(RecordingLoader(mapOf("AVAPIs" to UnsatisfiedLinkError("x"))))
        val rt = TutkRuntime(FakeTutkNative(), libs)
        assertEquals(TutkRuntimeState.Unavailable, rt.ensureInitialized())
        assertEquals(LoadState.Missing, libs.loadTutk())
    }
}
