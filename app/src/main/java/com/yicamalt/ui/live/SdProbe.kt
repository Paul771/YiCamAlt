// FILE: SdProbe.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Debug SD archive probe on the live-probe session: counts what the camera sends per channel / generation
//     and exercises every archive command (816 info, index, seek, pause, resume, live, segment boundary) so the device
//     facts of research "Device Probe Plan" can be read from value-free log lines.
//   SCOPE: SdProbeTee (VideoUnitSink tee in front of the FramePipeline: per-channel, recorded, other-generation and
//     audio counters, first recorded / first live key frame of the expected generation, max timestamp jump between
//     recorded key frames, stall across the jump), ProbeArchivePort (ArchiveLinkPort over one CameraLink, no
//     supervisor), SdProbe (one coroutine per action, 5 s channel ticker). Debug only: never used by the product UI.
//   DEPENDS: M-SD-ARCHIVE (ArchiveLinkPort, SdIndexRepository, SdTimeline, SdTime, PlaybackCommands, SdArchiveCodec),
//     M-STREAM-CORE (CameraLink, VideoUnit, FrameObservation, StreamMode, IoCtrlMessages), kotlinx.coroutines, Timber
//   LINKS: M-UI-LIVE-PROBE, V-M-UI-LIVE-PROBE, M-SD-ARCHIVE
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.ui.live

import com.yicamalt.archive.ArchiveLinkPort
import com.yicamalt.archive.IndexResult
import com.yicamalt.archive.PlaybackCmd
import com.yicamalt.archive.PlaybackCommands
import com.yicamalt.archive.SdArchiveCodec
import com.yicamalt.archive.SdIndexRepository
import com.yicamalt.archive.SdTime
import com.yicamalt.archive.SdTimeline
import com.yicamalt.archive.Segment
import com.yicamalt.camera.TransportType
import com.yicamalt.stream.CameraLink
import com.yicamalt.stream.FrameObservation
import com.yicamalt.stream.IoCtrlMessages
import com.yicamalt.stream.LinkIoCtrlReply
import com.yicamalt.stream.LinkState
import com.yicamalt.stream.StreamMode
import com.yicamalt.stream.VideoUnit
import com.yicamalt.stream.VideoUnitSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.time.ZoneId

// START_MODULE_MAP
//   SdProbeAction - INFO | INDEX | PLAY_NEWEST | PAUSE | RESUME | LIVE | BOUNDARY (one probe button each).
//   SdProbeTee - VideoUnitSink tee: forwards every unit unchanged; perChannel, recordedUnits, otherUcUnits,
//     totalUnits, audioUnits / onAudioUnit, expect(uc), firstRecorded (FirstRecord), firstLiveAtMs, maxJumpS,
//     jumpAtMs, stallMs, lastRecordedTsSec, lastFrame.
//   ProbeArchivePort - ArchiveLinkPort over the probe CameraLink (liveEpoch fixed 1, linkHealthy = isLive,
//     framesIn = tee.totalUnits).
//   SdProbe - run(action) launches one probe action; startChannelTicker() logs BLOCK_SD_CHANNELS every 5 s while live.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 04-04: SD archive debug probe (research "Device Probe Plan", Pitfalls 1 / 4).
//     Log fields are counts, codes, sizes, ms and second deltas only; never DIDs, uids, passwords or absolute times.
//     PAUSE: framesAfterMs500 = units in the first 500 ms after the commands (in flight), framesAfterS5 = units from
//     500 ms to 5 s (should be 0 when the camera really stops). BOUNDARY logs resumedAfterS (first recorded frame ->
//     first frame after the jump, as planned) plus stallS (wall gap across the jump) and gapS (index gap), because
//     the seek lands 10 s before the segment end and resumedAfterS alone always includes those 10 s.
// END_CHANGE_SUMMARY

enum class SdProbeAction { INFO, INDEX, PLAY_NEWEST, PAUSE, RESUME, LIVE, BOUNDARY }

// START_CONTRACT: SdProbeTee
//   PURPOSE: Count the probe session's video units per channel / generation and observe the archive generation.
//   INPUTS: { next: VideoUnitSink - the FramePipeline (receives every unit unchanged); clock: () -> Long - ms clock }
//   OUTPUTS: counters and observations (thread-safe snapshots)
//   SIDE_EFFECTS: none beyond forwarding to next
//   LINKS: M-UI-LIVE-PROBE, V-M-UI-LIVE-PROBE
// END_CONTRACT: SdProbeTee
class SdProbeTee(private val next: VideoUnitSink, private val clock: () -> Long) : VideoUnitSink {

