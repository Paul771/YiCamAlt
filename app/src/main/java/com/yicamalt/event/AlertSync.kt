// FILE: AlertSync.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Pure decisions of the feed sync (EVT-06): the 5-minute refresh gate and backward page planning.
//   SCOPE: GateDecision, RefreshGate.decide, PageRange, PageStep, PagePlanner.first / next / gap (page cap MAX_PAGES, ten).
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
//   LAST_CHANGE: v0.1.0 - GSD plan 05-02: created from 05-RESEARCH §1 (backward paging: to = oldest time of the
//     previous page, boundary duplicates removed by key) and §3 (at most one refresh per 5 minutes, 10 pages).
//   HAZARD: the page cap and the "no progress" stop are what bound the request count; never turn the paging
//     into an open loop.
// END_CHANGE_SUMMARY

internal sealed interface GateDecision {
    object Fetch : GateDecision
    data class TooEarly(val minutesAgo: Int) : GateDecision
}

internal object RefreshGate {
    const val MIN_INTERVAL_MS = 5 * 60_000L

    // START_CONTRACT: decide
    //   PURPOSE: 5-minute gate of the feed refresh (EVT-06).
    //   INPUTS: { nowMs: Long - wall clock; lastFetchMs: Long? - newer of the fetch / attempt marks, null = never }
    //   OUTPUTS: { GateDecision - Fetch when never, when the mark is in the future (clock change) or >= 5 min old;
    //     otherwise TooEarly(whole minutes since the mark) }
    //   SIDE_EFFECTS: none
    // END_CONTRACT: decide
    fun decide(nowMs: Long, lastFetchMs: Long?): GateDecision {
        // START_BLOCK_GATE_DECIDE
        if (lastFetchMs == null) return GateDecision.Fetch
        val age = nowMs - lastFetchMs
        if (age < 0 || age >= MIN_INTERVAL_MS) return GateDecision.Fetch
        return GateDecision.TooEarly((age / 60_000L).toInt())
        // END_BLOCK_GATE_DECIDE
    }
}

internal data class PageRange(val fromSec: Long, val toSec: Long)

internal sealed interface PageStep {
    data class Next(val toSec: Long) : PageStep
    data class Done(val noProgress: Boolean) : PageStep
    data class Capped(val gapToSec: Long) : PageStep
}

internal object PagePlanner {
    const val MAX_PAGES = 10

    private const val INCREMENTAL_OVERLAP_MS = 5_000L

    // START_CONTRACT: first
    //   PURPOSE: Range of the newer part of a refresh.
    //   INPUTS: { nowMs, zone - for AlertWire.window; newestCachedMs: Long? - newest cached event, null = empty cache }
    //   OUTPUTS: { PageRange - window when the cache is empty, else from = max(window.from, (newest - 5 s) / 1000) }
    //   SIDE_EFFECTS: none
    // END_CONTRACT: first
    fun first(nowMs: Long, zone: ZoneId, newestCachedMs: Long?): PageRange {
        val (from, to) = AlertWire.window(nowMs, zone)
        if (newestCachedMs == null) return PageRange(from, to)
        return PageRange(maxOf(from, (newestCachedMs - INCREMENTAL_OVERLAP_MS) / 1000), to)
    }

    // START_CONTRACT: next
    //   PURPOSE: Decide what follows one fetched page.
    //   INPUTS: { pageIndex: Int - pages fetched so far in this refresh (1-based); pageSize: Int - raw item count;
    //     oldestTimeMs: Long? - oldest kept time of the page; prevToSec: Long - `to` of that page's request }
    //   OUTPUTS: { PageStep - Done(false) for a short page; Done(true) when a full page gives no older `to`;
    //     Capped(oldest / 1000) after MAX_PAGES full pages; else Next(oldest / 1000) }
    //   SIDE_EFFECTS: none
    // END_CONTRACT: next
    fun next(pageIndex: Int, pageSize: Int, oldestTimeMs: Long?, prevToSec: Long): PageStep {
        // START_BLOCK_PAGE_NEXT
        if (pageSize < AlertWire.PAGE_LIMIT) return PageStep.Done(noProgress = false)
        if (oldestTimeMs == null) return PageStep.Done(noProgress = true)
        val nextTo = oldestTimeMs / 1000
        if (nextTo >= prevToSec) return PageStep.Done(noProgress = true)
        if (pageIndex >= MAX_PAGES) return PageStep.Capped(gapToSec = nextTo)
        return PageStep.Next(nextTo)
        // END_BLOCK_PAGE_NEXT
    }

    // START_CONTRACT: gap
    //   PURPOSE: Older part left by an earlier capped refresh, continued after the newer part is done.
    //   INPUTS: { nowMs, zone - for AlertWire.window; gapToSec: Long? - saved boundary, null = none }
    //   OUTPUTS: { PageRange(window.from, gapToSec), or null when there is no gap or it is outside the window }
    //   SIDE_EFFECTS: none
    // END_CONTRACT: gap
    fun gap(nowMs: Long, zone: ZoneId, gapToSec: Long?): PageRange? {
        if (gapToSec == null) return null
        val from = AlertWire.window(nowMs, zone).first
        return if (gapToSec > from) PageRange(from, gapToSec) else null
    }
}
