// FILE: YiRequestSigner.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Single Yi Cloud request signer: Base64(HMAC-SHA1(token&token_secret, canonical params)).
//   SCOPE: signingKey, canonicalMessage (seq + userid + optional extra params in order),
//     hmacSha1Base64, sign. Pure functions; no I/O, no logging.
//   DEPENDS: none
//   LINKS: M-HTTP, M-CAMERA-LIST, M-EVENT, V-M-HTTP
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.network

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// START_MODULE_MAP
//   YiRequestSigner - the one HMAC-SHA1 signer used by the device list and the event API.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - Collapses CameraRegistry/EventSigner/debug sign-probe copies; adds extra
//     params for /v4/tnp/device_info (seq=1&userid=<uid>&uid=<cloud uid>).
//   HAZARD: takes plain strings, not AuthSession, so M-HTTP stays independent of M-AUTH.
//     Never log keys, messages or signatures from here.
// END_CHANGE_SUMMARY

internal object YiRequestSigner {

    // START_CONTRACT: signingKey
    //   PURPOSE: Build the HMAC key "<token>&<token_secret>".
    //   INPUTS: { token: String, tokenSecret: String }
    //   OUTPUTS: { String - joined key }
    //   SIDE_EFFECTS: none
    // END_CONTRACT: signingKey
    fun signingKey(token: String, tokenSecret: String): String = "$token&$tokenSecret"

    // START_CONTRACT: canonicalMessage
    //   PURPOSE: Build the signed parameter string "seq=<seq>&userid=<uid>[&k=v...]".
    //   INPUTS: { userId: String, seq: String, extra: List<Pair<String,String>> - appended in order }
    //   OUTPUTS: { String - canonical message }
    //   SIDE_EFFECTS: none
    // END_CONTRACT: canonicalMessage
    fun canonicalMessage(
        userId: String,
        seq: String = "1",
        extra: List<Pair<String, String>> = emptyList(),
    ): String = buildString {
        append("seq=").append(seq).append("&userid=").append(userId)
        extra.forEach { (k, v) -> append('&').append(k).append('=').append(v) }
    }

    // START_CONTRACT: hmacSha1Base64
    //   PURPOSE: Base64(HMAC-SHA1(key, msg)), UTF-8, no line wrap.
    //   INPUTS: { key: String, msg: String }
    //   OUTPUTS: { String - Base64 signature }
    //   SIDE_EFFECTS: none
    // END_CONTRACT: hmacSha1Base64
    fun hmacSha1Base64(key: String, msg: String): String {
        // START_BLOCK_SIGN_REQUEST
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        return java.util.Base64.getEncoder().encodeToString(mac.doFinal(msg.toByteArray(Charsets.UTF_8)))
        // END_BLOCK_SIGN_REQUEST
    }

    // START_CONTRACT: sign
    //   PURPOSE: Compute the hmac query param for a signed Yi Cloud request.
    //   INPUTS: { token, tokenSecret, userId: String, extra: List<Pair<String,String>>, seq: String }
    //   OUTPUTS: { String - Base64 HMAC-SHA1 signature }
    //   SIDE_EFFECTS: none
    //   LINKS: M-CAMERA-LIST, M-EVENT
    // END_CONTRACT: sign
    fun sign(
        token: String,
        tokenSecret: String,
        userId: String,
        extra: List<Pair<String, String>> = emptyList(),
        seq: String = "1",
    ): String = hmacSha1Base64(signingKey(token, tokenSecret), canonicalMessage(userId, seq, extra))
}
