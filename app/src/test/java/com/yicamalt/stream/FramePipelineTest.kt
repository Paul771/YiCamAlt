// FILE: FramePipelineTest.kt
// VERSION: 0.2.0
// START_MODULE_CONTRACT
//   PURPOSE: Executable contract for VideoUnit -> DecodableFrame (decrypt check, framing, csd, codec mapping, pts, log).
//   SCOPE: encrypted H.264 with right/wrong password, plain mode, H.265, unsupported codec once, length prefixes,
//     monotonic pts, BLOCK_FIRST_KEYFRAME marker and redaction.
//   DEPENDS: M-STREAM-CORE (FramePipeline, NalUtils, StreamTypes), TraceRecorder, RedactionScanner, RedactionFixtures
//   LINKS: V-M-STREAM-CORE
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.stream

import com.yicamalt.test.RedactionFixtures
import com.yicamalt.test.RedactionScanner
import com.yicamalt.test.TraceRecorder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

// START_MODULE_MAP
//   RecordingSink - fake DecodableFrameSink collecting frames and unsupported ids.
//   info(codecId, key, seq) - FrameInfo with 1280x720.
//   nal(...) / annexB(...) / lengthPrefixed(...) - synthetic access units.
//   vendorEncrypt(plain, password) - encryption of [4,20) and [20,36) as the camera does.
//   FramePipelineTest - pipeline scenarios.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.2.0 - GSD plan 04-05: expectGeneration gate passes through the pipeline (dropped counted).
//   PREVIOUS: v0.1.0 - GSD plan 02-05: synthetic pipeline scenarios, trace and redaction checks.
// END_CHANGE_SUMMARY

class FramePipelineTest {

    private class RecordingSink : DecodableFrameSink {
        val frames = mutableListOf<DecodableFrame>()
        val unsupported = mutableListOf<Int>()
        override fun onFrame(frame: DecodableFrame) { frames += frame }
        override fun onUnsupportedCodec(codecId: Int) { unsupported += codecId }
    }

    private val password = "SYNTH1234567890"
    private val recorder = TraceRecorder()
    private val sc4 = byteArrayOf(0, 0, 0, 1)

    @BeforeEach
    fun setUp() { recorder.plant() }

    @AfterEach
    fun tearDown() { recorder.unplant(); recorder.reset() }

    private fun info(codecId: Int, key: Boolean, seq: Int, useCount: Int = 3) = FrameInfo(
        codecId = codecId, flags = if (key) 1 else 0, liveFlag = 1, onlineNum = 1, useCount = useCount, seq = seq,
        width = 1280, height = 720, timestampSec = 0, isDay = 1, cover = 0, outLoss = 0, inLoss = 0, timestampMs = 0,
    )

    private fun nal(vararg header: Int, size: Int): ByteArray =
        ByteArray(size) { i -> if (i < header.size) header[i].toByte() else (0x21 + i % 90).toByte() }

    private fun annexB(vararg nals: ByteArray) = nals.fold(ByteArray(0)) { acc, n -> acc + sc4 + n }

    private fun lengthPrefixed(vararg nals: ByteArray) = nals.fold(ByteArray(0)) { acc, n ->
        acc + byteArrayOf(0, 0, (n.size shr 8).toByte(), n.size.toByte()) + n
    }

    private val sps = nal(0x67, 0x4D, 0x00, 0x28, size = 20)
    private val pps = nal(0x68, 0xCE, 0x3C, 0x80, size = 4)
    private val idr = nal(0x65, 0x88, 0x84, size = 60)
    private val plainI264 = annexB(sps, pps, idr)
    private val p264 = annexB(nal(0x41, 0x9A, size = 30))

