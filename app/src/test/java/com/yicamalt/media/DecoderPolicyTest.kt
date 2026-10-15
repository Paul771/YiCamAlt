// FILE: DecoderPolicyTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Executable contract for the pure decoder rules (queue + key gate, reset streak, csd, pts, status texts).
//   SCOPE: FrameQueue gate/overflow/FIFO/clear, ResetCounter, CsdBuilder AVC/HEVC/empty, PtsGuard, DecoderStatus texts.
//   DEPENDS: M-MEDIA-RENDER (DecoderPolicy), M-STREAM-CORE (DecodableFrame, VideoCodec), RedactionScanner, RedactionFixtures
//   LINKS: V-M-MEDIA-RENDER
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.media

import com.yicamalt.stream.DecodableFrame
import com.yicamalt.stream.VideoCodec
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// START_MODULE_MAP
//   frame(key, pts, codec, csd) - synthetic DecodableFrame (1280x720).
//   DecoderPolicyTest - scenarios per DecoderPolicy export.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 02-08: created with DecoderPolicy.
// END_CHANGE_SUMMARY

class DecoderPolicyTest {

    private val sps = byteArrayOf(0, 0, 0, 1, 0x67, 1, 2)
    private val pps = byteArrayOf(0, 0, 0, 1, 0x68, 3)
    private val hevcSets = byteArrayOf(0, 0, 0, 1, 0x40, 1, 0, 0, 0, 1, 0x42, 1, 0, 0, 0, 1, 0x44, 1)

    private fun frame(
        key: Boolean,
        pts: Long = 0,
        codec: VideoCodec = VideoCodec.H264,
        csd: List<ByteArray> = emptyList(),
    ) = DecodableFrame(codec, key, byteArrayOf(0, 0, 0, 1, if (key) 0x65 else 0x41), csd, pts, 1280, 720)

    // START_BLOCK_FRAME_QUEUE
    @Test
    fun `P-frames before the first key frame are rejected`() {
        val q = FrameQueue()
        assertTrue(q.needsKeyFrame)
        assertEquals(OfferResult.DROPPED_NO_KEY, q.offer(frame(false)))
        assertEquals(0, q.size)
        assertEquals(OfferResult.ACCEPTED, q.offer(frame(true)))
        assertFalse(q.needsKeyFrame)
        assertEquals(OfferResult.ACCEPTED, q.offer(frame(false)))
        assertEquals(2, q.size)
    }

    @Test
    fun `ninth frame drops the oldest and closes the gate until the next key frame`() {
        val q = FrameQueue(capacity = 8)
        val first = frame(true, pts = 0)
        assertEquals(OfferResult.ACCEPTED, q.offer(first))
        for (i in 1..7) assertEquals(OfferResult.ACCEPTED, q.offer(frame(false, pts = i.toLong())))
        assertEquals(8, q.size)
        assertEquals(OfferResult.ACCEPTED_DROPPED_OLDEST, q.offer(frame(false, pts = 8)))
        assertEquals(8, q.size)
        assertTrue(q.needsKeyFrame)
        assertEquals(OfferResult.DROPPED_NO_KEY, q.offer(frame(false, pts = 9)))
        assertEquals(1L, q.poll()!!.ptsUs) // oldest (pts 0) is gone
        assertEquals(OfferResult.ACCEPTED, q.offer(frame(true, pts = 10)))
        assertFalse(q.needsKeyFrame)
    }

    @Test
    fun `poll is FIFO and clear closes the gate`() {
        val q = FrameQueue()
        val a = frame(true, 1)
        val b = frame(false, 2)
        val c = frame(false, 3)
        q.offer(a); q.offer(b); q.offer(c)
        assertSame(a, q.poll())
        assertSame(b, q.poll())
        q.clear()
        assertEquals(0, q.size)
        assertNull(q.poll())
        assertTrue(q.needsKeyFrame)
        assertEquals(OfferResult.DROPPED_NO_KEY, q.offer(frame(false, 4)))
    }
    // END_BLOCK_FRAME_QUEUE

    // START_BLOCK_RESET_COUNTER
    @Test
    fun `three errors in a row exhaust the counter and a render resets the streak`() {
        val r = ResetCounter(max = 3)
        r.onError(); r.onError()
        assertFalse(r.exhausted)
        r.onRendered()
        assertEquals(0, r.resets)
        r.onError(); r.onError()
        assertFalse(r.exhausted)
        assertEquals(3, r.onError())
        assertTrue(r.exhausted)
    }
    // END_BLOCK_RESET_COUNTER

    // START_BLOCK_CSD
    @Test
    fun `AVC csd maps SPS to csd-0 and PPS to csd-1`() {
        val csd = CsdBuilder.csdFor(frame(true, csd = listOf(sps, pps)))
        assertEquals(setOf("csd-0", "csd-1"), csd.keys)
        assertArrayEquals(sps, csd["csd-0"])
        assertArrayEquals(pps, csd["csd-1"])
    }

    @Test
    fun `HEVC csd is a single csd-0`() {
        val csd = CsdBuilder.csdFor(frame(true, codec = VideoCodec.H265, csd = listOf(hevcSets)))
        assertEquals(setOf("csd-0"), csd.keys)
        assertArrayEquals(hevcSets, csd["csd-0"])
    }

    @Test
    fun `empty csd gives an empty map`() {
        assertTrue(CsdBuilder.csdFor(frame(true)).isEmpty())
        assertTrue(CsdBuilder.csdFor(frame(true, codec = VideoCodec.H265)).isEmpty())
    }
    // END_BLOCK_CSD

    // START_BLOCK_PTS
    @Test
    fun `pts never decreases and is strictly increasing`() {
        val g = PtsGuard()
        assertEquals(listOf(100L, 101L, 102L, 200L), listOf(100L, 100L, 90L, 200L).map(g::next))
    }
    // END_BLOCK_PTS

    // START_BLOCK_STATUS_TEXT
    @Test
    fun `status texts are explicit and value-free`() {
        val ready = DecoderStatus.Ready(mime = "video/avc", name = "c2.qti.avc.decoder", hw = true, lowLatency = false)
        assertTrue(ready.userText().contains("c2.qti.avc.decoder"))
        assertTrue(ready.userText().contains("hw=true"))
        assertEquals("H.265: на этом телефоне нет декодера", DecoderStatus.NoDecoder("video/hevc").userText())
        assertEquals("H.264: на этом телефоне нет декодера", DecoderStatus.NoDecoder("video/avc").userText())
        assertEquals("Кодек 79 не поддерживается", DecoderStatus.Unsupported(79).userText())
        assertTrue(DecoderStatus.Failed(3).userText().contains("3"))
        assertTrue(DecoderStatus.Rendering(ready, 25, 12.5).userText().contains("fps=12.5"))
        assertTrue(DecoderStatus.Idle.userText().isNotBlank())
    }
    // END_BLOCK_STATUS_TEXT
}
