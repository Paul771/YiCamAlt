// FILE: SdTimeline.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Timeline rules of the SD archive: merged segments, days with recordings, day clipping, seek targets.
//   SCOPE: fromEntries (sort, drop empty, merge gaps <= 2 s), daysWithRecordings, segmentsForDay (DST-safe local day),
//          nextSegmentStart, resolveSeek (research Pattern 5). Zones always injected. Pure, no logging.
//   DEPENDS: SdArchiveCodec (IndexEntry), SdTime, SdTypes (Segment, SeekTarget)
//   LINKS: M-SD-ARCHIVE, V-M-SD-ARCHIVE
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.archive

import java.time.LocalDate
import java.time.ZoneId

// START_MODULE_MAP
//   SdTimeline - object: MERGE_GAP_MS, END_CLAMP_MS, LIVE_THRESHOLD_MS, INDEX_RANGE_MS, fromEntries,
//     daysWithRecordings, segmentsForDay, nextSegmentStart, resolveSeek.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 04-03: timeline and seek rules (research Patterns 4-5).
// END_CHANGE_SUMMARY

object SdTimeline {
    const val MERGE_GAP_MS = 2_000L
    const val END_CLAMP_MS = 6_000L
    const val LIVE_THRESHOLD_MS = 10_000L
    const val INDEX_RANGE_MS = 30L * 24 * 3_600_000

    // START_CONTRACT: fromEntries
    //   PURPOSE: Index entries to merged, sorted segments.
    //   INPUTS: { entries: List<IndexEntry> - raw index (any order); wireZone: ZoneId - zone of the wire STimeDay }
    //   OUTPUTS: { List<Segment> - sorted, duration-0 entries dropped, overlaps and gaps <= MERGE_GAP_MS merged }
    //   SIDE_EFFECTS: none
    //   LINKS: M-SD-ARCHIVE, SdTime.fromWire
    // END_CONTRACT: fromEntries
    fun fromEntries(entries: List<IndexEntry>, wireZone: ZoneId): List<Segment> {
        val runs = entries.asSequence()
            .filter { it.durationS > 0 }
            .map { val s = SdTime.fromWire(it.start, wireZone); Segment(s, s + it.durationS * 1_000L) }
            .sortedBy { it.startMs }
        val out = ArrayList<Segment>()
        for (r in runs) {
            val last = out.lastOrNull()
            if (last != null && r.startMs - last.endMs <= MERGE_GAP_MS) {
                out[out.size - 1] = Segment(last.startMs, maxOf(last.endMs, r.endMs))
            } else {
                out += r
            }
        }
        return out
    }

    // START_CONTRACT: daysWithRecordings
    //   PURPOSE: Local calendar days touched by any segment.
    //   INPUTS: { segments: List<Segment> - merged segments; zone: ZoneId - display zone }
    //   OUTPUTS: { Set<LocalDate> - every day from localDate(start) to localDate(end - 1) per segment }
    //   SIDE_EFFECTS: none
    //   LINKS: M-SD-ARCHIVE, SdTime.localDate
    // END_CONTRACT: daysWithRecordings
    fun daysWithRecordings(segments: List<Segment>, zone: ZoneId): Set<LocalDate> {
        val days = LinkedHashSet<LocalDate>()
        for (s in segments) {
            if (s.endMs <= s.startMs) continue
            var d = SdTime.localDate(s.startMs, zone)
            val last = SdTime.localDate(s.endMs - 1, zone)
            while (!d.isAfter(last)) {
                days += d
                d = d.plusDays(1)
            }
        }
        return days
    }

    // START_CONTRACT: segmentsForDay
    //   PURPOSE: Segments of one local day, clipped to its bounds (23 / 24 / 25 h).
    //   INPUTS: { segments: List<Segment> - merged segments; day: LocalDate - local day; zone: ZoneId - display zone }
    //   OUTPUTS: { List<Segment> - non-empty intersections with [startOfDay, nextStartOfDay) }
    //   SIDE_EFFECTS: none
    //   LINKS: M-SD-ARCHIVE, SdTime.dayBounds
    // END_CONTRACT: segmentsForDay
    fun segmentsForDay(segments: List<Segment>, day: LocalDate, zone: ZoneId): List<Segment> {
        val bounds = SdTime.dayBounds(day, zone)
        val dayStart = bounds.first
        val dayEnd = bounds.last + 1
        return segments.mapNotNull { s ->
            val a = maxOf(s.startMs, dayStart)
            val b = minOf(s.endMs, dayEnd)
            if (a < b) Segment(a, b) else null
        }
    }

    // START_CONTRACT: nextSegmentStart
    //   PURPOSE: Start of the first segment beginning after an instant.
    //   INPUTS: { segments: List<Segment> - merged sorted segments; afterMs: Long - instant }
    //   OUTPUTS: { Long? - first start > afterMs, null when none }
    //   SIDE_EFFECTS: none
    //   LINKS: M-SD-ARCHIVE
    // END_CONTRACT: nextSegmentStart
    fun nextSegmentStart(segments: List<Segment>, afterMs: Long): Long? =
        segments.firstOrNull { it.startMs > afterMs }?.startMs

    // START_CONTRACT: resolveSeek
    //   PURPOSE: Where a seek to a target instant actually goes (research Pattern 5).
    //   INPUTS: { segments: List<Segment> - merged sorted segments; targetMs: Long - requested instant;
    //             wallNowMs: Long - current wall time }
    //   OUTPUTS: { SeekTarget - empty NoRecording; within LIVE_THRESHOLD_MS of now (or later) Live; inside a segment
    //              the target; at / after the last end max(last.start, last.end - END_CLAMP_MS); else next start }
    //   SIDE_EFFECTS: none
    //   LINKS: M-SD-ARCHIVE
    // END_CONTRACT: resolveSeek
    fun resolveSeek(segments: List<Segment>, targetMs: Long, wallNowMs: Long): SeekTarget {
        if (segments.isEmpty()) return SeekTarget.NoRecording
        if (wallNowMs - targetMs <= LIVE_THRESHOLD_MS) return SeekTarget.Live
        if (segments.any { targetMs >= it.startMs && targetMs < it.endMs }) return SeekTarget.Archive(targetMs)
        val last = segments.last()
        if (targetMs >= last.endMs) return SeekTarget.Archive(maxOf(last.startMs, last.endMs - END_CLAMP_MS))
        // Not inside and before the last end: a later segment start always exists.
        return SeekTarget.Archive(nextSegmentStart(segments, targetMs) ?: last.startMs)
    }
}
