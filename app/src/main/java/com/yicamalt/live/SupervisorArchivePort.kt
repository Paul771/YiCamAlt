// FILE: SupervisorArchivePort.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: ArchiveLinkPort over the live session supervisor: the archive talks to whatever link is Live now.
//   SCOPE: delegation only (transport order, liveEpoch, replies, send, useCount, gate, frame observation, units,
//     stream mode, link health). No state of its own, no logging.
//   DEPENDS: M-LIVE-SESSION (LiveSessionSupervisor), M-SD-ARCHIVE (ArchiveLinkPort), M-STREAM-CORE, camera (TransportType)
//   LINKS: M-LIVE-SESSION, V-M-LIVE-SESSION, M-SD-ARCHIVE
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.live

import com.yicamalt.archive.ArchiveLinkPort
import com.yicamalt.camera.TransportType
import com.yicamalt.stream.FrameObservation
import com.yicamalt.stream.LinkIoCtrlReply
import com.yicamalt.stream.StreamMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

// START_MODULE_MAP
//   SupervisorArchivePort - ArchiveLinkPort delegating to LiveSessionSupervisor (current live link + media session).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 04-06: delegation to the supervisor's live link and media session. Without a live
//     link: send false, useCount null, health false; without a session: no gate, no frame, 0 units.
// END_CHANGE_SUMMARY

class SupervisorArchivePort internal constructor(
    private val supervisor: LiveSessionSupervisor,
    override val transport: TransportType,
    override val encrypted: Boolean,
) : ArchiveLinkPort {
    override val bigEndian: Boolean get() = transport == TransportType.TNP
    override val liveEpoch: StateFlow<Int> get() = supervisor.liveEpoch
    override val ioCtrlReplies: Flow<LinkIoCtrlReply> get() = supervisor.ioCtrlReplies
    override fun isLive(): Boolean = supervisor.liveLinkOrNull() != null
    override fun linkHealthy(): Boolean = supervisor.linkHealthy()
    override fun sendIoCtrl(type: Int, payload: ByteArray): Boolean =
        supervisor.liveLinkOrNull()?.sendIoCtrl(type, payload) ?: false
    override fun nextUseCount(): Int? = supervisor.liveLinkOrNull()?.nextUseCount()
    override fun liveResolution(): Int = supervisor.liveResolution()
    override fun expectGeneration(useCount: Int?) {
        supervisor.sessionOrNull()?.expectGeneration(useCount)
    }
    override fun lastFrame(): FrameObservation? = supervisor.sessionOrNull()?.lastFrame()
    override fun framesIn(): Long = supervisor.sessionOrNull()?.framesIn() ?: 0L
    override fun setStreamMode(mode: StreamMode) = supervisor.setStreamMode(mode)
    override fun archiveStallCount(): Int = supervisor.archiveStallCount()
}
