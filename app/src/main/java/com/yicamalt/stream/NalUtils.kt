// FILE: NalUtils.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: NAL framing detection, conversion to Annex-B, NAL type extraction and codec-specific data for H.264/H.265.
//   SCOPE: Annex-B (3/4-byte start codes) vs 4-byte big-endian length prefixes, NAL splitting, SPS/PPS/VPS extraction,
//     key-NAL test.
//   DEPENDS: StreamTypes (VideoCodec)
//   LINKS: M-STREAM-CORE, V-M-STREAM-CORE
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.stream

import java.io.ByteArrayOutputStream

// START_MODULE_MAP
//   NalUtils - object: Framing enum, START_CODE, detectFraming, toAnnexB, splitAnnexB, nalType, nalTypes,
//     parameterSets, isKeyStart.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 02-05 from 02-RESEARCH.md Pattern 6.
//     H.264 type = b and 0x1F (SPS 7, PPS 8, IDR 5); H.265 type = (b shr 1) and 0x3F (VPS 32, SPS 33, PPS 34,
//     IDR 19/20, CRA 21). Annex-B is checked before length prefixes, so an ambiguous 00 00 00 01 head is Annex-B.
// END_CHANGE_SUMMARY

object NalUtils {

    enum class Framing { ANNEX_B, LENGTH_PREFIXED, UNKNOWN }

    val START_CODE: ByteArray get() = byteArrayOf(0, 0, 0, 1)

    private const val H264_SPS = 7
    private const val H264_PPS = 8
    private const val H264_IDR = 5
    private const val H265_VPS = 32
    private const val H265_SPS = 33
    private const val H265_PPS = 34
    private val H264_KEY_START = setOf(H264_SPS, H264_IDR)
    private val H265_KEY_START = setOf(H265_VPS, H265_SPS, 19, 20, 21)

    // START_CONTRACT: detectFraming
    //   PURPOSE: Classify a payload as Annex-B, 4-byte length-prefixed, or unknown.
    //   INPUTS: { bytes: ByteArray - one access unit }
    //   OUTPUTS: { Framing - LENGTH_PREFIXED only if the length chain covers the payload exactly }
    //   SIDE_EFFECTS: none
    //   LINKS: M-STREAM-CORE
    // END_CONTRACT: detectFraming
    fun detectFraming(bytes: ByteArray): Framing {
        if (startCodeLength(bytes, 0) > 0) return Framing.ANNEX_B
        var pos = 0
        var nals = 0
        while (pos + 4 <= bytes.size) {
            val len = u32(bytes, pos)
            if (len <= 0 || len > bytes.size - pos - 4) return Framing.UNKNOWN
            pos += 4 + len.toInt()
            nals++
        }
        return if (nals > 0 && pos == bytes.size) Framing.LENGTH_PREFIXED else Framing.UNKNOWN
    }

    // START_CONTRACT: toAnnexB
    //   PURPOSE: Rewrite a length-prefixed access unit with 4-byte start codes.
    //   INPUTS: { bytes: ByteArray }
    //   OUTPUTS: { ByteArray - new array for LENGTH_PREFIXED input; the same instance for Annex-B or unknown input }
    //   SIDE_EFFECTS: none
    //   LINKS: M-STREAM-CORE
    // END_CONTRACT: toAnnexB
    fun toAnnexB(bytes: ByteArray): ByteArray {
        if (detectFraming(bytes) != Framing.LENGTH_PREFIXED) return bytes
        val out = ByteArrayOutputStream(bytes.size)
        var pos = 0
        while (pos + 4 <= bytes.size) {
            val len = u32(bytes, pos).toInt()
            out.write(START_CODE)
            out.write(bytes, pos + 4, len)
            pos += 4 + len
        }
        return out.toByteArray()
    }

    // START_CONTRACT: splitAnnexB
    //   PURPOSE: Locate NAL bodies (without start codes) in an Annex-B buffer.
    //   INPUTS: { bytes: ByteArray - Annex-B access unit }
    //   OUTPUTS: { List<IntRange> - non-empty NAL body ranges in order; empty if no start code is found }
    //   SIDE_EFFECTS: none
    //   LINKS: M-STREAM-CORE
    // END_CONTRACT: splitAnnexB
    fun splitAnnexB(bytes: ByteArray): List<IntRange> {
        // START_BLOCK_SPLIT_NALS
        val starts = ArrayList<Pair<Int, Int>>() // (start code begin, body begin)
        var i = 0
        while (i + 2 < bytes.size) {
            if (bytes[i].toInt() == 0 && bytes[i + 1].toInt() == 0 && bytes[i + 2].toInt() == 1) {
                val begin = if (i > 0 && bytes[i - 1].toInt() == 0) i - 1 else i
                starts += begin to (i + 3)
                i += 3
            } else {
                i++
            }
        }
        val out = ArrayList<IntRange>(starts.size)
        for ((idx, s) in starts.withIndex()) {
            val end = if (idx + 1 < starts.size) starts[idx + 1].first else bytes.size
            if (end > s.second) out += s.second until end
        }
        return out
        // END_BLOCK_SPLIT_NALS
    }

