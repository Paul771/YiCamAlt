// FILE: SdTypes.kt
// VERSION: 0.3.0
// START_MODULE_CONTRACT
//   PURPOSE: Value types and the session port of the SD card archive.
//   SCOPE: Segment, SdStatus, IndexResult, SeekTarget, ArchiveLinkPort (narrow view of the live session),
//          ArchiveSupport (transports with archive), sdStatusOf (817 tfstat rule, research Pattern 6). Pure, no logging.
//   DEPENDS: SdArchiveCodec (DeviceInfo), M-STREAM-CORE (LinkIoCtrlReply, StreamMode, FrameObservation),
//            camera (TransportType)
//   LINKS: M-SD-ARCHIVE, V-M-SD-ARCHIVE
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.archive

import com.yicamalt.camera.TransportType
import com.yicamalt.stream.FrameObservation
import com.yicamalt.stream.LinkIoCtrlReply
import com.yicamalt.stream.StreamMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

// START_MODULE_MAP
//   Segment - merged recorded run [startMs, endMs) in epoch ms.
//   SdStatus - NoCard | Abnormal(tfstat) | Ok | Unknown (no 817 reply).
//   IndexResult - Ok(segments) | Timeout | Malformed | NotReady.
//   SeekTarget - Archive(atMs) | Live | NoRecording.
//   ArchiveLinkPort - what the archive needs from the current session (transport, order, replies, send, generation,
//     frame observation, units received, ARCHIVE stall count with default 0).
//   ArchiveSupport - transports that get the archive UI.
//   sdStatusOf - 817 DeviceInfo to SdStatus.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.3.0 - GSD plan 04-10: archiveStallCount() for the silent re-seek cap (W-2).
//   PREVIOUS: v0.2.0 - GSD plan 04-05 (checker addendum 1): ArchiveLinkPort.framesIn() so the playback start
//     timeout counts only while units of any generation keep arriving (no units = supervisor stall / reconnect).
//   PREVIOUS: v0.1.0 - GSD plan 04-03: archive types and port contract (research Patterns 3-6).
// END_CHANGE_SUMMARY

data class Segment(val startMs: Long, val endMs: Long)

sealed interface SdStatus {
    object NoCard : SdStatus { override fun toString() = "NoCard" }
    data class Abnormal(val tfstat: Int) : SdStatus
    object Ok : SdStatus { override fun toString() = "Ok" }
    object Unknown : SdStatus { override fun toString() = "Unknown" }
}

sealed interface IndexResult {
    // toString carries the count only, never times.
    data class Ok(val segments: List<Segment>) : IndexResult { override fun toString() = "Ok(count=${segments.size})" }
    object Timeout : IndexResult { override fun toString() = "Timeout" }
    object Malformed : IndexResult { override fun toString() = "Malformed" }
    object NotReady : IndexResult { override fun toString() = "NotReady" }
}

sealed interface SeekTarget {
    data class Archive(val atMs: Long) : SeekTarget
    object Live : SeekTarget { override fun toString() = "Live" }
    object NoRecording : SeekTarget { override fun toString() = "NoRecording" }
}

// Narrow view of the current session for the archive (implemented by the live supervisor, 04-06, and by the debug probe, 04-04).
interface ArchiveLinkPort {
    val transport: TransportType
    val encrypted: Boolean              // P2pCredentials.p2pEncrypt: 12718 vs 794
    val bigEndian: Boolean              // TNP true, TUTK false
    val liveEpoch: StateFlow<Int>       // +1 each time a new link reaches Streaming; 0 = none yet
    val ioCtrlReplies: Flow<LinkIoCtrlReply>
    fun isLive(): Boolean               // a Streaming link exists now
    fun linkHealthy(): Boolean          // isLive() && network available && supervisor state is Live (playback timers run only while true)
    fun sendIoCtrl(type: Int, payload: ByteArray): Boolean
    fun nextUseCount(): Int?
    fun liveResolution(): Int           // 1 HD / 2 SD for 9030 / 9029
    fun expectGeneration(useCount: Int?)
    fun lastFrame(): FrameObservation?
    fun framesIn(): Long                // video units of ANY generation received by the current session (MediaSession.framesIn)
    fun setStreamMode(mode: StreamMode)
    // Links closed by the supervisor's ARCHIVE-mode stall watchdog so far (monotonic); 0 = unknown / never.
    fun archiveStallCount(): Int = 0
}

object ArchiveSupport {
    // Plan 04-09 may remove TUTK here from device evidence (D-06).
    val transports: Set<TransportType> = setOf(TransportType.TNP, TransportType.TUTK)

    // START_CONTRACT: supports
    //   PURPOSE: Whether a transport gets the archive.
    //   INPUTS: { t: TransportType? - camera transport, null = not resolved }
    //   OUTPUTS: { Boolean - t in transports }
    //   SIDE_EFFECTS: none
    //   LINKS: M-SD-ARCHIVE
    // END_CONTRACT: supports
    fun supports(t: TransportType?): Boolean = t != null && t in transports
}

// START_CONTRACT: sdStatusOf
//   PURPOSE: SD card status from an 817 reply (research Pattern 6).
//   INPUTS: { info: DeviceInfo? - parsed 817, null = no reply / unparsable }
//   OUTPUTS: { SdStatus - null Unknown; tfstat 5 NoCard; 2/3/8/9 Abnormal(code); 0 with total 0 Abnormal(0); else Ok }
//   SIDE_EFFECTS: none
//   LINKS: M-SD-ARCHIVE, SdArchiveCodec.parseDeviceInfo
// END_CONTRACT: sdStatusOf
fun sdStatusOf(info: DeviceInfo?): SdStatus = when {
    info == null -> SdStatus.Unknown
    info.tfstat == 5 -> SdStatus.NoCard
    info.tfstat in ABNORMAL_TFSTAT -> SdStatus.Abnormal(info.tfstat)
    info.tfstat == 0 && info.totalMb == 0 -> SdStatus.Abnormal(0)
    else -> SdStatus.Ok
}

private val ABNORMAL_TFSTAT = setOf(2, 3, 8, 9)
