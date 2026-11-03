// FILE: AlertSyncService.kt
// VERSION: 0.2.0
// START_MODULE_CONTRACT
//   PURPOSE: Account-safe sync of the alert feed into the Room cache (EVT-01, EVT-05, EVT-06).
//   SCOPE: SyncResult, MediaRefStore, ThumbPrefetcher, AlertSyncService.refresh / refreshMedia / mediaFor /
//     lastFetchMs; preview prefetch pass after a successful refresh; preview file purge.
//   DEPENDS: M-EVENT (AlertListApi, AlertMapper, AlertWire, AlertSync, EventCacheCleaner, EventMediaFiles,
//     ThumbPrefetcher = EventMediaRepository), M-LOCAL-DB, M-AUTH, M-HTTP
//   LINKS: M-EVENT, V-M-EVENT, EVT-01, EVT-05, EVT-06
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.event

import com.yicamalt.auth.AuthSession
import com.yicamalt.auth.SessionSource
import com.yicamalt.database.AppMetaDao
import com.yicamalt.database.AppMetaEntity
import com.yicamalt.database.EventDao
import com.yicamalt.database.EventEntity
import com.yicamalt.network.YiRequestSigner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import timber.log.Timber
import java.io.IOException
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

// START_MODULE_MAP
//   SyncResult - Fetched | TooEarly | InProgress | Offline | Unauthorized | Failed(code) | NoSession.
//   MediaRefStore - in-memory key -> MediaRef (signed URLs and keys); never persisted.
//   ThumbPrefetcher - port: one preview prefetch pass (bound to EventMediaRepository; Lazy breaks the cycle).
//   AlertSyncService - refresh (gated, capped, single flight), refreshMedia (plus minus 5 s), mediaFor, lastFetchMs.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.2.0 - GSD plan 05-03: after a Fetched refresh (Mutex already released) one preview prefetch
//     pass runs in the application scope: new records newest first, then cached rows without a thumb that still
//     have a media ref, at most PREFETCH_MAX records; a running pass is not started twice; its errors are only
//     logged. Purge collects thumb_path of rows older than 30 days before deleting them and removes the files.
//   PREV_CHANGE: v0.1.0 - GSD plan 05-02: created. Gate on the newer of events_last_fetch_ms / events_last_attempt_ms
//     (app_meta, survives restarts); newer part from the newest cached time, then the saved gap; 10 pages per
//     refresh; 30-day purge; media refs memory only; refreshMedia with a 60 s per-key and 6-per-minute global throttle.
//   HAZARD: no retry, no timer, no periodic work (D-04). Every write goes through EventCacheCleaner.writeIfCurrent
//     so a refresh that started before a logout writes nothing afterwards. Logs carry counts, codes, minutes and
//     page numbers only: never keys, ids, times, links or decrypt keys.
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

