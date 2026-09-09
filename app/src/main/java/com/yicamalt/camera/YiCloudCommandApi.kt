// FILE: YiCloudCommandApi.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Retrofit interface for the provisional cloud camera-control endpoint.
//   SCOPE: control(payload) -> envelope; code "20000" is treated as acknowledgement.
//   DEPENDS: M-HTTP (Retrofit)
//   LINKS: M-CAMERA-CMD
//   ROLE: DATA
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.camera

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.POST

// START_MODULE_MAP
//   YiCloudCommandApi - provisional /v8/device/control transport. Path unconfirmed; stop on capture.
// END_MODULE_MAP

@Serializable
data class CommandEnvelope(
    @SerialName("code") val code: String = "",
    @SerialName("message") val message: String? = null,
    @SerialName("data") val data: JsonElement? = null,
)

interface YiCloudCommandApi {
    @POST("v8/device/control")
    suspend fun control(@Body payload: CommandPayload): CommandEnvelope
}