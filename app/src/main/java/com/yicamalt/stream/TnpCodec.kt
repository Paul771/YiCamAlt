// FILE: TnpCodec.kt
// VERSION: 0.2.0
// START_MODULE_CONTRACT
//   PURPOSE: Encode TNP command units and parse TNP heads, IOCTRL replies and frame units (big-endian wire).
//   SCOPE: 8-byte TNPHead, 40-byte TNPIOCtrlHead, per-channel size validation, frame unit split.
//   DEPENDS: StreamTypes (FrameInfo), FrameHeadCodec
//   LINKS: M-STREAM-CORE, V-M-STREAM-CORE
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.stream

import java.nio.ByteBuffer
import java.nio.ByteOrder

// START_MODULE_MAP
//   TnpCodec - object: HEAD_SIZE, IOCTRL_HEAD_SIZE, FRAME_HEAD_SIZE, IOTYPE_* constants, CH_* channels.
//   TnpCodec.TnpHead - parsed 8-byte head (version, ioType, abilityRet, dataSize).
//   TnpCodec.HeadCheck - Ok | Dirty(reason in zero-size, too-large, bad-channel, bad-iotype).
//   TnpCodec.IoCtrlReply - parsed reply (type, number, payloadSize, authResult, payload); toString without payload.
//   TnpCodec.encodeHead / parseHead / maxDataSize / validateHead / commandUnit / parseIoCtrlReply / splitFrameUnit.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.2.0 - GSD plan 04-00: record channels CH_RECORD_I 4, CH_RECORD_P 5, CH_NEW_RECORD 7 (vendor
//     TnpCamera.startThread) with the live I / P buffer limits.
//   PREVIOUS: v0.1.0 - GSD plan 02-04 from 02-RESEARCH.md Pattern 4 (VERIFIED-LOCAL layouts).
//     INVARIANT: a head that fails validateHead is reported Dirty and its dataSize is never used to allocate.
//     INVARIANT: nothing here logs; IoCtrlReply.toString prints sizes only.
// END_CHANGE_SUMMARY

object TnpCodec {
    const val HEAD_SIZE = 8
    const val IOCTRL_HEAD_SIZE = 40
    const val FRAME_HEAD_SIZE = 24
    const val AUTH_OFFSET = 8
    const val AUTH_SIZE = 32

    const val IOTYPE_VIDEO = 1
    const val IOTYPE_AUDIO = 2
    const val IOTYPE_COMMAND = 3

    const val CH_COMMAND = 0
    const val CH_AUDIO = 1
    const val CH_IFRAME = 2
    const val CH_PFRAME = 3
    const val CH_NEW_LIVE = 6
    const val CH_RECORD_I = 4
    const val CH_RECORD_P = 5
    const val CH_NEW_RECORD = 7

    data class TnpHead(val version: Int, val ioType: Int, val abilityRet: Int, val dataSize: Long)

    sealed interface HeadCheck {
        object Ok : HeadCheck {
            override fun toString() = "Ok"
        }

        data class Dirty(val reason: String) : HeadCheck
    }

    class IoCtrlReply(
        val type: Int,
        val number: Int,
        val payloadSize: Int,
        val authResult: Int,
        val payload: ByteArray,
    ) {
        override fun toString() =
            "IoCtrlReply(type=$type, number=$number, payloadSize=$payloadSize, authResult=$authResult, bytes=${payload.size})"
    }

    // START_CONTRACT: encodeHead
    //   PURPOSE: Encode the 8-byte TNPHead.
    //   INPUTS: { version: Int, ioType: Int, dataSize: Int - bytes following the head, abilityRet: Int = 0 }
    //   OUTPUTS: { ByteArray - 8 bytes BE }
    //   SIDE_EFFECTS: none
    //   LINKS: M-STREAM-CORE
    // END_CONTRACT: encodeHead
    fun encodeHead(version: Int, ioType: Int, dataSize: Int, abilityRet: Int = 0): ByteArray =
        ByteBuffer.allocate(HEAD_SIZE).order(ByteOrder.BIG_ENDIAN)
            .put(version.toByte()).put(ioType.toByte()).put(abilityRet.toByte()).put(0)
            .putInt(dataSize)
            .array()

    // START_CONTRACT: parseHead
    //   PURPOSE: Decode an 8-byte TNPHead (dataSize as unsigned u32).
    //   INPUTS: { bytes: ByteArray, offset: Int = 0 }
    //   OUTPUTS: { TnpHead }
    //   SIDE_EFFECTS: none; throws IllegalArgumentException when fewer than 8 bytes are available
    //   LINKS: M-STREAM-CORE
    // END_CONTRACT: parseHead
    fun parseHead(bytes: ByteArray, offset: Int = 0): TnpHead {
        require(offset >= 0 && bytes.size - offset >= HEAD_SIZE) { "TNP head needs $HEAD_SIZE bytes" }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        return TnpHead(
            version = bytes[offset].toInt() and 0xFF,
            ioType = bytes[offset + 1].toInt() and 0xFF,
            abilityRet = bytes[offset + 2].toInt() and 0xFF,
            dataSize = bb.getInt(offset + 4).toLong() and 0xFFFF_FFFFL,
        )
    }

