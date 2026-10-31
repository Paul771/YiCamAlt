// FILE: PlaybackControllerTest.kt
// VERSION: 0.2.0
// START_MODULE_CONTRACT
//   PURPOSE: Executable contract of the archive playback state machine (SD-03, SD-04; decisions D-04, D-05).
//   SCOPE: seek / snap / live / no recording / not ready, start timeout (only while units arrive), pause, resume,
//          go live (gate release on a live key frame or fail-open), segment boundary jump, end of the last segment,
//          end past the last known segment end (04-10), mid-segment silence, reconnect re-seek, link-health freeze, background pause, log markers and redaction.
//   DEPENDS: M-SD-ARCHIVE (PlaybackController, PlaybackCommands, SdTime), FakeArchivePort, SdFixtures, TraceRecorder
//   LINKS: V-M-SD-ARCHIVE
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.archive

import com.yicamalt.archive.SdFixtures.utc
import com.yicamalt.camera.TransportType
import com.yicamalt.stream.FrameObservation
import com.yicamalt.stream.StreamMode
import com.yicamalt.test.TraceRecorder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.ZoneOffset

// START_MODULE_MAP
//   s1 / s2 / segments / wallNow - fixed timeline (S1 10:00-11:00, S2 11:30-12:00 UTC) and wall time 14:00 UTC.
//   newPort() - TNP encrypted fake; the live link used useCount 2, so the first archive generation is 3.
//   newController(port) - started controller on the test scheduler (clock = currentTime) with UTC wire zone.
//   step(ms) - advance virtual time and run due tasks.
//   seekCmds(uc, iso) - expected TNP 9030 list.
//   playingAt(controller, port, iso, seekIso) - seek, deliver a recorded key frame, assert Playing.
//   epochBump(port) - healthy again + new liveEpoch.
//   PlaybackControllerTest - one test per behaviour bullet of plan 04-05 Task 3 plus the checker addendum and
//     plan 04-10 Tasks 2-3.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.2.0 - GSD plan 04-10 (W-2a): ENDED past the last known end, before the 35 s stall.
//   PREVIOUS: v0.1.0 - GSD plan 04-05: playback state machine contract (RED first).
// END_CHANGE_SUMMARY

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerTest {

    private val recorder = TraceRecorder()
    private val utcZone = ZoneOffset.UTC
    private val s1 = Segment(utc("2026-10-30T10:00:00Z"), utc("2026-10-30T11:00:00Z"))
    private val s2 = Segment(utc("2026-10-30T11:30:00Z"), utc("2026-10-30T12:00:00Z"))
    private val segments = listOf(s1, s2)
    private val wallNow = utc("2026-10-30T14:00:00Z")

    @BeforeEach fun setUp() { recorder.plant() }
    @AfterEach fun tearDown() { recorder.unplant(); recorder.reset() }

    private fun newPort() = FakeArchivePort(TransportType.TNP, encrypted = true, bigEndian = true).apply { useCounter = 3 }

    private fun TestScope.newController(port: FakeArchivePort, segs: List<Segment> = segments): PlaybackController {
        val pc = PlaybackController(port, this, clock = { currentTime }, wallClock = { wallNow }, wireZone = utcZone)
        pc.setSegments(segs)
        pc.start()
        return pc
    }

    private fun TestScope.step(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun seekCmds(uc: Int, iso: String) =
        PlaybackCommands.seek(TransportType.TNP, true, uc, 1, SdTime.toWire(utc(iso), utcZone))

    private fun obs(uc: Int, iso: String, recorded: Boolean = true, keys: Long = 1) =
        FrameObservation(uc, utc(iso) / 1000, recorded, keys)

    private fun TestScope.playingAt(controller: PlaybackController, port: FakeArchivePort, iso: String, seekIso: String = iso): Int {
        controller.seek(utc(seekIso))
        val uc = (controller.state.value as PlaybackState.Starting).useCount
        port.frame = obs(uc, iso)
        step(500)
        assertEquals(PlaybackState.Playing(utc(iso), uc), controller.state.value)
        return uc
    }

    private fun epochBump(port: FakeArchivePort) {
        port.live = true
        port.healthy = null
        port.liveEpoch.value = port.liveEpoch.value + 1
    }

    // START_BLOCK_SEEK_TESTS
    @Test
    fun `seek inside a segment`() = runTest {
        val port = newPort()
        val controller = newController(port)
        controller.seek(utc("2026-10-30T10:15:00Z"))
        assertEquals(PlaybackState.Starting(utc("2026-10-30T10:15:00Z"), 3), controller.state.value)
        assertEquals(3, port.expected)
        assertEquals(StreamMode.ARCHIVE, port.modes.last())
        assertEquals(seekCmds(3, "2026-10-30T10:15:00Z"), port.sent)
        assertEquals(listOf("gate:3", "mode:ARCHIVE", "send:9030"), port.events)
        controller.close()
    }

    @Test
    fun `first recorded key frame starts Playing`() = runTest {
        val port = newPort()
        val controller = newController(port)
        controller.seek(utc("2026-10-30T10:15:00Z"))
        port.frame = obs(3, "2026-10-30T10:15:02Z")
        step(500)
        assertEquals(PlaybackState.Playing(utc("2026-10-30T10:15:02Z"), 3), controller.state.value)
        controller.close()
    }

    @Test
    fun `stale generation ignored`() = runTest {
        val port = newPort()
        val controller = newController(port)
        controller.seek(utc("2026-10-30T10:15:00Z"))
        port.frame = obs(2, "2026-10-30T10:15:02Z")
        step(2_000)
        assertTrue(controller.state.value is PlaybackState.Starting)
        controller.close()
    }

    @Test
    fun `seek into a gap snaps to the next segment`() = runTest {
        val port = newPort()
        val controller = newController(port)
        controller.seek(utc("2026-10-30T11:10:00Z"))
        assertEquals(PlaybackState.Starting(utc("2026-10-30T11:30:00Z"), 3), controller.state.value)
        assertEquals(seekCmds(3, "2026-10-30T11:30:00Z"), port.sent)
        controller.close()
    }

    @Test
    fun `seek within 10 s of now goes live`() = runTest {
        val port = newPort()
        val controller = newController(port)
        playingAt(controller, port, "2026-10-30T10:20:00Z")
        controller.seek(wallNow - 5_000)
        assertEquals(PlaybackState.Live, controller.state.value)
        assertEquals(PlaybackCommands.live(TransportType.TNP, true, 4, 1), port.sent.takeLast(2))
        assertEquals(StreamMode.LIVE, port.modes.last())
        controller.close()
    }

    @Test
    fun `seek with empty segments fails with NO_RECORDING`() = runTest {
        val port = newPort()
        val controller = newController(port, segs = emptyList())
        controller.seek(utc("2026-10-30T10:15:00Z"))
        assertEquals(PlaybackState.Failed(utc("2026-10-30T10:15:00Z"), FailReason.NO_RECORDING), controller.state.value)
        assertTrue(port.sent.isEmpty())
        controller.close()
    }

    @Test
    fun `start timeout fails with NO_FRAMES while units keep arriving`() = runTest {
        val port = newPort()
        val controller = newController(port)
        controller.seek(utc("2026-10-30T10:15:00Z"))
        step(19_000)
        assertTrue(controller.state.value is PlaybackState.Starting)
        step(1_500)
        assertEquals(PlaybackState.Failed(utc("2026-10-30T10:15:00Z"), FailReason.NO_FRAMES), controller.state.value)
        assertEquals(PlaybackCommands.pause(TransportType.TNP, true), port.sent.takeLast(2))
        assertEquals(StreamMode.PAUSED, port.modes.last())
        controller.close()
    }

    @Test
    fun `not ready fails with NOT_READY and sends nothing`() = runTest {
        val port = newPort().apply { useCountAvailable = false }
        val controller = newController(port)
        controller.seek(utc("2026-10-30T10:15:00Z"))
        assertEquals(PlaybackState.Failed(utc("2026-10-30T10:15:00Z"), FailReason.NOT_READY), controller.state.value)
        assertTrue(port.sent.isEmpty())
        controller.close()
    }
    // END_BLOCK_SEEK_TESTS

    // START_BLOCK_CONTROL_TESTS
    @Test
    fun `pause sends 767 and stop record`() = runTest {
        val port = newPort()
        val controller = newController(port)
        playingAt(controller, port, "2026-10-30T10:20:00Z")
        controller.pause()
        assertEquals(PlaybackCommands.pause(TransportType.TNP, true), port.sent.takeLast(2))
        assertEquals(StreamMode.PAUSED, port.modes.last())
        assertEquals(PlaybackState.Paused(utc("2026-10-30T10:20:00Z"), PauseReason.USER), controller.state.value)
        controller.close()
    }

    @Test
    fun `resume seeks to the paused position with a new useCount`() = runTest {
        val port = newPort()
        val controller = newController(port)
        playingAt(controller, port, "2026-10-30T10:20:00Z")
        controller.pause()
        controller.resume()
        assertEquals(PlaybackState.Starting(utc("2026-10-30T10:20:00Z"), 4), controller.state.value)
        assertEquals(seekCmds(4, "2026-10-30T10:20:00Z"), port.sent.takeLast(1))
        assertEquals(4, port.expected)
        controller.close()
    }

    @Test
    fun `go live from archive releases the gate on a live key frame`() = runTest {
        val port = newPort()
        val controller = newController(port)
        playingAt(controller, port, "2026-10-30T10:20:00Z")
        controller.goLive()
        assertEquals(PlaybackCommands.live(TransportType.TNP, true, 4, 1), port.sent.takeLast(2))
        assertEquals(4, port.expected)
        assertEquals(StreamMode.LIVE, port.modes.last())
        assertEquals(PlaybackState.Live, controller.state.value)
        port.frame = obs(4, "2026-10-30T14:00:01Z", recorded = false)
        step(500)
        assertNull(port.expected)
        controller.close()
    }

    @Test
    fun `go live releases the gate after 20 s without a live frame`() = runTest {
        val port = newPort()
        val controller = newController(port)
        playingAt(controller, port, "2026-10-30T10:20:00Z")
        controller.goLive()
        step(19_000)
        assertEquals(4, port.expected)
        step(1_500)
        assertNull(port.expected)
        assertEquals(PlaybackState.Live, controller.state.value)
        controller.close()
    }
    // END_BLOCK_CONTROL_TESTS

    // START_BLOCK_SEGMENT_TESTS
    @Test
    fun `boundary jump near the segment end`() = runTest {
        val port = newPort()
        val controller = newController(port)
        playingAt(controller, port, "2026-10-30T10:59:55Z")
        step(7_500)
        assertTrue(controller.state.value is PlaybackState.Playing)
        step(500)
        assertEquals(PlaybackState.Starting(utc("2026-10-30T11:30:00Z"), 4), controller.state.value)
        assertEquals(listOf(9030, 9030), port.sent.map { it.type })
        assertEquals(seekCmds(4, "2026-10-30T11:30:00Z"), port.sent.takeLast(1))
        controller.close()
    }

    @Test
    fun `boundary jump from a gap`() = runTest {
        val port = newPort()
        val controller = newController(port)
        playingAt(controller, port, "2026-10-30T11:05:00Z", seekIso = "2026-10-30T10:15:00Z")
        step(8_000)
        assertEquals(PlaybackState.Starting(utc("2026-10-30T11:30:00Z"), 4), controller.state.value)
        controller.close()
    }

    @Test
    fun `mid-segment silence waits for the stall and reconnect`() = runTest {
        val port = newPort()
        val controller = newController(port)
        playingAt(controller, port, "2026-10-30T10:20:00Z")
        step(34_000)
        assertEquals(PlaybackState.Playing(utc("2026-10-30T10:20:00Z"), 3), controller.state.value)
        assertEquals(1, port.sent.size)
        port.healthy = false
        port.live = false
        step(1_000)
        epochBump(port)
        runCurrent()
        assertEquals(PlaybackState.Starting(utc("2026-10-30T10:20:00Z"), 4), controller.state.value)
        assertEquals(seekCmds(4, "2026-10-30T10:20:00Z"), port.sent.takeLast(1))
        controller.close()
    }

    @Test
    fun `end of last segment pauses with ENDED after 30 s`() = runTest {
        val port = newPort()
        val controller = newController(port)
        playingAt(controller, port, "2026-10-30T11:59:55Z")
        step(8_000)
        assertTrue(controller.state.value is PlaybackState.Playing)
        step(22_000)
        assertEquals(PlaybackState.Paused(utc("2026-10-30T11:59:55Z"), PauseReason.ENDED), controller.state.value)
        assertEquals(PlaybackCommands.pause(TransportType.TNP, true), port.sent.takeLast(2))
        assertEquals(StreamMode.PAUSED, port.modes.last())
        controller.close()
    }

    @Test
    fun `no ENDED in the middle of the last segment`() = runTest {
        val port = newPort()
        val controller = newController(port)
        playingAt(controller, port, "2026-10-30T11:40:00Z")
        step(60_000)
        assertEquals(PlaybackState.Playing(utc("2026-10-30T11:40:00Z"), 3), controller.state.value)
        assertEquals(1, port.sent.size)
        controller.close()
    }

    @Test
    fun `playback past the last known end pauses with ENDED after 30 s`() = runTest {
        val port = newPort()
        val controller = newController(port)
        // The camera kept recording past the stale index end: key frame after S2.end.
        playingAt(controller, port, "2026-10-30T12:00:20Z", seekIso = "2026-10-30T11:59:00Z")
        step(8_000)
        assertEquals(PlaybackState.Playing(utc("2026-10-30T12:00:20Z"), 3), controller.state.value)
        step(22_000)
        assertEquals(PlaybackState.Paused(utc("2026-10-30T12:00:20Z"), PauseReason.ENDED), controller.state.value)
        assertEquals(PlaybackCommands.pause(TransportType.TNP, true), port.sent.takeLast(2))
        assertEquals(StreamMode.PAUSED, port.modes.last())
        assertEquals(1, port.sent.count { it.type == 9030 })
        controller.close()
    }

    @Test
    fun `ENDED past the end comes before the supervisor's 35 s stall`() = runTest {
        val port = newPort()
        val controller = newController(port)
        playingAt(controller, port, "2026-10-30T12:00:20Z", seekIso = "2026-10-30T11:59:00Z")
        val keyAt = currentTime
        step(29_000)
        assertTrue(controller.state.value is PlaybackState.Playing)
        step(1_500)
        val elapsed = currentTime - keyAt
        assertEquals(30_500L, elapsed)
        assertTrue(elapsed < 35_000L)
        assertEquals(PlaybackState.Paused(utc("2026-10-30T12:00:20Z"), PauseReason.ENDED), controller.state.value)
        controller.close()
    }

    @Test
    fun `no ENDED past the end while key frames keep arriving`() = runTest {
        val port = newPort()
        val controller = newController(port)
        playingAt(controller, port, "2026-10-30T12:00:20Z", seekIso = "2026-10-30T11:59:00Z")
        val base = utc("2026-10-30T12:00:20Z")
        for (i in 1..12) {
            step(5_000)
            port.frame = FrameObservation(3, (base + i * 5_000L) / 1000, true, 1L + i)
        }
        step(500)
        assertEquals(PlaybackState.Playing(base + 60_000L, 3), controller.state.value)
        assertEquals(1, port.sent.size)
        controller.close()
    }
    // END_BLOCK_SEGMENT_TESTS

    // START_BLOCK_LINK_TESTS
    @Test
    fun `reconnect re-seeks`() = runTest {
        val port = newPort()
        val controller = newController(port)
        playingAt(controller, port, "2026-10-30T10:20:00Z")
        port.liveEpoch.value = 2
        runCurrent()
        assertEquals(PlaybackState.Starting(utc("2026-10-30T10:20:00Z"), 4), controller.state.value)
        assertEquals(seekCmds(4, "2026-10-30T10:20:00Z"), port.sent.takeLast(1))
        controller.close()
    }

    @Test
    fun `link drop while playing freezes and re-seeks`() = runTest {
        val port = newPort()
        val controller = newController(port)
        playingAt(controller, port, "2026-10-30T10:20:00Z")
        port.healthy = false
        port.frame = null
        step(15_000)
        assertEquals(PlaybackState.Playing(utc("2026-10-30T10:20:00Z"), 3), controller.state.value)
        assertTrue(controller.isReconnecting)
        assertEquals(1, port.sent.size)
        assertEquals(listOf(StreamMode.ARCHIVE), port.modes)
        epochBump(port)
        runCurrent()
        assertEquals(PlaybackState.Starting(utc("2026-10-30T10:20:00Z"), 4), controller.state.value)
        assertFalse(controller.isReconnecting)
        controller.close()
    }

    @Test
    fun `link drop near the segment end neither jumps nor ends, timers re-base`() = runTest {
        val port = newPort()
        val controller = newController(port)
        playingAt(controller, port, "2026-10-30T10:59:58Z")
        port.healthy = false
        port.frame = null
        step(35_000)
        assertEquals(PlaybackState.Playing(utc("2026-10-30T10:59:58Z"), 3), controller.state.value)
        assertEquals(1, port.sent.size)
        // Healthy again without a new link: timers start from zero, no immediate jump.
        port.healthy = null
        step(500)
        assertEquals(PlaybackState.Playing(utc("2026-10-30T10:59:58Z"), 3), controller.state.value)
        step(6_000)
        assertEquals(PlaybackState.Playing(utc("2026-10-30T10:59:58Z"), 3), controller.state.value)
        assertEquals(1, port.sent.size)
        epochBump(port)
        runCurrent()
        assertEquals(PlaybackState.Starting(utc("2026-10-30T10:59:58Z"), 4), controller.state.value)
        controller.close()
    }

    @Test
    fun `link drop while starting keeps Starting and re-seeks with a fresh window`() = runTest {
        val port = newPort()
        val controller = newController(port)
        controller.seek(utc("2026-10-30T10:15:00Z"))
        port.healthy = false
        step(25_000)
        assertEquals(PlaybackState.Starting(utc("2026-10-30T10:15:00Z"), 3), controller.state.value)
        assertEquals(1, port.sent.size)
        epochBump(port)
        runCurrent()
        assertEquals(PlaybackState.Starting(utc("2026-10-30T10:15:00Z"), 4), controller.state.value)
        step(19_000)
        assertTrue(controller.state.value is PlaybackState.Starting)
        step(1_500)
        assertEquals(PlaybackState.Failed(utc("2026-10-30T10:15:00Z"), FailReason.NO_FRAMES), controller.state.value)
        controller.close()
    }

    @Test
    fun `seek while not healthy never fails or advances`() = runTest {
        val port = newPort()
        val controller = newController(port)
        playingAt(controller, port, "2026-10-30T10:20:00Z")
        port.healthy = false
        port.live = false
        controller.seek(utc("2026-10-30T10:40:00Z"))
        assertEquals(PlaybackState.Starting(utc("2026-10-30T10:40:00Z"), -1), controller.state.value)
        step(25_000)
        assertEquals(PlaybackState.Starting(utc("2026-10-30T10:40:00Z"), -1), controller.state.value)
        assertEquals(1, port.sent.size)
        epochBump(port)
        runCurrent()
        assertEquals(PlaybackState.Starting(utc("2026-10-30T10:40:00Z"), 4), controller.state.value)
        assertEquals(seekCmds(4, "2026-10-30T10:40:00Z"), port.sent.takeLast(1))
        controller.close()
    }

    @Test
    fun `seek while not healthy is sent when health returns without a new link`() = runTest {
        val port = newPort()
        val controller = newController(port)
        port.healthy = false
        controller.seek(utc("2026-10-30T10:40:00Z"))
        assertEquals(PlaybackState.Starting(utc("2026-10-30T10:40:00Z"), -1), controller.state.value)
        assertTrue(port.sent.isEmpty())
        step(3_000)
        port.healthy = null
        step(500)
        assertEquals(PlaybackState.Starting(utc("2026-10-30T10:40:00Z"), 3), controller.state.value)
        assertEquals(seekCmds(3, "2026-10-30T10:40:00Z"), port.sent)
        // The next liveEpoch of the same link would not resend: only a new link does.
        controller.close()
    }

    @Test
    fun `camera loss while starting with a healthy link and no units waits for the stall`() = runTest {
        val port = newPort()
        val controller = newController(port)
        controller.seek(utc("2026-10-30T10:15:00Z"))
        port.unitsFlowing = false
        step(34_000)
        assertEquals(PlaybackState.Starting(utc("2026-10-30T10:15:00Z"), 3), controller.state.value)
        // Supervisor stall closes the link; the new link re-seeks at the same position.
        port.healthy = false
        port.live = false
        step(1_000)
        epochBump(port)
        runCurrent()
        assertEquals(PlaybackState.Starting(utc("2026-10-30T10:15:00Z"), 4), controller.state.value)
        controller.close()
    }

    @Test
    fun `reconnect while paused shows live`() = runTest {
        val port = newPort()
        val controller = newController(port)
        playingAt(controller, port, "2026-10-30T10:20:00Z")
        controller.pause()
        val sentBefore = port.sent.size
        val modesBefore = port.modes.size
        epochBump(port)
        runCurrent()
        assertEquals(sentBefore, port.sent.size)
        assertEquals(modesBefore, port.modes.size)
        assertNull(port.expected)
        assertEquals(PlaybackState.Paused(utc("2026-10-30T10:20:00Z"), PauseReason.USER), controller.state.value)
        controller.close()
    }

    @Test
    fun `background then auto-resume keeps the archive paused`() = runTest {
        val port = newPort()
        val controller = newController(port)
        playingAt(controller, port, "2026-10-30T10:20:00Z")
        controller.onSessionStopped()
        assertEquals(PlaybackState.Paused(utc("2026-10-30T10:20:00Z"), PauseReason.BACKGROUND), controller.state.value)
        assertEquals(1, port.sent.size)
        port.live = false
        step(1_000)
        epochBump(port)
        runCurrent()
        step(40_000)
        assertEquals(1, port.sent.size)
        assertNull(port.expected)
        assertEquals(listOf(StreamMode.ARCHIVE), port.modes)
        assertEquals(PlaybackState.Paused(utc("2026-10-30T10:20:00Z"), PauseReason.BACKGROUND), controller.state.value)
        controller.resume()
        assertEquals(PlaybackState.Starting(utc("2026-10-30T10:20:00Z"), 4), controller.state.value)
        controller.close()
    }
    // END_BLOCK_LINK_TESTS

    @Test
    fun `log markers are present and value-free`() = runTest {
        val port = newPort()
        val controller = newController(port)
        playingAt(controller, port, "2026-10-30T10:20:00Z")
        controller.pause()
        controller.resume()
        controller.goLive()
        val text = recorder.bufferText()
        assertTrue(text.contains("BLOCK_PLAYBACK_STATE"), text)
        assertTrue(text.contains("BLOCK_SEEK"), text)
        assertFalse(Regex("20\\d\\d-").containsMatchIn(text), text)
        assertFalse(text.contains(utc("2026-10-30T10:20:00Z").toString()), text)
        controller.close()
    }

    @Test
    fun `close is idempotent and stops the ticker`() = runTest {
        val port = newPort()
        val controller = newController(port)
        controller.seek(utc("2026-10-30T10:15:00Z"))
        controller.close()
        controller.close()
        step(30_000)
        assertTrue(controller.state.value is PlaybackState.Starting)
        assertEquals(1, port.sent.size)
    }
}
