// FILE: LiveSessionSupervisorTest.kt
// VERSION: 0.2.0
// START_MODULE_CONTRACT
//   PURPOSE: Executable contract of the live session supervisor (D-01..D-03, D-10, LIVE-04/05/10/11) in virtual time.
//   SCOPE: classification table, backoff schedule, wrong-password / kick allowances, fatal, healthy reset, network
//     loss / change, stall watchdog, drain, stop / resume, quality requests, log markers + redaction; Phase 4 stream
//     modes (archive / paused stall windows, quality gating), liveEpoch, reply forwarding, SupervisorArchivePort.
//   DEPENDS: M-LIVE-SESSION (LiveSessionSupervisor), LiveFakes, TraceRecorder, RedactionScanner, RedactionFixtures
//   LINKS: V-M-LIVE-SESSION, M-LIVE-SESSION
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT

// START_MODULE_MAP
//   Env - one supervisor wired to fakes on the test scheduler; records every state via an unconfined collector.
//   LiveSessionSupervisorTest - one test per behaviour bullet of plan 03-06 and plan 04-06 Task 1.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.2.0 - GSD plan 04-06: mode-aware stall window, quality gating outside LIVE, liveEpoch, replies,
//     archive port delegation.
//   PREVIOUS: v0.1.0 - GSD plan 03-06 RED: full behaviour suite.
// END_CHANGE_SUMMARY
package com.yicamalt.live

