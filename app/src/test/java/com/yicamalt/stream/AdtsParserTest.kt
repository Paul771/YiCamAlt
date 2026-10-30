// FILE: AdtsParserTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Executable contract for ADTS header parsing and csd-0 derivation (LIVE-06).
//   SCOPE: AAC-LC 16 kHz mono header, CRC header length, every rejection rule, consecutive frame walk.
//   DEPENDS: M-STREAM-CORE (AdtsParser)
//   LINKS: V-M-STREAM-CORE
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.stream

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

// START_MODULE_MAP
//   header(...) - synthetic ADTS header builder (7 bytes, or 9 with CRC) followed by a zero payload.
//   AdtsParserTest - happy path, csd-0, sample rate, CRC header, rejections, parseAll.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 03-01: synthetic vectors only.
// END_CHANGE_SUMMARY

class AdtsParserTest {

    private fun header(
        payloadLen: Int,
        freqIndex: Int = 8,
        channels: Int = 1,
        profile: Int = 1,
        protectionAbsent: Int = 1,
        layer: Int = 0,
        rawBlocks: Int = 0,
        frameLenOverride: Int? = null,
    ): ByteArray {
        val hdr = if (protectionAbsent == 1) 7 else 9
        val len = frameLenOverride ?: (hdr + payloadLen)
        val h = ByteArray(hdr)
        h[0] = 0xFF.toByte()
        h[1] = (0xF0 or (layer shl 1) or protectionAbsent).toByte()
        h[2] = ((profile shl 6) or (freqIndex shl 2) or (channels shr 2)).toByte()
        h[3] = (((channels and 3) shl 6) or ((len shr 11) and 3)).toByte()
        h[4] = ((len shr 3) and 0xFF).toByte()
        h[5] = (((len and 7) shl 5) or 0x1F).toByte()
        h[6] = (0xFC or rawBlocks).toByte()
        return h + ByteArray(payloadLen)
    }

    @Test
    fun `AAC-LC 16 kHz mono header is recognised`() {
        val b = header(20)
        assertEquals(AdtsFrame(objectType = 2, freqIndex = 8, channels = 1, headerLen = 7, frameLen = 27), AdtsParser.parse(b, 0))
    }

    @Test
    fun `audio specific config of AAC-LC 16 kHz mono is 0x14 0x08`() {
        val f = AdtsParser.parse(header(20), 0)!!
        assertArrayEquals(byteArrayOf(0x14, 0x08), AdtsParser.audioSpecificConfig(f))
    }

    @Test
    fun `sample rate table maps index 8 to 16000 and 3 to 48000`() {
        assertEquals(16000, AdtsParser.sampleRate(8))
        assertEquals(48000, AdtsParser.sampleRate(3))
    }

    @Test
    fun `protection_absent 0 gives a 9 byte header`() {
        val f = AdtsParser.parse(header(20, protectionAbsent = 0), 0)!!
        assertEquals(9, f.headerLen)
        assertEquals(29, f.frameLen)
    }

    @Test
    fun `fewer than 7 bytes is rejected`() {
        assertNull(AdtsParser.parse(header(20).copyOf(6), 0))
        assertNull(AdtsParser.parse(header(0), 1))
    }

    @Test
    fun `bad sync byte is rejected`() {
        val b = header(20)
        b[0] = 0xFE.toByte()
        assertNull(AdtsParser.parse(b, 0))
    }

    @Test
    fun `non zero layer is rejected`() {
        assertNull(AdtsParser.parse(header(20, layer = 1), 0))
    }

    @Test
    fun `frequency index above 12 is rejected`() {
        assertNull(AdtsParser.parse(header(20, freqIndex = 13), 0))
    }

    @Test
    fun `more than one raw data block is rejected`() {
        assertNull(AdtsParser.parse(header(20, rawBlocks = 1), 0))
    }

    @Test
    fun `frame length shorter than header is rejected`() {
        assertNull(AdtsParser.parse(header(20, frameLenOverride = 6), 0))
    }

    @Test
    fun `frame length past the buffer end is rejected`() {
        assertNull(AdtsParser.parse(header(20, frameLenOverride = 28), 0))
    }

    @Test
    fun `parseAll walks two back to back frames`() {
        val b = header(10) + header(30)
        val all = AdtsParser.parseAll(b)
        assertEquals(2, all.size)
        assertEquals(17, all[0].frameLen)
        assertEquals(37, all[1].frameLen)
    }

    @Test
    fun `parseAll stops at the first invalid header`() {
        val b = header(10) + ByteArray(8) + header(10)
        assertEquals(1, AdtsParser.parseAll(b).size)
        assertEquals(0, AdtsParser.parseAll(ByteArray(16)).size)
    }
}
