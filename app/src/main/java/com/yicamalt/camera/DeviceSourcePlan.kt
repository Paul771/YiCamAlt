// FILE: DeviceSourcePlan.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Single edit point for the probe-derived device-list source order (research Conflict A decision tree)
//   SCOPE: OWNED_PATHS (ordered device-list paths the registry may call) and a label per path for value-free logs.
//   DEPENDS: DevicePaths (YiCloudDeviceApi.kt)
//   LINKS: M-CAMERA-LIST, V-M-CAMERA-LIST
//   ROLE: CONFIG
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.camera

// START_MODULE_MAP
//   DeviceSourcePlan.OWNED_PATHS - device-list paths in call order; the registry stops at the first with rows.
//   DeviceSourcePlan.label - fixed log label of a path (never an identifier).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - Created (plan 01-09) from 01-PROBE-RESULTS.md and 01-PLAN-ADDENDUM.md:
//     BRANCH: A, OWNED_PATH: /v5/devices/list, SHARED_PATH: none, DATA_REGION: US, LOGIN_REGION: US,
//     CROSS_REGION_SESSION: no.
//     - Owner path: /v5/devices/list only (the vendor app uses only this one). /v4/devices/list returns the
//       same payload but is not a default and not a fallback (addendum).
//     - No shared path: shared cameras arrive as rows of /v5/devices/list (category / share). The
//       deviceshare shareddetail path is a PUT that changes access rights and must never be called.
//     - No region fallback in the registry: tokens are per region (CROSS_REGION_SESSION: no), so a list call
//       on another gateway can only answer an auth code. The region is the one kept by login discovery
//       (M-AUTH, plan 01-08); the registry never switches it.
// END_CHANGE_SUMMARY

internal object DeviceSourcePlan {

    /** Device-list paths in call order. BRANCH A: the vendor path only. */
    val OWNED_PATHS: List<String> = listOf(DevicePaths.LIST_V5)

    private val LABELS = mapOf(
        DevicePaths.LIST_V5 to "v5list",
        DevicePaths.LIST_V4 to "v4list",
    )

    /** Fixed log label of a path; "other" for anything not listed. */
    fun label(path: String): String = LABELS[path] ?: "other"
}
