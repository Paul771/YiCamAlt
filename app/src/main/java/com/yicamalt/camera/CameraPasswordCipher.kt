// FILE: CameraPasswordCipher.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Recover the camera P2P password from the device-list `password` field (hex ciphertext).
//   SCOPE: AES in ECB mode with PKCS7 padding, key = first 16 chars of the cloud uid; an empty field means the
//     factory default password. Pure function: no I/O, no log output, no exception escapes.
//   DEPENDS: none
//   LINKS: M-P2P-CREDS, V-M-P2P-CREDS
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.camera

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

// START_MODULE_MAP
//   CameraPasswordCipher - decrypt(passwordHex, cloudUid): String? ; DEFAULT_PASSWORD.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 01-12 (CAM-03): created. Source is the field `password` of the owner device
//     list (plan addendum). ECB is the vendor wire format, not a choice; the result is a secret (SEC-04) and
//     must stay in memory or in P2pCredentialStore. hasPincode cameras need a separate call (not implemented).
// END_CHANGE_SUMMARY

internal object CameraPasswordCipher {

    const val DEFAULT_PASSWORD = "888888"

    private const val TRANSFORMATION = "AES/ECB/PKCS5Padding"
    private const val KEY_CHARS = 16

    // START_CONTRACT: decrypt
    //   PURPOSE: Turn the cloud-provided password field into the plain camera password.
    //   INPUTS: { passwordHex: String? - hex ciphertext, either case; null or blank means "not set";
    //     cloudUid: String? - device-list `uid`, the first 16 chars are the AES key }
    //   OUTPUTS: { String? - DEFAULT_PASSWORD for a null/blank field; the plain password; null when the uid is
    //     shorter than 16 chars, the hex is malformed or the ciphertext does not decrypt }
    //   SIDE_EFFECTS: none
    //   LINKS: M-P2P-CREDS, V-M-P2P-CREDS
    // END_CONTRACT: decrypt
    fun decrypt(passwordHex: String?, cloudUid: String?): String? {
        // START_BLOCK_DECRYPT_CAMERA_PASSWORD
        val hex = passwordHex?.trim().orEmpty()
        if (hex.isEmpty()) return DEFAULT_PASSWORD
        if (cloudUid == null || cloudUid.length < KEY_CHARS) return null
        return try {
            val key = cloudUid.substring(0, KEY_CHARS).toByteArray(Charsets.UTF_8)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
            String(cipher.doFinal(hexToBytes(hex)), Charsets.UTF_8)
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        // END_BLOCK_DECRYPT_CAMERA_PASSWORD
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd hex length" }
        return ByteArray(hex.length / 2) { i ->
            val hi = Character.digit(hex[2 * i], 16)
            val lo = Character.digit(hex[2 * i + 1], 16)
            require(hi >= 0 && lo >= 0) { "not hex" }
            ((hi shl 4) or lo).toByte()
        }
    }
}
