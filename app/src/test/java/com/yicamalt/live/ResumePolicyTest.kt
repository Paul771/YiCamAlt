// FILE: ResumePolicyTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Executable contract for auto-resume after background (LIVE-10, D-10).
//   SCOPE: window edge, single consumption, stop reasons, inactive stream, onStarted reset.
//   DEPENDS: M-LIVE-SESSION (ResumePolicy)
//   LINKS: V-M-LIVE-SESSION
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.live

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// START_MODULE_MAP
//   ResumePolicyTest - one test per behaviour bullet of plan 03-05.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 03-05: resume policy vectors.
// END_CHANGE_SUMMARY

class ResumePolicyTest {

    @Test
    fun `constants`() {
        assertEquals(300_000L, ResumePolicy.RESUME_WINDOW_MS)
        assertEquals("background", ResumePolicy.REASON_BACKGROUND)
    }

    @Test
    fun `background stop of an active stream resumes once inside the window`() {
        val p = ResumePolicy()
        p.onStopped(ResumePolicy.REASON_BACKGROUND, wasActive = true, nowMs = 0)
        assertTrue(p.shouldAutoResume(300_000))
        assertFalse(p.shouldAutoResume(300_000))
    }

    @Test
    fun `no resume after the window`() {
        val p = ResumePolicy()
        p.onStopped(ResumePolicy.REASON_BACKGROUND, wasActive = true, nowMs = 0)
        assertFalse(p.shouldAutoResume(300_001))
    }

    @Test
    fun `no resume after a user or screen-left stop`() {
        for (reason in listOf("user", "screen-left")) {
            val p = ResumePolicy()
            p.onStopped(reason, wasActive = true, nowMs = 0)
            assertFalse(p.shouldAutoResume(1), reason)
        }
    }

    @Test
    fun `no resume when the stream was not active`() {
        val p = ResumePolicy()
        p.onStopped(ResumePolicy.REASON_BACKGROUND, wasActive = false, nowMs = 0)
        assertFalse(p.shouldAutoResume(1))
    }

    @Test
    fun `onStarted clears a pending resume`() {
        val p = ResumePolicy()
        p.onStopped(ResumePolicy.REASON_BACKGROUND, wasActive = true, nowMs = 0)
        p.onStarted()
        assertFalse(p.shouldAutoResume(1))
    }

    @Test
    fun `a later non-background stop replaces a pending resume`() {
        val p = ResumePolicy()
        p.onStopped(ResumePolicy.REASON_BACKGROUND, wasActive = true, nowMs = 0)
        p.onStopped("user", wasActive = true, nowMs = 10)
        assertFalse(p.shouldAutoResume(20))
    }

    @Test
    fun `nothing to resume on a fresh policy`() {
        assertFalse(ResumePolicy().shouldAutoResume(0))
    }
}
