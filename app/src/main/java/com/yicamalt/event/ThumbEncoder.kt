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

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import javax.inject.Inject

// START_MODULE_MAP
//   ThumbEncoder - port: encode(image) -> small JPEG or null.
//   BitmapThumbEncoder - Android implementation (not JVM-tested; tests use a pass-through fake).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 05-03: created. Images narrower than 480 px are re-encoded, not upscaled.
// END_CHANGE_SUMMARY

fun interface ThumbEncoder {
    fun encode(image: ByteArray): ByteArray?
}

class BitmapThumbEncoder @Inject constructor() : ThumbEncoder {

    // START_CONTRACT: encode
    //   PURPOSE: Decode JPEG/PNG bytes, downscale to 480 px wide, encode JPEG quality 80.
    //   INPUTS: { image: ByteArray - decrypted preview }
    //   OUTPUTS: { ByteArray? - JPEG bytes; null when the image cannot be decoded or encoded }
    //   SIDE_EFFECTS: none
    // END_CONTRACT: encode
    override fun encode(image: ByteArray): ByteArray? {
        // START_BLOCK_THUMB_ENCODE
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET_WIDTH) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            image, 0, image.size, BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        val scaled = if (decoded.width > TARGET_WIDTH) {
            val height = (decoded.height.toLong() * TARGET_WIDTH / decoded.width).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(decoded, TARGET_WIDTH, height, true)
        } else {
            decoded
        }
        val out = ByteArrayOutputStream()
        val ok = scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        return if (ok) out.toByteArray() else null
        // END_BLOCK_THUMB_ENCODE
    }

    private companion object {
        const val TARGET_WIDTH = 480
        const val JPEG_QUALITY = 80
    }
}
