// FILE: PpppNative.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: The only seam between our code and the vendor CS2-PPPP library, plus its error-code table.
//   SCOPE: PpppNative interface (9 calls), value-free PpppCheck session projection, PpppCodes (names + classify).
//   DEPENDS: M-STREAM-CORE (LinkErrorKind)
//   LINKS: M-P2P-NATIVE, V-M-P2P-NATIVE, M-TNP-LINK
//   ROLE: TYPES
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.p2p

import com.yicamalt.stream.LinkErrorKind

// START_MODULE_MAP
//   PpppNative - initialize, configDebug, apiVersion, connect, connectBreak, check, read, write, forceClose.
//   PpppCheck - code, mode, connectTimeMs, remoteIsPrivate (no address values).
//   PpppCodes - PPPP / app error constants; name(code) and classify(code) -> LinkErrorKind.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 02-00: contract-first port types for Phase 2. Doc comments use // only (no block comments).
//     Android impl: AndroidPpppNative (plan 02-03); JVM: FakePpppNative (tests). Never call PPPP_DeInitialize.
// END_CHANGE_SUMMARY

// The only seam between our code and the vendor PPPP library. Android impl: AndroidPpppNative (plan 02-03); JVM: FakePpppNative.
interface PpppNative {
    fun initialize(initString: ByteArray, maxSessions: Int): Int
    fun configDebug(enable: Int, level: Int): Int
    fun apiVersion(): Int

    // wakeup=true -> PPPP_WakeUp_And_Connect; udpPort is always 0. Returns handle >= 0 or a negative code.
    fun connect(did: String, flag: Byte, wakeup: Boolean, serverString: String, licenseKey: String): Int
    fun connectBreak(did: String): Int
    fun check(handle: Int): PpppCheck

    // Reads into buf[0 until size[0]]; size[0] is updated to the bytes actually read, also on -3003 (TIME_OUT).
    fun read(handle: Int, channel: Int, buf: ByteArray, size: IntArray, timeoutMs: Int): Int
    fun write(handle: Int, channel: Int, buf: ByteArray, size: Int): Int
    fun forceClose(handle: Int): Int
}

// Value-free projection of st_PPPP_Session: remote address is reduced to a private/public boolean in-process.
data class PpppCheck(val code: Int, val mode: Int, val connectTimeMs: Int, val remoteIsPrivate: Boolean)

object PpppCodes {
    const val OK = 0
    const val NOT_INITIALIZED = -3001
    const val ALREADY_INITIALIZED = -3002
    const val TIME_OUT = -3003
    const val INVALID_ID = -3004
    const val INVALID_PARAMETER = -3005
    const val DEVICE_NOT_ONLINE = -3006
    const val FAIL_TO_RESOLVE_NAME = -3007
    const val INVALID_PREFIX = -3008
    const val ID_OUT_OF_DATE = -3009
    const val NO_RELAY_SERVER = -3010
    const val INVALID_SESSION_HANDLE = -3011
    const val SESSION_CLOSED_REMOTE = -3012
    const val SESSION_CLOSED_TIMEOUT = -3013
    const val SESSION_CLOSED_CALLED = -3014
    const val REMOTE_SITE_BUFFER_FULL = -3015
    const val MAX_SESSION = -3017
    const val USER_CONNECT_BREAK = -3019
    const val INVALID_APILICENSE = -3021
    const val INVALID_SERVER_STRING = -3023
    const val NO_AVAILABLE_P2P_SERVER = -3026
    const val DEVICE_MAX_SESSION = -3029
    const val APP_KICKED = -3100
    const val APP_KICK_MAX_SESSION = -3101
    const val APP_WRONG_PASSWORD = -20009
    const val APP_MANUAL_RETRY = -9000

