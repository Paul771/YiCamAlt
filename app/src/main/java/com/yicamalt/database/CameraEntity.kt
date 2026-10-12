// FILE: CameraEntity.kt
// VERSION: 0.2.0
// START_MODULE_CONTRACT
//   PURPOSE: Room entity caching a discovered Yi camera for offline-first UI.
//   SCOPE: device_id (PK, cloud uid), name, model, online flag, stream_url, last_seen, cached_at, transport, shared;
//     no P2P secrets — SEC-04 (camera password, p2p id, InitString and License live only in the encrypted store).
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

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.2.0 - GSD plan 01-12 (CAM-03, SEC-04): row v2 adds transport (TransportType name) and shared.
//     INVARIANT: no secret column is ever added here. device_id stays the cloud uid: an identifier, not a
//     credential; the DB is app-private and the value is never logged or rendered.
// END_CHANGE_SUMMARY

@Entity(tableName = "cameras")
data class CameraEntity(
    @PrimaryKey val device_id: String,
    val name: String,
    val model: String,
    val online: Boolean,
    val stream_url: String?,
    val last_seen: Long,
    val cached_at: Long,
    val transport: String = "UNKNOWN",
    val shared: Boolean = false,
)