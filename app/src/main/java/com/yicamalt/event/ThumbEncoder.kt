// FILE: ThumbEncoder.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Downscale a decrypted preview image to a small JPEG for the offline event list (EVT-01, EVT-05).
//   SCOPE: ThumbEncoder port; BitmapThumbEncoder (BitmapFactory inSampleSize + scale to 480 px wide, JPEG q80).
//   DEPENDS: android.graphics
//   LINKS: M-EVENT-PLAYBACK, EVT-01, EVT-05
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.event

import javax.inject.Inject

// START_MODULE_MAP
//   ThumbEncoder - port: encode(image) -> small JPEG or null.
//   BitmapThumbEncoder - Android implementation (not JVM-tested).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 05-03: created.
// END_CHANGE_SUMMARY

fun interface ThumbEncoder {
    fun encode(image: ByteArray): ByteArray?
}

class BitmapThumbEncoder @Inject constructor() : ThumbEncoder {
    override fun encode(image: ByteArray): ByteArray? = error("RED stub")
}
