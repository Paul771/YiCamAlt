// FILE: LiveSessionTypes.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Contract-first port types of the Phase 3 live session (supervisor state, quality, network, media session).
//   SCOPE: NetState / NetworkMonitor, Quality / QualityState, ErrorClass, KeyFrameObservation, MediaSession /
//     MediaSessionFactory and the SupervisorState machine values. No behaviour beyond pure value mapping.
//   DEPENDS: M-STREAM-CORE (LinkInfo, LinkError, LinkMode, IoCtrlMessages, VideoUnitSink, AudioUnitSink),
//     M-P2P-CREDS (P2pCredentials)
//   LINKS: M-LIVE-SESSION, V-M-LIVE-SESSION
//   ROLE: TYPES
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.live

import com.yicamalt.camera.P2pCredentials
import com.yicamalt.stream.AudioUnitSink
import com.yicamalt.stream.IoCtrlMessages
import com.yicamalt.stream.LinkError
import com.yicamalt.stream.LinkInfo
import com.yicamalt.stream.LinkMode
import com.yicamalt.stream.VideoUnitSink
import kotlinx.coroutines.flow.StateFlow

// START_MODULE_MAP
//   NetState - available flag + generation (bumps on every default-network change).
//   NetworkMonitor - port: state flow of NetState (Android impl in a later plan).
//   Quality - HD | SD with the 4881 resolution value; forMode(LinkMode), ofWidth(width), HD_MIN_WIDTH 1280.
//   QualityState - observed / pending / manual quality + switchSupported (D-06, D-07).
//   ErrorClass - NETWORK | WRONG_PASSWORD | KICK | FATAL: supervisor classification of a link failure.
//   KeyFrameObservation - useCount + size of the last key frame seen by the media session.
//   MediaSession - one link attempt's media plumbing: videoSink, audioSink, framesIn, lastKeyFrame, release.
//   MediaSessionFactory - create(credentials) -> MediaSession.
//   SupervisorState - Idle | Connecting | Live | Waiting | NoNetwork | Exhausted | AuthFailed | Kicked | Fatal | Stopped.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 03-00: contract-first port types for Phase 3 (interface-first for plans 03-01..03-13).
//     Doc comments use // only (no block comments). INVARIANT: every toString() is value-free (no ids, no secrets).
// END_CHANGE_SUMMARY

data class NetState(val available: Boolean, val generation: Int)

interface NetworkMonitor {
    val state: StateFlow<NetState>
}

enum class Quality(val resolution: Int) {
    HD(IoCtrlMessages.RESOLUTION_HD),
    SD(IoCtrlMessages.RESOLUTION_SD);

    companion object {
        const val HD_MIN_WIDTH = 1280

        fun forMode(mode: LinkMode): Quality =
            if (IoCtrlMessages.ResolutionPolicy.forMode(mode) == IoCtrlMessages.RESOLUTION_HD) HD else SD

        fun ofWidth(width: Int): Quality = if (width >= HD_MIN_WIDTH) HD else SD
    }
}

// observed: from key-frame width (null before the first key frame); pending: a sent request not yet confirmed;
// manual: user choice for this screen (never persisted, D-06); switchSupported false hides HD/SD (D-07).
data class QualityState(
    val observed: Quality? = null,
    val pending: Quality? = null,
    val manual: Quality? = null,
    val switchSupported: Boolean = true,
)

enum class ErrorClass { NETWORK, WRONG_PASSWORD, KICK, FATAL }

data class KeyFrameObservation(val useCount: Int, val width: Int, val height: Int)

// One link's media plumbing (pipeline + audio pipeline + key-frame tee). Created per link attempt, released after the link ends.
interface MediaSession {
    val videoSink: VideoUnitSink
    val audioSink: AudioUnitSink
    fun framesIn(): Long
    fun lastKeyFrame(): KeyFrameObservation?
    fun release()
}

fun interface MediaSessionFactory {
    fun create(credentials: P2pCredentials): MediaSession
}

sealed interface SupervisorState {
    // Never started on this screen.
    object Idle : SupervisorState {
        override fun toString() = "Idle"
    }

    // attempt 1..5
    data class Connecting(val attempt: Int) : SupervisorState

    data class Live(val info: LinkInfo, val authOk: Boolean) : SupervisorState

    data class Waiting(val nextAttempt: Int, val delayMs: Long, val cause: ErrorClass, val lastError: LinkError?) :
        SupervisorState

    // Badge "Офлайн", attempts not consumed.
    object NoNetwork : SupervisorState {
        override fun toString() = "NoNetwork"
    }

    // "Нет связи" + "Повторить" (D-02).
    data class Exhausted(val lastError: LinkError?) : SupervisorState

    // "Ошибка пароля", manual only.
    object AuthFailed : SupervisorState {
        override fun toString() = "AuthFailed"
    }

    // Second kick, manual only (D-03).
    object Kicked : SupervisorState {
        override fun toString() = "Kicked"
    }

    // Retry cannot help.
    data class Fatal(val error: LinkError) : SupervisorState

    // reason: user | screen-left | background
    data class Stopped(val reason: String) : SupervisorState
}
