// FILE: DeviceRecordParserTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Pin DeviceRecordParser and the CameraListParser key fixes on synthetic fixtures.
//   SCOPE: device-list shapes (array, wrapped, JSON-encoded, absent), ipcParam object or string,
//     type / boolean coercion, ownership, tnp device_info, value-free toString(), DevicePaths.
//   DEPENDS: M-CAMERA-LIST, DeviceListFixtures, RedactionFixtures, RedactionScanner
//   LINKS: V-M-CAMERA-LIST, M-CAMERA-LIST, M-P2P-CREDS
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.camera

import com.yicamalt.test.RedactionFixtures
import com.yicamalt.test.RedactionScanner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// START_MODULE_MAP
//   dataOf - data element of an envelope JSON string (null when absent).
//   rows - parse a JSON array literal of device rows.
//   DeviceRecordParserTest - one test per behavior of plan 01-07 plus the addendum (20200, ownership).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - Created (plan 01-07).
// END_CHANGE_SUMMARY

class DeviceRecordParserTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun dataOf(envelope: String): JsonElement? = json.parseToJsonElement(envelope).jsonObject["data"]

    private fun rows(arrayLiteral: String): List<DeviceRecord> =
        DeviceRecordParser.parse(json.parseToJsonElement(arrayLiteral), shared = false)

    // START_BLOCK_DEVICE_LIST_SHAPES
    @Test
    fun `list in the measured shape parses into two records and drops the row without an id`() {
        val records = DeviceRecordParser.parse(dataOf(DeviceListFixtures.V4_LIST), shared = false)
        assertEquals(2, records.size)

        val tnp = records[0]
        assertEquals(RedactionFixtures.CLOUD_UID, tnp.cloudUid)
        assertEquals(2, tnp.type)
        assertEquals(RedactionFixtures.DID, tnp.p2pId)
        assertEquals(RedactionFixtures.PASSWORD_HEX, tnp.passwordHex)
        assertTrue(tnp.p2pEncrypt, "string true inside an ipcParam object")
        assertFalse(tnp.wakeup)
        assertTrue(tnp.online)
        assertFalse(tnp.hasPincode)
        assertFalse(tnp.shared)
        assertEquals(DeviceListFixtures.NAME_1, tnp.name)
        assertEquals(DeviceListFixtures.MODEL, tnp.model)

        val tutk = records[1]
        assertEquals(RedactionFixtures.TUTK_UID, tutk.cloudUid)
        assertEquals(0, tutk.type, "type given as a string")
        assertEquals(RedactionFixtures.TUTK_UID, tutk.p2pId, "no did: falls back to the uid")
        assertEquals("", tutk.passwordHex)
        assertFalse(tutk.p2pEncrypt, "string false inside an ipcParam JSON string")
        assertTrue(tutk.wakeup, "number 1 inside an ipcParam JSON string")
        assertFalse(tutk.online)
        assertTrue(tutk.hasPincode, "string true")
        assertEquals(DeviceListFixtures.NAME_2, tutk.name)
    }

    @Test
    fun `data wrapped in an object or given as a JSON-encoded string parses the same`() {
        val bare = DeviceRecordParser.parse(dataOf(DeviceListFixtures.V4_LIST), shared = false)
        assertEquals(bare, DeviceRecordParser.parse(dataOf(DeviceListFixtures.WRAPPED_LIST), shared = false))
        assertEquals(bare, DeviceRecordParser.parse(dataOf(DeviceListFixtures.ENCODED_DATA_LIST), shared = false))
        for (key in listOf("list", "devices", "device_list")) {
            assertEquals(1, DeviceRecordParser.parse(json.parseToJsonElement("""{"$key":[{"uid":"SYNTHUIDX"}]}"""), false).size, key)
        }
    }

    @Test
    fun `code 20200 without data is an empty list`() {
        assertNull(dataOf(DeviceListFixtures.EMPTY_20200))
        assertTrue(DeviceRecordParser.parse(dataOf(DeviceListFixtures.EMPTY_20200), shared = false).isEmpty())
        assertTrue(DeviceRecordParser.parse(null, shared = false).isEmpty())
        assertTrue(DeviceRecordParser.parse(dataOf("""{"code":"20200","data":null}"""), shared = false).isEmpty())
        assertTrue(rows("[]").isEmpty())
        assertTrue(DeviceRecordParser.parse(json.parseToJsonElement("\"not json\""), shared = false).isEmpty())
    }
    // END_BLOCK_DEVICE_LIST_SHAPES

    // START_BLOCK_FIELD_COERCION
    @Test
    fun `type is read as number or string and is null when missing`() {
        assertEquals(2, rows("""[{"uid":"SYNTHUIDX","type":2}]""").single().type)
        assertEquals(2, rows("""[{"uid":"SYNTHUIDX","type":"2"}]""").single().type)
        assertNull(rows("""[{"uid":"SYNTHUIDX"}]""").single().type)
        assertNull(rows("""[{"uid":"SYNTHUIDX","type":"tnp"}]""").single().type)
    }

    @Test
    fun `hasPincode is true only for true and one`() {
        for (truthy in listOf("true", "\"true\"", "1", "\"1\"")) {
            assertTrue(rows("""[{"uid":"SYNTHUIDX","hasPincode":$truthy}]""").single().hasPincode, truthy)
        }
        for (falsy in listOf("false", "\"false\"", "0", "\"yes\"", "null", "2")) {
            assertFalse(rows("""[{"uid":"SYNTHUIDX","hasPincode":$falsy}]""").single().hasPincode, falsy)
        }
        assertFalse(rows("""[{"uid":"SYNTHUIDX"}]""").single().hasPincode)
    }

    @Test
    fun `p2p_encrypt defaults to true when ipcParam or the key is absent and wakeup to false`() {
        val noIpc = rows("""[{"uid":"SYNTHUIDX"}]""").single()
        assertTrue(noIpc.p2pEncrypt)
        assertFalse(noIpc.wakeup)
        assertTrue(rows("""[{"uid":"SYNTHUIDX","ipcParam":{"ssid":"SYNTH"}}]""").single().p2pEncrypt)
        assertTrue(rows("""[{"uid":"SYNTHUIDX","ipcParam":"not json"}]""").single().p2pEncrypt)
        assertTrue(rows("""[{"uid":"SYNTHUIDX","ipcParam":{"p2p_encrypt":true}}]""").single().p2pEncrypt)
        assertFalse(rows("""[{"uid":"SYNTHUIDX","ipcParam":{"p2p_encrypt":false}}]""").single().p2pEncrypt)
        assertFalse(rows("""[{"uid":"SYNTHUIDX","ipcParam":{"p2p_encrypt":"0"}}]""").single().p2pEncrypt)
        assertTrue(rows("""[{"uid":"SYNTHUIDX","ipc_param":{"wakeup":"true"}}]""").single().wakeup)
    }

    @Test
    fun `password is null when absent and empty when present but empty`() {
        assertNull(rows("""[{"uid":"SYNTHUIDX"}]""").single().passwordHex)
        assertEquals("", rows("""[{"uid":"SYNTHUIDX","password":""}]""").single().passwordHex)
    }

    @Test
    fun `type is not the model`() {
        val withModel = rows("""[{"uid":"SYNTHUIDX","type":2,"model":"${DeviceListFixtures.MODEL}"}]""").single()
        assertEquals(DeviceListFixtures.MODEL, withModel.model)
        assertEquals("", rows("""[{"uid":"SYNTHUIDX","type":2}]""").single().model)

        // Same regression for the CameraInfo parser, which also takes uid as the id now.
        val infos = CameraListParser.extract(json.parseToJsonElement("""[{"uid":"SYNTHUIDX","did":"SYNTHDIDX","type":2}]"""))
        assertEquals("", infos.single().model)
        assertEquals("SYNTHUIDX", infos.single().deviceId)
    }
    // END_BLOCK_FIELD_COERCION

    // START_BLOCK_OWNERSHIP
    @Test
    fun `ownership comes from category then share then the caller flag`() {
        val bySource = DeviceRecordParser.parse(dataOf(DeviceListFixtures.SHARED_LIST), shared = true)
        assertEquals(2, bySource.size)
        assertTrue(bySource.all { it.shared })

        val byRow = DeviceRecordParser.parse(dataOf(DeviceListFixtures.SHARED_LIST), shared = false)
        assertTrue(byRow[0].shared, "category 1")
        assertFalse(byRow[1].shared, "no ownership field")

        assertFalse(rows("""[{"uid":"SYNTHUIDX","category":0,"share":true}]""").single().shared, "category wins")
        assertTrue(rows("""[{"uid":"SYNTHUIDX","share":true}]""").single().shared)
        assertTrue(rows("""[{"uid":"SYNTHUIDX","category":"2"}]""").single().shared)
    }
    // END_BLOCK_OWNERSHIP

    // START_BLOCK_TNP_INFO
    @Test
    fun `tnp device_info gives did init string and the license part before the colon`() {
        val info = DeviceRecordParser.parseTnpInfo(dataOf(DeviceListFixtures.TNP_INFO))
        assertNotNull(info)
        assertEquals(RedactionFixtures.DID, info!!.did)
        assertEquals(RedactionFixtures.INIT_STRING, info.initString)
        assertEquals("SYNTHLIC01", info.licenseKey)
    }

    @Test
    fun `tnp device_info tolerates a license without colon lower-case keys and encoded data`() {
        val plain = DeviceRecordParser.parseTnpInfo(
            json.parseToJsonElement("""{"did":"SYNTHDIDX","initstring":"SYNTHINITX","license":"SYNTHLICX"}"""),
        )
        assertEquals(TnpDeviceInfo("SYNTHDIDX", "SYNTHINITX", "SYNTHLICX"), plain)

        val encoded = DeviceRecordParser.parseTnpInfo(
            json.parseToJsonElement(""""{\"DID\":\"SYNTHDIDX\",\"InitString\":\"SYNTHINITX\",\"License\":\"SYNTHLICX:SYNTHREST\"}""""),
        )
        assertEquals(TnpDeviceInfo("SYNTHDIDX", "SYNTHINITX", "SYNTHLICX"), encoded)
    }

    @Test
    fun `tnp device_info is null when any of the three parts is missing or blank`() {
        fun parse(body: String) = DeviceRecordParser.parseTnpInfo(json.parseToJsonElement(body))
        assertNull(parse("""{"InitString":"SYNTHINITX","License":"SYNTHLICX"}"""))
        assertNull(parse("""{"DID":"SYNTHDIDX","License":"SYNTHLICX"}"""))
        assertNull(parse("""{"DID":"SYNTHDIDX","InitString":"SYNTHINITX"}"""))
        assertNull(parse("""{"DID":" ","InitString":"SYNTHINITX","License":"SYNTHLICX"}"""))
        assertNull(parse("""{"DID":"SYNTHDIDX","InitString":"SYNTHINITX","License":":SYNTHREST"}"""))
        assertNull(parse("[]"))
        assertNull(DeviceRecordParser.parseTnpInfo(null))
        assertNull(DeviceRecordParser.parseTnpInfo(dataOf(DeviceListFixtures.EMPTY_20200)))
    }
    // END_BLOCK_TNP_INFO

    // START_BLOCK_NO_SECRETS_IN_TOSTRING
    @Test
    fun `record types never print secrets`() {
        val scanner = RedactionScanner()
        val records = DeviceRecordParser.parse(dataOf(DeviceListFixtures.V4_LIST), shared = false)
        val info = DeviceRecordParser.parseTnpInfo(dataOf(DeviceListFixtures.TNP_INFO))!!
        val secrets = RedactionFixtures.ALL_SECRETS +
            listOf("SYNTHLIC01", DeviceListFixtures.NAME_1, DeviceListFixtures.NAME_2, DeviceListFixtures.MODEL)
        scanner.assertClean(records.toString(), extraLiterals = secrets)
        scanner.assertClean("$info ${listOf(info)}", extraLiterals = secrets)
        assertTrue(records[0].toString().contains("hasPassword=true"))
        assertTrue(records[1].toString().contains("hasPassword=false"))
    }
    // END_BLOCK_NO_SECRETS_IN_TOSTRING

    // START_BLOCK_DEVICE_PATHS
    @Test
    fun `device paths are the vendor list path and tnp device_info with no share path`() {
        assertEquals("/v5/devices/list", DevicePaths.LIST_V5)
        assertEquals("/v4/tnp/device_info", DevicePaths.TNP_DEVICE_INFO)
        val declared = DevicePaths::class.java.declaredFields.filter { it.type == String::class.java }
            .map { it.get(null) as String }
        assertTrue(declared.none { it.contains("deviceshare") }, "no share path may be listed")
    }
    // END_BLOCK_DEVICE_PATHS
}
