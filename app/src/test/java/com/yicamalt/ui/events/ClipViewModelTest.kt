// FILE: ClipViewModelTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Verify ClipViewModel (EVT-03, EVT-04): clip load states and texts, camera name / time label, the SD
//     button rule, the OpenSd event, player errors, one load per key, cancellation on a new key and on dismiss.
//   SCOPE: JVM-only with fakes (scripted clip results behind per-key gates, fixed rows, fixed cached cameras); value-
//     free logs checked by RedactionScanner with the synthetic device ids as extra literals.
//   DEPENDS: M-UI-EVENTS (ClipViewModel, ClipSource, ClipLoad), M-CAMERA-LIST (CameraListPort), TraceRecorder,
//     RedactionScanner
//   LINKS: V-M-UI-EVENTS, M-UI-EVENTS
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT

// START_MODULE_MAP
//   FakeClipSource - clip results per key, optional CompletableDeferred gate per key, call counter per key,
//     cancellation counter; rows per key.
//   FakeCameraList - CameraListPort with a fixed cached list; network calls are a test failure.
//   ClipViewModelTest - one test per behaviour of plan 05-05 Task 2.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 05-05: initial clip view-model tests (RED first).
// END_CHANGE_SUMMARY
package com.yicamalt.ui.events

import com.yicamalt.camera.CameraInfo
import com.yicamalt.camera.CameraListPort
import com.yicamalt.database.EventEntity
import com.yicamalt.test.RedactionScanner
import com.yicamalt.test.TraceRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

private val CLIP_ZONE: ZoneId = ZoneId.of("Europe/Moscow")
private val EVENT_MS: Long = LocalDateTime.of(2026, 10, 28, 14, 5, 7).atZone(CLIP_ZONE).toInstant().toEpochMilli()
private const val DEV_A = "dev-clip-a"
private const val DEV_GONE = "dev-clip-gone"

private fun clipRow(key: String, device: String, timeMs: Long = EVENT_MS) =
    EventEntity(key, device, timeMs, 1, 0, "mp4", true, 6, null, 0L)

private class FakeClipSource : ClipSource {
    val results = mutableMapOf<String, ClipLoad>()
    val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
    val rows = mutableMapOf<String, EventEntity>()
    val calls = mutableMapOf<String, Int>()
    var cancelled = 0

    override suspend fun clip(key: String): ClipLoad {
        calls[key] = (calls[key] ?: 0) + 1
        try {
            gates[key]?.await()
        } catch (e: CancellationException) {
            cancelled++
            throw e
        }
        return results[key] ?: ClipLoad.Failed("no-clip")
    }

    override suspend fun event(key: String): EventEntity? = rows[key]
}

private class FakeCameraList(private val cached: List<CameraInfo>) : CameraListPort {
    override suspend fun getCameraList(): List<CameraInfo> = error("network camera list must not be called")
    override suspend fun cachedCameras(): List<CameraInfo> = cached
}

