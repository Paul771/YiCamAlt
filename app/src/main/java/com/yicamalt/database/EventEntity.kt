// FILE: EventEntity.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Room entity caching a Yi camera event (motion/sound/etc.) for offline browsing.
//   SCOPE: event_id (PK), device_id (FK), type, timestamp, payload_json, cached_at.
//   DEPENDS: M-LOCAL-DB
//   LINKS: M-PUSH, M-UI-EVENTS
//   ROLE: DATA
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// START_MODULE_MAP
//   EventEntity - cached event row keyed by event_id, indexed by device_id + timestamp.
// END_MODULE_MAP

@Entity(
    tableName = "events",
    indices = [
        Index(value = ["device_id"]),
        Index(value = ["device_id", "timestamp"]),
    ],
)
data class EventEntity(
    @PrimaryKey val event_id: String,
    val device_id: String,
    val type: String,
    val timestamp: Long,
    val payload_json: String,
    val cached_at: Long,
)