    private fun vendorEncrypt(plain: ByteArray, pw: String): ByteArray {
        val key = (pw + "0").toByteArray(Charsets.US_ASCII)
        val c = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }
        val out = plain.copyOf()
        for (off in intArrayOf(4, 20)) System.arraycopy(c.doFinal(plain, off, 16), 0, out, off, 16)
        return out
    }

    @Test
    fun `encrypted H264 I-frame with the right password decodes with csd`() {
        val sink = RecordingSink()
        val pipe = FramePipeline(password, p2pEncrypt = true, sink = sink)
        val enc = vendorEncrypt(plainI264, password)
        pipe.onVideoUnit(VideoUnit(2, info(78, true, 10), enc))

        assertEquals(1, sink.frames.size)
        val f = sink.frames[0]
        assertEquals(VideoCodec.H264, f.codec)
        assertTrue(f.isKeyFrame)
        assertArrayEquals(plainI264, f.annexB)
        assertEquals(2, f.csd.size)
        assertArrayEquals(sc4 + sps, f.csd[0])
        assertArrayEquals(sc4 + pps, f.csd[1])
        assertEquals(1280, f.width)
        assertEquals(720, f.height)
        val s = pipe.stats()
        assertEquals(true, s.decryptOk)
        assertEquals("annexb", s.framing)
        assertEquals(1, s.keyFrames)
        assertEquals(1, s.emitted)
        // The transport buffer is not modified; the pipeline decrypts a copy.
        assertArrayEquals(vendorEncrypt(plainI264, password), enc)
    }

    @Test
    fun `wrong password still forwards the frame but reports decryptOk false`() {
        val sink = RecordingSink()
        val pipe = FramePipeline("WRONG0987654321", p2pEncrypt = true, sink = sink)
        pipe.onVideoUnit(VideoUnit(2, info(78, true, 10), vendorEncrypt(plainI264, password)))
        assertEquals(1, sink.frames.size)
        assertEquals(false, pipe.stats().decryptOk)
    }

    @Test
    fun `plain mode skips decrypt and checks the key NAL`() {
        val sink = RecordingSink()
        val pipe = FramePipeline(password, p2pEncrypt = false, sink = sink)
        pipe.onVideoUnit(VideoUnit(2, info(78, true, 10), plainI264))
        assertArrayEquals(plainI264, sink.frames.single().annexB)
        assertEquals(true, pipe.stats().decryptOk)

        // Encrypted bytes in plain mode are not touched: the check fails.
        val sink2 = RecordingSink()
        val pipe2 = FramePipeline(password, p2pEncrypt = false, sink = sink2)
        val enc = vendorEncrypt(plainI264, password)
        pipe2.onVideoUnit(VideoUnit(2, info(78, true, 10), enc))
        assertArrayEquals(enc, sink2.frames.single().annexB)
        assertEquals(false, pipe2.stats().decryptOk)
    }

    @Test
    fun `H265 sample maps to hevc with one csd buffer`() {
        val vps = nal(0x40, 0x01, size = 24)
        val sps265 = nal(0x42, 0x01, size = 30)
        val pps265 = nal(0x44, 0x01, size = 8)
        val idr265 = nal(0x26, 0x01, size = 50)
        val sink = RecordingSink()
        val pipe = FramePipeline(password, p2pEncrypt = true, sink = sink)
        pipe.onVideoUnit(VideoUnit(2, info(81, true, 1), vendorEncrypt(annexB(vps, sps265, pps265, idr265), password)))
        val f = sink.frames.single()
        assertEquals(VideoCodec.H265, f.codec)
        assertEquals(1, f.csd.size)
        assertArrayEquals(sc4 + vps + sc4 + sps265 + sc4 + pps265, f.csd[0])
        assertEquals(true, pipe.stats().decryptOk)
    }

    @Test
    fun `unsupported codec is signalled exactly once`() {
        val sink = RecordingSink()
        val pipe = FramePipeline(password, p2pEncrypt = true, sink = sink)
        repeat(3) { pipe.onVideoUnit(VideoUnit(2, info(79, true, it), plainI264)) }
        pipe.onVideoUnit(VideoUnit(3, info(79, false, 3), p264))
        assertEquals(listOf(79), sink.unsupported)
        assertTrue(sink.frames.isEmpty())
        val s = pipe.stats()
        assertEquals(79, s.unsupportedCodec)
        assertEquals(4, s.framesIn)
        assertEquals(4, s.dropped)
        assertNull(s.decryptOk)
    }

    @Test
    fun `length prefixed I-frame becomes Annex-B`() {
        val sink = RecordingSink()
        val pipe = FramePipeline(password, p2pEncrypt = false, sink = sink)
        pipe.onVideoUnit(VideoUnit(2, info(78, true, 10), lengthPrefixed(sps, pps, idr)))
        val f = sink.frames.single()
        assertArrayEquals(sc4, f.annexB.copyOfRange(0, 4))
        assertArrayEquals(plainI264, f.annexB)
        assertEquals(2, f.csd.size)
        assertEquals("length", pipe.stats().framing)
    }

    @Test
    fun `P frames follow the key frame, reuse csd and keep pts strictly increasing`() {
        val sink = RecordingSink()
        val pipe = FramePipeline(password, p2pEncrypt = true, sink = sink, nanoTime = { 5_000_000_000L })
        pipe.onVideoUnit(VideoUnit(3, info(78, false, 9), p264))
        pipe.onVideoUnit(VideoUnit(2, info(78, true, 10), vendorEncrypt(plainI264, password)))
        pipe.onVideoUnit(VideoUnit(3, info(78, false, 11), p264))
        pipe.onVideoUnit(VideoUnit(3, info(78, false, 12), p264))
        assertEquals(listOf(true, false, false), sink.frames.map { it.isKeyFrame })
        assertEquals(listOf(0L, 1L, 2L), sink.frames.map { it.ptsUs })
        assertArrayEquals(p264, sink.frames[1].annexB)
        assertEquals(2, sink.frames[2].csd.size)
        assertEquals(1, pipe.stats().dropped)
    }

    @Test
    fun `pts follows the clock when it advances`() {
        var now = 1_000_000L
        val sink = RecordingSink()
        val pipe = FramePipeline(null, p2pEncrypt = false, sink = sink, nanoTime = { now })
        pipe.onVideoUnit(VideoUnit(2, info(78, true, 10), plainI264))
        now += 40_000_000L
        pipe.onVideoUnit(VideoUnit(3, info(78, false, 11), p264))
        assertEquals(listOf(0L, 40_000L), sink.frames.map { it.ptsUs })
    }

    @Test
    fun `reset re-arms the gate`() {
        val sink = RecordingSink()
        val pipe = FramePipeline(null, p2pEncrypt = false, sink = sink)
        pipe.onVideoUnit(VideoUnit(2, info(78, true, 10), plainI264))
        pipe.reset()
        pipe.onVideoUnit(VideoUnit(3, info(78, false, 11), p264))
        assertEquals(1, sink.frames.size)
        assertFalse(sink.frames.any { !it.isKeyFrame })
    }

    @Test
    fun `expectGeneration passes through and is synchronized`() {
        val sink = RecordingSink()
        val pipe = FramePipeline(null, p2pEncrypt = false, sink = sink)
        pipe.expectGeneration(4)
        pipe.onVideoUnit(VideoUnit(2, info(78, true, 10, useCount = 3), plainI264))
        assertTrue(sink.frames.isEmpty())
        assertEquals(1, pipe.stats().dropped)
        pipe.onVideoUnit(VideoUnit(2, info(78, true, 11, useCount = 4), plainI264))
        assertEquals(1, sink.frames.size)
        pipe.expectGeneration(null)
        pipe.onVideoUnit(VideoUnit(2, info(78, true, 12, useCount = 3), plainI264))
        assertEquals(2, sink.frames.size)
    }

    @Test
    fun `first keyframe marker appears once and the log is clean`() {
        val sink = RecordingSink()
        val pipe = FramePipeline(password, p2pEncrypt = true, sink = sink)
        pipe.onVideoUnit(VideoUnit(2, info(78, true, 10), vendorEncrypt(plainI264, password)))
        pipe.onVideoUnit(VideoUnit(3, info(78, false, 11), p264))
        pipe.onVideoUnit(VideoUnit(2, info(78, true, 12), vendorEncrypt(plainI264, password)))

        val lines = recorder.all().filter { it.message.contains("[BLOCK_FIRST_KEYFRAME]") }
        assertEquals(1, lines.size)
        assertEquals(
            "[Stream][pipeline][BLOCK_FIRST_KEYFRAME] codec=H264 framing=annexb nals=7,8,5 decryptOk=true size=1280x720",
            lines[0].message,
        )
        RedactionScanner().assertClean(
            recorder.bufferText(),
            extraLiterals = RedactionFixtures.ALL_SECRETS + password,
        )
    }
}
