// FILE: ArchiveCoordinatorTest.kt
// VERSION: 0.3.0
// START_MODULE_CONTRACT
//   PURPOSE: Executable contract of the archive screen state (SD-01..SD-05; D-01, D-02, D-04..D-07) in virtual time.
//   SCOPE: IDLE before the first Live, spinner only while a fetch is in flight on a healthy link, D-07 status / list states with retry, day selection and labels, days
//     dialog refresh, seek / pause / live with forced mute, ended and start-failed hints, TUTK hide rule (D-06) incl.
//     network loss, link drop, mid-segment silence and no-units (checker addendum), background marker, unsupported
//     transport, midnight crossing, log marker; ruler position (rulerMs) in Live / archive, «Живой» day, Live midnight
//     roll-over (plan 04-11); index refresh on liveEpoch / every 60 s with its guards,
//     refresh-first seek with a bounded pending seek, Paused(SILENT) hint (plan 04-12).
//   DEPENDS: M-UI-LIVEVIEW (ArchiveCoordinator), M-SD-ARCHIVE (PlaybackController, SdTime, FakeArchivePort,
//     SdFixtures), TraceRecorder, RedactionScanner
//   LINKS: V-M-UI-LIVEVIEW, M-UI-LIVEVIEW
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.ui.live

import com.yicamalt.archive.FakeArchivePort
import com.yicamalt.archive.PauseReason
import com.yicamalt.archive.PlaybackController
import com.yicamalt.archive.PlaybackState
import com.yicamalt.archive.SdFixtures
import com.yicamalt.archive.SdFixtures.utc
import com.yicamalt.archive.SdTime
import com.yicamalt.archive.Segment
import com.yicamalt.camera.TransportType
import com.yicamalt.stream.FrameObservation
import com.yicamalt.test.RedactionScanner
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
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

// START_MODULE_MAP
//   zone / wallNow / today - Europe/Moscow, 2026-10-30T09:00Z (12:00 local), 2026-10-30.
//   TODAY_SEG / MIDNIGHT_SEG / OLD_SEG / YESTERDAY_SEG / NEW_SEG - index entries (ISO UTC start, seconds).
//   Script - scripted 817 tfstat (null = no reply) and index entries (null = no reply).
//   Rig - port + controller + coordinator + mute counter, count(type), playbackSent() (all but 816 / 9031 / 792);
//     rig(...) builds and starts it on backgroundScope;
//     wallStart / movingWall: constant wall clock or wallStart + virtual time (shared by controller and coordinator).
//   wallSec(start) - moving wall time floored to whole seconds.
//   step(ms) - advance virtual time and run due tasks.
//   obs(uc, iso) - recorded key-frame observation.
//   playingAt(r, iso) - seek and deliver a recorded key frame -> Playing.
//   pendingSeek() - rig with a refresh-first seek of 08:30Z waiting on an unanswered refresh.
//   ArchiveCoordinatorTest - one test per behaviour bullet of plan 04-06 Task 2 plus the checker addendum.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.3.0 - GSD plan 04-12 (W-1): BLOCK_REFRESH_TESTS (epoch / periodic refresh, unhealthy / past
//     day / background / failure / overlap guards); `openDays refreshes a stale index` keeps the link unhealthy
//     during the 61 s wait; `background then auto-resume keeps the marker` counts playback commands only.
//     BLOCK_SEEK_REFRESH_TESTS (refresh-first seek, 3 s fallback, replace / cancel, SILENT hint), pendingSeek(rig).
//   PREVIOUS: v0.2.0 - GSD plan 04-11: moving-wall rig, BLOCK_RULER_TESTS (device defect 1); IDLE / spinner-only-
//     while-fetching load tests (device defect 2).
//   EARLIER: v0.1.0 - GSD plan 04-06: archive coordinator contract (RED first).
// END_CHANGE_SUMMARY

@OptIn(ExperimentalCoroutinesApi::class)
class ArchiveCoordinatorTest {

    private val recorder = TraceRecorder()
    private val scanner = RedactionScanner()
    private val zone: ZoneId = ZoneId.of("Europe/Moscow")
    private val wallNow = utc("2026-10-30T09:00:00Z")
    private val today = LocalDate.of(2026, 10, 30)

    private val TODAY_SEG = "2026-10-30T06:00:00Z" to 3_600         // 09:00-10:00 local
    private val MIDNIGHT_SEG = "2026-10-29T20:30:00Z" to 3_600      // 23:30-00:30 local
    private val OLD_SEG = "2026-10-28T10:00:00Z" to 3_600
    private val YESTERDAY_SEG = "2026-10-29T10:00:00Z" to 3_600
    private val NEW_SEG = "2026-10-30T07:00:00Z" to 6_600           // 10:00-11:50 local, merges with TODAY_SEG

    @BeforeEach fun setUp() { recorder.plant() }

    @AfterEach
    fun tearDown() {
        scanner.assertClean(recorder.bufferText())
        recorder.unplant()
        recorder.reset()
    }

    private class Script(var tfstat: Int? = 0, var entries: List<Pair<String, Int>>? = null)

    private inner class Rig(val port: FakeArchivePort, val controller: PlaybackController, val coord: ArchiveCoordinator) {
        var mutes = 0
        val ui: ArchiveUi get() = coord.ui.value
        fun count(type: Int) = port.sent.count { it.type == type }
        // Everything except status / index requests (816, 9031, 792).
        fun playbackSent() = port.sent.count { it.type != 816 && it.type != 9031 && it.type != 792 }
    }

