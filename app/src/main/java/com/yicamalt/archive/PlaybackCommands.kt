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
import com.yicamalt.stream.IoCtrlMessages

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

    // START_CONTRACT: seek
    //   PURPOSE: Commands that start archive playback at a target (also used for every later seek).
    //   INPUTS: { transport: TransportType - TNP or TUTK; encrypted: Boolean - p2pEncrypt; useCount: Int - new
    //             generation; resolution: Int - live resolution code (TNP only); at: STimeDay - wire target }
    //   OUTPUTS: { List<PlaybackCmd> - TNP [9030]; otherwise [12718 / 794 cmd 16 LE] }
    //   SIDE_EFFECTS: none
    //   LINKS: M-SD-ARCHIVE, SdArchiveCodec.tnpPlayRecord, SdArchiveCodec.playControl
    // END_CONTRACT: seek
    fun seek(transport: TransportType, encrypted: Boolean, useCount: Int, resolution: Int, at: STimeDay): List<PlaybackCmd> =
        if (transport == TransportType.TNP) {
            listOf(PlaybackCmd(SdArchiveCodec.TNP_START_RECORD, SdArchiveCodec.tnpPlayRecord(useCount, resolution, at)))
        } else {
            listOf(
                PlaybackCmd(
                    SdArchiveCodec.playControlType(encrypted),
                    SdArchiveCodec.playControl(SdArchiveCodec.PLAY_START, at, useCount, bigEndian = false),
                ),
            )
        }

    // START_CONTRACT: pause
    //   PURPOSE: Commands that pause archive playback (camera stops sending; resume is a fresh seek).
    //   INPUTS: { transport: TransportType; encrypted: Boolean - p2pEncrypt }
    //   OUTPUTS: { List<PlaybackCmd> - [767 stop, stop record] }
    //   SIDE_EFFECTS: none
    //   LINKS: M-SD-ARCHIVE, IoCtrlMessages.stop
    // END_CONTRACT: pause
    fun pause(transport: TransportType, encrypted: Boolean): List<PlaybackCmd> = listOf(
        PlaybackCmd(IoCtrlMessages.STOP, IoCtrlMessages.stop()),
        stopRecord(encrypted, bigEndian(transport)),
    )

    // START_CONTRACT: live
    //   PURPOSE: Commands that leave the archive and restart the live stream.
    //   INPUTS: { transport: TransportType; encrypted: Boolean - p2pEncrypt; useCount: Int - new generation;
    //             resolution: Int - live resolution code (TNP 9029) }
    //   OUTPUTS: { List<PlaybackCmd> - [stop record, 9029] on TNP; [stop record, 8190 / 511] otherwise }
    //   SIDE_EFFECTS: none
    //   LINKS: M-SD-ARCHIVE, IoCtrlMessages.startRealtime, IoCtrlMessages.tutkStart
    // END_CONTRACT: live
    fun live(transport: TransportType, encrypted: Boolean, useCount: Int, resolution: Int): List<PlaybackCmd> {
        val be = bigEndian(transport)
        val start = if (be) {
            PlaybackCmd(IoCtrlMessages.START_REALTIME, IoCtrlMessages.startRealtime(useCount, resolution))
        } else {
            val type = if (encrypted) IoCtrlMessages.TUTK_START_ENC else IoCtrlMessages.TUTK_START_PLAIN
            PlaybackCmd(type, IoCtrlMessages.tutkStart(useCount))
        }
        return listOf(stopRecord(encrypted, be), start)
    }

    private fun bigEndian(transport: TransportType): Boolean = transport == TransportType.TNP

    private fun stopRecord(encrypted: Boolean, bigEndian: Boolean) = PlaybackCmd(
        SdArchiveCodec.playControlType(encrypted),
        SdArchiveCodec.playControl(SdArchiveCodec.PLAY_STOP, null, 0, bigEndian),
    )
}
