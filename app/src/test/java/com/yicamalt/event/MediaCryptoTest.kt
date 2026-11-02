// FILE: MediaCryptoTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Pin the alert media decrypt (u32 LE length + AES-128/ECB/NoPadding), magic and codec classes and
//     the tar preview pick against synthetic data encrypted in the test with a dummy key.
//   SCOPE: round trip + truncation, 16-char key rule, null on malformed input, vendor length tolerance,
//     magic classes, codec classes, tar entry selection and truncated tar.
//   DEPENDS: M-EVENT-PLAYBACK (MediaCrypto)
//   LINKS: V-M-EVENT-PLAYBACK, EVT-03
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.event

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

// START_MODULE_MAP
//   MediaCryptoTest - decrypt, magic, codec and tarPreview scenarios.
//   encryptBody - builds u32 LE length + AES/ECB/NoPadding ciphertext with zero padding.
//   tarEntry / tarOf - minimal ustar builder for the preview pick.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 05-00: initial MediaCrypto scenarios (test-first).
// END_CHANGE_SUMMARY

class MediaCryptoTest {

    private val key16 = "abcdefghijklmnop"

    private fun encryptBody(plain: ByteArray, key: String, declaredLen: Int = plain.size): ByteArray {
        val padded = plain.copyOf(((plain.size + 15) / 16) * 16)
        val c = Cipher.getInstance("AES/ECB/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.US_ASCII), "AES"))
        val ct = c.doFinal(padded)
        val prefix = byteArrayOf(
            (declaredLen and 0xFF).toByte(), ((declaredLen shr 8) and 0xFF).toByte(),
            ((declaredLen shr 16) and 0xFF).toByte(), ((declaredLen shr 24) and 0xFF).toByte(),
        )
        return prefix + ct
    }

    private fun tarEntry(name: String, data: ByteArray): ByteArray {
        val header = ByteArray(512)
        name.toByteArray(Charsets.US_ASCII).copyInto(header, 0)
        val size = Integer.toOctalString(data.size).padStart(11, '0')
        size.toByteArray(Charsets.US_ASCII).copyInto(header, 124)
        "ustar".toByteArray(Charsets.US_ASCII).copyInto(header, 257)
        val padded = data.copyOf(((data.size + 511) / 512) * 512)
        return header + padded
    }

    private fun tarOf(vararg entries: ByteArray): ByteArray = entries.fold(ByteArray(0)) { a, b -> a + b } + ByteArray(1024)

    @Test
    fun `decrypt round trip truncates to the declared length`() {
        val plain = ByteArray(37) { (it * 7 + 3).toByte() }
        val body = encryptBody(plain, key16)
        assertEquals(52, body.size)
        assertArrayEquals(plain, MediaCrypto.decrypt(body, key16))
    }

    @Test
    fun `password longer than 16 chars uses the first 16`() {
        val plain = ByteArray(20) { it.toByte() }
        val body = encryptBody(plain, key16)
        assertArrayEquals(plain, MediaCrypto.decrypt(body, "abcdefghijklmnopXYZ"))
    }

    @Test
    fun `short password returns null`() {
        val body = encryptBody(ByteArray(16), key16)
        assertNull(MediaCrypto.decrypt(body, "short"))
    }

    @Test
    fun `body shorter than 4 returns null`() {
        assertNull(MediaCrypto.decrypt(byteArrayOf(1, 2, 3), key16))
    }

    @Test
    fun `ciphertext not multiple of 16 returns null`() {
        val body = encryptBody(ByteArray(16), key16)
        assertNull(MediaCrypto.decrypt(body.copyOf(body.size - 3), key16))
    }

    @Test
    fun `declared length larger than plaintext returns the whole plaintext`() {
        val plain = ByteArray(32) { (it + 1).toByte() }
        val body = encryptBody(plain, key16, declaredLen = 1000)
        assertArrayEquals(plain, MediaCrypto.decrypt(body, key16))
    }

    @Test
    fun `magic classifies jpeg png mp4 tar ts unknown`() {
        assertEquals(MediaCrypto.Magic.JPEG, MediaCrypto.magic(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0)))
        assertEquals(MediaCrypto.Magic.PNG, MediaCrypto.magic(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A)))
        val mp4 = ByteArray(32).also { "ftypisom".toByteArray(Charsets.US_ASCII).copyInto(it, 4) }
        assertEquals(MediaCrypto.Magic.MP4, MediaCrypto.magic(mp4))
        assertEquals(MediaCrypto.Magic.TAR, MediaCrypto.magic(tarOf(tarEntry("a.jpg", ByteArray(10)))))
        val ts = ByteArray(188 * 3).also { for (i in 0 until 3) it[i * 188] = 0x47 }
        assertEquals(MediaCrypto.Magic.TS, MediaCrypto.magic(ts))
        val badTs = ts.copyOf().also { it[188] = 0 }
        assertEquals(MediaCrypto.Magic.UNKNOWN, MediaCrypto.magic(badTs))
        assertEquals(MediaCrypto.Magic.UNKNOWN, MediaCrypto.magic("#EXTM3U\n".toByteArray()))
        assertEquals(MediaCrypto.Magic.UNKNOWN, MediaCrypto.magic(ByteArray(0)))
    }

    @Test
    fun `codec class finds avc1 hvc1 hev1 or unknown`() {
        fun mp4With(tag: String) = ByteArray(4096).also {
            "ftypisom".toByteArray(Charsets.US_ASCII).copyInto(it, 4)
            tag.toByteArray(Charsets.US_ASCII).copyInto(it, 2000)
        }
        assertEquals(MediaCrypto.Codec.AVC, MediaCrypto.codec(mp4With("avc1")))
        assertEquals(MediaCrypto.Codec.AVC, MediaCrypto.codec(mp4With("avc3")))
        assertEquals(MediaCrypto.Codec.HEVC, MediaCrypto.codec(mp4With("hvc1")))
        assertEquals(MediaCrypto.Codec.HEVC, MediaCrypto.codec(mp4With("hev1")))
        assertEquals(MediaCrypto.Codec.UNKNOWN, MediaCrypto.codec(mp4With("mp4a")))
    }

    @Test
    fun `tar preview entry picks the jpg that is not a face entry`() {
        val face = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 1, 1)
        val pic = ByteArray(700) { (it % 251).toByte() }.also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte() }
        val tar = tarOf(tarEntry("face_detect/face_1.jpg", face), tarEntry("pic.jpg", pic))
        assertArrayEquals(pic, MediaCrypto.tarPreview(tar))
    }

    @Test
    fun `tar without jpg returns null`() {
        val tar = tarOf(tarEntry("info.txt", "hello".toByteArray()))
        assertNull(MediaCrypto.tarPreview(tar))
    }

    @Test
    fun `truncated tar header returns null`() {
        val tar = tarOf(tarEntry("pic.jpg", ByteArray(600)))
        assertNull(MediaCrypto.tarPreview(tar.copyOf(300)))
        assertNull(MediaCrypto.tarPreview(tar.copyOf(700)))
    }
}
