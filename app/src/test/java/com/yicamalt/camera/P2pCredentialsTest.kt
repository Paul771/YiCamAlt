// FILE: P2pCredentialsTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Pin transport resolution (type vs p2pid shape) and the secret-free toString() of P2pCredentials.
//   SCOPE: TransportResolver.resolve, TransportType.fromStorage, P2pCredentials.toString (SEC-04). Synthetic values only.
//   DEPENDS: M-P2P-CREDS, TestInfrastructure (RedactionFixtures)
//   LINKS: V-M-P2P-CREDS, M-P2P-CREDS
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.camera

import com.yicamalt.test.RedactionFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// START_MODULE_MAP
//   P2pCredentialsTest - resolver matrix, storage-name parsing, toString redaction.
//   sample - synthetic P2pCredentials with every secret field set.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 01-12: first evidence for V-M-P2P-CREDS (transport + redaction).
// END_CHANGE_SUMMARY

class P2pCredentialsTest {

    private fun sample() = P2pCredentials(
        deviceId = RedactionFixtures.CLOUD_UID,
        p2pId = RedactionFixtures.DID,
        transport = TransportType.TNP,
        password = RedactionFixtures.PASSWORD_PLAIN,
        initString = RedactionFixtures.INIT_STRING,
        licenseKey = RedactionFixtures.LICENSE,
        p2pEncrypt = true,
        wakeup = false,
        needsPincode = false,
    )

    // START_BLOCK_RESOLVER_MATRIX
    @Test
    fun `type 2 with TNP shaped id resolves to TNP without mismatch`() {
        assertEquals(
            TransportResolver.Resolution(TransportType.TNP, false),
            TransportResolver.resolve(2, RedactionFixtures.DID),
        )
    }

    @Test
    fun `type 0 with TUTK shaped id resolves to TUTK without mismatch`() {
        assertEquals(
            TransportResolver.Resolution(TransportType.TUTK, false),
            TransportResolver.resolve(0, RedactionFixtures.TUTK_UID),
        )
    }

    @Test
    fun `type 1 resolves to LANGTAO`() {
        assertEquals(
            TransportResolver.Resolution(TransportType.LANGTAO, false),
            TransportResolver.resolve(1, null),
        )
    }

    @Test
    fun `missing type falls back to the id shape`() {
        assertEquals(
            TransportResolver.Resolution(TransportType.TNP, false),
            TransportResolver.resolve(null, RedactionFixtures.DID),
        )
        assertEquals(
            TransportResolver.Resolution(TransportType.UNKNOWN, false),
            TransportResolver.resolve(null, "whatever"),
        )
    }

    @Test
    fun `unknown type code resolves to UNKNOWN`() {
        assertEquals(
            TransportResolver.Resolution(TransportType.UNKNOWN, false),
            TransportResolver.resolve(7, null),
        )
    }

    @Test
    fun `type wins over the id shape and the conflict is flagged`() {
        assertEquals(
            TransportResolver.Resolution(TransportType.TUTK, true),
            TransportResolver.resolve(0, RedactionFixtures.DID),
        )
        assertEquals(
            TransportResolver.Resolution(TransportType.TNP, true),
            TransportResolver.resolve(2, RedactionFixtures.TUTK_UID),
        )
    }

    @Test
    fun `blank id never raises a mismatch`() {
        assertFalse(TransportResolver.resolve(2, null).mismatch)
        assertFalse(TransportResolver.resolve(2, "  ").mismatch)
        assertFalse(TransportResolver.resolve(0, "").mismatch)
    }
    // END_BLOCK_RESOLVER_MATRIX

    @Test
    fun `fromStorage maps names and tolerates garbage`() {
        assertEquals(TransportType.TNP, TransportType.fromStorage("TNP"))
        assertEquals(TransportType.TUTK, TransportType.fromStorage("TUTK"))
        assertEquals(TransportType.LANGTAO, TransportType.fromStorage("LANGTAO"))
        assertEquals(TransportType.UNKNOWN, TransportType.fromStorage(null))
        assertEquals(TransportType.UNKNOWN, TransportType.fromStorage("garbage"))
    }

    // START_BLOCK_TOSTRING_REDACTION
    @Test
    fun `toString prints flags only and no secret value`() {
        val text = sample().toString()
        listOf(
            RedactionFixtures.CLOUD_UID,
            RedactionFixtures.DID,
            RedactionFixtures.PASSWORD_PLAIN,
            RedactionFixtures.INIT_STRING,
            RedactionFixtures.LICENSE,
            RedactionFixtures.LICENSE.substringBefore(':'),
        ).forEach { secret ->
            assertFalse(text.contains(secret), "toString leaked a secret field")
        }
        assertTrue(text.contains("transport=TNP"))
        assertTrue(text.contains("hasPassword=true"))
    }

    @Test
    fun `toString reports absent optional secrets`() {
        val text = sample().copy(password = null, initString = null, licenseKey = null).toString()
        assertTrue(text.contains("hasPassword=false"))
        assertTrue(text.contains("hasInitString=false"))
        assertTrue(text.contains("hasLicense=false"))
    }
    // END_BLOCK_TOSTRING_REDACTION
}