/** One preview prefetch pass over [records] (newest first). Implemented by EventMediaRepository. */
fun interface ThumbPrefetcher {
    suspend fun prefetch(records: List<AlertRecord>)
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
    private val prefetcher: () -> ThumbPrefetcher,
    private val files: EventMediaFiles,
    private val appScope: CoroutineScope,
) {
    @Inject constructor(
        api: AlertListApi,
        eventDao: EventDao,
        appMetaDao: AppMetaDao,
        session: SessionSource,
        media: MediaRefStore,
        cleaner: EventCacheCleaner,
        prefetcher: dagger.Lazy<ThumbPrefetcher>,
        files: EventMediaFiles,
    ) : this(
        api, eventDao, appMetaDao, session, media, cleaner, System::currentTimeMillis, ZoneId::systemDefault,
        Dispatchers.IO, { prefetcher.get() }, files, CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    /** True while a preview prefetch pass runs (one pass at a time). */
    private val prefetchRunning = AtomicBoolean(false)

    /** One network user at a time: refresh uses tryLock (single flight), refreshMedia waits (withLock). */
    private val mutex = Mutex()

    private val throttleLock = Any()
    private val mediaAttempts = HashMap<String, Long>()
    private val mediaRecent = ArrayDeque<Long>()

    /** Outcome of one signed page request. */
    private sealed interface PageFetch {
        class Ok(val page: AlertPage) : PageFetch
        class Err(val result: SyncResult, val answered: Boolean) : PageFetch
    }

    /** Outcome of one request series (newer part or gap part). */
    private sealed interface Segment {
        class Ok(val pages: Int, val step: PageStep) : Segment
        class Err(val result: SyncResult, val answered: Boolean) : Segment
    }

    // START_CONTRACT: refresh
    //   PURPOSE: Bring the event cache up to date with the server (EVT-01, EVT-05) within the EVT-06 limits.
    //   INPUTS: { none - session from SessionSource; marks from app_meta }
    //   OUTPUTS: { SyncResult - Fetched(newCount, pages, capped) | TooEarly(minutesAgo) | InProgress | NoSession |
    //     Offline | Unauthorized | Failed(code) }
    //   SIDE_EFFECTS: at most MAX_PAGES signed GET v2/alert/list, none when gated / in progress / no session;
    //     Room upserts, MediaRefStore puts, 30-day purge (rows and preview files) and events_last_fetch_ms /
    //     events_gap_to_sec on success; events_last_attempt_ms when the server answered with an error; nothing on
    //     Offline. No retry. On Fetched, after the Mutex is released, launches one preview prefetch pass in the
    //     application scope (returns without waiting for it).
    //   LINKS: RefreshGate, PagePlanner, AlertWire.signedExtra, AlertMapper.page, EventCacheCleaner.writeIfCurrent,
    //     launchPrefetch
    // END_CONTRACT: refresh
    suspend fun refresh(): SyncResult {
        if (!mutex.tryLock()) return logResult(SyncResult.InProgress, 0, 0)
        val fetched = ArrayList<AlertRecord>()
        val result = try {
            withContext(io) { runRefresh(fetched) }
        } finally {
            mutex.unlock()
        }
        if (result is SyncResult.Fetched) launchPrefetch(fetched)
        return result
    }

    private suspend fun runRefresh(fetched: MutableList<AlertRecord>): SyncResult {
        // START_BLOCK_REFRESH_GATE
        val now = clock()
        val s = session.currentSession() ?: return logResult(SyncResult.NoSession, 0, 0)
        val last = listOfNotNull(readLong(EventMetaKeys.LAST_FETCH_MS), readLong(EventMetaKeys.LAST_ATTEMPT_MS)).maxOrNull()
        val decision = RefreshGate.decide(now, last)
        if (decision is GateDecision.TooEarly) {
            Timber.d("[Event][sync][BLOCK_REFRESH_GATE] decision=too-early minutesAgo=%d", decision.minutesAgo)
            return logResult(SyncResult.TooEarly(decision.minutesAgo), 0, 0)
        }
        val minutesAgo = last?.let { ((now - it) / 60_000L).toInt() } ?: -1
        Timber.d("[Event][sync][BLOCK_REFRESH_GATE] decision=fetch minutesAgo=%d", minutesAgo)
        // END_BLOCK_REFRESH_GATE

        // START_BLOCK_REFRESH_PAGES
        val generation = cleaner.generation
        val zoneId = zone()
        val countBefore = eventDao.count()
        val savedGap = readLong(EventMetaKeys.GAP_TO_SEC)
        val newer = runSegment(s, PagePlanner.first(now, zoneId, eventDao.newestTime()), 0, generation, fetched)
        if (newer is Segment.Err) return failRefresh(newer, now, generation)
        newer as Segment.Ok
        var pages = newer.pages
        var gapAfter: Long? = savedGap
        val newerStep = newer.step
        if (newerStep is PageStep.Capped) {
            gapAfter = newerStep.gapToSec
        } else {
            val gapRange = PagePlanner.gap(now, zoneId, savedGap)
            if (gapRange == null) {
                gapAfter = null
            } else if (pages < PagePlanner.MAX_PAGES) {
                val older = runSegment(s, gapRange, pages, generation, fetched)
                if (older is Segment.Err) return failRefresh(older, now, generation)
                older as Segment.Ok
                pages = older.pages
                gapAfter = (older.step as? PageStep.Capped)?.gapToSec
            }
        }
        // END_BLOCK_REFRESH_PAGES

        // START_BLOCK_REFRESH_COMMIT
        val gapToSave = gapAfter
        val cutoff = now - RETENTION_MS
        var oldThumbs: List<String> = emptyList()
        val committed = cleaner.writeIfCurrent(generation) {
            if (session.currentSession() == null) return@writeIfCurrent null
            val newCount = eventDao.count() - countBefore
            // Paths first: once the rows are gone nothing points at their files any more.
            oldThumbs = eventDao.thumbPathsOlderThan(cutoff)
            val purged = eventDao.clearOlderThan(cutoff)
            appMetaDao.put(AppMetaEntity(EventMetaKeys.LAST_FETCH_MS, now.toString()))
            if (gapToSave == null) {
                appMetaDao.delete(EventMetaKeys.GAP_TO_SEC)
            } else {
                appMetaDao.put(AppMetaEntity(EventMetaKeys.GAP_TO_SEC, gapToSave.toString()))
            }
            newCount to purged
        } ?: return logResult(SyncResult.NoSession, 0, 0)
        purgeFiles(cutoff, oldThumbs)
        return logResult(SyncResult.Fetched(committed.first, pages, gapToSave != null), committed.first, committed.second)
        // END_BLOCK_REFRESH_COMMIT
    }

    /** Deletes preview files of purged rows (and expired day folders); a file error never fails the refresh. */
    private fun purgeFiles(cutoff: Long, oldThumbs: List<String>) {
        // START_BLOCK_PURGE_FILES
        val deleted = runCatching { files.purgeThumbsOlderThan(cutoff, oldThumbs) }
        deleted.exceptionOrNull()?.let {
            Timber.d("[Event][sync][BLOCK_PURGE_FILES] error=%s", it.javaClass.simpleName)
        }
        Timber.d("[Event][sync][BLOCK_PURGE_FILES] files=%d", deleted.getOrDefault(0))
        // END_BLOCK_PURGE_FILES
    }

    // START_CONTRACT: launchPrefetch
    //   PURPOSE: Start one preview prefetch pass outside the sync Mutex (EVT-01, EVT-05).
    //   INPUTS: { fetched: List<AlertRecord> - records written by this refresh }
    //   OUTPUTS: { Unit - returns at once }
    //   SIDE_EFFECTS: launches in the application scope: batch = fetched newest first, then cached rows of the last
    //     30 days without a thumb that still have a media ref; at most PREFETCH_MAX; skipped while a pass runs.
    //     Errors are logged by class name only.
    //   LINKS: ThumbPrefetcher, EventMediaRepository.prefetchThumbs, EventDao.missingThumbsSince
    // END_CONTRACT: launchPrefetch
    private fun launchPrefetch(fetched: List<AlertRecord>) {
        // START_BLOCK_PREFETCH_LAUNCH
        if (!prefetchRunning.compareAndSet(false, true)) {
            Timber.d("[Event][sync][BLOCK_PREFETCH_LAUNCH] started=false reason=running")
            return
        }
        val now = clock()
        appScope.launch {
            try {
                val batch = withContext(io) { prefetchBatch(fetched, now) }
                Timber.d("[Event][sync][BLOCK_PREFETCH_LAUNCH] started=true records=%d", batch.size)
                if (batch.isNotEmpty()) prefetcher().prefetch(batch)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.d("[Event][sync][BLOCK_PREFETCH_LAUNCH] error=%s", e.javaClass.simpleName)
            } finally {
                prefetchRunning.set(false)
            }
        }
        // END_BLOCK_PREFETCH_LAUNCH
    }

    /** New records (newest first) without a thumb, then older thumb-less rows with a media ref; capped. */
    private fun prefetchBatch(fetched: List<AlertRecord>, now: Long): List<AlertRecord> {
        val missing = eventDao.missingThumbsSince(now - RETENTION_MS, PREFETCH_SCAN)
        val missingKeys = missing.mapTo(HashSet()) { it.eventKey }
        val fresh = fetched.asSequence()
            .filter { it.key in missingKeys && media.get(it.key) != null }
            .distinctBy { it.key }
            .sortedByDescending { it.timeMs }
            .toList()
        val freshKeys = fresh.mapTo(HashSet()) { it.key }
        val older = missing.asSequence()
            .filter { it.eventKey !in freshKeys && media.get(it.eventKey) != null }
            .map { AlertRecord(it.eventKey, it.deviceId, it.timeMs, it.type, it.subType, it.fileType, it.playTimeS, it.hasClip) }
        return (fresh.asSequence() + older).take(PREFETCH_MAX).toList()
    }

    /** Error end of a refresh: an answered error sets the attempt mark (same 5-minute gate); Offline sets nothing. */
    private fun failRefresh(err: Segment.Err, now: Long, generation: Long): SyncResult {
        if (err.answered) {
            cleaner.writeIfCurrent(generation) {
                appMetaDao.put(AppMetaEntity(EventMetaKeys.LAST_ATTEMPT_MS, now.toString()))
            }
        }
        return logResult(err.result, 0, 0)
    }

    // START_CONTRACT: runSegment
    //   PURPOSE: Backward paging over one range until Done / Capped (PagePlanner.next decides; bounded by MAX_PAGES).
    //   INPUTS: { s: AuthSession; range: PageRange; pagesBefore: Int - pages already fetched in this refresh;
    //     generation: Long - wipe generation captured at the start; fetched - collects the written records }
    //   OUTPUTS: { Segment - Ok(pages so far, final step) or Err(typed result, server answered?) }
    //   SIDE_EFFECTS: signed requests; each page upserted to Room, its media refs put into the store, its records
    //     appended to [fetched]
    //   LINKS: fetchPage, PagePlanner.next
    // END_CONTRACT: runSegment
    private suspend fun runSegment(
        s: AuthSession,
        range: PageRange,
        pagesBefore: Int,
        generation: Long,
        fetched: MutableList<AlertRecord>,
    ): Segment {
        // START_BLOCK_FETCH_PAGE
        var pages = pagesBefore
        var toSec = range.toSec
        var step: PageStep
        do {
            val page = when (val f = fetchPage(s, range.fromSec, toSec)) {
                is PageFetch.Ok -> f.page
                is PageFetch.Err -> return Segment.Err(f.result, f.answered)
            }
            pages++
            val cachedAt = clock()
            val written = cleaner.writeIfCurrent(generation) {
                if (session.currentSession() == null) return@writeIfCurrent false
                eventDao.upsertAll(page.records.map { it.toEntity(cachedAt) })
                media.putAll(page.media)
                fetched.addAll(page.records)
                true
            }
            if (written != true) return Segment.Err(SyncResult.NoSession, answered = false)
            step = PagePlanner.next(pages, page.rawCount, page.oldestTimeMs, toSec)
            val label = when (step) {
                is PageStep.Next -> "next"
                is PageStep.Done -> if (step.noProgress) "done-no-progress" else "done"
                is PageStep.Capped -> "capped"
            }
            Timber.d("[Event][sync][BLOCK_FETCH_PAGE] page=%d size=%d step=%s", pages, page.rawCount, label)
            if (step is PageStep.Next) toSec = step.toSec
        } while (step is PageStep.Next)
        return Segment.Ok(pages, step)
        // END_BLOCK_FETCH_PAGE
    }

    // START_CONTRACT: fetchPage
    //   PURPOSE: One signed GET v2/alert/list (vendor order: seq=0, userid, type=, sub_type=, from, to, limit, fromDB, expires).
    //   INPUTS: { s: AuthSession; fromSec, toSec: Long - epoch seconds }
    //   OUTPUTS: { PageFetch - Ok(mapped success page) | Err(Unauthorized | Failed(code) answered, Offline not answered) }
    //   SIDE_EFFECTS: one network request; no retry
    //   LINKS: AlertWire.signedExtra, YiRequestSigner.sign, AlertMapper.page
    // END_CONTRACT: fetchPage
    private suspend fun fetchPage(s: AuthSession, fromSec: Long, toSec: Long): PageFetch {
        // START_BLOCK_SIGNED_PAGE
        val hmac = YiRequestSigner.sign(
            s.accessToken, s.refreshToken, s.userId,
            extra = AlertWire.signedExtra(fromSec, toSec, AlertWire.PAGE_LIMIT),
            seq = AlertWire.SEQ,
        )
        val envelope = try {
            api.alertList(
                AlertWire.SEQ, s.userId, "", "", fromSec, toSec, AlertWire.PAGE_LIMIT, "true", AlertWire.EXPIRES, hmac,
            )
        } catch (e: HttpException) {
            return if (e.code() == HTTP_UNAUTHORIZED) {
                PageFetch.Err(SyncResult.Unauthorized, answered = true)
            } else {
                PageFetch.Err(SyncResult.Failed("http-${e.code()}"), answered = true)
            }
        } catch (e: IOException) {
            return PageFetch.Err(SyncResult.Offline, answered = false)
        } catch (e: SerializationException) {
            return PageFetch.Err(SyncResult.Failed("malformed"), answered = true)
        }
        val page = AlertMapper.page(envelope)
        if (page.success) return PageFetch.Ok(page)
        return if (page.code in AUTH_CODES) {
            PageFetch.Err(SyncResult.Unauthorized, answered = true)
        } else {
            PageFetch.Err(SyncResult.Failed(page.code), answered = true)
        }
        // END_BLOCK_SIGNED_PAGE
    }

    // START_CONTRACT: refreshMedia
    //   PURPOSE: Fresh signed media links / keys of one cached event (stale or 403 link in clip playback).
    //   INPUTS: { key: String - event_key of a cached row }
    //   OUTPUTS: { MediaRef? - the fresh ref; the cached ref (or null) when throttled; null on any failure or miss }
    //   SIDE_EFFECTS: at most one signed request with AlertWire.refreshWindow(time_ms) and PAGE_LIMIT; per key at most
    //     one per 60 s, globally at most MEDIA_REFRESH_MAX_PER_MIN per rolling 60 s; updates MediaRefStore only.
    //     Never touches the feed gate marks.
    //   LINKS: AlertWire.refreshWindow, MediaRef.isStale, M-EVENT-PLAYBACK
    // END_CONTRACT: refreshMedia
    suspend fun refreshMedia(key: String): MediaRef? = withContext(io) {
        // START_BLOCK_REFRESH_MEDIA
        val row: EventEntity? = eventDao.getByKey(key)
        if (row == null) {
            Timber.d("[Event][media][BLOCK_REFRESH_MEDIA] result=miss")
            return@withContext null
        }
        if (!takeMediaBudget(key, clock())) {
            Timber.d("[Event][media][BLOCK_REFRESH_MEDIA] result=throttled")
            return@withContext media.get(key)
        }
        val s = session.currentSession()
        if (s == null) {
            Timber.d("[Event][media][BLOCK_REFRESH_MEDIA] result=error")
            return@withContext null
        }
        val generation = cleaner.generation
        val (fromSec, toSec) = AlertWire.refreshWindow(row.timeMs)
        val fetch = mutex.withLock { fetchPage(s, fromSec, toSec) }
        val ref = (fetch as? PageFetch.Ok)?.page?.media?.get(key)
        val stored = ref != null && cleaner.writeIfCurrent(generation) { media.put(key, ref) } != null
        val result = when {
            fetch is PageFetch.Err -> "error"
            !stored -> "miss"
            else -> "ok"
        }
        Timber.d("[Event][media][BLOCK_REFRESH_MEDIA] result=%s", result)
        if (stored) ref else null
        // END_BLOCK_REFRESH_MEDIA
    }

    /** refreshMedia throttle: 60 s per key plus a rolling global cap; a request consumes budget, a refusal does not. */
    private fun takeMediaBudget(key: String, now: Long): Boolean = synchronized(throttleLock) {
        val lastForKey = mediaAttempts[key]
        if (lastForKey != null && now - lastForKey in 0 until MEDIA_THROTTLE_MS) return@synchronized false
        while (mediaRecent.isNotEmpty() && now - mediaRecent.first() >= MEDIA_THROTTLE_MS) mediaRecent.removeFirst()
        if (mediaRecent.size >= MEDIA_REFRESH_MAX_PER_MIN) return@synchronized false
        mediaAttempts[key] = now
        mediaRecent.addLast(now)
        true
    }

    /** Signed media links and keys of the last sync for [key]; memory only. */
    fun mediaFor(key: String): MediaRef? = media.get(key)

    /** Time of the last successful feed fetch (UI label), null when never fetched. */
    suspend fun lastFetchMs(): Long? = withContext(io) { readLong(EventMetaKeys.LAST_FETCH_MS) }

    private fun readLong(key: String): Long? = appMetaDao.get(key)?.toLongOrNull()

    private fun logResult(result: SyncResult, newCount: Int, purged: Int): SyncResult {
        // START_BLOCK_SYNC_RESULT
        val label = when (result) {
            is SyncResult.Fetched -> "fetched"
            is SyncResult.TooEarly -> "too-early"
            SyncResult.InProgress -> "in-progress"
            SyncResult.Offline -> "offline"
            SyncResult.Unauthorized -> "unauthorized"
            is SyncResult.Failed -> "failed-${result.code}"
            SyncResult.NoSession -> "no-session"
        }
        Timber.d("[Event][sync][BLOCK_SYNC_RESULT] result=%s new=%d purged=%d", label, newCount, purged)
        return result
        // END_BLOCK_SYNC_RESULT
    }

    private fun AlertRecord.toEntity(cachedAt: Long) = EventEntity(
        eventKey = key, deviceId = deviceId, timeMs = timeMs, type = type, subType = subType, fileType = fileType,
        hasClip = hasClip, playTimeS = playTimeS, thumbPath = null, cachedAt = cachedAt,
    )

    companion object {
        const val MEDIA_REFRESH_MAX_PER_MIN = 6
        const val PREFETCH_MAX = 100
        private const val PREFETCH_SCAN = 2_000
        private const val MEDIA_THROTTLE_MS = 60_000L
        private const val RETENTION_MS = AlertWire.WINDOW_DAYS * 86_400_000L
        private const val HTTP_UNAUTHORIZED = 401
        private val AUTH_CODES = setOf("20201", "20202", "20203")
    }
}
