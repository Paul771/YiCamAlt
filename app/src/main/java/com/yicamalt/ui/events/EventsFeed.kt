// FILE: EventsFeed.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Pure events-feed logic of the «События» tab (EVT-01, EVT-02, EVT-05, EVT-06; D-02..D-04): cards grouped
//     by local day, camera / day filters, calendar marks, time labels, subtitle and empty texts.
//   SCOPE: no Android, no I/O, no clock reads (now and zone are parameters). Day bounds come from SdTime (calendar
//     arithmetic, DST days are 23 / 25 h). Unknown cameras (uid not in the camera list) show «Камера».
//   DEPENDS: M-EVENT (SyncResult), M-DATABASE (EventEntity), M-SD-ARCHIVE (SdTime), M-UI-LIVEVIEW (ArchiveTexts)
//   LINKS: M-UI-EVENTS, V-M-UI-EVENTS
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.ui.events

import com.yicamalt.archive.SdTime
import com.yicamalt.database.EventEntity
import com.yicamalt.event.SyncResult
import com.yicamalt.ui.live.ArchiveTexts
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// START_MODULE_MAP
//   EventCard - one feed card (key, deviceId, cameraName, knownCamera, timeLabel, timeMs, thumbPath, hasClip);
//     toString hides key / deviceId / cameraName.
//   DaySection - day + header («Сегодня» / «Вчера» / «28 октября») + cards newest first.
//   EventsFeed.build - filter (cameras, day) and group rows into DaySections newest first.
//   EventsFeed.daysWithEvents - local days that have at least one event (calendar marks).
//   EventsFeed.subtitle - refresh result -> subtitle text (InProgress keeps the previous one).
//   EventsFeed.freshness - minutes since the last fetch -> «Обновлено только что» / «Обновлено N мин назад».
//   EventsFeed.emptyText - «Событий пока нет» / «Нет событий для выбранного фильтра» / null.
//   EventsFeed.UNKNOWN_CAMERA - «Камера».
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 05-04: grouping, filters, labels and texts (pure, JVM-tested).
// END_CHANGE_SUMMARY

data class EventCard(
    val key: String,
    val deviceId: String,
    val cameraName: String,
    val knownCamera: Boolean,
    val timeLabel: String,
    val timeMs: Long,
    val thumbPath: String?,
    val hasClip: Boolean,
) {
    override fun toString(): String =
        "EventCard(knownCamera=$knownCamera, hasThumb=${thumbPath != null}, hasClip=$hasClip)"
}

data class DaySection(val day: LocalDate, val header: String, val cards: List<EventCard>)

internal object EventsFeed {
    const val UNKNOWN_CAMERA = "Камера"
    const val EMPTY_NO_EVENTS = "Событий пока нет"
    const val EMPTY_FILTERED = "Нет событий для выбранного фильтра"
    const val OFFLINE = "Нет сети, показан кэш"
    const val SESSION_EXPIRED = "Сессия истекла"
    const val FAILED = "Не удалось обновить, показан кэш"
    const val JUST_NOW = "Обновлено только что"

    private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru"))
    private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    // START_CONTRACT: build
    //   PURPOSE: Filter rows by camera and day and group them into day sections, newest first.
    //   INPUTS: { rows: List<EventEntity> - feed window; cameras: Map<deviceId, name> - known cameras;
    //     selectedCameras: Set<String> - empty = all; selectedDay: LocalDate? - null = the whole window;
    //     zone: ZoneId - display zone; nowMs: Long - wall clock for «Сегодня» / «Вчера» }
    //   OUTPUTS: { List<DaySection> - non-empty sections, days descending, cards descending by time }
    //   SIDE_EFFECTS: none
    //   LINKS: SdTime.dayBounds, SdTime.localDate, D-02, D-03
    // END_CONTRACT: build
    fun build(
        rows: List<EventEntity>,
        cameras: Map<String, String>,
        selectedCameras: Set<String>,
        selectedDay: LocalDate?,
        zone: ZoneId,
        nowMs: Long,
    ): List<DaySection> {
        // START_BLOCK_FILTER_ROWS
        val bounds = selectedDay?.let { SdTime.dayBounds(it, zone) }
        val kept = rows.filter { r ->
            (selectedCameras.isEmpty() || r.deviceId in selectedCameras) && (bounds == null || r.timeMs in bounds)
        }
        // END_BLOCK_FILTER_ROWS
        // START_BLOCK_GROUP_DAYS
        val today = SdTime.localDate(nowMs, zone)
        return kept.sortedByDescending { it.timeMs }
            .groupBy { SdTime.localDate(it.timeMs, zone) }
            .entries
            .sortedByDescending { it.key }
            .map { (day, dayRows) -> DaySection(day, header(day, today), dayRows.map { card(it, cameras, zone) }) }
        // END_BLOCK_GROUP_DAYS
    }

