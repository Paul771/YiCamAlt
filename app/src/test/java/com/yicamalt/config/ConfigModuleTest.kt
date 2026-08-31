// FILE: ConfigModuleTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Verify ConfigModule reads BuildConfig once, exposes typed accessors, and rejects missing keys.
//   SCOPE: success + failure scenarios for V-M-CONFIG; trace + redaction assertions.
//   DEPENDS: M-CONFIG, TestInfrastructure
//   LINKS: V-M-CONFIG
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.config

import com.yicamalt.test.RedactionScanner
import com.yicamalt.test.TraceRecorder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfigModuleTest {

    private val recorder = TraceRecorder()
    private val redaction = RedactionScanner()

    @Test
    fun `scenario_1 api base url from BuildConfig + overrides`() {
        recorder.plant()
        try {
            val cfg = ConfigModule()
            // START_BLOCK_S1
            val url = cfg.getApiBaseUrl()
            // END_BLOCK_S1
            assertTrue(url.startsWith("https://"), "api base url must be https")
            recorder.assertMarkerAppeared("BLOCK_INIT_CONFIG")
        } finally { recorder.unplant() }
    }

    @Test
    fun `scenario_2 feature flags return expected defaults when not overridden`() {
        val cfg = ConfigModule()
        val flags = cfg.getFeatureFlags()
        assertEquals(true, flags[FeatureFlag.CLOUD_RELAY])
        assertEquals(false, flags[FeatureFlag.MULTI_CAMERA_VIEW])
        // LOCAL_RTSP mirrors BuildConfig.LOCAL_RTSP_ENABLED (true in debug)
        assertTrue(cfg.isLocalRtspEnabled())
    }

    @Test
    fun `scenario_3 missing config key throws CONFIG_MISSING_KEY`() {
        val cfg = ConfigModule()
        val err = assertThrows(ConfigError.MissingKey::class.java) {
            cfg.flag("nonexistent_flag")
        }
        assertTrue(err.message!!.contains("CONFIG_MISSING_KEY"))
    }

    @Test
    fun `trace init reads BuildConfig exactly once`() {
        recorder.plant()
        try {
            val cfg = ConfigModule()            // construction reads BuildConfig once
            cfg.getApiBaseUrl()                 // accessors must not re-read BuildConfig
            cfg.getFeatureFlags()
            cfg.isLocalRtspEnabled()
            val initMarkers = recorder.all().count { it.message.contains("BLOCK_INIT_CONFIG") }
            assertEquals(1, initMarkers, "BLOCK_INIT_CONFIG must appear exactly once")
        } finally { recorder.unplant() }
    }

    @Test
    fun `redaction config log does not leak secrets`() {
        recorder.plant()
        try {
            ConfigModule()
            redaction.assertClean(recorder.bufferText())
        } finally { recorder.unplant() }
    }

    @Test
    fun `flag present returns value`() {
        val cfg = ConfigModule()
        assertTrue(cfg.flag(FeatureFlag.CLOUD_RELAY))
        assertFalse(cfg.flag(FeatureFlag.MULTI_CAMERA_VIEW))
    }
}