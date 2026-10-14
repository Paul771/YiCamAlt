// FILE: IFrameDecryptor.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Decrypt a P2P I-frame payload in place exactly like the vendor client (two independent AES-ECB blocks).
//   SCOPE: key derivation (password + "0", US-ASCII), key length gate 16/24/32, blocks [4,20) and [20,36), AES primitive.
//   DEPENDS: JDK javax.crypto
//   LINKS: M-STREAM-CORE, V-M-STREAM-CORE
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.stream

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

// START_MODULE_MAP
//   IFrameDecryptor - object: decryptInPlace(payload, password) -> Boolean; internal aesEcbDecryptBlock(key, block).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 02-05 from 02-RESEARCH.md Pattern 6 (VERIFIED-LOCAL + PUBLIC).
//     INVARIANT: fail-open like the vendor: a key of the wrong length or a short payload leaves the bytes untouched.
//     INVARIANT: the password never appears in a log line, toString or exception message (this object logs nothing).
// END_CHANGE_SUMMARY

object IFrameDecryptor {
    const val MIN_PAYLOAD = 36
    private const val BLOCK = 16
    private val BLOCK_OFFSETS = intArrayOf(4, 20)
    private const val TRANSFORMATION = "AES/ECB/NoPadding"

    // START_CONTRACT: decryptInPlace
    //   PURPOSE: Decrypt payload[4,20) and payload[20,36) independently with key = ASCII(password + "0").
    //   INPUTS: { payload: ByteArray - I-frame payload after the 24-byte head, password: String - camera password }
    //   OUTPUTS: { Boolean - true if both blocks were decrypted; false if the payload is shorter than 36 bytes
    //     or the key length is not 16/24/32 (payload unchanged) }
    //   SIDE_EFFECTS: rewrites payload bytes 4..35 in place
    //   LINKS: M-STREAM-CORE, FramePipeline.onVideoUnit
    // END_CONTRACT: decryptInPlace
    fun decryptInPlace(payload: ByteArray, password: String): Boolean {
        // START_BLOCK_DECRYPT_IFRAME
        if (payload.size < MIN_PAYLOAD) return false
        val key = (password + "0").toByteArray(Charsets.US_ASCII)
        if (key.size != 16 && key.size != 24 && key.size != 32) return false
        val cipher = decryptCipher(key)
        for (off in BLOCK_OFFSETS) {
            val plain = cipher.doFinal(payload, off, BLOCK)
            System.arraycopy(plain, 0, payload, off, BLOCK)
        }
        key.fill(0)
        return true
        // END_BLOCK_DECRYPT_IFRAME
    }

    // START_CONTRACT: aesEcbDecryptBlock
    //   PURPOSE: One raw AES-ECB block decrypt through the same cipher path (for known-answer tests).
    //   INPUTS: { key: ByteArray - 16/24/32 bytes, block: ByteArray - 16 bytes }
    //   OUTPUTS: { ByteArray - 16 plaintext bytes }
    //   SIDE_EFFECTS: none
    //   LINKS: V-M-STREAM-CORE
    // END_CONTRACT: aesEcbDecryptBlock
    internal fun aesEcbDecryptBlock(key: ByteArray, block: ByteArray): ByteArray {
        require(block.size == BLOCK) { "AES block must be $BLOCK bytes" }
        return decryptCipher(key).doFinal(block)
    }

    private fun decryptCipher(key: ByteArray): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES")) }
}
