// FILE: DefaultMediaSession.kt
// VERSION: 0.3.0
// START_MODULE_CONTRACT
//   PURPOSE: Production MediaSession of one link attempt: FramePipeline + AudioPipeline + key-frame tee.
//   SCOPE: videoSink (key-frame observation, then FramePipeline), audioSink (AudioPipeline only), mute gate,
//     counters, audio availability, pipeline re-arm after a decoder reset, release (later units ignored).
//   DEPENDS: M-STREAM-CORE (FramePipeline, AudioPipeline, sinks), M-P2P-CREDS (P2pCredentials),
//     live/LiveSessionTypes.kt (MediaSession, KeyFrameObservation)
//   LINKS: M-LIVE-SESSION, V-M-LIVE-SESSION
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.live

import com.yicamalt.camera.P2pCredentials
import com.yicamalt.stream.AacFrameSink
import com.yicamalt.stream.AudioPipeline
import com.yicamalt.stream.AudioStats
import com.yicamalt.stream.AudioUnitSink
import com.yicamalt.stream.DecodableFrameSink
import com.yicamalt.stream.FrameObservation
import com.yicamalt.stream.FramePipeline
import com.yicamalt.stream.PipelineStats
import com.yicamalt.stream.VideoUnitSink

// START_MODULE_MAP
//   DefaultMediaSession - MediaSession: videoSink, audioSink, framesIn, lastKeyFrame, expectGeneration, lastFrame,
//     release, setMuted, resetPipeline, pipelineStats, audioStats, audioAvailability.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.3.0 - GSD plan 04-05: media session generation gate and frame observation. The tee records a
//     FrameObservation (useCount, timestamp, recorded = liveFlag bit 0, key frames since the gate) only for units
//     of the gated generation (low byte); expectGeneration resets the observation and forwards the gate to
//     FramePipeline. A resolution change across the gate reaches the decoder as a key frame with the new size and
//     csd, so VideoDecoder's format-change path recreates the codec (no extra reset here).
//   PREVIOUS: v0.2.0 - GSD plan 03-10 GREEN. INVARIANTS: video units go through the key-frame tee into
//     FramePipeline only; audio units go to AudioPipeline only; after release() nothing reaches either pipeline
//     (the decoder outputs are owned by the view model and outlive the session). resetPipeline() is the owner's
//     hook for VideoDecoder.resetEvents (the pipeline must wait for the next key frame again). The password goes
//     only into the two pipelines; nothing is logged here.
//   PREVIOUS: v0.1.0 - GSD plan 03-10 RED: signatures only.
// END_CHANGE_SUMMARY

class DefaultMediaSession(
    credentials: P2pCredentials,
    video: DecodableFrameSink,
    audioOut: AacFrameSink,
    muted: Boolean,
) : MediaSession {

    private val pipeline = FramePipeline(credentials.password, credentials.p2pEncrypt, video)
    private val audio = AudioPipeline(credentials.password, audioOut).also { it.muted = muted }

    @Volatile private var released = false
    @Volatile private var lastKey: KeyFrameObservation? = null

    private val obsLock = Any()
    private var gate: Int? = null
    private var keyCount = 0L
    private var lastObs: FrameObservation? = null

    // START_BLOCK_KEYFRAME_TEE
    override val videoSink: VideoUnitSink = VideoUnitSink { unit ->
        if (released) return@VideoUnitSink
        val info = unit.info
        synchronized(obsLock) {
            val g = gate
            if (g == null || (info.useCount and 0xFF) == (g and 0xFF)) {
                if (info.isKeyFrame) keyCount++
                lastObs = FrameObservation(info.useCount, info.timestampSec, (info.liveFlag and 1) == 1, keyCount)
            }
        }
        if (info.isKeyFrame) lastKey = KeyFrameObservation(info.useCount, info.width, info.height)
        pipeline.onVideoUnit(unit)
    }
    // END_BLOCK_KEYFRAME_TEE

    // START_BLOCK_AUDIO_ROUTE
    override val audioSink: AudioUnitSink = AudioUnitSink { unit ->
        if (released) return@AudioUnitSink
        audio.onAudioUnit(unit)
    }
    // END_BLOCK_AUDIO_ROUTE

    override fun framesIn(): Long = pipeline.stats().framesIn

    override fun lastKeyFrame(): KeyFrameObservation? = lastKey

    // START_CONTRACT: expectGeneration
    //   PURPOSE: Archive generation gate: only units of [useCount] (low byte) are observed and decoded; null = no gate.
    //   INPUTS: { useCount: Int? - generation of the last seek / go-live command }
    //   OUTPUTS: { Unit }
    //   SIDE_EFFECTS: clears lastFrame and the key-frame count; FramePipeline.expectGeneration; ignored after release
    //   LINKS: M-LIVE-SESSION, FramePipeline.expectGeneration, PlaybackController.startAt
    // END_CONTRACT: expectGeneration
    override fun expectGeneration(useCount: Int?) {
        if (released) return
        synchronized(obsLock) {
            gate = useCount
            keyCount = 0
            lastObs = null
        }
        pipeline.expectGeneration(useCount)
    }

    // START_CONTRACT: lastFrame
    //   PURPOSE: Last unit that passed the gate (archive cursor source).
    //   INPUTS: none
    //   OUTPUTS: { FrameObservation? - null before the first unit or right after expectGeneration }
    //   SIDE_EFFECTS: none
    //   LINKS: M-LIVE-SESSION, PlaybackController
    // END_CONTRACT: lastFrame
    override fun lastFrame(): FrameObservation? = synchronized(obsLock) { lastObs }

    // START_CONTRACT: release
    //   PURPOSE: End this session's media plumbing; idempotent.
    //   INPUTS: none
    //   OUTPUTS: { Unit }
    //   SIDE_EFFECTS: later units on both sinks are ignored; the shared decoder outputs are NOT released here
    //   LINKS: M-LIVE-SESSION
    // END_CONTRACT: release
    override fun release() {
        released = true
        audio.muted = true
    }

    // START_CONTRACT: setMuted
    //   PURPOSE: Local mute gate (D-05): frames reach the AAC output only while unmuted.
    //   INPUTS: { muted: Boolean }
    //   OUTPUTS: { Unit }
    //   SIDE_EFFECTS: AudioPipeline.muted; ignored after release
    //   LINKS: M-STREAM-CORE, M-UI-LIVEVIEW
    // END_CONTRACT: setMuted
    fun setMuted(muted: Boolean) {
        if (released) return
        audio.muted = muted
    }

    // Owner hook for VideoDecoder.resetEvents: re-arm the key-frame gate and drop the cached csd.
    fun resetPipeline() {
        if (!released) pipeline.reset()
    }

    fun pipelineStats(): PipelineStats = pipeline.stats()

    fun audioStats(): AudioStats = audio.stats()

    fun audioAvailability(streamingAtMs: Long?, nowMs: Long): Boolean? = audio.availability(streamingAtMs, nowMs)
}
