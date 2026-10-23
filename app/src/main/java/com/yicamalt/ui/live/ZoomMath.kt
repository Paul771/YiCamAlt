// FILE: ZoomMath.kt
// VERSION: 0.0.1
// START_MODULE_CONTRACT
//   PURPOSE: Zoom math (RED stub).
//   SCOPE: Zoom, ZoomMath.
//   DEPENDS: none
//   LINKS: M-UI-LIVEVIEW, V-M-UI-LIVEVIEW
// END_MODULE_CONTRACT
package com.yicamalt.ui.live

// START_MODULE_MAP
//   Zoom, ZoomMath - RED stub.
// END_MODULE_MAP

data class Zoom(val scale: Float = 1f, val tx: Float = 0f, val ty: Float = 0f)

object ZoomMath {
    const val MAX_SCALE = 4f
    const val CONTROLS_AUTO_HIDE_MS = 4_000L

    fun clamp(z: Zoom, w: Float, h: Float): Zoom = error("RED stub")

    fun applyGesture(z: Zoom, zoomChange: Float, panX: Float, panY: Float, w: Float, h: Float): Zoom = error("RED stub")

    fun fillScale(containerW: Float, containerH: Float, videoW: Int, videoH: Int): Float = error("RED stub")

    fun doubleTap(z: Zoom, fill: Float): Zoom = error("RED stub")

    fun isFullscreen(landscape: Boolean, requested: Boolean): Boolean = error("RED stub")
}
