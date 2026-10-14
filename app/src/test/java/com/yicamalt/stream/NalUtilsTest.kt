// FILE: NalUtilsTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Executable contract for NAL framing, Annex-B conversion, NAL types and csd extraction (criterion 5).
//   SCOPE: detectFraming, toAnnexB, splitAnnexB, nalTypes (H.264/H.265), parameterSets, isKeyStart.
//   DEPENDS: M-STREAM-CORE (NalUtils, VideoCodec)
//   LINKS: V-M-STREAM-CORE
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.stream

import com.yicamalt.stream.NalUtils.Framing
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// START_MODULE_MAP
//   nal(header, size) - synthetic NAL body: header bytes + non-zero filler.
//   annexB(nals, sc) - join NAL bodies with a start code.
//   lengthPrefixed(nals) - join NAL bodies with 4-byte big-endian lengths.
//   NalUtilsTest - framing, conversion, types, csd, key start.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 02-05: synthetic H.264/H.265 vectors.
// END_CHANGE_SUMMARY

class NalUtilsTest {

    private val sc4 = byteArrayOf(0, 0, 0, 1)

    private fun nal(vararg header: Int, size: Int = 10): ByteArray =
        ByteArray(size) { i -> if (i < header.size) header[i].toByte() else (0x11 + i).toByte() }

    private fun annexB(nals: List<ByteArray>, sc: ByteArray = sc4): ByteArray =
        nals.fold(ByteArray(0)) { acc, n -> acc + sc + n }

    private fun lengthPrefixed(nals: List<ByteArray>): ByteArray = nals.fold(ByteArray(0)) { acc, n ->
        acc + byteArrayOf(0, 0, (n.size shr 8).toByte(), n.size.toByte()) + n
    }

    private val sps264 = nal(0x67, 0x42, 0x00, 0x1F, size = 12)
    private val pps264 = nal(0x68, 0xCE, size = 4)
    private val idr264 = nal(0x65, 0x88, size = 30)
    private val h264 = listOf(sps264, pps264, idr264)

    private val vps265 = nal(0x40, 0x01, size = 14)
    private val sps265 = nal(0x42, 0x01, size = 20)
    private val pps265 = nal(0x44, 0x01, size = 6)
    private val idr265 = nal(0x26, 0x01, size = 30)
    private val h265 = listOf(vps265, sps265, pps265, idr265)

    @Test
    fun `detectFraming recognises 4 and 3 byte start codes`() {
        assertEquals(Framing.ANNEX_B, NalUtils.detectFraming(annexB(h264)))
        assertEquals(Framing.ANNEX_B, NalUtils.detectFraming(annexB(h264, byteArrayOf(0, 0, 1))))
    }

    @Test
    fun `detectFraming recognises length prefixes covering the payload exactly`() {
        val bytes = lengthPrefixed(listOf(nal(0x67, size = 10), nal(0x68, size = 4)))
        assertEquals(0x0A, bytes[3].toInt())
        assertEquals(Framing.LENGTH_PREFIXED, NalUtils.detectFraming(bytes))
        assertEquals(Framing.UNKNOWN, NalUtils.detectFraming(bytes + byteArrayOf(0x55)))
    }

    @Test
    fun `random bytes are unknown`() {
        val random = byteArrayOf(0x12, 0x34, 0x56, 0x78, 0x9A.toByte(), 0x0B, 0x7C, 0x2D, 0x4E)
        assertEquals(Framing.UNKNOWN, NalUtils.detectFraming(random))
        assertEquals(Framing.UNKNOWN, NalUtils.detectFraming(ByteArray(0)))
    }

    @Test
    fun `toAnnexB rewrites length prefixes and keeps Annex-B as is`() {
        assertArrayEquals(annexB(h264), NalUtils.toAnnexB(lengthPrefixed(h264)))
        val already = annexB(h264)
        assertSame(already, NalUtils.toAnnexB(already))
    }

    @Test
    fun `splitAnnexB returns NAL bodies for mixed start codes`() {
        val bytes = byteArrayOf(0, 0, 0, 1) + sps264 + byteArrayOf(0, 0, 1) + pps264
        val ranges = NalUtils.splitAnnexB(bytes)
        assertEquals(listOf(4 until 16, 19 until 23), ranges)
    }

    @Test
    fun `nalTypes for H264 and H265 samples`() {
        assertEquals(listOf(7, 8, 5), NalUtils.nalTypes(annexB(h264), VideoCodec.H264))
        assertEquals(listOf(32, 33, 34, 19), NalUtils.nalTypes(annexB(h265), VideoCodec.H265))
    }

    @Test
    fun `parameterSets H264 gives SPS and PPS with start codes`() {
        val csd = NalUtils.parameterSets(annexB(h264), VideoCodec.H264)
        assertEquals(2, csd.size)
        assertArrayEquals(sc4 + sps264, csd[0])
        assertArrayEquals(sc4 + pps264, csd[1])
    }

    @Test
    fun `parameterSets H265 gives one concatenated VPS SPS PPS`() {
        val csd = NalUtils.parameterSets(annexB(h265), VideoCodec.H265)
        assertEquals(1, csd.size)
        assertArrayEquals(sc4 + vps265 + sc4 + sps265 + sc4 + pps265, csd[0])
    }

    @Test
    fun `missing PPS gives empty csd`() {
        assertTrue(NalUtils.parameterSets(annexB(listOf(sps264, idr264)), VideoCodec.H264).isEmpty())
        assertTrue(NalUtils.parameterSets(annexB(listOf(vps265, sps265, idr265)), VideoCodec.H265).isEmpty())
    }

    @Test
    fun `isKeyStart by first NAL type`() {
        assertTrue(NalUtils.isKeyStart(listOf(7, 8, 5), VideoCodec.H264))
        assertTrue(NalUtils.isKeyStart(listOf(5), VideoCodec.H264))
        assertFalse(NalUtils.isKeyStart(listOf(1), VideoCodec.H264))
        assertFalse(NalUtils.isKeyStart(emptyList(), VideoCodec.H264))
        for (t in listOf(32, 33, 19, 20, 21)) assertTrue(NalUtils.isKeyStart(listOf(t), VideoCodec.H265))
        assertFalse(NalUtils.isKeyStart(listOf(1), VideoCodec.H265))
        assertFalse(NalUtils.isKeyStart(listOf(34), VideoCodec.H265))
    }
}
