// FILE: QualityControllerTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Executable contract for the HD/SD policy (LIVE-07, D-06, D-07).
//   SCOPE: target per link mode and manual, observed by width, per-link auto request, request success rules,
//     8 s timeout, unsupported 4881, setManual result.
//   DEPENDS: M-LIVE-SESSION (QualityController, Quality, KeyFrameObservation), M-STREAM-CORE (LinkMode)
//   LINKS: V-M-LIVE-SESSION
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.live

import com.yicamalt.stream.LinkMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// START_MODULE_MAP
//   hd(uc) / sd(uc) - key-frame observations 1920x1080 / 640x360.
//   QualityControllerTest - one test per behaviour bullet of plan 03-05.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 03-05: quality policy vectors.
// END_CHANGE_SUMMARY

class QualityControllerTest {

    private fun hd(uc: Int = 1) = KeyFrameObservation(uc, 1920, 1080)
    private fun sd(uc: Int = 1) = KeyFrameObservation(uc, 640, 360)

    @Test
    fun `target is HD on direct paths and SD on relays or unknown`() {
        val q = QualityController()
        assertEquals(Quality.HD, q.target(LinkMode.LAN))
        assertEquals(Quality.HD, q.target(LinkMode.P2P))
        for (m in listOf(LinkMode.RELAY, LinkMode.TCP_RELAY, LinkMode.SDEV_RELAY, LinkMode.UNKNOWN)) {
            assertEquals(Quality.SD, q.target(m), m.name)
        }
    }

    @Test
    fun `manual choice wins over the mode for the screen lifetime`() {
        val q = QualityController()
        q.setManual(Quality.SD)
        assertEquals(Quality.SD, q.target(LinkMode.LAN))
        q.onNewLink()
        assertEquals(Quality.SD, q.target(LinkMode.P2P))
        assertEquals(Quality.SD, q.state.manual)
    }

    @Test
    fun `observed quality follows key-frame width`() {
        val q = QualityController()
        assertNull(q.state.observed)
        q.observe(hd())
        assertEquals(Quality.HD, q.state.observed)
        q.observe(KeyFrameObservation(1, 640, 360))
        assertEquals(Quality.SD, q.state.observed)
        q.observe(KeyFrameObservation(1, 1280, 720))
        assertEquals(Quality.HD, q.state.observed)
    }

    @Test
    fun `onNewLink clears pending and the auto request flag but keeps manual and switchSupported`() {
        val q = QualityController()
        q.observe(sd())
        q.beginRequest(Quality.HD, useCount = 7, nowMs = 0)
        assertFalse(q.needsAutoRequest(LinkMode.LAN))
        q.onNewLink()
        assertNull(q.state.pending)
        assertTrue(q.state.switchSupported)
        // a fresh link needs its own first key frame first
        assertFalse(q.needsAutoRequest(LinkMode.LAN))
        q.observe(sd())
        assertTrue(q.needsAutoRequest(LinkMode.LAN))
    }

    @Test
    fun `onNewLink keeps manual and a cleared switchSupported`() {
        val q = QualityController()
        q.setManual(Quality.SD)
        q.onUnsupported()
        q.onNewLink()
        assertEquals(Quality.SD, q.state.manual)
        assertFalse(q.state.switchSupported)
    }

    @Test
    fun `no auto request before the first key frame of the link`() {
        val q = QualityController()
        assertFalse(q.needsAutoRequest(LinkMode.LAN))
    }

    @Test
    fun `auto request when observed differs from target and not after beginRequest`() {
        val q = QualityController()
        q.observe(sd())
        assertTrue(q.needsAutoRequest(LinkMode.LAN))
        q.beginRequest(Quality.HD, useCount = 7, nowMs = 0)
        assertFalse(q.needsAutoRequest(LinkMode.LAN))
    }

    @Test
    fun `no auto request when observed equals target or switch unsupported`() {
        val q = QualityController()
        q.observe(hd())
        assertFalse(q.needsAutoRequest(LinkMode.LAN))
        q.observe(sd())
        assertFalse(q.needsAutoRequest(LinkMode.RELAY))
        q.onUnsupported()
        assertFalse(q.needsAutoRequest(LinkMode.LAN))
    }

    @Test
    fun `request succeeds on matching useCount and shows the observed quality`() {
        val q = QualityController()
        q.beginRequest(Quality.HD, useCount = 7, nowMs = 1000)
        assertEquals(Quality.HD, q.state.pending)
        q.observe(KeyFrameObservation(7, 640, 360))
        assertNull(q.state.pending)
        assertEquals(Quality.SD, q.state.observed)
        assertTrue(q.state.switchSupported)
    }

    @Test
    fun `request succeeds on the requested width class`() {
        val q = QualityController()
        q.beginRequest(Quality.HD, useCount = 7, nowMs = 1000)
        q.observe(KeyFrameObservation(3, 1920, 1080))
        assertNull(q.state.pending)
        assertEquals(Quality.HD, q.state.observed)
    }

    @Test
    fun `request stays pending on a non-matching key frame`() {
        val q = QualityController()
        q.beginRequest(Quality.HD, useCount = 7, nowMs = 1000)
        q.observe(KeyFrameObservation(3, 640, 360))
        assertEquals(Quality.HD, q.state.pending)
        assertEquals(Quality.SD, q.state.observed)
    }

    @Test
    fun `a link that cannot send 4881 hides HD and SD`() {
        val q = QualityController()
        q.beginRequest(Quality.HD, useCount = null, nowMs = 1000)
        assertFalse(q.state.switchSupported)
        assertNull(q.state.pending)
    }

    @Test
    fun `8 s without success hides HD and SD`() {
        val q = QualityController()
        q.beginRequest(Quality.HD, useCount = 7, nowMs = 1000)
        q.tick(8999)
        assertEquals(Quality.HD, q.state.pending)
        assertTrue(q.state.switchSupported)
        q.tick(9000)
        assertNull(q.state.pending)
        assertFalse(q.state.switchSupported)
    }

    @Test
    fun `tick without pending changes nothing`() {
        val q = QualityController()
        q.tick(1_000_000)
        assertTrue(q.state.switchSupported)
    }

    @Test
    fun `onUnsupported hides HD and SD and drops pending`() {
        val q = QualityController()
        q.beginRequest(Quality.SD, useCount = 2, nowMs = 0)
        q.onUnsupported()
        assertFalse(q.state.switchSupported)
        assertNull(q.state.pending)
    }

    @Test
    fun `setManual returns true only when a request should be sent`() {
        val q = QualityController()
        q.observe(hd())
        assertFalse(q.setManual(Quality.HD))
        assertEquals(Quality.HD, q.state.manual)
        assertTrue(q.setManual(Quality.SD))
        assertEquals(Quality.SD, q.state.manual)
    }

    @Test
    fun `setManual is ignored when switch is unsupported`() {
        val q = QualityController()
        q.observe(hd())
        q.onUnsupported()
        assertFalse(q.setManual(Quality.SD))
        assertNull(q.state.manual)
    }
}
