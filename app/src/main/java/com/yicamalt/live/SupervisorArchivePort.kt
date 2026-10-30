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
//   LAST_CHANGE: v0.1.0 - GSD plan 04-06 RED: signatures only.
// END_CHANGE_SUMMARY

class SupervisorArchivePort internal constructor(
    private val supervisor: LiveSessionSupervisor,
    override val transport: TransportType,
    override val encrypted: Boolean,
) : ArchiveLinkPort {
    override val bigEndian: Boolean get() = error("RED stub")
    override val liveEpoch: StateFlow<Int> get() = error("RED stub")
    override val ioCtrlReplies: Flow<LinkIoCtrlReply> get() = error("RED stub")
    override fun isLive(): Boolean = error("RED stub")
    override fun linkHealthy(): Boolean = error("RED stub")
    override fun sendIoCtrl(type: Int, payload: ByteArray): Boolean = error("RED stub")
    override fun nextUseCount(): Int? = error("RED stub")
    override fun liveResolution(): Int = error("RED stub")
    override fun expectGeneration(useCount: Int?): Unit = error("RED stub")
    override fun lastFrame(): FrameObservation? = error("RED stub")
    override fun framesIn(): Long = error("RED stub")
    override fun setStreamMode(mode: StreamMode): Unit = error("RED stub")
}
