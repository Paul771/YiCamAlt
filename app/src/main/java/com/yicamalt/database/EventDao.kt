// FILE: EventDao.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Room DAO for event cache (insert, query by camera, clear-old).
//   SCOPE: BLOCK_DB_WRITE on insert; clearOldData evicts rows older than a cutoff timestamp;
//     getByDeviceInWindow serves the event timeline window via the (device_id, timestamp) index.
//   DEPENDS: M-LOCAL-DB (EventEntity)
//   LINKS: M-PUSH, M-UI-EVENTS, M-EVENT
//   ROLE: DATA
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

// START_MODULE_MAP
//   EventDao - abstract class DAO; concrete insert wraps an annotated raw insert to emit trace marker.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.1 - M-EVENT support: added getById + getByDeviceInWindow (the timeline read
//     path) and made the BLOCK_DB_WRITE line non-identifying. It previously logged event_id and
//     device_id, which V-M-EVENT scenario_6 caught leaking; real Yi serials are 10+ digit runs and
//     would trip RedactionScanner even without an explicit literal check.
// END_CHANGE_SUMMARY

@Dao
abstract class EventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insertEventRaw(entity: EventEntity)

    @Query("SELECT * FROM events WHERE device_id = :deviceId ORDER BY timestamp DESC")
    abstract fun getByDevice(deviceId: String): List<EventEntity>

    // Served by the (device_id, timestamp) index declared on EventEntity.
    @Query(
        "SELECT * FROM events WHERE device_id = :deviceId " +
            "AND timestamp BETWEEN :fromTime AND :toTime ORDER BY timestamp DESC",
    )
    abstract fun getByDeviceInWindow(deviceId: String, fromTime: Long, toTime: Long): List<EventEntity>

    @Query("SELECT * FROM events WHERE event_id = :eventId LIMIT 1")
    abstract fun getById(eventId: String): EventEntity?

    @Query("SELECT COUNT(*) FROM events WHERE device_id = :deviceId")
    abstract fun countByDevice(deviceId: String): Int

    @Query("DELETE FROM events WHERE timestamp < :cutoff")
    abstract fun clearOlderThan(cutoff: Long): Int

    @Query("DELETE FROM events")
    abstract fun deleteAll()

    // START_BLOCK_DB_WRITE
    open fun insertEvent(entity: EventEntity) {
        // Non-identifying on purpose: event_id and device_id are Yi-side identifiers and a real
        // device serial is a 10+ digit run, which trips RedactionScanner's long-digit-run rule.
        DbLog.d(
            "[DB][cache][BLOCK_DB_WRITE] type=${entity.type} " +
                "hasThumbnail=${entity.payload_json.contains("thumbnail")}",
        )
        insertEventRaw(entity)
    }
    // END_BLOCK_DB_WRITE
}