// FILE: AndroidTutkNative.kt
// VERSION: 0.1.1
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

import timber.log.Timber
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
//   LAST_CHANGE: v0.1.1 - device finding: avSendIOCtrl(767) blocked 10-80 s with the screen off, holding the camera slot;
//     stop now sends it on a helper thread, aborts with avSendIOCtrlExit after 1.5 s, and logs per-step timing
//     (BLOCK_STOP_SESSION: step names and ms only). Close time on the phone: 80 s -> 1.6 s.
//   PREV_CHANGE: v0.1.0 - GSD plan 02-10: created. UID and address bytes of St_SInfoEx never leave check(): they
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
            // avSendIOCtrl blocks until the camera acknowledges; with the screen off / Wi-Fi asleep that took 10-80 s on
            // the test phone and held the camera slot. Send on a helper thread and abort it with avSendIOCtrlExit (its
            // documented purpose) after a short wait; the stop command is a courtesy, the session is closed below anyway.
            val sender = Thread({
                try {
                    timed("sendIOCtrlStop") { AVAPIs.avSendIOCtrl(av, STOP_TYPE, ByteArray(STOP_PAYLOAD_SIZE), STOP_PAYLOAD_SIZE) }
                } catch (t: Throwable) {
                    Timber.w("[P2P][tutk][BLOCK_STOP_SESSION] step=sendIOCtrlStop error=%s", t.javaClass.simpleName)
                }
            }, "tutk-stop-send")
            sender.isDaemon = true
            sender.start()
            sender.join(STOP_SEND_WAIT_MS)
            val aborted = sender.isAlive
            timed("sendIOCtrlExit") { AVAPIs.avSendIOCtrlExit(av) }
            if (aborted) {
                sender.join(STOP_SEND_JOIN_MS)
                Timber.d("[P2P][tutk][BLOCK_STOP_SESSION] step=sendIOCtrlStop aborted=true stillAlive=%b", sender.isAlive)
            }
            timed("clientStop") { AVAPIs.avClientStop(av) }
        }
        if (sid >= 0) {
            timed("clientExit") { AVAPIs.avClientExit(sid, CLIENT_CHANNEL) }
            timed("sessionClose") { IOTCAPIs.IOTC_Session_Close(sid) }
        }
    }

    // Value-free per-step timing of the vendor teardown (step names and milliseconds only).
    private inline fun timed(step: String, block: () -> Unit) {
        val t0 = System.nanoTime()
        block()
        Timber.d("[P2P][tutk][BLOCK_STOP_SESSION] step=%s ms=%d", step, (System.nanoTime() - t0) / 1_000_000L)
    }
    // END_BLOCK_STOP_SESSION

    companion object {
        private const val UDP_PORT = 0
        const val CLIENT_CHANNEL = 0
        const val STOP_TYPE = 767
        const val STOP_PAYLOAD_SIZE = 8
        private const val STOP_SEND_WAIT_MS = 1_500L
        private const val STOP_SEND_JOIN_MS = 2_000L
    }
}
