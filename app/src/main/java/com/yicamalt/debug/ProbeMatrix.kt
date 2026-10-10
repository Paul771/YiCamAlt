// FILE: ProbeMatrix.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Probe matrix for the device-list decision (research Conflict A): targets, value-free response analysis, log formatting.
//   SCOPE: 4 list endpoints x 3 regions (US, EU, SG) + the tnp device_info path; JSON shape analysis
//     (code, length, count, shape, field NAMES); uid extraction kept in memory only. Pure; no I/O, no logging.
//   DEPENDS: none
//   LINKS: M-DEBUG-PROBE, V-M-DEBUG-PROBE, M-CAMERA-LIST
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.debug

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// START_MODULE_MAP
//   ProbeTarget - one (region, baseUrl, path) cell of the matrix.
//   ProbeResult - value-free analysis of one response (status, code, len, count, shape, field names).
//   ProbeResult.looksLikeDevices - true when field names include a device-identifying key name.
//   ProbeMatrix - REGIONS, LIST_PATHS, TNP_INFO_PATH, targets, analyze, failed, firstDeviceUid, isDeviceSource, format.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - Initial matrix from research SUMMARY Conflict A.
//     INVARIANT: format() never emits a JSON value — only status/code/len/count/shape/field names.
//     Keys that look like identifiers (5+ digit runs, odd characters) are counted as redactedKeys, never printed.
//     firstDeviceUid() returns a value for the next request only; callers must never log it.
// END_CHANGE_SUMMARY

internal data class ProbeTarget(val region: String, val baseUrl: String, val path: String)

internal data class ProbeResult(
    val region: String,
    val path: String,
    val httpStatus: Int,
    val code: String?,
    val bodyLength: Int,
    val dataCount: Int?,
    val dataShape: String,
    val container: String?,
    val fieldNames: List<String>,
    val nestedFieldNames: Map<String, List<String>>,
    val redactedKeys: Int,
    val error: String?,
    /** Kind per nested field: "object" or "string-json" (defaults to "object" when absent). */
    val nestedKinds: Map<String, String> = emptyMap(),
) {
    /** True only when at least one field name identifies a device (case-insensitive). */
    val looksLikeDevices: Boolean
        get() = fieldNames.any { it.lowercase() in DEVICE_KEYS }

    private companion object {
        val DEVICE_KEYS = setOf("uid", "did", "p2pid", "model", "ipcparam", "password")
    }
}

internal object ProbeMatrix {

    val REGIONS: LinkedHashMap<String, String> = linkedMapOf(
        "US" to "https://gw-us.xiaoyi.com",
        "EU" to "https://gw-eu.xiaoyi.com",
        "SG" to "https://gw-sg.xiaoyi.com",
    )

    val LIST_PATHS = listOf(
        "/v4/devices/list",
        "/v5/devices/list",
        "/v5/deviceshare/friends",
        "/v5/deviceshare/shareddetail",
    )

    const val TNP_INFO_PATH = "/v4/tnp/device_info"

    /** Paths whose rows describe devices; a friends row describes a person and must never feed tnp. */
    private val DEVICE_SOURCE_PATHS = setOf("/v4/devices/list", "/v5/devices/list", "/v5/deviceshare/shareddetail")

    private val SAFE_KEY = Regex("^[A-Za-z_][A-Za-z0-9_.-]{0,39}$")
    private val DIGIT_RUN = Regex("\\d{5,}")
    private val SAFE_CODE = Regex("^-?[0-9]{1,6}$")
    private const val MAX_ITEMS = 3
    private val json = Json { isLenient = true }

    // START_CONTRACT: targets
    //   PURPOSE: The 12 list probes, region-major (US, EU, SG), LIST_PATHS order within a region.
    //   INPUTS: none
    //   OUTPUTS: { List<ProbeTarget> - 12 targets }
    //   SIDE_EFFECTS: none
    // END_CONTRACT: targets
    fun targets(): List<ProbeTarget> =
        REGIONS.flatMap { (region, base) -> LIST_PATHS.map { ProbeTarget(region, base, it) } }

