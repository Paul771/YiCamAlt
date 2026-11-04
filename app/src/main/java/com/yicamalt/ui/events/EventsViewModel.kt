// FILE: EventsViewModel.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: View-model of the «События» tab (EVT-01, EVT-02, EVT-05, EVT-06; D-01..D-04): cached feed of all
//     cameras from Room, camera chips, day filter, refresh on tab entry and pull with the 5-minute subtitle.
//   SCOPE: collects EventFeedSource.observe over a 30-day window (now - 30 d .. now + 1 d, moved on every tab entry);
//     one EventSyncSource.refresh per user action (tab entry / pull); filters and labels via EventsFeed; one-shot
//     OpenClip / SessionExpired events. No ticker, no delay loop, no background refresh: the «N мин» label is
//     recomputed only on tab entry, pull, filter change or a DB emission (D-04).
//   DEPENDS: M-EVENT (AlertSyncService, SyncResult), M-DATABASE (EventDao, EventEntity), M-CAMERA-LIST (CameraListPort),
//     M-UI-EVENTS (EventsFeed), M-UI-LIVEVIEW (ArchiveTexts.dayLabel)
//   LINKS: M-UI-EVENTS, V-M-UI-EVENTS
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.ui.events

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yicamalt.camera.CameraListPort
import com.yicamalt.database.EventDao
import com.yicamalt.database.EventEntity
import com.yicamalt.event.AlertSyncService
import com.yicamalt.event.SyncResult
import com.yicamalt.ui.live.ArchiveTexts
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

// START_MODULE_MAP
//   EventFeedSource - port: observe(fromMs, toMs) feed rows (impl RoomEventFeedSource over EventDao.observeFeed).
//   EventSyncSource - port: refresh(): SyncResult (impl AlertEventSyncSource over AlertSyncService).
//   EventsUiModule - Hilt @Binds of both ports (SingletonComponent).
//   CameraChip - chip row item (deviceId, name, selected).
//   EventsUi - screen state (sections, cameras, allSelected, dayLabel, selectedDay, today, daysWithEvents,
//     daysDialogOpen, subtitle, refreshing, emptyText).
//   EventsEvent - one-shot OpenClip(key) | SessionExpired.
//   EventsViewModel - onTabEntered, onPullRefresh, toggleCamera, selectAllCameras, openDays, closeDays, selectDay,
//     clearDay, onCardTap; state, events.
//   ALL_DAYS_LABEL - «Все дни ▾».
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 05-04: initial view-model. Discretion choices: the day button reads «Все дни ▾»
//     with no day selected (the list spans the last days, «Сегодня ▾» would be misleading; D-03 "по умолчанию
//     последние дни без выбора конкретного") and ArchiveTexts.dayLabel with a day selected. The ports are bound in
//     EventsUiModule in this file (not in EventModule) to keep the UI wiring next to its only consumer. The port has
//     no times(fromMs): calendar marks come from the rows of the 30-day window (same span as the cache).
//     Unauthorized emits SessionExpired once per streak (reset by a successful result); NoSession only changes the
//     subtitle.
// END_CHANGE_SUMMARY

const val ALL_DAYS_LABEL = "Все дни ▾"

interface EventFeedSource {
    fun observe(fromMs: Long, toMs: Long): Flow<List<EventEntity>>
}

interface EventSyncSource {
    suspend fun refresh(): SyncResult
}

class RoomEventFeedSource @Inject constructor(private val dao: EventDao) : EventFeedSource {
    override fun observe(fromMs: Long, toMs: Long): Flow<List<EventEntity>> = dao.observeFeed(fromMs, toMs)
}

