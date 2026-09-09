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

// START_MODULE_MAP
//   YiCloudDeviceApi - Retrofit service for /v8/cloud/deviceList.
//   DeviceListEnvelope - code/message/data wrapper; data kept as raw JsonElement.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - Created for Phase-2 M-CAMERA-LIST. Path /v8/cloud/deviceList from
//     dex reverse engineering (PROJECT_STATUS 8.5); body shape provisional pending live capture.
// END_CHANGE_SUMMARY

@Serializable
data class DeviceListEnvelope(
    @SerialName("code") val code: String = "",
    @SerialName("message") val message: String? = null,
    @SerialName("data") val data: JsonElement? = null,
)

interface YiCloudDeviceApi {
    @GET("v8/cloud/deviceList")
    suspend fun deviceList(): DeviceListEnvelope
}