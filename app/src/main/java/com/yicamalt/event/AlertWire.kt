// FILE: AlertWire.kt
// VERSION: 0.0.1
// START_MODULE_CONTRACT
//   PURPOSE: Wire constants and signed parameter list of GET v2/alert/list.
//   SCOPE: LIST_PATH, SEQ, limits, signedExtra, window, refreshWindow. Pure; no I/O, no logging.
//   DEPENDS: none
//   LINKS: M-EVENT, V-M-EVENT, EVT-01, EVT-06
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.event

import java.time.ZoneId

// START_MODULE_MAP
//   AlertWire - constants + signedExtra, window, refreshWindow (RED stub).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.0.1 - GSD plan 05-00: RED stub for the test-first cycle.
// END_CHANGE_SUMMARY

internal object AlertWire {
    const val LIST_PATH = "v2/alert/list"
    const val SEQ = "0"
    const val PAGE_LIMIT = 100
    const val EXPIRES = "10"
    const val WINDOW_DAYS = 30
    const val FROM_FLOOR_SEC = 1704067200L
    const val SUCCESS = "20000"

    fun signedExtra(fromSec: Long, toSec: Long, limit: Int): List<Pair<String, String>> = error("RED stub")
    fun window(nowMs: Long, zone: ZoneId): Pair<Long, Long> = error("RED stub")
    fun refreshWindow(timeMs: Long): Pair<Long, Long> = error("RED stub")
}
