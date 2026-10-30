// FILE: ZoomMathTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Executable contract for the live-view zoom / pan / double-tap / fullscreen rules (LIVE-09, D-09).
//   SCOPE: scale clamp, pan clamp, gesture application, fill-over-fit scale, double-tap toggle, fullscreen rule.
//   DEPENDS: M-UI-LIVEVIEW (Zoom, ZoomMath)
//   LINKS: V-M-UI-LIVEVIEW
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.ui.live

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// START_MODULE_MAP
//   ZoomMathTest - one test per behaviour bullet of plan 03-09 Task 2 (+ edge cases).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 03-09: zoom math vectors.
// END_CHANGE_SUMMARY

class ZoomMathTest {

    @Test
    fun `scale is clamped to 1 to 4 and pan resets at 1x`() {
        assertEquals(4f, ZoomMath.clamp(Zoom(5f, 0f, 0f), 1000f, 500f).scale)
        assertEquals(Zoom(1f, 0f, 0f), ZoomMath.clamp(Zoom(0.5f, 10f, 10f), 1000f, 500f))
        assertEquals(Zoom(1f, 0f, 0f), ZoomMath.clamp(Zoom(1f, -10f, -10f), 1000f, 500f))
    }

    @Test
    fun `pan is clamped to the frame bounds`() {
        assertEquals(Zoom(2f, 500f, -250f), ZoomMath.clamp(Zoom(2f, 900f, -900f), 1000f, 500f))
    }

    @Test
    fun `gesture multiplies scale and adds pan`() {
        assertEquals(Zoom(2f, 100f, 0f), ZoomMath.applyGesture(Zoom(1f, 0f, 0f), 2f, 100f, 0f, 1000f, 500f))
        assertEquals(Zoom(4f, 0f, 0f), ZoomMath.applyGesture(Zoom(3f, 0f, 0f), 2f, 0f, 0f, 1000f, 500f))
    }

    @Test
    fun `fill scale is fill over fit clamped to 1 to 4`() {
        val expected = (2000f / 1920f) / (1000f / 1080f)
        assertEquals(expected, ZoomMath.fillScale(2000f, 1000f, 1920, 1080), 0.001f)
        assertEquals(1f, ZoomMath.fillScale(1920f, 1080f, 1920, 1080), 0.001f)
        assertEquals(4f, ZoomMath.fillScale(10000f, 100f, 1920, 1080), 0.001f)
        assertEquals(1f, ZoomMath.fillScale(2000f, 1000f, 0, 1080), 0.001f)
    }

    @Test
    fun `double tap toggles fit and fill or 2x`() {
        assertEquals(Zoom(1.2f, 0f, 0f), ZoomMath.doubleTap(Zoom(1f, 0f, 0f), 1.2f))
        assertEquals(Zoom(2f, 0f, 0f), ZoomMath.doubleTap(Zoom(1f, 0f, 0f), 1f))
        assertEquals(Zoom(1f, 0f, 0f), ZoomMath.doubleTap(Zoom(3f, 50f, 0f), 1.2f))
    }

    @Test
    fun `fullscreen is landscape or requested`() {
        assertTrue(ZoomMath.isFullscreen(landscape = true, requested = false))
        assertTrue(ZoomMath.isFullscreen(landscape = false, requested = true))
        assertFalse(ZoomMath.isFullscreen(landscape = false, requested = false))
        assertEquals(4_000L, ZoomMath.CONTROLS_AUTO_HIDE_MS)
        assertEquals(4f, ZoomMath.MAX_SCALE)
    }
}
