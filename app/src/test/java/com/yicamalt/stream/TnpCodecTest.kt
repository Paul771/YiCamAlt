// FILE: TnpCodecTest.kt
// VERSION: 0.2.0
// START_MODULE_CONTRACT
//   PURPOSE: Byte-exact contract for TNP command units, head parsing/validation, IOCTRL replies and frame units.
//   SCOPE: commandUnit 4881 / 9029 / 768, parseHead round trip, validateHead per channel, parseIoCtrlReply,
//          splitFrameUnit, record channel 4 / 5 / 7 buffer limits.
//   DEPENDS: M-STREAM-CORE (TnpCodec, IoCtrlMessages, YiP2pAuth)
//   LINKS: V-M-STREAM-CORE
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.stream

import com.yicamalt.stream.TnpCodec.HeadCheck
import com.yicamalt.stream.TnpCodec.TnpHead
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random

// START_MODULE_MAP
//   bytes(vararg) - int list to byte array.
//   ZERO_AUTH - 32 zero bytes.
//   TnpCodecTest - byte-exact command units, head validation, reply and frame unit parsing.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.2.0 - GSD plan 04-00: record channels 4 / 5 / 7 vendor buffer limits.
//   PREVIOUS: v0.1.0 - GSD plan 02-04: wire-layer vectors.
// END_CHANGE_SUMMARY

class TnpCodecTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private val ZERO_AUTH = ByteArray(32)

    @Test
    fun `record channels have vendor buffer limits`() {
        assertEquals(1_048_576, TnpCodec.maxDataSize(4))
        assertEquals(307_200, TnpCodec.maxDataSize(5))
        assertEquals(1_048_576, TnpCodec.maxDataSize(7))
        assertNull(TnpCodec.maxDataSize(8))
    }

    @Test
    fun `4881 command unit is 56 bytes byte exact`() {
        val unit = TnpCodec.commandUnit(2, IoCtrlMessages.SET_RESOLUTION, 1, ZERO_AUTH, IoCtrlMessages.setResolution(1, 3))
        val expected = bytes(2, 3, 0, 0, 0, 0, 0, 0x30) +
            bytes(0x13, 0x11, 0, 1, 0, 0, 0, 8) + ByteArray(32) +
            bytes(0, 0, 0, 1, 0, 0, 0, 3)
        assertEquals(56, unit.size)
        assertArrayEquals(expected, unit)
    }

    @Test
    fun `9029 command unit is 52 bytes byte exact`() {
        val unit = TnpCodec.commandUnit(2, IoCtrlMessages.START_REALTIME, 2, ZERO_AUTH, IoCtrlMessages.startRealtime(4, 1))
        val expected = bytes(2, 3, 0, 0, 0, 0, 0, 0x2C) +
            bytes(0x23, 0x45, 0, 2, 0, 0, 0, 4) + ByteArray(32) +
            bytes(4, 1, 1, 0)
        assertEquals(52, unit.size)
        assertArrayEquals(expected, unit)
    }

    @Test
    fun `768 command unit is 56 bytes byte exact`() {
        val unit = TnpCodec.commandUnit(2, IoCtrlMessages.AUDIO_START, 3, ZERO_AUTH, IoCtrlMessages.audioStart())
        val expected = bytes(2, 3, 0, 0, 0, 0, 0, 0x30) +
            bytes(0x03, 0x00, 0, 3, 0, 0, 0, 8) + ByteArray(32) + ByteArray(8)
        assertEquals(56, unit.size)
        assertArrayEquals(expected, unit)
    }

    @Test
    fun `authInfo lands at IOCtrl offset 8`() {
        val auth = YiP2pAuth.authInfo(true, "SYNTHpw1", "SYNTHAB", Random(3))
        val unit = TnpCodec.commandUnit(2, IoCtrlMessages.SET_RESOLUTION, 1, auth, IoCtrlMessages.setResolution(1, 0))
        assertArrayEquals(auth, unit.copyOfRange(16, 48))
    }

    @Test
    fun `parseHead round trips encodeHead`() {
        val head = TnpCodec.parseHead(TnpCodec.encodeHead(2, 1, 307_200, abilityRet = 5))
        assertEquals(TnpHead(2, 1, 5, 307_200L), head)
        val unsigned = TnpCodec.parseHead(bytes(2, 3, 0, 0, 0xFF, 0xFF, 0xFF, 0xFF))
        assertEquals(0xFFFF_FFFFL, unsigned.dataSize)
    }

    @Test
    fun `validateHead reports dirty heads per channel`() {
        assertEquals(HeadCheck.Dirty("zero-size"), TnpCodec.validateHead(TnpHead(2, 3, 0, 0), 0))
        assertEquals(HeadCheck.Dirty("too-large"), TnpCodec.validateHead(TnpHead(2, 1, 0, 307_201), 3))
        assertEquals(HeadCheck.Ok, TnpCodec.validateHead(TnpHead(2, 1, 0, 1_048_576), 2))
        assertEquals(HeadCheck.Ok, TnpCodec.validateHead(TnpHead(2, 1, 0, 1_048_576), 6))
        assertEquals(HeadCheck.Dirty("too-large"), TnpCodec.validateHead(TnpHead(2, 2, 0, 5_121), 1))
        assertEquals(HeadCheck.Dirty("too-large"), TnpCodec.validateHead(TnpHead(2, 3, 0, 204_801), 0))
        assertEquals(HeadCheck.Dirty("bad-channel"), TnpCodec.validateHead(TnpHead(2, 1, 0, 100), 9))
        assertEquals(HeadCheck.Dirty("bad-iotype"), TnpCodec.validateHead(TnpHead(2, 7, 0, 100), 2))
        assertEquals(HeadCheck.Dirty("too-large"), TnpCodec.validateHead(TnpHead(2, 1, 0, 0xFFFF_FFFFL), 2))
    }

    @Test
    fun `parseIoCtrlReply reads type number payload and authResult`() {
        val body = ByteBuffer.allocate(44).order(ByteOrder.BIG_ENDIAN)
            .putShort(4882).putShort(1).putShort(0).putShort(4)
            .putInt(2).put(ByteArray(28))
            .put(bytes(9, 8, 7, 6))
            .array()
        val reply = TnpCodec.parseIoCtrlReply(body)
        assertNotNull(reply)
        reply!!
        assertEquals(4882, reply.type)
        assertEquals(1, reply.number)
        assertEquals(4, reply.payloadSize)
        assertEquals(2, reply.authResult)
        assertArrayEquals(bytes(9, 8, 7, 6), reply.payload)
        assertFalse(reply.toString().contains("9, 8"))
    }

    @Test
    fun `parseIoCtrlReply returns null for a short body`() {
        assertNull(TnpCodec.parseIoCtrlReply(ByteArray(39)))
    }

    @Test
    fun `parseIoCtrlReply negative authResult stays signed`() {
        val body = ByteBuffer.allocate(40).order(ByteOrder.BIG_ENDIAN)
            .putShort(4882).putShort(1).putShort(0).putShort(0).putInt(-1).put(ByteArray(28)).array()
        assertEquals(-1, TnpCodec.parseIoCtrlReply(body)!!.authResult)
    }

    @Test
    fun `splitFrameUnit returns head and payload copy`() {
        val head = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN)
            .putShort(81).put(0).put(1).put(0).put(2).putShort(10)
            .putShort(640).putShort(360).putInt(5).put(0).put(0).put(0).put(0).putInt(6)
            .array()
        val body = head + bytes(0, 0, 0, 1, 0x40)
        val (info, payload) = TnpCodec.splitFrameUnit(body)!!
        assertEquals(81, info.codecId)
        assertEquals(10, info.seq)
        assertEquals(640, info.width)
        assertArrayEquals(bytes(0, 0, 0, 1, 0x40), payload)
        payload[0] = 9
        assertEquals(0.toByte(), body[24])
    }

    @Test
    fun `splitFrameUnit rejects a body without payload`() {
        assertNull(TnpCodec.splitFrameUnit(ByteArray(24)))
        assertNull(TnpCodec.splitFrameUnit(ByteArray(3)))
        assertNotNull(TnpCodec.splitFrameUnit(ByteArray(25)))
    }
}
