// FILE: AudioPipeline.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Turn transport AudioUnits into raw AAC frames + csd-0 for the decoder (LIVE-06).
//   SCOPE: TNP audio decrypt (every full 16-byte block), ADTS walk, mute gate, availability, value-free stats.
//   DEPENDS: StreamTypes, AdtsParser, JDK javax.crypto, Timber
//   LINKS: M-STREAM-CORE, V-M-STREAM-CORE
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.stream

// START_MODULE_MAP
//   AacConfig - value class of objectType, sampleRate, channels, csd0 (toString without bytes).
//   AacFrameSink - fun interface: onAacFrame(config, frame, ptsUs).
//   AudioStats - data class: counters, last codec id, rate, channels, decryptOk.
//   AudioPipeline - AudioUnitSink: muted, onAudioUnit, stats, availability, AUDIO_PROBE_MS.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 03-01 RED stubs.
// END_CHANGE_SUMMARY

class AacConfig(val objectType: Int, val sampleRate: Int, val channels: Int, val csd0: ByteArray)

fun interface AacFrameSink {
    fun onAacFrame(config: AacConfig, frame: ByteArray, ptsUs: Long)
}

data class AudioStats(
    val unitsIn: Long,
    val framesOk: Long,
    val badUnits: Long,
    val codecId: Int?,
    val sampleRate: Int?,
    val channels: Int?,
    val decryptOk: Boolean?,
)

class AudioPipeline(
    private val password: String?,
    private val out: AacFrameSink,
    private val nanoTime: () -> Long = System::nanoTime,
) : AudioUnitSink {
    @Volatile var muted: Boolean = true

    @Synchronized override fun onAudioUnit(unit: AudioUnit) { error("RED stub") }

    fun stats(): AudioStats = error("RED stub")

    fun availability(streamingAtMs: Long?, nowMs: Long): Boolean? = error("RED stub")

    companion object {
        const val AUDIO_PROBE_MS = 6_000L
    }
}
