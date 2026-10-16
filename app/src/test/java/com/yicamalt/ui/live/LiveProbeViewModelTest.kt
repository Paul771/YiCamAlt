// FILE: LiveProbeViewModelTest.kt
// VERSION: 0.2.0
// START_MODULE_CONTRACT
//   PURPOSE: Verify the debug live-probe orchestration: refusals, one factory.open per press off Main, no start after
//     close during an in-flight open, status line and Russian messages, close on leave, decoder reset -> pipeline reset.
//   SCOPE: JVM-only; fake CameraLinkFactory / CameraLink / VideoSinkPort / HevcProbe; synthetic credentials.
//   DEPENDS: M-UI-LIVE-PROBE (LiveProbeViewModel), M-STREAM-CORE (ports, FramePipeline), TraceRecorder,
//     RedactionScanner, RedactionFixtures
//   LINKS: V-M-UI-LIVE-PROBE, M-UI-LIVE-PROBE
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT

// START_MODULE_MAP
//   FakeLink - CameraLink with a controllable state flow; counts start / close and captures the sink; close()
//     publishes Closed(reason) like a real link unless holdClose (teardown still running).
//   CountingSource - P2pCredentialsSource counting refreshCredentials calls; refreshed value configurable.
//   FakeFactory - CameraLinkFactory recording calls, thread names and options; optional latch gate.
//   FakeDecoder - VideoSinkPort with a controllable status, reset events, frame and release counters.
//   FakeProvider / FakeHevc - injectable seams replaced by subclasses.
//   LiveProbeViewModelTest - one test per behaviour of plan 02-09 Task 1 plus the Phase 2 review fixes (single
//     WRONG_PASSWORD retry, Connect disabled while the link drains, auth pending, transport code names).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.2.0 - Phase 2 review MED-4 / LOW-8 / LOW-10: retry once then success, retry once then final
//     failure (exactly 2 opens), no retry on other errors or after Stop, Connect disabled until the link is closed,
//     auth=pending, TUTK code names.
//   PREVIOUS: v0.1.0 - GSD plan 02-09: initial tests.
// END_CHANGE_SUMMARY
package com.yicamalt.ui.live

import android.view.Surface
import androidx.lifecycle.ViewModelStore
import com.yicamalt.camera.P2pCredentials
import com.yicamalt.camera.TransportType
import com.yicamalt.media.DecoderStatus
import com.yicamalt.stream.CameraLink
import com.yicamalt.stream.CameraLinkFactory
import com.yicamalt.stream.DecodableFrame
import com.yicamalt.stream.FrameInfo
import com.yicamalt.stream.LinkError
import com.yicamalt.stream.LinkErrorKind
import com.yicamalt.stream.LinkInfo
import com.yicamalt.stream.LinkMode
import com.yicamalt.stream.LinkOptions
import com.yicamalt.stream.LinkState
import com.yicamalt.stream.P2pCredentialsSource
import com.yicamalt.stream.VideoUnit
import com.yicamalt.stream.VideoUnitSink
import com.yicamalt.test.RedactionFixtures
import com.yicamalt.test.RedactionScanner
import com.yicamalt.test.TraceRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val DEVICE_ID = "cam-SYNTH"
private const val SYNTH_DID = "TNPSYN-123456-ABCDE"
private const val SYNTH_PW = "SYNTHpw1"
private const val SYNTH_INIT = "SYNTHINITSTRINGSYNTHINITSTRINGSYNTHINIT"
private const val SYNTH_LIC = "SYNTHLIC01"

private fun creds(transport: TransportType = TransportType.TNP, encrypt: Boolean = false) = P2pCredentials(
    deviceId = DEVICE_ID,
    p2pId = SYNTH_DID,
    transport = transport,
    password = SYNTH_PW,
    initString = SYNTH_INIT,
    licenseKey = SYNTH_LIC,
    p2pEncrypt = encrypt,
    wakeup = false,
    needsPincode = false,
    transportVerified = true,
)

