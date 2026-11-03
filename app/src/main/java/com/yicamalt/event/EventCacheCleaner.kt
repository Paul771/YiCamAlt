// FILE: EventCacheCleaner.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Wipe of the local alert cache: Room event rows and the event app_meta keys (logout, EVT-05).
//   SCOPE: EventMetaKeys (app_meta key names of the feed sync), EventCacheCleaner.wipeAll.
//   DEPENDS: M-LOCAL-DB (EventDao, AppMetaDao)
//   LINKS: M-EVENT, M-CAMERA-LIST (CameraRegistry logout wipe), EVT-05
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.event

import com.yicamalt.database.AppMetaDao
import com.yicamalt.database.EventDao
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

// START_MODULE_MAP
//   EventMetaKeys - app_meta keys: LAST_FETCH_MS, LAST_ATTEMPT_MS, GAP_TO_SEC (ALL lists them).
//   EventCacheCleaner - @Singleton; wipeAll() deletes every event row and every EventMetaKeys entry.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 05-02: created; called synchronously from CameraRegistry's logout wipe.
// END_CHANGE_SUMMARY

/** app_meta keys owned by the feed sync. Values are numbers only (ms or s), never ids or URLs. */
object EventMetaKeys {
    const val LAST_FETCH_MS = "events_last_fetch_ms"
    const val LAST_ATTEMPT_MS = "events_last_attempt_ms"
    const val GAP_TO_SEC = "events_gap_to_sec"
    val ALL = listOf(LAST_FETCH_MS, LAST_ATTEMPT_MS, GAP_TO_SEC)
}

@Singleton
class EventCacheCleaner @Inject constructor(
    private val eventDao: EventDao,
    private val appMetaDao: AppMetaDao,
) {
    // START_CONTRACT: wipeAll
    //   PURPOSE: Remove the whole local alert cache (logout).
    //   INPUTS: { none }
    //   OUTPUTS: { Unit - returns after rows and keys are gone; Room errors propagate to the caller }
    //   SIDE_EFFECTS: blocking Room writes (call off the main thread); one BLOCK_WIPE_EVENTS line
    //   LINKS: CameraRegistry.clearSignedOutData
    // END_CONTRACT: wipeAll
    fun wipeAll() {
        // START_BLOCK_WIPE_EVENTS
        eventDao.deleteAll()
        EventMetaKeys.ALL.forEach(appMetaDao::delete)
        // START_BLOCK_WIPE_FILES
        // Plan 05-03 deletes the thumbnail / clip files here.
        // END_BLOCK_WIPE_FILES
        Timber.d("[Event][cache][BLOCK_WIPE_EVENTS] wiped=true")
        // END_BLOCK_WIPE_EVENTS
    }
}
