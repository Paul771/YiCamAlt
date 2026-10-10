// FILE: YiCloudEventApi.kt
// VERSION: 0.1.2
// START_MODULE_CONTRACT
//   PURPOSE: Retrofit interface + envelope DTO for the Yi Cloud event timeline and playback endpoints.
//   SCOPE: eventList() and playbackUrl(); data is kept as raw JsonElement because the real
//     success envelope is unconfirmed (provisional until a live capture lands).
//   DEPENDS: M-HTTP (Retrofit)
//   LINKS: M-EVENT, docs/reference/yi-cloud-endpoints.md, tools/apk_endpoints.py
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
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.2 - EventSigner removed; event requests are signed by network/YiRequestSigner
//     (deferred-2 in verification-plan closed).
//   LAST_CHANGE: v0.1.1 - EventPaths re-pointed from `v1/camera/event/{list,playback}` to
//     `/v5/alert/events` + `/bs/v8/recording/{id}`, on static APK evidence (tools/apk_endpoints.py,
//     docs/reference/yi-cloud-endpoints.md). The old values were disproven: neither appears among
//     the 210 recovered endpoint constants. Older generations `/v4/alert/events` and
//     `/v2/alert/list` are retained as named constants so a capture only has to switch one.
//     Not capture-verified: parameters, signing, and the success envelope remain open.
//   LAST_CHANGE: v0.1.1 - Signing risk narrowed, not closed. `/v5/alert/events` sits in the same
//     `v5` family as the on-device-confirmed `/v5/devices/list`, so the HMAC-SHA1 scheme is
//     plausibly shared per-family. That is an inference, and stays flagged as such.
//   LAST_CHANGE: v0.1.0 - Created for Phase-3 M-EVENT.
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

/**
 * Paths kept as constants: a live capture should only require editing this object.
 *
 * Re-pointed 2026-10-06 from the `v1/camera/event/{list,playback}` guess to paths recovered from the official
 * APK by DEX string-pool scan (`tools/apk_endpoints.py`, evidence in
 * `docs/reference/yi-cloud-endpoints.md`).
 *
 * The old values were **disproven**, not merely unconfirmed: `/v1/camera/event/list` and
 * `/v1/camera/event/playback` appear nowhere in the 210 recovered endpoint constants.
 *
 * Provenance of the new values - static string evidence only, NOT capture-verified:
 *   - `/v5/alert/events` - richest event path in the APK; the `/v4` and `/v2` generations
 *     (`/v4/alert/events`, `/v2/alert/list`) are the older equivalents still shipped.
 *   - PLAYBACK has no literal counterpart in the APK: the recording URL is assembled at runtime,
 *     so string scanning cannot recover it. `/bs/v8/recording/{id}` is the closest static
 *     candidate and is kept ONLY so the seam is explicit - it is expected to change.
 */
object EventPaths {
    const val EVENT_LIST = "/v5/alert/events"

    /** Older generations kept visible until a capture settles which one the app actually calls. */
    const val EVENT_LIST_V4 = "/v4/alert/events"
    const val EVENT_LIST_V2 = "/v2/alert/list"

    /**
     * Provisional: no literal playback path exists in the APK (runtime-assembled).
     * Closest static candidate; must be re-pointed after a capture.
     */
    const val PLAYBACK = "/bs/v8/recording/{id}"
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