@OptIn(ExperimentalCoroutinesApi::class)
class ClipViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val recorder = TraceRecorder()
    private val scanner = RedactionScanner()
    private val source = FakeClipSource()
    private val file = File("clip-test.mp4")
    private val seen = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        recorder.plant()
        source.rows["k1"] = clipRow("k1", DEV_A)
        source.rows["k2"] = clipRow("k2", DEV_A, EVENT_MS + 60_000)
        source.rows["k-gone"] = clipRow("k-gone", DEV_GONE)
        source.results["k1"] = ClipLoad.Ready(file)
        source.results["k2"] = ClipLoad.Ready(File("clip-test-2.mp4"))
        source.results["k-gone"] = ClipLoad.Ready(file)
    }

    @AfterEach
    fun tearDown() {
        scanner.assertClean(recorder.bufferText() + "\n" + seen.joinToString("\n"), extraLiterals = listOf(DEV_A, DEV_GONE))
        recorder.unplant()
        recorder.reset()
        Dispatchers.resetMain()
    }

    private fun vm() = ClipViewModel(
        source,
        FakeCameraList(listOf(CameraInfo(DEV_A, "Прихожая", "M", online = true, streamUrl = null))),
        { CLIP_ZONE },
    )

    private fun ClipViewModel.snap(): ClipUi? = ui.value.also { seen += it.toString() }

    private fun TestScope.collect(vm: ClipViewModel): MutableList<ClipEvent> {
        val out = mutableListOf<ClipEvent>()
        backgroundScope.launch { vm.events.collect { out += it } }
        return out
    }

    @Test
    fun `open shows loading then ready with the file`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        source.gates["k1"] = gate
        val vm = vm()
        assertNull(vm.snap())
        vm.open("k1")
        assertEquals(ClipState.Loading, vm.snap()?.state)
        advanceUntilIdle()
        assertEquals(ClipState.Loading, vm.snap()?.state)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(ClipState.Ready(file), vm.snap()?.state)
        recorder.assertMarkerAppeared("BLOCK_CLIP_LOAD")
    }

    @Test
    fun `unsupported shows Клип недоступен`() = runTest(dispatcher) {
        source.results["k1"] = ClipLoad.Unsupported
        val vm = vm()
        vm.open("k1")
        advanceUntilIdle()
        assertEquals(ClipState.Message("Клип недоступен"), vm.snap()?.state)
    }

    @Test
    fun `expired shows Клип устарел, обновите ленту`() = runTest(dispatcher) {
        source.results["k1"] = ClipLoad.Expired
        val vm = vm()
        vm.open("k1")
        advanceUntilIdle()
        assertEquals(ClipState.Message("Клип устарел, обновите ленту"), vm.snap()?.state)
    }

    @Test
    fun `failed shows Не удалось загрузить клип`() = runTest(dispatcher) {
        source.results["k1"] = ClipLoad.Failed("http")
        val vm = vm()
        vm.open("k1")
        advanceUntilIdle()
        assertEquals(ClipState.Message("Не удалось загрузить клип"), vm.snap()?.state)
        // a row that is gone is a load failure too
        val other = vm()
        other.open("k-missing")
        advanceUntilIdle()
        assertEquals(ClipState.Message("Не удалось загрузить клип"), other.snap()?.state)
        assertFalse(other.snap()!!.sdEnabled)
    }

    @Test
    fun `ready state carries camera name and time label but no device id in toString`() = runTest(dispatcher) {
        val vm = vm()
        vm.open("k1")
        advanceUntilIdle()
        val ui = vm.snap()!!
        assertEquals("Прихожая", ui.cameraName)
        assertEquals("28 октября, 14:05:07", ui.timeLabel)
        assertFalse(ui.toString().contains(DEV_A))
        assertFalse(ui.toString().contains(file.path))
    }

    @Test
    fun `sd button enabled only when the camera is in the camera list`() = runTest(dispatcher) {
        val vm = vm()
        vm.open("k1")
        advanceUntilIdle()
        assertTrue(vm.snap()!!.sdEnabled)
        assertEquals("Открыть запись на SD", vm.snap()!!.sdLabel)
        vm.open("k-gone")
        advanceUntilIdle()
        assertFalse(vm.snap()!!.sdEnabled)
        assertEquals("Камера недоступна", vm.snap()!!.sdLabel)
        assertEquals("Камера", vm.snap()!!.cameraName)
    }

    @Test
    fun `openSd emits OpenSd with device id and event time and closes the dialog`() = runTest(dispatcher) {
        val vm = vm()
        val events = collect(vm)
        vm.open("k1")
        advanceUntilIdle()
        vm.openSd()
        advanceUntilIdle()
        val e = events.single() as ClipEvent.OpenSd
        assertEquals(DEV_A, e.deviceId)
        assertEquals(EVENT_MS, e.timeMs)
        assertEquals("OpenSd", e.toString())
        assertNull(vm.snap())
        recorder.assertMarkerAppeared("BLOCK_CLIP_OPEN_SD")
        // disabled button: no event
        vm.open("k-gone")
        advanceUntilIdle()
        vm.openSd()
        advanceUntilIdle()
        assertEquals(1, events.size)
    }

    @Test
    fun `player error switches to Не удалось воспроизвести клип and keeps the sd button`() = runTest(dispatcher) {
        val vm = vm()
        vm.open("k1")
        advanceUntilIdle()
        vm.onPlayerError(1)
        val ui = vm.snap()!!
        assertEquals(ClipState.Message("Не удалось воспроизвести клип"), ui.state)
        assertTrue(ui.sdEnabled)
        assertEquals("Прихожая", ui.cameraName)
    }

    @Test
    fun `reopening the same key while loading does not start a second download`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        source.gates["k1"] = gate
        val vm = vm()
        vm.open("k1")
        advanceUntilIdle()
        vm.open("k1")
        advanceUntilIdle()
        assertEquals(1, source.calls["k1"])
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(ClipState.Ready(file), vm.snap()?.state)
        assertEquals(1, source.calls["k1"])
    }

    @Test
    fun `open with a new key resets the state and cancels the old load`() = runTest(dispatcher) {
        val gateA = CompletableDeferred<Unit>()
        source.gates["k1"] = gateA
        val gateB = CompletableDeferred<Unit>()
        source.gates["k2"] = gateB
        val vm = vm()
        vm.open("k1")
        advanceUntilIdle()
        vm.open("k2")
        assertEquals(ClipState.Loading, vm.snap()?.state)
        advanceUntilIdle()
        assertEquals(1, source.cancelled)
        gateA.complete(Unit)
        advanceUntilIdle()
        assertEquals(ClipState.Loading, vm.snap()?.state)
        gateB.complete(Unit)
        advanceUntilIdle()
        assertEquals(ClipState.Ready(File("clip-test-2.mp4")), vm.snap()?.state)
        assertEquals("28 октября, 14:06:07", vm.snap()!!.timeLabel)
    }

    @Test
    fun `dismiss cancels an in-flight load`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        source.gates["k1"] = gate
        val vm = vm()
        vm.open("k1")
        advanceUntilIdle()
        vm.dismiss()
        advanceUntilIdle()
        assertEquals(1, source.cancelled)
        assertNull(vm.snap())
        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(vm.snap())
        // reopening after dismiss loads again
        source.gates.remove("k1")
        vm.open("k1")
        advanceUntilIdle()
        assertEquals(ClipState.Ready(file), vm.snap()?.state)
        assertEquals(2, source.calls["k1"])
    }
}
