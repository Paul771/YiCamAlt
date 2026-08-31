// FILE: CameraEntity.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Room entity caching a discovered Yi camera for offline-first UI.
//   SCOPE: device_id (PK), name, model, online flag, stream_url, last_seen, cached_at.
//   DEPENDS: M-LOCAL-DB
//   LINKS: M-CAMERA-MGR, M-UI-CAMERALIST
//   ROLE: DATA
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.database

import androidx.room.Entity
import androidx.room.PrimaryKey

// START_MODULE_MAP
//   CameraEntity - cached camera row keyed by device_id.
// END_MODULE_MAP

@Entity(tableName = "cameras")
data class CameraEntity(
    @PrimaryKey val device_id: String,
    val name: String,
    val model: String,
    val online: Boolean,
    val stream_url: String?,
    val last_seen: Long,
    val cached_at: Long,
)