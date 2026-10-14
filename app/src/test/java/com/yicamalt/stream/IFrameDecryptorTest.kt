// FILE: IFrameDecryptorTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Executable contract for the vendor I-frame decrypt rule (criterion 5).
//   SCOPE: FIPS-197 AES-128 known answer through our cipher path, AES-128/192/256 round trips, fail-open gates.
//   DEPENDS: M-STREAM-CORE (IFrameDecryptor)
//   LINKS: V-M-STREAM-CORE
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.stream

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

// START_MODULE_MAP
//   hex(s) - hex string to bytes.
//   plainFrame() - 00 00 00 01 + bytes 0..31 + "tail".
//   vendorEncrypt(plain, password) - test-side encryption of [4,20) and [20,36) with key password + "0".
//   IFrameDecryptorTest - KAT, round trips (16/24/32-byte keys), short key, short payload.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 02-05: FIPS-197 C.1 and synthetic vectors.
// END_CHANGE_SUMMARY

class IFrameDecryptorTest {

    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun plainFrame(): ByteArray =
        byteArrayOf(0, 0, 0, 1) + ByteArray(32) { it.toByte() } + "tail".toByteArray(Charsets.US_ASCII)

    private fun vendorEncrypt(plain: ByteArray, password: String): ByteArray {
        val key = (password + "0").toByteArray(Charsets.US_ASCII)
        val c = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }
        val out = plain.copyOf()
        for (off in intArrayOf(4, 20)) System.arraycopy(c.doFinal(plain, off, 16), 0, out, off, 16)
        return out
    }

    private fun assertRoundTrip(password: String) {
        val plain = plainFrame()
        val enc = vendorEncrypt(plain, password)
        assertFalse(plain.contentEquals(enc))
        assertTrue(IFrameDecryptor.decryptInPlace(enc, password))
        assertArrayEquals(plain, enc)
    }

    @Test
    fun `FIPS-197 C1 AES-128 known answer through our path`() {
        val key = hex("000102030405060708090a0b0c0d0e0f")
        val ct = hex("69c4e0d86a7b0430d8cdb78070b4c55a")
        assertArrayEquals(hex("00112233445566778899aabbccddeeff"), IFrameDecryptor.aesEcbDecryptBlock(key, ct))
    }

    @Test
    fun `15 char password gives AES-128 and restores the frame with tail untouched`() {
        assertRoundTrip("SYNTH1234567890")
    }

    @Test
    fun `23 and 31 char passwords give AES-192 and AES-256`() {
        val pw24 = "SYNTH12345678901234567A"
        val pw32 = "SYNTH1234567890123456789012345A"
        assertTrue(pw24.length == 23 && pw32.length == 31)
        assertRoundTrip(pw24)
        assertRoundTrip(pw32)
    }

    @Test
    fun `blocks are decrypted independently`() {
        // Corrupting the first ciphertext block must not change the second plaintext block (ECB, not CBC).
        val plain = plainFrame()
        val enc = vendorEncrypt(plain, "SYNTH1234567890")
        enc[5] = (enc[5].toInt() xor 0x55).toByte()
        assertTrue(IFrameDecryptor.decryptInPlace(enc, "SYNTH1234567890"))
        assertArrayEquals(plain.copyOfRange(20, 40), enc.copyOfRange(20, 40))
        assertFalse(plain.copyOfRange(4, 20).contentEquals(enc.copyOfRange(4, 20)))
    }

    @Test
    fun `default 6 char password gives a 7 byte key and leaves bytes unchanged`() {
        val enc = vendorEncrypt(plainFrame(), "SYNTH1234567890")
        val before = enc.copyOf()
        assertFalse(IFrameDecryptor.decryptInPlace(enc, "888888"))
        assertArrayEquals(before, enc)
    }

    @Test
    fun `payload of 35 bytes is left unchanged`() {
        val short = ByteArray(35) { (it * 7).toByte() }
        val before = short.copyOf()
        assertFalse(IFrameDecryptor.decryptInPlace(short, "SYNTH1234567890"))
        assertArrayEquals(before, short)
    }
}
