// FILE: YiDatabase.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Room database aggregating camera/event/app-meta entities + DAOs for offline-first UI.
//   SCOPE: version 1, exportSchema true; exposes 3 DAOs; logs BLOCK_INIT_DB via Callback.
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

@Database(
    entities = [CameraEntity::class, EventEntity::class, AppMetaEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class YiDatabase : RoomDatabase() {
    abstract fun cameraDao(): CameraDao
    abstract fun eventDao(): EventDao
    abstract fun appMetaDao(): AppMetaDao
}