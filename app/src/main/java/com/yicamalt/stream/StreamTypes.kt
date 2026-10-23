// FILE: StreamTypes.kt
// VERSION: 0.2.0
// START_MODULE_CONTRACT
//   PURPOSE: Value types shared by the P2P transport, the frame pipeline and the decoder (Phase 2 port types).
//   SCOPE: VideoCodec / AudioCodec wire ids, 24-byte frame head value, transport video and audio units,
//     decodable Annex-B frame, sinks.
//   DEPENDS: none
//   LINKS: M-STREAM-CORE, V-M-STREAM-CORE
//   ROLE: TYPES
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.stream

// START_MODULE_MAP
//   VideoCodec - H264 (wire 78, video/avc) | H265 (wire 81, video/hevc); fromWireId(id) -> VideoCodec?.
//   FrameInfo - parsed 24-byte frame head; isKeyFrame = flags bit 0.
//   VideoUnit - one unit read from the transport (channel, head, payload); toString carries no bytes.
//   DecodableFrame - decrypted Annex-B access unit + csd for MediaCodec; toString carries no bytes.
//   VideoUnitSink - transport -> pipeline callback.
//   DecodableFrameSink - pipeline -> decoder callbacks (frame, unsupported codec).
//   AudioCodec - AAC (wire 138) | PCM (wire 140); fromWireId(id) -> AudioCodec?.
//   AudioUnit - one audio unit from the transport (channel, head, payload, encrypted); toString carries no bytes.
//   AudioUnitSink - transport -> audio pipeline callback.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.2.0 - GSD plan 03-00: Phase 3 audio port types AudioCodec, AudioUnit, AudioUnitSink.
//     INVARIANT: AudioUnit.toString() never prints payload bytes.
//   PREVIOUS: v0.1.0 - GSD plan 02-00: contract-first port types for Phase 2. Doc comments use // only (no block comments).
//     INVARIANT: toString() of VideoUnit / DecodableFrame never prints payload bytes (I-frames carry key-derived data).
// END_CHANGE_SUMMARY

enum class VideoCodec(val wireId: Int, val mime: String) {
    H264(78, "video/avc"),
    H265(81, "video/hevc");

    companion object {
        // Maps the frame-head codec id to a codec; null for audio or unknown ids.
        fun fromWireId(id: Int): VideoCodec? = entries.firstOrNull { it.wireId == id }
    }
}

// 24-byte frame head (TNP big-endian, TUTK little-endian). Parsed by FrameHeadCodec (plan 02-04).
data class FrameInfo(
    val codecId: Int,
    val flags: Int,
    val liveFlag: Int,
    val onlineNum: Int,
    val useCount: Int,
    val seq: Int,
    val width: Int,
    val height: Int,
    val timestampSec: Long,
    val isDay: Int,
    val cover: Int,
    val outLoss: Int,
    val inLoss: Int,
    val timestampMs: Long,
) {
    val isKeyFrame: Boolean get() = (flags and 1) == 1
}

// One video unit as read from the transport: head + payload after the 24-byte head (I-frames still encrypted).
class VideoUnit(val channel: Int, val info: FrameInfo, val payload: ByteArray) {
    override fun toString() =
        "VideoUnit(ch=$channel, codec=${info.codecId}, key=${info.isKeyFrame}, seq=${info.seq}, bytes=${payload.size})"
}

// Decrypted, Annex-B access unit ready for MediaCodec. csd: AVC [SPS, PPS] or HEVC [VPS+SPS+PPS], each with start code.
class DecodableFrame(
    val codec: VideoCodec,
    val isKeyFrame: Boolean,
    val annexB: ByteArray,
    val csd: List<ByteArray>,
    val ptsUs: Long,
    val width: Int,
    val height: Int,
) {
    override fun toString() =
        "DecodableFrame(codec=$codec, key=$isKeyFrame, bytes=${annexB.size}, csd=${csd.size}, ${width}x$height)"
}

fun interface VideoUnitSink {
    fun onVideoUnit(unit: VideoUnit)
}

interface DecodableFrameSink {
    fun onFrame(frame: DecodableFrame)
    fun onUnsupportedCodec(codecId: Int)
}

enum class AudioCodec(val wireId: Int) {
    AAC(138),
    PCM(140);

    companion object {
        // Maps the frame-head codec id to an audio codec; null for video or unknown ids.
        fun fromWireId(id: Int): AudioCodec? = entries.firstOrNull { it.wireId == id }
    }
}

// One audio unit from the transport: 24-byte frame head + payload. encrypted = TNP header version >= 2 at read time
// (every full 16-byte block AES-ECB); TUTK audio is never encrypted. toString carries no bytes.
class AudioUnit(val channel: Int, val info: FrameInfo, val payload: ByteArray, val encrypted: Boolean) {
    override fun toString() = "AudioUnit(ch=$channel, codec=${info.codecId}, enc=$encrypted, bytes=${payload.size})"
}

fun interface AudioUnitSink {
    fun onAudioUnit(unit: AudioUnit)
}
