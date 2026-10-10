// FILE: ProbeMatrixTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Pin the probe matrix order, the value-free analyzer and formatter (research Conflict A).
//   SCOPE: targets order, every data shape, identifier-like keys, non-json, uid extraction, device-source rule,
//     RedactionScanner-clean formatted lines built from synthetic RedactionFixtures values.
//   DEPENDS: M-DEBUG-PROBE, RedactionFixtures, RedactionScanner
//   LINKS: V-M-DEBUG-PROBE, SEC-01
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.debug

// START_MODULE_MAP
//   ProbeMatrixTest - matrix order, analyze() shapes, format() redaction, firstDeviceUid, isDeviceSource.
//   v4Body / tnpBody - synthetic response fixtures.
//   assertValueFree - format() + RedactionScanner + required tokens.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - Initial V-M-DEBUG-PROBE deterministic scenarios.
// END_CHANGE_SUMMARY

import com.yicamalt.test.RedactionFixtures as F
import com.yicamalt.test.RedactionScanner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProbeMatrixTest {

    private val scanner = RedactionScanner()
    private val names = listOf("Living Room SYNTH", "yunyi.camera.SYNTH")
    private val v4 = ProbeTarget("EU", "https://gw-eu.xiaoyi.com", "/v4/devices/list")

    private fun device(uid: String, did: String) =
        """{"uid":"$uid","did":"$did","name":"Living Room SYNTH","model":"yunyi.camera.SYNTH","type":2,""" +
            """"password":"${F.PASSWORD_HEX}","online":true,"ipcParam":{"p2p_encrypt":"true","wakeup":false}}"""

    private val v4Body =
        """{"code":"20000","data":[${device(F.CLOUD_UID, F.DID)},${device(F.TUTK_UID, F.SERIAL)}]}"""

    private val tnpBody =
        """{"code":"20000","data":{"DID":"${F.DID}","InitString":"${F.INIT_STRING}","License":"${F.LICENSE}"}}"""

    private fun assertValueFree(r: ProbeResult): String {
        val line = ProbeMatrix.format(r)
        scanner.assertClean(line, extraLiterals = F.ALL_SECRETS + names)
        assertTrue(line.contains("code=") && line.contains("len=") && line.contains("count="), line)
        return line
    }

    @Test
    fun `targets are 12 region-major in fixed path order`() {
        val t = ProbeMatrix.targets()
        assertEquals(12, t.size)
        assertEquals(listOf("US", "EU", "SG"), t.map { it.region }.distinct())
        t.chunked(4).forEach { region ->
            assertEquals(ProbeMatrix.LIST_PATHS, region.map { it.path })
            assertEquals(1, region.map { it.baseUrl }.distinct().size)
        }
        assertEquals(
            listOf("/v4/devices/list", "/v5/devices/list", "/v5/deviceshare/friends", "/v5/deviceshare/shareddetail"),
            t.take(4).map { it.path },
        )
        assertEquals("https://gw-us.xiaoyi.com", t.first().baseUrl)
        assertEquals("https://gw-sg.xiaoyi.com", t.last().baseUrl)
    }

    @Test
    fun `accepted-empty 20200 body`() {
        val r = ProbeMatrix.analyze(v4, 200, """{"code":"20200"}""")
        assertEquals("20200", r.code)
        assertEquals(16, r.bodyLength)
        assertNull(r.dataCount)
        assertEquals("absent", r.dataShape)
        assertFalse(r.looksLikeDevices)
        assertTrue(assertValueFree(r).contains("devicelike=false"))
    }

    @Test
    fun `v4 array body yields count field names and nested ipcParam names only`() {
        val r = ProbeMatrix.analyze(v4, 200, v4Body)
        assertEquals("20000", r.code)
        assertEquals(2, r.dataCount)
        assertEquals("array", r.dataShape)
        assertEquals(
            listOf("did", "ipcParam", "model", "name", "online", "password", "type", "uid"),
            r.fieldNames,
        )
        assertEquals(listOf("p2p_encrypt", "wakeup"), r.nestedFieldNames["ipcParam"])
        assertTrue(r.looksLikeDevices)
        val line = assertValueFree(r)
        assertTrue(line.contains("ipcParam=object(p2p_encrypt,wakeup)"), line)
        assertTrue(line.contains("devicelike=true"), line)
        assertTrue(line.startsWith("region=EU path=/v4/devices/list http=200 code=20000 len=${v4Body.length} count=2 shape=array"), line)
    }

    @Test
    fun `data as json-encoded string array`() {
        val encoded = kotlinx.serialization.json.JsonPrimitive("[${device(F.CLOUD_UID, F.DID)}]").toString()
        val body = """{"code":"20000","data":$encoded}"""
        val r = ProbeMatrix.analyze(v4, 200, body)
        assertEquals("string-json", r.dataShape)
        assertEquals(1, r.dataCount)
        assertTrue("uid" in r.fieldNames)
        assertValueFree(r)
    }

    @Test
    fun `data object wrapping an array reports container`() {
        val body = """{"code":"20000","data":{"list":[${device(F.CLOUD_UID, F.DID)},${device(F.TUTK_UID, F.DID)},${device(F.CLOUD_UID, F.DID)}],"total":3}}"""
        val r = ProbeMatrix.analyze(v4, 200, body)
        assertEquals(3, r.dataCount)
        assertEquals("list", r.container)
        assertTrue(assertValueFree(r).contains("container=list"))
    }

    @Test
    fun `tnp device_info object body`() {
        val t = ProbeTarget("EU", "https://gw-eu.xiaoyi.com", ProbeMatrix.TNP_INFO_PATH)
        val r = ProbeMatrix.analyze(t, 200, tnpBody)
        assertEquals("object", r.dataShape)
        assertEquals(listOf("DID", "InitString", "License"), r.fieldNames)
        assertEquals(1, r.dataCount)
        assertValueFree(r)
    }

    @Test
    fun `identifier-like keys are counted never emitted`() {
        val body = """{"data":{"${F.SERIAL}":{"name":"x"}}}"""
        val r = ProbeMatrix.analyze(v4, 200, body)
        assertEquals(1, r.redactedKeys)
        assertTrue(r.fieldNames.isEmpty())
        val line = assertValueFree(r)
        assertTrue(line.contains("redactedKeys=1"), line)
        assertFalse(line.contains(F.SERIAL))
    }

    @Test
    fun `non-json body reported by length only`() {
        val body = "<html>gateway</html>"
        val r = ProbeMatrix.analyze(v4, 502, body)
        assertEquals("non-json", r.dataShape)
        assertNull(r.code)
        val line = assertValueFree(r)
        assertFalse(line.contains("gateway") || line.contains("html"), line)
        assertTrue(line.contains("len=${body.length}"))
    }

    @Test
    fun `failed carries the exception class name only`() {
        val r = ProbeMatrix.failed(v4, java.net.SocketTimeoutException("timeout to ${F.DID}"))
        val line = assertValueFree(r)
        assertTrue(line.endsWith("error=SocketTimeoutException"), line)
    }

    @Test
    fun `firstDeviceUid returns first item uid or null`() {
        assertEquals(F.CLOUD_UID, ProbeMatrix.firstDeviceUid(v4Body))
        assertNull(ProbeMatrix.firstDeviceUid(tnpBody))
        assertNull(ProbeMatrix.firstDeviceUid("""{"code":"20200"}"""))
        assertNull(ProbeMatrix.firstDeviceUid("<html/>"))
        assertNull(ProbeMatrix.firstDeviceUid(null))
    }

    @Test
    fun `friends is never a device source`() {
        assertTrue(ProbeMatrix.isDeviceSource("/v4/devices/list"))
        assertTrue(ProbeMatrix.isDeviceSource("/v5/devices/list"))
        assertTrue(ProbeMatrix.isDeviceSource("/v5/deviceshare/shareddetail"))
        assertFalse(ProbeMatrix.isDeviceSource("/v5/deviceshare/friends"))
    }

    @Test
    fun `friends rows without device keys are not device-like`() {
        val body = """{"code":"20000","data":[{"userid":"${F.USER_ID}","nickname":"Living Room SYNTH","email":"${F.EMAIL}"}]}"""
        val r = ProbeMatrix.analyze(ProbeTarget("US", "https://gw-us.xiaoyi.com", "/v5/deviceshare/friends"), 200, body)
        assertFalse(r.looksLikeDevices)
        assertTrue(assertValueFree(r).contains("devicelike=false"))
    }
}