    // START_CONTRACT: nalType
    //   PURPOSE: NAL unit type from the first header byte.
    //   INPUTS: { firstByte: Byte, codec: VideoCodec }
    //   OUTPUTS: { Int - H.264 b and 0x1F; H.265 (b shr 1) and 0x3F }
    //   SIDE_EFFECTS: none
    //   LINKS: M-STREAM-CORE
    // END_CONTRACT: nalType
    fun nalType(firstByte: Byte, codec: VideoCodec): Int {
        val b = firstByte.toInt() and 0xFF
        return when (codec) {
            VideoCodec.H264 -> b and 0x1F
            VideoCodec.H265 -> (b shr 1) and 0x3F
        }
    }

    // START_CONTRACT: nalTypes
    //   PURPOSE: NAL types of every NAL in an Annex-B access unit, in order.
    //   INPUTS: { annexB: ByteArray, codec: VideoCodec }
    //   OUTPUTS: { List<Int> }
    //   SIDE_EFFECTS: none
    //   LINKS: M-STREAM-CORE
    // END_CONTRACT: nalTypes
    fun nalTypes(annexB: ByteArray, codec: VideoCodec): List<Int> =
        splitAnnexB(annexB).map { nalType(annexB[it.first], codec) }

    // START_CONTRACT: parameterSets
    //   PURPOSE: Codec-specific data for MediaCodec.
    //   INPUTS: { annexB: ByteArray, codec: VideoCodec }
    //   OUTPUTS: { List<ByteArray> - H.264 [SPS, PPS] each with a start code; H.265 [VPS+SPS+PPS] concatenated
    //     with start codes; empty list if any required parameter set is missing }
    //   SIDE_EFFECTS: none
    //   LINKS: M-STREAM-CORE, FramePipeline
    // END_CONTRACT: parameterSets
    fun parameterSets(annexB: ByteArray, codec: VideoCodec): List<ByteArray> {
        val byType = LinkedHashMap<Int, IntRange>()
        for (r in splitAnnexB(annexB)) byType.putIfAbsent(nalType(annexB[r.first], codec), r)
        fun withStart(r: IntRange) = START_CODE + annexB.copyOfRange(r.first, r.last + 1)
        return when (codec) {
            VideoCodec.H264 -> {
                val sps = byType[H264_SPS] ?: return emptyList()
                val pps = byType[H264_PPS] ?: return emptyList()
                listOf(withStart(sps), withStart(pps))
            }
            VideoCodec.H265 -> {
                val vps = byType[H265_VPS] ?: return emptyList()
                val sps = byType[H265_SPS] ?: return emptyList()
                val pps = byType[H265_PPS] ?: return emptyList()
                listOf(withStart(vps) + withStart(sps) + withStart(pps))
            }
        }
    }

    // START_CONTRACT: isKeyStart
    //   PURPOSE: Does the access unit start with a key NAL (proof that the I-frame decrypt produced valid bytes)?
    //   INPUTS: { types: List<Int> - from nalTypes, codec: VideoCodec }
    //   OUTPUTS: { Boolean - H.264 first in {7, 5}; H.265 first in {32, 33, 19, 20, 21}; false for an empty list }
    //   SIDE_EFFECTS: none
    //   LINKS: M-STREAM-CORE, FramePipeline
    // END_CONTRACT: isKeyStart
    fun isKeyStart(types: List<Int>, codec: VideoCodec): Boolean {
        val first = types.firstOrNull() ?: return false
        return when (codec) {
            VideoCodec.H264 -> first in H264_KEY_START
            VideoCodec.H265 -> first in H265_KEY_START
        }
    }

    private fun startCodeLength(b: ByteArray, at: Int): Int = when {
        b.size >= at + 4 && b[at].toInt() == 0 && b[at + 1].toInt() == 0 && b[at + 2].toInt() == 0 &&
            b[at + 3].toInt() == 1 -> 4
        b.size >= at + 3 && b[at].toInt() == 0 && b[at + 1].toInt() == 0 && b[at + 2].toInt() == 1 -> 3
        else -> 0
    }

    private fun u32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)
}
