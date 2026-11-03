// FILE: AlertSync.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Pure decisions of the feed sync (EVT-06): the 5-minute refresh gate and backward page planning.
//   SCOPE: GateDecision, RefreshGate.decide, PageRange, PageStep, PagePlanner.first / next / gap (MAX_PAGES = 10).
//   DEPENDS: M-EVENT (AlertWire.window, AlertWire.PAGE_LIMIT)
//   LINKS: M-EVENT, V-M-EVENT, EVT-06, AlertSyncService
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.event

import java.time.ZoneId

// START_MODULE_MAP
//   GateDecision - Fetch | TooEarly(minutesAgo).
//   RefreshGate - decide(nowMs, lastFetchMs): fetch unless the last mark is less than 5 minutes old.
//   PageRange - (fromSec, toSec) of one request series.
//   PageStep - Next(toSec) | Done(noProgress) | Capped(gapToSec).
//   PagePlanner - first (newer part), next (after one page), gap (older part left by a capped run).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 05-02: created (RED stub).
// END_CHANGE_SUMMARY

internal sealed interface GateDecision {
    object Fetch : GateDecision
    data class TooEarly(val minutesAgo: Int) : GateDecision
}

internal object RefreshGate {
    const val MIN_INTERVAL_MS = 5 * 60_000L

    fun decide(nowMs: Long, lastFetchMs: Long?): GateDecision = error("RED stub")
}

internal data class PageRange(val fromSec: Long, val toSec: Long)

internal sealed interface PageStep {
    data class Next(val toSec: Long) : PageStep
    data class Done(val noProgress: Boolean) : PageStep
    data class Capped(val gapToSec: Long) : PageStep
}

internal object PagePlanner {
    const val MAX_PAGES = 10

    fun first(nowMs: Long, zone: ZoneId, newestCachedMs: Long?): PageRange = error("RED stub")

    fun next(pageIndex: Int, pageSize: Int, oldestTimeMs: Long?, prevToSec: Long): PageStep = error("RED stub")

    fun gap(nowMs: Long, zone: ZoneId, gapToSec: Long?): PageRange? = error("RED stub")
}
