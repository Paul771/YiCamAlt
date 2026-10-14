// FILE: FramePipeline.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Turn transport VideoUnits into DecodableFrames: order, decrypt I-frames, convert to Annex-B, extract csd.
//   SCOPE: codec mapping (78 H.264, 81 H.265, else unsupported once), FrameAssembler gate, IFrameDecryptor on key
//     frames when p2pEncrypt, NalUtils framing/csd, monotonic ptsUs, stats snapshot, first-keyframe log.
//   DEPENDS: StreamTypes, FrameAssembler, IFrameDecryptor, NalUtils, Timber
//   LINKS: M-STREAM-CORE, V-M-STREAM-CORE
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.stream

import timber.log.Timber

// START_MODULE_MAP
//   PipelineStats - snapshot: framesIn, keyFrames, emitted, dropped, decryptOk, framing, unsupportedCodec.
//   FramePipeline - VideoUnitSink: onVideoUnit(unit), stats(), reset().
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 02-05.
//     INVARIANT: the password reaches only IFrameDecryptor; logs carry NAL type numbers, framing, sizes and booleans,
//     never payload bytes. Fail-open: a frame whose decrypt check fails is still forwarded (vendor behaviour);
//     decryptOk in stats reports the result. Units arrive from two reader threads (ch2 I, ch3 P), so
//     onVideoUnit and reset are @Synchronized.
// END_CHANGE_SUMMARY

// Snapshot of pipeline counters. decryptOk is null until the first key frame; framing is "annexb" | "length" |
// "unknown" | "none" (no key frame yet); unsupportedCodec is the last unsupported wire id seen, if any.
data class PipelineStats(
    val framesIn: Long,
    val keyFrames: Long,
    val emitted: Long,
    val dropped: Long,
    val decryptOk: Boolean?,
    val framing: String,
    val unsupportedCodec: Int?,
)

class FramePipeline(
    private val password: String?,
    private val p2pEncrypt: Boolean,
    private val sink: DecodableFrameSink,
    private val nanoTime: () -> Long = System::nanoTime,
) : VideoUnitSink {

    private val assembler = FrameAssembler()
    private val signalledUnsupported = HashSet<Int>()
    private var framesIn = 0L
    private var keyFrames = 0L
    private var emitted = 0L
    private var codecDropped = 0L
    private var decryptOk: Boolean? = null
    private var framing = "none"
    private var unsupportedCodec: Int? = null
    private var lastCsd: List<ByteArray> = emptyList()
    private var firstKeyLogged = false
    private var startNs: Long? = null
    private var lastPtsUs = -1L

    @Volatile
    private var snapshot = buildStats()

    // START_CONTRACT: onVideoUnit
    //   PURPOSE: Accept one transport unit and forward every decodable frame it releases.
    //   INPUTS: { unit: VideoUnit - I-frame payload still encrypted when p2pEncrypt }
    //   OUTPUTS: none (DecodableFrameSink.onFrame per emitted frame; onUnsupportedCodec once per unknown codec id)
    //   SIDE_EFFECTS: decrypts a copy of the I-frame payload; updates stats; logs BLOCK_FIRST_KEYFRAME once per arm
    //   LINKS: M-STREAM-CORE, FrameAssembler.offer, IFrameDecryptor.decryptInPlace, NalUtils
    // END_CONTRACT: onVideoUnit
    @Synchronized
    override fun onVideoUnit(unit: VideoUnit) {
        framesIn++
        try {
            // START_BLOCK_MAP_CODEC
            val codecId = unit.info.codecId
            if (VideoCodec.fromWireId(codecId) == null) {
                codecDropped++
                unsupportedCodec = codecId
                if (signalledUnsupported.add(codecId)) {
                    Timber.w("[Stream][pipeline][BLOCK_MAP_CODEC] unsupported codecId=%d", codecId)
                    sink.onUnsupportedCodec(codecId)
                }
                return
            }
            // END_BLOCK_MAP_CODEC
            for (ready in assembler.offer(unit)) forward(ready)
        } finally {
            snapshot = buildStats()
        }
    }

    private fun forward(unit: VideoUnit) {
        val info = unit.info
        val codec = VideoCodec.fromWireId(info.codecId) ?: return
        val annexB: ByteArray
        val csd: List<ByteArray>
        if (info.isKeyFrame) {
            // START_BLOCK_FIRST_KEYFRAME
            val bytes = unit.payload.copyOf()
            if (p2pEncrypt && password != null) IFrameDecryptor.decryptInPlace(bytes, password)
            val detected = NalUtils.detectFraming(bytes)
            framing = when (detected) {
                NalUtils.Framing.ANNEX_B -> "annexb"
                NalUtils.Framing.LENGTH_PREFIXED -> "length"
                NalUtils.Framing.UNKNOWN -> "unknown"
            }
            annexB = NalUtils.toAnnexB(bytes)
            val types = NalUtils.nalTypes(annexB, codec)
            val ok = NalUtils.isKeyStart(types, codec)
            decryptOk = ok
            val found = NalUtils.parameterSets(annexB, codec)
            if (found.isNotEmpty()) lastCsd = found
            csd = lastCsd
            keyFrames++
            if (!firstKeyLogged) {
                firstKeyLogged = true
                Timber.d(
                    "[Stream][pipeline][BLOCK_FIRST_KEYFRAME] codec=%s framing=%s nals=%s decryptOk=%s size=%dx%d",
                    codec.name, framing, types.take(8).joinToString(","), ok, info.width, info.height,
                )
            }
            // END_BLOCK_FIRST_KEYFRAME
        } else {
            annexB = NalUtils.toAnnexB(unit.payload)
            csd = lastCsd
        }
        // START_BLOCK_EMIT_FRAME
        val now = nanoTime()
        val start = startNs ?: now.also { startNs = it }
        val ptsUs = maxOf(lastPtsUs + 1, (now - start) / 1000)
        lastPtsUs = ptsUs
        emitted++
        sink.onFrame(DecodableFrame(codec, info.isKeyFrame, annexB, csd, ptsUs, info.width, info.height))
        // END_BLOCK_EMIT_FRAME
    }

    // START_CONTRACT: stats
    //   PURPOSE: Thread-safe read of the latest counters.
    //   INPUTS: none
    //   OUTPUTS: { PipelineStats }
    //   SIDE_EFFECTS: none
    //   LINKS: M-STREAM-CORE
    // END_CONTRACT: stats
    fun stats(): PipelineStats = snapshot

    // START_CONTRACT: reset
    //   PURPOSE: Re-arm the keyframe gate after the decoder owner recreates the decoder.
    //   INPUTS: none
    //   OUTPUTS: none
    //   SIDE_EFFECTS: clears pending units and cached csd; the next first key frame is logged again; ptsUs stays monotonic
    //   LINKS: FrameAssembler.reset
    // END_CONTRACT: reset
    @Synchronized
    fun reset() {
        assembler.reset()
        lastCsd = emptyList()
        firstKeyLogged = false
        snapshot = buildStats()
    }

    private fun buildStats() = PipelineStats(
        framesIn = framesIn,
        keyFrames = keyFrames,
        emitted = emitted,
        dropped = assembler.dropped + codecDropped,
        decryptOk = decryptOk,
        framing = framing,
        unsupportedCodec = unsupportedCodec,
    )
}
