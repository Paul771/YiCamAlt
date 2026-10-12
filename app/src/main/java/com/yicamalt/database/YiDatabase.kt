// FILE: YiDatabase.kt
// VERSION: 0.2.0
// START_MODULE_CONTRACT
//   PURPOSE: Room database aggregating camera/event/app-meta entities + DAOs for offline-first UI.
//   SCOPE: schema v2, exportSchema true; exposes 3 DAOs; logs BLOCK_INIT_DB via Callback. Cache only: no secrets (SEC-04).
//   DEPENDS: none
//   LINKS: M-CAMERA-MGR, M-PUSH, M-CONFIG
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.database

import androidx.room.Database
import androidx.room.RoomDatabase

// START_MODULE_MAP
//   YiDatabase - RoomDatabase with CameraEntity, EventEntity, AppMetaEntity.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.2.0 - GSD plan 01-12: v2: cameras + transport, shared; destructive migration is acceptable
//     for a cache-only table (DatabaseModule uses fallbackToDestructiveMigration).
// END_CHANGE_SUMMARY

@Database(
    entities = [CameraEntity::class, EventEntity::class, AppMetaEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class YiDatabase : RoomDatabase() {
    abstract fun cameraDao(): CameraDao
    abstract fun eventDao(): EventDao
    abstract fun appMetaDao(): AppMetaDao
}