class AlertEventSyncSource @Inject constructor(private val service: AlertSyncService) : EventSyncSource {
    override suspend fun refresh(): SyncResult = service.refresh()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class EventsUiModule {
    @Binds abstract fun bindFeedSource(impl: RoomEventFeedSource): EventFeedSource
    @Binds abstract fun bindSyncSource(impl: AlertEventSyncSource): EventSyncSource
}

data class CameraChip(val deviceId: String, val name: String, val selected: Boolean) {
    override fun toString(): String = "CameraChip(selected=$selected)"
}

data class EventsUi(
    val sections: List<DaySection> = emptyList(),
    val cameras: List<CameraChip> = emptyList(),
    val allSelected: Boolean = true,
    val dayLabel: String = ALL_DAYS_LABEL,
    val selectedDay: LocalDate? = null,
    val today: LocalDate = LocalDate.MIN,
    val daysWithEvents: Set<LocalDate> = emptySet(),
    val daysDialogOpen: Boolean = false,
    val subtitle: String? = null,
    val refreshing: Boolean = false,
    val emptyText: String? = null,
)

sealed interface EventsEvent {
    data class OpenClip(val key: String) : EventsEvent {
        override fun toString(): String = "OpenClip"
    }
    data object SessionExpired : EventsEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class EventsViewModel internal constructor(
    private val feed: EventFeedSource,
    private val sync: EventSyncSource,
    private val cameraPort: CameraListPort,
    private val clock: () -> Long,
    private val zone: () -> ZoneId,
) : ViewModel() {

    @Inject constructor(feed: EventFeedSource, sync: EventSyncSource, cameraPort: CameraListPort) :
        this(feed, sync, cameraPort, System::currentTimeMillis, ZoneId::systemDefault)

    // Main-thread state (viewModelScope runs on Dispatchers.Main).
    private var rows: List<EventEntity> = emptyList()
    private var cameraNames: Map<String, String> = emptyMap()
    private var selectedCameras: Set<String> = emptySet()
    private var selectedDay: LocalDate? = null
    private var dialogOpen = false
    private var subtitleText: String? = null
    /** Time of the last successful fetch while the subtitle is a freshness label; null for other subtitles. */
    private var fetchAtMs: Long? = null
    private var pullsInFlight = 0
    private var sessionExpiredSent = false

    private val window = MutableStateFlow(windowAt(clock()))
    private val _state = MutableStateFlow(EventsUi())
    val state: StateFlow<EventsUi> = _state.asStateFlow()

    private val channel = Channel<EventsEvent>(Channel.BUFFERED)
    val events: Flow<EventsEvent> = channel.receiveAsFlow()

    init {
        publish()
        viewModelScope.launch {
            window.flatMapLatest { w -> feed.observe(w.first, w.last) }.collect { list ->
                rows = list
                publish()
            }
        }
        loadCameras()
    }

    // START_CONTRACT: onTabEntered
    //   PURPOSE: Tab entry: move the window to now, reload camera names from cache, one refresh (D-04).
    //   INPUTS: none
    //   OUTPUTS: none (state / events)
    //   SIDE_EFFECTS: one EventSyncSource.refresh(); CameraListPort.cachedCameras() (Room only)
    //   LINKS: AlertSyncService.refresh, D-04
    // END_CONTRACT: onTabEntered
    fun onTabEntered() {
        window.value = windowAt(clock())
        loadCameras()
        refresh(trigger = "enter", pull = false)
    }

    // START_CONTRACT: onPullRefresh
    //   PURPOSE: Pull gesture: one refresh; refreshing stays true until it returns.
    //   INPUTS: none
    //   OUTPUTS: none
    //   SIDE_EFFECTS: one EventSyncSource.refresh()
    //   LINKS: AlertSyncService.refresh, D-04
    // END_CONTRACT: onPullRefresh
    fun onPullRefresh() {
        pullsInFlight++
        publish()
        refresh(trigger = "pull", pull = true)
    }

    fun toggleCamera(deviceId: String) {
        selectedCameras = if (deviceId in selectedCameras) selectedCameras - deviceId else selectedCameras + deviceId
        publish(filterChanged = true)
    }

    fun selectAllCameras() {
        selectedCameras = emptySet()
        publish(filterChanged = true)
    }

    fun openDays() {
        dialogOpen = true
        publish()
    }

    fun closeDays() {
        dialogOpen = false
        publish()
    }

    fun selectDay(day: LocalDate) {
        selectedDay = day
        dialogOpen = false
        publish(filterChanged = true)
    }

    fun clearDay() {
        selectedDay = null
        publish(filterChanged = true)
    }

    fun onCardTap(key: String) {
        Timber.d("[Events][vm][BLOCK_OPEN_CLIP] tap")
        channel.trySend(EventsEvent.OpenClip(key))
    }

    private fun loadCameras() {
        viewModelScope.launch {
            val list = try {
                cameraPort.cachedCameras()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.d("[Events][vm][BLOCK_CAMERAS] error=%s", e.javaClass.simpleName)
                return@launch
            }
            cameraNames = list.associate { it.deviceId to it.name }
            publish()
        }
    }

    private fun refresh(trigger: String, pull: Boolean) {
        viewModelScope.launch {
            // START_BLOCK_REFRESH
            val result = try {
                sync.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SyncResult.Failed(e.javaClass.simpleName)
            }
            Timber.d("[Events][vm][BLOCK_REFRESH] trigger=%s result=%s", trigger, result.javaClass.simpleName)
            applyResult(result)
            if (pull) pullsInFlight--
            publish()
            // END_BLOCK_REFRESH
        }
    }

    private fun applyResult(result: SyncResult) {
        val now = clock()
        when (result) {
            is SyncResult.Fetched -> fetchAtMs = now
            is SyncResult.TooEarly -> fetchAtMs = now - result.minutesAgo * MINUTE_MS
            SyncResult.InProgress -> Unit
            else -> fetchAtMs = null
        }
        subtitleText = EventsFeed.subtitle(result, subtitleText)
        when (result) {
            SyncResult.Unauthorized -> if (!sessionExpiredSent) {
                sessionExpiredSent = true
                channel.trySend(EventsEvent.SessionExpired)
            }
            is SyncResult.Fetched, is SyncResult.TooEarly -> sessionExpiredSent = false
            else -> Unit
        }
    }

    private fun publish(filterChanged: Boolean = false) {
        val now = clock()
        val z = zone()
        val today = com.yicamalt.archive.SdTime.localDate(now, z)
        val sections = EventsFeed.build(rows, cameraNames, selectedCameras, selectedDay, z, now)
        val shown = sections.sumOf { it.cards.size }
        val fetchAt = fetchAtMs
        val subtitle = if (fetchAt != null) EventsFeed.freshness((now - fetchAt) / MINUTE_MS) else subtitleText
        val day = selectedDay
        _state.value = EventsUi(
            sections = sections,
            cameras = cameraNames.map { (id, name) ->
                CameraChip(id, name.ifBlank { EventsFeed.UNKNOWN_CAMERA }, id in selectedCameras)
            },
            allSelected = selectedCameras.isEmpty(),
            dayLabel = if (day == null) ALL_DAYS_LABEL else ArchiveTexts.dayLabel(day, today),
            selectedDay = day,
            today = today,
            daysWithEvents = EventsFeed.daysWithEvents(rows.map { it.timeMs }, z),
            daysDialogOpen = dialogOpen,
            subtitle = subtitle,
            refreshing = pullsInFlight > 0,
            emptyText = EventsFeed.emptyText(rows.size, shown),
        )
        if (filterChanged) {
            Timber.d(
                "[Events][vm][BLOCK_FILTER] cameras=%d day=%b shown=%d",
                selectedCameras.size, day != null, shown,
            )
        }
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val DAY_MS = 86_400_000L
        const val WINDOW_DAYS = 30L

        fun windowAt(nowMs: Long): LongRange = (nowMs - WINDOW_DAYS * DAY_MS)..(nowMs + DAY_MS)
    }
}
