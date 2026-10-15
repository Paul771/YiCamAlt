// FILE: DecoderPolicy.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Pure decision rules of the video decoder, testable on the JVM without MediaCodec.
//   SCOPE: bounded input queue with key-frame gate, codec-error streak counter, csd mapping per codec,
//     never-decreasing presentation time, decoder status values with Russian user texts.
//   DEPENDS: M-STREAM-CORE (DecodableFrame, VideoCodec)
//   LINKS: M-MEDIA-RENDER, V-M-MEDIA-RENDER
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.media

import com.yicamalt.stream.DecodableFrame
import com.yicamalt.stream.VideoCodec

// START_MODULE_MAP
//   OfferResult - ACCEPTED | DROPPED_NO_KEY | ACCEPTED_DROPPED_OLDEST.
//   FrameQueue(capacity = 8) - thread-safe FIFO behind a key-frame gate; overflow drops the oldest and closes the gate.
//   ResetCounter(max = 3) - codec-error streak; exhausted after max errors without a render in between.
//   CsdBuilder.csdFor(frame) - MediaFormat csd keys: AVC csd-0 SPS, csd-1 PPS; HEVC csd-0 VPS+SPS+PPS.
//   PtsGuard - strictly increasing presentation time for queueInputBuffer.
//   DecoderStatus - Idle | Ready | Rendering | NoDecoder | Unsupported | Failed; userText() is value-free.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 02-08: created; every decoder decision that can be tested off-device lives here.
//     INVARIANT: no Android framework import (JVM-tested by DecoderPolicyTest).
// END_CHANGE_SUMMARY

enum class OfferResult { ACCEPTED, DROPPED_NO_KEY, ACCEPTED_DROPPED_OLDEST }

// START_CONTRACT: FrameQueue
//   PURPOSE: Bounded FIFO between the frame pipeline and MediaCodec input buffers.
//   INPUTS: { capacity: Int - max queued frames (default 8) }
//   OUTPUTS: { offer -> OfferResult; poll -> oldest frame or null }
//   SIDE_EFFECTS: none (in-memory)
//   LINKS: M-MEDIA-RENDER
// END_CONTRACT: FrameQueue
class FrameQueue(private val capacity: Int = 8) {
    init { require(capacity > 0) { "capacity must be > 0" } }

    private val items = ArrayDeque<DecodableFrame>()
    private var gateOpen = false

    // True while P-frames are rejected (before the first key frame, after overflow or clear()).
    val needsKeyFrame: Boolean @Synchronized get() = !gateOpen

    val size: Int @Synchronized get() = items.size

    @Synchronized
    fun offer(frame: DecodableFrame): OfferResult {
        if (frame.isKeyFrame) {
            gateOpen = true
        } else if (!gateOpen) {
            return OfferResult.DROPPED_NO_KEY
        }
        items.addLast(frame)
        if (items.size > capacity) {
            items.removeFirst()
            // The reference chain is broken: wait for the next key frame.
            gateOpen = false
            return OfferResult.ACCEPTED_DROPPED_OLDEST
        }
        return OfferResult.ACCEPTED
    }

    @Synchronized
    fun poll(): DecodableFrame? = items.removeFirstOrNull()

    @Synchronized
    fun clear() {
        items.clear()
        gateOpen = false
    }
}

// START_CONTRACT: ResetCounter
//   PURPOSE: Stop a codec-error loop: max errors in a row without a rendered frame end in a failed state.
//   INPUTS: { max: Int - errors in a row that exhaust the counter (default 3) }
//   OUTPUTS: { resets: Int - errors in the current streak; exhausted: Boolean }
//   SIDE_EFFECTS: none
//   LINKS: M-MEDIA-RENDER
// END_CONTRACT: ResetCounter
class ResetCounter(private val max: Int = 3) {
    @Volatile var resets: Int = 0
        private set

    val exhausted: Boolean get() = resets >= max

    @Synchronized
    fun onError(): Int {
        resets += 1
        return resets
    }

    @Synchronized
    fun onRendered() {
        resets = 0
    }
}

object CsdBuilder {
    // START_CONTRACT: csdFor
    //   PURPOSE: MediaFormat codec-specific data for the key frame that creates the decoder.
    //   INPUTS: { frame: DecodableFrame - csd AVC [SPS, PPS] or HEVC [VPS+SPS+PPS], start codes included }
    //   OUTPUTS: { Map<String, ByteArray> - csd-0 / csd-1 keys; empty when the frame has no csd (in-band sets) }
    //   SIDE_EFFECTS: none
    //   LINKS: M-MEDIA-RENDER, M-STREAM-CORE
    // END_CONTRACT: csdFor
    fun csdFor(frame: DecodableFrame): Map<String, ByteArray> {
        if (frame.csd.isEmpty()) return emptyMap()
        return when (frame.codec) {
            VideoCodec.H264 ->
                if (frame.csd.size >= 2) mapOf("csd-0" to frame.csd[0], "csd-1" to frame.csd[1])
                else mapOf("csd-0" to frame.csd[0])
            VideoCodec.H265 -> {
                // HEVC: all parameter sets go into csd-0 as one Annex-B buffer.
                val joined = if (frame.csd.size == 1) frame.csd[0]
                else frame.csd.fold(ByteArray(0)) { acc, b -> acc + b }
                mapOf("csd-0" to joined)
            }
        }
    }
}

// START_CONTRACT: PtsGuard
//   PURPOSE: MediaCodec requires never-decreasing presentationTimeUs; equal or older input is bumped by 1 us.
//   INPUTS: { ptsUs: Long - pipeline pts }
//   OUTPUTS: { Long - strictly increasing pts }
//   SIDE_EFFECTS: remembers the last value
//   LINKS: M-MEDIA-RENDER
// END_CONTRACT: PtsGuard
class PtsGuard {
    private var last: Long = Long.MIN_VALUE

    @Synchronized
    fun next(ptsUs: Long): Long {
        val out = if (last == Long.MIN_VALUE || ptsUs > last) ptsUs else last + 1
        last = out
        return out
    }

    @Synchronized
    fun reset() {
        last = Long.MIN_VALUE
    }
}

sealed interface DecoderStatus {
    fun userText(): String

    object Idle : DecoderStatus {
        override fun userText() = "Декодер ждёт первый ключевой кадр"
        override fun toString() = "Idle"
    }

    data class Ready(val mime: String, val name: String, val hw: Boolean, val lowLatency: Boolean) : DecoderStatus {
        override fun userText() = "${codecLabel(mime)}: $name hw=$hw lowLatency=$lowLatency"
    }

    data class Rendering(val ready: Ready, val framesRendered: Long, val fps: Double) : DecoderStatus {
        override fun userText() = "${ready.userText()} кадров=$framesRendered fps=${"%.1f".format(java.util.Locale.ROOT, fps)}"
    }

    data class NoDecoder(val mime: String) : DecoderStatus {
        override fun userText() = "${codecLabel(mime)}: на этом телефоне нет декодера"
    }

    data class Unsupported(val codecId: Int) : DecoderStatus {
        override fun userText() = "Кодек $codecId не поддерживается"
    }

    data class Failed(val resets: Int) : DecoderStatus {
        override fun userText() = "Декодер остановлен после $resets ошибок подряд"
    }

    companion object {
        // Human codec label for a mime: video/avc -> H.264, video/hevc -> H.265, otherwise the mime itself.
        fun codecLabel(mime: String): String = when (mime) {
            VideoCodec.H264.mime -> "H.264"
            VideoCodec.H265.mime -> "H.265"
            else -> mime
        }
    }
}
