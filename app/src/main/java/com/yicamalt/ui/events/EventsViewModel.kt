// FILE: EventsViewModel.kt
// VERSION: 0.0.1
// START_MODULE_CONTRACT
//   PURPOSE: Events tab view-model (RED stub).
//   SCOPE: state, refresh triggers, filters.
//   DEPENDS: M-EVENT, M-DATABASE, M-CAMERA-LIST
//   LINKS: M-UI-EVENTS, V-M-UI-EVENTS
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.ui.events

import androidx.lifecycle.ViewModel
import com.yicamalt.camera.CameraListPort
import com.yicamalt.database.EventEntity
import com.yicamalt.event.SyncResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate
import java.time.ZoneId

// START_MODULE_MAP
//   EventsViewModel - stub
// END_MODULE_MAP

interface EventFeedSource {
    fun observe(fromMs: Long, toMs: Long): Flow<List<EventEntity>>
}

interface EventSyncSource {
    suspend fun refresh(): SyncResult
}

data class CameraChip(val deviceId: String, val name: String, val selected: Boolean)

data class EventsUi(
    val sections: List<DaySection> = emptyList(),
    val cameras: List<CameraChip> = emptyList(),
    val allSelected: Boolean = true,
    val dayLabel: String = "",
    val selectedDay: LocalDate? = null,
    val today: LocalDate = LocalDate.MIN,
    val daysWithEvents: Set<LocalDate> = emptySet(),
    val daysDialogOpen: Boolean = false,
    val subtitle: String? = null,
    val refreshing: Boolean = false,
    val emptyText: String? = null,
)

sealed interface EventsEvent {
    data class OpenClip(val key: String) : EventsEvent
    data object SessionExpired : EventsEvent
}

class EventsViewModel internal constructor(
    private val feed: EventFeedSource,
    private val sync: EventSyncSource,
    private val cameraPort: CameraListPort,
    private val clock: () -> Long,
    private val zone: () -> ZoneId,
) : ViewModel() {
    val state: StateFlow<EventsUi> get() = error("RED stub")
    val events: Flow<EventsEvent> get() = error("RED stub")
    fun onTabEntered(): Unit = error("RED stub")
    fun onPullRefresh(): Unit = error("RED stub")
    fun toggleCamera(deviceId: String): Unit = error("RED stub")
    fun selectAllCameras(): Unit = error("RED stub")
    fun openDays(): Unit = error("RED stub")
    fun closeDays(): Unit = error("RED stub")
    fun selectDay(day: LocalDate): Unit = error("RED stub")
    fun clearDay(): Unit = error("RED stub")
    fun onCardTap(key: String): Unit = error("RED stub")
}
