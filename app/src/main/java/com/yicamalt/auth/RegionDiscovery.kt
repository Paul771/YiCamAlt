// FILE: RegionDiscovery.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Device-list presence check used by the login region discovery (CAM-02): does this session's
//     gateway hold any devices for the account.
//   SCOPE: DeviceListCheckPort (one signed GET /v5/devices/list on the current gateway, reduced to response
//     code + item count), the production HTTP implementation on the shared M-HTTP client, and the pure
//     body reducer. No device parsing, no retries, no logging of values.
//   DEPENDS: M-HTTP (HttpClientModule, YiRequestSigner)
//   LINKS: M-AUTH, M-HTTP, M-CONFIG, V-M-AUTH
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.auth

import com.yicamalt.network.HttpClientModule
import com.yicamalt.network.YiRequestSigner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

// START_MODULE_MAP
//   DeviceListCheck - value-free outcome of one check: response code + item count; hasDevices.
//   DeviceListCheckPort - one device-list presence check for a session on the current gateway.
//   NoDeviceListCheck - default port: discovery disabled (AuthRepository built without a check).
//   HttpDeviceListCheck - production port: signed GET /v5/devices/list on the shared OkHttp client.
//   DeviceListCounter - pure reducer: response body -> DeviceListCheck.
//   DEVICE_LIST_OK_CODE / DEVICE_LIST_EMPTY_CODE / DEVICE_LIST_PATH - wire constants.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - Initial (plan 01-08 + addendum). Probe 2026-10-11: the same credentials log in on
//     gw-us, gw-eu and gw-sg, but only one gateway holds the cameras; the others answer code 20200
//     (= no devices on this server). Login success therefore does not identify the region.
//   HAZARD: independent of the device parsers (M-CAMERA-LIST) on purpose: only code + count are read.
//     Never log the user id, the hmac or the body from here.
// END_CHANGE_SUMMARY

/** Device list answered with data. */
internal const val DEVICE_LIST_OK_CODE = "20000"

/** "This account has no devices on this server": an empty list, not an error. */
internal const val DEVICE_LIST_EMPTY_CODE = "20200"

/** The only owner-list path the vendor app uses (shared cameras arrive in the same list). */
internal const val DEVICE_LIST_PATH = "v5/devices/list"

/** Value-free outcome of one device-list check. `code` is null when the body is not a JSON envelope. */
data class DeviceListCheck(val code: String?, val count: Int) {
    /** True only for a success envelope with at least one item. */
    val hasDevices: Boolean get() = code == DEVICE_LIST_OK_CODE && count > 0
}

/** One device-list presence check for [session] on the gateway that is currently selected. */
fun interface DeviceListCheckPort {
    suspend fun check(session: AuthSession): DeviceListCheck
}

/** Default port: no check is ever made, so login keeps the first gateway that accepts the account. */
internal object NoDeviceListCheck : DeviceListCheckPort {
    override suspend fun check(session: AuthSession): DeviceListCheck = DeviceListCheck(null, 0)
}

internal object DeviceListCounter {

    private val SAFE_CODE = Regex("^-?[0-9]{1,6}$")
    private val json = Json { isLenient = true }

    // START_CONTRACT: reduce
    //   PURPOSE: Reduce a device-list response body to its code and item count.
    //   INPUTS: { body: String? - raw response body, not retained }
    //   OUTPUTS: { DeviceListCheck - code (digits only, "?" for anything else, null without an envelope), count }
    //   SIDE_EFFECTS: none
    //   LINKS: V-M-AUTH
    // END_CONTRACT: reduce
    fun reduce(body: String?): DeviceListCheck {
        // START_BLOCK_REDUCE_DEVICE_LIST
        val root = parse(body) as? JsonObject ?: return DeviceListCheck(null, 0)
        val code = (root["code"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
            ?.let { if (SAFE_CODE.matches(it)) it else "?" }
        return DeviceListCheck(code, countItems(root["data"], depth = 0))
        // END_BLOCK_REDUCE_DEVICE_LIST
    }

    /** Array: its size. Object: the first array inside, else 0. String: JSON-encoded array/object. */
    private fun countItems(data: JsonElement?, depth: Int): Int = when {
        data == null || data is JsonNull || depth > 1 -> 0
        data is JsonArray -> data.size
        data is JsonObject -> (data.values.firstOrNull { it is JsonArray } as? JsonArray)?.size ?: 0
        data is JsonPrimitive && data.isString ->
            countItems(parse(data.content)?.takeIf { it !is JsonPrimitive }, depth + 1)
        else -> 0
    }

    private fun parse(text: String?): JsonElement? =
        if (text.isNullOrBlank()) null else runCatching { json.parseToJsonElement(text) }.getOrNull()
}

/**
 * Production check: one signed GET on the shared client. The request is built on the placeholder host,
 * so M-HTTP sends it to the gateway that is selected at call time. An I/O failure propagates to the caller.
 */
@Singleton
class HttpDeviceListCheck @Inject constructor(
    private val http: HttpClientModule,
) : DeviceListCheckPort {

    // START_CONTRACT: check
    //   PURPOSE: Ask the current gateway whether the account has devices there.
    //   INPUTS: { session: AuthSession - token, token_secret and user id of the login just made }
    //   OUTPUTS: { DeviceListCheck - code + count }
    //   SIDE_EFFECTS: exactly one GET /v5/devices/list; no retry
    //   LINKS: M-HTTP, V-M-AUTH
    // END_CONTRACT: check
    override suspend fun check(session: AuthSession): DeviceListCheck = withContext(Dispatchers.IO) {
        // START_BLOCK_DEVICE_LIST_CHECK
        val url = (HttpClientModule.PLACEHOLDER_BASE_URL + DEVICE_LIST_PATH).toHttpUrl().newBuilder()
            .addQueryParameter("seq", "1")
            .addQueryParameter("userid", session.userId)
            .addQueryParameter("hmac", YiRequestSigner.sign(session.accessToken, session.refreshToken, session.userId))
            .build()
        http.getHttpClient().newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            DeviceListCounter.reduce(response.body?.string())
        }
        // END_BLOCK_DEVICE_LIST_CHECK
    }
}
