// FILE: YiRequestSignerTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Pin YiRequestSigner to a published HMAC-SHA1 vector and the confirmed Yi canonical string.
//   SCOPE: hmacSha1Base64 vector, canonicalMessage (plain + extra params), signingKey, sign.
//   DEPENDS: M-HTTP (YiRequestSigner)
//   LINKS: V-M-HTTP, M-CAMERA-LIST, M-EVENT
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.network

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// START_MODULE_MAP
//   YiRequestSignerTest - deterministic signer pins; referenceHmac recomputes with javax.crypto.
// END_MODULE_MAP

class YiRequestSignerTest {

    private fun referenceHmac(key: String, msg: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA1")
        mac.init(javax.crypto.spec.SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        return java.util.Base64.getEncoder().encodeToString(mac.doFinal(msg.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun hmac_matches_published_sha1_vector() {
        // Published vector: hex de7c9b85b8b78aa6bc8a7a36f70a90701c9db4d9, Base64 below.
        assertEquals(
            "3nybhbi3iqa8ino29wqQcBydtNk=",
            YiRequestSigner.hmacSha1Base64("key", "The quick brown fox jumps over the lazy dog"),
        )
    }

    @Test
    fun canonical_message_plain() {
        assertEquals("seq=1&userid=100042", YiRequestSigner.canonicalMessage("100042"))
    }

    @Test
    fun canonical_message_with_extra_params_for_device_info() {
        assertEquals(
            "seq=1&userid=100042&uid=SYNTHUID00000001",
            YiRequestSigner.canonicalMessage("100042", extra = listOf("uid" to "SYNTHUID00000001")),
        )
    }

    @Test
    fun signing_key_joins_token_and_secret() {
        assertEquals("t&s", YiRequestSigner.signingKey("t", "s"))
    }

    @Test
    fun sign_matches_confirmed_device_list_wire_format() {
        assertEquals(
            referenceHmac("t-1&s-1", "seq=1&userid=u-1"),
            YiRequestSigner.sign(token = "t-1", tokenSecret = "s-1", userId = "u-1"),
        )
    }

    @Test
    fun sign_with_extra_covers_extended_canonical_string() {
        assertEquals(
            referenceHmac("t-1&s-1", "seq=1&userid=u-1&uid=SYNTHUID00000001"),
            YiRequestSigner.sign("t-1", "s-1", "u-1", extra = listOf("uid" to "SYNTHUID00000001")),
        )
    }
}
