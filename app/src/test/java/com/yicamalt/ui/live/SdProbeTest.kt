// FILE: SdProbeTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Executable contract of the debug SD archive probe: tee counters, probe port adapter and probe actions.
//   SCOPE: JVM-only; in-file fake CameraLink (sendIoCtrl, ioCtrlReplies, nextUseCount, setStreamMode); synthetic
//     units and replies; virtual time; value-free log lines checked by RedactionScanner.
//   DEPENDS: M-UI-LIVE-PROBE (SdProbeTee, ProbeArchivePort, SdProbe), M-SD-ARCHIVE (PlaybackCommands, SdTime,
//     SdFixtures), M-STREAM-CORE (CameraLink, VideoUnit), TraceRecorder, RedactionScanner
//   LINKS: V-M-UI-LIVE-PROBE, M-UI-LIVE-PROBE
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT

// START_MODULE_MAP
//   ProbeFakeLink - streaming CameraLink that records IOCTRL sends and stream modes, replies via onSend.
//   unit(...) - synthetic VideoUnit with a given channel, generation, key flag, liveFlag and timestamp.
//   SdProbeTest - tee counters / first recorded frame / max jump, port adapter, INFO / INDEX+PLAY / PAUSE / LIVE.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 04-04: SD probe tee and actions.
// END_CHANGE_SUMMARY
package com.yicamalt.ui.live

import com.yicamalt.archive.PlaybackCmd
import com.yicamalt.archive.PlaybackCommands
import com.yicamalt.archive.SdFixtures
import com.yicamalt.archive.SdTime
import com.yicamalt.camera.TransportType
import com.yicamalt.stream.CameraLink
import com.yicamalt.stream.FrameInfo
import com.yicamalt.stream.IoCtrlMessages
import com.yicamalt.stream.LinkInfo
import com.yicamalt.stream.LinkIoCtrlReply
import com.yicamalt.stream.LinkMode
import com.yicamalt.stream.LinkState
import com.yicamalt.stream.StreamMode
import com.yicamalt.stream.VideoUnit
import com.yicamalt.stream.VideoUnitSink
import com.yicamalt.test.RedactionScanner
import com.yicamalt.test.TraceRecorder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

private const val PROBE_DEVICE = "cam-SYNTH"
private val NOW = SdFixtures.utc("2026-10-30T12:00:00Z")

private class ProbeFakeLink : CameraLink {
    override val deviceId = PROBE_DEVICE
    val flow = MutableStateFlow<LinkState>(
        LinkState.Streaming(LinkInfo(TransportType.TNP, LinkMode.LAN, true, 10), true),
    )
    override val state = flow
    val replies = MutableSharedFlow<LinkIoCtrlReply>(extraBufferCapacity = 64)
    override val ioCtrlReplies: SharedFlow<LinkIoCtrlReply> = replies
    val sent = CopyOnWriteArrayList<PlaybackCmd>()
    val modes = CopyOnWriteArrayList<StreamMode>()
    @Volatile var onSend: (Int, ByteArray) -> Unit = { _, _ -> }
    @Volatile var uc = 2

    override fun start(sink: VideoUnitSink) = Unit
    override fun close(reason: String) = Unit
    override fun sendIoCtrl(type: Int, payload: ByteArray): Boolean {
        sent += PlaybackCmd(type, payload)
        onSend(type, payload)
        return true
    }
    override fun nextUseCount(): Int? = ++uc
    override fun setStreamMode(mode: StreamMode) {
        modes += mode
    }
}

private fun unit(ch: Int, uc: Int, key: Boolean, live: Int, ts: Long, w: Int = 1920, h: Int = 1080) = VideoUnit(
    ch,
    FrameInfo(
        codecId = 78, flags = if (key) 1 else 0, liveFlag = live, onlineNum = 0, useCount = uc, seq = 0,
        width = w, height = h, timestampSec = ts, isDay = 0, cover = 0, outLoss = 0, inLoss = 0, timestampMs = 0,
    ),
    ByteArray(4),
)

@OptIn(ExperimentalCoroutinesApi::class)
class SdProbeTest {

    private val recorder = TraceRecorder()
    private val forwarded = CopyOnWriteArrayList<VideoUnit>()
    private val next = VideoUnitSink { forwarded += it }

    @BeforeEach fun setUp() { recorder.plant() }
    @AfterEach fun tearDown() { recorder.unplant() }

    private fun lines(block: String) = recorder.all().map { it.message }.filter { it.contains("[$block]") }

    private fun TestScope.probe(link: ProbeFakeLink, tee: SdProbeTee): SdProbe {
        val port = ProbeArchivePort(link, TransportType.TNP, encrypted = false, tee = tee)
        return SdProbe(port, tee, backgroundScope, clock = { currentTime }, wallClock = { NOW })
    }

