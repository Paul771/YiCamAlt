// FILE: P2pCredentialStoreTest.kt
// VERSION: 0.2.0
// START_MODULE_CONTRACT
//   PURPOSE: Pin the storage codec of P2P credentials and the replace-all semantics of the store contract.
//   SCOPE: P2pCredentialsCodec round trip + rejects; InMemoryP2pCredentialStore get/replaceAll/clear.
//     EncryptedP2pCredentialStore needs the Android Keystore and is verified on the phone (plan 01-11).
//   DEPENDS: M-P2P-CREDS, TestInfrastructure (RedactionFixtures)
//   LINKS: V-M-P2P-CREDS, M-P2P-CREDS
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.camera

import com.yicamalt.test.RedactionFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

// START_MODULE_MAP
//   P2pCredentialStoreTest - codec round trip / rejects, in-memory store semantics.
//   full / bare - synthetic credentials with all fields set / with the nullable fields null.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.2.0 - GSD plan 02-01: transportVerified round trip; v0.1.0 JSON decodes as unverified.
//   PREVIOUS: v0.1.0 - GSD plan 01-12 (SEC-04): created.
// END_CHANGE_SUMMARY

class P2pCredentialStoreTest {

    private val full = P2pCredentials(
        deviceId = RedactionFixtures.CLOUD_UID,
        p2pId = RedactionFixtures.DID,
        transport = TransportType.TNP,
        password = RedactionFixtures.PASSWORD_PLAIN,
        initString = RedactionFixtures.INIT_STRING,
        licenseKey = RedactionFixtures.LICENSE,
        p2pEncrypt = true,
        wakeup = true,
        needsPincode = true,
    )

    private val bare = P2pCredentials(
        deviceId = "SYNTHUID00000002",
        p2pId = RedactionFixtures.TUTK_UID,
        transport = TransportType.TUTK,
        password = null,
        initString = null,
        licenseKey = null,
        p2pEncrypt = false,
        wakeup = false,
        needsPincode = false,
    )

    // START_BLOCK_CODEC
    @Test
    fun `codec round trips credentials with every field set`() {
        assertEquals(full, P2pCredentialsCodec.decode(P2pCredentialsCodec.encode(full)))
    }

    @Test
    fun `codec round trips credentials with null optional fields`() {
        assertEquals(bare, P2pCredentialsCodec.decode(P2pCredentialsCodec.encode(bare)))
    }

    @Test
    fun `codec keeps every transport by name`() {
        TransportType.entries.forEach { t ->
            val c = bare.copy(transport = t)
            assertEquals(c, P2pCredentialsCodec.decode(P2pCredentialsCodec.encode(c)))
        }
    }

    @Test
    fun `codec keeps transportVerified true`() {
        val c = full.copy(transportVerified = true)
        assertEquals(c, P2pCredentialsCodec.decode(P2pCredentialsCodec.encode(c)))
    }

    @Test
    fun `v0_1_0 record without transportVerified decodes as unverified`() {
        val legacy = """{"deviceId":"SYNTHUID00000002","p2pId":"SYNTHTUTKUID0000001A","transport":"TUTK",""" +
            """"password":"synthpw1","p2pEncrypt":false,"wakeup":false,"needsPincode":false}"""
        val decoded = P2pCredentialsCodec.decode(legacy)
        assertEquals(TransportType.TUTK, decoded?.transport)
        assertEquals(false, decoded?.transportVerified)
    }

    @Test
    fun `codec rejects garbage and incomplete records`() {
        assertNull(P2pCredentialsCodec.decode("not json"))
        assertNull(P2pCredentialsCodec.decode("{}"))
        assertNull(P2pCredentialsCodec.decode(""))
        assertNull(P2pCredentialsCodec.decode("[]"))
    }
    // END_BLOCK_CODEC

    // START_BLOCK_IN_MEMORY_STORE
    @Test
    fun `replaceAll makes both records readable`() {
        val store = InMemoryP2pCredentialStore()
        store.replaceAll(listOf(full, bare))
        assertEquals(full, store.get(full.deviceId))
        assertEquals(bare, store.get(bare.deviceId))
    }

    @Test
    fun `second replaceAll drops records that are not in the new list`() {
        val store = InMemoryP2pCredentialStore()
        store.replaceAll(listOf(full, bare))
        store.replaceAll(listOf(bare))
        assertNull(store.get(full.deviceId))
        assertEquals(bare, store.get(bare.deviceId))
    }

    @Test
    fun `clear empties the store`() {
        val store = InMemoryP2pCredentialStore()
        store.replaceAll(listOf(full, bare))
        store.clear()
        assertNull(store.get(full.deviceId))
        assertNull(store.get(bare.deviceId))
    }

    @Test
    fun `unknown device id yields null`() {
        assertNull(InMemoryP2pCredentialStore().get("SYNTHUID0000MISS"))
    }
    // END_BLOCK_IN_MEMORY_STORE
}
