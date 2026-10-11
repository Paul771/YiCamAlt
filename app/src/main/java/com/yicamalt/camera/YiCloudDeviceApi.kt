// FILE: YiCloudDeviceApi.kt
// VERSION: 0.4.0
// START_MODULE_CONTRACT
//   PURPOSE: Retrofit interface + envelope DTO for the Yi Cloud device endpoints.
//   SCOPE: signed GET of the device list (/v5/devices/list, the only path the vendor app uses) and of
//     /v4/tnp/device_info; DevicePaths names the paths; data is kept as raw JsonElement and decoded
//     by the shape-tolerant parsers (CameraListParser, DeviceRecordParser).
//   DEPENDS: M-HTTP (Retrofit)
//   LINKS: M-CAMERA-LIST
//   ROLE: DATA
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.camera

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Url

// START_MODULE_MAP
//   DevicePaths - path constants: LIST_V5 (default, only owner path), LIST_V4 (same payload, not
//     used by the vendor app, never a default), TNP_DEVICE_INFO.
//   YiCloudDeviceApi - Retrofit service: deviceList(), signedList(@Url), tnpDeviceInfo() (hmac-signed).
//   DeviceListEnvelope - code/message/data wrapper; data kept as raw JsonElement.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.4.0 - DevicePaths + signedList(@Url) for the device-list source; tnpDeviceInfo.
//     Probe 2026-10-11: shared cameras arrive inside /v5/devices/list, so there is no share-list
//     path here (the shareddetail path is a PUT that changes access rights and must not be called).
//     Code 20200 on the list means "no devices on this server" (empty list), not an error.
//   LAST_CHANGE: v0.3.0 - Signed shape confirmed on-device: hmac param = Base64(HMAC-SHA1(
//     key="<token>&<token_secret>", msg="seq=1&userid=<uid>")) => code 20200. Success codes
//     20000/20200; 20201/20202 => Unauthorized. Bearer header is NOT used for this API.
//   LAST_CHANGE: v0.2.0 - Path corrected to /v5/devices/list.
// END_CHANGE_SUMMARY

@Serializable
data class DeviceListEnvelope(
    @SerialName("code") val code: String = "",
    @SerialName("message") val message: String? = null,
    @SerialName("data") val data: JsonElement? = null,
)

// START_BLOCK_DEVICE_PATHS
/** Absolute paths (leading slash) so a Retrofit @Url argument resolves against the host only. */
object DevicePaths {
    /** Default and only owner path; also carries cameras shared with the account. */
    const val LIST_V5 = "/v5/devices/list"

    /** Same payload as LIST_V5 on the probe; the vendor app does not use it. Never a default. */
    const val LIST_V4 = "/v4/devices/list"

    /** TNP (CS2 PPPP) credentials of one camera: DID, InitString, License. */
    const val TNP_DEVICE_INFO = "/v4/tnp/device_info"
}
// END_BLOCK_DEVICE_PATHS

interface YiCloudDeviceApi {
    @GET("v5/devices/list")
    suspend fun deviceList(
        @Query("seq") seq: String = "1",
        @Query("userid") userId: String,
        @Query("hmac") hmac: String,
    ): DeviceListEnvelope

    /** Signed GET of a DevicePaths list path; hmac is over "seq=1&userid=<userId>". */
    @GET
    suspend fun signedList(
        @Url path: String,
        @Query("seq") seq: String = "1",
        @Query("userid") userId: String,
        @Query("hmac") hmac: String,
    ): DeviceListEnvelope

    /** hmac is over "seq=1&userid=<userId>&uid=<uid>"; data carries DID, InitString, License. */
    @GET(DevicePaths.TNP_DEVICE_INFO)
    suspend fun tnpDeviceInfo(
        @Query("seq") seq: String = "1",
        @Query("userid") userId: String,
        @Query("uid") uid: String,
        @Query("hmac") hmac: String,
    ): DeviceListEnvelope
}