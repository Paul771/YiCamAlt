// FILE: ZoomMath.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Pure live-view zoom / pan / double-tap / fullscreen rules (LIVE-09, D-09).
//   SCOPE: scale clamp 1..MAX_SCALE, pan clamp |tx| <= (s-1)*w/2 and |ty| <= (s-1)*h/2, pinch gesture
//     application, fill-over-fit scale, double-tap toggle, fullscreen rule, controls auto-hide delay.
//     Plain Kotlin floats; the screen (plan 03-11) maps Zoom onto graphicsLayer.
//   DEPENDS: none
//   LINKS: M-UI-LIVEVIEW, V-M-UI-LIVEVIEW
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.ui.live

// START_MODULE_MAP
//   Zoom - scale + translation of the video view (identity = fit)
//   ZoomMath - MAX_SCALE, CONTROLS_AUTO_HIDE_MS, clamp, applyGesture, fillScale, doubleTap, isFullscreen
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 03-09: created (D-09 zoom up to 4x, double tap fit <-> fill or 2x).
// END_CHANGE_SUMMARY

data class Zoom(val scale: Float = 1f, val tx: Float = 0f, val ty: Float = 0f)

object ZoomMath {
    const val MIN_SCALE = 1f
    const val MAX_SCALE = 4f
    const val CONTROLS_AUTO_HIDE_MS = 4_000L
    const val DOUBLE_TAP_FALLBACK_SCALE = 2f
    private const val EPS = 0.001f

    // START_CONTRACT: clamp
    //   PURPOSE: Keep scale in 1..MAX_SCALE and the scaled frame covering the w x h box.
    //   INPUTS: { z: Zoom; w: Float - box width px; h: Float - box height px }
    //   OUTPUTS: { Zoom - clamped; translations are never -0f }
    //   SIDE_EFFECTS: none
    //   LINKS: M-UI-LIVEVIEW
    // END_CONTRACT: clamp
    fun clamp(z: Zoom, w: Float, h: Float): Zoom {
        val s = if (z.scale.isNaN()) MIN_SCALE else z.scale.coerceIn(MIN_SCALE, MAX_SCALE)
        val maxX = ((s - 1f) * w.coerceAtLeast(0f) / 2f)
        val maxY = ((s - 1f) * h.coerceAtLeast(0f) / 2f)
        val tx = (if (z.tx.isNaN()) 0f else z.tx.coerceIn(-maxX, maxX)) + 0f
        val ty = (if (z.ty.isNaN()) 0f else z.ty.coerceIn(-maxY, maxY)) + 0f
        return Zoom(s, tx, ty)
    }

    // START_CONTRACT: applyGesture
    //   PURPOSE: Apply one detectTransformGestures step (zoom factor + pan delta), then clamp.
    //   INPUTS: { z: Zoom; zoomChange: Float - multiplicative; panX, panY: Float - px; w, h: Float - box px }
    //   OUTPUTS: { Zoom - clamped }
    //   SIDE_EFFECTS: none
    //   LINKS: M-UI-LIVEVIEW
    // END_CONTRACT: applyGesture
    fun applyGesture(z: Zoom, zoomChange: Float, panX: Float, panY: Float, w: Float, h: Float): Zoom =
        clamp(Zoom(z.scale * zoomChange, z.tx + panX, z.ty + panY), w, h)

    // START_CONTRACT: fillScale
    //   PURPOSE: Scale that turns the fitted (letterboxed) video into a filled (cropped) container.
    //   INPUTS: { containerW, containerH: Float - px; videoW, videoH: Int - decoded visible size }
    //   OUTPUTS: { Float - fill / fit in 1..MAX_SCALE; 1f for unknown or non-positive sizes }
    //   SIDE_EFFECTS: none
    //   LINKS: M-UI-LIVEVIEW, M-MEDIA-RENDER (videoSize)
    // END_CONTRACT: fillScale
    fun fillScale(containerW: Float, containerH: Float, videoW: Int, videoH: Int): Float {
        if (containerW <= 0f || containerH <= 0f || videoW <= 0 || videoH <= 0) return MIN_SCALE
        val sx = containerW / videoW
        val sy = containerH / videoH
        val fit = minOf(sx, sy)
        val fill = maxOf(sx, sy)
        return (fill / fit).coerceIn(MIN_SCALE, MAX_SCALE)
    }

    // START_CONTRACT: doubleTap
    //   PURPOSE: Toggle fit <-> fill; when fill equals fit, toggle fit <-> 2x.
    //   INPUTS: { z: Zoom - current; fill: Float - fillScale(...) }
    //   OUTPUTS: { Zoom - centred target zoom }
    //   SIDE_EFFECTS: none
    //   LINKS: M-UI-LIVEVIEW
    // END_CONTRACT: doubleTap
    fun doubleTap(z: Zoom, fill: Float): Zoom {
        if (z.scale > MIN_SCALE + EPS) return Zoom()
        val target = if (fill > MIN_SCALE + EPS) fill.coerceAtMost(MAX_SCALE) else DOUBLE_TAP_FALLBACK_SCALE
        return Zoom(target, 0f, 0f)
    }

    // START_CONTRACT: isFullscreen
    //   PURPOSE: Fullscreen layout when the phone is landscape or the user pressed the fullscreen button.
    //   INPUTS: { landscape: Boolean; requested: Boolean }
    //   OUTPUTS: { Boolean }
    //   SIDE_EFFECTS: none
    //   LINKS: M-UI-LIVEVIEW
    // END_CONTRACT: isFullscreen
    fun isFullscreen(landscape: Boolean, requested: Boolean): Boolean = landscape || requested
}
