// FILE: CameraInfo.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Domain model for a Yi camera + shape-tolerant JSON parser for /v8/cloud/deviceList.
//   SCOPE: immutable CameraInfo value; CameraListParser maps arbitrary device envelopes to CameraInfo.
//   DEPENDS: none
//   LINKS: M-CAMERA-LIST, M-LOCAL-DB
//   ROLE: TYPES
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.camera

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// START_MODULE_MAP
//   CameraInfo - device_id/name/model/online/streamUrl value object.
//   CameraListParser - tolerant extractor: finds the first camera array in any data envelope.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - Created for Phase-2 M-CAMERA-LIST. Field mapping is PROVISIONAL:
//     the real /v8/cloud/deviceList response shape is not captured yet; candidate keys are
//     enumerated so a live capture only needs key additions, not structure changes.
// END_CHANGE_SUMMARY

data class CameraInfo(
    val deviceId: String,
    val name: String,
    val model: String,
    val online: Boolean,
    val streamUrl: String?,
)

/** Shape-tolerant device-list parser. Accepts data as an array of camera objects OR an
 *  object wrapping one (keys deviceList/list/devices/items/device_info/...). */
internal object CameraListParser {

    private val ID_KEYS = listOf("device_id", "deviceId", "did", "sn", "id")
    private val NAME_KEYS = listOf("name", "device_name", "nick_name", "nickname", "alias")
    private val MODEL_KEYS = listOf("model", "device_model", "model_name", "dev_type", "type")
    private val ONLINE_KEYS = listOf("online", "is_online", "online_status")
    private val STREAM_KEYS = listOf("stream_url", "live_url", "streamUrl", "url")
    private val LIST_KEYS = listOf("deviceList", "device_list", "list", "devices", "items", "data")

    fun extract(element: JsonElement?): List<CameraInfo> {
        if (element == null) return emptyList()
        val objects = collectCameraObjects(element, depth = 0)
        return objects.map { obj ->
            CameraInfo(
                deviceId = firstString(obj, ID_KEYS).orEmpty(),
                name = firstString(obj, NAME_KEYS).orEmpty(),
                model = firstString(obj, MODEL_KEYS).orEmpty(),
                online = firstBool(obj, ONLINE_KEYS),
                streamUrl = firstString(obj, STREAM_KEYS),
            )
        }.filter { it.deviceId.isNotBlank() }
    }

    private fun collectCameraObjects(element: JsonElement, depth: Int): List<JsonObject> {
        if (depth > 3) return emptyList()
        return when (element) {
            is JsonObject -> {
                // Prefer an array value under known list keys, else the first array we find.
                val byKey = LIST_KEYS.firstNotNullOfOrNull { k ->
                    (element[k] as? JsonArray)?.takeIf { it.isNotEmpty() }
                }
                if (byKey != null) return byKey.mapNotNull { it as? JsonObject }
                val anyArray = element.values.firstOrNull { it is JsonArray } as? JsonArray
                if (anyArray != null) return anyArray.mapNotNull { it as? JsonObject }
                element.values.flatMap { collectCameraObjects(it, depth + 1) }
            }
            is JsonArray -> element.mapNotNull { it as? JsonObject }
            else -> emptyList()
        }
    }

    private fun firstString(obj: JsonObject, keys: List<String>): String? {
        for (k in keys) {
            val v = obj[k] ?: continue
            if (v is JsonPrimitive && v !is JsonNull && v.isString) {
                val s = v.content.trim()
                if (s.isNotEmpty()) return s
            }
        }
        // numeric ids fallback (e.g. "id":167315 is a primitive number)
        for (k in keys) {
            val v = obj[k] ?: continue
            if (v is JsonPrimitive && v !is JsonNull && !v.isString) {
                val s = v.content.trim()
                if (s.isNotEmpty()) return s
            }
        }
        return null
    }

    private fun firstBool(obj: JsonObject, keys: List<String>): Boolean {
        for (k in keys) {
            val v = obj[k] ?: continue
            if (v is JsonPrimitive && v !is JsonNull) {
                when (v.content.trim().lowercase()) {
                    "1", "true", "online", "yes" -> return true
                    "0", "false", "offline", "no" -> return false
                }
            }
        }
        return false
    }
}