// FILE: SdTimeTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Executable contract for the wire-time conversion and local day bounds.
//   SCOPE: toWire / fromWire round trip and wday mapping, WIRE_IS_UTC pin, DST-safe dayBounds, localDate.
//   DEPENDS: M-SD-ARCHIVE (SdTime, STimeDay), SdFixtures
//   LINKS: V-M-SD-ARCHIVE
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.archive

import com.yicamalt.archive.SdFixtures.utc
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

// START_MODULE_MAP
//   HOUR_MS - one hour in ms.
//   lengthMs(range) - length of a half-open day range.
//   SdTimeTest - wire conversion, wday mapping, constant pin, day bounds across DST, local date.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 04-01: P0 wire time (UTC fields) and Pattern 4 day bounds.
// END_CHANGE_SUMMARY

class SdTimeTest {

    private val hourMs = 3_600_000L

    private fun lengthMs(r: LongRange) = r.last + 1 - r.first

    @Test
    fun `STimeDay round trip 2026-10-30T12-34-56Z`() {
        val ms = utc("2026-10-30T12:34:56Z")
        val t = SdTime.toWire(ms, ZoneOffset.UTC)
        assertEquals(STimeDay(2026, 10, 30, 6, 12, 34, 56), t)
        assertEquals(ms, SdTime.fromWire(t, ZoneOffset.UTC))
    }

    @Test
    fun `toWire drops sub-second part`() {
        val t = SdTime.toWire(utc("2026-10-30T12:34:56.789Z"), ZoneOffset.UTC)
        assertEquals(STimeDay(2026, 10, 30, 6, 12, 34, 56), t)
        assertEquals(utc("2026-10-30T12:34:56Z"), SdTime.fromWire(t, ZoneOffset.UTC))
    }

    @Test
    fun `wday Sunday is 1 and Saturday is 7`() {
        assertEquals(1, SdTime.toWire(utc("2026-11-01T10:00:00Z"), ZoneOffset.UTC).wday)
        assertEquals(7, SdTime.toWire(utc("2026-10-31T10:00:00Z"), ZoneOffset.UTC).wday)
    }

    @Test
    fun `WIRE_IS_UTC pinned true`() {
        // Plan 04-04 may flip the constant from device evidence; it must update this test with it.
        assertTrue(SdTime.WIRE_IS_UTC)
        assertEquals(ZoneOffset.UTC, SdTime.wireZone())
    }

    @Test
    fun `default wire zone is UTC fields`() {
        val ms = utc("2026-10-30T22:30:00Z")
        assertEquals(STimeDay(2026, 10, 30, 6, 22, 30, 0), SdTime.toWire(ms))
        assertEquals(ms, SdTime.fromWire(STimeDay(2026, 10, 30, 6, 22, 30, 0)))
    }

    @Test
    fun `fromWire in a non-UTC wire zone`() {
        val moscow = ZoneId.of("Europe/Moscow")
        assertEquals(utc("2026-10-30T09:00:00Z"), SdTime.fromWire(STimeDay(2026, 10, 30, 6, 12, 0, 0), moscow))
    }

    @Test
    fun `dayBounds Moscow normal day`() {
        val zone = ZoneId.of("Europe/Moscow")
        val r = SdTime.dayBounds(LocalDate.of(2026, 10, 30), zone)
        assertEquals(utc("2026-10-29T21:00:00Z"), r.first)
        assertEquals(24 * hourMs, lengthMs(r))
    }

    @Test
    fun `dayBounds New York 2026-03-08 is 23 h`() {
        val r = SdTime.dayBounds(LocalDate.of(2026, 3, 8), ZoneId.of("America/New_York"))
        assertEquals(23 * hourMs, lengthMs(r))
    }

    @Test
    fun `dayBounds New York 2026-11-01 is 25 h`() {
        val r = SdTime.dayBounds(LocalDate.of(2026, 11, 1), ZoneId.of("America/New_York"))
        assertEquals(25 * hourMs, lengthMs(r))
    }

    @Test
    fun `localDate in Moscow crosses midnight`() {
        assertEquals(LocalDate.of(2026, 10, 31), SdTime.localDate(utc("2026-10-30T22:30:00Z"), ZoneId.of("Europe/Moscow")))
        assertEquals(LocalDate.of(2026, 10, 30), SdTime.localDate(utc("2026-10-30T22:30:00Z"), ZoneOffset.UTC))
    }
}
