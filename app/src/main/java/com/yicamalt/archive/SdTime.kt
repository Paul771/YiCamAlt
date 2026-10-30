// FILE: SdTime.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Conversion between epoch milliseconds and the wire STimeDay, plus local day bounds for the timeline.
//   SCOPE: wire zone (UTC per 04-RESEARCH P0 for gw-us), toWire / fromWire, DST-safe day bounds, local date of an
//          instant. Pure java.time, no logging.
//   DEPENDS: SdArchiveCodec (STimeDay)
//   LINKS: M-SD-ARCHIVE, V-M-SD-ARCHIVE
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.archive

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

// START_MODULE_MAP
//   SdTime - object: WIRE_IS_UTC seam, wireZone, toWire, fromWire, dayBounds, localDate.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 04-01: wire time per 04-RESEARCH §P0 (UTC fields for gw-us; WIRE_IS_UTC is the
//     single seam), DST-safe day bounds via atStartOfDay.
// END_CHANGE_SUMMARY

object SdTime {
    // Wire time is UTC broken-down time (04-RESEARCH P0, U = 0 outside CN/JP). Plan 04-04 may flip it from device evidence.
    const val WIRE_IS_UTC = true

    // START_CONTRACT: wireZone
    //   PURPOSE: Zone in which STimeDay fields are broken down.
    //   INPUTS: none
    //   OUTPUTS: { ZoneId - ZoneOffset.UTC when WIRE_IS_UTC else ZoneId.systemDefault() }
    //   SIDE_EFFECTS: none
    //   LINKS: M-SD-ARCHIVE
    // END_CONTRACT: wireZone
    fun wireZone(): ZoneId = if (WIRE_IS_UTC) ZoneOffset.UTC else ZoneId.systemDefault()

    // START_CONTRACT: toWire
    //   PURPOSE: Epoch ms to wire STimeDay (wday Sunday = 1 .. Saturday = 7).
    //   INPUTS: { epochMs: Long - instant; wireZone: ZoneId - break-down zone }
    //   OUTPUTS: { STimeDay - broken-down time, sub-second part dropped }
    //   SIDE_EFFECTS: none
    //   LINKS: M-SD-ARCHIVE
    // END_CONTRACT: toWire
    fun toWire(epochMs: Long, wireZone: ZoneId = wireZone()): STimeDay {
        val z = Instant.ofEpochMilli(epochMs).atZone(wireZone)
        return STimeDay(
            year = z.year,
            month = z.monthValue,
            day = z.dayOfMonth,
            wday = z.dayOfWeek.value % 7 + 1, // java.util.Calendar order: Sunday = 1 .. Saturday = 7
            hour = z.hour,
            minute = z.minute,
            second = z.second,
        )
    }

    // START_CONTRACT: fromWire
    //   PURPOSE: Wire STimeDay to epoch ms (wday ignored).
    //   INPUTS: { t: STimeDay - wire time; wireZone: ZoneId - break-down zone }
    //   OUTPUTS: { Long - epoch ms }
    //   SIDE_EFFECTS: none
    //   LINKS: M-SD-ARCHIVE
    // END_CONTRACT: fromWire
    fun fromWire(t: STimeDay, wireZone: ZoneId = wireZone()): Long =
        LocalDateTime.of(t.year, t.month, t.day, t.hour, t.minute, t.second)
            .atZone(wireZone).toInstant().toEpochMilli()

    // START_CONTRACT: dayBounds
    //   PURPOSE: Epoch-ms range of one calendar day in a zone (23 / 24 / 25 h across DST).
    //   INPUTS: { day: LocalDate - calendar day; zone: ZoneId - display zone }
    //   OUTPUTS: { LongRange - [startOfDay, nextStartOfDay) }
    //   SIDE_EFFECTS: none
    //   LINKS: M-SD-ARCHIVE
    // END_CONTRACT: dayBounds
    fun dayBounds(day: LocalDate, zone: ZoneId): LongRange {
        // Calendar arithmetic, never a fixed day length: DST days are 23 or 25 h.
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val next = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start until next
    }

    // START_CONTRACT: localDate
    //   PURPOSE: Calendar day of an instant in a zone.
    //   INPUTS: { epochMs: Long - instant; zone: ZoneId - display zone }
    //   OUTPUTS: { LocalDate - day }
    //   SIDE_EFFECTS: none
    //   LINKS: M-SD-ARCHIVE
    // END_CONTRACT: localDate
    fun localDate(epochMs: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
}
