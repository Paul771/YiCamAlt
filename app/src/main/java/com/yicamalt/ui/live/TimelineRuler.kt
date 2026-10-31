// FILE: TimelineRuler.kt
// VERSION: 0.2.0
// START_MODULE_CONTRACT
//   PURPOSE: Canvas timeline ruler of one day for the SD archive (SD-03, D-03): recorded segments, ticks, centre
//     cursor; drag pans, pinch zooms (15 min..24 h, default 3 h); on release after a real move it seeks to the centre.
//   SCOPE: composable only; all math in TimelineMath. While the user is not touching it, follows cursorMs: the
//     archive position or the live wall time (about 1 Hz).
//     Value-free logs (no times).
//   DEPENDS: M-UI-LIVEVIEW (TimelineMath), M-SD-ARCHIVE (Segment), Compose foundation / material3, Timber
//   LINKS: M-UI-LIVEVIEW, V-M-UI-LIVEVIEW
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.ui.live

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yicamalt.archive.Segment
import timber.log.Timber
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// START_MODULE_MAP
//   TimelineRuler - day ruler composable (segments, ticks, centre cursor, pinch / pan, release-to-seek)
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.2.0 - GSD plan 04-11: contract only - cursorMs is now fed from ArchiveUi.rulerMs (live wall time
//     in Live for today, archive position in the archive); code unchanged.
//   PREVIOUS: v0.1.0 - GSD plan 04-07: created. Centre state is keyed on the day; while a finger is down the
//     playback cursor does not move the ruler; a pure pinch never seeks (TimelineMath release rule).
// END_CHANGE_SUMMARY

private val CENTER_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

// START_CONTRACT: TimelineRuler
//   PURPOSE: Draw the day ruler and turn gestures into a seek.
//   INPUTS: { segments: List<Segment> - recorded segments of the day; dayStartMs, dayEndMs: Long - day bounds;
//     cursorMs: Long? - position the centre follows while untouched: archive position, or the live wall time (about
//     1 Hz) - null keeps the current centre; initialCenterMs: Long - centre on first show of a day;
//     zone: ZoneId - label zone; onSeek: (Long) -> Unit - centre time after a real move; modifier }
//   OUTPUTS: { Unit - composable, 64 dp high }
//   SIDE_EFFECTS: onSeek; BLOCK_TIMELINE_GESTURE log
//   LINKS: M-UI-LIVEVIEW, TimelineMath, LiveViewModel.archiveSeek
// END_CONTRACT: TimelineRuler
@Composable
fun TimelineRuler(
    segments: List<Segment>,
    dayStartMs: Long,
    dayEndMs: Long,
    cursorMs: Long?,
    initialCenterMs: Long,
    zone: ZoneId,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var centerMs by remember(dayStartMs) { mutableLongStateOf(initialCenterMs.coerceIn(dayStartMs, dayEndMs)) }
    var spanMs by remember { mutableLongStateOf(TimelineMath.DEFAULT_SPAN_MS) }
    var dragging by remember { mutableStateOf(false) }
    val currentOnSeek by rememberUpdatedState(onSeek)
    val currentDayEnd by rememberUpdatedState(dayEndMs)

    LaunchedEffect(cursorMs, dragging) {
        if (!dragging && cursorMs != null) centerMs = cursorMs.coerceIn(dayStartMs, dayEndMs)
    }

    val measurer = rememberTextMeasurer()
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val segmentColor = MaterialTheme.colorScheme.primary
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant
    val cursorColor = MaterialTheme.colorScheme.error
    val tickStyle = TextStyle(color = tickColor, fontSize = 10.sp)
    val centerStyle = TextStyle(color = cursorColor, fontSize = 11.sp)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            // START_BLOCK_TIMELINE_GESTURE
            .pointerInput(dayStartMs) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    dragging = true
                    val startCenter = centerMs
                    var zoomed = false
                    do {
                        val e = awaitPointerEvent()
                        val z = e.calculateZoom()
                        val p = e.calculatePan()
                        if (z != 1f) {
                            spanMs = TimelineMath.zoom(spanMs, z)
                            zoomed = true
                        }
                        if (p.x != 0f) {
                            centerMs = TimelineMath.pan(centerMs, p.x, size.width.toFloat(), spanMs, dayStartMs, currentDayEnd)
                        }
                        e.changes.forEach { if (it.positionChanged()) it.consume() }
                    } while (e.changes.any { it.pressed })
                    dragging = false
                    val width = size.width.coerceAtLeast(1)
                    val seek = TimelineMath.shouldSeekAfterGesture(startCenter, centerMs, spanMs.toFloat() / width)
                    Timber.d("[LiveView][archive-ui][BLOCK_TIMELINE_GESTURE] released=true zoomed=%b seek=%b", zoomed, seek)
                    if (seek) currentOnSeek(centerMs)
                }
            },
        // END_BLOCK_TIMELINE_GESTURE
    ) {
        val w = size.width
        val h = size.height
        val trackTop = 16.dp.toPx()
        val trackH = (h - trackTop) * 0.6f
        val c = centerMs
        val span = spanMs

        drawRect(trackColor, topLeft = Offset(0f, trackTop), size = Size(w, trackH))
        for (s in TimelineMath.visibleSegments(segments, c, span)) {
            val x0 = TimelineMath.xOf(s.startMs, c, span, w).coerceIn(0f, w)
            val x1 = TimelineMath.xOf(s.endMs, c, span, w).coerceIn(0f, w)
            drawRect(segmentColor, topLeft = Offset(x0, trackTop), size = Size((x1 - x0).coerceAtLeast(1f), trackH))
        }
        val tickW = 1.dp.toPx()
        val labelTop = trackTop + trackH + 2.dp.toPx()
        for (t in TimelineMath.ticks(c, span, dayStartMs, dayEndMs, zone)) {
            val x = TimelineMath.xOf(t.ms, c, span, w)
            drawLine(tickColor, Offset(x, trackTop + trackH - 6.dp.toPx()), Offset(x, trackTop + trackH), strokeWidth = tickW)
            val layout = measurer.measure(t.label, tickStyle)
            val lx = (x - layout.size.width / 2f).coerceIn(0f, (w - layout.size.width).coerceAtLeast(0f))
            if (labelTop + layout.size.height <= h + 1f) drawText(layout, topLeft = Offset(lx, labelTop))
        }
        val cx = w / 2f
        drawLine(cursorColor, Offset(cx, trackTop), Offset(cx, trackTop + trackH), strokeWidth = 2.dp.toPx())
        val centerText = CENTER_LABEL.format(Instant.ofEpochMilli(c).atZone(zone))
        val cl = measurer.measure(centerText, centerStyle)
        drawText(cl, topLeft = Offset(cx - cl.size.width / 2f, (trackTop - cl.size.height).coerceAtLeast(0f)))
    }
}
