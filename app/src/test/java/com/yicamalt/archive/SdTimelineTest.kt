// FILE: SdTimelineTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Executable contract for the archive timeline: segment merge, days with recordings, day clipping, seek rules.
//   SCOPE: fromEntries merge / drop / sort, midnight crossing, DST 23 / 25 h days, nextSegmentStart, resolveSeek.
//   DEPENDS: M-SD-ARCHIVE (SdTimeline, SdTime, Segment, SeekTarget), SdFixtures
//   LINKS: V-M-SD-ARCHIVE
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.archive

import com.yicamalt.archive.SdFixtures.utc
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

// START_MODULE_MAP
//   entry(iso, durationS) - IndexEntry in the UTC wire zone.
//   seg(fromIso, toIso) - Segment from two ISO instants.
//   SdTimelineTest - merge, days, clipping, DST, next segment, seek target rules.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 04-03: research Pattern 4 / 5 timeline and seek rules.
// END_CHANGE_SUMMARY

class SdTimelineTest {

    private val wire: ZoneId = ZoneOffset.UTC
    private val moscow: ZoneId = ZoneId.of("Europe/Moscow")
    private val newYork: ZoneId = ZoneId.of("America/New_York")
    private val hourMs = 3_600_000L

    private fun entry(iso: String, durationS: Int) = IndexEntry(SdTime.toWire(utc(iso), wire), durationS)
    private fun seg(from: String, to: String) = Segment(utc(from), utc(to))

    @Test
    fun `fromEntries merges overlapping and adjacent runs`() {
        val segments = SdTimeline.fromEntries(
            listOf(
                entry("2026-10-30T10:00:00Z", 600),
                entry("2026-10-30T10:10:01Z", 60), // gap 1 s < 2 s
                entry("2026-10-30T11:00:00Z", 60),
            ),
            wire,
        )
        assertEquals(
            listOf(seg("2026-10-30T10:00:00Z", "2026-10-30T10:11:01Z"), seg("2026-10-30T11:00:00Z", "2026-10-30T11:01:00Z")),
            segments,
        )
    }

    @Test
    fun `fromEntries merges overlap without shrinking`() {
        val segments = SdTimeline.fromEntries(
            listOf(entry("2026-10-30T10:00:00Z", 600), entry("2026-10-30T10:05:00Z", 60)),
            wire,
        )
        assertEquals(listOf(seg("2026-10-30T10:00:00Z", "2026-10-30T10:10:00Z")), segments)
    }

    @Test
    fun `fromEntries keeps a 3 s gap as two segments`() {
        val segments = SdTimeline.fromEntries(
            listOf(entry("2026-10-30T10:00:00Z", 60), entry("2026-10-30T10:01:03Z", 60)),
            wire,
        )
        assertEquals(2, segments.size)
    }

    @Test
    fun `fromEntries drops zero duration and sorts input`() {
        val segments = SdTimeline.fromEntries(
            listOf(
                entry("2026-10-30T11:00:00Z", 60),
                entry("2026-10-30T09:00:00Z", 0),
                entry("2026-10-30T10:00:00Z", 60),
            ),
            wire,
        )
        assertEquals(
            listOf(seg("2026-10-30T10:00:00Z", "2026-10-30T10:01:00Z"), seg("2026-10-30T11:00:00Z", "2026-10-30T11:01:00Z")),
            segments,
        )
    }

    @Test
    fun `segment crossing local midnight marks two days`() {
        val segments = listOf(seg("2026-10-30T20:50:00Z", "2026-10-30T21:10:00Z")) // 23:50..00:10 Moscow
        assertEquals(
            setOf(LocalDate.of(2026, 10, 30), LocalDate.of(2026, 10, 31)),
            SdTimeline.daysWithRecordings(segments, moscow),
        )
        assertEquals(
            listOf(seg("2026-10-30T21:00:00Z", "2026-10-30T21:10:00Z")),
            SdTimeline.segmentsForDay(segments, LocalDate.of(2026, 10, 31), moscow),
        )
        assertEquals(
            listOf(seg("2026-10-30T20:50:00Z", "2026-10-30T21:00:00Z")),
            SdTimeline.segmentsForDay(segments, LocalDate.of(2026, 10, 30), moscow),
        )
    }

    @Test
    fun `segment ending exactly at midnight marks one day`() {
        val segments = listOf(seg("2026-10-30T20:50:00Z", "2026-10-30T21:00:00Z")) // ends at 00:00 Moscow
        assertEquals(setOf(LocalDate.of(2026, 10, 30)), SdTimeline.daysWithRecordings(segments, moscow))
        assertTrue(SdTimeline.segmentsForDay(segments, LocalDate.of(2026, 10, 31), moscow).isEmpty())
    }

