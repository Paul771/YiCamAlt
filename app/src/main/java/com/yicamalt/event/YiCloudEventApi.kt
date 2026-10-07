// FILE: YiCloudEventApi.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Retrofit interface + envelope DTO for the Yi Cloud event timeline and playback endpoints.
//   SCOPE: eventList() and playbackUrl(); data is kept as raw JsonElement because the real
//     success envelope is unconfirmed (provisional until a live capture lands).
//   DEPENDS: M-HTTP (Retrofit)
//   LINKS: M-EVENT
//   ROLE: DATA
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.event

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import retrofit2.http.GET
import retrofit2.http.Query

// START_MODULE_MAP
//   YiCloudEventApi - Retrofit service for the event timeline + playback URL endpoints.
//   EventListEnvelope - code/message/data wrapper; data kept as raw JsonElement.
//   EventPaths - path constants isolated so a live capture edits one place only.
//   EventSigner - provisional hmac request signing, mirroring the confirmed device-API scheme.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - Created for Phase-3 M-EVENT. PATHS ARE PROVISIONAL: development-plan
//     M-EVENT note-1 records GET /v1/camera/event/list, but the reverse-engineering work in
//     docs/PROJECT_STATUS.md showed real Yi Cloud paths are versioned without a /v1 prefix.
//     The value is therefore a guess and must be re-pointed after a capture.
//   LAST_CHANGE: v0.1.0 - EventSigner duplicates the HmacSHA1 scheme confirmed on-device for
//     /v5/devices/list (M-CAMERA-LIST) rather than depending on CameraRegistry. This is deliberate
//     debt: it keeps M-EVENT free of a M-CAMERA-LIST dependency, and both copies should collapse
//     into M-HTTP once the signing scheme is confirmed for the event API by a real capture.
// END_CHANGE_SUMMARY

@Serializable
data class EventListEnvelope(
    @SerialName("code") val code: String = "",
    @SerialName("message") val message: String? = null,
    @SerialName("data") val data: JsonElement? = null,
)

/** Paths kept as constants: a live capture should only require editing this object. */
object EventPaths {
    const val EVENT_LIST = "v1/camera/event/list"
    const val PLAYBACK = "v1/camera/event/playback"
}

interface YiCloudEventApi {

    @GET(EventPaths.EVENT_LIST)
    suspend fun eventList(
        @Query("seq") seq: String = "1",
        @Query("userid") userId: String,
        @Query("hmac") hmac: String,
        @Query("device_id") deviceId: String,
        @Query("start_time") startTime: Long,
        @Query("end_time") endTime: Long,
        @Query("page") page: Int,
        @Query("page_size") pageSize: Int,
    ): EventListEnvelope

    @GET(EventPaths.PLAYBACK)
    suspend fun playbackUrl(
        @Query("seq") seq: String = "1",
        @Query("userid") userId: String,
        @Query("hmac") hmac: String,
        @Query("event_id") eventId: String,
    ): EventListEnvelope
}

/**
 * Provisional request signing for the event API.
 *
 * Mirrors the scheme confirmed on-device for the device API — Base64(HMAC-SHA1) keyed on
 * "<token>&<token_secret>" over the canonical parameter string. Unconfirmed for events.
 */
internal object EventSigner {
    fun hmacSha1Base64(key: String, msg: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA1")
        mac.init(javax.crypto.spec.SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        return java.util.Base64.getEncoder()
            .encodeToString(mac.doFinal(msg.toByteArray(Charsets.UTF_8)))
    }

    /** Canonical signed string for a signed request; kept in one place so tests can recompute it. */
    fun canonicalMessage(userId: String, seq: String = "1"): String = "seq=$seq&userid=$userId"
}
