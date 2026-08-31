// FILE: AppMetaDao.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Room DAO for app metadata key/value store.
//   SCOPE: get, put (upsert), delete.
//   DEPENDS: M-LOCAL-DB (AppMetaEntity)
//   LINKS: M-CONFIG
//   ROLE: DATA
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

// START_MODULE_MAP
//   AppMetaDao - key/value metadata DAO.
// END_MODULE_MAP

@Dao
interface AppMetaDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(entity: AppMetaEntity)

    @Query("SELECT value FROM app_meta WHERE key = :key")
    fun get(key: String): String?

    @Query("DELETE FROM app_meta WHERE key = :key")
    fun delete(key: String)

    @Query("SELECT * FROM app_meta")
    fun all(): List<AppMetaEntity>
}