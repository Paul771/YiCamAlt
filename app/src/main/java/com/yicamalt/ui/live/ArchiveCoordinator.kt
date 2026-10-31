// FILE: ArchiveCoordinator.kt
// VERSION: 0.4.0
// START_MODULE_CONTRACT
//   PURPOSE: One screen state for the SD archive under the live view: card status, recorded days, the selected day's
//     timeline and playback (SD-01..SD-05; D-01, D-02, D-04..D-07). Live video keeps running under every state.
//   SCOPE: ArchivePanel / ArchiveUi / ArchiveTexts; ArchiveCoordinator: load on the first liveEpoch (816 / 817 then
//     index), retry, days dialog with a stale-index refresh, day selection, seek / pause / continue / live with
//     forced mute (D-05), background pause, TUTK hide rule (D-06), midnight crossing, ruler position (archive
//     position or live wall time), «Живой» back to today, Live day roll-over at local midnight. Value-free logs.
//     Refresh: first liveEpoch full load; index-only refresh on every later liveEpoch, every refreshMs while today is
//     shown / healthy / foreground, before a seek (plan 04-12 Task 2), on openDays when stale; never overlapping;
//     failure keeps the old list.
//   DEPENDS: M-SD-ARCHIVE (ArchiveLinkPort, ArchiveSupport, SdIndexRepository, PlaybackController, SdTimeline, SdTime),
//     camera (TransportType), kotlinx.coroutines, Timber
//   LINKS: M-UI-LIVEVIEW, V-M-UI-LIVEVIEW, M-SD-ARCHIVE
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.ui.live

import com.yicamalt.archive.ArchiveLinkPort
import com.yicamalt.archive.ArchiveSupport
import com.yicamalt.archive.FailReason
import com.yicamalt.archive.IndexResult
import com.yicamalt.archive.PauseReason
import com.yicamalt.archive.PlaybackController
import com.yicamalt.archive.PlaybackState
import com.yicamalt.archive.SdIndexRepository
import com.yicamalt.archive.SdStatus
import com.yicamalt.archive.SdTime
import com.yicamalt.archive.SdTimeline
import com.yicamalt.archive.Segment
import com.yicamalt.camera.TransportType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// START_MODULE_MAP
//   ArchivePanel - HIDDEN | IDLE | LOADING | NO_CARD | CARD_ERROR | LIST_FAILED | NO_RECORDINGS | TIMELINE.
//   ArchiveUi - archive part of the camera screen (panel + message + retry, days, timeline, cursor, rulerMs, playback labels);
//     ArchiveUi.initial(today, dayStartMs, dayEndMs) - IDLE state before the coordinator exists.
//   ArchiveTexts - Russian archive texts (D-07) and dayLabel(day, today).
//   ArchiveCoordinator - ui, start, close, retry, openDays, closeDays, selectDay, seek, togglePause, goLive, onBackground;
//     internal refresh policy (BLOCK_ARCHIVE_REFRESH: launchLoad reasons, shouldPoll).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.4.0 - GSD plan 04-12 (W-1): today's index is refreshed (index only, BLOCK_ARCHIVE_REFRESH) on
//     every liveEpoch increase after the first and every refreshMs (60 s) while today's TIMELINE / NO_RECORDINGS
//     panel is shown, the link is healthy and the app is not backgrounded (onBackground sets the flag, the next
//     liveEpoch increase clears it). The poll counts from the last success or attempt, so a failed refresh waits
//     for the next slot and keeps the old list; a refresh never starts while another load runs. Discretion: today's
//     NO_RECORDINGS panel is polled too, so a fresh card's first recording appears without user action.
//   PREVIOUS: v0.3.0 - GSD plan 04-11 (device defect 1): ArchiveUi.rulerMs - archive position in the archive, wall
//     clock floored to 1 s in Live for today; «Живой» selects today; the Live day rolls over at local midnight.
//     cursorMs semantics unchanged. (device defect 2): ArchivePanel.IDLE; the spinner (LOADING) is shown only while
//     a status / index fetch is in flight on a healthy link; LIST_FAILED only after a real failure; liveEpoch reload
//     of LOADING / LIST_FAILED unchanged.
//   EARLIER: v0.2.0 - GSD plan 04-06 GREEN. INVARIANTS: nothing is sent before the first liveEpoch > 0; a full
//     load (816 then index) runs on the first liveEpoch and again on a liveEpoch / retry while the last load ended in
//     LOADING / LIST_FAILED; NoCard / Abnormal never ask for the index; a stale-index refresh (openDays) keeps the
//     old data on failure. Sound is forced off on entering the archive and on «Живой» (D-05). TUTK hide (D-06): only
//     on Failed(NO_FRAMES) with no Playing ever reached, the link healthy and on the same liveEpoch for the whole
//     start window (liveThroughout: set on a transition into Starting from a non-Starting state, cleared by any
//     unhealthy observation or a liveEpoch change) AND units of any generation arriving during the window (checker
//     addendum: no units = connection problem, left to the supervisor). Paused(BACKGROUND) survives a reconnect as
//     a «Продолжить» marker. The selected day follows the cursor only when the cursor's day changes.
//   EARLIER: v0.1.0 - GSD plan 04-06 RED: signatures only.
// END_CHANGE_SUMMARY