    data class FirstRecord(
        val channel: Int,
        val useCount: Int,
        val tsSec: Long,
        val width: Int,
        val height: Int,
        val codecId: Int,
        val atMs: Long,
    )

    private val channels = LinkedHashMap<Int, Long>()
    private var total = 0L
    private var recorded = 0L
    private var otherUc = 0L
    private var audio = 0L
    private var expected: Int? = null
    private var first: FirstRecord? = null
    private var firstLive: Long? = null
    private var keyFrames = 0L
    private var maxJump = 0L
    private var lastKeyTs: Long? = null
    private var lastKeyAtMs = 0L
    private var jumpAt: Long? = null
    private var stall: Long? = null
    private var lastRecordedTs: Long? = null
    private var last: FrameObservation? = null

    override fun onVideoUnit(unit: VideoUnit) {
        // START_BLOCK_SD_TEE_COUNT
        val now = clock()
        val info = unit.info
        val isRecorded = (info.liveFlag and 1) == 1
        synchronized(this) {
            total++
            channels[unit.channel] = (channels[unit.channel] ?: 0L) + 1
            if (isRecorded) recorded++
            val exp = expected
            if (exp != null && info.useCount != exp) {
                otherUc++
            } else {
                if (info.isKeyFrame) keyFrames++
                last = FrameObservation(info.useCount, info.timestampSec, isRecorded, keyFrames)
                if (isRecorded) lastRecordedTs = info.timestampSec
                if (info.isKeyFrame && isRecorded) observeRecordedKey(unit, now)
                if (info.isKeyFrame && !isRecorded && exp != null && firstLive == null) firstLive = now
            }
            Unit
        }
        // END_BLOCK_SD_TEE_COUNT
        next.onVideoUnit(unit)
    }

    // Caller holds the lock.
    private fun observeRecordedKey(unit: VideoUnit, now: Long) {
        val info = unit.info
        if (expected != null && first == null) {
            first = FirstRecord(unit.channel, info.useCount, info.timestampSec, info.width, info.height, info.codecId, now)
        }
        val prev = lastKeyTs
        if (prev != null) {
            val jump = info.timestampSec - prev
            if (jump > maxJump) maxJump = jump
            if (jump > JUMP_THRESHOLD_S && jumpAt == null) {
                jumpAt = now
                stall = now - lastKeyAtMs
            }
        }
        lastKeyTs = info.timestampSec
        lastKeyAtMs = now
    }

    @Synchronized fun onAudioUnit() {
        audio++
    }

    // New generation: resets every per-generation observation (counters keep running).
    @Synchronized fun expect(useCount: Int?) {
        expected = useCount
        first = null
        firstLive = null
        keyFrames = 0
        maxJump = 0
        lastKeyTs = null
        jumpAt = null
        stall = null
    }

    @Synchronized fun perChannel(): Map<Int, Long> = LinkedHashMap(channels)
    @Synchronized fun recordedUnits(): Long = recorded
    @Synchronized fun otherUcUnits(): Long = otherUc
    @Synchronized fun totalUnits(): Long = total
    @Synchronized fun audioUnits(): Long = audio
    @Synchronized fun firstRecorded(): FirstRecord? = first
    @Synchronized fun firstLiveAtMs(): Long? = firstLive
    @Synchronized fun maxJumpS(): Long = maxJump
    @Synchronized fun jumpAtMs(): Long? = jumpAt
    @Synchronized fun stallMs(): Long? = stall
    @Synchronized fun lastRecordedTsSec(): Long? = lastRecordedTs
    @Synchronized fun lastFrame(): FrameObservation? = last

    companion object {
        // A recorded key-frame timestamp step above this is a jump over a recording gap.
        const val JUMP_THRESHOLD_S = 10L
    }
}

