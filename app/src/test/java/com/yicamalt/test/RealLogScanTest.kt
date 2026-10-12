// FILE: RealLogScanTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Opt-in sweep of a pulled device log for secrets without echoing them (SEC-01).
//   SCOPE: runs only when Gradle gets -PredactionLogFile=<abs path>; otherwise skipped via assumption.
//   DEPENDS: RedactionScanner
//   LINKS: V-M-HTTP, SEC-01
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.test

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import java.io.File

// START_MODULE_MAP
//   RealLogScanTest - reads system property redaction.logFile and runs assertCleanQuiet over it.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - SEC-01: initial opt-in device-log sweep.
// END_CHANGE_SUMMARY

class RealLogScanTest {

    @Test
    fun `pulled device log contains no secrets`() {
        val path = System.getProperty("redaction.logFile").orEmpty()
        Assumptions.assumeTrue(path.isNotBlank(), "no -PredactionLogFile given; sweep skipped")
        // START_BLOCK_SWEEP_DEVICE_LOG
        val text = File(path).readText(Charsets.UTF_8)
        assertTrue(text.contains("[BLOCK_"), "file has no [BLOCK_ marker; not an app log")
        RedactionScanner().assertCleanQuiet(text)
        // END_BLOCK_SWEEP_DEVICE_LOG
    }
}
