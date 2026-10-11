// FILE: ConfigModuleTest.kt
// VERSION: 0.2.0
// START_MODULE_CONTRACT
//   PURPOSE: Verify ConfigModule reads BuildConfig once, exposes typed accessors, and rejects missing keys.
//   SCOPE: success + failure scenarios for V-M-CONFIG; trace + redaction assertions; YiRegion lookup/order,
//     RegionSelector behaviour, manual-server flag, real default gateway (CAM-02).
//   DEPENDS: M-CONFIG, TestInfrastructure
//   LINKS: V-M-CONFIG
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.config

// START_MODULE_MAP
//   ConfigModuleTest - scenario_1..3 + trace/redaction (v0.1.0); region_1..region_7 (CAM-02).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.2.0 - CAM-02: YiRegion.fromBaseUrl/orderStartingWith, default region US, selectRegion,
//     restoreBaseUrl, manual-server flag (pins the server, selectRegion becomes a no-op), ProbeMatrix.REGIONS
//     derived from YiRegion.
// END_CHANGE_SUMMARY

import com.yicamalt.test.RedactionScanner
import com.yicamalt.test.TraceRecorder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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

    // START_BLOCK_REGION_TESTS
    @Test
    fun `region_1 fromBaseUrl matches the gateway host case-insensitively`() {
        assertEquals(YiRegion.EU, YiRegion.fromBaseUrl("https://gw-eu.xiaoyi.com"))
        assertEquals(YiRegion.EU, YiRegion.fromBaseUrl("https://gw-eu.xiaoyi.com/"))
        assertEquals(YiRegion.EU, YiRegion.fromBaseUrl("https://GW-EU.xiaoyi.com/v1/"))
        assertEquals(YiRegion.US, YiRegion.fromBaseUrl("https://gw-us.xiaoyi.com/"))
        assertEquals(YiRegion.SG, YiRegion.fromBaseUrl(" https://gw-sg.xiaoyi.com/ "))
        assertNull(YiRegion.fromBaseUrl("https://api.yicamalt.local/v1/"))
        assertNull(YiRegion.fromBaseUrl("https://api.xiaoyi.com/"), "the fourth server is manual-only, not a region")
        assertNull(YiRegion.fromBaseUrl(null))
        assertNull(YiRegion.fromBaseUrl(""))
        assertNull(YiRegion.fromBaseUrl("garbage"))
        assertNull(YiRegion.fromBaseUrl("http://[bad"))
    }

    @Test
    fun `region_2 default order is US EU SG and orderStartingWith visits every region once`() {
        assertEquals(listOf(YiRegion.US, YiRegion.EU, YiRegion.SG), YiRegion.entries.toList())
        assertEquals(listOf(YiRegion.EU, YiRegion.US, YiRegion.SG), YiRegion.orderStartingWith(YiRegion.EU))
        assertEquals(listOf(YiRegion.US, YiRegion.EU, YiRegion.SG), YiRegion.orderStartingWith(YiRegion.US))
        assertEquals(listOf(YiRegion.SG, YiRegion.US, YiRegion.EU), YiRegion.orderStartingWith(YiRegion.SG))
    }

    @Test
    fun `region_3 default server is the real US gateway`() {
        val cfg = ConfigModule(InMemorySettingsStore())
        assertEquals("https://gw-us.xiaoyi.com/", cfg.getApiBaseUrl())
        assertEquals(YiRegion.US, cfg.currentRegion())
        assertFalse(cfg.isManualBaseUrl())
        assertFalse(cfg.getApiBaseUrl().contains("yicamalt.local"))
    }

    @Test
    fun `region_4 selectRegion switches the effective base url and logs only the region name`() {
        recorder.plant()
        try {
            val cfg = ConfigModule(InMemorySettingsStore())
            cfg.selectRegion(YiRegion.SG)
            assertEquals("https://gw-sg.xiaoyi.com/", cfg.getApiBaseUrl())
            assertEquals("https://gw-sg.xiaoyi.com/", cfg.currentBaseUrl())
            assertEquals(YiRegion.SG, cfg.currentRegion())
            assertFalse(cfg.isManualBaseUrl(), "selectRegion never sets the manual flag")
            recorder.assertMarkerAppeared("BLOCK_SELECT_REGION")
            assertTrue(recorder.bufferText().contains("region=SG"))
            redaction.assertClean(recorder.bufferText())
        } finally { recorder.unplant() }
    }

    @Test
    fun `region_5 custom server has no region and restoreBaseUrl puts the previous url back`() {
        val cfg = ConfigModule(InMemorySettingsStore())
        cfg.selectRegion(YiRegion.EU)
        val prev = cfg.currentBaseUrl()
        cfg.setApiBaseUrl("http://127.0.0.1:9/")
        assertNull(cfg.currentRegion())
        cfg.restoreBaseUrl(prev)
        assertEquals("https://gw-eu.xiaoyi.com/", cfg.getApiBaseUrl())
        assertEquals(YiRegion.EU, cfg.currentRegion())
        assertFalse(cfg.isManualBaseUrl(), "restoreBaseUrl never sets the manual flag")
    }

    @Test
    fun `region_6 manual server pins the url even when it is a gateway and clearing returns to automatic`() {
        val store = InMemorySettingsStore()
        val cfg = ConfigModule(store)
        cfg.setManualBaseUrl("https://gw-eu.xiaoyi.com")
        assertEquals("https://gw-eu.xiaoyi.com", cfg.getApiBaseUrl())
        assertTrue(cfg.isManualBaseUrl())
        assertTrue(store.isManualBaseUrl(), "the flag is persisted in the settings store")
        assertNull(cfg.currentRegion(), "manual server: no region, so no hop and no discovery")

        cfg.selectRegion(YiRegion.SG)
        assertEquals("https://gw-eu.xiaoyi.com", cfg.getApiBaseUrl(), "selectRegion is a no-op while manual")
        cfg.restoreBaseUrl("https://gw-us.xiaoyi.com/")
        assertEquals("https://gw-eu.xiaoyi.com", cfg.getApiBaseUrl(), "restoreBaseUrl is a no-op while manual")

        cfg.setManualBaseUrl("   ")
        assertFalse(cfg.isManualBaseUrl())
        assertEquals("https://gw-us.xiaoyi.com/", cfg.getApiBaseUrl())
        assertEquals(YiRegion.US, cfg.currentRegion())

        cfg.setManualBaseUrl("https://api.xiaoyi.com/")
        assertTrue(cfg.isManualBaseUrl())
        cfg.setManualBaseUrl(null)
        assertFalse(cfg.isManualBaseUrl())
        assertEquals(YiRegion.US, cfg.currentRegion())
    }

    @Test
    fun `region_7 probe matrix regions are derived from YiRegion`() {
        val regions = com.yicamalt.debug.ProbeMatrix.REGIONS
        assertEquals(YiRegion.entries.map { it.name }, regions.keys.toList())
        assertEquals(YiRegion.entries.map { it.baseUrl.trimEnd('/') }, regions.values.toList())
        assertEquals("https://gw-us.xiaoyi.com", regions["US"])
    }
    // END_BLOCK_REGION_TESTS
}
