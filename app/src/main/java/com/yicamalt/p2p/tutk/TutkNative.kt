// FILE: TutkNative.kt
// VERSION: 0.1.1
// START_MODULE_CONTRACT
//   PURPOSE: The only seam between our code and the vendor TUTK IOTC/AV libraries, plus their error-code table.
//   SCOPE: TutkNative interface (12 calls in the vendor client order), value-free TutkCheck / ClientStart /
//     RecvResult projections, TutkCodes (IOTC_ER_* and AV_ER_* names + classify to LinkErrorKind).
//   DEPENDS: M-STREAM-CORE (LinkErrorKind)
//   LINKS: M-TUTK-LINK, V-M-TUTK-LINK, M-P2P-NATIVE
//   ROLE: TYPES
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.p2p.tutk

import com.yicamalt.stream.LinkErrorKind

// START_MODULE_MAP
//   TutkNative - initialize, avInitialize, iotcVersion, avVersion, sessionId, connectByUid, connectStop, check,
//     clientStart, sendIoCtrl, recvFrame, stop.
//   TutkCheck - code, mode (0 P2P, 1 Relay, 2 LAN), remoteIsPrivate (no address values).
//   ClientStart - av channel id (or negative code), servType.
//   RecvResult - code (> 0 bytes, else AV_ER_*), size (frame bytes written into buf).
//   TutkCodes - IOTC / AV constants; name(code), classify(code) -> LinkErrorKind; isAlreadyInitialized helpers.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.1 - Phase 2 review MED-5: AV_ER_BUFPARA_MAXSIZE_INSUFF (-20001) added to the code table.
//   PREVIOUS: v0.1.0 - GSD plan 02-10: created. Android impl: AndroidTutkNative; JVM: FakeTutkNative (tests).
//     IOTC / AV are never de-initialised. The cloud uid passed to connectByUid is a secret: never logged.
// END_CHANGE_SUMMARY

// The only seam between our code and the vendor TUTK libraries. Every call must run on a named TUTK thread.
interface TutkNative {
    // IOTC_Initialize2(0): 0 or IOTC_ER_ALREADY_INITIALIZED (-3) is success.
    fun initialize(): Int

    // avInitialize(maxChannels): >= 0 or AV_ER_ALREADY_INITIALIZED (-20031) is success.
    fun avInitialize(maxChannels: Int): Int
    fun iotcVersion(): Int
    fun avVersion(): Int

    // IOTC_Get_SessionID(): free session id >= 0 or a negative IOTC code.
    fun sessionId(): Int

    // IOTC_Connect_ByUID_Parallel(uid, sid0): session id >= 0 or a negative IOTC code. Blocks; abort with connectStop.
    fun connectByUid(uid: String, sid0: Int): Int
    fun connectStop(sid0: Int): Int
    fun check(sid: Int): TutkCheck

    // avClientStart2(sid, user, pass, timeoutS, servType, channel 0, resend): av >= 0 or AV_ER_* (-20009 wrong password).
    fun clientStart(sid: Int, user: String, pass: String, timeoutS: Int): ClientStart
    fun sendIoCtrl(av: Int, type: Int, payload: ByteArray): Int

    // avRecvFrameData2 into buf (whole buffer) and frameInfo (24-byte FrameHead, little-endian).
    fun recvFrame(av: Int, buf: ByteArray, frameInfo: ByteArray): RecvResult

    // Vendor stop order: avSendIOCtrl(767, 8 zeros), avSendIOCtrlExit, avClientStop, avClientExit(sid, 0),
    // IOTC_Session_Close. A negative av skips the AV steps, a negative sid skips the session steps.
    fun stop(av: Int, sid: Int)
}

// Value-free projection of St_SInfoEx: the UID and remote addresses are reduced in-process and zero-filled.
data class TutkCheck(val code: Int, val mode: Int, val remoteIsPrivate: Boolean)

data class ClientStart(val av: Int, val servType: Int)

data class RecvResult(val code: Int, val size: Int)

object TutkCodes {
    const val OK = 0