    private fun assertLogsClean() =
        RedactionScanner().assertClean(recorder.bufferText(), extraLiterals = listOf(PROBE_DEVICE))

    // START_BLOCK_TEE_TESTS
    @Test
    fun `tee counts per channel and generation`() {
        val tee = SdProbeTee(next) { 0L }
        tee.expect(3)
        val units = listOf(unit(2, 2, false, 0, 10), unit(4, 3, true, 1, 20), unit(5, 3, false, 1, 20))
        units.forEach { tee.onVideoUnit(it) }
        assertEquals(mapOf(2 to 1L, 4 to 1L, 5 to 1L), tee.perChannel())
        assertEquals(2L, tee.recordedUnits())
        assertEquals(1L, tee.otherUcUnits())
        assertEquals(3L, tee.totalUnits())
        assertEquals(3, forwarded.size)
        units.forEachIndexed { i, u -> assertSame(u, forwarded[i]) }
    }

    @Test
    fun `first recorded key frame of the expected generation is reported once`() {
        var now = 100L
        val tee = SdProbeTee(next) { now }
        tee.expect(3)
        tee.onVideoUnit(unit(4, 2, true, 1, 500)) // older generation: ignored
        assertNull(tee.firstRecorded())
        now = 250L
        tee.onVideoUnit(unit(4, 3, true, 1, 1_000, 1280, 720))
        val first = tee.firstRecorded()
        assertNotNull(first)
        assertEquals(SdProbeTee.FirstRecord(4, 3, 1_000, 1280, 720, 78, 250), first)
        now = 400L
        tee.onVideoUnit(unit(5, 3, true, 1, 1_002))
        assertEquals(first, tee.firstRecorded())
        val obs = tee.lastFrame()!!
        assertEquals(3, obs.useCount)
        assertEquals(1_002L, obs.timestampSec)
        assertTrue(obs.recorded)
        assertEquals(2L, obs.keyFrames)
    }

    @Test
    fun `maxJumpS between consecutive recorded key frames`() {
        val tee = SdProbeTee(next) { 0L }
        tee.expect(3)
        listOf(100L, 102L, 160L).forEach { tee.onVideoUnit(unit(4, 3, true, 1, it)) }
        assertEquals(58L, tee.maxJumpS())
        assertEquals(160L, tee.lastRecordedTsSec())
        tee.expect(4)
        assertEquals(0L, tee.maxJumpS())
        assertNull(tee.firstRecorded())
    }

    @Test
    fun `port adapter forwards to the link and counts frames of any generation`() {
        val link = ProbeFakeLink()
        val tee = SdProbeTee(next) { 0L }
        val port = ProbeArchivePort(link, TransportType.TNP, encrypted = true, tee = tee)
        assertTrue(port.isLive())
        assertTrue(port.linkHealthy())
        assertTrue(port.bigEndian)
        assertTrue(port.encrypted)
        assertEquals(IoCtrlMessages.RESOLUTION_HD, port.liveResolution())
        assertEquals(1, port.liveEpoch.value)
        tee.onVideoUnit(unit(2, 2, true, 0, 1))
        tee.onVideoUnit(unit(4, 7, true, 1, 2))
        assertEquals(2L, port.framesIn())
        port.expectGeneration(7)
        assertEquals(0L, tee.otherUcUnits())
        port.setStreamMode(StreamMode.ARCHIVE)
        assertEquals(listOf(StreamMode.ARCHIVE), link.modes.toList())
        link.flow.value = LinkState.Closed("user")
        assertFalse(port.isLive())
        assertFalse(port.linkHealthy())
    }
    // END_BLOCK_TEE_TESTS

    // START_BLOCK_ACTION_TESTS
    @Test
    fun `info logs value-free fields`() = runTest {
        val link = ProbeFakeLink()
        link.onSend = { type, _ ->
            if (type == 816) link.replies.tryEmit(LinkIoCtrlReply(817, SdFixtures.deviceInfo(tfstat = 5)))
        }
        val tee = SdProbeTee(next) { currentTime }
        probe(link, tee).run(SdProbeAction.INFO)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(816, link.sent.single().type)
        val info = lines("BLOCK_SD_INFO")
        assertEquals(1, info.size, recorder.bufferText())
        assertTrue(
            info[0].contains(
                "[Archive][probe][BLOCK_SD_INFO] transport=TNP replyLen=344 iface=0 hw=0 tfstat=5 totalMb=0 freeMb=0 isUtc=0",
            ),
            info[0],
        )
        assertLogsClean()
    }