    private fun wire(iso: String) = SdTime.toWire(utc(iso), ZoneOffset.UTC)

    private fun TestScope.rig(
        transport: TransportType? = TransportType.TNP,
        script: Script = Script(entries = listOf(TODAY_SEG, MIDNIGHT_SEG, OLD_SEG)),
        epoch: Int = 1,
        start: Boolean = true,
        wallStart: Long = wallNow,
        movingWall: Boolean = false,
    ): Rig {
        val wall: () -> Long = if (movingWall) { { wallStart + currentTime } } else { { wallStart } }
        val tutk = transport == TransportType.TUTK
        val port = FakeArchivePort(transport ?: TransportType.UNKNOWN, encrypted = true, bigEndian = !tutk)
        port.liveEpoch.value = epoch
        port.onSend = { type, _ ->
            when (type) {
                816 -> script.tfstat?.let {
                    port.reply(817, SdFixtures.deviceInfo(tfstat = it, total = 100, bigEndian = !tutk))
                }
                9031 -> script.entries?.let { e ->
                    port.reply(9032, SdFixtures.tnpIndex(*e.map { wire(it.first) to it.second }.toTypedArray()))
                }
                792 -> script.entries?.let { e ->
                    port.reply(
                        793,
                        SdFixtures.tutkPage(e.size, 0, true, e.map { wire(it.first) to it.second }),
                    )
                }
            }
        }
        val controller = PlaybackController(port, backgroundScope, { currentTime }, wall)
        lateinit var r: Rig
        val coord = ArchiveCoordinator(
            port = port,
            transport = transport,
            scope = backgroundScope,
            clock = { currentTime },
            wallClock = wall,
            zone = zone,
            forceMute = { r.mutes++ },
            controller = controller,
        )
        r = Rig(port, controller, coord)
        if (start) {
            coord.start()
            runCurrent()
        }
        return r
    }

