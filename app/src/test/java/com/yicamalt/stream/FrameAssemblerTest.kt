// FILE: FrameAssemblerTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Executable contract for the keyframe gate, P-frame reorder and useCount generation filter.
//   SCOPE: P before I, in-order and out-of-order P, duplicates, u16 wrap, MAX_PENDING overflow, generations, reset.
//   DEPENDS: M-STREAM-CORE (FrameAssembler, VideoUnit, FrameInfo)
//   LINKS: V-M-STREAM-CORE
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.stream

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// START_MODULE_MAP
//   unit(key, seq, useCount) - scripted VideoUnit; payload is a marker "I<seq>" / "P<seq>".
//   labels(units) - payload markers of emitted units.
//   FrameAssemblerTest - scripted sequences.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 02-05: scripted ordering sequences.
// END_CHANGE_SUMMARY

class FrameAssemblerTest {

    private fun unit(key: Boolean, seq: Int, useCount: Int = 5): VideoUnit {
        val info = FrameInfo(
            codecId = 78, flags = if (key) 1 else 0, liveFlag = 1, onlineNum = 1, useCount = useCount, seq = seq,
            width = 1920, height = 1080, timestampSec = 0, isDay = 1, cover = 0, outLoss = 0, inLoss = 0,
            timestampMs = 0,
        )
        val label = (if (key) "I" else "P") + seq + "u" + useCount
        return VideoUnit(if (key) 2 else 3, info, label.toByteArray(Charsets.US_ASCII))
    }

    private fun i(seq: Int, useCount: Int = 5) = unit(true, seq, useCount)
    private fun p(seq: Int, useCount: Int = 5) = unit(false, seq, useCount)
    private fun labels(units: List<VideoUnit>) = units.map { String(it.payload, Charsets.US_ASCII).substringBefore('u') }

    @Test
    fun `P before any I is dropped`() {
        val a = FrameAssembler()
        assertTrue(a.offer(p(1)).isEmpty())
        assertEquals(1, a.dropped)
        assertEquals(0, a.emitted)
    }

    @Test
    fun `in order, out of order and stale P frames`() {
        val a = FrameAssembler()
        assertEquals(listOf("I10"), labels(a.offer(i(10))))
        assertEquals(listOf("P11"), labels(a.offer(p(11))))
        assertEquals(listOf("P12"), labels(a.offer(p(12))))
        assertTrue(a.offer(p(14)).isEmpty())
        assertEquals(1, a.pending)
        assertEquals(listOf("P13", "P14"), labels(a.offer(p(13))))
        assertEquals(0, a.pending)
        assertTrue(a.offer(p(12)).isEmpty())
        assertEquals(1, a.dropped)
        assertEquals(5, a.emitted)
    }

    @Test
    fun `duplicate future P is dropped`() {
        val a = FrameAssembler()
        a.offer(i(10))
        a.offer(p(13))
        assertTrue(a.offer(p(13)).isEmpty())
        assertEquals(1, a.dropped)
        assertEquals(1, a.pending)
    }

    @Test
    fun `u16 sequence wraps`() {
        val a = FrameAssembler()
        assertEquals(listOf("I65534"), labels(a.offer(i(65534))))
        assertEquals(listOf("P65535"), labels(a.offer(p(65535))))
        assertEquals(listOf("P0"), labels(a.offer(p(0))))
        assertEquals(listOf("P1"), labels(a.offer(p(1))))
        assertEquals(0, a.dropped)
    }

    @Test
    fun `out of order across the wrap`() {
        val a = FrameAssembler()
        a.offer(i(65535))
        assertTrue(a.offer(p(1)).isEmpty())
        assertEquals(listOf("P0", "P1"), labels(a.offer(p(0))))
    }

    @Test
    fun `gap that never fills is bounded by MAX_PENDING dropping the oldest`() {
        val a = FrameAssembler()
        a.offer(i(10))
        for (s in 12 until 12 + FrameAssembler.MAX_PENDING) assertTrue(a.offer(p(s)).isEmpty())
        assertEquals(100, a.pending)
        assertEquals(0, a.dropped)
        assertTrue(a.offer(p(112)).isEmpty())
        assertEquals(100, a.pending)
        assertEquals(1, a.dropped)
        // P12 was the oldest and is gone: P11 releases nothing after itself.
        assertEquals(listOf("P11"), labels(a.offer(p(11))))
    }

    @Test
    fun `new I starts a new generation`() {
        val a = FrameAssembler()
        a.offer(i(10, useCount = 5))
        a.offer(p(11, useCount = 5))
        assertEquals(listOf("I50"), labels(a.offer(i(50, useCount = 6))))
        assertTrue(a.offer(p(51, useCount = 5)).isEmpty())
        assertEquals(1, a.dropped)
        assertEquals(listOf("P51"), labels(a.offer(p(51, useCount = 6))))
    }

    @Test
    fun `I frame discards pending of the previous decode point`() {
        val a = FrameAssembler()
        a.offer(i(10))
        a.offer(p(13))
        a.offer(i(20))
        assertEquals(0, a.pending)
        assertEquals(1, a.dropped)
    }

    @Test
    fun `reset re-arms the keyframe gate`() {
        val a = FrameAssembler()
        a.offer(i(10))
        a.reset()
        assertTrue(a.offer(p(11)).isEmpty())
        assertEquals(1, a.dropped)
        assertEquals(listOf("I12"), labels(a.offer(i(12))))
        assertEquals(listOf("P13"), labels(a.offer(p(13))))
    }
}
