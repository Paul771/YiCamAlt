// FILE: MediaCrypto.kt
// VERSION: 0.0.1
// START_MODULE_CONTRACT
//   PURPOSE: Decrypt alert previews / clips (u32 LE length + AES-128/ECB/NoPadding) and classify the result.
//   SCOPE: decrypt, magic, codec, tarPreview. Pure; no I/O, no logging.
//   DEPENDS: none
//   LINKS: M-EVENT-PLAYBACK, V-M-EVENT-PLAYBACK, EVT-03
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.event

// START_MODULE_MAP
//   MediaCrypto - decrypt, Magic, magic, Codec, codec, tarPreview (RED stub).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.0.1 - GSD plan 05-00: RED stub for the test-first cycle.
// END_CHANGE_SUMMARY

internal object MediaCrypto {
    enum class Magic { JPEG, PNG, MP4, TAR, TS, UNKNOWN }
    enum class Codec { AVC, HEVC, UNKNOWN }

    fun decrypt(body: ByteArray, pwd: String): ByteArray? = error("RED stub")
    fun magic(bytes: ByteArray): Magic = error("RED stub")
    fun codec(mp4: ByteArray): Codec = error("RED stub")
    fun tarPreview(tar: ByteArray): ByteArray? = error("RED stub")
}
