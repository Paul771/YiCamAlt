// FILE: TimelineMath.kt
// VERSION: 0.0.1
package com.yicamalt.ui.live

import com.yicamalt.archive.Segment
import java.time.ZoneId

object TimelineMath {
    const val DEFAULT_SPAN_MS = 3 * 3_600_000L
    const val MIN_SPAN_MS = 15 * 60_000L
    const val MAX_SPAN_MS = 24 * 3_600_000L
    const val SEEK_MIN_PX = 4f
    const val SEEK_MIN_MS = 2_000L

    data class Tick(val ms: Long, val label: String)

    fun clampSpan(spanMs: Long): Long = error("RED stub")
    fun zoom(spanMs: Long, factor: Float): Long = error("RED stub")
    fun pan(centerMs: Long, dxPx: Float, widthPx: Float, spanMs: Long, dayStartMs: Long, dayEndMs: Long): Long = error("RED stub")
    fun xOf(ms: Long, centerMs: Long, spanMs: Long, widthPx: Float): Float = error("RED stub")
    fun visibleSegments(segments: List<Segment>, centerMs: Long, spanMs: Long): List<Segment> = error("RED stub")
    fun tickStepMs(spanMs: Long): Long = error("RED stub")
    fun ticks(centerMs: Long, spanMs: Long, dayStartMs: Long, dayEndMs: Long, zone: ZoneId): List<Tick> = error("RED stub")
    fun initialCenter(today: Boolean, nowMs: Long, dayStartMs: Long, dayEndMs: Long, daySegments: List<Segment>): Long = error("RED stub")
    fun shouldSeekAfterGesture(startCenterMs: Long, endCenterMs: Long, msPerPx: Float): Boolean = error("RED stub")
}
