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

    fun fromEntries(entries: List<IndexEntry>, wireZone: ZoneId): List<Segment> = error("RED stub")

    fun daysWithRecordings(segments: List<Segment>, zone: ZoneId): Set<LocalDate> = error("RED stub")

    fun segmentsForDay(segments: List<Segment>, day: LocalDate, zone: ZoneId): List<Segment> = error("RED stub")

    fun nextSegmentStart(segments: List<Segment>, afterMs: Long): Long? = error("RED stub")

    fun resolveSeek(segments: List<Segment>, targetMs: Long, wallNowMs: Long): SeekTarget = error("RED stub")
}