import com.yicamalt.camera.TransportType
import com.yicamalt.stream.FrameObservation
import com.yicamalt.stream.LinkCapabilities
import com.yicamalt.stream.LinkError
import com.yicamalt.stream.LinkErrorKind
import com.yicamalt.stream.LinkInfo
import com.yicamalt.stream.LinkIoCtrlReply
import com.yicamalt.stream.LinkMode
import com.yicamalt.stream.LinkState
import com.yicamalt.stream.StreamMode
import com.yicamalt.test.RedactionFixtures
import com.yicamalt.test.RedactionScanner
import com.yicamalt.test.TraceRecorder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveSessionSupervisorTest {

    private val dispatcher = StandardTestDispatcher()
    private val recorder = TraceRecorder()
    private val scanner = RedactionScanner()

    private val tnpLan = LinkInfo(TransportType.TNP, LinkMode.LAN, lan = true, connectMs = 80)
    private val tutkLan = LinkInfo(TransportType.TUTK, LinkMode.LAN, lan = true, connectMs = 90)

    private fun err(kind: LinkErrorKind, code: Int = -1) = LinkError(kind, code)
    private fun failed(kind: LinkErrorKind, code: Int = -1) = LinkState.Failed(err(kind, code))

    @BeforeEach
    fun setUp() {
        recorder.plant()
    }

    @AfterEach
    fun tearDown() {
        scanner.assertClean(recorder.bufferText(), extraLiterals = RedactionFixtures.ALL_SECRETS + LIVE_SECRETS)
        recorder.unplant()
        recorder.reset()
    }

    private inner class Env(val ts: TestScope, netAvailable: Boolean) {
        val now: () -> Long = { ts.testScheduler.currentTime }
        val factory = FakeLinkFactory(now)
        val sessions = FakeMediaSessionFactory()
        val net = FakeNetworkMonitor(netAvailable)
        val creds = FakeCredentialsSource()
        val sup = LiveSessionSupervisor(
            deviceId = LIVE_DEVICE_ID,
            credentials = creds,
            factory = factory,
            sessions = sessions,
            network = net,
            scope = ts.backgroundScope,
            ioDispatcher = dispatcher,
            now = now,
        )
        val states = mutableListOf<SupervisorState>()

        init {
            ts.backgroundScope.launch(UnconfinedTestDispatcher(ts.testScheduler)) { sup.state.collect { states += it } }
        }

        val link: FakeCameraLink get() = factory.links.last()
        val session: FakeMediaSession get() = sessions.created.last()
        val state: SupervisorState get() = sup.state.value

        fun stream(info: LinkInfo = tnpLan) {
            link.flow.value = LinkState.Streaming(info, authOk = true)
            ts.runCurrent()
        }

        fun advance(ms: Long) {
            ts.advanceTimeBy(ms)
            ts.runCurrent()
        }

        fun startNow() {
            sup.start()
            ts.runCurrent()
        }
    }

    private fun TestScope.env(netAvailable: Boolean = true) = Env(this, netAvailable)

    // START_BLOCK_CLASSIFICATION
    @Test
    fun `classify covers every LinkErrorKind`() {
        val expected = mapOf(
            LinkErrorKind.OFFLINE to ErrorClass.NETWORK,
            LinkErrorKind.NETWORK to ErrorClass.NETWORK,
            LinkErrorKind.TIMEOUT to ErrorClass.NETWORK,
            LinkErrorKind.REMOTE_CLOSED to ErrorClass.NETWORK,
            LinkErrorKind.PROTOCOL to ErrorClass.NETWORK,
            LinkErrorKind.INTERNAL to ErrorClass.NETWORK,
            LinkErrorKind.ALREADY_OPEN to ErrorClass.NETWORK,
            LinkErrorKind.WRONG_PASSWORD to ErrorClass.WRONG_PASSWORD,
            LinkErrorKind.KICKED to ErrorClass.KICK,
            LinkErrorKind.BUSY to ErrorClass.KICK,
            LinkErrorKind.NATIVE_UNAVAILABLE to ErrorClass.FATAL,
            LinkErrorKind.NO_CREDENTIALS to ErrorClass.FATAL,
            LinkErrorKind.UNSUPPORTED_TRANSPORT to ErrorClass.FATAL,
            LinkErrorKind.CREDENTIALS to ErrorClass.FATAL,
        )
        for (kind in LinkErrorKind.entries) {
            assertEquals(expected[kind], classify(LinkError(kind, -1)), kind.name)
        }
        assertEquals(LinkErrorKind.entries.size, expected.size)
    }

    @Test
    fun `classifyClosed maps reasons and returns null for our own stops`() {
        assertEquals(ErrorClass.KICK, classifyClosed("kicked"))
        for (r in listOf("stall", "network-change", "error", "timeout")) assertEquals(ErrorClass.NETWORK, classifyClosed(r), r)
        for (r in listOf("user", "screen-left", "background")) assertNull(classifyClosed(r), r)
    }
    // END_BLOCK_CLASSIFICATION

    // START_BLOCK_SCHEDULE
    @Test
    fun `start opens once and sets the audio sink before start`() = runTest(dispatcher) {
        val e = env()
        e.startNow()
        assertEquals(1, e.factory.opens)
        assertEquals(SupervisorState.Connecting(1), e.state)
        assertEquals(1, e.link.startCalls)
        assertEquals(true, e.link.audioSinkSetBeforeStart)
        assertSame(e.session.audioSink, e.link.audioSinkSet)
        assertSame(e.session.videoSink, e.link.videoSink)
    }

    @Test
    fun `streaming link becomes Live`() = runTest(dispatcher) {
        val e = env()
        e.startNow()
        e.stream(tnpLan)
        assertEquals(SupervisorState.Live(tnpLan, true), e.state)
        assertEquals(LinkState.Streaming(tnpLan, true), e.sup.currentLinkState.value)
    }

    @Test
    fun `five network failures walk the backoff then Exhausted`() = runTest(dispatcher) {
        val e = env()
        e.factory.defaultInitial = failed(LinkErrorKind.TIMEOUT)
        e.startNow()
        e.advance(40_000)
        val t = err(LinkErrorKind.TIMEOUT)
        val n = ErrorClass.NETWORK
        assertEquals(
            listOf(
                SupervisorState.Idle,
                SupervisorState.Connecting(1), SupervisorState.Waiting(2, 2_000, n, t),
                SupervisorState.Connecting(2), SupervisorState.Waiting(3, 5_000, n, t),
                SupervisorState.Connecting(3), SupervisorState.Waiting(4, 10_000, n, t),
                SupervisorState.Connecting(4), SupervisorState.Waiting(5, 20_000, n, t),
                SupervisorState.Connecting(5), SupervisorState.Exhausted(t),
            ),
            e.states,
        )
        assertEquals(5, e.factory.opens)
    }

    @Test
    fun `opens happen at 0 2000 7000 17000 37000`() = runTest(dispatcher) {
        val e = env()
        e.factory.defaultInitial = failed(LinkErrorKind.TIMEOUT)
        e.startNow()
        e.advance(40_000)
        assertEquals(listOf(0L, 2_000L, 7_000L, 17_000L, 37_000L), e.factory.openTimes)
    }

    @Test
    fun `no sixth open after Exhausted`() = runTest(dispatcher) {
        val e = env()
        e.factory.defaultInitial = failed(LinkErrorKind.OFFLINE)
        e.startNow()
        e.advance(40_000)
        e.advance(600_000)
        assertEquals(5, e.factory.opens)
        assertTrue(e.state is SupervisorState.Exhausted)
    }

    @Test
    fun `retry after Exhausted starts a new schedule`() = runTest(dispatcher) {
        val e = env()
        e.factory.defaultInitial = failed(LinkErrorKind.TIMEOUT)
        e.startNow()
        e.advance(40_000)
        assertTrue(e.state is SupervisorState.Exhausted)
        e.factory.defaultInitial = LinkState.Connecting
        e.sup.retry()
        e.ts.runCurrent()
        assertEquals(6, e.factory.opens)
        assertEquals(SupervisorState.Connecting(1), e.state)
        e.link.flow.value = failed(LinkErrorKind.NETWORK)
        e.ts.runCurrent()
        assertEquals(SupervisorState.Waiting(2, 2_000, ErrorClass.NETWORK, err(LinkErrorKind.NETWORK)), e.state)
    }
    // END_BLOCK_SCHEDULE

    // START_BLOCK_AUTH_KICK_FATAL
    @Test
    fun `wrong password retries once after 2 s with refreshed credentials`() = runTest(dispatcher) {
        val e = env()
        e.factory.script += failed(LinkErrorKind.WRONG_PASSWORD)
        e.startNow()
        assertEquals(
            SupervisorState.Waiting(2, 2_000, ErrorClass.WRONG_PASSWORD, err(LinkErrorKind.WRONG_PASSWORD)),
            e.state,
        )
        assertEquals(0, e.creds.refreshCalls)
        e.advance(1_999)
        assertEquals(1, e.factory.opens)
        e.advance(1)
        assertEquals(1, e.creds.refreshCalls)
        assertEquals(2, e.factory.opens)
        assertEquals(listOf<String?>(LIVE_SYNTH_PW, LIVE_SYNTH_PW2), e.factory.passwords)
    }

    @Test
    fun `second wrong password ends in AuthFailed`() = runTest(dispatcher) {
        val e = env()
        e.factory.defaultInitial = failed(LinkErrorKind.WRONG_PASSWORD)
        e.startNow()
        e.advance(2_000)
        assertEquals(SupervisorState.AuthFailed, e.state)
        e.advance(600_000)
        assertEquals(2, e.factory.opens)
        assertEquals(1, e.creds.refreshCalls)
    }

    @Test
    fun `kick retries once after 10 s then Kicked`() = runTest(dispatcher) {
        val e = env()
        e.factory.script += failed(LinkErrorKind.KICKED)
        e.factory.script += failed(LinkErrorKind.BUSY)
        e.startNow()
        val s = e.state
        assertTrue(s is SupervisorState.Waiting && s.cause == ErrorClass.KICK && s.delayMs == 10_000L, "$s")
        e.advance(9_999)
        assertEquals(1, e.factory.opens)
        e.advance(1)
        assertEquals(2, e.factory.opens)
        assertEquals(SupervisorState.Kicked, e.state)
        e.advance(600_000)
        assertEquals(2, e.factory.opens)
    }

    @Test
    fun `Closed kicked not initiated by us counts as a kick`() = runTest(dispatcher) {
        val e = env()
        e.startNow()
        e.stream()
        e.link.flow.value = LinkState.Closed("kicked")
        e.ts.runCurrent()
        val s = e.state
        assertTrue(s is SupervisorState.Waiting && s.cause == ErrorClass.KICK && s.delayMs == 10_000L, "$s")
        e.advance(10_000)
        assertEquals(2, e.factory.opens)
        e.link.flow.value = LinkState.Closed("kicked")
        e.ts.runCurrent()
        assertEquals(SupervisorState.Kicked, e.state)
        e.advance(600_000)
        assertEquals(2, e.factory.opens)
    }

    @Test
    fun `fatal factory error stops at once`() = runTest(dispatcher) {
        val e = env()
        e.factory.defaultInitial = failed(LinkErrorKind.NO_CREDENTIALS, 0)
        e.startNow()
        assertEquals(SupervisorState.Fatal(err(LinkErrorKind.NO_CREDENTIALS, 0)), e.state)
        e.advance(600_000)
        assertEquals(1, e.factory.opens)
    }

    @Test
    fun `missing credentials are fatal without any open`() = runTest(dispatcher) {
        val e = env()
        e.creds.stored = null
        e.startNow()
        assertEquals(SupervisorState.Fatal(LinkError(LinkErrorKind.NO_CREDENTIALS, 0)), e.state)
        e.advance(600_000)
        assertEquals(0, e.factory.opens)
    }

    @Test
    fun `healthy 30 s stream resets the attempt counter`() = runTest(dispatcher) {
        val e = env()
        e.factory.script += failed(LinkErrorKind.TIMEOUT)
        e.startNow()
        assertEquals(SupervisorState.Waiting(2, 2_000, ErrorClass.NETWORK, err(LinkErrorKind.TIMEOUT)), e.state)
        e.advance(2_000)
        e.stream()
        repeat(31) {
            e.session.frames += 25
            e.advance(1_000)
        }
        e.link.flow.value = failed(LinkErrorKind.NETWORK)
        e.ts.runCurrent()
        assertEquals(SupervisorState.Waiting(2, 2_000, ErrorClass.NETWORK, err(LinkErrorKind.NETWORK)), e.state)
    }

    @Test
    fun `healthy 30 s stream restores the wrong password allowance`() = runTest(dispatcher) {
        val e = env()
        e.factory.script += failed(LinkErrorKind.WRONG_PASSWORD)
        e.startNow()
        e.advance(2_000)
        e.stream()
        repeat(31) {
            e.session.frames += 25
            e.advance(1_000)
        }
        e.link.flow.value = failed(LinkErrorKind.WRONG_PASSWORD)
        e.ts.runCurrent()
        val s = e.state
        assertTrue(s is SupervisorState.Waiting && s.cause == ErrorClass.WRONG_PASSWORD, "$s")
    }
    // END_BLOCK_AUTH_KICK_FATAL

    // START_BLOCK_NETWORK
    @Test
    fun `no network before start - NoNetwork and zero opens until it returns`() = runTest(dispatcher) {
        val e = env(netAvailable = false)
        e.startNow()
        assertEquals(SupervisorState.NoNetwork, e.state)
        e.advance(60_000)
        assertEquals(0, e.factory.opens)
        e.net.set(true)
        e.ts.runCurrent()
        assertEquals(1, e.factory.opens)
        assertEquals(60_000L, e.factory.openTimes.single())
        assertEquals(SupervisorState.Connecting(1), e.state)
    }

    @Test
    fun `network lost while Waiting does not consume attempts`() = runTest(dispatcher) {
        val e = env()
        e.factory.script += failed(LinkErrorKind.TIMEOUT)
        e.factory.script += failed(LinkErrorKind.TIMEOUT)
        e.startNow()
        e.advance(2_000)
        assertEquals(SupervisorState.Waiting(3, 5_000, ErrorClass.NETWORK, err(LinkErrorKind.TIMEOUT)), e.state)
        e.advance(1_000)
        e.net.set(false)
        e.ts.runCurrent()
        assertEquals(SupervisorState.NoNetwork, e.state)
        e.advance(60_000)
        assertEquals(2, e.factory.opens)
        e.net.set(true)
        e.ts.runCurrent()
        assertEquals(3, e.factory.opens)
        assertEquals(63_000L, e.factory.openTimes.last())
        assertEquals(SupervisorState.Connecting(1), e.state)
    }

    @Test
    fun `default network change while Live reconnects after 1500 ms debounce`() = runTest(dispatcher) {
        val e = env()
        e.startNow()
        e.stream()
        e.advance(5_000)
        val first = e.link
        e.net.bump()
        e.ts.runCurrent()
        e.advance(1_499)
        assertTrue(first.closeReasons.isEmpty())
        e.advance(1)
        assertEquals(listOf("network-change"), first.closeReasons)
        assertEquals(2, e.factory.opens)
        assertEquals(6_500L, e.factory.openTimes[1])
        assertEquals(SupervisorState.Connecting(1), e.state)
        assertFalse(e.states.any { it is SupervisorState.Waiting })
    }

    @Test
    fun `network unavailable while Live does not close the link`() = runTest(dispatcher) {
        val e = env()
        e.startNow()
        e.stream()
        e.net.set(false)
        e.ts.runCurrent()
        e.advance(5_000)
        assertTrue(e.link.closeReasons.isEmpty())
        assertTrue(e.state is SupervisorState.Live)
        assertEquals(1, e.factory.opens)
    }
    // END_BLOCK_NETWORK

    // START_BLOCK_STALL
    @Test
    fun `stalled stream is closed after 10 s and reconnected`() = runTest(dispatcher) {
        val e = env()
        e.startNow()
        e.stream()
        e.advance(9_999)
        assertTrue(e.link.closeReasons.isEmpty())
        e.advance(1)
        assertEquals(listOf("stall"), e.link.closeReasons)
        assertEquals(SupervisorState.Waiting(2, 2_000, ErrorClass.NETWORK, null), e.state)
        e.advance(2_000)
        assertEquals(2, e.factory.opens)
        assertEquals(12_000L, e.factory.openTimes[1])
    }

    @Test
    fun `healthy stream is never ended by a timer`() = runTest(dispatcher) {
        val e = env()
        e.startNow()
        e.stream()
        repeat(900) {
            e.session.frames += 25
            e.advance(1_000)
        }
        assertEquals(1, e.factory.opens)
        assertTrue(e.link.closeReasons.isEmpty())
        assertTrue(e.state is SupervisorState.Live)
    }
    // END_BLOCK_STALL

    // START_BLOCK_DRAIN
    @Test
    fun `next open waits for the previous link up to 5 s`() = runTest(dispatcher) {
        val e = env()
        e.factory.configure = { if (e.factory.links.isEmpty()) it.terminalOnClose = false }
        e.startNow()
        e.stream()
        e.net.bump()
        e.ts.runCurrent()
        e.advance(1_500)
        assertEquals(listOf("network-change"), e.factory.links[0].closeReasons)
        e.advance(4_999)
        assertEquals(1, e.factory.opens)
        e.advance(1)
        assertEquals(2, e.factory.opens)
        assertEquals(6_500L, e.factory.openTimes[1])
    }

    @Test
    fun `next open happens as soon as the previous link turns terminal`() = runTest(dispatcher) {
        val e = env()
        e.factory.configure = { if (e.factory.links.isEmpty()) it.terminalOnClose = false }
        e.startNow()
        e.stream()
        e.net.bump()
        e.ts.runCurrent()
        e.advance(1_500)
        e.advance(1_500)
        assertEquals(1, e.factory.opens)
        e.factory.links[0].flow.value = LinkState.Closed("network-change")
        e.ts.runCurrent()
        assertEquals(2, e.factory.opens)
        assertEquals(3_000L, e.factory.openTimes[1])
    }

    @Test
    fun `factory ALREADY_OPEN is scheduled as a network failure`() = runTest(dispatcher) {
        val e = env()
        e.factory.script += failed(LinkErrorKind.ALREADY_OPEN, 0)
        e.startNow()
        assertEquals(
            SupervisorState.Waiting(2, 2_000, ErrorClass.NETWORK, err(LinkErrorKind.ALREADY_OPEN, 0)),
            e.state,
        )
        e.advance(2_000)
        assertEquals(2, e.factory.opens)
    }
    // END_BLOCK_DRAIN

    // START_BLOCK_STOP_RESUME
    @Test
    fun `stop user while Connecting closes once and never reopens`() = runTest(dispatcher) {
        val e = env()
        e.startNow()
        e.sup.stop("user")
        e.ts.runCurrent()
        assertEquals(listOf("user"), e.link.closeReasons)
        assertEquals(1, e.session.releaseCalls)
        assertEquals(SupervisorState.Stopped("user"), e.state)
        e.advance(600_000)
        assertEquals(1, e.factory.opens)
    }

    @Test
    fun `stop user while Waiting cancels the pending delay`() = runTest(dispatcher) {
        val e = env()
        e.factory.script += failed(LinkErrorKind.TIMEOUT)
        e.startNow()
        assertTrue(e.state is SupervisorState.Waiting)
        e.sup.stop("user")
        e.ts.runCurrent()
        assertEquals(listOf("user"), e.link.closeReasons)
        assertEquals(1, e.session.releaseCalls)
        assertEquals(SupervisorState.Stopped("user"), e.state)
        e.advance(600_000)
        assertEquals(1, e.factory.opens)
    }

    @Test
    fun `stop user while Live closes once and releases the session`() = runTest(dispatcher) {
        val e = env()
        e.startNow()
        e.stream()
        e.sup.stop("user")
        e.ts.runCurrent()
        assertEquals(listOf("user"), e.link.closeReasons)
        assertEquals(1, e.session.releaseCalls)
        assertEquals(SupervisorState.Stopped("user"), e.state)
        e.advance(600_000)
        assertEquals(1, e.factory.opens)
    }

    @Test
    fun `open returning after stop is closed with the stop reason and never started`() = runTest(dispatcher) {
        val e = env()
        e.factory.onOpen = { e.sup.stop("screen-left") }
        e.startNow()
        assertEquals(0, e.link.startCalls)
        assertEquals(listOf("screen-left"), e.link.closeReasons)
        assertEquals(1, e.session.releaseCalls)
        assertEquals(SupervisorState.Stopped("screen-left"), e.state)
        e.advance(600_000)
        assertEquals(1, e.factory.opens)
    }

    @Test
    fun `background stop of a Live stream resumes within 5 minutes`() = runTest(dispatcher) {
        val e = env()
        e.startNow()
        e.stream()
        e.sup.stop("background")
        e.ts.runCurrent()
        assertEquals(SupervisorState.Stopped("background"), e.state)
        e.advance(240_000)
        assertTrue(e.sup.onForeground())
        e.ts.runCurrent()
        assertEquals(SupervisorState.Connecting(1), e.state)
        assertEquals(2, e.factory.opens)
    }

    @Test
    fun `background stop older than 5 minutes does not resume`() = runTest(dispatcher) {
        val e = env()
        e.startNow()
        e.stream()
        e.sup.stop("background")
        e.ts.runCurrent()
        e.advance(360_000)
        assertFalse(e.sup.onForeground())
        e.ts.runCurrent()
        assertEquals(SupervisorState.Stopped("background"), e.state)
        assertEquals(1, e.factory.opens)
    }

    @Test
    fun `background stop while Exhausted does not resume`() = runTest(dispatcher) {
        val e = env()
        e.factory.defaultInitial = failed(LinkErrorKind.TIMEOUT)
        e.startNow()
        e.advance(40_000)
        assertTrue(e.state is SupervisorState.Exhausted)
        e.sup.stop("background")
        e.advance(1_000)
        assertFalse(e.sup.onForeground())
        assertEquals(5, e.factory.opens)
    }

    @Test
    fun `background stop while AuthFailed does not resume`() = runTest(dispatcher) {
        val e = env()
        e.factory.defaultInitial = failed(LinkErrorKind.WRONG_PASSWORD)
        e.startNow()
        e.advance(2_000)
        assertEquals(SupervisorState.AuthFailed, e.state)
        e.sup.stop("background")
        e.advance(1_000)
        assertFalse(e.sup.onForeground())
        assertEquals(2, e.factory.opens)
    }
    // END_BLOCK_STOP_RESUME

    // START_BLOCK_QUALITY
    @Test
    fun `SD key frame on a LAN link triggers one HD request`() = runTest(dispatcher) {
        val e = env()
        e.startNow()
        e.stream(tutkLan)
        e.session.keyFrame = KeyFrameObservation(1, 640, 360)
        e.advance(1_000)
        assertEquals(listOf(1), e.link.resolutions)
        assertEquals(Quality.HD, e.sup.qualityState.value.pending)
        repeat(5) {
            e.session.frames += 25
            e.advance(1_000)
        }
        assertEquals(listOf(1), e.link.resolutions)
    }

    @Test
    fun `manual SD is requested and survives a reconnect`() = runTest(dispatcher) {
        val e = env()
        e.startNow()
        e.stream(tnpLan)
        e.session.keyFrame = KeyFrameObservation(1, 1920, 1080)
        e.advance(1_000)
        assertTrue(e.link.resolutions.isEmpty())
        e.sup.setQuality(Quality.SD)
        e.ts.runCurrent()
        assertEquals(listOf(2), e.link.resolutions)
        assertEquals(Quality.SD, e.sup.qualityState.value.manual)
        e.link.flow.value = failed(LinkErrorKind.NETWORK)
        e.ts.runCurrent()
        e.advance(2_000)
        assertEquals(2, e.factory.opens)
        e.stream(tnpLan)
        e.session.keyFrame = KeyFrameObservation(1, 1920, 1080)
        e.advance(1_000)
        assertEquals(listOf(2), e.link.resolutions)
    }

    @Test
    fun `capabilities without resolution switch hide the switch`() = runTest(dispatcher) {
        val e = env()
        e.startNow()
        e.stream()
        e.link.capabilitiesFlow.value = LinkCapabilities(resolutionSwitch = false)
        e.ts.runCurrent()
        assertFalse(e.sup.qualityState.value.switchSupported)
    }

    @Test
    fun `requestResolution returning null hides the switch`() = runTest(dispatcher) {
        val e = env()
        e.factory.configure = { l -> l.resolutionResult = { null } }
        e.startNow()
        e.stream(tutkLan)
        e.session.keyFrame = KeyFrameObservation(1, 640, 360)
        e.advance(1_000)
        assertEquals(listOf(1), e.link.resolutions)
        assertFalse(e.sup.qualityState.value.switchSupported)
    }
    // END_BLOCK_QUALITY

    // START_BLOCK_LOG_MARKERS
    @Test
    fun `log markers are emitted and value-free`() = runTest(dispatcher) {
        val e = env(netAvailable = false)
        e.startNow()
        e.net.set(true)
        e.ts.runCurrent()
        e.stream(tutkLan)
        e.session.keyFrame = KeyFrameObservation(1, 640, 360)
        e.advance(10_000)
        e.advance(2_000)
        assertEquals(2, e.factory.opens)
        e.sup.stop("background")
        e.ts.runCurrent()
        assertTrue(e.sup.onForeground())
        e.ts.runCurrent()
        for (b in listOf(
            "BLOCK_OPEN_LINK", "BLOCK_CLASSIFY", "BLOCK_SCHEDULE", "BLOCK_DRAIN", "BLOCK_STALL",
            "BLOCK_NETWORK", "BLOCK_STOP", "BLOCK_QUALITY", "BLOCK_RESUME",
        )) {
            recorder.assertMarkerAppeared(b)
        }
        assertTrue(recorder.all().any { it.message.startsWith("[LiveSession][supervisor][") })
    }
    // END_BLOCK_LOG_MARKERS

    // START_BLOCK_STREAM_MODE_TESTS
    @Test
    fun `ARCHIVE mode stall window is 35 s`() = runTest(dispatcher) {
        val e = env()
        e.startNow()
        e.stream()
        e.sup.setStreamMode(StreamMode.ARCHIVE)
        e.advance(11_000)
        assertTrue(e.link.closeReasons.isEmpty())
        e.advance(23_000)
        assertTrue(e.link.closeReasons.isEmpty())
        e.advance(1_000)
        assertEquals(listOf("stall"), e.link.closeReasons)
    }

    @Test
    fun `PAUSED mode never stalls`() = runTest(dispatcher) {
        val e = env()
        e.startNow()
        e.stream()
        e.sup.setStreamMode(StreamMode.PAUSED)
        e.advance(120_000)
        assertTrue(e.link.closeReasons.isEmpty())
        assertTrue(e.state is SupervisorState.Live)
        e.sup.setStreamMode(StreamMode.LIVE)
        e.advance(9_000)
        assertTrue(e.link.closeReasons.isEmpty())
        e.advance(1_000)
        assertEquals(listOf("stall"), e.link.closeReasons)
    }

    @Test
    fun `stream mode reaches the live link`() = runTest(dispatcher) {
        val e = env()
        e.startNow()
        e.stream()
        e.sup.setStreamMode(StreamMode.ARCHIVE)
        e.sup.setStreamMode(StreamMode.PAUSED)
        assertEquals(listOf(StreamMode.ARCHIVE, StreamMode.PAUSED), e.link.modes)
    }

    @Test
    fun `a new link starts in LIVE`() = runTest(dispatcher) {
        val e = env()
        e.startNow()
        e.stream()
        e.sup.setStreamMode(StreamMode.ARCHIVE)
        e.link.flow.value = failed(LinkErrorKind.NETWORK)
        e.ts.runCurrent()
        e.advance(2_000)
        assertEquals(2, e.factory.opens)
        assertTrue(e.link.modes.isEmpty())
        e.stream()
        assertTrue(e.link.modes.isEmpty())
        // LIVE window again: 10 s, not 35 s.
        e.advance(10_000)
        assertEquals(listOf("stall"), e.link.closeReasons)
    }

    @Test
    fun `no quality requests outside LIVE`() = runTest(dispatcher) {
        val e = env()
        e.startNow()
        e.stream(tutkLan)
        e.sup.setStreamMode(StreamMode.ARCHIVE)
        e.session.keyFrame = KeyFrameObservation(1, 640, 360)
        e.session.frames += 25
        e.advance(1_000)
        assertTrue(e.link.resolutions.isEmpty())
        e.sup.setQuality(Quality.SD)
        e.ts.runCurrent()
        assertTrue(e.link.resolutions.isEmpty())
        assertEquals(Quality.SD, e.sup.qualityState.value.manual)
    }

    @Test
    fun `quality requests in LIVE are unchanged`() = runTest(dispatcher) {
        val e = env()
        e.startNow()
        e.stream(tutkLan)
        e.session.keyFrame = KeyFrameObservation(1, 640, 360)
        e.advance(1_000)
        assertEquals(listOf(1), e.link.resolutions)
    }

    @Test
    fun `liveEpoch counts links that reached Live`() = runTest(dispatcher) {
        val e = env()
        assertEquals(0, e.sup.liveEpoch.value)
        e.startNow()
        assertEquals(0, e.sup.liveEpoch.value)
        e.stream()
        assertEquals(1, e.sup.liveEpoch.value)
        e.link.flow.value = failed(LinkErrorKind.NETWORK)
        e.ts.runCurrent()
        e.advance(2_000)
        assertEquals(1, e.sup.liveEpoch.value)
        e.stream()
        assertEquals(2, e.sup.liveEpoch.value)
    }

    @Test
    fun `replies are forwarded`() = runTest(dispatcher) {
        val e = env()
        val got = mutableListOf<LinkIoCtrlReply>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { e.sup.ioCtrlReplies.collect { got += it } }
        e.startNow()
        e.stream()
        assertTrue(e.link.replies.tryEmit(LinkIoCtrlReply(9032, ByteArray(12))))
        e.ts.runCurrent()
        assertEquals(1, got.size)
        assertEquals(9032, got[0].type)
        assertEquals(12, got[0].payload.size)
    }

    @Test
    fun `archive port delegates`() = runTest(dispatcher) {
        val e = env()
        val port = e.sup.archivePort(TransportType.TNP, true)
        assertTrue(port.bigEndian)
        assertTrue(port.encrypted)
        assertEquals(TransportType.TNP, port.transport)
        assertFalse(e.sup.archivePort(TransportType.TUTK, false).bigEndian)
        assertFalse(port.isLive())
        assertFalse(port.linkHealthy())
        assertFalse(port.sendIoCtrl(816, ByteArray(4)))
        assertNull(port.nextUseCount())
        assertEquals(0L, port.framesIn())
        e.startNow()
        assertFalse(port.isLive())
        assertFalse(port.linkHealthy())
        e.stream()
        assertTrue(port.isLive())
        assertTrue(port.linkHealthy())
        assertSame(e.sup.liveEpoch, port.liveEpoch)
        assertEquals(1, port.liveEpoch.value)

        assertTrue(port.sendIoCtrl(816, ByteArray(4)))
        assertEquals(816, e.link.sentIoCtrl.single().first)
        e.link.sendIoCtrlResult = false
        assertFalse(port.sendIoCtrl(9031, ByteArray(20)))

        assertEquals(1, port.nextUseCount())
        assertEquals(2, port.nextUseCount())
        assertEquals(3, e.link.requestResolution(1)) // one shared counter

        port.expectGeneration(7)
        assertEquals(7, e.session.expected)
        assertEquals(1, e.session.expectCalls)
        assertNull(port.lastFrame())
        val obs = FrameObservation(7, 1_000, true, 1)
        e.session.frame = obs
        assertSame(obs, port.lastFrame())
        e.session.frames = 42
        assertEquals(42L, port.framesIn())

        port.setStreamMode(StreamMode.ARCHIVE)
        assertEquals(listOf(StreamMode.ARCHIVE), e.link.modes)

        assertEquals(1, port.liveResolution())
        e.sup.setQuality(Quality.SD)
        assertEquals(2, port.liveResolution())
        e.sup.setQuality(Quality.HD)
        assertEquals(1, port.liveResolution())

        e.net.set(false)
        e.ts.runCurrent()
        assertTrue(port.isLive())
        assertFalse(port.linkHealthy())
        e.net.set(true)
        e.ts.runCurrent()
        assertTrue(port.linkHealthy())

        e.link.flow.value = failed(LinkErrorKind.NETWORK)
        e.ts.runCurrent()
        assertTrue(e.state is SupervisorState.Waiting)
        assertFalse(port.isLive())
        assertFalse(port.linkHealthy())
        assertNull(port.nextUseCount())
    }

    @Test
    fun `ARCHIVE stall-closed links do not reset the attempt counter`() = runTest(dispatcher) {
        val e = env()
        e.startNow()
        val delays = listOf(2_000L, 5_000L, 10_000L, 20_000L)
        for (i in 0..3) {
            e.stream()
            e.sup.setStreamMode(StreamMode.ARCHIVE)
            // healthyResetMs (30 s) passes before the 35 s ARCHIVE stall fires.
            e.advance(35_000)
            assertEquals(listOf("stall"), e.link.closeReasons)
            assertEquals(SupervisorState.Waiting(i + 2, delays[i], ErrorClass.NETWORK, null), e.state)
            e.advance(delays[i])
        }
        e.stream()
        e.sup.setStreamMode(StreamMode.ARCHIVE)
        e.advance(35_000)
        assertEquals(SupervisorState.Exhausted(null), e.state)
        assertEquals(5, e.factory.opens)
    }

    @Test
    fun `LIVE stall after a healthy stream still resets the attempt counter`() = runTest(dispatcher) {
        val e = env()
        e.factory.script += failed(LinkErrorKind.TIMEOUT)
        e.startNow()
        assertEquals(SupervisorState.Waiting(2, 2_000, ErrorClass.NETWORK, err(LinkErrorKind.TIMEOUT)), e.state)
        e.advance(2_000)
        e.stream()
        repeat(31) {
            e.session.frames += 25
            e.advance(1_000)
        }
        e.advance(10_000)
        assertEquals(listOf("stall"), e.link.closeReasons)
        assertEquals(SupervisorState.Waiting(2, 2_000, ErrorClass.NETWORK, null), e.state)
    }

    @Test
    fun `ARCHIVE stall after a long archive playback still resets the attempt counter`() = runTest(dispatcher) {
        val e = env()
        e.startNow()
        e.stream()
        e.sup.setStreamMode(StreamMode.ARCHIVE)
        repeat(60) {
            e.session.frames += 25
            e.advance(1_000)
        }
        e.advance(35_000)
        assertEquals(listOf("stall"), e.link.closeReasons)
        assertEquals(SupervisorState.Waiting(2, 2_000, ErrorClass.NETWORK, null), e.state)
    }

    @Test
    fun `archiveStallCount counts only ARCHIVE stalls`() = runTest(dispatcher) {
        val e = env()
        assertEquals(0, e.sup.archiveStallCount())
        e.startNow()
        e.stream()
        e.advance(10_000)
        assertEquals(listOf("stall"), e.link.closeReasons)
        assertEquals(0, e.sup.archiveStallCount())
        e.advance(2_000)
        assertEquals(2, e.factory.opens)
        e.stream()
        e.sup.setStreamMode(StreamMode.ARCHIVE)
        e.advance(35_000)
        assertEquals(listOf("stall"), e.link.closeReasons)
        assertEquals(1, e.sup.archiveStallCount())
        assertEquals(1, e.sup.archivePort(TransportType.TNP, true).archiveStallCount())
    }
    // END_BLOCK_STREAM_MODE_TESTS
}
