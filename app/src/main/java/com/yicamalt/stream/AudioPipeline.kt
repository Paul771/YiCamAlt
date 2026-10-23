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

import timber.log.Timber
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

// START_MODULE_MAP
//   AacConfig - value class of objectType, sampleRate, channels, csd0 (toString without bytes).
//   AacFrameSink - fun interface: onAacFrame(config, frame, ptsUs).
//   AudioStats - data class: counters, last codec id, rate, channels, decryptOk.
//   AudioPipeline - AudioUnitSink: muted, onAudioUnit, stats, availability, AUDIO_PROBE_MS.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 03-01 from 03-RESEARCH.md Pattern 3.
//     INVARIANT: muted by default (D-05); units are always counted, frames reach the sink only when unmuted.
//     INVARIANT: fail-open decrypt: no password or a key length outside 16/24/32 uses the payload as is.
//     INVARIANT: the unit payload is never modified (decrypt works on a copy); nothing is dropped here.
//     INVARIANT: logs and toString are value-free (no payload, password or cipher material).
// END_CHANGE_SUMMARY

class AacConfig(val objectType: Int, val sampleRate: Int, val channels: Int, val csd0: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is AacConfig && other.objectType == objectType && other.sampleRate == sampleRate &&
            other.channels == channels && other.csd0.contentEquals(csd0)

    override fun hashCode(): Int = ((objectType * 31 + sampleRate) * 31 + channels) * 31 + csd0.contentHashCode()

    override fun toString() = "AacConfig(obj=$objectType, rate=$sampleRate, ch=$channels, csdLen=${csd0.size})"
}

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

    private val cipher: Cipher? = buildCipher(password)
    private val startNs = nanoTime()
    private var lastPtsUs = -1L

    private var unitsIn = 0L
    private var framesOk = 0L
    private var badUnits = 0L
    private var codecId: Int? = null
    private var sampleRate: Int? = null
    private var channels: Int? = null
    private var decryptOk: Boolean? = null
    private var badCodecSeen = false
    private var config: AacConfig? = null
    private var loggedFirst = false
    private var loggedBad = false

    // START_CONTRACT: onAudioUnit
    //   PURPOSE: Decrypt (if flagged and a key is usable), walk ADTS frames, update stats, deliver when unmuted.
    //   INPUTS: { unit: AudioUnit - one transport audio unit }
    //   OUTPUTS: { Unit }
    //   SIDE_EFFECTS: stats counters; AacFrameSink.onAacFrame per frame when muted == false; first-good / first-bad log
    //   LINKS: M-STREAM-CORE, AdtsParser.parse, M-MEDIA-AUDIO
    // END_CONTRACT: onAudioUnit
    @Synchronized override fun onAudioUnit(unit: AudioUnit) {
        unitsIn++
        val id = unit.info.codecId
        codecId = id

        // START_BLOCK_AUDIO_CODEC_GATE
        if (id != AudioCodec.AAC.wireId) {
            badUnits++
            badCodecSeen = true
            logBad(id, "codec")
            return
        }
        // END_BLOCK_AUDIO_CODEC_GATE

        // START_BLOCK_AUDIO_DECRYPT
        val c = cipher
        val decrypted = unit.encrypted && c != null
        val data = if (decrypted) decryptFullBlocks(c!!, unit.payload) else unit.payload
        // END_BLOCK_AUDIO_DECRYPT

        // START_BLOCK_AUDIO_SPLIT
        val frames = AdtsParser.parseAll(data)
        if (frames.isEmpty()) {
            badUnits++
            if (decrypted) decryptOk = false
            logBad(id, "sync")
            return
        }
        if (decrypted) decryptOk = true
        var off = 0
        for (f in frames) {
            framesOk++
            val cfg = configFor(f)
            // START_BLOCK_AUDIO_FIRST
            if (!loggedFirst) {
                loggedFirst = true
                Timber.i(
                    "[Stream][audio][BLOCK_AUDIO_FIRST] codecId=%d rate=%d ch=%d enc=%b",
                    id, cfg.sampleRate, cfg.channels, unit.encrypted,
                )
            }
            // END_BLOCK_AUDIO_FIRST
            if (!muted) {
                val raw = data.copyOfRange(off + f.headerLen, off + f.frameLen)
                out.onAacFrame(cfg, raw, nextPtsUs())
            }
            off += f.frameLen
        }
        // END_BLOCK_AUDIO_SPLIT
    }

    // START_CONTRACT: stats
    //   PURPOSE: Snapshot of value-free counters for the debug row.
    //   INPUTS: none
    //   OUTPUTS: { AudioStats }
    //   SIDE_EFFECTS: none
    //   LINKS: M-STREAM-CORE
    // END_CONTRACT: stats
    @Synchronized fun stats(): AudioStats =
        AudioStats(unitsIn, framesOk, badUnits, codecId, sampleRate, channels, decryptOk)

    // START_CONTRACT: availability
    //   PURPOSE: Decide whether the sound button is shown (D-07).
    //   INPUTS: { streamingAtMs: Long? - time video streaming started, null if not streaming; nowMs: Long - now }
    //   OUTPUTS: { Boolean? - true after the first valid frame; false if a non-AAC codec was seen or no valid frame
    //     within AUDIO_PROBE_MS of streaming; null while still unknown }
    //   SIDE_EFFECTS: none
    //   LINKS: M-STREAM-CORE, M-UI-LIVEVIEW
    // END_CONTRACT: availability
    @Synchronized fun availability(streamingAtMs: Long?, nowMs: Long): Boolean? {
        // START_BLOCK_AUDIO_AVAILABILITY
        if (framesOk > 0) return true
        if (badCodecSeen) return false
        if (streamingAtMs == null) return null
        return if (nowMs - streamingAtMs >= AUDIO_PROBE_MS) false else null
        // END_BLOCK_AUDIO_AVAILABILITY
    }

    private fun configFor(f: AdtsFrame): AacConfig {
        val rate = AdtsParser.sampleRate(f.freqIndex)
        val cur = config
        if (cur != null && cur.objectType == f.objectType && cur.sampleRate == rate && cur.channels == f.channels) return cur
        val cfg = AacConfig(f.objectType, rate, f.channels, AdtsParser.audioSpecificConfig(f))
        config = cfg
        sampleRate = rate
        channels = f.channels
        return cfg
    }

    private fun nextPtsUs(): Long {
        val pts = maxOf(lastPtsUs + 1, (nanoTime() - startNs) / 1000)
        lastPtsUs = pts
        return pts
    }

    private fun logBad(id: Int, reason: String) {
        // START_BLOCK_AUDIO_BAD
        if (loggedBad) return
        loggedBad = true
        Timber.w("[Stream][audio][BLOCK_AUDIO_BAD] codecId=%d reason=%s", id, reason)
        // END_BLOCK_AUDIO_BAD
    }

    companion object {
        const val AUDIO_PROBE_MS = 6_000L
        private const val BLOCK = 16
        private const val TRANSFORMATION = "AES/ECB/NoPadding"

        // Same rule as IFrameDecryptor: ASCII(password + "0"), length gate 16/24/32, otherwise no cipher (fail-open).
        private fun buildCipher(password: String?): Cipher? {
            if (password == null) return null
            val material = (password + "0").toByteArray(Charsets.US_ASCII)
            try {
                if (material.size != 16 && material.size != 24 && material.size != 32) return null
                return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(material, "AES")) }
            } finally {
                material.fill(0)
            }
        }

        private fun decryptFullBlocks(c: Cipher, payload: ByteArray): ByteArray {
            val copy = payload.copyOf()
            val full = copy.size / BLOCK * BLOCK
            if (full > 0) c.doFinal(copy, 0, full, copy, 0)
            return copy
        }
    }
}