    private fun card(r: EventEntity, cameras: Map<String, String>, zone: ZoneId): EventCard {
        val name = cameras[r.deviceId]
        return EventCard(
            key = r.eventKey,
            deviceId = r.deviceId,
            cameraName = name?.takeIf { it.isNotBlank() } ?: UNKNOWN_CAMERA,
            knownCamera = name != null,
            timeLabel = TIME_FORMAT.format(java.time.Instant.ofEpochMilli(r.timeMs).atZone(zone)),
            timeMs = r.timeMs,
            thumbPath = r.thumbPath,
            hasClip = r.hasClip,
        )
    }

    private fun header(day: LocalDate, today: LocalDate): String = when (day) {
        today -> ArchiveTexts.TODAY
        today.minusDays(1) -> ArchiveTexts.YESTERDAY
        else -> DAY_FORMAT.format(day)
    }

    // START_CONTRACT: daysWithEvents
    //   PURPOSE: Local days that contain at least one event (dots in the day picker).
    //   INPUTS: { times: List<Long> - event times; zone: ZoneId }
    //   OUTPUTS: { Set<LocalDate> }
    //   SIDE_EFFECTS: none
    //   LINKS: CalendarGrid.month, D-03
    // END_CONTRACT: daysWithEvents
    fun daysWithEvents(times: List<Long>, zone: ZoneId): Set<LocalDate> =
        times.mapTo(HashSet()) { SdTime.localDate(it, zone) }

    // START_CONTRACT: subtitle
    //   PURPOSE: Subtitle under the filters for a refresh result (D-04, EVT-06).
    //   INPUTS: { result: SyncResult? - last refresh result; previous: String? - current subtitle }
    //   OUTPUTS: { String? - text; InProgress / null keep [previous]; NoSession -> «Сессия истекла» }
    //   SIDE_EFFECTS: none
    //   LINKS: AlertSyncService.refresh, D-04
    // END_CONTRACT: subtitle
    fun subtitle(result: SyncResult?, previous: String?): String? = when (result) {
        null, SyncResult.InProgress -> previous
        is SyncResult.Fetched -> JUST_NOW
        is SyncResult.TooEarly -> freshness(result.minutesAgo.toLong())
        SyncResult.Offline -> OFFLINE
        SyncResult.Unauthorized, SyncResult.NoSession -> SESSION_EXPIRED
        is SyncResult.Failed -> FAILED
    }

    /** «Обновлено только что» for less than a minute, else «Обновлено N мин назад». */
    fun freshness(minutesAgo: Long): String =
        if (minutesAgo <= 0) JUST_NOW else "Обновлено $minutesAgo мин назад"

    // START_CONTRACT: emptyText
    //   PURPOSE: Empty-state text of the list.
    //   INPUTS: { totalRows: Int - rows in the window; shownRows: Int - rows after the filters }
    //   OUTPUTS: { String? - «Событий пока нет» when the window is empty, «Нет событий для выбранного фильтра» when
    //     the filters hide everything, null when cards are shown }
    //   SIDE_EFFECTS: none
    //   LINKS: EVT-05
    // END_CONTRACT: emptyText
    fun emptyText(totalRows: Int, shownRows: Int): String? = when {
        totalRows == 0 -> EMPTY_NO_EVENTS
        shownRows == 0 -> EMPTY_FILTERED
        else -> null
    }
}