    fun isDeviceSource(path: String): Boolean = path in DEVICE_SOURCE_PATHS

    // START_CONTRACT: analyze
    //   PURPOSE: Reduce a response to value-free facts (code, length, count, shape, field names).
    //   INPUTS: { target: ProbeTarget, httpStatus: Int, body: String? - raw body, not retained }
    //   OUTPUTS: { ProbeResult }
    //   SIDE_EFFECTS: none
    //   LINKS: V-M-DEBUG-PROBE
    // END_CONTRACT: analyze
    fun analyze(target: ProbeTarget, httpStatus: Int, body: String?): ProbeResult {
        // START_BLOCK_ANALYZE_PROBE_RESPONSE
        val len = body?.length ?: 0
        val root = parseObject(body)
            ?: return result(target, httpStatus, null, len, null, "non-json", null, Names())
        val code = (root["code"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
            ?.let { if (SAFE_CODE.matches(it)) it else "?" }
        val names = Names()
        val data = root["data"]
        var shape: String
        var count: Int? = null
        var container: String? = null
        when {
            data == null -> shape = "absent"
            data is JsonNull -> shape = "null"
            data is JsonArray -> { shape = "array"; count = data.size; names.collectItems(data) }
            data is JsonObject -> {
                shape = "object"
                val c = describeObject(data, names)
                count = c.first; container = c.second
            }
            else -> {
                val prim = data as JsonPrimitive
                when (val decoded = if (prim.isString) parseElement(prim.content) else null) {
                    is JsonArray -> { shape = "string-json"; count = decoded.size; names.collectItems(decoded) }
                    is JsonObject -> {
                        shape = "string-json"
                        val c = describeObject(decoded, names)
                        count = c.first; container = c.second
                    }
                    else -> shape = "primitive"
                }
            }
        }
        return result(target, httpStatus, code, len, count, shape, container, names)
        // END_BLOCK_ANALYZE_PROBE_RESPONSE
    }

    /** Result for a request that threw: only the exception's simple class name, never its message. */
    fun failed(target: ProbeTarget, error: Throwable): ProbeResult = ProbeResult(
        region = target.region, path = target.path, httpStatus = -1, code = null, bodyLength = 0,
        dataCount = null, dataShape = "absent", container = null, fieldNames = emptyList(),
        nestedFieldNames = emptyMap(), redactedKeys = 0, error = error::class.simpleName ?: "Throwable",
    )

    // START_CONTRACT: firstDeviceUid
    //   PURPOSE: First data item's `uid` value, for the follow-up tnp request. Never log the result.
    //   INPUTS: { body: String? }
    //   OUTPUTS: { String? - uid or null when no item has a `uid` key }
    //   SIDE_EFFECTS: none
    // END_CONTRACT: firstDeviceUid
    fun firstDeviceUid(body: String?): String? {
        val root = parseObject(body) ?: return null
        val items = itemsOf(root["data"]) ?: return null
        val first = items.firstOrNull() as? JsonObject ?: return null
        val uid = first["uid"] as? JsonPrimitive ?: return null
        if (uid is JsonNull) return null
        return uid.content.takeIf { it.isNotBlank() }
    }

    // START_CONTRACT: format
    //   PURPOSE: One value-free log/UI line for a ProbeResult.
    //   INPUTS: { r: ProbeResult }
    //   OUTPUTS: { String - "region=.. path=.. http=.. code=.. len=.. count=.. shape=.. fields=.. devicelike=.." }
    //   SIDE_EFFECTS: none
    // END_CONTRACT: format
    fun format(r: ProbeResult): String = buildString {
        append("region=").append(r.region)
        append(" path=").append(r.path)
        append(" http=").append(r.httpStatus)
        append(" code=").append(r.code ?: "-")
        append(" len=").append(r.bodyLength)
        append(" count=").append(r.dataCount?.toString() ?: "-")
        append(" shape=").append(r.dataShape)
        r.container?.let { append(" container=").append(it) }
        append(" fields=").append(r.fieldNames.joinToString(","))
        r.nestedFieldNames.forEach { (field, kids) ->
            append(' ').append(field).append('=').append(r.nestedKinds[field] ?: "object")
            append(kids.joinToString(",", "(", ")"))
        }
        append(" devicelike=").append(r.looksLikeDevices)
        if (r.redactedKeys > 0) append(" redactedKeys=").append(r.redactedKeys)
        r.error?.let { append(" error=").append(it) }
    }

    // ---- internals ----------------------------------------------------------------------------

    /** Collected names: top-level fields, nested field names + kind, and the distinct unsafe keys. */
    private class Names {
        val fields = sortedSetOf<String>()
        val nested = sortedMapOf<String, MutableSet<String>>()
        val nestedKind = mutableMapOf<String, String>()
        val redacted = mutableSetOf<String>()

        fun safe(key: String): Boolean {
            val ok = SAFE_KEY.matches(key) && !DIGIT_RUN.containsMatchIn(key)
            if (!ok) redacted += key
            return ok
        }

        fun collectObject(obj: JsonObject) {
            obj.forEach { (k, v) ->
                if (!safe(k)) return@forEach
                fields += k
                val (kind, inner) = when {
                    v is JsonObject -> "object" to v
                    v is JsonPrimitive && v.isString -> (parseElement(v.content) as? JsonObject)?.let { "string-json" to it } ?: return@forEach
                    else -> return@forEach
                }
                nestedKind[k] = kind
                val set = nested.getOrPut(k) { sortedSetOf() }
                inner.keys.forEach { ik -> if (safe(ik)) set += ik }
            }
        }

        fun collectItems(arr: JsonArray) {
            arr.take(MAX_ITEMS).forEach { (it as? JsonObject)?.let(::collectObject) }
        }
    }

    /** Object data: the first safe-keyed array inside wins (container), else the object itself is one item. */
    private fun describeObject(obj: JsonObject, names: Names): Pair<Int, String?> {
        val arrayEntry = obj.entries.firstOrNull { it.value is JsonArray }
        if (arrayEntry != null) {
            val arr = arrayEntry.value as JsonArray
            names.collectItems(arr)
            val container = if (names.safe(arrayEntry.key)) arrayEntry.key else "redacted"
            return arr.size to container
        }
        names.collectObject(obj)
        return 1 to null
    }

    private fun itemsOf(data: JsonElement?): JsonArray? = when (data) {
        is JsonArray -> data
        is JsonObject -> data.values.firstOrNull { it is JsonArray } as? JsonArray ?: JsonArray(listOf(data))
        is JsonPrimitive -> if (data.isString) itemsOf(parseElement(data.content)?.takeIf { it !is JsonPrimitive }) else null
        else -> null
    }

    private fun result(
        t: ProbeTarget, status: Int, code: String?, len: Int, count: Int?, shape: String, container: String?, names: Names,
    ): ProbeResult = ProbeResult(
        region = t.region, path = t.path, httpStatus = status, code = code, bodyLength = len,
        dataCount = count, dataShape = shape, container = container, fieldNames = names.fields.toList(),
        nestedFieldNames = names.nested.mapValues { it.value.toList() }, redactedKeys = names.redacted.size,
        error = null, nestedKinds = names.nestedKind.toMap(),
    )

    private fun parseElement(text: String?): JsonElement? =
        if (text.isNullOrBlank()) null else runCatching { json.parseToJsonElement(text) }.getOrNull()

    private fun parseObject(text: String?): JsonObject? = parseElement(text) as? JsonObject
}
