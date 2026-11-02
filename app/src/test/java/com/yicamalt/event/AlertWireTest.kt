// FILE: AlertWireTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Pin the v2/alert/list signed parameter order, the canonical string and the time windows.
//   SCOPE: signedExtra order with empty values, canonical vector with dummy values, 30-day window with
//     vendor floor, refresh window of plus minus 5 seconds.
//   DEPENDS: M-EVENT (AlertWire), M-HTTP (YiRequestSigner)
//   LINKS: V-M-EVENT, EVT-01, EVT-06
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.event

import com.yicamalt.network.YiRequestSigner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId

// START_MODULE_MAP
//   AlertWireTest - signedExtra, canonical vector, window and refreshWindow scenarios.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 05-00: initial AlertWire scenarios (test-first).
// END_CHANGE_SUMMARY

class AlertWireTest {

    @Test
    fun `signed extra keeps vendor order and empty values`() {
        assertEquals(
            listOf(
                "type" to "", "sub_type" to "", "from" to "1700000000", "to" to "1700086400",
                "limit" to "100", "fromDB" to "true", "expires" to "10",
            ),
            AlertWire.signedExtra(1700000000L, 1700086400L, 100),
        )
    }

    @Test
    fun `canonical string matches the vendor vector`() {
        assertEquals(
            "seq=0&userid=U1&type=&sub_type=&from=1700000000&to=1700086400&limit=20&fromDB=true&expires=10",
            YiRequestSigner.canonicalMessage("U1", AlertWire.SEQ, AlertWire.signedExtra(1700000000L, 1700086400L, 20)),
        )
        assertEquals("v2/alert/list", AlertWire.LIST_PATH)
        assertEquals("20000", AlertWire.SUCCESS)
    }

    @Test
    fun `window clamps from to 30 days and to the vendor floor`() {
        val zone = ZoneId.of("Europe/Moscow")
        val nowMs = 1_760_000_000_000L
        val (from, to) = AlertWire.window(nowMs, zone)
        val today = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        assertEquals(today.plusDays(1).atStartOfDay(zone).toEpochSecond(), to)
        assertEquals(nowMs / 1000 - 30L * 86_400 + 60, from)
        assertTrue(from < to)
        assertEquals(10, from.toString().length)
        assertEquals(10, to.toString().length)

        // Just after the floor: from is clamped to floor + 1.
        val early = LocalDate.of(2024, 1, 5).atStartOfDay(zone).toInstant().toEpochMilli()
        val (fromEarly, toEarly) = AlertWire.window(early, zone)
        assertEquals(AlertWire.FROM_FLOOR_SEC + 1, fromEarly)
        assertTrue(fromEarly < toEarly)
    }

    @Test
    fun `refresh window is plus minus 5 seconds`() {
        val t = 1_760_000_123_456L
        assertEquals((t - 5000) / 1000 to (t + 5000) / 1000, AlertWire.refreshWindow(t))
    }
}
