// FILE: DeviceRecordParser.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Shape-tolerant parsers for the Yi device list and /v4/tnp/device_info.
//   SCOPE: DeviceRecord (one device-list row with the fields the P2P layer needs), TnpDeviceInfo
//     (DID, InitString, license key), DeviceRecordParser.parse / parseTnpInfo. Pure functions over
//     JsonElement: no network, no storage, no logging, no decryption.
//   DEPENDS: CameraListParser helpers (CameraInfo.kt)
//   LINKS: M-CAMERA-LIST, M-P2P-CREDS, V-M-CAMERA-LIST
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.camera

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// START_MODULE_MAP
//   DeviceRecord - one parsed device-list row; toString() prints flags only, never values.
//   TnpDeviceInfo - DID, InitString, license key (text before the first colon of License); toString() is value-free.
//   DeviceRecordParser.parse - device-list data (array, wrapping object, JSON-encoded string, absent) to records.
//   DeviceRecordParser.parseTnpInfo - /v4/tnp/device_info data to TnpDeviceInfo, or null when incomplete.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - Created (plan 01-07). Key provenance:
//     MEASURED on the device (01-PROBE-RESULTS.md, 2026-10-11, names only): uid, did, type, password,
//       name, model, online, hasPincode, ipcParam, category, share; ipcParam inner key p2p_encrypt;
//       tnp device_info keys DID, InitString, License.
//     From static analysis of the vendor app (01-STATIC-FINDINGS.md): ipcParam inner key wakeup,
//       ipcParam as a JSON string, category 0 = own, p2p_encrypt defaults to true when absent,
//       code 20200 = no devices (data absent, parses to an empty list).
//     Research candidates, not seen on the wire: device_id, deviceId, sn, id, p2pid, p2p_id, p2pId,
//       tnp_did, p2p_type, p2pType, pwd, view_password, viewPassword, has_pincode, ipc_param.
//   INVARIANT: shared rows are NOT nested under another key; they are plain rows of the same array,
//     told apart by category (non-zero) or, when category is absent, by share.
//   INVARIANT: these types carry secrets (uid, did, password, InitString, license); never log an
//     instance field, only toString().
// END_CHANGE_SUMMARY

/** One device-list row. [passwordHex] is still encrypted; decryption belongs to M-P2P-CREDS. */
internal data class DeviceRecord(
    val cloudUid: String,
    val name: String,
    val model: String,
    val online: Boolean,
    val type: Int?,
    val p2pId: String?,
    val passwordHex: String?,
    val hasPincode: Boolean,
    val p2pEncrypt: Boolean,
    val wakeup: Boolean,
    val shared: Boolean,
) {
    override fun toString() =
        "DeviceRecord(type=$type, online=$online, shared=$shared, hasP2pId=${!p2pId.isNullOrBlank()}, " +
            "hasPassword=${!passwordHex.isNullOrBlank()}, encrypt=$p2pEncrypt, wakeup=$wakeup, hasPincode=$hasPincode)"
}

/** TNP (CS2 PPPP) connection credentials of one camera. */
internal data class TnpDeviceInfo(val did: String, val initString: String, val licenseKey: String) {
    override fun toString() = "TnpDeviceInfo(hasDid=true, hasInitString=true, hasLicense=true)"
}

internal object DeviceRecordParser {

    // First match wins. Names measured by the probe stand at the front of each list.
    private val UID_KEYS = listOf("uid", "device_id", "deviceId", "did", "sn", "id")
    private val P2PID_KEYS = listOf("did", "p2pid", "p2p_id", "p2pId", "DID", "tnp_did")
    private val TYPE_KEYS = listOf("type", "p2p_type", "p2pType")
    private val PASSWORD_KEYS = listOf("password", "pwd", "view_password", "viewPassword")
    private val NAME_KEYS = listOf("name", "device_name", "nick_name", "nickname", "alias")
    private val MODEL_KEYS = listOf("model", "device_model", "model_name", "dev_type")
    private val ONLINE_KEYS = listOf("online", "is_online", "online_status")
    private val PINCODE_KEYS = listOf("hasPincode", "has_pincode")
    private val IPC_KEYS = listOf("ipcParam", "ipc_param")
    private val CATEGORY_KEYS = listOf("category")
    private val SHARE_KEYS = listOf("share")
    private const val IPC_ENCRYPT = "p2p_encrypt"
    private const val IPC_WAKEUP = "wakeup"

    private val TNP_DID_KEYS = listOf("DID", "did")
    private val TNP_INIT_KEYS = listOf("InitString", "init_string")
    private val TNP_LICENSE_KEYS = listOf("License", "license_key")