// IDLE - nothing known yet and nothing being fetched (no link, failed / retrying / waiting link, or the last load
// ended NotReady): empty area, no text, no spinner. Display-only: the coordinator keeps LOADING internally.
enum class ArchivePanel { HIDDEN, IDLE, LOADING, NO_CARD, CARD_ERROR, LIST_FAILED, NO_RECORDINGS, TIMELINE }

// cursorMs - archive position (null in Live). rulerMs - where the ruler centre follows: the archive position
// (Starting.atMs / Playing / Paused / Failed position) in the archive; in Live, the wall clock floored to whole
// seconds while the selected day is today; null otherwise (ruler keeps its own centre).
data class ArchiveUi(
    val panel: ArchivePanel, val message: String?, val showRetry: Boolean,
    val today: LocalDate, val selectedDay: LocalDate, val dayLabel: String, val recordedDays: Set<LocalDate>, val daysDialogOpen: Boolean,
    val dayStartMs: Long, val dayEndMs: Long, val segments: List<Segment>,
    val cursorMs: Long?, val rulerMs: Long?, val inArchive: Boolean, val playing: Boolean, val pauseLabel: String?, val hint: String?, val showLiveButton: Boolean,
) {
    override fun toString() = "ArchiveUi(panel=$panel, segments=${segments.size}, inArchive=$inArchive, playing=$playing)"

    companion object {
        // START_CONTRACT: initial
        //   PURPOSE: IDLE archive state for a day before any coordinator data (nothing fetched yet, no spinner).
        //   INPUTS: { today: LocalDate; dayStartMs / dayEndMs: Long - bounds of today in the display zone }
        //   OUTPUTS: { ArchiveUi - panel IDLE, message null, no retry, no playback }
        //   SIDE_EFFECTS: none
        //   LINKS: M-UI-LIVEVIEW
        // END_CONTRACT: initial
        fun initial(today: LocalDate, dayStartMs: Long, dayEndMs: Long) = ArchiveUi(
            panel = ArchivePanel.IDLE, message = null, showRetry = false,
            today = today, selectedDay = today, dayLabel = ArchiveTexts.dayLabel(today, today), recordedDays = emptySet(),
            daysDialogOpen = false, dayStartMs = dayStartMs, dayEndMs = dayEndMs, segments = emptyList(),
            cursorMs = null, rulerMs = null, inArchive = false, playing = false, pauseLabel = null, hint = null, showLiveButton = false,
        )
    }
}

