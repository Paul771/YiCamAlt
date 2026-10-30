// FILE: FakeArchivePort.kt
// VERSION: 0.1.0
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
//     healthy override, expected generation, frame, modes, live, resolution.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 04-03: fake port for SdIndexRepository and later playback tests.
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

    // Emits a reply into the shared flow (subscribers must already be collecting).
    fun reply(type: Int, payload: ByteArray) {
        check(replies.tryEmit(LinkIoCtrlReply(type, payload))) { "reply buffer full" }
    }

    override fun isLive(): Boolean = live
    override fun linkHealthy(): Boolean = healthy ?: live

    override fun sendIoCtrl(type: Int, payload: ByteArray): Boolean {
        sent += PlaybackCmd(type, payload)
        if (!sendResult) return false
        onSend(type, payload)
        return true
    }

    override fun nextUseCount(): Int? = useCounter++
    override fun liveResolution(): Int = resolution
    override fun expectGeneration(useCount: Int?) { expected = useCount }
    override fun lastFrame(): FrameObservation? = frame
    override fun setStreamMode(mode: StreamMode) { modes += mode }
}
