// FILE: TimelineMathTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Executable contract for the archive timeline ruler math (SD-03, D-03).
//   SCOPE: span clamp / default, zoom, pan, x mapping, visible segments, tick step and labels, initial centre,
//     release-to-seek rule.
//   DEPENDS: M-UI-LIVEVIEW (TimelineMath), M-SD-ARCHIVE (Segment)
//   LINKS: V-M-UI-LIVEVIEW
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.ui.live

import com.yicamalt.archive.Segment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

// START_MODULE_MAP
//   TimelineMathTest - one test per behaviour bullet of plan 04-07 Task 1 (TimelineMath).
//   msk / at - Moscow zone and local-time helper for 2026-10-30.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 04-07: timeline math vectors.
// END_CHANGE_SUMMARY

class TimelineMathTest {

    private val msk = ZoneId.of("Europe/Moscow")
    private val day = LocalDate.of(2026, 10, 30)
    private val dayStart = day.atStartOfDay(msk).toInstant().toEpochMilli()
    private val dayEnd = day.plusDays(1).atStartOfDay(msk).toInstant().toEpochMilli()
    private fun at(h: Int, m: Int = 0): Long = day.atTime(LocalTime.of(h, m)).atZone(msk).toInstant().toEpochMilli()

    private val hour = 3_600_000L
    private val minute = 60_000L

    @Test
    fun `default span is 3 h and span is clamped to 15 min - 24 h`() {
        assertEquals(3 * hour, TimelineMath.DEFAULT_SPAN_MS)
        assertEquals(15 * minute, TimelineMath.clampSpan(minute))
        assertEquals(24 * hour, TimelineMath.clampSpan(30 * hour))
        assertEquals(2 * hour, TimelineMath.clampSpan(2 * hour))
    }

    @Test
    fun `zoom factor 2 halves the span and is clamped`() {
        assertEquals(90 * minute, TimelineMath.zoom(3 * hour, 2f))
        assertEquals(6 * hour, TimelineMath.zoom(3 * hour, 0.5f))
        assertEquals(15 * minute, TimelineMath.zoom(20 * minute, 4f))
        assertEquals(24 * hour, TimelineMath.zoom(20 * hour, 0.1f))
    }

    @Test
    fun `xOf centre is half width and the right edge is width`() {
        val c = at(12)
        assertEquals(500f, TimelineMath.xOf(c, c, 3 * hour, 1000f), 0.01f)
        assertEquals(1000f, TimelineMath.xOf(c + 90 * minute, c, 3 * hour, 1000f), 0.01f)
        assertEquals(0f, TimelineMath.xOf(c - 90 * minute, c, 3 * hour, 1000f), 0.01f)
    }

    @Test
    fun `pan right moves to earlier time`() {
        val c = at(12)
        assertEquals(c - 18 * minute, TimelineMath.pan(c, 100f, 1000f, 3 * hour, dayStart, dayEnd))
        assertEquals(c + 18 * minute, TimelineMath.pan(c, -100f, 1000f, 3 * hour, dayStart, dayEnd))
    }

    @Test
    fun `pan is clamped to the day`() {
        assertEquals(dayStart, TimelineMath.pan(at(0, 10), 1000f, 1000f, 3 * hour, dayStart, dayEnd))
        assertEquals(dayEnd, TimelineMath.pan(at(23, 50), -1000f, 1000f, 3 * hour, dayStart, dayEnd))
    }

    @Test
    fun `visibleSegments keeps only segments overlapping the window`() {
        val inside = Segment(at(11), at(11, 30))
        val leftOverlap = Segment(at(10), at(10, 45))
        val before = Segment(at(8), at(9))
        val after = Segment(at(14), at(15))
        val visible = TimelineMath.visibleSegments(listOf(before, leftOverlap, inside, after), at(12), 3 * hour)
        assertEquals(listOf(leftOverlap, inside), visible)
    }

    @Test
    fun `tick step depends on the span`() {
        assertEquals(10 * minute, TimelineMath.tickStepMs(hour))
        assertEquals(10 * minute, TimelineMath.tickStepMs(15 * minute))
        assertEquals(hour, TimelineMath.tickStepMs(3 * hour))
        assertEquals(hour, TimelineMath.tickStepMs(6 * hour))
        assertEquals(3 * hour, TimelineMath.tickStepMs(12 * hour))
    }

    @Test
    fun `ticks are local hours inside the window`() {
        val ticks = TimelineMath.ticks(at(12), 3 * hour, dayStart, dayEnd, msk)
        assertEquals(listOf("11:00", "12:00", "13:00"), ticks.map { it.label })
        assertEquals(listOf(at(11), at(12), at(13)), ticks.map { it.ms })
    }

    @Test
    fun `ticks stay inside the day`() {
        val ticks = TimelineMath.ticks(dayStart, 3 * hour, dayStart, dayEnd, msk)
        assertEquals(listOf("00:00", "01:00"), ticks.map { it.label })
    }

    @Test
    fun `initialCenter for today is now, capped at the day end`() {
        assertEquals(at(15), TimelineMath.initialCenter(true, at(15), dayStart, dayEnd, emptyList()))
        assertEquals(dayEnd, TimelineMath.initialCenter(true, dayEnd + hour, dayStart, dayEnd, emptyList()))
    }

    @Test
    fun `initialCenter for a past day is the last segment end or noon`() {
        val segs = listOf(Segment(at(8), at(9)), Segment(at(17), at(18, 30)))
        assertEquals(at(18, 30), TimelineMath.initialCenter(false, at(23) + 24 * hour, dayStart, dayEnd, segs))
        assertEquals(dayStart + 12 * hour, TimelineMath.initialCenter(false, at(23) + 24 * hour, dayStart, dayEnd, emptyList()))
    }

    @Test
    fun `pure pinch zoom never seeks`() {
        assertFalse(TimelineMath.shouldSeekAfterGesture(at(12), at(12), 10_800f))
    }

    @Test
    fun `tiny pan does not seek`() {
        // 3 px at 10.8 s/px is below the 4 px threshold.
        assertFalse(TimelineMath.shouldSeekAfterGesture(at(12), at(12) + 3 * 10_800L, 10_800f))
        // 15 px at 100 ms/px = 1.5 s is below the 2 s threshold.
        assertFalse(TimelineMath.shouldSeekAfterGesture(at(12), at(12) - 1_500L, 100f))
    }

    @Test
    fun `real pan seeks`() {
        assertTrue(TimelineMath.shouldSeekAfterGesture(at(12), at(12) + 5 * 10_800L, 10_800f))
        assertTrue(TimelineMath.shouldSeekAfterGesture(at(12), at(12) - 2_500L, 100f))
    }
}