    // IOTC_ER_*
    const val IOTC_SERVER_NOT_RESPONSE = -1
    const val IOTC_FAIL_RESOLVE_HOSTNAME = -2
    const val IOTC_ALREADY_INITIALIZED = -3
    const val IOTC_FAIL_CREATE_MUTEX = -4
    const val IOTC_FAIL_CREATE_THREAD = -5
    const val IOTC_FAIL_CREATE_SOCKET = -6
    const val IOTC_UNLICENSE = -10
    const val IOTC_NOT_INITIALIZED = -12
    const val IOTC_TIMEOUT = -13
    const val IOTC_INVALID_SID = -14
    const val IOTC_UNKNOWN_DEVICE = -15
    const val IOTC_EXCEED_MAX_SESSION = -18
    const val IOTC_CAN_NOT_FIND_DEVICE = -19
    const val IOTC_SESSION_CLOSE_BY_REMOTE = -22
    const val IOTC_REMOTE_TIMEOUT_DISCONNECT = -23
    const val IOTC_DEVICE_NOT_LISTENING = -24
    const val IOTC_MASTER_TOO_FEW = -28
    const val IOTC_SESSION_NO_FREE_CHANNEL = -31
    const val IOTC_TCP_TRAVEL_FAILED = -32
    const val IOTC_TCP_CONNECT_TO_SERVER_FAILED = -33
    const val IOTC_NO_PERMISSION = -40
    const val IOTC_NETWORK_UNREACHABLE = -41
    const val IOTC_FAIL_SETUP_RELAY = -42
    const val IOTC_DEVICE_EXCEED_MAX_SESSION = -48
    const val IOTC_SESSION_CLOSED = -50
    const val IOTC_ABORTED = -52
    const val IOTC_DEVICE_IS_BANNED = -59
    const val IOTC_MASTER_NOT_RESPONSE = -60
    const val IOTC_DEVICE_IS_SLEEP = -64
    const val IOTC_DEVICE_OFFLINE = -90

    // AV_ER_*
    const val AV_INVALID_ARG = -20000
    const val AV_BUFPARA_MAXSIZE_INSUFF = -20001
    const val AV_EXCEED_MAX_CHANNEL = -20002
    const val AV_MEM_INSUFF = -20003
    const val AV_SERV_NO_RESPONSE = -20007
    const val AV_CLIENT_NO_AVLOGIN = -20008
    const val AV_WRONG_VIEWACCORPWD = -20009
    const val AV_INVALID_SID = -20010
    const val AV_TIMEOUT = -20011
    const val AV_DATA_NOREADY = -20012
    const val AV_INCOMPLETE_FRAME = -20013
    const val AV_LOSED_THIS_FRAME = -20014
    const val AV_SESSION_CLOSE_BY_REMOTE = -20015
    const val AV_REMOTE_TIMEOUT_DISCONNECT = -20016
    const val AV_SERVER_EXIT = -20017
    const val AV_CLIENT_EXIT = -20018
    const val AV_NOT_INITIALIZED = -20019
    const val AV_SENDIOCTRL_EXIT = -20022
    const val AV_NO_PERMISSION = -20023
    const val AV_IOTC_SESSION_CLOSED = -20025
    const val AV_IOTC_DEINITIALIZED = -20026
    const val AV_ALREADY_INITIALIZED = -20031
    const val AV_DTLS_WRONG_PWD = -20040

