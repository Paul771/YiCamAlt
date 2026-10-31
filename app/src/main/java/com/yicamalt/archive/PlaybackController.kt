// FILE: PlaybackController.kt
// VERSION: 0.3.0
// START_MODULE_CONTRACT
//   PURPOSE: Archive playback state machine over ArchiveLinkPort: seek, pause, resume, back to live, segment
//            boundaries, end of recording, reconnect and background (SD-03, SD-04; decisions D-04, D-05).
//   SCOPE: PlaybackState values, useCount gate before every seek / go-live, ticker (start timeout, boundary jump,
//          end of the last segment, live gate release), liveEpoch re-seek, link-health freeze. Logs value-free.
//   DEPENDS: SdTypes (ArchiveLinkPort, Segment, SeekTarget), SdTimeline, SdTime, PlaybackCommands,
//            M-STREAM-CORE (StreamMode, FrameObservation), kotlinx.coroutines, Timber
//   LINKS: M-SD-ARCHIVE, V-M-SD-ARCHIVE
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.archive

import com.yicamalt.stream.StreamMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.ZoneId

// START_MODULE_MAP
//   PauseReason - USER | ENDED | BACKGROUND | SILENT (repeated ARCHIVE-stall reconnects at one position).
//   FailReason - NOT_READY | NO_FRAMES | NO_RECORDING.
//   PlaybackState - Live | Starting(atMs, useCount; -1 = waiting for a healthy link) | Playing(positionMs, useCount)
//     | Paused(positionMs, reason) | Failed(positionMs, reason).
//   PlaybackController - state, start, setSegments, seek, pause, resume, goLive, onSessionStopped, close,
//     Timing (tick 500 ms, start timeout 20 s, boundary 8 s, ended 30 s, end-near 10 s, maxSilentReseeks 2),
//     isReconnecting (tests).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.3.0 - GSD plan 04-10 (W-2b): a reconnect whose previous link was closed by the ARCHIVE stall
//     (port.archiveStallCount grew since the last sent seek) at the same position (+-2 s) counts as a silent
//     re-seek; at 2 the controller goes Paused(SILENT) with live video instead of re-seeking; network drops never
//     count; user seek / resume / goLive / pause reset the count.
//   PREVIOUS: v0.2.0 - GSD plan 04-10 (W-2a): a position at or after the last known segment end with no next
//     segment counts as end-near, so 30 s without a key frame there gives Paused(ENDED) before the 35 s ARCHIVE
//     stall (log reason ended-past-index).
//   PREVIOUS: v0.1.0 - GSD plan 04-05: playback state machine (research Pattern 1 / 5, P5-P8; D-04, D-05).
//     INVARIANTS: the gate (port.expectGeneration) is set BEFORE the seek / live commands; all timers run only
//     while port.linkHealthy() (unhealthy = freeze position, suspend timers, never fail / end / jump; health back
//     re-bases the timers; the next liveEpoch re-seeks the frozen position). The start timeout accumulates only
//     while units of any generation arrive (port.framesIn growth, checker addendum 1); no units at all is left to
//     the supervisor's ARCHIVE stall + reconnect. A seek made while unhealthy (Starting(at, -1)) is sent when health
//     returns even without a new link (addendum 2). Boundary jump only near the segment end or from a gap; ENDED
//     only near the end of the last segment. Paused / Failed never resume by themselves (D-05). Public calls,
//     ticker and liveEpoch collector are serialised by one lock (thread-confined state).
// END_CHANGE_SUMMARY

// SILENT = repeated ARCHIVE-stall reconnects at one position without progress (W-2b).
enum class PauseReason { USER, ENDED, BACKGROUND, SILENT }
enum class FailReason { NOT_READY, NO_FRAMES, NO_RECORDING }

sealed interface PlaybackState {
    object Live : PlaybackState { override fun toString() = "Live" }
    data class Starting(val atMs: Long, val useCount: Int) : PlaybackState
    data class Playing(val positionMs: Long, val useCount: Int) : PlaybackState
    data class Paused(val positionMs: Long, val reason: PauseReason) : PlaybackState
    data class Failed(val positionMs: Long, val reason: FailReason) : PlaybackState
}

