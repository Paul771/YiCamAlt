// FILE: PlaybackCommands.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Exact IOCTRL command lists for archive seek, pause and return to live, per transport and p2pEncrypt.
//   SCOPE: TNP seek 9030; TUTK seek 12718 / 794 cmd 16 (LE); pause 767 + stop record; live stop record + 9029 (TNP)
//          or 8190 / 511 (TUTK). Byte order: TNP big-endian, other transports little-endian. Pure, no logging.
//   DEPENDS: SdArchiveCodec, M-STREAM-CORE (IoCtrlMessages), camera (TransportType)
//   LINKS: M-SD-ARCHIVE, V-M-SD-ARCHIVE
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.archive

import com.yicamalt.camera.TransportType

// START_MODULE_MAP
//   PlaybackCmd - one IOCTRL (type + payload); content equality; toString without bytes.
//   PlaybackCommands - object: seek, pause, live.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 04-03: research §P4-P6 command sequences.
// END_CHANGE_SUMMARY

class PlaybackCmd(val type: Int, val payload: ByteArray) {
    override fun toString() = "PlaybackCmd(type=$type, bytes=${payload.size})"
    override fun equals(other: Any?) = other is PlaybackCmd && other.type == type && other.payload.contentEquals(payload)
    override fun hashCode() = 31 * type + payload.contentHashCode()
}

object PlaybackCommands {
    fun seek(transport: TransportType, encrypted: Boolean, useCount: Int, resolution: Int, at: STimeDay): List<PlaybackCmd> =
        error("RED stub")

    fun pause(transport: TransportType, encrypted: Boolean): List<PlaybackCmd> = error("RED stub")

    fun live(transport: TransportType, encrypted: Boolean, useCount: Int, resolution: Int): List<PlaybackCmd> =
        error("RED stub")
}