    // START_CONTRACT: parse
    //   PURPOSE: Map the data element of a device-list envelope to DeviceRecord rows.
    //   INPUTS: { element: JsonElement? - envelope data: array, object wrapping an array, JSON-encoded
    //     string of either, or null (code 20200 = no devices); shared: Boolean - force every row to
    //     shared = true (a row is also shared when its own category / share field says so) }
    //   OUTPUTS: { List<DeviceRecord> - rows that carry an id; empty when there is no data }
    //   SIDE_EFFECTS: none
    //   LINKS: M-CAMERA-LIST, V-M-CAMERA-LIST
    // END_CONTRACT: parse
    fun parse(element: JsonElement?, shared: Boolean): List<DeviceRecord> {
        // START_BLOCK_PARSE_DEVICE_LIST
        if (element == null || element is JsonNull) return emptyList()
        return CameraListParser.collectCameraObjects(element, depth = 0).mapNotNull { obj ->
            val uid = CameraListParser.firstString(obj, UID_KEYS) ?: return@mapNotNull null
            val ipc = ipcObject(obj)
            DeviceRecord(
                cloudUid = uid,
                name = CameraListParser.firstString(obj, NAME_KEYS).orEmpty(),
                model = CameraListParser.firstString(obj, MODEL_KEYS).orEmpty(),
                online = CameraListParser.firstBool(obj, ONLINE_KEYS),
                type = firstInt(obj, TYPE_KEYS),
                // The display DID when present, else the cloud uid (TUTK rows carry no separate id).
                p2pId = CameraListParser.firstString(obj, P2PID_KEYS) ?: uid,
                passwordHex = firstText(obj, PASSWORD_KEYS),
                hasPincode = strictBool(firstPrimitive(obj, PINCODE_KEYS)),
                // Vendor default: encryption is ON unless ipcParam says otherwise.
                p2pEncrypt = ipc?.get(IPC_ENCRYPT)?.let { strictBool(it as? JsonPrimitive) } ?: true,
                wakeup = strictBool(ipc?.get(IPC_WAKEUP) as? JsonPrimitive),
                shared = shared || isSharedRow(obj),
            )
        }
        // END_BLOCK_PARSE_DEVICE_LIST
    }

    // START_CONTRACT: parseTnpInfo
    //   PURPOSE: Map the data element of a /v4/tnp/device_info envelope to TnpDeviceInfo.
    //   INPUTS: { element: JsonElement? - envelope data: object or JSON-encoded string of an object }
    //   OUTPUTS: { TnpDeviceInfo? - null when DID, InitString or License is missing or blank }
    //   SIDE_EFFECTS: none
    //   LINKS: M-P2P-CREDS, V-M-CAMERA-LIST
    // END_CONTRACT: parseTnpInfo
    fun parseTnpInfo(element: JsonElement?): TnpDeviceInfo? {
        // START_BLOCK_PARSE_TNP_INFO
        val obj = (element as? JsonObject)
            ?: (CameraListParser.decodeEmbeddedJson(element) as? JsonObject)
            ?: return null
        val did = firstTextIgnoreCase(obj, TNP_DID_KEYS) ?: return null
        val initString = firstTextIgnoreCase(obj, TNP_INIT_KEYS) ?: return null
        val license = firstTextIgnoreCase(obj, TNP_LICENSE_KEYS) ?: return null
        // The device key is the text before the first colon; a License without a colon is the key itself.
        val licenseKey = license.substringBefore(':').trim().takeIf { it.isNotEmpty() } ?: return null
        return TnpDeviceInfo(did = did, initString = initString, licenseKey = licenseKey)
        // END_BLOCK_PARSE_TNP_INFO
    }

    // START_CONTRACT: parseTnpDid
    //   PURPOSE: Raw DID of a /v4/tnp/device_info answer, even when InitString / License are missing (the vendor
    //     transport rule looks at the DID alone).
    //   INPUTS: { element: JsonElement? - envelope data: object or JSON-encoded string of an object }
    //   OUTPUTS: { String? - DID (key DID/did, case-insensitive), null when absent or blank }
    //   SIDE_EFFECTS: none (the value is a secret and is never logged)
    //   LINKS: M-P2P-CREDS
    // END_CONTRACT: parseTnpDid
    fun parseTnpDid(element: JsonElement?): String? = TODO()

    // START_BLOCK_FIELD_HELPERS
    /** ipcParam as an object, whether it arrived as an object or as a JSON-encoded string. */
    private fun ipcObject(obj: JsonObject): JsonObject? {
        for (key in IPC_KEYS) {
            val value = obj[key] ?: continue
            (value as? JsonObject)?.let { return it }
            (CameraListParser.decodeEmbeddedJson(value) as? JsonObject)?.let { return it }
        }
        return null
    }

    /** category decides when present (0 = own); otherwise the share flag. */
    private fun isSharedRow(obj: JsonObject): Boolean {
        val category = firstInt(obj, CATEGORY_KEYS)
        if (category != null) return category != 0
        return strictBool(firstPrimitive(obj, SHARE_KEYS))
    }

    private fun firstPrimitive(obj: JsonObject, keys: List<String>): JsonPrimitive? =
        keys.firstNotNullOfOrNull { key -> (obj[key] as? JsonPrimitive)?.takeIf { it !is JsonNull } }

    /** Only true, "true", 1 and "1" are true; everything else, including absent, is false. */
    private fun strictBool(value: JsonPrimitive?): Boolean {
        if (value == null || value is JsonNull) return false
        val text = value.content.trim().lowercase()
        return text == "true" || text == "1"
    }

    /** Number 2 or string "2" gives 2; absent or not an integer gives null. */
    private fun firstInt(obj: JsonObject, keys: List<String>): Int? =
        keys.firstNotNullOfOrNull { key ->
            (obj[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()?.toIntOrNull()
        }

    /** First present key as trimmed text; a present but empty value gives "" (password: default applies). */
    private fun firstText(obj: JsonObject, keys: List<String>): String? =
        firstPrimitive(obj, keys)?.content?.trim()

    private fun firstTextIgnoreCase(obj: JsonObject, keys: List<String>): String? {
        for (key in keys) {
            val value = obj.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
            val text = (value as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()
            if (!text.isNullOrEmpty()) return text
        }
        return null
    }
    // END_BLOCK_FIELD_HELPERS
}
