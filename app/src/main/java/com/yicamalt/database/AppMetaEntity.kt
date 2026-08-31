// FILE: AppMetaEntity.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Room key-value entity for app metadata (last sync, version, prefs flags).
//   SCOPE: key (PK), value.
//   DEPENDS: M-LOCAL-DB
//   LINKS: M-CONFIG
//   ROLE: DATA
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.database

import androidx.room.Entity
import androidx.room.PrimaryKey

// START_MODULE_MAP
//   AppMetaEntity - key/value metadata row.
// END_MODULE_MAP

@Entity(tableName = "app_meta")
data class AppMetaEntity(
    @PrimaryKey val key: String,
    val value: String,
)