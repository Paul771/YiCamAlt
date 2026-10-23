// FILE: DefaultMediaSession.kt
// VERSION: 0.2.0
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
import com.yicamalt.stream.FramePipeline
import com.yicamalt.stream.PipelineStats
import com.yicamalt.stream.VideoUnitSink

// START_MODULE_MAP
//   DefaultMediaSession - MediaSession: videoSink, audioSink, framesIn, lastKeyFrame, release, setMuted,
//     resetPipeline, pipelineStats, audioStats, audioAvailability.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.2.0 - GSD plan 03-10 GREEN. INVARIANTS: video units go through the key-frame tee into
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

    // START_BLOCK_KEYFRAME_TEE
    override val videoSink: VideoUnitSink = VideoUnitSink { unit ->
        if (released) return@VideoUnitSink
        val info = unit.info
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
