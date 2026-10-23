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
//   LAST_CHANGE: v0.1.0 - GSD plan 03-01 RED stubs (03-RESEARCH.md "ADTS -> csd-0").
// END_CHANGE_SUMMARY

data class AdtsFrame(val objectType: Int, val freqIndex: Int, val channels: Int, val headerLen: Int, val frameLen: Int)

object AdtsParser {
    val SAMPLE_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    fun parse(b: ByteArray, off: Int): AdtsFrame? = error("RED stub")

    fun parseAll(b: ByteArray): List<AdtsFrame> = error("RED stub")

    fun audioSpecificConfig(f: AdtsFrame): ByteArray = error("RED stub")

    fun sampleRate(freqIndex: Int): Int = error("RED stub")
}