private class FakeLink(override val deviceId: String, initial: LinkState) : CameraLink {
    val flow = MutableStateFlow(initial)
    @Volatile var holdClose = false
    override val state = flow
    val startCalls = AtomicInteger()
    val closeCalls = AtomicInteger()
    val closeReasons = CopyOnWriteArrayList<String>()
    @Volatile var sink: VideoUnitSink? = null

    override fun start(sink: VideoUnitSink) {
        startCalls.incrementAndGet()
        this.sink = sink
    }

    override fun close(reason: String) {
        closeCalls.incrementAndGet()
        closeReasons += reason
        if (!holdClose) flow.update { if (it is LinkState.Failed || it is LinkState.Closed) it else LinkState.Closed(reason) }
    }
}

private class CountingSource(@Volatile var stored: P2pCredentials?) : P2pCredentialsSource {
    val refreshCalls = AtomicInteger()
    @Volatile var refreshed: P2pCredentials? = null
    override fun credentialsFor(deviceId: String): P2pCredentials? = if (deviceId == DEVICE_ID) stored else null
    override suspend fun refreshCredentials(deviceId: String): P2pCredentials? {
        refreshCalls.incrementAndGet()
        return refreshed ?: credentialsFor(deviceId)
    }
}

private class FakeFactory : CameraLinkFactory {
    val calls = AtomicInteger()
    val threads = CopyOnWriteArrayList<String>()
    val options = CopyOnWriteArrayList<LinkOptions>()
    val links = CopyOnWriteArrayList<FakeLink>()
    @Volatile var gate: CountDownLatch? = null
    @Volatile var initial: LinkState = LinkState.Idle
    @Volatile var holdClose = false
    val passwords = CopyOnWriteArrayList<String?>()

    override fun open(credentials: P2pCredentials, options: LinkOptions): CameraLink {
        calls.incrementAndGet()
        threads += Thread.currentThread().name
        this.options += options
        passwords += credentials.password
        gate?.await(5, TimeUnit.SECONDS)
        return FakeLink(credentials.deviceId, initial).also {
            it.holdClose = holdClose
            links += it
        }
    }
}

private class FakeDecoder : VideoSinkPort {
    override val status = MutableStateFlow<DecoderStatus>(DecoderStatus.Idle)
    override val resetEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val frames = CopyOnWriteArrayList<DecodableFrame>()
    val releaseCalls = AtomicInteger()
    val attachCalls = AtomicInteger()
    val detachCalls = AtomicInteger()
    override fun attachSurface(surface: Surface) { attachCalls.incrementAndGet() }
    override fun detachSurface() { detachCalls.incrementAndGet() }
    override fun release() { releaseCalls.incrementAndGet() }
    override fun onFrame(frame: DecodableFrame) { frames += frame }
    override fun onUnsupportedCodec(codecId: Int) = Unit
}

private class FakeProvider : VideoSinkProvider() {
    val created = CopyOnWriteArrayList<FakeDecoder>()
    override fun create(): VideoSinkPort = FakeDecoder().also { created += it }
}

private class FakeHevc : HevcProbe() {
    @Volatile var thread: String? = null
    override fun describe(): String {
        thread = Thread.currentThread().name
        return "HEVC-декодер: c2.synth.hevc.decoder (hw)"
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LiveProbeViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val recorder = TraceRecorder()
    private val scanner = RedactionScanner()
    private val factory = FakeFactory()
    private val provider = FakeProvider()
    private val hevc = FakeHevc()
    private val seenUi = CopyOnWriteArrayList<String>()
    private var vm: LiveProbeViewModel? = null

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        recorder.plant()
    }

