// FILE: FakeArchivePort.kt
// VERSION: 0.3.0
// START_MODULE_CONTRACT
//   PURPOSE: Scriptable in-memory ArchiveLinkPort for archive JVM tests.
//   SCOPE: records sent commands, scripted replies via onSend, refusable send, generation / stream mode capture.
//   DEPENDS: M-SD-ARCHIVE (ArchiveLinkPort, PlaybackCmd), M-STREAM-CORE (LinkIoCtrlReply, StreamMode, FrameObservation)
//   LINKS: V-M-SD-ARCHIVE
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.archive

import com.yicamalt.camera.TransportType
import com.yicamalt.stream.FrameObservation
import com.yicamalt.stream.LinkIoCtrlReply
import com.yicamalt.stream.StreamMode
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

// START_MODULE_MAP
//   FakeArchivePort - ArchiveLinkPort fake: sent, sendResult, onSend hook, reply(type, payload), useCounter,
//     healthy override, expected generation, frame, modes, live, resolution, useCountAvailable, unitsFlowing /
//     units (framesIn), events (ordered gate / mode / send trace), archiveStalls (archiveStallCount).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.3.0 - GSD plan 04-10 (W-2b): archiveStalls drives archiveStallCount() (default 0, so old
//     tests never count a silent re-seek).
//   PREVIOUS: v0.2.0 - GSD plan 04-05: framesIn (units flowing switch), not-ready use counter, ordered events.
//   PREVIOUS: v0.1.0 - GSD plan 04-03: fake port for SdIndexRepository and later playback tests.
// END_CHANGE_SUMMARY

class FakeArchivePort(
    override var transport: TransportType = TransportType.TNP,
    override var encrypted: Boolean = true,
    override var bigEndian: Boolean = true,
) : ArchiveLinkPort {
    override val liveEpoch = MutableStateFlow(1)
    val replies = MutableSharedFlow<LinkIoCtrlReply>(extraBufferCapacity = 64)
    override val ioCtrlReplies get() = replies

    val sent = mutableListOf<PlaybackCmd>()
    var sendResult = true
    var onSend: (type: Int, payload: ByteArray) -> Unit = { _, _ -> }
    var useCounter = 1
    var healthy: Boolean? = null
    var expected: Int? = null
    var frame: FrameObservation? = null
    val modes = mutableListOf<StreamMode>()
    var live = true
    var resolution = 1
    // false: nextUseCount() returns null (session not ready).
    var useCountAvailable = true
    // true: every framesIn() call sees one more unit (camera streaming something); false: counter frozen.
    var unitsFlowing = true
    var units = 0L
    // Ordered trace of port calls: "gate:<uc|null>", "mode:<MODE>", "send:<type>".
    val events = mutableListOf<String>()
    // Links closed by the supervisor's ARCHIVE stall so far (tests bump it to simulate a stall close).
    var archiveStalls = 0

    // Emits a reply into the shared flow (subscribers must already be collecting).
    fun reply(type: Int, payload: ByteArray) {
        check(replies.tryEmit(LinkIoCtrlReply(type, payload))) { "reply buffer full" }
    }

    override fun isLive(): Boolean = live
    override fun linkHealthy(): Boolean = healthy ?: live

    override fun sendIoCtrl(type: Int, payload: ByteArray): Boolean {
        sent += PlaybackCmd(type, payload)
        events += "send:$type"
        if (!sendResult) return false
        onSend(type, payload)
        return true
    }

    override fun nextUseCount(): Int? = if (useCountAvailable) useCounter++ else null
    override fun liveResolution(): Int = resolution
    override fun expectGeneration(useCount: Int?) {
        expected = useCount
        events += "gate:$useCount"
    }
    override fun lastFrame(): FrameObservation? = frame
    override fun archiveStallCount(): Int = archiveStalls
    override fun framesIn(): Long {
        if (unitsFlowing) units++
        return units
    }
    override fun setStreamMode(mode: StreamMode) {
        modes += mode
        events += "mode:$mode"
    }
}
