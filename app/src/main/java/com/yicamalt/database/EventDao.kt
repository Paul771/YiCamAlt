// FILE: EventDao.kt
// VERSION: 0.2.0
// START_MODULE_CONTRACT
//   PURPOSE: Room DAO for the v3 alert cache (feed window reads, upsert that keeps thumbnails, purge, wipe).
//   SCOPE: upsertAll (BLOCK_DB_WRITE, keeps an existing thumb_path), observeFeed / feed (window, newest first),
//     timesSince (calendar marks), newestTime, getByKey, setThumb, missingThumbsSince, thumbPathsOlderThan,
//     clearOlderThan, count, deleteAll.
//   DEPENDS: M-LOCAL-DB (EventEntity)
//   LINKS: M-EVENT, M-UI-EVENTS, EVT-05
//   ROLE: DATA
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

// START_MODULE_MAP
//   EventDao - abstract class DAO; upsertAll is a transaction over a raw REPLACE insert and emits the trace marker.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.2.0 - GSD plan 05-02: v3 schema (event_key, time_ms). Feed window queries, Flow feed,
//     calendar times, thumbnail bookkeeping, upsert that preserves thumb_path. The device-only queries
//     (getByDevice, getByDeviceInWindow, countByDevice, insertEvent) had no caller after 05-01 and are gone.
//   PREV_CHANGE: v0.1.1 - BLOCK_DB_WRITE made non-identifying (no event or device id in the log).
// END_CHANGE_SUMMARY

@Dao
abstract class EventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insertAllRaw(rows: List<EventEntity>)

    @Query("SELECT event_key, thumb_path FROM events WHERE event_key IN (:keys) AND thumb_path IS NOT NULL")
    abstract fun thumbsFor(keys: List<String>): List<KeyThumb>

    @Query("SELECT * FROM events WHERE time_ms BETWEEN :fromMs AND :toMs ORDER BY time_ms DESC")
    abstract fun observeFeed(fromMs: Long, toMs: Long): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE time_ms BETWEEN :fromMs AND :toMs ORDER BY time_ms DESC")
    abstract fun feed(fromMs: Long, toMs: Long): List<EventEntity>

    @Query("SELECT time_ms FROM events WHERE time_ms >= :fromMs ORDER BY time_ms DESC")
    abstract fun timesSince(fromMs: Long): List<Long>

    @Query("SELECT MAX(time_ms) FROM events")
    abstract fun newestTime(): Long?

    @Query("SELECT * FROM events WHERE event_key = :key LIMIT 1")
    abstract fun getByKey(key: String): EventEntity?

    @Query("UPDATE events SET thumb_path = :path WHERE event_key = :key")
    abstract fun setThumb(key: String, path: String?)

    @Query("SELECT * FROM events WHERE time_ms >= :fromMs AND thumb_path IS NULL ORDER BY time_ms DESC LIMIT :limit")
    abstract fun missingThumbsSince(fromMs: Long, limit: Int): List<EventEntity>

    @Query("SELECT thumb_path FROM events WHERE time_ms < :cutoff AND thumb_path IS NOT NULL")
    abstract fun thumbPathsOlderThan(cutoff: Long): List<String>

    @Query("DELETE FROM events WHERE time_ms < :cutoff")
    abstract fun clearOlderThan(cutoff: Long): Int

    @Query("SELECT COUNT(*) FROM events")
    abstract fun count(): Int

    @Query("DELETE FROM events")
    abstract fun deleteAll()

    // START_CONTRACT: upsertAll
    //   PURPOSE: Insert or replace rows by event_key; a thumb_path already stored for a key survives the replace.
    //   INPUTS: { rows: List<EventEntity> - value-free rows (thumb_path usually null for fresh rows) }
    //   OUTPUTS: { Unit }
    //   SIDE_EFFECTS: one transaction; one BLOCK_DB_WRITE line with counts only
    //   LINKS: M-EVENT (AlertSyncService)
    // END_CONTRACT: upsertAll
    @Transaction
    open fun upsertAll(rows: List<EventEntity>) {
        // START_BLOCK_DB_WRITE
        if (rows.isEmpty()) return
        val kept = rows.map { it.eventKey }.chunked(SQL_VAR_CHUNK)
            .flatMap { thumbsFor(it) }
            .associate { it.event_key to it.thumb_path }
        val merged = rows.map { row ->
            val thumb = kept[row.eventKey]
            if (row.thumbPath == null && thumb != null) row.copy(thumbPath = thumb) else row
        }
        insertAllRaw(merged)
        // Non-identifying on purpose: keys and device ids are Yi-side identifiers.
        DbLog.d("[DB][cache][BLOCK_DB_WRITE] events=${rows.size} keptThumbs=${kept.size}")
        // END_BLOCK_DB_WRITE
    }

    /** Projection of (event_key, thumb_path) used by upsertAll. */
    data class KeyThumb(val event_key: String, val thumb_path: String)

    private companion object {
        /** Below SQLite's 999 bound-variable limit. */
        const val SQL_VAR_CHUNK = 500
    }
}
