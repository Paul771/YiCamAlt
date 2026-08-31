// FILE: CameraDao.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Room DAO for camera cache (insert/upsert, query, clear).
//   SCOPE: BLOCK_DB_WRITE on insert; offline-first read accessors.
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

// START_MODULE_MAP
//   CameraDao - abstract class DAO; concrete insert wraps an annotated raw insert to emit trace marker.
// END_MODULE_MAP

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
        DbLog.d("[DB][cache][BLOCK_DB_WRITE] camera=${entity.device_id} online=${entity.online}")
        insertCameraRaw(entity)
    }
    // END_BLOCK_DB_WRITE
}