    @Test
    fun `DST fall back day is 25 h`() {
        val segments = listOf(seg("2026-10-31T00:00:00Z", "2026-11-03T00:00:00Z"))
        val day = SdTimeline.segmentsForDay(segments, LocalDate.of(2026, 11, 1), newYork)
        assertEquals(1, day.size)
        assertEquals(25 * hourMs, day[0].endMs - day[0].startMs)
    }

    @Test
    fun `DST spring forward day is 23 h`() {
        val segments = listOf(seg("2026-03-07T00:00:00Z", "2026-03-10T00:00:00Z"))
        val day = SdTimeline.segmentsForDay(segments, LocalDate.of(2026, 3, 8), newYork)
        assertEquals(1, day.size)
        assertEquals(23 * hourMs, day[0].endMs - day[0].startMs)
    }

    @Test
    fun `no segments means no days`() {
        assertTrue(SdTimeline.daysWithRecordings(emptyList(), moscow).isEmpty())
    }

    @Test
    fun `nextSegmentStart in a gap`() {
        val segments = listOf(seg("2026-10-30T10:00:00Z", "2026-10-30T10:20:00Z"), seg("2026-10-30T11:00:00Z", "2026-10-30T11:30:00Z"))
        assertEquals(utc("2026-10-30T11:00:00Z"), SdTimeline.nextSegmentStart(segments, utc("2026-10-30T10:30:00Z")))
    }

    @Test
    fun `nextSegmentStart after the last segment is null`() {
        val segments = listOf(seg("2026-10-30T10:00:00Z", "2026-10-30T10:20:00Z"), seg("2026-10-30T11:00:00Z", "2026-10-30T11:30:00Z"))
        assertNull(SdTimeline.nextSegmentStart(segments, utc("2026-10-30T11:40:00Z")))
    }

    private val now = utc("2026-10-30T12:00:00Z")
    private val seekSegments = listOf(
        seg("2026-10-30T08:00:00Z", "2026-10-30T09:00:00Z"),
        seg("2026-10-30T10:00:00Z", "2026-10-30T11:50:00Z"),
    )

    @Test
    fun `resolveSeek inside a segment is the target`() {
        assertEquals(
            SeekTarget.Archive(utc("2026-10-30T08:30:00Z")),
            SdTimeline.resolveSeek(seekSegments, utc("2026-10-30T08:30:00Z"), now),
        )
    }

    @Test
    fun `resolveSeek in a gap goes to the next start`() {
        assertEquals(
            SeekTarget.Archive(utc("2026-10-30T10:00:00Z")),
            SdTimeline.resolveSeek(seekSegments, utc("2026-10-30T09:30:00Z"), now),
        )
    }

    @Test
    fun `resolveSeek before the first segment goes to the first start`() {
        assertEquals(
            SeekTarget.Archive(utc("2026-10-30T08:00:00Z")),
            SdTimeline.resolveSeek(seekSegments, utc("2026-10-30T07:00:00Z"), now),
        )
    }

    @Test
    fun `resolveSeek after the last end clamps to end minus 6 s`() {
        assertEquals(
            SeekTarget.Archive(utc("2026-10-30T11:49:54Z")),
            SdTimeline.resolveSeek(seekSegments, utc("2026-10-30T11:55:00Z"), now),
        )
    }

    @Test
    fun `resolveSeek clamp never goes before the last segment start`() {
        val short = listOf(seg("2026-10-30T10:00:00Z", "2026-10-30T10:00:03Z"))
        assertEquals(
            SeekTarget.Archive(utc("2026-10-30T10:00:00Z")),
            SdTimeline.resolveSeek(short, utc("2026-10-30T10:30:00Z"), now),
        )
    }

    @Test
    fun `resolveSeek within 10 s of now is Live`() {
        assertEquals(SeekTarget.Live, SdTimeline.resolveSeek(seekSegments, utc("2026-10-30T11:59:55Z"), now))
        assertEquals(SeekTarget.Live, SdTimeline.resolveSeek(seekSegments, utc("2026-10-30T12:00:30Z"), now))
    }

    @Test
    fun `resolveSeek with no segments is NoRecording`() {
        assertEquals(SeekTarget.NoRecording, SdTimeline.resolveSeek(emptyList(), utc("2026-10-30T08:30:00Z"), now))
    }
}
