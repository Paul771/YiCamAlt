// FILE: AlertSyncTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Pure decisions of the feed sync: 5-minute gate and backward page planning with the 10-page cap.
//   SCOPE: RefreshGate.decide, PagePlanner.first / next / gap. No Android, no I/O.
//   DEPENDS: M-EVENT (AlertSync, AlertWire)
//   LINKS: V-M-EVENT, EVT-06
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.event

// START_MODULE_MAP
//   NOW / ZONE - fixed clock and zone of the planner cases.
//   AlertSyncTest - gate (4 cases), planner first / incremental (3), next steps (5), gap resume (2).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 05-02: created (RED first).
// END_CHANGE_SUMMARY

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.ZoneId

class AlertSyncTest {

    private val now = 1_760_000_000_000L
    private val zone: ZoneId = ZoneId.of("UTC")
    private val window = AlertWire.window(now, zone)

    @Test
    fun `gate fetches when never fetched`() {
        assertEquals(GateDecision.Fetch, RefreshGate.decide(now, null))
    }

    @Test
    fun `gate refuses within 5 minutes and reports minutes ago`() {
        assertEquals(GateDecision.TooEarly(4), RefreshGate.decide(now, now - (4 * 60_000L + 59_000L)))
        assertEquals(GateDecision.TooEarly(0), RefreshGate.decide(now, now - 1_000L))
        assertEquals(GateDecision.Fetch, RefreshGate.decide(now, now - 5 * 60_000L))
    }

    @Test
    fun `gate treats a future last fetch as fetch allowed`() {
        assertEquals(GateDecision.Fetch, RefreshGate.decide(now, now + 60_000L))
    }

    @Test
    fun `gate interval is five minutes`() {
        assertEquals(300_000L, RefreshGate.MIN_INTERVAL_MS)
    }

    @Test
    fun `planner first page uses the window`() {
        assertEquals(PageRange(window.first, window.second), PagePlanner.first(now, zone, newestCachedMs = null))
    }

    @Test
    fun `incremental refresh starts at newest cached minus 5 s floored at the window`() {
        val newest = now - 3_600_000L
        assertEquals(PageRange((newest - 5_000L) / 1000, window.second), PagePlanner.first(now, zone, newest))
        val ancient = now - 40L * 86_400_000L
        assertEquals(PageRange(window.first, window.second), PagePlanner.first(now, zone, ancient))
    }

    @Test
    fun `next page uses oldest time in seconds as to`() {
        val oldest = now - 99_500L
        assertEquals(PageStep.Next(oldest / 1000), PagePlanner.next(1, 100, oldest, window.second))
    }

    @Test
    fun `short page stops`() {
        assertEquals(PageStep.Done(noProgress = false), PagePlanner.next(1, 37, now - 1_000L, window.second))
        assertEquals(PageStep.Done(noProgress = false), PagePlanner.next(3, 0, null, window.second))
    }

    @Test
    fun `no progress stops`() {
        val prevTo = now / 1000
        assertEquals(PageStep.Done(noProgress = true), PagePlanner.next(2, 100, now, prevTo))
        assertEquals(PageStep.Done(noProgress = true), PagePlanner.next(2, 100, now + 5_000L, prevTo))
    }

    @Test
    fun `full page without usable records stops without progress`() {
        assertEquals(PageStep.Done(noProgress = true), PagePlanner.next(1, 100, null, window.second))
    }

    @Test
    fun `ten pages cap keeps the gap boundary`() {
        val oldest = now - 900_000L
        assertEquals(PageStep.Capped(gapToSec = oldest / 1000), PagePlanner.next(10, 100, oldest, window.second))
        assertEquals(PageStep.Next(oldest / 1000), PagePlanner.next(9, 100, oldest, window.second))
        assertEquals(10, PagePlanner.MAX_PAGES)
    }

    @Test
    fun `a saved gap boundary continues from it on the next refresh after the newer part is done`() {
        val gap = (now - 2 * 86_400_000L) / 1000
        assertEquals(PageRange(window.first, gap), PagePlanner.gap(now, zone, gap))
        assertNull(PagePlanner.gap(now, zone, null))
    }

    @Test
    fun `a gap boundary older than the window is dropped`() {
        assertNull(PagePlanner.gap(now, zone, window.first))
        assertNull(PagePlanner.gap(now, zone, window.first - 100))
    }
}