class PlaybackController(
    private val port: ArchiveLinkPort,
    private val scope: CoroutineScope,
    private val clock: () -> Long,          // monotonic ms
    private val wallClock: () -> Long,      // epoch ms (seek resolution only)
    private val wireZone: ZoneId = SdTime.wireZone(),
    private val timing: Timing = Timing(),
) {
    data class Timing(
        val tickMs: Long = 500,
        val startTimeoutMs: Long = 20_000,
        val boundaryMs: Long = 8_000,
        val endedMs: Long = 30_000,
        val endNearMs: Long = 10_000,
        val maxSilentReseeks: Int = 2,
    )

    private companion object {
        const val NO_UC = -1
        const val NEXT_AFTER_MS = 1_000L
        const val SAME_POSITION_MS = 2_000L
    }

    private val lock = Any()
    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Live)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var segments: List<Segment> = emptyList()
    private var jobs: List<Job> = emptyList()
    private var closed = false

    private var reconnecting = false
    private var timersFrozen = false
    internal val isReconnecting: Boolean get() = synchronized(lock) { reconnecting }

    private var seekEpoch = -1          // liveEpoch value when the last seek was sent
    private var lastEpoch = 0
    private var lastTickAt = 0L
    private var startWaitMs = 0L        // start-timeout time accumulated while units arrive
    private var lastUnits = 0L
    private var lastKeyAt = 0L
    private var seenKeys = 0L
    private var pendingLiveUc: Int? = null
    private var liveAt = 0L
    private var stallsAtSend = 0        // port.archiveStallCount() when the last seek was sent
    private var silentReseeks = 0       // consecutive ARCHIVE-stall reconnects at silentPos
    private var silentPos: Long? = null

    // START_CONTRACT: start
    //   PURPOSE: Launch the ticker (tickMs) and the liveEpoch collector in scope; the current epoch is not replayed.
    //   INPUTS: none
    //   OUTPUTS: none
    //   SIDE_EFFECTS: two coroutines in scope until close()
    //   LINKS: M-SD-ARCHIVE, ArchiveLinkPort.liveEpoch
    // END_CONTRACT: start
    fun start() {
        synchronized(lock) {
            if (closed || jobs.isNotEmpty()) return
            lastEpoch = port.liveEpoch.value
            lastTickAt = clock()
            val ticker = scope.launch {
                while (isActive) {
                    delay(timing.tickMs)
                    synchronized(lock) { if (!closed) tick() }
                }
            }
            val epochs = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                port.liveEpoch.collect { epoch -> synchronized(lock) { if (!closed) onEpoch(epoch) } }
            }
            jobs = listOf(ticker, epochs)
        }
    }

    // START_CONTRACT: setSegments
    //   PURPOSE: Timeline used for seek resolution, boundary jumps and end detection.
    //   INPUTS: { segments: List<Segment> - merged sorted segments }
    //   OUTPUTS: none
    //   SIDE_EFFECTS: none beyond the stored list
    //   LINKS: SdTimeline.fromEntries
    // END_CONTRACT: setSegments
    fun setSegments(segments: List<Segment>) {
        synchronized(lock) { this.segments = segments }
    }

    // START_CONTRACT: seek
    //   PURPOSE: Play the archive from a target instant (or go live / fail, research Pattern 5).
    //   INPUTS: { targetMs: Long - epoch ms chosen on the timeline }
    //   OUTPUTS: none (state)
    //   SIDE_EFFECTS: Live -> goLive; NoRecording -> Failed(NO_RECORDING); Archive(at) -> startAt(at)
    //   LINKS: SdTimeline.resolveSeek, PlaybackCommands.seek
    // END_CONTRACT: seek
    fun seek(targetMs: Long) {
        synchronized(lock) {
            if (closed) return
            resetSilent()
            // START_BLOCK_SEEK
            val target = SdTimeline.resolveSeek(segments, targetMs, wallClock())
            val result: String
            val snapped: Boolean
            when (target) {
                SeekTarget.Live -> { result = "Live"; snapped = false }
                SeekTarget.NoRecording -> { result = "NoRecording"; snapped = false }
                is SeekTarget.Archive -> { result = "Archive"; snapped = target.atMs != targetMs }
            }
            Timber.i("[Archive][playback][BLOCK_SEEK] result=%s snapped=%b healthy=%b", result, snapped, port.linkHealthy())
            when (target) {
                SeekTarget.Live -> goLiveLocked()
                SeekTarget.NoRecording -> setState(PlaybackState.Failed(targetMs, FailReason.NO_RECORDING), "no-recording")
                is SeekTarget.Archive -> startAt(target.atMs, "seek")
            }
            // END_BLOCK_SEEK
        }
    }

    // START_CONTRACT: pause
    //   PURPOSE: User pause: Starting / Playing -> Paused(position, USER).
    //   INPUTS: none
    //   OUTPUTS: none (state)
    //   SIDE_EFFECTS: setStreamMode(PAUSED) + 767 + stop record when the link is healthy
    //   LINKS: PlaybackCommands.pause
    // END_CONTRACT: pause
    fun pause() {
        synchronized(lock) {
            if (closed) return
            resetSilent()
            val pos = activePosition() ?: return
            if (port.linkHealthy()) sendPause()
            reconnecting = false
            setState(PlaybackState.Paused(pos, PauseReason.USER), "user")
        }
    }

    // START_CONTRACT: resume
    //   PURPOSE: Continue from the saved position with a fresh seek (new useCount).
    //   INPUTS: none
    //   OUTPUTS: none (state)
    //   SIDE_EFFECTS: Paused (any reason) / Failed(NO_FRAMES | NOT_READY) -> startAt(position)
    //   LINKS: PlaybackController.startAt
    // END_CONTRACT: resume
    fun resume() {
        synchronized(lock) {
            if (closed) return
            resetSilent()
            when (val s = _state.value) {
                is PlaybackState.Paused -> startAt(s.positionMs, "resume")
                is PlaybackState.Failed -> if (s.reason != FailReason.NO_RECORDING) startAt(s.positionMs, "resume")
                else -> Unit
            }
        }
    }

    // START_CONTRACT: goLive
    //   PURPOSE: Leave the archive («Живой») on the same link.
    //   INPUTS: none
    //   OUTPUTS: none (state Live)
    //   SIDE_EFFECTS: gate(new uc) -> setStreamMode(LIVE) -> stop record + 9029 / 8190|511; gate released on the
    //     first live key frame of that uc or after startTimeoutMs (fail-open). Unhealthy / no uc: gate cleared only.
    //   LINKS: PlaybackCommands.live
    // END_CONTRACT: goLive
    fun goLive() {
        synchronized(lock) {
            if (closed) return
            resetSilent()
            goLiveLocked()
        }
    }

    // START_CONTRACT: onSessionStopped
    //   PURPOSE: Background / screen left: Starting / Playing -> Paused(position, BACKGROUND); never resumes by itself.
    //   INPUTS: none
    //   OUTPUTS: none (state)
    //   SIDE_EFFECTS: none on the port (the link is being stopped)
    //   LINKS: D-05
    // END_CONTRACT: onSessionStopped
    fun onSessionStopped() {
        synchronized(lock) {
            if (closed) return
            val pos = activePosition() ?: return
            reconnecting = false
            setState(PlaybackState.Paused(pos, PauseReason.BACKGROUND), "background")
        }
    }

    // START_CONTRACT: close
    //   PURPOSE: Stop the ticker and the collector; idempotent.
    //   INPUTS: none
    //   OUTPUTS: none
    //   SIDE_EFFECTS: cancels own jobs; nothing sent
    //   LINKS: M-SD-ARCHIVE
    // END_CONTRACT: close
    fun close() {
        val toCancel = synchronized(lock) {
            if (closed) return
            closed = true
            jobs.also { jobs = emptyList() }
        }
        toCancel.forEach { it.cancel() }
    }

    // ---- internals (all called under lock) ----

    private fun activePosition(): Long? = when (val s = _state.value) {
        is PlaybackState.Starting -> s.atMs
        is PlaybackState.Playing -> s.positionMs
        else -> null
    }

    private fun startAt(at: Long, reason: String) {
        pendingLiveUc = null
        if (!port.linkHealthy()) {
            // Replayed by the next liveEpoch or by the first healthy tick (addendum 2).
            reconnecting = true
            timersFrozen = true
            setState(PlaybackState.Starting(at, NO_UC), "$reason-unhealthy")
            return
        }
        val uc = port.nextUseCount()
        if (uc == null) {
            reconnecting = false
            setState(PlaybackState.Failed(at, FailReason.NOT_READY), "$reason-not-ready")
            return
        }
        port.expectGeneration(uc)
        port.setStreamMode(StreamMode.ARCHIVE)
        for (cmd in PlaybackCommands.seek(port.transport, port.encrypted, uc, port.liveResolution(), SdTime.toWire(at, wireZone))) {
            port.sendIoCtrl(cmd.type, cmd.payload)
        }
        stallsAtSend = port.archiveStallCount()
        seekEpoch = port.liveEpoch.value
        reconnecting = false
        timersFrozen = false
        rebase(clock())
        seenKeys = 0
        setState(PlaybackState.Starting(at, uc), reason)
    }

    private fun goLiveLocked() {
        if (_state.value == PlaybackState.Live) return
        reconnecting = false
        timersFrozen = false
        val uc = if (port.linkHealthy()) port.nextUseCount() else null
        if (uc == null) {
            port.expectGeneration(null)
            port.setStreamMode(StreamMode.LIVE)
            pendingLiveUc = null
            setState(PlaybackState.Live, "live-no-send")
            return
        }
        port.expectGeneration(uc)
        port.setStreamMode(StreamMode.LIVE)
        for (cmd in PlaybackCommands.live(port.transport, port.encrypted, uc, port.liveResolution())) {
            port.sendIoCtrl(cmd.type, cmd.payload)
        }
        pendingLiveUc = uc
        liveAt = clock()
        setState(PlaybackState.Live, "live")
    }

    private fun sendPause() {
        port.setStreamMode(StreamMode.PAUSED)
        for (cmd in PlaybackCommands.pause(port.transport, port.encrypted)) port.sendIoCtrl(cmd.type, cmd.payload)
    }

    private fun rebase(now: Long) {
        lastTickAt = now
        lastKeyAt = now
        startWaitMs = 0
        lastUnits = port.framesIn()
    }

    private fun onEpoch(epoch: Int) {
        if (epoch == lastEpoch) return
        lastEpoch = epoch
        when (val s = _state.value) {
            is PlaybackState.Starting -> {
                if (s.useCount != NO_UC && seekEpoch == epoch && !reconnecting) return
                if (s.useCount == NO_UC) startAt(s.atMs, "reconnect") else reseekOrGiveUp(s.atMs)
            }
            is PlaybackState.Playing -> reseekOrGiveUp(s.positionMs)
            is PlaybackState.Paused, is PlaybackState.Failed -> {
                // New link starts in LIVE (supervisor): show live video, keep the saved position (D-05).
                port.expectGeneration(null)
            }
            PlaybackState.Live -> {
                pendingLiveUc = null
                port.expectGeneration(null)
            }
        }
    }

    private fun resetSilent() {
        silentReseeks = 0
        silentPos = null
    }

    // Reconnect re-seek of [pos], unless the ARCHIVE stall closed the previous link at the same position for the
    // maxSilentReseeks-th time in a row: then Paused(SILENT) over the live video of the new link (W-2b).
    private fun reseekOrGiveUp(pos: Long) {
        // START_BLOCK_SILENT_RESEEK
        val stalled = port.archiveStallCount() > stallsAtSend
        val same = silentPos?.let { kotlin.math.abs(it - pos) <= SAME_POSITION_MS } ?: true
        if (stalled && same) {
            silentReseeks += 1
            silentPos = pos
        } else if (stalled) {
            silentReseeks = 1
            silentPos = pos
        } else {
            resetSilent()
        }
        Timber.i("[Archive][playback][BLOCK_SILENT_RESEEK] stalled=%b count=%d", stalled, silentReseeks)
        if (silentReseeks >= timing.maxSilentReseeks) {
            reconnecting = false
            timersFrozen = false
            pendingLiveUc = null
            port.expectGeneration(null)          // the new link streams LIVE (supervisor reset); show it
            setState(PlaybackState.Paused(pos, PauseReason.SILENT), "silent-reseek-cap")
            return
        }
        // END_BLOCK_SILENT_RESEEK
        startAt(pos, "reconnect")
    }

    private fun matches(a: Int, b: Int) = (a and 0xFF) == (b and 0xFF)

    private fun tick() {
        val now = clock()
        val dt = now - lastTickAt
        lastTickAt = now
        val s = _state.value
        val active = s is PlaybackState.Starting || s is PlaybackState.Playing
        if (active && !port.linkHealthy()) {
            if (!reconnecting || !timersFrozen) {
                Timber.i("[Archive][playback][BLOCK_PLAYBACK_STATE] from=%s to=%s uc=%s reason=%s", name(s), name(s), ucOf(s), "link-unhealthy")
            }
            reconnecting = true
            timersFrozen = true
            return
        }
        if (active && timersFrozen) {
            timersFrozen = false
            if (s is PlaybackState.Starting && s.useCount == NO_UC) {
                startAt(s.atMs, "health-back")
                return
            }
            rebase(now)
            return
        }
        when (s) {
            is PlaybackState.Starting -> tickStarting(s, now, dt)
            is PlaybackState.Playing -> tickPlaying(s, now)
            PlaybackState.Live -> tickLive(now)
            else -> Unit
        }
    }

    private fun tickStarting(s: PlaybackState.Starting, now: Long, dt: Long) {
        if (s.useCount == NO_UC) return
        val f = port.lastFrame()
        if (f != null && f.recorded && f.keyFrames >= 1 && matches(f.useCount, s.useCount)) {
            reconnecting = false
            seenKeys = f.keyFrames
            lastKeyAt = now
            setState(PlaybackState.Playing(f.timestampSec * 1000, s.useCount), "first-key")
            return
        }
        val units = port.framesIn()
        if (units != lastUnits) {
            startWaitMs += dt
            lastUnits = units
        }
        if (startWaitMs >= timing.startTimeoutMs) {
            sendPause()
            setState(PlaybackState.Failed(s.atMs, FailReason.NO_FRAMES), "start-timeout")
        }
    }

    private fun tickPlaying(s: PlaybackState.Playing, now: Long) {
        var pos = s.positionMs
        val f = port.lastFrame()
        if (f != null && f.recorded && matches(f.useCount, s.useCount)) {
            pos = f.timestampSec * 1000
            if (f.keyFrames > seenKeys) {
                seenKeys = f.keyFrames
                lastKeyAt = now
                reconnecting = false
            }
        }
        if (pos != s.positionMs) _state.value = PlaybackState.Playing(pos, s.useCount)
        val idle = now - lastKeyAt
        val seg = segments.firstOrNull { pos >= it.startMs && pos < it.endMs }
        val next = SdTimeline.nextSegmentStart(segments, pos + NEXT_AFTER_MS)
        val last = segments.lastOrNull()
        val pastEnd = seg == null && next == null && last != null && pos >= last.endMs
        val nearEnd = (seg != null && seg.endMs - pos <= timing.endNearMs) || pastEnd
        if (idle >= timing.boundaryMs && next != null && (seg == null || nearEnd)) {
            startAt(next, if (seg == null) "boundary-gap" else "boundary")
            return
        }
        if (next == null && nearEnd && idle >= timing.endedMs) {
            sendPause()
            setState(PlaybackState.Paused(pos, PauseReason.ENDED), if (pastEnd) "ended-past-index" else "ended")
        }
        // ENDED covers the end of the last segment and any position at / after the last known end (stale index,
        // W-2a). Any other silence (mid-segment) waits: the supervisor's ARCHIVE stall closes the link, the link
        // turns unhealthy (freeze) and the next liveEpoch re-seeks.
    }

    private fun tickLive(now: Long) {
        val uc = pendingLiveUc ?: return
        val f = port.lastFrame()
        val gotLive = f != null && !f.recorded && f.keyFrames >= 1 && matches(f.useCount, uc)
        val timedOut = now - liveAt >= timing.startTimeoutMs
        if (gotLive || timedOut) {
            port.expectGeneration(null)
            pendingLiveUc = null
            Timber.i(
                "[Archive][playback][BLOCK_PLAYBACK_STATE] from=Live to=Live uc=%d reason=%s",
                uc, if (gotLive) "gate-released" else "gate-fail-open",
            )
        }
    }

    private fun name(s: PlaybackState): String = when (s) {
        PlaybackState.Live -> "Live"
        is PlaybackState.Starting -> "Starting"
        is PlaybackState.Playing -> "Playing"
        is PlaybackState.Paused -> "Paused(${s.reason})"
        is PlaybackState.Failed -> "Failed(${s.reason})"
    }

    private fun ucOf(s: PlaybackState): String = when (s) {
        is PlaybackState.Starting -> s.useCount.toString()
        is PlaybackState.Playing -> s.useCount.toString()
        else -> "-"
    }

    private fun setState(next: PlaybackState, reason: String) {
        val prev = _state.value
        _state.value = next
        // START_BLOCK_PLAYBACK_STATE
        Timber.i(
            "[Archive][playback][BLOCK_PLAYBACK_STATE] from=%s to=%s uc=%s reason=%s",
            name(prev), name(next), ucOf(next), reason,
        )
        // END_BLOCK_PLAYBACK_STATE
    }
}
