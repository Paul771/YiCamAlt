// FILE: MediaCrypto.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Decrypt alert previews / clips (u32 LE length + AES-128/ECB/NoPadding) and classify the result.
//   SCOPE: decrypt, magic, codec, tarPreview (own minimal ustar reader). Pure; no I/O, no logging.
//   DEPENDS: none
//   LINKS: M-EVENT-PLAYBACK, V-M-EVENT-PLAYBACK, EVT-03
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.event

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

// START_MODULE_MAP
//   MediaCrypto - object: decrypt, magic, codec, tarPreview.
//   MediaCrypto.Magic - JPEG, PNG, MP4, TAR, TS, UNKNOWN container class by magic bytes.
//   MediaCrypto.Codec - AVC, HEVC, UNKNOWN video codec class by sample-entry fourcc.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 05-00: re-implemented from the vendor algorithm (research §2), not copied.
//     Output is TRUNCATED to the declared length (the vendor zero-fills, leaving trailing zeros in MP4/JPEG).
//   HAZARD: keys are passwords from the alert list; never log them (no logging in this object by design).
// END_CHANGE_SUMMARY

internal object MediaCrypto {

    enum class Magic { JPEG, PNG, MP4, TAR, TS, UNKNOWN }
    enum class Codec { AVC, HEVC, UNKNOWN }

    private const val TS_PACKET = 188
    private const val TAR_BLOCK = 512
    private const val CODEC_SCAN_LIMIT = 1 shl 20
    private val FACE_ENTRY = Regex("face_detect/face_.*\\.jpg")

    // START_CONTRACT: decrypt
    //   PURPOSE: Decrypt a media body: u32 LE plain length + AES-128/ECB/NoPadding ciphertext.
    //   INPUTS: { body: ByteArray - downloaded bytes, pwd: String - key; first 16 ASCII chars are used }
    //   OUTPUTS: { ByteArray? - plaintext truncated to the declared length (whole plaintext when the length
    //     exceeds it); null on body < 4, ciphertext not a multiple of 16, key shorter than 16, crypto error }
    //   SIDE_EFFECTS: none
    //   LINKS: M-EVENT-PLAYBACK
    // END_CONTRACT: decrypt
    fun decrypt(body: ByteArray, pwd: String): ByteArray? {
        // START_BLOCK_MEDIA_DECRYPT
        if (body.size < 4 || (body.size - 4) % 16 != 0) return null
        val key = pwd.take(16).toByteArray(Charsets.US_ASCII)
        if (key.size != 16) return null
        val plainLen = (body[0].toLong() and 0xFF) or ((body[1].toLong() and 0xFF) shl 8) or
            ((body[2].toLong() and 0xFF) shl 16) or ((body[3].toLong() and 0xFF) shl 24)
        val plain = try {
            Cipher.getInstance("AES/ECB/NoPadding")
                .apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES")) }
                .doFinal(body, 4, body.size - 4)
        } catch (e: GeneralSecurityException) {
            return null
        }
        return if (plainLen <= plain.size) plain.copyOf(plainLen.toInt()) else plain
        // END_BLOCK_MEDIA_DECRYPT
    }

    // START_CONTRACT: magic
    //   PURPOSE: Classify bytes by magic: FF D8 jpeg, 89 50 4E 47 png, ftyp at 4 mp4, ustar at 257 tar,
    //     0x47 at every 188-byte packet start (up to 5 packets) ts.
    //   INPUTS: { bytes: ByteArray }
    //   OUTPUTS: { Magic }
    //   SIDE_EFFECTS: none
    // END_CONTRACT: magic
    fun magic(bytes: ByteArray): Magic {
        // START_BLOCK_MEDIA_MAGIC
        fun at(off: Int, ascii: String): Boolean =
            bytes.size >= off + ascii.length && ascii.indices.all { bytes[off + it] == ascii[it].code.toByte() }
        return when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> Magic.JPEG
            bytes.size >= 4 && bytes[0] == 0x89.toByte() && at(1, "PNG") -> Magic.PNG
            at(4, "ftyp") -> Magic.MP4
            at(257, "ustar") -> Magic.TAR
            isTs(bytes) -> Magic.TS
            else -> Magic.UNKNOWN
        }
        // END_BLOCK_MEDIA_MAGIC
    }

    private fun isTs(bytes: ByteArray): Boolean {
        if (bytes.size < TS_PACKET) return false
        val packets = minOf(bytes.size / TS_PACKET, 5)
        return (0 until packets).all { bytes[it * TS_PACKET] == 0x47.toByte() }
    }

    // START_CONTRACT: codec
    //   PURPOSE: Find the video sample-entry fourcc in the first 1 MB of an MP4.
    //   INPUTS: { mp4: ByteArray }
    //   OUTPUTS: { Codec - AVC for avc1/avc3, HEVC for hvc1/hev1, else UNKNOWN }
    //   SIDE_EFFECTS: none
    // END_CONTRACT: codec
    fun codec(mp4: ByteArray): Codec {
        // START_BLOCK_MEDIA_CODEC
        val end = minOf(mp4.size, CODEC_SCAN_LIMIT) - 4
        for (i in 0..end) {
            if (mp4[i] == 'a'.code.toByte() && mp4[i + 1] == 'v'.code.toByte() && mp4[i + 2] == 'c'.code.toByte() &&
                (mp4[i + 3] == '1'.code.toByte() || mp4[i + 3] == '3'.code.toByte())
            ) return Codec.AVC
            if (mp4[i] == 'h'.code.toByte() &&
                ((mp4[i + 1] == 'v'.code.toByte() && mp4[i + 2] == 'c'.code.toByte()) ||
                    (mp4[i + 1] == 'e'.code.toByte() && mp4[i + 2] == 'v'.code.toByte())) &&
                mp4[i + 3] == '1'.code.toByte()
            ) return Codec.HEVC
        }
        return Codec.UNKNOWN
        // END_BLOCK_MEDIA_CODEC
    }

    // START_CONTRACT: tarPreview
    //   PURPOSE: Pick the preview image from a decrypted ustar bundle.
    //   INPUTS: { tar: ByteArray - decrypted bytes }
    //   OUTPUTS: { ByteArray? - data of the first entry ending in .jpg that is not face_detect/face_*.jpg;
    //     null when none, on a truncated header or an out-of-range size }
    //   SIDE_EFFECTS: none
    // END_CONTRACT: tarPreview
    fun tarPreview(tar: ByteArray): ByteArray? {
        // START_BLOCK_TAR_PREVIEW
        var off = 0
        while (off + TAR_BLOCK <= tar.size) {
            if ((0 until TAR_BLOCK).all { tar[off + it] == 0.toByte() }) return null
            val name = cString(tar, off, 100)
            val size = octal(tar, off + 124, 12) ?: return null
            val dataStart = off + TAR_BLOCK
            if (size < 0 || dataStart.toLong() + size > tar.size) return null
            if (name.endsWith(".jpg") && !FACE_ENTRY.matches(name)) {
                return tar.copyOfRange(dataStart, dataStart + size.toInt())
            }
            off = dataStart + (((size + TAR_BLOCK - 1) / TAR_BLOCK) * TAR_BLOCK).toInt()
        }
        return null
        // END_BLOCK_TAR_PREVIEW
    }

    private fun cString(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.US_ASCII)
    }

    private fun octal(b: ByteArray, off: Int, len: Int): Long? {
        val s = cString(b, off, len).trim()
        if (s.isEmpty() || s.any { it !in '0'..'7' }) return null
        return s.toLongOrNull(8)
    }
}
