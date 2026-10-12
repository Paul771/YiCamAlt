// FILE: CameraPasswordCipherTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Pin camera-password recovery: default for an empty value, key = first 16 chars of the cloud uid, null on any failure.
//   SCOPE: CameraPasswordCipher.decrypt with synthetic vectors only (SEC-04); no real camera value is ever used here.
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
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

// START_MODULE_MAP
//   CameraPasswordCipherTest - default password, round trip, pinned vector, failure paths.
//   encryptHex - test-side AES/ECB encryptor producing lowercase hex.
//   PINNED_HEX / WRONG_KEY_HEX - synthetic ciphertexts computed once outside the code under test.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 01-12: first evidence for V-M-P2P-CREDS (password cipher).
// END_CHANGE_SUMMARY

class CameraPasswordCipherTest {

    private val uid = RedactionFixtures.CLOUD_UID

    private fun encryptHex(plain: String, key: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"))
        return cipher.doFinal(plain.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `empty password yields the factory default`() {
        assertEquals("888888", CameraPasswordCipher.decrypt(null, uid))
        assertEquals("888888", CameraPasswordCipher.decrypt("", uid))
        assertEquals("888888", CameraPasswordCipher.decrypt("  ", uid))
        assertEquals("888888", CameraPasswordCipher.DEFAULT_PASSWORD)
    }

    // START_BLOCK_ROUND_TRIP
    @Test
    fun `round trip uses only the first 16 chars of the cloud uid`() {
        val hex = encryptHex(RedactionFixtures.PASSWORD_PLAIN, uid)
        assertEquals(
            RedactionFixtures.PASSWORD_PLAIN,
            CameraPasswordCipher.decrypt(hex, "$uid-extra-chars"),
        )
    }

    @Test
    fun `uppercase hex and surrounding blanks are accepted`() {
        val hex = encryptHex(RedactionFixtures.PASSWORD_PLAIN, uid)
        assertEquals(RedactionFixtures.PASSWORD_PLAIN, CameraPasswordCipher.decrypt(hex.uppercase(), uid))
        assertEquals(RedactionFixtures.PASSWORD_PLAIN, CameraPasswordCipher.decrypt(" $hex ", uid))
    }

    @Test
    fun `pinned vector guards key derivation and padding`() {
        assertEquals(PINNED_HEX, encryptHex(RedactionFixtures.PASSWORD_PLAIN, uid))
        assertEquals(RedactionFixtures.PASSWORD_PLAIN, CameraPasswordCipher.decrypt(PINNED_HEX, uid))
    }
    // END_BLOCK_ROUND_TRIP

    // START_BLOCK_FAILURE_PATHS
    @Test
    fun `malformed hex yields null`() {
        assertNull(CameraPasswordCipher.decrypt("zz", uid))
        assertNull(CameraPasswordCipher.decrypt("abc", uid))
        // 15 bytes: valid hex, not a whole AES block
        assertNull(CameraPasswordCipher.decrypt(PINNED_HEX.dropLast(2), uid))
    }

    @Test
    fun `missing or short uid yields null`() {
        assertNull(CameraPasswordCipher.decrypt(PINNED_HEX, "short"))
        assertNull(CameraPasswordCipher.decrypt(PINNED_HEX, null))
    }

    @Test
    fun `ciphertext made with another key yields null`() {
        assertNull(CameraPasswordCipher.decrypt(WRONG_KEY_HEX, uid))
    }
    // END_BLOCK_FAILURE_PATHS

    private companion object {
        // SYNTH vector: AES-128/ECB/PKCS7 of "synthpw1" under the key "SYNTHUID00000001" (computed with openssl).
        const val PINNED_HEX = "4a0856af0885dcff0c4dbc3820c21b4d" // SYNTH vector
        // SYNTH vector: the same plaintext under the key "SYNTHUID00000002".
        const val WRONG_KEY_HEX = "25bf85a96d26e1cc05d81af3f4806210" // SYNTH vector
    }
}