    @AfterEach
    fun tearDown() {
        vm?.close("screen-left")
        val buffer = recorder.bufferText() + "\n" + seenUi.joinToString("\n")
        scanner.assertClean(
            buffer,
            extraLiterals = RedactionFixtures.ALL_SECRETS + listOf(DEVICE_ID, SYNTH_DID, SYNTH_PW, SYNTH_INIT, SYNTH_LIC),
        )
        recorder.unplant()
        recorder.reset()
        Dispatchers.resetMain()
    }

    private var source = CountingSource(null)

    private fun newVm(c: P2pCredentials?): LiveProbeViewModel {
        source = CountingSource(c)
        return LiveProbeViewModel(source, factory, provider, hevc).also { vm = it }
    }

    private fun LiveProbeViewModel.snap(): LiveProbeUi =
        ui.value.also { seenUi += "${it.statusLine} | ${it.message} | ${it.hevcInfo} | ${it.title}" }

    // Real-time wait for work on Dispatchers.Default, draining the Main test dispatcher between polls.
    private fun TestScope.awaitCond(what: String, timeoutMs: Long = 3_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            runCurrent()
            if (cond()) break
            if (System.currentTimeMillis() > end) fail<Unit>("timed out waiting for $what")
            Thread.sleep(5)
        }
        runCurrent()
    }

    private fun TestScope.connectedVm(c: P2pCredentials = creds()): Pair<LiveProbeViewModel, FakeLink> {
        val v = newVm(c)
        v.open(DEVICE_ID)
        v.connect()
        awaitCond("start") { factory.links.firstOrNull()?.startCalls?.get() == 1 }
        return v to factory.links.single()
    }

    private val tnpP2pLan = LinkInfo(TransportType.TNP, LinkMode.P2P, lan = true, connectMs = 120)

    // START_BLOCK_REFUSALS
    @Test
    fun `no credentials - explicit message and Connect disabled`() = runTest(dispatcher) {
        val v = newVm(null)
        v.open(DEVICE_ID)
        runCurrent()
        val ui = v.snap()
        assertEquals("Нет P2P-данных камеры: обновите список камер", ui.message)
        assertFalse(ui.canConnect)
        v.connect()
        Thread.sleep(50)
        assertEquals(0, factory.calls.get())
    }

    @Test
    fun `unknown and langtao transports are refused before any factory call`() = runTest(dispatcher) {
        val v1 = newVm(creds(TransportType.UNKNOWN))
        v1.open(DEVICE_ID)
        assertEquals("Транспорт камеры не определён: обновите список камер", v1.snap().message)
        assertFalse(v1.snap().canConnect)
        v1.connect()
        val v2 = newVm(creds(TransportType.LANGTAO))
        v2.open(DEVICE_ID)
        assertEquals("Транспорт Langtao не поддерживается", v2.snap().message)
        assertFalse(v2.snap().canConnect)
        v2.connect()
        Thread.sleep(50)
        assertEquals(0, factory.calls.get())
    }

    @Test
    fun `TUTK refused by the factory shows the TUTK text`() = runTest(dispatcher) {
        factory.initial = LinkState.Failed(LinkError(LinkErrorKind.UNSUPPORTED_TRANSPORT, 0))
        val v = newVm(creds(TransportType.TUTK))
        v.open(DEVICE_ID)
        assertTrue(v.snap().canConnect)
        v.connect()
        awaitCond("failed") { v.ui.value.message != null }
        assertEquals("TUTK-камеры пока не поддерживаются", v.snap().message)
        assertTrue(v.snap().canConnect)
        assertTrue(v.snap().statusLine.contains("error=UNSUPPORTED_TRANSPORT"))
    }

    @Test
    fun `every LinkErrorKind has a non-blank Russian text`() {
        for (kind in LinkErrorKind.entries) {
            for (t in TransportType.entries) {
                val text = linkErrorMessage(LinkError(kind, -3003), t)
                assertTrue(text.isNotBlank(), "$kind/$t")
                assertTrue(text.any { it in 'а'..'я' || it in 'А'..'Я' }, "$kind/$t not Russian: $text")
            }
        }
        assertEquals("Внутренняя ошибка P2P (код TIME_OUT)", linkErrorMessage(LinkError(LinkErrorKind.INTERNAL, -3003), null))
        assertEquals(
            "Нет P2P-данных камеры (пароль/лицензия): обновите список камер",
            linkErrorMessage(LinkError(LinkErrorKind.NO_CREDENTIALS, 0), TransportType.TNP),
        )
    }
    // END_BLOCK_REFUSALS

    // START_BLOCK_THREADING
    @Test
    fun `factory open runs off Main and a blocking factory does not block connect`() = runTest(dispatcher) {
        val gate = CountDownLatch(1)
        factory.gate = gate
        val v = newVm(creds())
        v.open(DEVICE_ID)
        val t0 = System.nanoTime()
        v.connect()
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(ms < 500, "connect() blocked for $ms ms")
        awaitCond("factory call") { factory.calls.get() == 1 }
        Thread.sleep(1_000)
        gate.countDown()
        awaitCond("start") { factory.links.firstOrNull()?.startCalls?.get() == 1 }
        val testThread = Thread.currentThread().name
        assertNotEquals(testThread, factory.threads.single())
        awaitCond("hevc") { v.ui.value.hevcInfo != LiveProbeTexts.HEVC_PENDING }
        assertNotEquals(testThread, hevc.thread)
        assertEquals("HEVC-декодер: c2.synth.hevc.decoder (hw)", v.snap().hevcInfo)
    }

    @Test
    fun `close during an in-flight open - link closed once and never started`() = runTest(dispatcher) {
        val gate = CountDownLatch(1)
        factory.gate = gate
        val v = newVm(creds())
        v.open(DEVICE_ID)
        v.connect()
        awaitCond("factory call") { factory.calls.get() == 1 }
        v.close("screen-left")
        assertEquals(1, provider.created.single().releaseCalls.get())
        gate.countDown()
        awaitCond("late close") { factory.links.firstOrNull()?.closeCalls?.get() == 1 }
        Thread.sleep(100)
        val link = factory.links.single()
        assertEquals(0, link.startCalls.get())
        assertEquals(1, link.closeCalls.get())
        assertEquals(listOf("screen-left"), link.closeReasons.toList())
    }

    @Test
    fun `two presses while the first open is in flight call the factory once`() = runTest(dispatcher) {
        val gate = CountDownLatch(1)
        factory.gate = gate
        val v = newVm(creds())
        v.open(DEVICE_ID)
        v.connect()
        assertFalse(v.ui.value.canConnect)
        assertTrue(v.ui.value.canStop)
        v.connect()
        gate.countDown()
        awaitCond("start") { factory.links.firstOrNull()?.startCalls?.get() == 1 }
        Thread.sleep(100)
        v.connect()
        runCurrent()
        assertEquals(1, factory.calls.get())
        assertEquals(1, provider.created.size)
    }
    // END_BLOCK_THREADING

    // START_BLOCK_CONNECT_AND_STATUS
    @Test
    fun `connect opens once with the relay option and starts a FramePipeline`() = runTest(dispatcher) {
        val (v, link) = connectedVm()
        assertEquals(listOf(LinkOptions(false)), factory.options.toList())
        assertTrue(link.sink is com.yicamalt.stream.FramePipeline)
        link.flow.value = LinkState.Connecting
        runCurrent()
        v.connect()
        link.flow.value = LinkState.Streaming(tnpP2pLan, authOk = true)
        runCurrent()
        v.connect()
        runCurrent()
        assertEquals(1, factory.calls.get())
        assertFalse(v.snap().canConnect)
    }

    @Test
    fun `force relay is passed to the factory`() = runTest(dispatcher) {
        val v = newVm(creds())
        v.open(DEVICE_ID)
        v.setForceRelay(true)
        v.connect()
        awaitCond("factory call") { factory.links.isNotEmpty() }
        assertEquals(listOf(LinkOptions(true)), factory.options.toList())
        assertTrue(v.snap().statusLine.contains("relayForced=yes"))
    }

    @Test
    fun `streaming status line shows mode, lan, codec and decoder`() = runTest(dispatcher) {
        val (v, link) = connectedVm()
        link.flow.value = LinkState.Streaming(tnpP2pLan, authOk = true)
        val ready = DecoderStatus.Ready("video/avc", "c2.synth.avc.decoder", hw = true, lowLatency = true)
        provider.created.single().status.value = DecoderStatus.Rendering(ready, framesRendered = 10, fps = 14.8)
        runCurrent()
        val line = v.snap().statusLine
        assertTrue(line.contains("state=STREAMING mode=P2P lan=yes"), line)
        assertTrue(line.contains("transport=TNP"), line)
        assertTrue(line.contains("codec=H264 decoder=c2.synth.avc.decoder hw=yes fps=14.8"), line)
        assertTrue(line.contains("auth=ok error=-"), line)
        assertNull(v.snap().message)
        assertTrue(v.snap().canStop)
        recorder.assertMarkerAppeared("BLOCK_STATUS")
    }

    private fun TestScope.failWith(state: LinkState): Pair<LiveProbeViewModel, FakeLink> {
        val (v, link) = connectedVm()
        link.flow.value = LinkState.Connected(tnpP2pLan)
        runCurrent()
        link.flow.value = state
        runCurrent()
        return v to link
    }

    @Test
    fun `offline failure names the code and never reconnects by itself`() = runTest(dispatcher) {
        val (v, link) = failWith(LinkState.Failed(LinkError(LinkErrorKind.OFFLINE, -3006)))
        val ui = v.snap()
        assertTrue(ui.statusLine.contains("error=DEVICE_NOT_ONLINE"), ui.statusLine)
        assertTrue(ui.statusLine.contains("state=FAILED"), ui.statusLine)
        assertEquals("Камера не в сети", ui.message)
        assertTrue(ui.canConnect)
        assertFalse(ui.canStop)
        awaitCond("close after failure") { link.closeCalls.get() == 1 }
        advanceTimeBy(30_000)
        runCurrent()
        Thread.sleep(200)
        runCurrent()
        assertEquals(1, factory.calls.get())
        assertEquals(1, provider.created.single().releaseCalls.get())
    }

    @Test
    fun `wrong password, native libs, kicked map to explicit texts`() = runTest(dispatcher) {
        val (v1, _) = failWith(LinkState.Failed(LinkError(LinkErrorKind.WRONG_PASSWORD, -20009)))
        assertEquals(LiveProbeTexts.RETRYING, v1.snap().message)
        advanceTimeBy(LiveProbeViewModel.RETRY_DELAY_MS)
        awaitCond("retry start") { factory.links.size == 2 && factory.links[1].startCalls.get() == 1 }
        factory.links[1].flow.value = LinkState.Failed(LinkError(LinkErrorKind.WRONG_PASSWORD, -20009))
        runCurrent()
        assertEquals("Неверный пароль камеры", v1.snap().message)
        assertTrue(v1.snap().statusLine.contains("auth=fail"))
        v1.close("screen-left")
        factory.links.clear()

        val (v2, _) = failWith(LinkState.Failed(LinkError(LinkErrorKind.NATIVE_UNAVAILABLE, 0)))
        assertEquals("Нативные библиотеки не загружены", v2.snap().message)
        assertTrue(v2.snap().statusLine.contains("error=NATIVE_UNAVAILABLE"))
        factory.links.clear()

        val (v3, _) = failWith(LinkState.Closed("kicked"))
        assertEquals("Сессию закрыла камера (другой клиент)", v3.snap().message)
        assertTrue(v3.snap().canConnect)
    }

    @Test
    fun `missing HEVC decoder is shown as an explicit message`() = runTest(dispatcher) {
        val (v, link) = connectedVm()
        link.flow.value = LinkState.Streaming(tnpP2pLan, authOk = true)
        provider.created.single().status.value = DecoderStatus.NoDecoder("video/hevc")
        runCurrent()
        assertEquals("H.265: на этом телефоне нет декодера", v.snap().message)
        assertTrue(v.snap().statusLine.contains("codec=H265 decoder=none"), v.snap().statusLine)
    }
    // END_BLOCK_CONNECT_AND_STATUS

    // START_BLOCK_RETRY_TESTS
    private fun TestScope.firstFailsWrongPassword(): LiveProbeViewModel {
        val (v, link) = connectedVm()
        link.flow.value = LinkState.Connected(tnpP2pLan)
        runCurrent()
        link.flow.value = LinkState.Failed(LinkError(LinkErrorKind.WRONG_PASSWORD, -20009))
        runCurrent()
        return v
    }

    @Test
    fun `wrong password retries once after 2 s with refreshed credentials and streams`() = runTest(dispatcher) {
        val v = firstFailsWrongPassword()
        source.refreshed = creds().copy(password = "SYNTHpw2")
        val ui = v.snap()
        assertEquals(LiveProbeTexts.RETRYING, ui.message)
        assertTrue(ui.statusLine.contains("state=CONNECTING"), ui.statusLine)
        assertFalse(ui.canConnect)
        assertTrue(ui.canStop)
        assertEquals(1, provider.created.single().releaseCalls.get())
        advanceTimeBy(LiveProbeViewModel.RETRY_DELAY_MS - 1)
        runCurrent()
        Thread.sleep(50)
        runCurrent()
        assertEquals(1, factory.calls.get())
        assertEquals(0, source.refreshCalls.get())
        advanceTimeBy(1)
        awaitCond("retry start") { factory.links.size == 2 && factory.links[1].startCalls.get() == 1 }
        assertEquals(1, source.refreshCalls.get())
        assertEquals(listOf<String?>(SYNTH_PW, "SYNTHpw2"), factory.passwords.toList())
        factory.links[1].flow.value = LinkState.Streaming(tnpP2pLan, authOk = true)
        runCurrent()
        assertNull(v.snap().message)
        assertTrue(v.snap().statusLine.contains("state=STREAMING"))
        assertEquals(2, provider.created.size)
        recorder.assertMarkerAppeared("BLOCK_RETRY")
        scanner.assertClean(recorder.bufferText(), extraLiterals = listOf("SYNTHpw2"))
    }

    @Test
    fun `second wrong password is final - exactly two factory opens`() = runTest(dispatcher) {
        val v = firstFailsWrongPassword()
        advanceTimeBy(LiveProbeViewModel.RETRY_DELAY_MS)
        awaitCond("retry start") { factory.links.size == 2 && factory.links[1].startCalls.get() == 1 }
        factory.links[1].flow.value = LinkState.Failed(LinkError(LinkErrorKind.WRONG_PASSWORD, -20009))
        runCurrent()
        assertEquals("Неверный пароль камеры", v.snap().message)
        assertTrue(v.snap().canConnect)
        assertFalse(v.snap().canStop)
        advanceTimeBy(60_000)
        runCurrent()
        Thread.sleep(100)
        runCurrent()
        assertEquals(2, factory.calls.get())
        assertEquals(1, source.refreshCalls.get())
    }

    @Test
    fun `other errors never retry`() = runTest(dispatcher) {
        listOf(
            LinkError(LinkErrorKind.TIMEOUT, 0),
            LinkError(LinkErrorKind.PROTOCOL, -9000),
            LinkError(LinkErrorKind.REMOTE_CLOSED, -3012),
        ).forEach { err ->
            factory.links.clear()
            factory.calls.set(0)
            val (v, _) = failWith(LinkState.Failed(err))
            assertNotEquals(LiveProbeTexts.RETRYING, v.snap().message)
            advanceTimeBy(10_000)
            runCurrent()
            Thread.sleep(50)
            runCurrent()
            assertEquals(1, factory.calls.get(), err.toString())
            v.close("screen-left")
        }
        assertEquals(0, source.refreshCalls.get())
    }

    @Test
    fun `stop during the retry pause cancels the retry`() = runTest(dispatcher) {
        val v = firstFailsWrongPassword()
        v.stop()
        val ui = v.snap()
        assertTrue(ui.statusLine.contains("state=CLOSED"), ui.statusLine)
        assertNull(ui.message)
        assertTrue(ui.canConnect)
        advanceTimeBy(10_000)
        runCurrent()
        Thread.sleep(100)
        runCurrent()
        assertEquals(1, factory.calls.get())
        assertEquals(0, source.refreshCalls.get())
    }

    @Test
    fun `user stop of a streaming session never retries`() = runTest(dispatcher) {
        val (v, link) = connectedVm()
        link.flow.value = LinkState.Streaming(tnpP2pLan, authOk = true)
        runCurrent()
        v.stop()
        awaitCond("close") { link.closeCalls.get() == 1 }
        link.flow.value = LinkState.Failed(LinkError(LinkErrorKind.WRONG_PASSWORD, -20009))
        advanceTimeBy(10_000)
        runCurrent()
        Thread.sleep(50)
        runCurrent()
        assertEquals(1, factory.calls.get())
    }
    // END_BLOCK_RETRY_TESTS

    // START_BLOCK_DRAIN_AND_STATUS_TESTS
    @Test
    fun `Connect stays disabled until the closed link reports Closed`() = runTest(dispatcher) {
        factory.holdClose = true
        val (v, link) = connectedVm()
        link.flow.value = LinkState.Streaming(tnpP2pLan, authOk = true)
        runCurrent()
        v.stop()
        awaitCond("close") { link.closeCalls.get() == 1 }
        assertFalse(v.snap().canConnect)
        assertFalse(v.snap().canStop)
        v.connect()
        runCurrent()
        assertEquals(1, factory.calls.get())
        link.flow.value = LinkState.Closed("user")
        runCurrent()
        assertTrue(v.snap().canConnect)
        v.connect()
        awaitCond("second open") { factory.calls.get() == 2 }
    }

    @Test
    fun `auth is pending while streaming without an auth answer`() = runTest(dispatcher) {
        val (v, link) = connectedVm()
        link.flow.value = LinkState.Connected(tnpP2pLan)
        runCurrent()
        assertTrue(v.snap().statusLine.contains("auth=- "), v.snap().statusLine)
        link.flow.value = LinkState.Streaming(tnpP2pLan, authOk = false)
        runCurrent()
        assertTrue(v.snap().statusLine.contains("auth=pending"), v.snap().statusLine)
        link.flow.value = LinkState.Streaming(tnpP2pLan, authOk = true)
        runCurrent()
        assertTrue(v.snap().statusLine.contains("auth=ok"), v.snap().statusLine)
    }

    @Test
    fun `TUTK error codes use the TUTK name table`() = runTest(dispatcher) {
        val (v, link) = connectedVm(creds(TransportType.TUTK))
        link.flow.value = LinkState.Failed(LinkError(LinkErrorKind.REMOTE_CLOSED, -20015))
        runCurrent()
        assertTrue(v.snap().statusLine.contains("error=AV_ER_SESSION_CLOSE_BY_REMOTE"), v.snap().statusLine)
        assertEquals(
            "Внутренняя ошибка P2P (код IOTC_ER_TIMEOUT)",
            linkErrorMessage(LinkError(LinkErrorKind.INTERNAL, -13), TransportType.TUTK),
        )
        assertEquals("AV_ER_WRONG_VIEWACCorPWD", codeName(-20009, TransportType.TUTK))
        assertEquals("TIME_OUT", codeName(-3003, TransportType.TNP))
    }
    // END_BLOCK_DRAIN_AND_STATUS_TESTS

    // START_BLOCK_CLOSE_ON_LEAVE
    @Test
    fun `onStop closes the link and releases the decoder once, idempotently`() = runTest(dispatcher) {
        val (v, link) = connectedVm()
        link.flow.value = LinkState.Streaming(tnpP2pLan, authOk = true)
        runCurrent()
        v.onStop()
        v.onStop()
        v.close("screen-left")
        awaitCond("close") { link.closeCalls.get() == 1 }
        Thread.sleep(100)
        assertEquals(1, link.closeCalls.get())
        assertEquals(listOf("background"), link.closeReasons.toList())
        assertEquals(1, provider.created.single().releaseCalls.get())
        assertTrue(v.snap().canConnect)
        recorder.assertMarkerAppeared("BLOCK_CLOSE_PROBE")
    }

    @Test
    fun `onCleared closes the session with screen-left`() = runTest(dispatcher) {
        val (v, link) = connectedVm()
        val store = ViewModelStore()
        store.put("probe", v)
        store.clear()
        v.close("user")
        awaitCond("close") { link.closeCalls.get() == 1 }
        Thread.sleep(100)
        assertEquals(listOf("screen-left"), link.closeReasons.toList())
        assertEquals(1, provider.created.single().releaseCalls.get())
    }

    @Test
    fun `stop by user then a new press opens a fresh session`() = runTest(dispatcher) {
        val (v, link) = connectedVm()
        v.stop()
        awaitCond("close") { link.closeCalls.get() == 1 }
        assertEquals(listOf("user"), link.closeReasons.toList())
        assertTrue(v.snap().statusLine.contains("state=CLOSED"))
        v.connect()
        awaitCond("second start") { factory.links.size == 2 && factory.links[1].startCalls.get() == 1 }
        assertEquals(2, factory.calls.get())
        assertEquals(2, provider.created.size)
    }
    // END_BLOCK_CLOSE_ON_LEAVE

    // START_BLOCK_DECODER_RESET
    private fun info(key: Boolean, seq: Int) = FrameInfo(
        codecId = 78, flags = if (key) 1 else 0, liveFlag = 1, onlineNum = 1, useCount = 3, seq = seq,
        width = 1280, height = 720, timestampSec = 0, isDay = 1, cover = 0, outLoss = 0, inLoss = 0, timestampMs = 0,
    )

    private fun nal(vararg header: Int, size: Int): ByteArray =
        ByteArray(size) { i -> if (i < header.size) header[i].toByte() else (0x21 + i % 90).toByte() }

    private fun annexB(vararg nals: ByteArray) = nals.fold(ByteArray(0)) { acc, n -> acc + byteArrayOf(0, 0, 0, 1) + n }

    @Test
    fun `decoder reset event resets the pipeline gate`() = runTest(dispatcher) {
        val (_, link) = connectedVm()
        val sink = link.sink!!
        val decoder = provider.created.single()
        val keyUnit = annexB(nal(0x67, 0x4D, 0x00, 0x28, size = 20), nal(0x68, 0xCE, 0x3C, 0x80, size = 4), nal(0x65, 0x88, 0x84, size = 60))
        val pUnit = annexB(nal(0x41, 0x9A, size = 30))
        sink.onVideoUnit(VideoUnit(2, info(true, 10), keyUnit))
        sink.onVideoUnit(VideoUnit(3, info(false, 11), pUnit))
        assertEquals(2, decoder.frames.size)
        assertTrue(decoder.resetEvents.tryEmit(Unit))
        runCurrent()
        sink.onVideoUnit(VideoUnit(3, info(false, 12), pUnit))
        assertEquals(2, decoder.frames.size, "P-frame after reset must wait for a key frame")
        sink.onVideoUnit(VideoUnit(2, info(true, 13), keyUnit))
        assertEquals(3, decoder.frames.size)
    }
    // END_BLOCK_DECODER_RESET
}
