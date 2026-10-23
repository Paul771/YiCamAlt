// FILE: QualityController.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Pure HD/SD policy of one live screen: target, observed (key-frame width), pending request, manual choice.
//   SCOPE: target(mode), per-link auto request, request success / 8 s timeout, 4881 unsupported, manual choice (D-06, D-07).
//   DEPENDS: M-STREAM-CORE (LinkMode), live/LiveSessionTypes.kt (Quality, QualityState, KeyFrameObservation)
//   LINKS: M-LIVE-SESSION, V-M-LIVE-SESSION
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.live

import com.yicamalt.stream.LinkMode

// START_MODULE_MAP
//   QualityController - target / observe / needsAutoRequest / beginRequest / tick / onUnsupported / setManual; SWITCH_TIMEOUT_MS 8000.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 03-05 RED: signatures only.
// END_CHANGE_SUMMARY

class QualityController(private val switchTimeoutMs: Long = SWITCH_TIMEOUT_MS) {

    val state: QualityState get() = error("RED stub")

    fun target(mode: LinkMode): Quality = error("RED stub")

    fun onNewLink() {
        error("RED stub")
    }

    fun observe(frame: KeyFrameObservation) {
        error("RED stub")
    }

    fun needsAutoRequest(mode: LinkMode): Boolean = error("RED stub")

    fun beginRequest(quality: Quality, useCount: Int?, nowMs: Long) {
        error("RED stub")
    }

    fun tick(nowMs: Long) {
        error("RED stub")
    }

    fun onUnsupported() {
        error("RED stub")
    }

    fun setManual(quality: Quality): Boolean = error("RED stub")

    companion object {
        const val SWITCH_TIMEOUT_MS = 8_000L
    }
}
