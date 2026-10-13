// FILE: AndroidPpppNative.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: PpppNative over the JNI shim com.p2p.pppp_api.PPPP_APIs (device implementation of the seam).
//   SCOPE: 1:1 mapping of the 9 seam calls; check() reduces st_PPPP_Session to a value-free PpppCheck.
//   DEPENDS: M-P2P-NATIVE (PPPP_APIs shim, st_PPPP_Session, PpppNative)
//   LINKS: M-P2P-NATIVE, V-M-P2P-NATIVE, M-TNP-LINK
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.p2p

import com.p2p.pppp_api.PPPP_APIs
import com.tnp.model.st_PPPP_Session
import javax.inject.Inject
import javax.inject.Singleton

// START_MODULE_MAP
//   AndroidPpppNative - PpppNative backed by PPPP_APIs static natives (udpPort always 0).
//   isPrivateIpv4(bytes) - NUL-terminated ASCII dotted quad -> RFC1918 / loopback / link-local check.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 02-03: created. Address and DID bytes of st_PPPP_Session never leave check():
//     they are reduced to a boolean and zero-filled. Never logs; never de-initialises the library.
// END_CHANGE_SUMMARY

@Singleton
class AndroidPpppNative @Inject constructor() : PpppNative {

    override fun initialize(initString: ByteArray, maxSessions: Int): Int =
        PPPP_APIs.PPPP_Initialize(initString, maxSessions)

    override fun configDebug(enable: Int, level: Int): Int =
        PPPP_APIs.PPPP_Config_Debug(enable.toByte(), level)

    override fun apiVersion(): Int = PPPP_APIs.PPPP_GetAPIVersion()

    override fun connect(did: String, flag: Byte, wakeup: Boolean, serverString: String, licenseKey: String): Int =
        if (wakeup) {
            PPPP_APIs.PPPP_WakeUp_And_Connect(did, flag, UDP_PORT, serverString, licenseKey)
        } else {
            PPPP_APIs.PPPP_Connect(did, flag, UDP_PORT, serverString, licenseKey)
        }

    override fun connectBreak(did: String): Int = PPPP_APIs.PPPP_Connect_Break(did)

    // START_BLOCK_CHECK_SESSION
    override fun check(handle: Int): PpppCheck {
        val s = st_PPPP_Session()
        try {
            val code = PPPP_APIs.PPPP_Check(handle, s)
            return PpppCheck(
                code = code,
                mode = s.bMode.toInt() and 0xFF,
                connectTimeMs = s.ConnectTime,
                remoteIsPrivate = isPrivateIpv4(s.RemoteIP),
            )
        } finally {
            s.RemoteIP.fill(0)
            s.MyLocalIP.fill(0)
            s.MyWanIP.fill(0)
            s.DID.fill(0)
        }
    }
    // END_BLOCK_CHECK_SESSION

    override fun read(handle: Int, channel: Int, buf: ByteArray, size: IntArray, timeoutMs: Int): Int =
        PPPP_APIs.PPPP_Read(handle, channel.toByte(), buf, size, timeoutMs)

    override fun write(handle: Int, channel: Int, buf: ByteArray, size: Int): Int =
        PPPP_APIs.PPPP_Write(handle, channel.toByte(), buf, size)

    override fun forceClose(handle: Int): Int = PPPP_APIs.PPPP_ForceClose(handle)

    companion object {
        private const val UDP_PORT = 0

        // START_CONTRACT: isPrivateIpv4
        //   PURPOSE: Decide whether a native-written address is private (LAN) without ever exposing it.
        //   INPUTS: { bytes: ByteArray - NUL-terminated ASCII dotted quad as written by PPPP_Check }
        //   OUTPUTS: { Boolean - true for 10/8, 172.16/12, 192.168/16, 127/8, 169.254/16; false otherwise or unparsable }
        //   SIDE_EFFECTS: none
        //   LINKS: M-P2P-NATIVE
        // END_CONTRACT: isPrivateIpv4
        internal fun isPrivateIpv4(bytes: ByteArray): Boolean {
            val end = bytes.indexOf(0.toByte()).let { if (it < 0) bytes.size else it }
            val parts = String(bytes, 0, end, Charsets.US_ASCII).split('.')
            if (parts.size != 4) return false
            val o = IntArray(4)
            for (i in 0 until 4) {
                val p = parts[i]
                if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return false
                o[i] = p.toInt()
                if (o[i] > 255) return false
            }
            return when {
                o[0] == 10 -> true
                o[0] == 172 && o[1] in 16..31 -> true
                o[0] == 192 && o[1] == 168 -> true
                o[0] == 127 -> true
                o[0] == 169 && o[1] == 254 -> true
                else -> false
            }
        }
    }
}
