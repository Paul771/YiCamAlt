// FILE: AdtsParser.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Parse ADTS AAC frame headers and derive the MediaCodec csd-0 (AudioSpecificConfig).
//   SCOPE: header validation, consecutive frame walk, sample rate table, 2-byte AudioSpecificConfig.
//   DEPENDS: none
//   LINKS: M-STREAM-CORE, V-M-STREAM-CORE
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.stream

// START_MODULE_MAP
//   AdtsFrame - data class: objectType, freqIndex, channels, headerLen, frameLen of one ADTS frame.
//   AdtsParser - object: parse(b, off), parseAll(b), audioSpecificConfig(f), sampleRate(freqIndex), SAMPLE_RATES.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 03-01 from 03-RESEARCH.md "ADTS -> csd-0".
//     INVARIANT: parse never throws on arbitrary bytes; any inconsistency returns null.
//     INVARIANT: only single-raw-block frames are accepted (MediaCodec gets one access unit per frame).
// END_CHANGE_SUMMARY

data class AdtsFrame(val objectType: Int, val freqIndex: Int, val channels: Int, val headerLen: Int, val frameLen: Int)

object AdtsParser {
    val SAMPLE_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
    private const val MIN_HEADER = 7
    private const val CRC_HEADER = 9

    // START_CONTRACT: parse
    //   PURPOSE: Validate and decode one ADTS header at b[off].
    //   INPUTS: { b: ByteArray - buffer, off: Int - header offset }
    //   OUTPUTS: { AdtsFrame? - null on short buffer, bad sync, layer != 0, freqIndex > 12, raw blocks != 0,
    //     frameLen < headerLen or frame past the buffer end }
    //   SIDE_EFFECTS: none
    //   LINKS: M-STREAM-CORE, AudioPipeline.onAudioUnit
    // END_CONTRACT: parse
    fun parse(b: ByteArray, off: Int): AdtsFrame? {
        // START_BLOCK_ADTS_HEADER
        if (off < 0 || b.size - off < MIN_HEADER) return null
        val b0 = b[off].toInt() and 0xFF
        val b1 = b[off + 1].toInt() and 0xFF
        val b2 = b[off + 2].toInt() and 0xFF
        val b3 = b[off + 3].toInt() and 0xFF
        val b4 = b[off + 4].toInt() and 0xFF
        val b5 = b[off + 5].toInt() and 0xFF
        val b6 = b[off + 6].toInt() and 0xFF
        if (b0 != 0xFF || (b1 and 0xF0) != 0xF0) return null
        if ((b1 shr 1) and 3 != 0) return null
        val headerLen = if (b1 and 1 == 1) MIN_HEADER else CRC_HEADER
        val objectType = ((b2 shr 6) and 3) + 1
        val freqIndex = (b2 shr 2) and 0xF
        if (freqIndex > 12) return null
        val channels = ((b2 and 1) shl 2) or ((b3 shr 6) and 3)
        val frameLen = ((b3 and 3) shl 11) or (b4 shl 3) or ((b5 shr 5) and 7)
        if (b6 and 3 != 0) return null
        if (frameLen < headerLen) return null
        if (off + frameLen > b.size) return null
        return AdtsFrame(objectType, freqIndex, channels, headerLen, frameLen)
        // END_BLOCK_ADTS_HEADER
    }

    // START_CONTRACT: parseAll
    //   PURPOSE: Walk consecutive ADTS frames from offset 0.
    //   INPUTS: { b: ByteArray - one transport audio payload (plaintext) }
    //   OUTPUTS: { List<AdtsFrame> - frames in order; stops at the first invalid header (empty if none) }
    //   SIDE_EFFECTS: none
    //   LINKS: M-STREAM-CORE
    // END_CONTRACT: parseAll
    fun parseAll(b: ByteArray): List<AdtsFrame> {
        // START_BLOCK_ADTS_WALK
        val out = ArrayList<AdtsFrame>(2)
        var off = 0
        while (off < b.size) {
            val f = parse(b, off) ?: break
            out += f
            off += f.frameLen
        }
        return out
        // END_BLOCK_ADTS_WALK
    }

    // START_CONTRACT: audioSpecificConfig
    //   PURPOSE: Build the 2-byte AudioSpecificConfig (MediaCodec csd-0) of a frame.
    //   INPUTS: { f: AdtsFrame }
    //   OUTPUTS: { ByteArray - (objectType shl 11) or (freqIndex shl 7) or (channels shl 3), big-endian }
    //   SIDE_EFFECTS: none
    //   LINKS: M-STREAM-CORE, M-MEDIA-AUDIO
    // END_CONTRACT: audioSpecificConfig
    fun audioSpecificConfig(f: AdtsFrame): ByteArray {
        val v = (f.objectType shl 11) or (f.freqIndex shl 7) or (f.channels shl 3)
        return byteArrayOf((v shr 8).toByte(), v.toByte())
    }

    // START_CONTRACT: sampleRate
    //   PURPOSE: Map an ADTS sampling frequency index to Hz.
    //   INPUTS: { freqIndex: Int - 0..12 }
    //   OUTPUTS: { Int - sample rate; 0 for an index outside the table }
    //   SIDE_EFFECTS: none
    //   LINKS: M-STREAM-CORE
    // END_CONTRACT: sampleRate
    fun sampleRate(freqIndex: Int): Int = SAMPLE_RATES.getOrElse(freqIndex) { 0 }
}
