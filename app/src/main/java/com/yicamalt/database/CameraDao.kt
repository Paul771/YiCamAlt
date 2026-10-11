// FILE: CameraDao.kt
// VERSION: 0.3.0
// START_MODULE_CONTRACT
//   PURPOSE: Room DAO for camera cache (insert/upsert, query, clear, atomic replace).
//   SCOPE: BLOCK_DB_WRITE on insert; offline-first read accessors; replaceAll in one transaction.
//   DEPENDS: M-LOCAL-DB (CameraEntity)
//   LINKS: M-CAMERA-MGR
//   ROLE: DATA
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

// START_MODULE_MAP
//   CameraDao - abstract class DAO; concrete insert wraps an annotated raw insert to emit trace marker;
//     replaceAll swaps the whole cache inside one transaction.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.3.0 - GSD plan 01-12: replaceAll = atomic cache replace (CONCERNS: non-atomic delete-then-insert)
//   PREVIOUS: v0.2.0 - SEC-01: BLOCK_DB_WRITE no longer logs device_id (13-digit serials); mirrors EventDao
// END_CHANGE_SUMMARY

@Dao
abstract class CameraDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insertCameraRaw(entity: CameraEntity)

    @Query("SELECT * FROM cameras ORDER BY name ASC")
    abstract fun getAll(): List<CameraEntity>

    @Query("SELECT * FROM cameras WHERE device_id = :deviceId")
    abstract fun getById(deviceId: String): CameraEntity?

    @Query("DELETE FROM cameras")
    abstract fun deleteAll()

    @Query("SELECT COUNT(*) FROM cameras")
    abstract fun count(): Int

    // START_BLOCK_DB_WRITE
    open fun insertCamera(entity: CameraEntity) {
        DbLog.d("[DB][cache][BLOCK_DB_WRITE] online=${entity.online} hasModel=${entity.model.isNotBlank()}")
        insertCameraRaw(entity)
    }
    // END_BLOCK_DB_WRITE

    // START_CONTRACT: replaceAll
    //   PURPOSE: Swap the whole camera cache so a reader never sees an empty or half-filled table.
    //   INPUTS: { entities: List<CameraEntity> - the new complete list; empty clears the cache }
    //   OUTPUTS: { Unit }
    //   SIDE_EFFECTS: deletes every row and inserts the new ones in ONE transaction; any failure rolls back
    //     to the previous rows. Emits BLOCK_DB_WRITE per row (no device_id).
    //   LINKS: M-LOCAL-DB, M-CAMERA-LIST
    // END_CONTRACT: replaceAll
    // START_BLOCK_DB_REPLACE_ALL
    @Transaction
    open fun replaceAll(entities: List<CameraEntity>) {
        deleteAll()
        entities.forEach { insertCamera(it) }
    }
    // END_BLOCK_DB_REPLACE_ALL
}