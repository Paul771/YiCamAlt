// FILE: FrameHeadCodecTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Executable contract for the 24-byte frame head parser in both byte orders.
//   SCOPE: BE (TNP) and LE (TUTK) parse, unsigned reads, offset, short input.
//   DEPENDS: M-STREAM-CORE (FrameHeadCodec, FrameInfo)
//   LINKS: V-M-STREAM-CORE
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.stream

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.ByteBuffer
import java.nio.ByteOrder

// START_MODULE_MAP
//   head(order, ts) - hand-built 24-byte head with fixed synthetic values.
//   expected(ts) - the FrameInfo that head() encodes.
//   FrameHeadCodecTest - BE/LE parse, unsigned fields, offset, short input.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 02-04: wire-layer vectors.
// END_CHANGE_SUMMARY

class FrameHeadCodecTest {

    private fun head(order: ByteOrder, ts: Long = 1_700_000_000L): ByteArray =
        ByteBuffer.allocate(24).order(order)
            .putShort(78).put(1).put(1).put(2).put(5).putShort(0xFFFE.toShort())
            .putShort(1920).putShort(1080).putInt(ts.toInt())
            .put(1).put(0).put(3).put(4).putInt(123)
            .array()

    private fun expected(ts: Long = 1_700_000_000L) = FrameInfo(
        codecId = 78, flags = 1, liveFlag = 1, onlineNum = 2, useCount = 5, seq = 65534,
        width = 1920, height = 1080, timestampSec = ts, isDay = 1, cover = 0, outLoss = 3, inLoss = 4,
        timestampMs = 123,
    )

    @Test
    fun `big endian head parses exactly`() {
        val info = FrameHeadCodec.parse(head(ByteOrder.BIG_ENDIAN), bigEndian = true)
        assertEquals(expected(), info)
        assertTrue(info.isKeyFrame)
    }

    @Test
    fun `little endian head parses exactly`() {
        assertEquals(expected(), FrameHeadCodec.parse(head(ByteOrder.LITTLE_ENDIAN), bigEndian = false))
    }

    @Test
    fun `u32 timestamp above 2^31 stays positive`() {
        val ts = 0xF000_0000L
        val info = FrameHeadCodec.parse(head(ByteOrder.BIG_ENDIAN, ts), bigEndian = true)
        assertEquals(ts, info.timestampSec)
        assertTrue(info.timestampSec > 0)
    }

    @Test
    fun `offset parameter is honoured`() {
        val buf = ByteArray(5) { 0x7F } + head(ByteOrder.BIG_ENDIAN) + ByteArray(3) { 0x55 }
        assertEquals(expected(), FrameHeadCodec.parse(buf, offset = 5, bigEndian = true))
    }

    @Test
    fun `fewer than 24 bytes is rejected`() {
        assertThrows<IllegalArgumentException> { FrameHeadCodec.parse(ByteArray(23), bigEndian = true) }
        assertThrows<IllegalArgumentException> { FrameHeadCodec.parse(ByteArray(30), offset = 7, bigEndian = true) }
    }
}