    private val NAMES: Map<Int, String> = mapOf(
        OK to "OK",
        IOTC_SERVER_NOT_RESPONSE to "IOTC_ER_SERVER_NOT_RESPONSE",
        IOTC_FAIL_RESOLVE_HOSTNAME to "IOTC_ER_FAIL_RESOLVE_HOSTNAME",
        IOTC_ALREADY_INITIALIZED to "IOTC_ER_ALREADY_INITIALIZED",
        IOTC_FAIL_CREATE_MUTEX to "IOTC_ER_FAIL_CREATE_MUTEX",
        IOTC_FAIL_CREATE_THREAD to "IOTC_ER_FAIL_CREATE_THREAD",
        IOTC_FAIL_CREATE_SOCKET to "IOTC_ER_FAIL_CREATE_SOCKET",
        IOTC_UNLICENSE to "IOTC_ER_UNLICENSE",
        IOTC_NOT_INITIALIZED to "IOTC_ER_NOT_INITIALIZED",
        IOTC_TIMEOUT to "IOTC_ER_TIMEOUT",
        IOTC_INVALID_SID to "IOTC_ER_INVALID_SID",
        IOTC_UNKNOWN_DEVICE to "IOTC_ER_UNKNOWN_DEVICE",
        IOTC_EXCEED_MAX_SESSION to "IOTC_ER_EXCEED_MAX_SESSION",
        IOTC_CAN_NOT_FIND_DEVICE to "IOTC_ER_CAN_NOT_FIND_DEVICE",
        IOTC_SESSION_CLOSE_BY_REMOTE to "IOTC_ER_SESSION_CLOSE_BY_REMOTE",
        IOTC_REMOTE_TIMEOUT_DISCONNECT to "IOTC_ER_REMOTE_TIMEOUT_DISCONNECT",
        IOTC_DEVICE_NOT_LISTENING to "IOTC_ER_DEVICE_NOT_LISTENING",
        IOTC_MASTER_TOO_FEW to "IOTC_ER_MASTER_TOO_FEW",
        IOTC_SESSION_NO_FREE_CHANNEL to "IOTC_ER_SESSION_NO_FREE_CHANNEL",
        IOTC_TCP_TRAVEL_FAILED to "IOTC_ER_TCP_TRAVEL_FAILED",
        IOTC_TCP_CONNECT_TO_SERVER_FAILED to "IOTC_ER_TCP_CONNECT_TO_SERVER_FAILED",
        IOTC_NO_PERMISSION to "IOTC_ER_NO_PERMISSION",
        IOTC_NETWORK_UNREACHABLE to "IOTC_ER_NETWORK_UNREACHABLE",
        IOTC_FAIL_SETUP_RELAY to "IOTC_ER_FAIL_SETUP_RELAY",
        IOTC_DEVICE_EXCEED_MAX_SESSION to "IOTC_ER_DEVICE_EXCEED_MAX_SESSION",
        IOTC_SESSION_CLOSED to "IOTC_ER_SESSION_CLOSED",
        IOTC_ABORTED to "IOTC_ER_ABORTED",
        IOTC_DEVICE_IS_BANNED to "IOTC_ER_DEVICE_IS_BANNED",
        IOTC_MASTER_NOT_RESPONSE to "IOTC_ER_MASTER_NOT_RESPONSE",
        IOTC_DEVICE_IS_SLEEP to "IOTC_ER_DEVICE_IS_SLEEP",
        IOTC_DEVICE_OFFLINE to "IOTC_ER_DEVICE_OFFLINE",
        AV_INVALID_ARG to "AV_ER_INVALID_ARG",
        AV_BUFPARA_MAXSIZE_INSUFF to "AV_ER_BUFPARA_MAXSIZE_INSUFF",
        AV_EXCEED_MAX_CHANNEL to "AV_ER_EXCEED_MAX_CHANNEL",
        AV_MEM_INSUFF to "AV_ER_MEM_INSUFF",
        AV_SERV_NO_RESPONSE to "AV_ER_SERV_NO_RESPONSE",
        AV_CLIENT_NO_AVLOGIN to "AV_ER_CLIENT_NO_AVLOGIN",
        AV_WRONG_VIEWACCORPWD to "AV_ER_WRONG_VIEWACCorPWD",
        AV_INVALID_SID to "AV_ER_INVALID_SID",
        AV_TIMEOUT to "AV_ER_TIMEOUT",
        AV_DATA_NOREADY to "AV_ER_DATA_NOREADY",
        AV_INCOMPLETE_FRAME to "AV_ER_INCOMPLETE_FRAME",
        AV_LOSED_THIS_FRAME to "AV_ER_LOSED_THIS_FRAME",
        AV_SESSION_CLOSE_BY_REMOTE to "AV_ER_SESSION_CLOSE_BY_REMOTE",
        AV_REMOTE_TIMEOUT_DISCONNECT to "AV_ER_REMOTE_TIMEOUT_DISCONNECT",
        AV_SERVER_EXIT to "AV_ER_SERVER_EXIT",
        AV_CLIENT_EXIT to "AV_ER_CLIENT_EXIT",
        AV_NOT_INITIALIZED to "AV_ER_NOT_INITIALIZED",
        AV_SENDIOCTRL_EXIT to "AV_ER_SENDIOCTRL_EXIT",
        AV_NO_PERMISSION to "AV_ER_NO_PERMISSION",
        AV_IOTC_SESSION_CLOSED to "AV_ER_IOTC_SESSION_CLOSED",
        AV_IOTC_DEINITIALIZED to "AV_ER_IOTC_DEINITIALIZED",
        AV_ALREADY_INITIALIZED to "AV_ER_ALREADY_INITIALIZED",
        AV_DTLS_WRONG_PWD to "AV_ER_DTLS_WRONG_PWD",
    )

