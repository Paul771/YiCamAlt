// FILE: YiP2pAuthTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Known-answer tests for YiP2pAuth (RFC 2202 HMAC-SHA1, pinned hmacPassword, nonce, authInfo layout).
//   SCOPE: hmacSha1, hmacPassword, nonce, noncePrefix, authInfo encrypted and plain.
//   DEPENDS: M-STREAM-CORE (YiP2pAuth)
//   LINKS: V-M-STREAM-CORE
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.stream

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

// START_MODULE_MAP
//   hex - lowercase hex of a byte array.
//   YiP2pAuthTest - RFC 2202 vectors, pinned protocol vector, nonce/authInfo structure.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 02-04: wire-layer vectors. Inputs are synthetic (SYNTH...) or public (RFC 2202).
// END_CHANGE_SUMMARY

private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

class YiP2pAuthTest {

    @Test
    fun `hmacSha1 matches RFC 2202 case 1`() {
        val key = ByteArray(20) { 0x0b }
        assertEquals(
            "b617318655057264e28bc0b6fb378c8ef146be00",
            hex(YiP2pAuth.hmacSha1(key, "Hi There".toByteArray(Charsets.US_ASCII))),
        )
    }

    @Test
    fun `hmacSha1 matches RFC 2202 case 2`() {
        assertEquals(
            "effcdf6ae5eb2fa2d27416d5f184df9c259a7c79",
            hex(
                YiP2pAuth.hmacSha1(
                    "Jefe".toByteArray(Charsets.US_ASCII),
                    "what do ya want for nothing?".toByteArray(Charsets.US_ASCII),
                ),
            ),
        )
    }

    @Test
    fun `hmacPassword is the pinned protocol vector`() {
        // pinned with JDK HmacSHA1, not from a capture
        val pass = YiP2pAuth.hmacPassword("SYNTHpw1", "SYNTHAAbcdefgh1")
        assertEquals("C5KKYDqrtOxn15T", pass)
        assertEquals(15, pass.length)
        assertTrue(pass.all { it.isLetterOrDigit() || it == '+' || it == '/' })
    }

    @Test
    fun `nonce is deterministic for a seeded Random and uses the alphabet`() {
        val a = YiP2pAuth.nonce(8, Random(42))
        val b = YiP2pAuth.nonce(8, Random(42))
        assertEquals(8, a.length)
        assertEquals(a, b)
        assertTrue(a.all { it in YiP2pAuth.ALPHABET })
    }

    @Test
    fun `noncePrefix is 7 alphabet chars`() {
        val p = YiP2pAuth.noncePrefix(Random(7))
        assertEquals(7, p.length)
        assertTrue(p.all { it in YiP2pAuth.ALPHABET })
    }

    @Test
    fun `encrypted authInfo is nonce comma pass15 then one zero`() {
        val info = YiP2pAuth.authInfo(encrypted = true, password = "SYNTHpw1", noncePrefix7 = "SYNTHAB", rnd = Random(1))
        assertEquals(32, info.size)
        val nonce = String(info, 0, 15, Charsets.US_ASCII)
        assertTrue(nonce.startsWith("SYNTHAB"))
        assertTrue(nonce.all { it in YiP2pAuth.ALPHABET })
        assertEquals(','.code.toByte(), info[15])
        assertEquals(YiP2pAuth.hmacPassword("SYNTHpw1", nonce), String(info, 16, 15, Charsets.US_ASCII))
        assertEquals(0.toByte(), info[31])
        // the tail is the same as a fresh nonce(8) from the same seed
        assertEquals("SYNTHAB" + YiP2pAuth.nonce(8, Random(1)), nonce)
    }

    @Test
    fun `plain authInfo is admin comma password zero padded`() {
        val info = YiP2pAuth.authInfo(encrypted = false, password = "SYNTHpw1", noncePrefix7 = "SYNTHAB", rnd = Random(1))
        val expected = "admin,SYNTHpw1".toByteArray(Charsets.US_ASCII).copyOf(32)
        assertArrayEquals(expected, info)
    }

    @Test
    fun `plain authInfo truncates a long password to 32 bytes`() {
        val info = YiP2pAuth.authInfo(false, "SYNTH" + "x".repeat(40), "SYNTHAB", Random(1))
        assertEquals(32, info.size)
        assertTrue(info.none { it == 0.toByte() })
    }
}
