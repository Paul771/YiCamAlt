// FILE: ResumePolicy.kt
// VERSION: 0.1.0
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
//   LAST_CHANGE: v0.1.0 - GSD plan 03-05 RED: signatures only.
// END_CHANGE_SUMMARY

class ResumePolicy(private val windowMs: Long = RESUME_WINDOW_MS) {

    fun onStopped(reason: String, wasActive: Boolean, nowMs: Long) {
        error("RED stub")
    }

    fun onStarted() {
        error("RED stub")
    }

    fun shouldAutoResume(nowMs: Long): Boolean = error("RED stub")

    companion object {
        const val RESUME_WINDOW_MS = 300_000L
        const val REASON_BACKGROUND = "background"
    }
}