    // START_CONTRACT: maxDataSize
    //   PURPOSE: Vendor upper bound of dataSize per channel.
    //   INPUTS: { channel: Int }
    //   OUTPUTS: { Int? - max bytes, null for an unknown channel }
    //   SIDE_EFFECTS: none
    //   LINKS: M-STREAM-CORE
    // END_CONTRACT: maxDataSize
    fun maxDataSize(channel: Int): Int? = when (channel) {
        CH_COMMAND -> 204_800
        CH_AUDIO -> 5_120
        CH_IFRAME, CH_NEW_LIVE, CH_RECORD_I, CH_NEW_RECORD -> 1_048_576
        CH_PFRAME, CH_RECORD_P -> 307_200
        else -> null
    }

    // START_CONTRACT: validateHead
    //   PURPOSE: Decide whether a received head can be trusted on this channel.
    //   INPUTS: { head: TnpHead, channel: Int }
    //   OUTPUTS: { HeadCheck - Ok, or Dirty(zero-size | too-large | bad-channel | bad-iotype) }
    //   SIDE_EFFECTS: none
    //   LINKS: M-STREAM-CORE
    // END_CONTRACT: validateHead
    fun validateHead(head: TnpHead, channel: Int): HeadCheck {
        // START_BLOCK_VALIDATE_HEAD
        val max = maxDataSize(channel) ?: return HeadCheck.Dirty("bad-channel")
        if (head.ioType !in IOTYPE_VIDEO..IOTYPE_COMMAND) return HeadCheck.Dirty("bad-iotype")
        if (head.dataSize <= 0L) return HeadCheck.Dirty("zero-size")
        if (head.dataSize > max) return HeadCheck.Dirty("too-large")
        return HeadCheck.Ok
        // END_BLOCK_VALIDATE_HEAD
    }

    // START_CONTRACT: commandUnit
    //   PURPOSE: Full command unit = TNPHead(version, 3, 40 + payload) + TNPIOCtrlHead(type, number, 0, size, auth) + payload.
    //   INPUTS: { version: Int - 2, type: Int - IOCTRL command, number: Int - 1-based per-session counter,
    //             auth: ByteArray - 32-byte YiP2pAuth.authInfo, payload: ByteArray }
    //   OUTPUTS: { ByteArray - 48 + payload.size bytes }
    //   SIDE_EFFECTS: none; throws IllegalArgumentException when auth is not 32 bytes
    //   LINKS: M-STREAM-CORE, YiP2pAuth.authInfo
    // END_CONTRACT: commandUnit
    fun commandUnit(version: Int, type: Int, number: Int, auth: ByteArray, payload: ByteArray): ByteArray {
        // START_BLOCK_ENCODE_COMMAND
        require(auth.size == AUTH_SIZE) { "authInfo must be $AUTH_SIZE bytes" }
        require(payload.size <= 0xFFFF) { "payload too large" }
        val bb = ByteBuffer.allocate(HEAD_SIZE + IOCTRL_HEAD_SIZE + payload.size).order(ByteOrder.BIG_ENDIAN)
        bb.put(encodeHead(version, IOTYPE_COMMAND, IOCTRL_HEAD_SIZE + payload.size))
        bb.putShort(type.toShort()).putShort(number.toShort()).putShort(0).putShort(payload.size.toShort())
        bb.put(auth).put(payload)
        return bb.array()
        // END_BLOCK_ENCODE_COMMAND
    }

    // START_CONTRACT: parseIoCtrlReply
    //   PURPOSE: Parse a command-channel body (bytes after TNPHead) into an IoCtrlReply.
    //   INPUTS: { body: ByteArray }
    //   OUTPUTS: { IoCtrlReply? - null when body < 40 bytes; payload = min(payloadSize, available) bytes after the head }
    //   SIDE_EFFECTS: none
    //   LINKS: M-STREAM-CORE, IoCtrlMessages.authOutcome
    // END_CONTRACT: parseIoCtrlReply
    fun parseIoCtrlReply(body: ByteArray): IoCtrlReply? {
        // START_BLOCK_PARSE_UNIT
        if (body.size < IOCTRL_HEAD_SIZE) return null
        val bb = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN)
        val type = bb.getShort(0).toInt() and 0xFFFF
        val number = bb.getShort(2).toInt() and 0xFFFF
        val payloadSize = bb.getShort(6).toInt() and 0xFFFF
        val authResult = bb.getInt(AUTH_OFFSET)
        val end = minOf(body.size, IOCTRL_HEAD_SIZE + payloadSize)
        return IoCtrlReply(type, number, payloadSize, authResult, body.copyOfRange(IOCTRL_HEAD_SIZE, end))
        // END_BLOCK_PARSE_UNIT
    }

    // START_CONTRACT: splitFrameUnit
    //   PURPOSE: Split a media body (bytes after TNPHead) into frame head + payload.
    //   INPUTS: { body: ByteArray, bigEndian: Boolean = true - false for TUTK }
    //   OUTPUTS: { Pair<FrameInfo, ByteArray>? - null when body <= 24 bytes; payload is a copy of body[24..] }
    //   SIDE_EFFECTS: none
    //   LINKS: M-STREAM-CORE, FrameHeadCodec.parse
    // END_CONTRACT: splitFrameUnit
    fun splitFrameUnit(body: ByteArray, bigEndian: Boolean = true): Pair<FrameInfo, ByteArray>? {
        if (body.size <= FRAME_HEAD_SIZE) return null
        val info = FrameHeadCodec.parse(body, 0, bigEndian)
        return info to body.copyOfRange(FRAME_HEAD_SIZE, body.size)
    }
}