object ArchiveTexts {
    const val LOADING = "Загрузка записей…"; const val NO_CARD = "Нет SD-карты"; const val CARD_ERROR = "SD-карта не готова"
    const val LIST_FAILED = "Не удалось получить список"; const val RETRY = "Повторить"; const val NO_RECORDINGS = "За этот день записей нет"
    const val UNSUPPORTED = "Архив SD на этой камере недоступен"; const val LIVE = "Живой"; const val PAUSE = "Пауза"
    const val CONTINUE = "Продолжить"; const val ENDED = "Записи закончились"; const val START_FAILED = "Не удалось начать воспроизведение"
    const val NO_RECORDING_AT = "Нет записи в это время"; const val TODAY = "Сегодня"; const val YESTERDAY = "Вчера"

    private const val ARROW = " ▾"
    private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru"))

    // START_CONTRACT: dayLabel
    //   PURPOSE: Day button text.
    //   INPUTS: { day: LocalDate - selected day; today: LocalDate }
    //   OUTPUTS: { String - «Сегодня ▾» | «Вчера ▾» | «28 октября ▾» }
    //   SIDE_EFFECTS: none
    //   LINKS: D-02
    // END_CONTRACT: dayLabel
    fun dayLabel(day: LocalDate, today: LocalDate): String = when (day) {
        today -> TODAY + ARROW
        today.minusDays(1) -> YESTERDAY + ARROW
        else -> DAY_FORMAT.format(day) + ARROW
    }
}

