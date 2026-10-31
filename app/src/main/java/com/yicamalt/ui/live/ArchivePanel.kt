// FILE: ArchivePanel.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Archive part of the camera screen under the video (SD-01..SD-05; D-01, D-02, D-03, D-07): day button,
//     pause / continue and «Живой» buttons, the timeline ruler or the D-07 state text, and the day dialog.
//   SCOPE: thin composables over ArchiveUi (state comes from ArchiveCoordinator via LiveViewModel.archive); the month
//     grid comes from CalendarGrid. Live video keeps playing under every state; nothing here touches the video slot.
//   DEPENDS: M-UI-LIVEVIEW (ArchiveUi, ArchiveTexts, TimelineRuler, TimelineMath, CalendarGrid), Compose material3
//   LINKS: M-UI-LIVEVIEW, V-M-UI-LIVEVIEW
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import timber.log.Timber
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

// START_MODULE_MAP
//   ArchivePanel - composable: day button, pause / continue, «Живой», ruler or D-07 state text, hint
//   DayPickerDialog - composable: month grid dialog, recorded days dotted and tappable, others disabled
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 04-07: custom month grid instead of Material3 DatePicker (BOM 2024.02.00 /
//     material3 1.2 has SelectableDates but no per-day badge; D-02 needs a dot on recorded days).
//     Discretion call: today stays tappable even without recordings so the user can return to the default day (D-02);
//     every other day without recordings is disabled. State boxes are 64 dp high like the ruler (no layout jump).
// END_CHANGE_SUMMARY

private val PANEL_HEIGHT = 64.dp

// START_CONTRACT: ArchivePanel
//   PURPOSE: Render the archive state under the video.
//   INPUTS: { ui: ArchiveUi; zone: ZoneId - display zone; nowMs: Long - wall clock for today's initial centre;
//     onOpenDays, onTogglePause, onLive, onRetry: () -> Unit; onSeek: (Long) -> Unit; modifier }
//   OUTPUTS: { Unit - composable }
//   SIDE_EFFECTS: callbacks only; BLOCK_ARCHIVE_PANEL log on a panel change (panel name only)
//   LINKS: M-UI-LIVEVIEW, LiveViewModel.archiveOpenDays / archiveSeek / archiveTogglePause / archiveGoLive / archiveRetry
// END_CONTRACT: ArchivePanel
@Composable
fun ArchivePanel(
    ui: ArchiveUi,
    zone: ZoneId,
    nowMs: Long,
    onOpenDays: () -> Unit,
    onSeek: (Long) -> Unit,
    onTogglePause: () -> Unit,
    onLive: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // START_BLOCK_ARCHIVE_PANEL
    LaunchedEffect(ui.panel) { Timber.d("[LiveView][archive-ui][BLOCK_ARCHIVE_PANEL] panel=%s", ui.panel.name) }
    if (ui.panel == ArchivePanel.HIDDEN) {
        ui.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = modifier) }
        return
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onOpenDays) { Text(ui.dayLabel) }
            Spacer(Modifier.weight(1f))
            ui.pauseLabel?.let { label ->
                OutlinedButton(onClick = onTogglePause) { Text(label) }
            }
            if (ui.showLiveButton) {
                Spacer(Modifier.width(8.dp))
                Button(onClick = onLive) { Text(ArchiveTexts.LIVE) }
            }
        }
        when (ui.panel) {
            ArchivePanel.IDLE -> Box(modifier = Modifier.fillMaxWidth().height(PANEL_HEIGHT))
            ArchivePanel.LOADING -> Row(
                modifier = Modifier.fillMaxWidth().height(PANEL_HEIGHT),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(ui.message ?: ArchiveTexts.LOADING, style = MaterialTheme.typography.bodyMedium)
            }
            ArchivePanel.NO_CARD, ArchivePanel.CARD_ERROR, ArchivePanel.NO_RECORDINGS -> Box(
                modifier = Modifier.fillMaxWidth().height(PANEL_HEIGHT),
                contentAlignment = Alignment.Center,
            ) {
                Text(ui.message ?: "", style = MaterialTheme.typography.bodyMedium)
            }
            ArchivePanel.LIST_FAILED -> Row(
                modifier = Modifier.fillMaxWidth().height(PANEL_HEIGHT),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(ui.message ?: ArchiveTexts.LIST_FAILED, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onRetry) { Text(ArchiveTexts.RETRY) }
            }
            ArchivePanel.TIMELINE -> TimelineRuler(
                segments = ui.segments,
                dayStartMs = ui.dayStartMs,
                dayEndMs = ui.dayEndMs,
                cursorMs = ui.cursorMs,
                initialCenterMs = TimelineMath.initialCenter(
                    ui.selectedDay == ui.today, nowMs, ui.dayStartMs, ui.dayEndMs, ui.segments,
                ),
                zone = zone,
                onSeek = onSeek,
            )
            ArchivePanel.HIDDEN -> Unit
        }
        ui.hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
    // END_BLOCK_ARCHIVE_PANEL
}

