// FILE: PlaybackController.kt
// VERSION: 0.0.1
// START_MODULE_CONTRACT
//   PURPOSE: Archive playback state machine over ArchiveLinkPort (RED stub).
//   SCOPE: signatures only.
//   DEPENDS: SdTypes, SdTimeline, PlaybackCommands, SdTime
//   LINKS: M-SD-ARCHIVE, V-M-SD-ARCHIVE
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.archive

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.time.ZoneId

// START_MODULE_MAP
//   PauseReason, FailReason, PlaybackState, PlaybackController - RED stubs.
// END_MODULE_MAP

enum class PauseReason { USER, ENDED, BACKGROUND }
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
    private val clock: () -> Long,
    private val wallClock: () -> Long,
    private val wireZone: ZoneId = SdTime.wireZone(),
    private val timing: Timing = Timing(),
) {
    data class Timing(
        val tickMs: Long = 500,
        val startTimeoutMs: Long = 20_000,
        val boundaryMs: Long = 8_000,
        val endedMs: Long = 30_000,
        val endNearMs: Long = 10_000,
    )

    val state: StateFlow<PlaybackState> get() = error("RED stub")
    internal val isReconnecting: Boolean get() = error("RED stub")

    fun start(): Unit = error("RED stub")
    fun setSegments(segments: List<Segment>): Unit = error("RED stub")
    fun seek(targetMs: Long): Unit = error("RED stub")
    fun pause(): Unit = error("RED stub")
    fun resume(): Unit = error("RED stub")
    fun goLive(): Unit = error("RED stub")
    fun onSessionStopped(): Unit = error("RED stub")
    fun close(): Unit = error("RED stub")
}
