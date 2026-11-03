// FILE: AlertSyncService.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Account-safe sync of the alert feed into the Room cache (EVT-01, EVT-05, EVT-06).
//   SCOPE: SyncResult, MediaRefStore, AlertSyncService.refresh / refreshMedia / mediaFor / lastFetchMs.
//   DEPENDS: M-EVENT (AlertListApi, AlertMapper, AlertWire, AlertSync, EventCacheCleaner), M-LOCAL-DB, M-AUTH, M-HTTP
//   LINKS: M-EVENT, V-M-EVENT, EVT-01, EVT-05, EVT-06
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.event

import com.yicamalt.auth.SessionSource
import com.yicamalt.database.AppMetaDao
import com.yicamalt.database.EventDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

// START_MODULE_MAP
//   SyncResult - Fetched | TooEarly | InProgress | Offline | Unauthorized | Failed(code) | NoSession.
//   MediaRefStore - in-memory key -> MediaRef (signed URLs and keys); never persisted.
//   AlertSyncService - refresh (gated, capped, single flight), refreshMedia (plus minus 5 s), mediaFor, lastFetchMs.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 05-02: created (RED stub).
// END_CHANGE_SUMMARY

sealed interface SyncResult {
    data class Fetched(val newCount: Int, val pages: Int, val capped: Boolean) : SyncResult
    data class TooEarly(val minutesAgo: Int) : SyncResult
    object InProgress : SyncResult
    object Offline : SyncResult
    object Unauthorized : SyncResult
    data class Failed(val code: String) : SyncResult
    object NoSession : SyncResult
}

/** Signed media links and keys of the last sync, by event key. Memory only (HAZARD: never persist or log). */
@Singleton
class MediaRefStore @Inject constructor() {
    private val refs = ConcurrentHashMap<String, MediaRef>()

    fun get(key: String): MediaRef? = refs[key]

    fun putAll(media: Map<String, MediaRef>) = refs.putAll(media)

    fun put(key: String, ref: MediaRef) { refs[key] = ref }

    fun clear() = refs.clear()

    fun size(): Int = refs.size
}

@Singleton
class AlertSyncService internal constructor(
    private val api: AlertListApi,
    private val eventDao: EventDao,
    private val appMetaDao: AppMetaDao,
    private val session: SessionSource,
    private val media: MediaRefStore,
    private val cleaner: EventCacheCleaner,
    private val clock: () -> Long,
    private val zone: () -> ZoneId,
    private val io: CoroutineDispatcher,
) {
    @Inject constructor(
        api: AlertListApi,
        eventDao: EventDao,
        appMetaDao: AppMetaDao,
        session: SessionSource,
        media: MediaRefStore,
        cleaner: EventCacheCleaner,
    ) : this(api, eventDao, appMetaDao, session, media, cleaner, System::currentTimeMillis, ZoneId::systemDefault, Dispatchers.IO)

    suspend fun refresh(): SyncResult = error("RED stub")

    suspend fun refreshMedia(key: String): MediaRef? = error("RED stub")

    fun mediaFor(key: String): MediaRef? = error("RED stub")

    suspend fun lastFetchMs(): Long? = error("RED stub")

    companion object {
        const val MEDIA_REFRESH_MAX_PER_MIN = 6
    }
}