    private fun TestScope.step(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    // Wall time floored to whole seconds for a moving-wall rig.
    private fun TestScope.wallSec(start: Long = wallNow) = (start + currentTime) / 1000 * 1000

    private fun obs(uc: Int, iso: String) = FrameObservation(uc, utc(iso) / 1000, true, 1)

    private fun TestScope.playingAt(r: Rig, iso: String): Int {
        r.coord.seek(utc(iso))
        runCurrent()
        val uc = r.port.expected!!
        r.port.frame = obs(uc, iso)
        step(500)
        assertTrue(r.controller.state.value is PlaybackState.Playing, "${r.controller.state.value}")
        return uc
    }

    // START_BLOCK_LOAD_TESTS
    @Test
    fun `before the first Live the panel is IDLE without a spinner and nothing is sent`() = runTest {
        val r = rig(epoch = 0)
        step(1_000)
        assertEquals(ArchivePanel.IDLE, r.ui.panel)
        assertNull(r.ui.message)
        assertFalse(r.ui.showRetry)
        assertTrue(r.port.sent.isEmpty())
        r.port.liveEpoch.value = 1
        runCurrent()
        assertEquals(ArchivePanel.TIMELINE, r.ui.panel)
        r.coord.close()
    }

    @Test
    fun `no card`() = runTest {
        val r = rig(script = Script(tfstat = 5, entries = listOf(TODAY_SEG)))
        assertEquals(ArchivePanel.NO_CARD, r.ui.panel)
        assertEquals("Нет SD-карты", r.ui.message)
        assertFalse(r.ui.showRetry)
        assertEquals(1, r.count(816))
        assertEquals(0, r.count(9031))
        r.coord.close()
    }

    @Test
    fun `card abnormal`() = runTest {
        val r = rig(script = Script(tfstat = 2, entries = listOf(TODAY_SEG)))
        assertEquals(ArchivePanel.CARD_ERROR, r.ui.panel)
        assertEquals(ArchiveTexts.CARD_ERROR, r.ui.message)
        assertEquals(0, r.count(9031))
        r.coord.close()
    }

    @Test
    fun `status timeout still tries the index`() = runTest {
        val r = rig(script = Script(tfstat = null, entries = listOf(TODAY_SEG)))
        assertEquals(0, r.count(9031))
        step(10_000)
        assertEquals(1, r.count(9031))
        assertEquals(ArchivePanel.TIMELINE, r.ui.panel)
        r.coord.close()
    }

    @Test
    fun `index timeout is LIST_FAILED and retry loads again`() = runTest {
        val script = Script(entries = null)
        val r = rig(script = script)
        assertEquals(1, r.count(9031))
        step(10_000)
        assertEquals(ArchivePanel.LIST_FAILED, r.ui.panel)
        assertEquals("Не удалось получить список", r.ui.message)
        assertTrue(r.ui.showRetry)
        script.entries = listOf(TODAY_SEG)
        r.coord.retry()
        runCurrent()
        assertEquals(2, r.count(816))
        assertEquals(2, r.count(9031))
        assertEquals(ArchivePanel.TIMELINE, r.ui.panel)
        assertFalse(r.ui.showRetry)
        assertNull(r.ui.message)
        r.coord.close()
    }

    @Test
    fun `no recordings today`() = runTest {
        val r = rig(script = Script(entries = listOf(OLD_SEG)))
        assertEquals(ArchivePanel.NO_RECORDINGS, r.ui.panel)
        assertEquals("За этот день записей нет", r.ui.message)
        assertEquals(setOf(LocalDate.of(2026, 10, 28)), r.ui.recordedDays)
        assertEquals("Сегодня ▾", r.ui.dayLabel)
        assertEquals(today, r.ui.selectedDay)
        assertEquals(today, r.ui.today)
        r.coord.close()
    }

    @Test
    fun `timeline today`() = runTest {
        val r = rig()
        val ui = r.ui
        assertEquals(ArchivePanel.TIMELINE, ui.panel)
        assertNull(ui.message)
        assertEquals(today, ui.selectedDay)
        assertEquals(utc("2026-10-29T21:00:00Z"), ui.dayStartMs)
        assertEquals(utc("2026-10-30T21:00:00Z"), ui.dayEndMs)
        assertEquals(
            listOf(
                Segment(utc("2026-10-29T21:00:00Z"), utc("2026-10-29T21:30:00Z")),
                Segment(utc("2026-10-30T06:00:00Z"), utc("2026-10-30T07:00:00Z")),
            ),
            ui.segments,
        )
        assertNull(ui.cursorMs)
        assertFalse(ui.inArchive)
        assertFalse(ui.playing)
        assertNull(ui.pauseLabel)
        assertFalse(ui.showLiveButton)
        assertFalse(ui.daysDialogOpen)
        recorder.assertMarkerAppeared("BLOCK_ARCHIVE_LOAD")
        r.coord.close()
    }

    @Test
    fun `spinner while the first load is in flight on a healthy link`() = runTest {
        val r = rig(script = Script(tfstat = null, entries = listOf(TODAY_SEG)))
        assertEquals(ArchivePanel.LOADING, r.ui.panel)
        assertEquals(ArchiveTexts.LOADING, r.ui.message)
        r.coord.close()
    }

    @Test
    fun `link drop during the first load hides the spinner`() = runTest {
        val r = rig(script = Script(tfstat = null, entries = listOf(TODAY_SEG)))
        r.port.healthy = false
        step(500)
        assertEquals(ArchivePanel.IDLE, r.ui.panel)
        assertNull(r.ui.message)
        r.port.healthy = null
        step(500)
        assertEquals(ArchivePanel.LOADING, r.ui.panel)
        step(10_000)
        assertEquals(ArchivePanel.TIMELINE, r.ui.panel)
        r.coord.close()
    }

    @Test
    fun `failed link before any load shows nothing and loads after the link returns`() = runTest {
        val r = rig(epoch = 0)
        r.port.healthy = false
        r.port.live = false
        step(30_000)
        assertEquals(ArchivePanel.IDLE, r.ui.panel)
        assertTrue(r.port.sent.isEmpty())
        r.port.healthy = null
        r.port.live = true
        r.port.liveEpoch.value = 1
        runCurrent()
        assertEquals(ArchivePanel.TIMELINE, r.ui.panel)
        r.coord.close()
    }

    @Test
    fun `a fetch that really failed shows LIST_FAILED even with the link down, and the next liveEpoch reloads`() = runTest {
        val script = Script(entries = null)
        val r = rig(script = script)
        step(10_000)
        assertEquals(ArchivePanel.LIST_FAILED, r.ui.panel)
        r.port.healthy = false
        step(1_000)
        assertEquals(ArchivePanel.LIST_FAILED, r.ui.panel)
        assertEquals(ArchiveTexts.LIST_FAILED, r.ui.message)
        assertTrue(r.ui.showRetry)
        script.entries = listOf(TODAY_SEG)
        r.port.healthy = null
        r.port.liveEpoch.value = 2
        runCurrent()
        assertEquals(ArchivePanel.TIMELINE, r.ui.panel)
        r.coord.close()
    }

    @Test
    fun `refused index request leaves no spinner`() = runTest {
        val r = rig(start = false)
        r.port.sendResult = false
        r.coord.start()
        runCurrent()
        assertEquals(ArchivePanel.IDLE, r.ui.panel)
        assertNull(r.ui.message)
        r.port.sendResult = true
        r.port.liveEpoch.value = 2
        runCurrent()
        assertEquals(ArchivePanel.TIMELINE, r.ui.panel)
        r.coord.close()
    }
    // END_BLOCK_LOAD_TESTS

    // START_BLOCK_DAY_TESTS
    @Test
    fun `selectDay labels and ignores days without recordings`() = runTest {
        val r = rig(script = Script(entries = listOf(TODAY_SEG, YESTERDAY_SEG, OLD_SEG)))
        r.coord.selectDay(LocalDate.of(2026, 10, 29))
        assertEquals("Вчера ▾", r.ui.dayLabel)
        assertEquals(LocalDate.of(2026, 10, 29), r.ui.selectedDay)
        assertEquals(listOf(Segment(utc("2026-10-29T10:00:00Z"), utc("2026-10-29T11:00:00Z"))), r.ui.segments)
        r.coord.selectDay(LocalDate.of(2026, 10, 28))
        assertEquals("28 октября ▾", r.ui.dayLabel)
        val before = r.ui
        r.coord.selectDay(LocalDate.of(2026, 10, 20))
        assertEquals(before, r.ui)
        r.coord.selectDay(today)
        assertEquals("Сегодня ▾", r.ui.dayLabel)
        r.coord.close()
    }

    @Test
    fun `dayLabel formats`() {
        assertEquals("Сегодня ▾", ArchiveTexts.dayLabel(today, today))
        assertEquals("Вчера ▾", ArchiveTexts.dayLabel(today.minusDays(1), today))
        assertEquals("28 октября ▾", ArchiveTexts.dayLabel(LocalDate.of(2026, 10, 28), today))
        assertEquals("1 сентября ▾", ArchiveTexts.dayLabel(LocalDate.of(2026, 9, 1), today))
    }

    @Test
    fun `openDays refreshes a stale index`() = runTest {
        val r = rig()
        assertEquals(1, r.count(9031))
        r.coord.openDays()
        runCurrent()
        assertTrue(r.ui.daysDialogOpen)
        assertEquals(1, r.count(9031))
        r.coord.closeDays()
        assertFalse(r.ui.daysDialogOpen)
        r.port.healthy = false // no periodic poll: the dialog refresh alone is counted
        step(61_000)
        r.port.healthy = null
        r.coord.openDays()
        runCurrent()
        assertTrue(r.ui.daysDialogOpen)
        assertEquals(2, r.count(9031))
        assertEquals(ArchivePanel.TIMELINE, r.ui.panel)
        r.coord.closeDays()
        assertFalse(r.ui.daysDialogOpen)
        r.coord.close()
    }

    @Test
    fun `cursor crossing midnight selects the next day`() = runTest {
        val r = rig()
        r.coord.selectDay(LocalDate.of(2026, 10, 29))
        r.coord.seek(utc("2026-10-29T20:59:50Z"))
        runCurrent()
        assertEquals(LocalDate.of(2026, 10, 29), r.ui.selectedDay)
        val uc = r.port.expected!!
        r.port.frame = obs(uc, "2026-10-29T21:00:05Z")
        step(500)
        assertEquals(utc("2026-10-29T21:00:05Z"), r.ui.cursorMs)
        assertEquals(today, r.ui.selectedDay)
        assertEquals("Сегодня ▾", r.ui.dayLabel)
        r.coord.close()
    }
    // END_BLOCK_DAY_TESTS

    // START_BLOCK_REFRESH_TESTS
    @Test
    fun `liveEpoch bump refreshes the index`() = runTest {
        val r = rig()
        assertEquals(1, r.count(9031))
        r.port.liveEpoch.value = 2
        runCurrent()
        assertEquals(2, r.count(9031))
        assertEquals(1, r.count(816))
        assertEquals(ArchivePanel.TIMELINE, r.ui.panel)
        recorder.assertMarkerAppeared("BLOCK_ARCHIVE_REFRESH")
        r.coord.close()
    }

    @Test
    fun `periodic refresh every 60 s while today's timeline is shown`() = runTest {
        val script = Script(entries = listOf(TODAY_SEG))
        val r = rig(script = script)
        step(59_000)
        assertEquals(1, r.count(9031))
        script.entries = listOf(TODAY_SEG, NEW_SEG)
        step(1_500)
        assertEquals(2, r.count(9031))
        assertEquals(utc("2026-10-30T08:50:00Z"), r.ui.segments.last().endMs)
        step(60_000)
        assertEquals(3, r.count(9031))
        assertEquals(ArchivePanel.TIMELINE, r.ui.panel)
        r.coord.close()
    }

    @Test
    fun `periodic refresh also runs for today's empty day`() = runTest {
        val script = Script(entries = listOf(OLD_SEG))
        val r = rig(script = script)
        assertEquals(ArchivePanel.NO_RECORDINGS, r.ui.panel)
        script.entries = listOf(OLD_SEG, TODAY_SEG)
        step(60_500)
        assertEquals(2, r.count(9031))
        assertEquals(ArchivePanel.TIMELINE, r.ui.panel)
        r.coord.close()
    }

    @Test
    fun `no periodic refresh while the link is unhealthy`() = runTest {
        val r = rig()
        r.port.healthy = false
        step(130_000)
        assertEquals(1, r.count(9031))
        r.port.healthy = null
        step(500)
        assertEquals(2, r.count(9031))
        r.coord.close()
    }

    @Test
    fun `no periodic refresh for a past day`() = runTest {
        val r = rig(script = Script(entries = listOf(TODAY_SEG, YESTERDAY_SEG)))
        r.coord.selectDay(LocalDate.of(2026, 10, 29))
        step(130_000)
        assertEquals(1, r.count(9031))
        r.coord.close()
    }

    @Test
    fun `no periodic refresh after onBackground until the next liveEpoch`() = runTest {
        val r = rig()
        r.coord.onBackground()
        step(130_000)
        assertEquals(1, r.count(9031))
        r.port.liveEpoch.value = 2
        runCurrent()
        assertEquals(2, r.count(9031))
        r.coord.close()
    }

    @Test
    fun `failed periodic refresh keeps the old list and waits for the next slot`() = runTest {
        val script = Script(entries = listOf(TODAY_SEG))
        val r = rig(script = script)
        val before = r.ui.segments
        script.entries = null
        step(60_500)
        assertEquals(2, r.count(9031))
        step(10_000)
        assertEquals(ArchivePanel.TIMELINE, r.ui.panel)
        assertEquals(before, r.ui.segments)
        step(40_000)
        assertEquals(2, r.count(9031))
        step(20_500)
        assertEquals(3, r.count(9031))
        r.coord.close()
    }

    @Test
    fun `refreshes never overlap`() = runTest {
        val script = Script(entries = listOf(TODAY_SEG))
        val r = rig(script = script)
        script.entries = null
        step(60_500)
        assertEquals(2, r.count(9031))
        r.port.liveEpoch.value = 2
        runCurrent()
        assertEquals(2, r.count(9031))
        step(500)
        assertEquals(2, r.count(9031))
        r.coord.close()
    }
    // END_BLOCK_REFRESH_TESTS

    // START_BLOCK_SEEK_REFRESH_TESTS
    // Pending refresh-first seek of 08:30Z (past the old list end 07:00Z) whose refresh gets no reply.
    private fun TestScope.pendingSeek(): Rig {
        val script = Script(entries = listOf(TODAY_SEG))
        val r = rig(script = script)
        step(10_000)
        script.entries = null
        r.coord.seek(utc("2026-10-30T08:30:00Z"))
        runCurrent()
        assertEquals(PlaybackState.Live, r.controller.state.value)
        return r
    }

    @Test
    fun `seek past the last known end refreshes first and resolves against the new list`() = runTest {
        val script = Script(entries = listOf(TODAY_SEG))
        val r = rig(script = script)
        step(10_000)
        script.entries = listOf(TODAY_SEG, NEW_SEG)
        r.coord.seek(utc("2026-10-30T08:30:00Z"))
        runCurrent()
        assertEquals(2, r.count(9031))
        assertTrue(
            r.port.events.lastIndexOf("send:9031") < r.port.events.indexOf("send:9030"),
            "${r.port.events}",
        )
        assertEquals(PlaybackState.Starting(utc("2026-10-30T08:30:00Z"), r.port.expected!!), r.controller.state.value)
        assertEquals(1, r.mutes)
        recorder.assertMarkerAppeared("BLOCK_ARCHIVE_REFRESH")
        r.coord.close()
    }

    @Test
    fun `first seek from Live refreshes first when the index is older than 5 s`() = runTest {
        val r = rig()
        step(6_000)
        r.coord.seek(utc("2026-10-30T06:15:00Z"))
        runCurrent()
        assertEquals(2, r.count(9031))
        assertTrue(r.port.events.lastIndexOf("send:9031") < r.port.events.indexOf("send:9030"), "${r.port.events}")
        assertEquals(PlaybackState.Starting(utc("2026-10-30T06:15:00Z"), r.port.expected!!), r.controller.state.value)
        r.coord.close()
    }

    @Test
    fun `seek with a fresh index does not refresh`() = runTest {
        val r = rig()
        r.coord.seek(utc("2026-10-30T06:15:00Z"))
        assertEquals(PlaybackState.Starting(utc("2026-10-30T06:15:00Z"), r.port.expected!!), r.controller.state.value)
        runCurrent()
        assertEquals(1, r.count(9031))
        r.coord.close()
    }

    @Test
    fun `seek inside the known list while playing does not refresh`() = runTest {
        val r = rig()
        step(6_000)
        playingAt(r, "2026-10-30T06:15:00Z")
        assertEquals(2, r.count(9031))
        r.coord.seek(utc("2026-10-30T06:30:00Z"))
        runCurrent()
        assertEquals(2, r.count(9031))
        assertEquals(utc("2026-10-30T06:30:00Z"), (r.controller.state.value as PlaybackState.Starting).atMs)
        r.coord.close()
    }

    @Test
    fun `seek on a past day never refreshes`() = runTest {
        val r = rig(script = Script(entries = listOf(TODAY_SEG, YESTERDAY_SEG)))
        step(6_000)
        r.coord.selectDay(LocalDate.of(2026, 10, 29))
        r.coord.seek(utc("2026-10-29T10:10:00Z"))
        runCurrent()
        assertEquals(1, r.count(9031))
        assertEquals(utc("2026-10-29T10:10:00Z"), (r.controller.state.value as PlaybackState.Starting).atMs)
        r.coord.close()
    }

    @Test
    fun `pending seek keeps the ruler on the target and falls back to the old list after 3 s`() = runTest {
        val r = pendingSeek()
        assertEquals(utc("2026-10-30T08:30:00Z"), r.ui.rulerMs)
        assertEquals(1, r.mutes)
        step(2_900)
        assertEquals(PlaybackState.Live, r.controller.state.value)
        assertEquals(utc("2026-10-30T08:30:00Z"), r.ui.rulerMs)
        assertEquals(0, r.count(9030))
        step(200)
        assertEquals(PlaybackState.Starting(utc("2026-10-30T06:59:54Z"), r.port.expected!!), r.controller.state.value)
        r.coord.close()
    }

    @Test
    fun `a second seek while pending replaces the target`() = runTest {
        val r = pendingSeek()
        r.coord.seek(utc("2026-10-30T06:20:00Z"))
        runCurrent()
        assertEquals(PlaybackState.Live, r.controller.state.value)
        assertEquals(utc("2026-10-30T06:20:00Z"), r.ui.rulerMs)
        step(3_100)
        assertEquals(PlaybackState.Starting(utc("2026-10-30T06:20:00Z"), r.port.expected!!), r.controller.state.value)
        assertEquals(1, r.count(9030))
        r.coord.close()
    }

    @Test
    fun `goLive cancels a pending seek`() = runTest {
        val r = pendingSeek()
        r.coord.goLive()
        step(5_000)
        assertEquals(PlaybackState.Live, r.controller.state.value)
        assertEquals(0, r.count(9030))
        r.coord.close()
    }

    @Test
    fun `onBackground cancels a pending seek`() = runTest {
        val r = pendingSeek()
        r.coord.onBackground()
        step(5_000)
        assertEquals(PlaybackState.Live, r.controller.state.value)
        assertEquals(0, r.count(9030))
        r.coord.close()
    }

    @Test
    fun `silent re-seek cap shows the no-recording hint`() = runTest {
        val r = rig()
        r.coord.seek(utc("2026-10-30T06:15:00Z"))
        runCurrent()
        r.port.unitsFlowing = false
        repeat(2) {
            step(35_000)
            r.port.healthy = false
            r.port.live = false
            r.port.archiveStalls += 1
            step(1_000)
            r.port.live = true
            r.port.healthy = null
            r.port.liveEpoch.value += 1
            runCurrent()
        }
        assertEquals(PlaybackState.Paused(utc("2026-10-30T06:15:00Z"), PauseReason.SILENT), r.controller.state.value)
        assertEquals(ArchiveTexts.NO_RECORDING_AT, r.ui.hint)
        assertEquals(ArchiveTexts.CONTINUE, r.ui.pauseLabel)
        assertTrue(r.ui.inArchive)
        r.coord.close()
    }
    // END_BLOCK_SEEK_REFRESH_TESTS

    // START_BLOCK_RULER_TESTS
    @Test
    fun `in Live the ruler follows the wall clock`() = runTest {
        val r = rig(movingWall = true)
        assertEquals(wallSec(), r.ui.rulerMs)
        assertNull(r.ui.cursorMs)
        val first = r.ui.rulerMs!!
        step(1_000)
        assertEquals(wallSec(), r.ui.rulerMs)
        assertEquals(first + 1_000, r.ui.rulerMs)
        step(5_000)
        assertEquals(first + 6_000, r.ui.rulerMs)
        r.coord.close()
    }

    @Test
    fun `after goLive the ruler tracks the wall clock, not the last archive position`() = runTest {
        val r = rig(movingWall = true)
        playingAt(r, "2026-10-30T06:15:00Z")
        assertEquals(utc("2026-10-30T06:15:00Z"), r.ui.rulerMs)
        r.coord.goLive()
        runCurrent()
        step(500)
        assertEquals(wallSec(), r.ui.rulerMs)
        assertNotEquals(utc("2026-10-30T06:15:00Z"), r.ui.rulerMs)
        assertNull(r.ui.cursorMs)
        assertFalse(r.ui.inArchive)
        step(3_000)
        assertEquals(wallSec(), r.ui.rulerMs)
        r.coord.close()
    }

    @Test
    fun `goLive from a past day selects today`() = runTest {
        val r = rig(script = Script(entries = listOf(TODAY_SEG, YESTERDAY_SEG)))
        r.coord.selectDay(LocalDate.of(2026, 10, 29))
        playingAt(r, "2026-10-29T10:10:00Z")
        assertEquals(LocalDate.of(2026, 10, 29), r.ui.selectedDay)
        r.coord.goLive()
        runCurrent()
        assertEquals(today, r.ui.selectedDay)
        assertEquals("Сегодня ▾", r.ui.dayLabel)
        assertTrue(r.ui.rulerMs != null)
        r.coord.close()
    }

    @Test
    fun `in Live with a past day selected the ruler has no live position`() = runTest {
        val r = rig(script = Script(entries = listOf(TODAY_SEG, YESTERDAY_SEG)))
        r.coord.selectDay(LocalDate.of(2026, 10, 29))
        step(1_000)
        assertEquals(LocalDate.of(2026, 10, 29), r.ui.selectedDay)
        assertNull(r.ui.rulerMs)
        r.coord.close()
    }

    @Test
    fun `in archive rulerMs equals the playback position`() = runTest {
        val r = rig()
        r.coord.seek(utc("2026-10-30T06:15:00Z"))
        val starting = r.controller.state.value as PlaybackState.Starting
        assertEquals(starting.atMs, r.ui.rulerMs)
        val uc = r.port.expected!!
        r.port.frame = obs(uc, "2026-10-30T06:15:02Z")
        step(500)
        val playing = r.controller.state.value as PlaybackState.Playing
        assertEquals(playing.positionMs, r.ui.rulerMs)
        assertEquals(r.ui.cursorMs, r.ui.rulerMs)
        r.coord.close()
    }

    @Test
    fun `live day rolls over at midnight`() = runTest {
        val start = utc("2026-10-30T20:59:58Z") // 23:59:58 local
        val r = rig(wallStart = start, movingWall = true)
        assertEquals(today, r.ui.selectedDay)
        step(3_000)
        assertEquals(LocalDate.of(2026, 10, 31), r.ui.selectedDay)
        assertEquals("Сегодня ▾", r.ui.dayLabel)
        assertEquals(wallSec(start), r.ui.rulerMs)
        r.coord.close()
    }
    // END_BLOCK_RULER_TESTS

    // START_BLOCK_PLAYBACK_TESTS
    @Test
    fun `seek enters archive and forces mute`() = runTest {
        val r = rig()
        r.coord.seek(utc("2026-10-30T06:15:00Z"))
        assertEquals(1, r.mutes)
        assertTrue(r.ui.inArchive)
        assertEquals(ArchiveTexts.PAUSE, r.ui.pauseLabel)
        assertTrue(r.ui.showLiveButton)
        assertFalse(r.ui.playing)
        assertEquals(utc("2026-10-30T06:15:00Z"), r.ui.cursorMs)
        val uc = r.port.expected!!
        r.port.frame = obs(uc, "2026-10-30T06:15:02Z")
        step(500)
        assertTrue(r.ui.playing)
        assertEquals(utc("2026-10-30T06:15:02Z"), r.ui.cursorMs)
        // a second seek inside the archive does not mute again
        r.coord.seek(utc("2026-10-30T06:30:00Z"))
        assertEquals(1, r.mutes)
        r.coord.close()
    }

    @Test
    fun `togglePause pauses and continues`() = runTest {
        val r = rig()
        playingAt(r, "2026-10-30T06:15:00Z")
        r.coord.togglePause()
        assertTrue(r.controller.state.value is PlaybackState.Paused)
        assertEquals("Продолжить", r.ui.pauseLabel)
        assertFalse(r.ui.playing)
        assertTrue(r.ui.inArchive)
        r.coord.togglePause()
        assertTrue(r.controller.state.value is PlaybackState.Starting)
        assertEquals(ArchiveTexts.PAUSE, r.ui.pauseLabel)
        r.coord.close()
    }

    @Test
    fun `goLive forces mute and leaves the archive`() = runTest {
        val r = rig()
        playingAt(r, "2026-10-30T06:15:00Z")
        r.coord.goLive()
        assertEquals(2, r.mutes)
        assertEquals(PlaybackState.Live, r.controller.state.value)
        assertFalse(r.ui.inArchive)
        assertNull(r.ui.cursorMs)
        assertNull(r.ui.pauseLabel)
        assertFalse(r.ui.showLiveButton)
        r.coord.close()
    }

    @Test
    fun `ended shows the hint`() = runTest {
        val r = rig()
        playingAt(r, "2026-10-30T06:59:55Z")
        step(30_000)
        assertEquals(PauseReason.ENDED, (r.controller.state.value as PlaybackState.Paused).reason)
        assertEquals("Записи закончились", r.ui.hint)
        assertEquals("Продолжить", r.ui.pauseLabel)
        r.coord.close()
    }

    @Test
    fun `start failed on TNP shows the hint and keeps the timeline`() = runTest {
        val r = rig()
        r.coord.seek(utc("2026-10-30T06:15:00Z"))
        step(20_500)
        assertTrue(r.controller.state.value is PlaybackState.Failed, "${r.controller.state.value}")
        assertEquals(ArchivePanel.TIMELINE, r.ui.panel)
        assertEquals("Не удалось начать воспроизведение", r.ui.hint)
        assertEquals("Продолжить", r.ui.pauseLabel)
        r.coord.close()
    }

    @Test
    fun `no recording at the chosen time shows the hint`() = runTest {
        val r = rig(script = Script(entries = listOf(OLD_SEG)))
        r.coord.seek(utc("2026-10-28T10:15:00Z"))
        assertTrue(r.ui.inArchive)
        r.coord.close()
        val empty = rig(script = Script(entries = emptyList()))
        empty.coord.seek(utc("2026-10-30T06:15:00Z"))
        assertEquals(ArchiveTexts.NO_RECORDING_AT, empty.ui.hint)
        empty.coord.close()
    }
    // END_BLOCK_PLAYBACK_TESTS

    // START_BLOCK_TUTK_HIDE_TESTS
    @Test
    fun `TUTK playback rejected hides the archive`() = runTest {
        val r = rig(transport = TransportType.TUTK)
        assertEquals(ArchivePanel.TIMELINE, r.ui.panel)
        r.coord.seek(utc("2026-10-30T06:15:00Z"))
        step(20_500)
        assertEquals(ArchivePanel.HIDDEN, r.ui.panel)
        assertEquals("Архив SD на этой камере недоступен", r.ui.message)
        assertEquals(PlaybackState.Live, r.controller.state.value)
        assertFalse(r.ui.inArchive)
        r.coord.close()
    }

    @Test
    fun `TUTK network loss during start does not hide`() = runTest {
        val r = rig(transport = TransportType.TUTK)
        r.coord.seek(utc("2026-10-30T06:15:00Z"))
        runCurrent()
        r.port.healthy = false // network unavailable, link still open
        assertTrue(r.port.isLive())
        step(25_000)
        assertNotEquals(ArchivePanel.HIDDEN, r.ui.panel)
        assertTrue(r.controller.state.value is PlaybackState.Starting, "${r.controller.state.value}")
        assertNotEquals(ArchiveTexts.START_FAILED, r.ui.hint)
        r.port.healthy = null
        r.port.liveEpoch.value = 2
        runCurrent()
        val uc = r.port.expected!!
        r.port.frame = obs(uc, "2026-10-30T06:15:01Z")
        step(500)
        assertTrue(r.ui.playing)
        assertEquals(ArchivePanel.TIMELINE, r.ui.panel)
        r.coord.close()
    }

    @Test
    fun `TUTK mid-segment silence does not hide`() = runTest {
        val r = rig(transport = TransportType.TUTK)
        playingAt(r, "2026-10-30T06:15:00Z")
        step(34_000)
        assertEquals(ArchivePanel.TIMELINE, r.ui.panel)
        assertTrue(r.ui.inArchive)
        r.coord.close()
    }

    @Test
    fun `TUTK link drop during start does not hide`() = runTest {
        val r = rig(transport = TransportType.TUTK)
        r.coord.seek(utc("2026-10-30T06:15:00Z"))
        runCurrent()
        r.port.live = false
        step(15_000)
        r.port.live = true
        r.port.liveEpoch.value = 2
        runCurrent()
        val uc = r.port.expected!!
        r.port.frame = obs(uc, "2026-10-30T06:15:01Z")
        step(500)
        assertEquals(ArchivePanel.TIMELINE, r.ui.panel)
        assertTrue(r.ui.playing)
        r.coord.close()
    }

    @Test
    fun `TUTK start failure after a window with a drop does not hide`() = runTest {
        val r = rig(transport = TransportType.TUTK)
        r.coord.seek(utc("2026-10-30T06:15:00Z"))
        runCurrent()
        r.port.live = false
        step(15_000)
        r.port.live = true
        r.port.liveEpoch.value = 2
        runCurrent()
        step(20_500)
        assertTrue(r.controller.state.value is PlaybackState.Failed, "${r.controller.state.value}")
        assertEquals(ArchivePanel.TIMELINE, r.ui.panel)
        assertEquals(ArchiveTexts.START_FAILED, r.ui.hint)
        r.coord.close()
    }

    @Test
    fun `TUTK camera silent after a seek does not hide (no units at all)`() = runTest {
        val r = rig(transport = TransportType.TUTK)
        r.coord.seek(utc("2026-10-30T06:15:00Z"))
        runCurrent()
        r.port.unitsFlowing = false
        step(25_000)
        assertEquals(ArchivePanel.TIMELINE, r.ui.panel)
        assertTrue(r.controller.state.value is PlaybackState.Starting, "${r.controller.state.value}")
        r.coord.close()
    }
    // END_BLOCK_TUTK_HIDE_TESTS

    // START_BLOCK_LIFECYCLE_TESTS
    @Test
    fun `background pauses and a liveEpoch bump does not resume`() = runTest {
        val r = rig()
        playingAt(r, "2026-10-30T06:15:00Z")
        r.coord.onBackground()
        assertEquals(PauseReason.BACKGROUND, (r.controller.state.value as PlaybackState.Paused).reason)
        assertEquals("Продолжить", r.ui.pauseLabel)
        r.port.liveEpoch.value = 2
        step(5_000)
        assertEquals(PauseReason.BACKGROUND, (r.controller.state.value as PlaybackState.Paused).reason)
        r.coord.close()
    }

    @Test
    fun `background then auto-resume keeps the marker`() = runTest {
        val r = rig()
        playingAt(r, "2026-10-30T06:15:00Z")
        val t = r.ui.cursorMs!!
        r.coord.onBackground()
        val sent = r.playbackSent() // the reconnect index refresh (plan 04-12) is not a playback command
        r.port.liveEpoch.value = 2
        step(1_000)
        assertEquals(sent, r.playbackSent())
        assertTrue(r.ui.inArchive)
        assertEquals("Продолжить", r.ui.pauseLabel)
        assertEquals(t, r.ui.cursorMs)
        assertTrue(r.ui.showLiveButton)
        assertEquals(ArchivePanel.TIMELINE, r.ui.panel)
        r.coord.togglePause()
        assertEquals(t, (r.controller.state.value as PlaybackState.Starting).atMs)
        assertTrue(r.playbackSent() > sent)
        r.coord.close()
    }

    @Test
    fun `unsupported transport is HIDDEN and sends nothing`() = runTest {
        val r = rig(transport = TransportType.LANGTAO)
        step(2_000)
        assertEquals(ArchivePanel.HIDDEN, r.ui.panel)
        assertEquals(ArchiveTexts.UNSUPPORTED, r.ui.message)
        assertTrue(r.port.sent.isEmpty())
        r.coord.close()
        val unresolved = rig(transport = null)
        step(2_000)
        assertEquals(ArchivePanel.HIDDEN, unresolved.ui.panel)
        assertTrue(unresolved.port.sent.isEmpty())
        unresolved.coord.close()
    }

    @Test
    fun `close stops loading and playback`() = runTest {
        val r = rig(epoch = 0)
        r.coord.close()
        r.port.liveEpoch.value = 1
        step(2_000)
        assertTrue(r.port.sent.isEmpty())
    }

    @Test
    fun `texts are the D-07 wording`() {
        assertEquals("Нет SD-карты", ArchiveTexts.NO_CARD)
        assertEquals("За этот день записей нет", ArchiveTexts.NO_RECORDINGS)
        assertEquals("Не удалось получить список", ArchiveTexts.LIST_FAILED)
        assertEquals("Повторить", ArchiveTexts.RETRY)
        assertEquals("Живой", ArchiveTexts.LIVE)
        assertEquals("Продолжить", ArchiveTexts.CONTINUE)
        assertEquals("Загрузка записей…", ArchiveTexts.LOADING)
    }
    // END_BLOCK_LIFECYCLE_TESTS
}