// START_CONTRACT: DayPickerDialog
//   PURPOSE: Month-grid day picker (D-02): recorded days have a dot and are tappable, other days greyed out.
//   INPUTS: { ui: ArchiveUi - today / selectedDay / recordedDays; onSelect: (LocalDate) -> Unit - enabled cells only;
//     onDismiss: () -> Unit }
//   OUTPUTS: { Unit - composable dialog }
//   SIDE_EFFECTS: callbacks only
//   LINKS: M-UI-LIVEVIEW, CalendarGrid, LiveViewModel.archiveSelectDay / archiveCloseDays
// END_CONTRACT: DayPickerDialog
@Composable
fun DayPickerDialog(ui: ArchiveUi, onSelect: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val months = remember(ui.recordedDays, ui.today) { CalendarGrid.monthsWithRecordings(ui.recordedDays, ui.today) }
    var month by remember(ui.selectedDay) { mutableStateOf(YearMonth.from(ui.selectedDay)) }
    val cells = remember(month, ui.today, ui.recordedDays, ui.selectedDay) {
        CalendarGrid.month(month, ui.today, ui.recordedDays, ui.selectedDay)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
        title = {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { month = month.minusMonths(1) }, enabled = month > months.first()) {
                    Icon(Icons.Filled.ChevronLeft, contentDescription = "Предыдущий месяц")
                }
                Text(
                    CalendarGrid.title(month),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
                IconButton(onClick = { month = month.plusMonths(1) }, enabled = month < months.last()) {
                    Icon(Icons.Filled.ChevronRight, contentDescription = "Следующий месяц")
                }
            }
        },
        text = {
            Column {
                Row {
                    for (h in CalendarGrid.weekdayHeaders()) {
                        Box(Modifier.size(width = 40.dp, height = 24.dp), contentAlignment = Alignment.Center) {
                            Text(h, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                for (week in cells.chunked(7)) {
                    Row {
                        for (cell in week) DayCellView(cell, onSelect)
                    }
                }
            }
        },
    )
}

@Composable
private fun DayCellView(cell: DayCell, onSelect: (LocalDate) -> Unit) {
    val date = cell.date
    if (date == null) {
        Spacer(Modifier.size(40.dp))
        return
    }
    val base = Modifier.size(40.dp)
    val clickable = if (cell.enabled) base.clickable { onSelect(date) } else base.alpha(0.38f)
    Box(modifier = clickable, contentAlignment = Alignment.Center) {
        Box(
            modifier = if (cell.selected) {
                Modifier.size(34.dp).background(MaterialTheme.colorScheme.primary, CircleShape)
            } else {
                Modifier.size(34.dp)
            },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (cell.isToday) FontWeight.Bold else FontWeight.Normal,
                    color = if (cell.selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                )
                if (cell.marked) {
                    Box(
                        Modifier
                            .padding(top = 1.dp)
                            .size(4.dp)
                            .background(
                                if (cell.selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                                CircleShape,
                            ),
                    )
                }
            }
        }
    }
}
