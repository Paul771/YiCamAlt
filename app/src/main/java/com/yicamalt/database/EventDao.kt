// FILE: EventDao.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Room DAO for event cache (insert, query by camera, clear-old).
//   SCOPE: BLOCK_DB_WRITE on insert; clearOldData evicts rows older than a cutoff timestamp.
//   DEPENDS: M-LOCAL-DB (EventEntity)
//   LINKS: M-PUSH, M-UI-EVENTS
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

@Dao
abstract class EventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insertEventRaw(entity: EventEntity)

    @Query("SELECT * FROM events WHERE device_id = :deviceId ORDER BY timestamp DESC")
    abstract fun getByDevice(deviceId: String): List<EventEntity>

    @Query("SELECT COUNT(*) FROM events WHERE device_id = :deviceId")
    abstract fun countByDevice(deviceId: String): Int

    @Query("DELETE FROM events WHERE timestamp < :cutoff")
    abstract fun clearOlderThan(cutoff: Long): Int

    @Query("DELETE FROM events")
    abstract fun deleteAll()

    // START_BLOCK_DB_WRITE
    open fun insertEvent(entity: EventEntity) {
        DbLog.d("[DB][cache][BLOCK_DB_WRITE] event=${entity.event_id} device=${entity.device_id} type=${entity.type}")
        insertEventRaw(entity)
    }
    // END_BLOCK_DB_WRITE
}