class ArchiveCoordinator(
    private val port: ArchiveLinkPort,
    private val transport: TransportType?,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val wallClock: () -> Long,
    private val zone: ZoneId,
    private val forceMute: () -> Unit,
    private val repository: SdIndexRepository = SdIndexRepository(port),
    private val controller: PlaybackController = PlaybackController(port, scope, clock, wallClock),
    private val refreshMs: Long = 60_000,
) {
    private companion object {
        const val TICK_MS = 500L
    }

    private val lock = Any()
    private var started = false
    private var closed = false
    private var jobs: List<Job> = emptyList()
    private var loadJob: Job? = null

    private var panel = ArchivePanel.LOADING
    private var fetching = false // a status / index load is in flight
    private var hidden = false
    private var all: List<Segment> = emptyList()
    private var recordedDays: Set<LocalDate> = emptySet()
    private var selectedDay: LocalDate = SdTime.localDate(wallClock(), zone)
    private var lastToday: LocalDate = selectedDay
    private var daysDialogOpen = false
    private var loadedAt: Long? = null
    private var refreshAttemptAt: Long? = null // last index-only refresh start (success or not)
    private var backgrounded = false // set by onBackground, cleared by the next liveEpoch increase
    private var lastEpoch = 0

    // Playback tracking for the TUTK hide rule and the cursor day.
    private var prev: PlaybackState = PlaybackState.Live
    private var everPlayed = false
    private var liveThroughout = false
    private var startEpoch = 0
    private var unitsAtStart = 0L
    private var unitsSeen = false
    private var lastCursorDay: LocalDate? = null

    private val _ui = MutableStateFlow(build())
    val ui: StateFlow<ArchiveUi> = _ui.asStateFlow()

    // START_CONTRACT: start
    //   PURPOSE: Begin: unsupported transport -> HIDDEN; else start the playback controller, follow liveEpoch and
    //     playback state, run the 500 ms health tick.
    //   INPUTS: none
    //   OUTPUTS: none
    //   SIDE_EFFECTS: coroutines in scope until close(); loads on the first liveEpoch > 0
    //   LINKS: ArchiveSupport, PlaybackController.start
    // END_CONTRACT: start
    fun start() {
        synchronized(lock) {
            if (closed || started) return
            started = true
            if (!ArchiveSupport.supports(transport)) {
                hidden = true
                panel = ArchivePanel.HIDDEN
                Timber.i("[LiveView][archive][BLOCK_ARCHIVE_HIDE] reason=unsupported-transport")
                publish()
                return
            }
            controller.start()
            lastEpoch = port.liveEpoch.value
            val epochs = scope.launch(start = CoroutineStart.UNDISPATCHED) { port.liveEpoch.collect { onEpoch(it) } }
            val states = scope.launch(start = CoroutineStart.UNDISPATCHED) { controller.state.collect { onPlayback(it) } }
            val ticker = scope.launch {
                while (isActive) {
                    delay(TICK_MS)
                    tick()
                }
            }
            jobs = listOf(epochs, states, ticker)
        }
    }

    // START_CONTRACT: close
    //   PURPOSE: Stop loading, playback tracking and the playback controller; idempotent.
    //   INPUTS: none
    //   OUTPUTS: none
    //   SIDE_EFFECTS: cancels own jobs; controller.close()
    //   LINKS: PlaybackController.close
    // END_CONTRACT: close
    fun close() {
        val toCancel = synchronized(lock) {
            if (closed) return
            closed = true
            fetching = false
            val j = jobs + listOfNotNull(loadJob)
            jobs = emptyList()
            loadJob = null
            j
        }
        toCancel.forEach { it.cancel() }
        controller.close()
    }

    // START_CONTRACT: retry
    //   PURPOSE: «Повторить»: full status + index load again.
    //   INPUTS: none
    //   OUTPUTS: none
    //   SIDE_EFFECTS: may launch a load
    //   LINKS: BLOCK_ARCHIVE_LOAD
    // END_CONTRACT: retry
    fun retry() {
        synchronized(lock) {
            if (!active()) return
            if (loadJob?.isActive == true) return
            launchLoad(full = true, reason = "retry")
        }
    }

    // START_CONTRACT: openDays
    //   PURPOSE: Open the days dialog; refresh the index when it is older than refreshMs.
    //   INPUTS: none
    //   OUTPUTS: none
    //   SIDE_EFFECTS: may launch an index refresh (or a full load after LIST_FAILED)
    //   LINKS: D-02
    // END_CONTRACT: openDays
    fun openDays() {
        synchronized(lock) {
            if (!active()) return
            daysDialogOpen = true
            if (loadJob?.isActive != true) {
                val at = loadedAt
                when {
                    panel == ArchivePanel.LIST_FAILED -> launchLoad(full = true, reason = "days")
                    at != null && clock() - at > refreshMs -> launchLoad(full = false, reason = "days")
                }
            }
            publish()
        }
    }

    fun closeDays() {
        synchronized(lock) {
            daysDialogOpen = false
            publish()
        }
    }

    // START_CONTRACT: selectDay
    //   PURPOSE: Show another day's timeline; only today or a day with recordings.
    //   INPUTS: { day: LocalDate }
    //   OUTPUTS: none
    //   SIDE_EFFECTS: ui update
    //   LINKS: D-02, SdTimeline.segmentsForDay
    // END_CONTRACT: selectDay
    fun selectDay(day: LocalDate) {
        synchronized(lock) {
            if (!active()) return
            if (day != today() && day !in recordedDays) return
            selectedDay = day
            refreshDayPanel()
            publish()
        }
    }

    // START_CONTRACT: seek
    //   PURPOSE: Play the archive from a timeline instant; entering the archive forces sound off (D-05).
    //   INPUTS: { targetMs: Long - epoch ms }
    //   OUTPUTS: none
    //   SIDE_EFFECTS: forceMute() when coming from live; PlaybackController.seek
    //   LINKS: PlaybackController.seek
    // END_CONTRACT: seek
    fun seek(targetMs: Long) {
        if (!synchronized(lock) { active() }) return
        if (controller.state.value == PlaybackState.Live) forceMute()
        controller.seek(targetMs)
        onPlayback(controller.state.value)
    }

    // START_CONTRACT: togglePause
    //   PURPOSE: «Пауза» / «Продолжить».
    //   INPUTS: none
    //   OUTPUTS: none
    //   SIDE_EFFECTS: Starting / Playing -> pause; Paused / Failed -> resume (fresh seek at the saved position)
    //   LINKS: PlaybackController.pause, PlaybackController.resume
    // END_CONTRACT: togglePause
    fun togglePause() {
        if (!synchronized(lock) { active() }) return
        when (controller.state.value) {
            is PlaybackState.Starting, is PlaybackState.Playing -> controller.pause()
            is PlaybackState.Paused, is PlaybackState.Failed -> controller.resume()
            PlaybackState.Live -> Unit
        }
        onPlayback(controller.state.value)
    }

    // START_CONTRACT: goLive
    //   PURPOSE: «Живой»: back to live video with sound forced off (D-05).
    //   INPUTS: none
    //   OUTPUTS: none
    //   SIDE_EFFECTS: forceMute(); PlaybackController.goLive
    //   LINKS: PlaybackController.goLive
    // END_CONTRACT: goLive
    fun goLive() {
        if (!synchronized(lock) { active() }) return
        forceMute()
        controller.goLive()
        onPlayback(controller.state.value)
    }

    // START_CONTRACT: onBackground
    //   PURPOSE: App to background: archive playback becomes Paused(BACKGROUND) at the same position (D-05).
    //   INPUTS: none
    //   OUTPUTS: none
    //   SIDE_EFFECTS: PlaybackController.onSessionStopped; stops the periodic index poll until the next liveEpoch
    //   LINKS: D-05, BLOCK_ARCHIVE_REFRESH
    // END_CONTRACT: onBackground
    fun onBackground() {
        synchronized(lock) {
            if (!started || closed) return
            backgrounded = true // no index polling until the next liveEpoch increase
        }
        controller.onSessionStopped()
        onPlayback(controller.state.value)
    }

    // ---- internals ----

    private fun active() = started && !closed && !hidden

    private fun today(): LocalDate = SdTime.localDate(wallClock(), zone)

    private fun onEpoch(epoch: Int) {
        synchronized(lock) {
            if (closed || hidden) return
            if (epoch != lastEpoch && controller.state.value is PlaybackState.Starting) liveThroughout = false
            val prevEpoch = lastEpoch
            lastEpoch = epoch
            val needLoad = epoch > 0 && loadJob?.isActive != true &&
                (panel == ArchivePanel.LOADING || panel == ArchivePanel.LIST_FAILED)
            if (needLoad) launchLoad(full = true, reason = "first")
            val increased = epoch > prevEpoch && epoch > 0
            if (increased) backgrounded = false // a resumed link = foreground again
            if (increased && !needLoad && loadJob?.isActive != true &&
                (panel == ArchivePanel.TIMELINE || panel == ArchivePanel.NO_RECORDINGS)
            ) {
                launchLoad(full = false, reason = "epoch")
            }
        }
    }

    // Under lock. Reasons: first, retry, epoch, periodic, days, seek.
    private fun launchLoad(full: Boolean, reason: String) {
        // START_BLOCK_ARCHIVE_REFRESH
        Timber.i("[LiveView][archive][BLOCK_ARCHIVE_REFRESH] reason=%s full=%b", reason, full)
        if (full && panel != ArchivePanel.LOADING) {
            panel = ArchivePanel.LOADING
            publish()
        }
        if (!full) refreshAttemptAt = clock()
        fetching = true
        loadJob = scope.launch { load(full) }
        // END_BLOCK_ARCHIVE_REFRESH
    }

    // Under lock: today's list is due for an index-only refresh (refreshMs since the last success or attempt).
    private fun shouldPoll(): Boolean {
        val last = maxOf(loadedAt ?: return false, refreshAttemptAt ?: Long.MIN_VALUE)
        return !backgrounded && loadJob?.isActive != true && port.linkHealthy() &&
            (panel == ArchivePanel.TIMELINE || panel == ArchivePanel.NO_RECORDINGS) &&
            selectedDay == today() && clock() - last >= refreshMs
    }

    private suspend fun load(full: Boolean) {
        // START_BLOCK_ARCHIVE_LOAD
        val status: SdStatus? = if (full) repository.fetchStatus() else null
        val index: IndexResult? = when (status) {
            SdStatus.NoCard, is SdStatus.Abnormal -> null
            else -> repository.fetchIndex(wallClock())
        }
        synchronized(lock) {
            fetching = false
            if (closed || hidden) return
            when {
                status == SdStatus.NoCard -> panel = ArchivePanel.NO_CARD
                status is SdStatus.Abnormal -> panel = ArchivePanel.CARD_ERROR
                index is IndexResult.Ok -> {
                    all = index.segments
                    controller.setSegments(all)
                    recordedDays = SdTimeline.daysWithRecordings(all, zone)
                    loadedAt = clock()
                    panel = ArchivePanel.TIMELINE
                    refreshDayPanel()
                }
                !full -> Unit // refresh failure keeps the old index
                index == IndexResult.NotReady -> panel = ArchivePanel.LOADING
                else -> panel = ArchivePanel.LIST_FAILED
            }
            Timber.i(
                "[LiveView][archive][BLOCK_ARCHIVE_LOAD] status=%s index=%s days=%d panel=%s",
                status?.toString() ?: "skipped", index?.toString() ?: "skipped", recordedDays.size, panel.name,
            )
            publish()
        }
        // END_BLOCK_ARCHIVE_LOAD
    }

    // Under lock: TIMELINE / NO_RECORDINGS follow the selected day.
    private fun refreshDayPanel() {
        if (panel != ArchivePanel.TIMELINE && panel != ArchivePanel.NO_RECORDINGS) return
        panel = if (SdTimeline.segmentsForDay(all, selectedDay, zone).isEmpty()) ArchivePanel.NO_RECORDINGS else ArchivePanel.TIMELINE
    }

    private fun tick() {
        synchronized(lock) {
            if (closed || hidden) return
            val s = controller.state.value
            if (s is PlaybackState.Starting && port.linkHealthy() && port.framesIn() != unitsAtStart) unitsSeen = true
            if (shouldPoll()) launchLoad(full = false, reason = "periodic")
        }
        onPlayback(controller.state.value)
    }

    private fun onPlayback(s: PlaybackState) {
        var hideNow = false
        synchronized(lock) {
            if (closed) return
            if (s !== prev) {
                when (s) {
                    is PlaybackState.Playing -> everPlayed = true
                    is PlaybackState.Starting -> if (prev !is PlaybackState.Starting) {
                        liveThroughout = true
                        startEpoch = port.liveEpoch.value
                        unitsAtStart = port.framesIn()
                        unitsSeen = false
                    }
                    is PlaybackState.Failed -> if (s.reason == FailReason.NO_FRAMES && prev !is PlaybackState.Failed) {
                        hideNow = shouldHide()
                    }
                    PlaybackState.Live -> if (prev != PlaybackState.Live) {
                        // «Живой» returns the ruler to today.
                        selectedDay = today()
                        refreshDayPanel()
                    }
                    else -> Unit
                }
                prev = s
            }
            if (s == PlaybackState.Live) {
                // Live day roll-over at local midnight: the selected today becomes the new today.
                val t = today()
                if (t != lastToday) {
                    if (selectedDay == lastToday) {
                        selectedDay = t
                        refreshDayPanel()
                    }
                    lastToday = t
                }
            }
            if (s is PlaybackState.Starting && (!port.linkHealthy() || port.liveEpoch.value != startEpoch)) {
                liveThroughout = false
            }
            if (hideNow) {
                hidden = true
                panel = ArchivePanel.HIDDEN
                daysDialogOpen = false
            }
            followCursorDay(cursorOf(s))
            publish()
        }
        if (hideNow) controller.goLive()
    }

    // Under lock.
    private fun shouldHide(): Boolean {
        // START_BLOCK_ARCHIVE_TUTK_HIDE
        val unitsArrived = unitsSeen || port.framesIn() != unitsAtStart
        val hide = transport == TransportType.TUTK && !everPlayed && liveThroughout && unitsArrived
        Timber.i(
            "[LiveView][archive][BLOCK_ARCHIVE_TUTK_HIDE] transport=%s everPlayed=%b liveThroughout=%b units=%b hide=%b",
            transport?.name ?: "none", everPlayed, liveThroughout, unitsArrived, hide,
        )
        return hide
        // END_BLOCK_ARCHIVE_TUTK_HIDE
    }

    // Under lock: the selected day follows the cursor only when the cursor's day changes (midnight crossing).
    private fun followCursorDay(cursor: Long?) {
        if (cursor == null) {
            lastCursorDay = null
            return
        }
        val d = SdTime.localDate(cursor, zone)
        if (d == lastCursorDay) return
        lastCursorDay = d
        if (d != selectedDay) {
            selectedDay = d
            refreshDayPanel()
        }
    }

    private fun cursorOf(s: PlaybackState): Long? = when (s) {
        PlaybackState.Live -> null
        is PlaybackState.Starting -> s.atMs
        is PlaybackState.Playing -> s.positionMs
        is PlaybackState.Paused -> s.positionMs
        is PlaybackState.Failed -> s.positionMs
    }

    // Under lock.
    private fun liveRulerMs(): Long? = if (selectedDay == today()) wallClock() / 1000 * 1000 else null

    private fun rulerOf(s: PlaybackState): Long? = if (s == PlaybackState.Live) liveRulerMs() else cursorOf(s)

    // Under lock (or from the constructor).
    private fun publish() {
        _ui.value = build()
    }

    private fun build(): ArchiveUi {
        val s = controller.state.value
        val today = today()
        val bounds = SdTime.dayBounds(selectedDay, zone)
        val inArchive = s != PlaybackState.Live
        // The spinner only while a fetch is in flight on a healthy link; otherwise an empty IDLE area.
        val shown = if (panel == ArchivePanel.LOADING && !(fetching && port.linkHealthy())) ArchivePanel.IDLE else panel
        val message = when (shown) {
            ArchivePanel.HIDDEN -> ArchiveTexts.UNSUPPORTED
            ArchivePanel.IDLE -> null
            ArchivePanel.LOADING -> ArchiveTexts.LOADING
            ArchivePanel.NO_CARD -> ArchiveTexts.NO_CARD
            ArchivePanel.CARD_ERROR -> ArchiveTexts.CARD_ERROR
            ArchivePanel.LIST_FAILED -> ArchiveTexts.LIST_FAILED
            ArchivePanel.NO_RECORDINGS -> ArchiveTexts.NO_RECORDINGS
            ArchivePanel.TIMELINE -> null
        }
        val pauseLabel = when (s) {
            PlaybackState.Live -> null
            is PlaybackState.Starting, is PlaybackState.Playing -> ArchiveTexts.PAUSE
            is PlaybackState.Paused, is PlaybackState.Failed -> ArchiveTexts.CONTINUE
        }
        val hint = when {
            s is PlaybackState.Paused && s.reason == PauseReason.ENDED -> ArchiveTexts.ENDED
            s is PlaybackState.Failed && s.reason == FailReason.NO_RECORDING -> ArchiveTexts.NO_RECORDING_AT
            s is PlaybackState.Failed -> ArchiveTexts.START_FAILED
            else -> null
        }
        return ArchiveUi(
            panel = shown,
            message = message,
            showRetry = shown == ArchivePanel.LIST_FAILED,
            today = today,
            selectedDay = selectedDay,
            dayLabel = ArchiveTexts.dayLabel(selectedDay, today),
            recordedDays = recordedDays,
            daysDialogOpen = daysDialogOpen,
            dayStartMs = bounds.first,
            dayEndMs = bounds.last + 1,
            segments = if (shown == ArchivePanel.HIDDEN) emptyList() else SdTimeline.segmentsForDay(all, selectedDay, zone),
            cursorMs = cursorOf(s),
            rulerMs = rulerOf(s),
            inArchive = inArchive,
            playing = s is PlaybackState.Playing,
            pauseLabel = pauseLabel,
            hint = hint,
            showLiveButton = inArchive,
        )
    }
}
