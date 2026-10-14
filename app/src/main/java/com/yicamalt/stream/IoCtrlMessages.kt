// FILE: IoCtrlMessages.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: IOCTRL command ids, payload builders and reply interpretation for the live-view session.
//   SCOPE: 4881/9029/768/767 payloads (BE, TNP), 8190/511 payload (LE, TUTK), 4882 authResult, 9033 kick reason,
//          resolution policy per link mode.
//   DEPENDS: CameraLink (LinkMode)
//   LINKS: M-STREAM-CORE, V-M-STREAM-CORE
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.stream

import java.nio.ByteBuffer
import java.nio.ByteOrder

// START_MODULE_MAP
//   IoCtrlMessages - object: command constants, setResolution, startRealtime, audioStart, stop, tutkStart,
//     authOutcome(authResult), kickCode(payload), app codes.
//   IoCtrlMessages.AuthOutcome - OK | WRONG_PASSWORD(-20009) | RETRY(-9000) | Unknown(code).
//   IoCtrlMessages.ResolutionPolicy - forMode(LinkMode): 1 (HD) on P2P/LAN, 2 (SD) otherwise.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 02-04 from 02-RESEARCH.md Pattern 4 (VERIFIED-LOCAL layouts).
//     AuthOutcome is a sealed interface (not an enum) so the unknown branch keeps the raw authResult code.
// END_CHANGE_SUMMARY

object IoCtrlMessages {
    const val SET_RESOLUTION = 4881
    const val SET_RESOLUTION_RESP = 4882
    const val START_REALTIME = 9029
    const val AUDIO_START = 768
    const val STOP = 767
    const val KICK = 9033
    const val TUTK_START_ENC = 8190
    const val TUTK_START_PLAIN = 511

    const val CODE_WRONG_PASSWORD = -20009
    const val CODE_MANUAL_RETRY = -9000
    const val CODE_KICKED = -3100
    const val CODE_KICK_MAX_SESSION = -3101

    const val RESOLUTION_HD = 1
    const val RESOLUTION_SD = 2

    sealed interface AuthOutcome {
        val appCode: Int

        object OK : AuthOutcome {
            override val appCode = 0
            override fun toString() = "OK"
        }

        object WRONG_PASSWORD : AuthOutcome {
            override val appCode = CODE_WRONG_PASSWORD
            override fun toString() = "WRONG_PASSWORD"
        }

        object RETRY : AuthOutcome {
            override val appCode = CODE_MANUAL_RETRY
            override fun toString() = "RETRY"
        }

        data class Unknown(val code: Int) : AuthOutcome {
            override val appCode = CODE_MANUAL_RETRY
        }
    }

    // START_CONTRACT: setResolution
    //   PURPOSE: 4881 payload: u32 BE resolution + u32 BE useCount.
    //   INPUTS: { resolution: Int - 1 HD, 2 SD; useCount: Int }
    //   OUTPUTS: { ByteArray - 8 bytes }
    //   SIDE_EFFECTS: none
    //   LINKS: M-STREAM-CORE
    // END_CONTRACT: setResolution
    fun setResolution(resolution: Int, useCount: Int): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putInt(resolution).putInt(useCount).array()

    // START_CONTRACT: startRealtime
    //   PURPOSE: 9029 payload: [useCount, resolution, 1, 0].
    //   INPUTS: { useCount: Int, resolution: Int }
    //   OUTPUTS: { ByteArray - 4 bytes }
    //   SIDE_EFFECTS: none
    //   LINKS: M-STREAM-CORE
    // END_CONTRACT: startRealtime
    fun startRealtime(useCount: Int, resolution: Int): ByteArray =
        byteArrayOf(useCount.toByte(), resolution.toByte(), 1, 0)

    // START_CONTRACT: audioStart
    //   PURPOSE: 768 payload: 8 zero bytes.
    //   INPUTS: none
    //   OUTPUTS: { ByteArray - 8 bytes }
    //   SIDE_EFFECTS: none
    //   LINKS: M-STREAM-CORE
    // END_CONTRACT: audioStart
    fun audioStart(): ByteArray = ByteArray(8)

    // START_CONTRACT: stop
    //   PURPOSE: 767 payload: 8 zero bytes.
    //   INPUTS: none
    //   OUTPUTS: { ByteArray - 8 bytes }
    //   SIDE_EFFECTS: none
    //   LINKS: M-STREAM-CORE
    // END_CONTRACT: stop
    fun stop(): ByteArray = ByteArray(8)

    // START_CONTRACT: tutkStart
    //   PURPOSE: TUTK 8190 / 511 payload: u32 LE useCount.
    //   INPUTS: { useCount: Int }
    //   OUTPUTS: { ByteArray - 4 bytes }
    //   SIDE_EFFECTS: none
    //   LINKS: M-STREAM-CORE, M-TUTK-LINK
    // END_CONTRACT: tutkStart
    fun tutkStart(useCount: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(useCount).array()

    // START_CONTRACT: authOutcome
    //   PURPOSE: Interpret authResult (s32 at IOCtrl offset 8 of a reply).
    //   INPUTS: { authResult: Int }
    //   OUTPUTS: { AuthOutcome - 0 OK, 1|2 WRONG_PASSWORD, 4 RETRY, else Unknown(code) }
    //   SIDE_EFFECTS: none
    //   LINKS: M-STREAM-CORE, TnpCodec.parseIoCtrlReply
    // END_CONTRACT: authOutcome
    fun authOutcome(authResult: Int): AuthOutcome = when (authResult) {
        0 -> AuthOutcome.OK
        1, 2 -> AuthOutcome.WRONG_PASSWORD
        4 -> AuthOutcome.RETRY
        else -> AuthOutcome.Unknown(authResult)
    }

    // START_CONTRACT: kickCode
    //   PURPOSE: App error code for a 9033 kick reply (u32 BE reason in payload).
    //   INPUTS: { payload: ByteArray }
    //   OUTPUTS: { Int - -3101 for reason 1 (max sessions), else -3100 (also for a short payload) }
    //   SIDE_EFFECTS: none
    //   LINKS: M-STREAM-CORE
    // END_CONTRACT: kickCode
    fun kickCode(payload: ByteArray): Int {
        if (payload.size < 4) return CODE_KICKED
        val reason = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).getInt(0)
        return if (reason == 1) CODE_KICK_MAX_SESSION else CODE_KICKED
    }

    object ResolutionPolicy {
        // START_CONTRACT: forMode
        //   PURPOSE: Phase 2 resolution choice: HD on direct paths (P2P, LAN), SD on any relay or unknown path.
        //   INPUTS: { mode: LinkMode }
        //   OUTPUTS: { Int - RESOLUTION_HD (1) or RESOLUTION_SD (2) }
        //   SIDE_EFFECTS: none
        //   LINKS: M-STREAM-CORE
        // END_CONTRACT: forMode
        fun forMode(mode: LinkMode): Int = when (mode) {
            LinkMode.P2P, LinkMode.LAN -> RESOLUTION_HD
            LinkMode.RELAY, LinkMode.TCP_RELAY, LinkMode.SDEV_RELAY, LinkMode.UNKNOWN -> RESOLUTION_SD
        }
    }
}
