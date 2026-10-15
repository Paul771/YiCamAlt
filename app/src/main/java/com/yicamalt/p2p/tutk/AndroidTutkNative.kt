// FILE: AndroidTutkNative.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: TutkNative over the JNI shim com.tutk.IOTC.IOTCAPIs / AVAPIs (device implementation of the seam).
//   SCOPE: 1:1 mapping of the seam calls in the vendor client order; check() reduces St_SInfoEx to a value-free
//     TutkCheck; stop() runs the vendor teardown order.
//   DEPENDS: M-TUTK-LINK (IOTCAPIs / AVAPIs shim, St_SInfoEx, TutkNative), M-P2P-NATIVE (private-address check)
//   LINKS: M-TUTK-LINK, V-M-TUTK-LINK, M-P2P-NATIVE
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.p2p.tutk

import com.tutk.IOTC.AVAPIs
import com.tutk.IOTC.IOTCAPIs
import com.tutk.IOTC.St_SInfoEx
import com.yicamalt.p2p.AndroidPpppNative
import javax.inject.Inject
import javax.inject.Singleton

// START_MODULE_MAP
//   AndroidTutkNative - TutkNative backed by IOTCAPIs / AVAPIs static natives; STOP_TYPE, CLIENT_CHANNEL constants.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 02-10: created. UID and address bytes of St_SInfoEx never leave check(): they
//     are reduced to a boolean and zero-filled. Never logs; never de-initialises IOTC / AV.
// END_CHANGE_SUMMARY

@Singleton
class AndroidTutkNative @Inject constructor() : TutkNative {

    override fun initialize(): Int = IOTCAPIs.IOTC_Initialize2(UDP_PORT)

    override fun avInitialize(maxChannels: Int): Int = AVAPIs.avInitialize(maxChannels)

    override fun iotcVersion(): Int {
        val out = IntArray(1)
        IOTCAPIs.IOTC_Get_Version(out)
        return out[0]
    }

    override fun avVersion(): Int = AVAPIs.avGetAVApiVer()

    override fun sessionId(): Int = IOTCAPIs.IOTC_Get_SessionID()

    override fun connectByUid(uid: String, sid0: Int): Int = IOTCAPIs.IOTC_Connect_ByUID_Parallel(uid, sid0)

    override fun connectStop(sid0: Int): Int = IOTCAPIs.IOTC_Connect_Stop_BySID(sid0)

    // START_BLOCK_CHECK_SESSION
    override fun check(sid: Int): TutkCheck {
        val s = St_SInfoEx()
        try {
            val code = IOTCAPIs.IOTC_Session_Check_Ex(sid, s)
            return TutkCheck(
                code = code,
                mode = s.Mode.toInt() and 0xFF,
                remoteIsPrivate = AndroidPpppNative.isPrivateIpv4(s.RemoteIP),
            )
        } finally {
            s.UID.fill(0)
            s.RemoteIP.fill(0)
            s.RemoteWANIP.fill(0)
        }
    }
    // END_BLOCK_CHECK_SESSION

    override fun clientStart(sid: Int, user: String, pass: String, timeoutS: Int): ClientStart {
        val servType = IntArray(1)
        val resend = IntArray(1)
        val av = AVAPIs.avClientStart2(sid, user, pass, timeoutS, servType, CLIENT_CHANNEL, resend)
        return ClientStart(av, servType[0])
    }

    override fun sendIoCtrl(av: Int, type: Int, payload: ByteArray): Int =
        AVAPIs.avSendIOCtrl(av, type, payload, payload.size)

    override fun recvFrame(av: Int, buf: ByteArray, frameInfo: ByteArray): RecvResult {
        val actualFrameSize = IntArray(1)
        val expectedFrameSize = IntArray(1)
        val actualInfoSize = IntArray(1)
        val frameIndex = IntArray(1)
        val code = AVAPIs.avRecvFrameData2(
            av, buf, buf.size, actualFrameSize, expectedFrameSize,
            frameInfo, frameInfo.size, actualInfoSize, frameIndex,
        )
        return RecvResult(code, if (code > 0) code else actualFrameSize[0])
    }

    // START_BLOCK_STOP_SESSION
    override fun stop(av: Int, sid: Int) {
        if (av >= 0) {
            AVAPIs.avSendIOCtrl(av, STOP_TYPE, ByteArray(STOP_PAYLOAD_SIZE), STOP_PAYLOAD_SIZE)
            AVAPIs.avSendIOCtrlExit(av)
            AVAPIs.avClientStop(av)
        }
        if (sid >= 0) {
            AVAPIs.avClientExit(sid, CLIENT_CHANNEL)
            IOTCAPIs.IOTC_Session_Close(sid)
        }
    }
    // END_BLOCK_STOP_SESSION

    companion object {
        private const val UDP_PORT = 0
        const val CLIENT_CHANNEL = 0
        const val STOP_TYPE = 767
        const val STOP_PAYLOAD_SIZE = 8
    }
}