// START_CONTRACT: ProbeArchivePort
//   PURPOSE: ArchiveLinkPort over the probe's single CameraLink (debug: no supervisor, no reconnect).
//   INPUTS: { link: CameraLink; transport: TransportType; encrypted: Boolean - p2pEncrypt; tee: SdProbeTee }
//   OUTPUTS: ArchiveLinkPort members delegating to the link and the tee
//   SIDE_EFFECTS: sendIoCtrl / setStreamMode reach the link
//   LINKS: M-UI-LIVE-PROBE, M-SD-ARCHIVE
// END_CONTRACT: ProbeArchivePort
class ProbeArchivePort(
    private val link: CameraLink,
    override val transport: TransportType,
    override val encrypted: Boolean,
    private val tee: SdProbeTee,
) : ArchiveLinkPort {
    override val bigEndian: Boolean get() = link.transportBigEndian
    override val liveEpoch: StateFlow<Int> = MutableStateFlow(1)
    override val ioCtrlReplies: Flow<LinkIoCtrlReply> get() = link.ioCtrlReplies
    override fun isLive(): Boolean = link.state.value is LinkState.Streaming
    override fun linkHealthy(): Boolean = isLive()
    override fun sendIoCtrl(type: Int, payload: ByteArray): Boolean = link.sendIoCtrl(type, payload)
    override fun nextUseCount(): Int? = link.nextUseCount()
    override fun liveResolution(): Int = IoCtrlMessages.RESOLUTION_HD
    override fun expectGeneration(useCount: Int?) = tee.expect(useCount)
    override fun lastFrame(): FrameObservation? = tee.lastFrame()
    override fun framesIn(): Long = tee.totalUnits()
    override fun setStreamMode(mode: StreamMode) = link.setStreamMode(mode)
}

