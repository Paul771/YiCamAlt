// FILE: DecoderCatalog.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Tell which H.264 / H.265 decoder the phone would use, and whether it is hardware and low-latency.
//   SCOPE: MediaCodecList lookup per mime; one value-free summary line for the debug P2P check.
//   DEPENDS: android.media (MediaCodecList, MediaCodecInfo, MediaFormat)
//   LINKS: M-MEDIA-RENDER
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.media

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import timber.log.Timber

// START_MODULE_MAP
//   DecoderChoice - mime, decoder name (null = none), hardware, lowLatency.
//   DecoderCatalog.find(mime, width, height) - best decoder for the format; never throws.
//   DecoderCatalog.summary() - BLOCK_QUERY_DECODERS line for avc and hevc.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 02-03: created for criterion 4 (does the phone decode HEVC). Android-only,
//     device-verified in plan 02-06.
// END_CHANGE_SUMMARY

data class DecoderChoice(val mime: String, val name: String?, val hardware: Boolean, val lowLatency: Boolean)

object DecoderCatalog {

    const val AVC = MediaFormat.MIMETYPE_VIDEO_AVC
    const val HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC

    // START_CONTRACT: find
    //   PURPOSE: Decoder MediaCodec would pick for mime at width x height.
    //   INPUTS: { mime: String - e.g. video/avc; width, height: Int - format size }
    //   OUTPUTS: { DecoderChoice - name null and flags false when none exists or the lookup throws }
    //   SIDE_EFFECTS: none
    //   LINKS: M-MEDIA-RENDER
    // END_CONTRACT: find
    fun find(mime: String, width: Int = 1920, height: Int = 1080): DecoderChoice = try {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val name = list.findDecoderForFormat(MediaFormat.createVideoFormat(mime, width, height))
        if (name == null) {
            DecoderChoice(mime, null, false, false)
        } else {
            val info = list.codecInfos.firstOrNull { it.name == name }
            DecoderChoice(mime, name, isHardware(name, info), isLowLatency(mime, info))
        }
    } catch (e: Exception) {
        DecoderChoice(mime, null, false, false)
    }

    private fun isHardware(name: String, info: MediaCodecInfo?): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info != null) {
            info.isHardwareAccelerated
        } else {
            !name.startsWith("OMX.google.") && !name.startsWith("c2.android.")
        }

    private fun isLowLatency(mime: String, info: MediaCodecInfo?): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && info != null) {
            runCatching {
                info.getCapabilitiesForType(mime)
                    .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
            }.getOrDefault(false)
        } else {
            false
        }

    // START_BLOCK_QUERY_DECODERS
    fun summary(): String {
        val avc = find(AVC)
        val hevc = find(HEVC)
        val line = "avc=${avc.name ?: "none"} avcHw=${avc.hardware} hevc=${hevc.name ?: "none"} hevcHw=${hevc.hardware}"
        Timber.d("[Media][catalog][BLOCK_QUERY_DECODERS] %s", line)
        return line
    }
    // END_BLOCK_QUERY_DECODERS
}
