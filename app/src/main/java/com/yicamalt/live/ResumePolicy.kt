// FILE: ResumePolicy.kt
// VERSION: 0.2.0
// START_MODULE_CONTRACT
//   PURPOSE: Pure auto-resume rule after background (D-10, LIVE-10).
//   SCOPE: remember the last stop (reason, wasActive, time); answer once whether the stream should resume.
//   DEPENDS: none
//   LINKS: M-LIVE-SESSION, V-M-LIVE-SESSION
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.live

// START_MODULE_MAP
//   ResumePolicy - onStopped / onStarted / shouldAutoResume; RESUME_WINDOW_MS 300000, REASON_BACKGROUND.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.2.0 - GSD plan 03-05 GREEN: rule implemented. Not thread-safe by design; no logging (supervisor logs BLOCK_RESUME).
//   PREVIOUS: v0.1.0 - GSD plan 03-05 RED: signatures only.
// END_CHANGE_SUMMARY

class ResumePolicy(private val windowMs: Long = RESUME_WINDOW_MS) {

    // Time of a background stop of an active stream; null when nothing to resume.
    private var backgroundStopAtMs: Long? = null

    fun onStopped(reason: String, wasActive: Boolean, nowMs: Long) {
        backgroundStopAtMs = if (reason == REASON_BACKGROUND && wasActive) nowMs else null
    }

    fun onStarted() {
        backgroundStopAtMs = null
    }

    // START_CONTRACT: shouldAutoResume
    //   PURPOSE: True once when the last stop was background of an active stream and nowMs is within the window (D-10).
    //   INPUTS: { nowMs: Long }
    //   OUTPUTS: { Boolean }
    //   SIDE_EFFECTS: consumes the pending resume
    //   LINKS: M-LIVE-SESSION
    // END_CONTRACT: shouldAutoResume
    fun shouldAutoResume(nowMs: Long): Boolean {
        // START_BLOCK_RESUME_DECISION
        val at = backgroundStopAtMs ?: return false
        backgroundStopAtMs = null
        return nowMs - at <= windowMs
        // END_BLOCK_RESUME_DECISION
    }

    companion object {
        const val RESUME_WINDOW_MS = 300_000L
        const val REASON_BACKGROUND = "background"
    }
}
