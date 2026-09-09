// FILE: YiCloudDeviceApi.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Retrofit interface + envelope DTO for the Yi Cloud device endpoints.
//   SCOPE: deviceList() hits /v8/cloud/deviceList (reverse-engineered path); data is kept raw
//     JsonElement because the exact success envelope is unconfirmed (provisional for Phase-2).
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

// START_MODULE_MAP
//   YiCloudDeviceApi - Retrofit service for GET /v5/devices/list (hmac-signed).
//   DeviceListEnvelope - code/message/data wrapper; data kept as raw JsonElement.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
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

interface YiCloudDeviceApi {
    @GET("v5/devices/list")
    suspend fun deviceList(
        @Query("seq") seq: String = "1",
        @Query("userid") userId: String,
        @Query("hmac") hmac: String,
    ): DeviceListEnvelope
}