    @Test
    fun `info without a reply logs a timeout after 10 s`() = runTest {
        val link = ProbeFakeLink()
        val tee = SdProbeTee(next) { currentTime }
        probe(link, tee).run(SdProbeAction.INFO)
        advanceTimeBy(9_000)
        assertTrue(lines("BLOCK_SD_INFO").isEmpty())
        advanceTimeBy(1_500)
        assertTrue(lines("BLOCK_SD_INFO").single().contains("result=timeout"))
    }

    @Test
    fun `play sends PlaybackCommands seek with nextUseCount and sets ARCHIVE mode`() = runTest {
        val link = ProbeFakeLink()
        val older = SdTime.toWire(NOW - 7_200_000)
        val newer = SdTime.toWire(NOW - 3_600_000)
        link.onSend = { type, _ ->
            if (type == 9031) link.replies.tryEmit(LinkIoCtrlReply(9032, SdFixtures.tnpIndex(older to 600, newer to 600)))
        }
        val tee = SdProbeTee(next) { currentTime }
        val probe = probe(link, tee)
        probe.run(SdProbeAction.INDEX)
        advanceTimeBy(100)
        val index = lines("BLOCK_SD_INDEX").single()
        assertTrue(index.contains("result=ok count=2 sumDurS=1200 maxDurS=600"), index)
        assertTrue(index.contains("newestAgoMin=50"), index)

        probe.run(SdProbeAction.PLAY_NEWEST)
        runCurrent()
        val targetMs = NOW - 3_600_000 + 30_000
        val expected = PlaybackCommands.seek(TransportType.TNP, false, 3, IoCtrlMessages.RESOLUTION_HD, SdTime.toWire(targetMs))
        assertEquals(expected, link.sent.toList().takeLast(expected.size))
        assertEquals(StreamMode.ARCHIVE, link.modes.last())

        advanceTimeBy(700)
        tee.onVideoUnit(unit(4, 3, true, 1, targetMs / 1000 + 2))
        advanceTimeBy(300)
        val first = lines("BLOCK_SD_FIRST_FRAME").single()
        assertTrue(first.contains("ch=4 liveFlag=1 uc=3 codec=78 size=1920x1080 deltaS=2"), first)
        assertLogsClean()
    }

    @Test
    fun `play without an index logs no-index and sends nothing`() = runTest {
        val link = ProbeFakeLink()
        val tee = SdProbeTee(next) { currentTime }
        probe(link, tee).run(SdProbeAction.PLAY_NEWEST)
        runCurrent()
        assertTrue(link.sent.isEmpty())
        assertTrue(lines("BLOCK_SD_FIRST_FRAME").single().contains("result=no-index"))
    }

    @Test
    fun `pause sends 767 and stop record, sets PAUSED and counts later frames`() = runTest {
        val link = ProbeFakeLink()
        val tee = SdProbeTee(next) { currentTime }
        probe(link, tee).run(SdProbeAction.PAUSE)
        runCurrent()
        assertEquals(PlaybackCommands.pause(TransportType.TNP, false), link.sent.toList())
        assertEquals(IoCtrlMessages.STOP, link.sent[0].type)
        assertEquals(StreamMode.PAUSED, link.modes.last())
        tee.onVideoUnit(unit(4, 3, false, 1, 10)) // in flight before 500 ms
        advanceTimeBy(600)
        tee.onVideoUnit(unit(4, 3, false, 1, 11))
        tee.onVideoUnit(unit(4, 3, false, 1, 12))
        advanceTimeBy(5_000)
        val pause = lines("BLOCK_SD_PAUSE")
        assertTrue(pause.any { it.contains("framesAfterMs500=1") }, pause.toString())
        assertTrue(pause.any { it.contains("framesAfterS5=2") }, pause.toString())
    }

    @Test
    fun `live sends stop record and 9029 and sets LIVE`() = runTest {
        val link = ProbeFakeLink()
        val tee = SdProbeTee(next) { currentTime }
        probe(link, tee).run(SdProbeAction.LIVE)
        runCurrent()
        assertEquals(PlaybackCommands.live(TransportType.TNP, false, 3, IoCtrlMessages.RESOLUTION_HD), link.sent.toList())
        assertEquals(IoCtrlMessages.START_REALTIME, link.sent.last().type)
        assertEquals(StreamMode.LIVE, link.modes.last())
        advanceTimeBy(250)
        tee.onVideoUnit(unit(2, 3, true, 0, 50))
        advanceTimeBy(200)
        val live = lines("BLOCK_SD_LIVE").single()
        assertTrue(live.contains("liveFlag=0 uc=3"), live)
        assertTrue(Regex("firstLiveMs=(\\d+)").find(live)!!.groupValues[1].toLong() in 250L..300L, live)
        assertLogsClean()
    }
    // END_BLOCK_ACTION_TESTS
}
