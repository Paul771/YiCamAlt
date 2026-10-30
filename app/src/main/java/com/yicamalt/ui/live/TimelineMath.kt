// FILE: TimelineMath.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Pure archive timeline ruler math (SD-03, D-03): visible span, zoom, pan, x mapping, ticks, initial
//     centre and the release-to-seek rule. No Compose; TimelineRuler maps the results onto a Canvas.
//   SCOPE: span clamp 15 min..24 h (default 3 h); zoom by a pinch factor; pan in px -> ms (drag right = earlier),
//     centre clamped to the day; xOf; overlap filter; tick step 10 min / 1 h / 3 h with local HH:mm labels;
//     initial centre for today / past days; a gesture seeks only when the centre really moved.
//   DEPENDS: M-SD-ARCHIVE (Segment), java.time
//   LINKS: M-UI-LIVEVIEW, V-M-UI-LIVEVIEW
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.ui.live

import com.yicamalt.archive.Segment
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToLong

// START_MODULE_MAP
//   TimelineMath - DEFAULT_SPAN_MS / MIN_SPAN_MS / MAX_SPAN_MS / SEEK_MIN_PX / SEEK_MIN_MS, Tick, clampSpan, zoom, pan,
//     xOf, visibleSegments, tickStepMs, ticks, initialCenter, shouldSeekAfterGesture
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 04-07 GREEN: ruler math. Ticks are dayStart + k * step (so DST-free local hours
//     for whole-hour zones); a pure pinch zoom never seeks (startCenter == endCenter).
//   PREVIOUS: v0.0.1 - GSD plan 04-07 RED: signatures only.
// END_CHANGE_SUMMARY

object TimelineMath {
    const val DEFAULT_SPAN_MS = 3 * 3_600_000L
    const val MIN_SPAN_MS = 15 * 60_000L
    const val MAX_SPAN_MS = 24 * 3_600_000L
    const val SEEK_MIN_PX = 4f
    const val SEEK_MIN_MS = 2_000L

    private const val TEN_MIN = 10 * 60_000L
    private const val HOUR = 3_600_000L
    private val LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    data class Tick(val ms: Long, val label: String)

    // START_CONTRACT: clampSpan
    //   PURPOSE: Keep the visible span in MIN_SPAN_MS..MAX_SPAN_MS.
    //   INPUTS: { spanMs: Long }
    //   OUTPUTS: { Long - clamped span }
    //   SIDE_EFFECTS: none
    //   LINKS: D-03
    // END_CONTRACT: clampSpan
    fun clampSpan(spanMs: Long): Long = spanMs.coerceIn(MIN_SPAN_MS, MAX_SPAN_MS)

    // START_CONTRACT: zoom
    //   PURPOSE: Apply a multiplicative pinch factor (>1 = zoom in = shorter span).
    //   INPUTS: { spanMs: Long; factor: Float - calculateZoom() of one pointer event }
    //   OUTPUTS: { Long - clamped span; unchanged for a non-positive / NaN factor }
    //   SIDE_EFFECTS: none
    //   LINKS: D-03
    // END_CONTRACT: zoom
    fun zoom(spanMs: Long, factor: Float): Long {
        if (factor.isNaN() || factor <= 0f) return clampSpan(spanMs)
        return clampSpan((spanMs / factor.toDouble()).toLong())
    }

    // START_CONTRACT: pan
    //   PURPOSE: Move the centre by a horizontal drag; dragging right shows earlier time.
    //   INPUTS: { centerMs: Long; dxPx: Float; widthPx: Float; spanMs: Long; dayStartMs / dayEndMs: Long - day bounds }
    //   OUTPUTS: { Long - new centre clamped to [dayStartMs, dayEndMs]; unchanged for a non-positive width }
    //   SIDE_EFFECTS: none
    //   LINKS: D-03
    // END_CONTRACT: pan
    fun pan(centerMs: Long, dxPx: Float, widthPx: Float, spanMs: Long, dayStartMs: Long, dayEndMs: Long): Long {
        if (widthPx <= 0f || dxPx.isNaN()) return centerMs.coerceIn(dayStartMs, dayEndMs)
        val deltaMs = (dxPx.toDouble() * spanMs / widthPx).roundToLong()
        return (centerMs - deltaMs).coerceIn(dayStartMs, dayEndMs)
    }

    // START_CONTRACT: xOf
    //   PURPOSE: Map a time onto the ruler x coordinate (centre at width / 2).
    //   INPUTS: { ms: Long; centerMs: Long; spanMs: Long; widthPx: Float }
    //   OUTPUTS: { Float - x in px, may be outside 0..width }
    //   SIDE_EFFECTS: none
    //   LINKS: D-03
    // END_CONTRACT: xOf
    fun xOf(ms: Long, centerMs: Long, spanMs: Long, widthPx: Float): Float {
        if (spanMs <= 0L) return widthPx / 2f
        return (widthPx / 2.0 + (ms - centerMs).toDouble() * widthPx / spanMs).toFloat()
    }

    // START_CONTRACT: visibleSegments
    //   PURPOSE: Segments overlapping the visible window [center - span/2, center + span/2].
    //   INPUTS: { segments: List<Segment>; centerMs: Long; spanMs: Long }
    //   OUTPUTS: { List<Segment> - same order, unclipped }
    //   SIDE_EFFECTS: none
    //   LINKS: D-03
    // END_CONTRACT: visibleSegments
    fun visibleSegments(segments: List<Segment>, centerMs: Long, spanMs: Long): List<Segment> {
        val from = centerMs - spanMs / 2
        val to = centerMs + spanMs / 2
        return segments.filter { it.endMs > from && it.startMs < to }
    }

    // START_CONTRACT: tickStepMs
    //   PURPOSE: Tick spacing for a span.
    //   INPUTS: { spanMs: Long }
    //   OUTPUTS: { Long - 10 min (<= 1 h), 1 h (<= 6 h), else 3 h }
    //   SIDE_EFFECTS: none
    //   LINKS: D-03
    // END_CONTRACT: tickStepMs
    fun tickStepMs(spanMs: Long): Long = when {
        spanMs <= HOUR -> TEN_MIN
        spanMs <= 6 * HOUR -> HOUR
        else -> 3 * HOUR
    }

    // START_CONTRACT: ticks
    //   PURPOSE: Ticks dayStart + k * step inside both the visible window and the day.
    //   INPUTS: { centerMs, spanMs: Long; dayStartMs, dayEndMs: Long; zone: ZoneId - label zone }
    //   OUTPUTS: { List<Tick> - ascending, labels HH:mm in zone }
    //   SIDE_EFFECTS: none
    //   LINKS: D-03
    // END_CONTRACT: ticks
    fun ticks(centerMs: Long, spanMs: Long, dayStartMs: Long, dayEndMs: Long, zone: ZoneId): List<Tick> {
        val step = tickStepMs(spanMs)
        val from = maxOf(centerMs - spanMs / 2, dayStartMs)
        val to = minOf(centerMs + spanMs / 2, dayEndMs)
        if (to < from) return emptyList()
        val firstK = Math.floorDiv(from - dayStartMs + step - 1, step)
        val out = ArrayList<Tick>()
        var ms = dayStartMs + firstK * step
        while (ms <= to) {
            out += Tick(ms, LABEL.format(Instant.ofEpochMilli(ms).atZone(zone)))
            ms += step
        }
        return out
    }

    // START_CONTRACT: initialCenter
    //   PURPOSE: Where the ruler opens for a day.
    //   INPUTS: { today: Boolean - day is today; nowMs: Long; dayStartMs, dayEndMs: Long; daySegments: List<Segment> }
    //   OUTPUTS: { Long - today: now capped to the day; past day: last segment end, or noon without segments }
    //   SIDE_EFFECTS: none
    //   LINKS: D-03
    // END_CONTRACT: initialCenter
    fun initialCenter(today: Boolean, nowMs: Long, dayStartMs: Long, dayEndMs: Long, daySegments: List<Segment>): Long {
        if (today) return nowMs.coerceIn(dayStartMs, dayEndMs)
        val last = daySegments.maxOfOrNull { it.endMs }
        return (last ?: (dayStartMs + 12 * HOUR)).coerceIn(dayStartMs, dayEndMs)
    }

    // START_CONTRACT: shouldSeekAfterGesture
    //   PURPOSE: Release-to-seek rule: seek only when the centre really moved (a pure pinch zoom never seeks).
    //   INPUTS: { startCenterMs, endCenterMs: Long; msPerPx: Float - span / width }
    //   OUTPUTS: { Boolean - |end - start| >= max(SEEK_MIN_PX * msPerPx, SEEK_MIN_MS) }
    //   SIDE_EFFECTS: none
    //   LINKS: D-03, SD-03
    // END_CONTRACT: shouldSeekAfterGesture
    fun shouldSeekAfterGesture(startCenterMs: Long, endCenterMs: Long, msPerPx: Float): Boolean {
        val moved = abs(endCenterMs - startCenterMs)
        if (moved == 0L) return false
        val pxThreshold = if (msPerPx.isNaN() || msPerPx < 0f) 0.0 else SEEK_MIN_PX.toDouble() * msPerPx
        return moved >= maxOf(pxThreshold, SEEK_MIN_MS.toDouble())
    }
}
