// FILE: AudioPipelineTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Executable contract for the audio front end: decrypt rule, mute gate (D-05), availability (D-07), stats.
//   SCOPE: synthetic ADTS units, AES-ECB full-block encryption with password + "0", fake clock.
//   DEPENDS: M-STREAM-CORE (AudioPipeline, AdtsParser, StreamTypes)
//   LINKS: V-M-STREAM-CORE
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.stream

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

// START_MODULE_MAP
//   adts(payloadLen, freqIndex, channels) - real 7-byte ADTS header + patterned payload.
//   encryptFullBlocks(plain, password) - test-side AES-ECB over every full 16-byte block, tail plain.
//   unit(payload, codecId, encrypted) - AudioUnit with a synthetic frame head.
//   Captured - recording AacFrameSink.
//   AudioPipelineTest - mute gate, delivery, decrypt, fail-open, codec, availability, garbage, no drop, toString.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 03-01: synthetic vectors only (password is a synthetic test literal).
// END_CHANGE_SUMMARY

class AudioPipelineTest {

    private val pw = "SYNTHpw12345678" // 15 chars -> 16-byte key

    private fun adts(payloadLen: Int, freqIndex: Int = 8, channels: Int = 1): ByteArray {
        val len = 7 + payloadLen
        val h = byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(),
            ((1 shl 6) or (freqIndex shl 2) or (channels shr 2)).toByte(),
            (((channels and 3) shl 6) or ((len shr 11) and 3)).toByte(),
            ((len shr 3) and 0xFF).toByte(),
            (((len and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
        return h + ByteArray(payloadLen) { (it * 3 + 1).toByte() }
    }

    private fun encryptFullBlocks(plain: ByteArray, password: String): ByteArray {
        val k = (password + "0").toByteArray(Charsets.US_ASCII)
        val c = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES")) }
        val out = plain.copyOf()
        val full = plain.size / 16 * 16
        if (full > 0) System.arraycopy(c.doFinal(plain, 0, full), 0, out, 0, full)
        return out
    }

    private fun unit(payload: ByteArray, codecId: Int = 138, encrypted: Boolean = false): AudioUnit =
        AudioUnit(
            channel = 1,
            info = FrameInfo(codecId, 0, 0, 0, 0, 1, 0, 0, 0L, 1, 0, 0, 0, 0L),
            payload = payload,
            encrypted = encrypted,
        )

    private class Captured : AacFrameSink {
        val frames = mutableListOf<Triple<AacConfig, ByteArray, Long>>()
        override fun onAacFrame(config: AacConfig, frame: ByteArray, ptsUs: Long) {
            frames += Triple(config, frame, ptsUs)
        }
    }

    private var now = 0L
    private val clock: () -> Long = { now += 20_000_000L; now }

    @Test
    fun `muted by default counts the unit but delivers nothing`() {
        val sink = Captured()
        val p = AudioPipeline(null, sink, clock)
        assertTrue(p.muted)
        p.onAudioUnit(unit(adts(20)))
        assertEquals(1, p.stats().unitsIn)
        assertEquals(1, p.stats().framesOk)
        assertEquals(0, sink.frames.size)
    }

    @Test
    fun `unmuted delivers raw frames with 16 kHz mono config and increasing pts`() {
        val sink = Captured()
        val p = AudioPipeline(null, sink, clock)
        p.muted = false
        p.onAudioUnit(unit(adts(20) + adts(12)))
        p.onAudioUnit(unit(adts(8)))
        assertEquals(3, sink.frames.size)
        val cfg = sink.frames[0].first
        assertEquals(16000, cfg.sampleRate)
        assertEquals(1, cfg.channels)
        assertEquals(2, cfg.objectType)
        assertArrayEquals(byteArrayOf(0x14, 0x08), cfg.csd0)
        assertArrayEquals(adts(20).copyOfRange(7, 27), sink.frames[0].second)
        assertEquals(12, sink.frames[1].second.size)
        assertTrue(sink.frames[0].third < sink.frames[1].third)
        assertTrue(sink.frames[1].third < sink.frames[2].third)
        assertEquals(16000, p.stats().sampleRate)
        assertEquals(1, p.stats().channels)
    }

    @Test
    fun `pts stays strictly increasing with a frozen clock`() {
        val sink = Captured()
        val p = AudioPipeline(null, sink) { 5_000L }
        p.muted = false
        p.onAudioUnit(unit(adts(4) + adts(4) + adts(4)))
        val pts = sink.frames.map { it.third }
        assertEquals(3, pts.size)
        assertTrue(pts[0] < pts[1] && pts[1] < pts[2])
    }

    @Test
    fun `encrypted unit is decrypted on every full block with tail untouched`() {
        val plain = adts(30) // 37 bytes: two full blocks + 5-byte tail
        assertEquals(37, plain.size)
        val enc = encryptFullBlocks(plain, pw)
        assertFalse(plain.copyOfRange(0, 32).contentEquals(enc.copyOfRange(0, 32)))
        assertArrayEquals(plain.copyOfRange(32, 37), enc.copyOfRange(32, 37))
        val sink = Captured()
        val p = AudioPipeline(pw, sink, clock)
        p.muted = false
        val u = unit(enc, encrypted = true)
        p.onAudioUnit(u)
        assertEquals(1, sink.frames.size)
        assertArrayEquals(plain.copyOfRange(7, 37), sink.frames[0].second)
        assertEquals(true, p.stats().decryptOk)
        assertArrayEquals(encryptFullBlocks(plain, pw), u.payload) // the unit payload is not modified
    }

    @Test
    fun `encrypted flag without a usable key is fail-open`() {
        for (password in listOf(null, "888888")) {
            val sink = Captured()
            val p = AudioPipeline(password, sink, clock)
            p.muted = false
            p.onAudioUnit(unit(adts(30), encrypted = true))
            assertEquals(1, sink.frames.size)
            assertNull(p.stats().decryptOk)
        }
    }

    @Test
    fun `wrong key on an encrypted unit leaves the data unchanged and counts it bad`() {
        val enc = encryptFullBlocks(adts(30), pw)
        val p = AudioPipeline("888888", Captured(), clock)
        p.onAudioUnit(unit(enc, encrypted = true))
        assertEquals(1, p.stats().badUnits)
        assertNull(p.stats().decryptOk)
    }

    @Test
    fun `non AAC codec is counted bad and makes audio unavailable`() {
        val sink = Captured()
        val p = AudioPipeline(null, sink, clock)
        p.muted = false
        p.onAudioUnit(unit(adts(20), codecId = 140))
        assertEquals(1, p.stats().badUnits)
        assertEquals(140, p.stats().codecId)
        assertEquals(0, sink.frames.size)
        assertEquals(false, p.availability(streamingAtMs = 1000, nowMs = 1500))
        val q = AudioPipeline(null, sink, clock)
        q.onAudioUnit(unit(adts(20), codecId = 77))
        assertEquals(false, q.availability(streamingAtMs = 1000, nowMs = 1500))
    }

    @Test
    fun `availability follows the 6 s probe window`() {
        val p = AudioPipeline(null, Captured(), clock)
        assertNull(p.availability(streamingAtMs = null, nowMs = 100_000))
        assertNull(p.availability(streamingAtMs = 1000, nowMs = 6999))
        assertEquals(false, p.availability(streamingAtMs = 1000, nowMs = 7000))
        p.onAudioUnit(unit(adts(20)))
        assertEquals(true, p.availability(streamingAtMs = 1000, nowMs = 1001))
        assertEquals(true, p.availability(streamingAtMs = 1000, nowMs = 1_000_000))
    }

    @Test
    fun `garbage unit is bad and an encrypted one flips decryptOk until a good one`() {
        val p = AudioPipeline(pw, Captured(), clock)
        p.onAudioUnit(unit(ByteArray(40) { 0x11 }))
        assertEquals(1, p.stats().badUnits)
        assertNull(p.stats().decryptOk)
        p.onAudioUnit(unit(ByteArray(40) { 0x22 }, encrypted = true))
        assertEquals(2, p.stats().badUnits)
        assertEquals(false, p.stats().decryptOk)
        p.onAudioUnit(unit(encryptFullBlocks(adts(30), pw), encrypted = true))
        assertEquals(true, p.stats().decryptOk)
        assertEquals(3, p.stats().unitsIn)
    }

    @Test
    fun `no dropping inside the pipeline`() {
        val sink = Captured()
        val p = AudioPipeline(null, sink, clock)
        p.muted = false
        repeat(10) { p.onAudioUnit(unit(adts(16))) }
        assertEquals(10, sink.frames.size)
        assertEquals(10, p.stats().framesOk)
    }

    @Test
    fun `toString of stats and config carries no bytes`() {
        val sink = Captured()
        val p = AudioPipeline(pw, sink, clock)
        p.muted = false
        p.onAudioUnit(unit(encryptFullBlocks(adts(30), pw), encrypted = true))
        val text = p.stats().toString() + sink.frames[0].first.toString()
        assertFalse(text.contains(pw))
        assertFalse(text.contains("[B@"))
        assertFalse(text.contains("csd0=["))
        assertEquals(AacConfig(2, 16000, 1, byteArrayOf(0x14, 0x08)), sink.frames[0].first)
    }
}
