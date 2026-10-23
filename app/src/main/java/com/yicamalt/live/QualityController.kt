// FILE: QualityController.kt
// VERSION: 0.2.0
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
//   LAST_CHANGE: v0.2.0 - GSD plan 03-05 GREEN: rules implemented. Not thread-safe by design (one supervisor context).
//     No logging here: the supervisor logs BLOCK_QUALITY. Shown quality is always the observed one.
//   PREVIOUS: v0.1.0 - GSD plan 03-05 RED: signatures only.
// END_CHANGE_SUMMARY

class QualityController(private val switchTimeoutMs: Long = SWITCH_TIMEOUT_MS) {

    private class Pending(val quality: Quality, val useCount: Int, val sinceMs: Long)

    private var observed: Quality? = null
    private var pending: Pending? = null
    private var manual: Quality? = null
    private var switchSupported = true

    // Per-link flags, reset by onNewLink.
    private var keyFrameSeenOnLink = false
    private var autoRequestDone = false

    val state: QualityState
        get() = QualityState(observed = observed, pending = pending?.quality, manual = manual, switchSupported = switchSupported)

    // START_CONTRACT: target
    //   PURPOSE: Desired quality: manual choice for this screen, else HD on LAN/P2P and SD on relays/unknown (D-06).
    //   INPUTS: { mode: LinkMode }
    //   OUTPUTS: { Quality }
    //   SIDE_EFFECTS: none
    //   LINKS: M-LIVE-SESSION
    // END_CONTRACT: target
    fun target(mode: LinkMode): Quality = manual ?: Quality.forMode(mode)

    fun onNewLink() {
        pending = null
        keyFrameSeenOnLink = false
        autoRequestDone = false
    }

    // START_CONTRACT: observe
    //   PURPOSE: Record a key frame: observed quality from width; confirm a pending request on useCount or width class.
    //   INPUTS: { frame: KeyFrameObservation }
    //   OUTPUTS: { Unit }
    //   SIDE_EFFECTS: updates observed / pending
    //   LINKS: M-LIVE-SESSION
    // END_CONTRACT: observe
    fun observe(frame: KeyFrameObservation) {
        // START_BLOCK_OBSERVE_KEY_FRAME
        val seen = Quality.ofWidth(frame.width)
        observed = seen
        keyFrameSeenOnLink = true
        val p = pending
        if (p != null && (frame.useCount == p.useCount || seen == p.quality)) pending = null
        // END_BLOCK_OBSERVE_KEY_FRAME
    }

    fun needsAutoRequest(mode: LinkMode): Boolean =
        keyFrameSeenOnLink && !autoRequestDone && switchSupported && pending == null && observed != target(mode)

    // START_CONTRACT: beginRequest
    //   PURPOSE: Mark a 4881 request as sent; a null useCount means the link cannot send it -> switch hidden (D-07).
    //   INPUTS: { quality: Quality, useCount: Int? - the sent useCount, nowMs: Long }
    //   OUTPUTS: { Unit }
    //   SIDE_EFFECTS: updates pending / switchSupported / per-link auto flag
    //   LINKS: M-LIVE-SESSION
    // END_CONTRACT: beginRequest
    fun beginRequest(quality: Quality, useCount: Int?, nowMs: Long) {
        autoRequestDone = true
        if (useCount == null) {
            onUnsupported()
            return
        }
        pending = Pending(quality, useCount, nowMs)
    }

    fun tick(nowMs: Long) {
        // START_BLOCK_SWITCH_TIMEOUT
        val p = pending ?: return
        if (nowMs - p.sinceMs >= switchTimeoutMs) onUnsupported()
        // END_BLOCK_SWITCH_TIMEOUT
    }

    fun onUnsupported() {
        switchSupported = false
        pending = null
    }

    // START_CONTRACT: setManual
    //   PURPOSE: User HD/SD choice for this screen; ignored when the switch is hidden.
    //   INPUTS: { quality: Quality }
    //   OUTPUTS: { Boolean - true when the caller should send a request (supported and differs from observed) }
    //   SIDE_EFFECTS: updates manual
    //   LINKS: M-LIVE-SESSION
    // END_CONTRACT: setManual
    fun setManual(quality: Quality): Boolean {
        if (!switchSupported) return false
        manual = quality
        return quality != observed
    }

    companion object {
        const val SWITCH_TIMEOUT_MS = 8_000L
    }
}