    // START_BLOCK_CLASSIFY_TUTK_CODE
    // START_CONTRACT: name
    //   PURPOSE: Stable symbolic name of an IOTC / AV code for value-free log lines.
    //   INPUTS: { code: Int - IOTC_ER_* or AV_ER_* }
    //   OUTPUTS: { String - vendor constant name, e.g. "AV_ER_WRONG_VIEWACCorPWD"; "UNKNOWN_<code>" otherwise }
    //   SIDE_EFFECTS: none
    //   LINKS: M-TUTK-LINK
    // END_CONTRACT: name
    fun name(code: Int): String = NAMES[code] ?: "UNKNOWN_$code"

    // START_CONTRACT: classify
    //   PURPOSE: Map an IOTC / AV code to the user-facing failure class (02-RESEARCH.md Pattern 8).
    //   INPUTS: { code: Int - negative IOTC or AV code }
    //   OUTPUTS: { LinkErrorKind - WRONG_PASSWORD for -20009 / -20040; REMOTE_CLOSED for -20015 / -20016 / -20010
    //     and the IOTC remote-close codes; INTERNAL for everything outside the table. -20012 / -20013 / -20014 are
    //     read-loop conditions (retry / drop to next I-frame) that callers handle before classify. }
    //   SIDE_EFFECTS: none
    //   LINKS: M-TUTK-LINK
    // END_CONTRACT: classify
    fun classify(code: Int): LinkErrorKind = when (code) {
        AV_WRONG_VIEWACCORPWD, AV_DTLS_WRONG_PWD -> LinkErrorKind.WRONG_PASSWORD
        AV_SESSION_CLOSE_BY_REMOTE, AV_REMOTE_TIMEOUT_DISCONNECT, AV_INVALID_SID, AV_IOTC_SESSION_CLOSED,
        AV_SERVER_EXIT, IOTC_SESSION_CLOSE_BY_REMOTE, IOTC_REMOTE_TIMEOUT_DISCONNECT, IOTC_SESSION_CLOSED,
        -> LinkErrorKind.REMOTE_CLOSED
        IOTC_DEVICE_OFFLINE, IOTC_CAN_NOT_FIND_DEVICE, IOTC_DEVICE_NOT_LISTENING, IOTC_DEVICE_IS_SLEEP,
        -> LinkErrorKind.OFFLINE
        IOTC_UNKNOWN_DEVICE, IOTC_UNLICENSE, IOTC_NO_PERMISSION, IOTC_DEVICE_IS_BANNED, AV_NO_PERMISSION,
        -> LinkErrorKind.CREDENTIALS
        IOTC_SERVER_NOT_RESPONSE, IOTC_FAIL_RESOLVE_HOSTNAME, IOTC_TIMEOUT, IOTC_MASTER_TOO_FEW,
        IOTC_TCP_TRAVEL_FAILED, IOTC_TCP_CONNECT_TO_SERVER_FAILED, IOTC_NETWORK_UNREACHABLE, IOTC_FAIL_SETUP_RELAY,
        IOTC_MASTER_NOT_RESPONSE,
        -> LinkErrorKind.NETWORK
        IOTC_EXCEED_MAX_SESSION, IOTC_DEVICE_EXCEED_MAX_SESSION, IOTC_SESSION_NO_FREE_CHANNEL, AV_EXCEED_MAX_CHANNEL,
        -> LinkErrorKind.BUSY
        AV_TIMEOUT, AV_SERV_NO_RESPONSE -> LinkErrorKind.TIMEOUT
        else -> LinkErrorKind.INTERNAL
    }
    // END_BLOCK_CLASSIFY_TUTK_CODE

    fun iotcInitOk(code: Int): Boolean = code >= 0 || code == IOTC_ALREADY_INITIALIZED

    fun avInitOk(code: Int): Boolean = code >= 0 || code == AV_ALREADY_INITIALIZED
}
