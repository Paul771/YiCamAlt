// FILE: LogRedactor.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Single source of log-value masking for the HTTP layer (SEC-01).
//   SCOPE: mask secret query parameters in request/response lines; reduce JSON bodies to shape-only form.
//   DEPENDS: none
//   LINKS: M-HTTP, V-M-HTTP, SEC-01
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

// START_MODULE_MAP
//   LogRedactor - object: MASK, QUERY_KEYS, maskQuery, isSecretKey, maskJsonBody.
//   LogRedactor.maskQuery - replaces values of secret query keys (hmac, userid, uid, token, ...) with ██.
//   LogRedactor.isSecretKey - name-based helper for key/value callers (does NOT protect bodies).
//   LogRedactor.maskJsonBody - allowlist (shape-only) JSON masker; keeps key names, `code`, booleans, null.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - SEC-01: extracted from HttpClientModule ad-hoc regexes. Bodies are masked by
//     ALLOWLIST (every string/number masked except `code`), because unknown device-list field names
//     cannot be protected by a key denylist. Encoded-`data` string envelopes are masked recursively.
// END_CHANGE_SUMMARY

internal object LogRedactor {

    const val MASK = "██"

    val QUERY_KEYS = listOf(
        "password", "account", "hmac", "token", "token_secret", "userid", "uid",
        "pincode", "did", "license", "nonce",
    )

    private val SECRET_KEY_FRAGMENTS = listOf(
        "token", "secret", "password", "pwd", "hmac", "license", "initstring", "nonce",
        "authkey", "signature", "p2pid", "p2p_id", "serial", "pincode",
    )

    private val SECRET_KEY_EXACT = setOf(
        "did", "uid", "sn", "userid", "user_id", "device_id", "deviceid", "id",
        "account", "email", "mac", "ip", "ssid",
    )

    // Key must not be preceded by a word char, so `seq=` or `xuid=` are not touched by accident.
    private val QUERY_REGEX = Regex(
        "(?i)((?<![A-Za-z0-9_])(?:${QUERY_KEYS.sortedByDescending { it.length }.joinToString("|")})=)[^&\\s#]+",
    )

    private val SAFE_KEY = Regex("^[A-Za-z_][A-Za-z0-9_.-]{0,39}$")
    private val DIGIT_RUN = Regex("\\d{5,}")
    private const val MAX_ARRAY_ITEMS = 3

    private val parser = Json { isLenient = true }

    // START_CONTRACT: maskQuery
    //   PURPOSE: Mask values of secret query parameters inside a log line or URL.
    //   INPUTS: { text: String - request/response line or encoded path }
    //   OUTPUTS: { String - same text with `key=██` for every QUERY_KEYS key }
    //   SIDE_EFFECTS: none
    //   LINKS: M-HTTP
    // END_CONTRACT: maskQuery
    fun maskQuery(text: String): String = text.replace(QUERY_REGEX, "$1$MASK")

    // START_CONTRACT: isSecretKey
    //   PURPOSE: Name-based classification of a key as secret-bearing.
    //   INPUTS: { key: String - JSON or query key name }
    //   OUTPUTS: { Boolean - true when the lowercase key contains a secret fragment or is an exact id key }
    //   SIDE_EFFECTS: none
    //   LINKS: M-HTTP
    // END_CONTRACT: isSecretKey
    fun isSecretKey(key: String): Boolean {
        val k = key.lowercase()
        return k in SECRET_KEY_EXACT || SECRET_KEY_FRAGMENTS.any { k.contains(it) }
    }

    // START_CONTRACT: maskJsonBody
    //   PURPOSE: Reduce a response body to its shape so no value under any key name can leak.
    //   INPUTS: { body: String - raw (peeked) response body }
    //   OUTPUTS: { String - masked JSON, or `<non-json len=N>` when the body is not a JSON object/array }
    //   SIDE_EFFECTS: none
    //   LINKS: M-HTTP, V-M-HTTP
    // END_CONTRACT: maskJsonBody
    fun maskJsonBody(body: String): String {
        // START_BLOCK_MASK_JSON_BODY
        val root = parseContainer(body) ?: return "<non-json len=${body.length}>"
        return try {
            maskElement(root, key = null).toString()
        } catch (e: Exception) {
            "<non-json len=${body.length}>"
        }
        // END_BLOCK_MASK_JSON_BODY
    }

    /** Parse only JSON objects/arrays; lenient bare literals (e.g. HTML) are rejected. */
    private fun parseContainer(text: String): JsonElement? {
        val trimmed = text.trim()
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) return null
        val el = try { parser.parseToJsonElement(trimmed) } catch (e: Exception) { return null }
        return if (el is JsonObject || el is JsonArray) el else null
    }

    private fun maskElement(el: JsonElement, key: String?): JsonElement = when (el) {
        is JsonNull -> JsonNull
        is JsonObject -> maskObject(el)
        is JsonArray -> maskArray(el)
        is JsonPrimitive -> maskPrimitive(el, key)
    }

    private fun maskObject(obj: JsonObject): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        for ((k, v) in obj) {
            var name = if (SAFE_KEY.matches(k) && !DIGIT_RUN.containsMatchIn(k)) k else "<key>"
            if (name in out) {
                var n = 2
                while ("<key$n>" in out) n++
                name = "<key$n>"
            }
            out[name] = maskElement(v, k)
        }
        return JsonObject(out)
    }

    private fun maskArray(arr: JsonArray): JsonArray {
        val items = arr.take(MAX_ARRAY_ITEMS).map { maskElement(it, key = null) }.toMutableList()
        if (arr.size > MAX_ARRAY_ITEMS) items += JsonPrimitive("<+${arr.size - MAX_ARRAY_ITEMS} more>")
        return JsonArray(items)
    }

    private fun maskPrimitive(p: JsonPrimitive, key: String?): JsonElement {
        if (key == "code") return p
        if (!p.isString && p.booleanOrNull != null) return p
        if (p.isString) {
            // Encoded envelope: a string that itself carries a JSON object/array.
            val nested = parseContainer(p.content)
            if (nested != null) return JsonPrimitive(maskElement(nested, key = null).toString())
        }
        return JsonPrimitive(MASK)
    }
}
