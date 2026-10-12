// FILE: YiRegion.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Yi Cloud gateway regions and the region-selection port (CAM-02).
//   SCOPE: the three gateways (US, EU, SG) in default probing order, host-based lookup from a base URL,
//     region order for a login attempt, and the RegionSelector port implemented by ConfigModule.
//   DEPENDS: none
//   LINKS: M-CONFIG, M-AUTH, M-CAMERA-LIST, V-M-CONFIG
//   ROLE: TYPES
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.config

import java.net.URI

// START_MODULE_MAP
//   YiRegion - gateway enum (US, EU, SG) with base URLs; declaration order = default region order.
//   YiRegion.fromBaseUrl - region of a base URL by host (case-insensitive), null for unknown hosts.
//   YiRegion.orderStartingWith - the given region first, then the rest in declaration order.
//   RegionSelector - port: current region, select a region, read/restore the effective base URL.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - Initial. Order US, EU, SG (probe 2026-10-11: the same credentials log in on all
//     three gateways, cameras live on one). The fourth server (api.xiaoyi.com) is deliberately NOT a
//     YiRegion: it is reachable only through the manual-server setting.
// END_CHANGE_SUMMARY

/** Yi Cloud gateways. Declaration order is the default probing order: US, EU, SG. */
enum class YiRegion(val baseUrl: String) {
    US("https://gw-us.xiaoyi.com/"),
    EU("https://gw-eu.xiaoyi.com/"),
    SG("https://gw-sg.xiaoyi.com/");

    private val host: String = URI(baseUrl).host

    companion object {
        // START_CONTRACT: fromBaseUrl
        //   PURPOSE: Map a base URL to its gateway region by host.
        //   INPUTS: { url: String? - any base URL, with or without path/trailing slash }
        //   OUTPUTS: { YiRegion? - null for null/blank/unparseable input or an unknown host }
        //   SIDE_EFFECTS: none
        // END_CONTRACT: fromBaseUrl
        fun fromBaseUrl(url: String?): YiRegion? {
            if (url.isNullOrBlank()) return null
            val host = runCatching { URI(url.trim()).host }.getOrNull() ?: return null
            return entries.firstOrNull { it.host.equals(host, ignoreCase = true) }
        }

        // START_CONTRACT: orderStartingWith
        //   PURPOSE: Region order for one login attempt: every region exactly once.
        //   INPUTS: { first: YiRegion }
        //   OUTPUTS: { List<YiRegion> - first, then the remaining regions in declaration order }
        //   SIDE_EFFECTS: none
        // END_CONTRACT: orderStartingWith
        fun orderStartingWith(first: YiRegion): List<YiRegion> = listOf(first) + entries.filter { it != first }
    }
}

/** Region-selection port. Implemented by ConfigModule; consumed by M-AUTH and M-CAMERA-LIST. */
interface RegionSelector {
    /** Region of the effective base URL; null for a custom/unknown server or a manual server setting. */
    fun currentRegion(): YiRegion?

    /** Make the region's gateway the effective base URL. No-op while a manual server is set. */
    fun selectRegion(region: YiRegion)

    /** Effective base URL right now. */
    fun currentBaseUrl(): String

    /** Put a previously read effective base URL back. No-op while a manual server is set. */
    fun restoreBaseUrl(url: String)
}