// START_CONTRACT: SdProbe
//   PURPOSE: Run one archive probe action at a time per button press on the probe session and log the outcome.
//   INPUTS: { port: ProbeArchivePort; tee: SdProbeTee; scope: CoroutineScope - session scope (off Main in the app);
//             clock: () -> Long - ms for durations; wallClock: () -> Long - wall time for the index range only }
//   OUTPUTS: none (log lines [Archive][probe][BLOCK_SD_*])
//   SIDE_EFFECTS: IOCTRL sends and stream mode changes on the link; value-free log lines
//   LINKS: M-UI-LIVE-PROBE, V-M-UI-LIVE-PROBE, M-SD-ARCHIVE
// END_CONTRACT: SdProbe
class SdProbe(
    private val port: ProbeArchivePort,
    private val tee: SdProbeTee,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    @Volatile private var segments: List<Segment> = emptyList()

    fun run(action: SdProbeAction): Job = scope.launch {
        when (action) {
            SdProbeAction.INFO -> info()
            SdProbeAction.INDEX -> index()
            SdProbeAction.PLAY_NEWEST -> playNewest()
            SdProbeAction.PAUSE -> pause()
            SdProbeAction.RESUME -> resume()
            SdProbeAction.LIVE -> live()
            SdProbeAction.BOUNDARY -> boundary()
        }
    }

    // START_BLOCK_SD_CHANNELS
    fun startChannelTicker(): Job = scope.launch {
        while (isActive) {
            delay(CHANNEL_TICK_MS)
            if (!port.isLive()) continue
            val ch = tee.perChannel()
            Timber.i(
                "[Archive][probe][BLOCK_SD_CHANNELS] ch2=%d ch3=%d ch4=%d ch5=%d ch6=%d ch7=%d recorded=%d otherUc=%d audioUnits=%d",
                ch[2] ?: 0L, ch[3] ?: 0L, ch[4] ?: 0L, ch[5] ?: 0L, ch[6] ?: 0L, ch[7] ?: 0L,
                tee.recordedUnits(), tee.otherUcUnits(), tee.audioUnits(),
            )
        }
    }
    // END_BLOCK_SD_CHANNELS

    // START_BLOCK_SD_INFO
    private suspend fun info() {
        val started = clock()
        val (sent, reply) = exchange(SdArchiveCodec.DEVICE_INFO_REQ, SdArchiveCodec.deviceInfoRequest(), SdArchiveCodec.DEVICE_INFO_RESP)
        val ms = clock() - started
        when {
            !sent -> Timber.i("[Archive][probe][BLOCK_SD_INFO] transport=%s result=send-refused", port.transport)
            reply == null -> Timber.i("[Archive][probe][BLOCK_SD_INFO] transport=%s result=timeout ms=%d", port.transport, ms)
            else -> {
                val d = SdArchiveCodec.parseDeviceInfo(reply.payload, port.bigEndian)
                if (d == null) {
                    Timber.i(
                        "[Archive][probe][BLOCK_SD_INFO] transport=%s result=malformed replyLen=%d ms=%d",
                        port.transport, reply.payload.size, ms,
                    )
                } else {
                    Timber.i(
                        "[Archive][probe][BLOCK_SD_INFO] transport=%s replyLen=%d iface=%d hw=%d tfstat=%d totalMb=%d freeMb=%d isUtc=%d ms=%d",
                        port.transport, reply.payload.size, d.interfaceVersion, d.hardwareVersion, d.tfstat,
                        d.totalMb, d.freeMb, d.isUtc, ms,
                    )
                }
            }
        }
    }

    // Subscribe before send so a fast reply is never lost. first = false when the link refused the send.
    private suspend fun exchange(type: Int, payload: ByteArray, want: Int): Pair<Boolean, LinkIoCtrlReply?> = coroutineScope {
        val reply = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(REPLY_TIMEOUT_MS) { port.ioCtrlReplies.first { it.type == want } }
        }
        if (!port.sendIoCtrl(type, payload)) {
            reply.cancel()
            false to null
        } else {
            true to reply.await()
        }
    }
    // END_BLOCK_SD_INFO

    // START_BLOCK_SD_INDEX
    private suspend fun index() {
        val started = clock()
        val wallNow = wallClock()
        val result = SdIndexRepository(port, clock = clock).fetchIndex(wallNow)
        val segs = (result as? IndexResult.Ok)?.segments.orEmpty()
        if (result is IndexResult.Ok) segments = segs
        val name = when (result) {
            is IndexResult.Ok -> "ok"
            IndexResult.Timeout -> "timeout"
            IndexResult.Malformed -> "malformed"
            IndexResult.NotReady -> "not-ready"
        }
        val newest = segs.maxByOrNull { it.endMs }
        Timber.i(
            "[Archive][probe][BLOCK_SD_INDEX] result=%s count=%d sumDurS=%d maxDurS=%d days=%d newestAgoMin=%d ms=%d",
            name, segs.size, segs.sumOf { (it.endMs - it.startMs) / 1_000 },
            segs.maxOfOrNull { (it.endMs - it.startMs) / 1_000 } ?: 0L,
            SdTimeline.daysWithRecordings(segs, ZoneId.systemDefault()).size,
            newest?.let { (wallNow - it.endMs) / 60_000 } ?: -1L, clock() - started,
        )
    }
    // END_BLOCK_SD_INDEX

    // START_BLOCK_SD_FIRST_FRAME
    private suspend fun playNewest() {
        val newest = segments.maxByOrNull { it.startMs }
        if (newest == null) {
            Timber.i("[Archive][probe][BLOCK_SD_FIRST_FRAME] action=play result=no-index")
            return
        }
        val dur = newest.endMs - newest.startMs
        playAt(newest.startMs + minOf(PLAY_OFFSET_MS, dur / 2), "play")
    }

    private suspend fun resume() {
        val ts = tee.lastRecordedTsSec()
        if (ts == null) {
            Timber.i("[Archive][probe][BLOCK_SD_FIRST_FRAME] action=resume result=no-position")
            return
        }
        playAt(ts * 1_000, "resume")
    }

    // Seek with a new generation; returns the send time (ms clock) or null when no seek was sent.
    private suspend fun playAt(targetMs: Long, action: String): Long? {
        val uc = port.nextUseCount()
        if (uc == null) {
            Timber.i("[Archive][probe][BLOCK_SD_FIRST_FRAME] action=%s result=no-uc", action)
            return null
        }
        tee.expect(uc)
        port.setStreamMode(StreamMode.ARCHIVE)
        val sentAt = clock()
        val ok = sendAll(PlaybackCommands.seek(port.transport, port.encrypted, uc, port.liveResolution(), SdTime.toWire(targetMs)))
        val requestedSec = targetMs / 1_000
        val first = awaitValue(FIRST_FRAME_WAIT_MS) { tee.firstRecorded() }
        if (first == null) {
            Timber.i("[Archive][probe][BLOCK_SD_FIRST_FRAME] action=%s result=no-frame uc=%d sent=%b", action, uc, ok)
        } else {
            Timber.i(
                "[Archive][probe][BLOCK_SD_FIRST_FRAME] action=%s ch=%d liveFlag=1 uc=%d codec=%d size=%dx%d deltaS=%d ms=%d sent=%b",
                action, first.channel, first.useCount, first.codecId, first.width, first.height,
                first.tsSec - requestedSec, first.atMs - sentAt, ok,
            )
        }
        return sentAt
    }
    // END_BLOCK_SD_FIRST_FRAME

    // START_BLOCK_SD_PAUSE
    private suspend fun pause() {
        val ok = sendAll(PlaybackCommands.pause(port.transport, port.encrypted))
        port.setStreamMode(StreamMode.PAUSED)
        val t0 = tee.totalUnits()
        delay(PAUSE_FIRST_MS)
        val t1 = tee.totalUnits()
        Timber.i("[Archive][probe][BLOCK_SD_PAUSE] framesAfterMs500=%d sent=%b", t1 - t0, ok)
        delay(PAUSE_TOTAL_MS - PAUSE_FIRST_MS)
        Timber.i("[Archive][probe][BLOCK_SD_PAUSE] framesAfterS5=%d", tee.totalUnits() - t1)
    }
    // END_BLOCK_SD_PAUSE

    // START_BLOCK_SD_LIVE
    private suspend fun live() {
        val uc = port.nextUseCount()
        if (uc == null) {
            Timber.i("[Archive][probe][BLOCK_SD_LIVE] result=no-uc")
            return
        }
        tee.expect(uc)
        port.setStreamMode(StreamMode.LIVE)
        val sentAt = clock()
        val ok = sendAll(PlaybackCommands.live(port.transport, port.encrypted, uc, port.liveResolution()))
        val at = awaitValue(FIRST_FRAME_WAIT_MS) { tee.firstLiveAtMs() }
        if (at == null) {
            Timber.i("[Archive][probe][BLOCK_SD_LIVE] result=no-frame uc=%d sent=%b", uc, ok)
        } else {
            Timber.i("[Archive][probe][BLOCK_SD_LIVE] firstLiveMs=%d liveFlag=0 uc=%d sent=%b", at - sentAt, uc, ok)
        }
    }
    // END_BLOCK_SD_LIVE

    // START_BLOCK_SD_BOUNDARY
    private suspend fun boundary() {
        val segs = segments.sortedBy { it.startMs }
        if (segs.size < 2) {
            Timber.i("[Archive][probe][BLOCK_SD_BOUNDARY] result=no-boundary count=%d", segs.size)
            return
        }
        val seg = segs[0]
        val gapS = (segs[1].startMs - seg.endMs) / 1_000
        val sentAt = playAt(maxOf(seg.startMs, seg.endMs - BOUNDARY_LEAD_MS), "boundary") ?: return
        delay((BOUNDARY_WAIT_MS - (clock() - sentAt)).coerceAtLeast(0))
        val first = tee.firstRecorded()
        val jumpAt = tee.jumpAtMs()
        val resumedAfterS = if (first != null && jumpAt != null) (jumpAt - first.atMs) / 1_000 else -1L
        Timber.i(
            "[Archive][probe][BLOCK_SD_BOUNDARY] jumpS=%d resumedAfterS=%d stallS=%d gapS=%d",
            tee.maxJumpS(), resumedAfterS, tee.stallMs()?.let { it / 1_000 } ?: -1L, gapS,
        )
    }
    // END_BLOCK_SD_BOUNDARY

    private fun sendAll(cmds: List<PlaybackCmd>): Boolean {
        var ok = true
        for (c in cmds) ok = port.sendIoCtrl(c.type, c.payload) && ok
        return ok
    }

    private suspend fun <T> awaitValue(maxMs: Long, read: () -> T?): T? {
        var waited = 0L
        while (true) {
            read()?.let { return it }
            if (waited >= maxMs) return null
            delay(POLL_MS)
            waited += POLL_MS
        }
    }

    companion object {
        const val REPLY_TIMEOUT_MS = 10_000L
        const val FIRST_FRAME_WAIT_MS = 20_000L
        const val POLL_MS = 100L
        const val PLAY_OFFSET_MS = 30_000L
        const val PAUSE_FIRST_MS = 500L
        const val PAUSE_TOTAL_MS = 5_000L
        const val BOUNDARY_LEAD_MS = 10_000L
        const val BOUNDARY_WAIT_MS = 40_000L
        const val CHANNEL_TICK_MS = 5_000L
    }
}
