// FILE: EventsFeed.kt
// VERSION: 0.0.1
// START_MODULE_CONTRACT
//   PURPOSE: Pure events-feed logic (RED stub).
//   SCOPE: grouping, filters, labels.
//   DEPENDS: M-EVENT, M-DATABASE
//   LINKS: M-UI-EVENTS, V-M-UI-EVENTS
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.ui.events

import com.yicamalt.database.EventEntity
import com.yicamalt.event.SyncResult
import java.time.LocalDate
import java.time.ZoneId

// START_MODULE_MAP
//   EventsFeed - stub
// END_MODULE_MAP

data class EventCard(
    val key: String, val deviceId: String, val cameraName: String, val knownCamera: Boolean,
    val timeLabel: String, val timeMs: Long, val thumbPath: String?, val hasClip: Boolean,
)

data class DaySection(val day: LocalDate, val header: String, val cards: List<EventCard>)

internal object EventsFeed {
    fun build(
        rows: List<EventEntity>, cameras: Map<String, String>, selectedCameras: Set<String>,
        selectedDay: LocalDate?, zone: ZoneId, nowMs: Long,
    ): List<DaySection> = error("RED stub")

    fun daysWithEvents(times: List<Long>, zone: ZoneId): Set<LocalDate> = error("RED stub")

    fun subtitle(result: SyncResult?, previous: String?): String? = error("RED stub")

    fun emptyText(totalRows: Int, shownRows: Int): String? = error("RED stub")
}
