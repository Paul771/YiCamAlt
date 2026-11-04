// FILE: AlertListApi.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Retrofit interface and envelope of the real alert feed GET v2/alert/list.
//   SCOPE: AlertEnvelope (code tolerant to number or string, data kept as raw JsonElement),
//     AlertCodeSerializer, AlertListApi.alertList (signed query in vendor order, no defaults).
//   DEPENDS: M-HTTP (Retrofit), M-EVENT (AlertWire)
//   LINKS: M-EVENT, V-M-EVENT, EVT-01, EVT-06
//   ROLE: DATA
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.event

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.http.GET
import retrofit2.http.Query

// START_MODULE_MAP
//   AlertEnvelope - code / message / data wrapper of v2/alert/list; data is the raw JsonElement.
//   AlertCodeSerializer - decodes code from a JSON string or number into its text form.
//   AlertListApi - Retrofit service: alertList(seq, userid, type, sub_type, from, to, limit, fromDB, expires, hmac).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 05-01: re-pointed to GET v2/alert/list per 05-RESEARCH §1 and 05-PROBE
//     (code 20000, data = array of objects). Replaces the guessed YiCloudEventApi (wrong v5 path).
//   HAZARD: no default values on alertList: the caller passes the AlertWire values that were signed, so the
//     signed and the sent query cannot drift. This is the ONLY alert path; destructive / push-settings
//     alert paths are forbidden and guarded by ForbiddenEndpointsTest.
// END_CHANGE_SUMMARY

// START_CONTRACT: AlertCodeSerializer
//   PURPOSE: Accept `code` as "20000" or 20000 (vendor optString semantics).
//   INPUTS: { Decoder - JSON decoder }
//   OUTPUTS: { String - primitive content, "" for null / non-primitive }
//   SIDE_EFFECTS: none
// END_CONTRACT: AlertCodeSerializer
internal object AlertCodeSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("AlertCode", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val json = decoder as? JsonDecoder ?: return decoder.decodeString()
        val element = json.decodeJsonElement()
        return if (element is JsonPrimitive && element !is JsonNull) element.content else ""
    }

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}

@Serializable
data class AlertEnvelope(
    @Serializable(with = AlertCodeSerializer::class)
    @SerialName("code") val code: String = "",
    @SerialName("message") val message: String? = null,
    @SerialName("data") val data: JsonElement? = null,
)

interface AlertListApi {
    // START_CONTRACT: alertList
    //   PURPOSE: One page of the alert feed. hmac is over
    //     "seq=0&userid=U&type=&sub_type=&from=F&to=T&limit=L&fromDB=true&expires=10" (AlertWire.signedExtra).
    //   INPUTS: { all query values exactly as signed; fromSec / toSec epoch seconds }
    //   OUTPUTS: { AlertEnvelope - code "20000" on success, data = array of alert objects }
    //   SIDE_EFFECTS: network GET through the shared Yi Retrofit (relative path, region base URL)
    //   LINKS: AlertWire.LIST_PATH, AlertWire.signedExtra, AlertMapper.page
    // END_CONTRACT: alertList
    @GET(AlertWire.LIST_PATH)
    suspend fun alertList(
        @Query("seq") seq: String,
        @Query("userid") userId: String,
        @Query("type") type: String,
        @Query("sub_type") subType: String,
        @Query("from") fromSec: Long,
        @Query("to") toSec: Long,
        @Query("limit") limit: Int,
        @Query("fromDB") fromDb: String,
        @Query("expires") expires: String,
        @Query("hmac") hmac: String,
    ): AlertEnvelope
}
