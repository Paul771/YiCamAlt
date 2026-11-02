// FILE: AlertWire.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Wire constants and signed parameter list of GET v2/alert/list (vendor 6.9.8 order).
//   SCOPE: LIST_PATH, SEQ, PAGE_LIMIT, EXPIRES, WINDOW_DAYS, FROM_FLOOR_SEC, SUCCESS, signedExtra, window,
//     refreshWindow. Pure; no I/O, no logging.
//   DEPENDS: none
//   LINKS: M-EVENT, V-M-EVENT, EVT-01, EVT-06
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.event

import java.time.Instant
import java.time.ZoneId

// START_MODULE_MAP
//   AlertWire - object: wire constants of v2/alert/list.
//   AlertWire.signedExtra - extra signed params after seq/userid, in vendor order, empty values kept.
//   AlertWire.window - (fromSec, toSec): last 30 days (+60 s) floored at the vendor guard, to = start of tomorrow.
//   AlertWire.refreshWindow - (fromSec, toSec) = time plus minus 5 s, for re-fetching fresh media URLs.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 05-00: from research §1. Named AlertWire, not EventPaths (the old
//     EventPaths in YiCloudEventApi.kt still exists until plan 05-01 removes it).
//   HAZARD: seq is "0" here (not "1"); type= and sub_type= are signed with empty values. Never add
//     destructive or push-settings alert paths to this object.
// END_CHANGE_SUMMARY

internal object AlertWire {
    const val LIST_PATH = "v2/alert/list"
    const val SEQ = "0"
    const val PAGE_LIMIT = 100
    const val EXPIRES = "10"
    const val WINDOW_DAYS = 30
    const val FROM_FLOOR_SEC = 1704067200L
    const val SUCCESS = "20000"

    private const val DAY_SEC = 86_400L
    private const val REFRESH_HALF_MS = 5_000L

    // START_CONTRACT: signedExtra
    //   PURPOSE: Signed params after seq and userid, in the vendor insertion order.
    //   INPUTS: { fromSec: Long, toSec: Long - epoch seconds; limit: Int - page size }
    //   OUTPUTS: { List<Pair<String,String>> - type, sub_type, from, to, limit, fromDB, expires }
    //   SIDE_EFFECTS: none
    //   LINKS: M-HTTP (YiRequestSigner.sign extra)
    // END_CONTRACT: signedExtra
    fun signedExtra(fromSec: Long, toSec: Long, limit: Int): List<Pair<String, String>> = listOf(
        "type" to "",
        "sub_type" to "",
        "from" to fromSec.toString(),
        "to" to toSec.toString(),
        "limit" to limit.toString(),
        "fromDB" to "true",
        "expires" to EXPIRES,
    )

    // START_CONTRACT: window
    //   PURPOSE: Request window of the feed sync.
    //   INPUTS: { nowMs: Long - wall clock ms; zone: ZoneId - local zone for "start of tomorrow" }
    //   OUTPUTS: { Pair<Long,Long> - (from = max(now - 30 d + 60 s, floor + 1), to = start of tomorrow) seconds }
    //   SIDE_EFFECTS: none
    // END_CONTRACT: window
    fun window(nowMs: Long, zone: ZoneId): Pair<Long, Long> {
        // START_BLOCK_ALERT_WINDOW
        val nowSec = nowMs / 1000
        val to = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toEpochSecond()
        val from = maxOf(nowSec - WINDOW_DAYS * DAY_SEC + 60, FROM_FLOOR_SEC + 1)
        return from to to
        // END_BLOCK_ALERT_WINDOW
    }

    // START_CONTRACT: refreshWindow
    //   PURPOSE: Narrow window around one event to re-fetch its fresh media URLs and keys.
    //   INPUTS: { timeMs: Long - event time in ms }
    //   OUTPUTS: { Pair<Long,Long> - ((time - 5 s) / 1000, (time + 5 s) / 1000) }
    //   SIDE_EFFECTS: none
    // END_CONTRACT: refreshWindow
    fun refreshWindow(timeMs: Long): Pair<Long, Long> =
        (timeMs - REFRESH_HALF_MS) / 1000 to (timeMs + REFRESH_HALF_MS) / 1000
}
