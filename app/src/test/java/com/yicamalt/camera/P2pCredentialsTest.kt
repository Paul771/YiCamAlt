// FILE: P2pCredentialsTest.kt
// VERSION: 0.2.0
// START_MODULE_CONTRACT
//   PURPOSE: Pin the vendor transport rule (device_info DID shape, else uid case) and the secret-free toString() of P2pCredentials.
//   SCOPE: TransportResolver.decide / isTnpDid, TransportType.fromStorage, P2pCredentials.toString (SEC-04). Synthetic values only.
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
//   LAST_CHANGE: v0.2.0 - GSD plan 02-01: resolver cases rewritten to the vendor rule (decide / isTnpDid);
//     mismatch tests dropped with the flag; verified= in toString.
//   PREVIOUS: v0.1.0 - GSD plan 01-12: first evidence for V-M-P2P-CREDS (transport + redaction).
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
    fun `isTnpDid accepts TNP prefix with exactly three parts`() {
        assertTrue(TransportResolver.isTnpDid("TNPXSYN-123456-SYNTH"))
        assertTrue(TransportResolver.isTnpDid("TNPSYNTH-ABCDEF-SYNTH"))
    }

    @Test
    fun `isTnpDid rejects wrong part count, lower-case prefix and empty input`() {
        assertFalse(TransportResolver.isTnpDid("TNP-123"))
        assertFalse(TransportResolver.isTnpDid("TNPA-1-2-3"))
        assertFalse(TransportResolver.isTnpDid("tnpxsyn-123456-synth"))
        assertFalse(TransportResolver.isTnpDid(null))
        assertFalse(TransportResolver.isTnpDid(""))
    }

    @Test
    fun `TNP shaped device_info DID decides TNP and is the id to dial`() {
        assertEquals(
            TransportResolver.Resolution(
                TransportType.TNP, RedactionFixtures.DID, TransportResolver.Source.DEVICE_INFO,
            ),
            TransportResolver.decide(RedactionFixtures.DID, RedactionFixtures.CLOUD_UID),
        )
    }

    @Test
    fun `no DID falls back to the cloud uid shape - mixed case is TUTK`() {
        assertEquals(
            TransportResolver.Resolution(
                TransportType.TUTK, RedactionFixtures.TUTK_UID, TransportResolver.Source.UID_SHAPE,
            ),
            TransportResolver.decide(null, RedactionFixtures.TUTK_UID),
        )
        assertEquals(TransportType.TUTK, TransportResolver.decide(null, "SynthMixedUid01").transport)
    }

    @Test
    fun `non TNP DID is ignored and the uid decides`() {
        assertEquals(
            TransportResolver.Resolution(
                TransportType.TUTK, RedactionFixtures.TUTK_UID, TransportResolver.Source.UID_SHAPE,
            ),
            TransportResolver.decide("NOTATNPID", RedactionFixtures.TUTK_UID),
        )
    }

    @Test
    fun `all lower-case uid is LANGTAO`() {
        assertEquals(
            TransportResolver.Resolution(
                TransportType.LANGTAO, "synthlangtao0001", TransportResolver.Source.UID_SHAPE,
            ),
            TransportResolver.decide(null, "synthlangtao0001"),
        )
    }

    @Test
    fun `Resolution toString hides the id`() {
        val text = TransportResolver.Resolution(
            TransportType.TNP, RedactionFixtures.DID, TransportResolver.Source.DEVICE_INFO,
        ).toString()
        assertFalse(text.contains(RedactionFixtures.DID))
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
        assertTrue(text.contains("verified=false"))
    }

    @Test
    fun `transportVerified defaults to false and shows in toString`() {
        assertFalse(sample().transportVerified)
        assertTrue(sample().copy(transportVerified = true).toString().contains("verified=true"))
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