    private val NAMES: Map<Int, String> = mapOf(
        OK to "OK",
        NOT_INITIALIZED to "NOT_INITIALIZED",
        ALREADY_INITIALIZED to "ALREADY_INITIALIZED",
        TIME_OUT to "TIME_OUT",
        INVALID_ID to "INVALID_ID",
        INVALID_PARAMETER to "INVALID_PARAMETER",
        DEVICE_NOT_ONLINE to "DEVICE_NOT_ONLINE",
        FAIL_TO_RESOLVE_NAME to "FAIL_TO_RESOLVE_NAME",
        INVALID_PREFIX to "INVALID_PREFIX",
        ID_OUT_OF_DATE to "ID_OUT_OF_DATE",
        NO_RELAY_SERVER to "NO_RELAY_SERVER",
        INVALID_SESSION_HANDLE to "INVALID_SESSION_HANDLE",
        SESSION_CLOSED_REMOTE to "SESSION_CLOSED_REMOTE",
        SESSION_CLOSED_TIMEOUT to "SESSION_CLOSED_TIMEOUT",
        SESSION_CLOSED_CALLED to "SESSION_CLOSED_CALLED",
        REMOTE_SITE_BUFFER_FULL to "REMOTE_SITE_BUFFER_FULL",
        MAX_SESSION to "MAX_SESSION",
        USER_CONNECT_BREAK to "USER_CONNECT_BREAK",
        INVALID_APILICENSE to "INVALID_APILICENSE",
        INVALID_SERVER_STRING to "INVALID_SERVER_STRING",
        NO_AVAILABLE_P2P_SERVER to "NO_AVAILABLE_P2P_SERVER",
        DEVICE_MAX_SESSION to "DEVICE_MAX_SESSION",
        APP_KICKED to "APP_KICKED",
        APP_KICK_MAX_SESSION to "APP_KICK_MAX_SESSION",
        APP_WRONG_PASSWORD to "APP_WRONG_PASSWORD",
        APP_MANUAL_RETRY to "APP_MANUAL_RETRY",
    )

    // START_BLOCK_CLASSIFY_PPPP_CODE
    // START_CONTRACT: name
    //   PURPOSE: Stable symbolic name of a PPPP / app code for value-free log lines.
    //   INPUTS: { code: Int - PPPP or app code }
    //   OUTPUTS: { String - e.g. "TIME_OUT"; "UNKNOWN_<code>" for codes outside the table }
    //   SIDE_EFFECTS: none
    //   LINKS: M-P2P-NATIVE
    // END_CONTRACT: name
    fun name(code: Int): String = NAMES[code] ?: "UNKNOWN_$code"

    // START_CONTRACT: classify
    //   PURPOSE: Map a PPPP / app code to the user-facing failure class (02-RESEARCH.md "PPPP error table").
    //   INPUTS: { code: Int - negative PPPP or app code }
    //   OUTPUTS: { LinkErrorKind - INTERNAL for everything outside the table, including -3014 / -3019, which
    //     callers treat as "closed by us" (LinkState.Closed) rather than a failure }
    //   SIDE_EFFECTS: none
    //   LINKS: M-P2P-NATIVE, M-TNP-LINK
    // END_CONTRACT: classify
    fun classify(code: Int): LinkErrorKind = when (code) {
        DEVICE_NOT_ONLINE -> LinkErrorKind.OFFLINE
        INVALID_ID, INVALID_PREFIX, ID_OUT_OF_DATE, INVALID_APILICENSE, INVALID_SERVER_STRING,
        in -3038..-3031 -> LinkErrorKind.CREDENTIALS
        TIME_OUT, FAIL_TO_RESOLVE_NAME, NO_RELAY_SERVER, NO_AVAILABLE_P2P_SERVER -> LinkErrorKind.NETWORK
        SESSION_CLOSED_REMOTE, SESSION_CLOSED_TIMEOUT -> LinkErrorKind.REMOTE_CLOSED
        MAX_SESSION, DEVICE_MAX_SESSION -> LinkErrorKind.BUSY
        APP_KICKED, APP_KICK_MAX_SESSION -> LinkErrorKind.KICKED
        APP_WRONG_PASSWORD -> LinkErrorKind.WRONG_PASSWORD
        APP_MANUAL_RETRY -> LinkErrorKind.PROTOCOL
        else -> LinkErrorKind.INTERNAL
    }
    // END_BLOCK_CLASSIFY_PPPP_CODE
}
