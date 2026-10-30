// FILE: ArchiveCoordinator.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: One screen state for the SD archive under the live view.
//   SCOPE: RED stub (signatures only).
//   DEPENDS: M-SD-ARCHIVE
//   LINKS: M-UI-LIVEVIEW, V-M-UI-LIVEVIEW
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.ui.live

import com.yicamalt.archive.ArchiveLinkPort
import com.yicamalt.archive.PlaybackController
import com.yicamalt.archive.SdIndexRepository
import com.yicamalt.archive.Segment
import com.yicamalt.camera.TransportType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate
import java.time.ZoneId

// START_MODULE_MAP
//   ArchivePanel, ArchiveUi, ArchiveTexts, ArchiveCoordinator - RED stubs.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 04-06 RED: signatures only.
// END_CHANGE_SUMMARY

enum class ArchivePanel { HIDDEN, LOADING, NO_CARD, CARD_ERROR, LIST_FAILED, NO_RECORDINGS, TIMELINE }

data class ArchiveUi(
    val panel: ArchivePanel, val message: String?, val showRetry: Boolean,
    val today: LocalDate, val selectedDay: LocalDate, val dayLabel: String, val recordedDays: Set<LocalDate>, val daysDialogOpen: Boolean,
    val dayStartMs: Long, val dayEndMs: Long, val segments: List<Segment>,
    val cursorMs: Long?, val inArchive: Boolean, val playing: Boolean, val pauseLabel: String?, val hint: String?, val showLiveButton: Boolean,
)

object ArchiveTexts {
    const val LOADING = ""; const val NO_CARD = ""; const val CARD_ERROR = ""
    const val LIST_FAILED = ""; const val RETRY = ""; const val NO_RECORDINGS = ""
    const val UNSUPPORTED = ""; const val LIVE = ""; const val PAUSE = ""
    const val CONTINUE = ""; const val ENDED = ""; const val START_FAILED = ""
    const val NO_RECORDING_AT = ""; const val TODAY = ""; const val YESTERDAY = ""
    fun dayLabel(day: LocalDate, today: LocalDate): String = error("RED stub")
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
    val ui: StateFlow<ArchiveUi> get() = error("RED stub")
    fun start(): Unit = error("RED stub")
    fun close(): Unit = error("RED stub")
    fun retry(): Unit = error("RED stub")
    fun openDays(): Unit = error("RED stub")
    fun closeDays(): Unit = error("RED stub")
    fun selectDay(day: LocalDate): Unit = error("RED stub")
    fun seek(targetMs: Long): Unit = error("RED stub")
    fun togglePause(): Unit = error("RED stub")
    fun goLive(): Unit = error("RED stub")
    fun onBackground(): Unit = error("RED stub")
}
