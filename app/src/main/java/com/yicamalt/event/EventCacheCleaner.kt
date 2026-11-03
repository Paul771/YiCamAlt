// FILE: EventCacheCleaner.kt
// VERSION: 0.2.0
// START_MODULE_CONTRACT
//   PURPOSE: Wipe of the local alert cache: Room event rows, the event app_meta keys and the in-memory media
//     refs (logout, EVT-05); write guard so a sync started before a wipe cannot write after it.
//   SCOPE: EventMetaKeys (app_meta key names of the feed sync), EventCacheCleaner.wipeAll / generation / writeIfCurrent.
//   DEPENDS: M-LOCAL-DB (EventDao, AppMetaDao), M-EVENT (MediaRefStore)
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
//   EventCacheCleaner - @Singleton; wipeAll() clears media refs, every event row and every EventMetaKeys entry.
//   EventCacheCleaner.generation - wipe counter; writeIfCurrent runs a write only if no wipe happened since.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.2.0 - GSD plan 05-02: MediaRefStore cleared on wipe; generation + writeIfCurrent (logout race).
//   PREV_CHANGE: v0.1.0 - GSD plan 05-02: created; called synchronously from CameraRegistry's logout wipe.
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
    private val media: MediaRefStore,
) {
    private val lock = Any()

    /** Bumped by every wipe; a sync captures it at start and writes only while it is unchanged. */
    @Volatile var generation: Long = 0L
        private set

    // START_CONTRACT: writeIfCurrent
    //   PURPOSE: Run one cache write atomically with respect to wipeAll.
    //   INPUTS: { expected: Long - generation captured by the caller; block - the write }
    //   OUTPUTS: { T? - block result, or null when a wipe happened since [expected] (nothing written) }
    //   SIDE_EFFECTS: whatever block writes, under the wipe lock
    //   LINKS: AlertSyncService.refresh
    // END_CONTRACT: writeIfCurrent
    fun <T> writeIfCurrent(expected: Long, block: () -> T): T? = synchronized(lock) {
        if (generation != expected) null else block()
    }

    // START_CONTRACT: wipeAll
    //   PURPOSE: Remove the whole local alert cache (logout).
    //   INPUTS: { none }
    //   OUTPUTS: { Unit - returns after rows and keys are gone; Room errors propagate to the caller }
    //   SIDE_EFFECTS: blocking Room writes (call off the main thread); one BLOCK_WIPE_EVENTS line
    //   LINKS: CameraRegistry.clearSignedOutData
    // END_CONTRACT: wipeAll
    fun wipeAll() {
        // START_BLOCK_WIPE_EVENTS
        synchronized(lock) {
            // Generation and media first: even if Room throws below, no running sync writes afterwards.
            generation++
            media.clear()
            eventDao.deleteAll()
            EventMetaKeys.ALL.forEach(appMetaDao::delete)
        }
        // START_BLOCK_WIPE_FILES
        // Plan 05-03 deletes the thumbnail / clip files here.
        // END_BLOCK_WIPE_FILES
        Timber.d("[Event][cache][BLOCK_WIPE_EVENTS] wiped=true")
        // END_BLOCK_WIPE_EVENTS
    }
}
