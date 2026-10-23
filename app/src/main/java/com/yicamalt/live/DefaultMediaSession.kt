// FILE: DefaultMediaSession.kt
// VERSION: 0.1.0
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
import com.yicamalt.stream.AudioStats
import com.yicamalt.stream.AudioUnitSink
import com.yicamalt.stream.DecodableFrameSink
import com.yicamalt.stream.PipelineStats
import com.yicamalt.stream.VideoUnitSink

// START_MODULE_MAP
//   DefaultMediaSession - MediaSession: videoSink, audioSink, framesIn, lastKeyFrame, release, setMuted,
//     resetPipeline, pipelineStats, audioStats, audioAvailability.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 03-10 RED: signatures only.
// END_CHANGE_SUMMARY

class DefaultMediaSession(
    credentials: P2pCredentials,
    video: DecodableFrameSink,
    audioOut: AacFrameSink,
    muted: Boolean,
) : MediaSession {
    override val videoSink: VideoUnitSink = VideoUnitSink { error("RED stub") }
    override val audioSink: AudioUnitSink = AudioUnitSink { error("RED stub") }

    override fun framesIn(): Long = error("RED stub")

    override fun lastKeyFrame(): KeyFrameObservation? = error("RED stub")

    override fun release() {
        error("RED stub")
    }

    fun setMuted(muted: Boolean) {
        error("RED stub")
    }

    fun resetPipeline() {
        error("RED stub")
    }

    fun pipelineStats(): PipelineStats = error("RED stub")

    fun audioStats(): AudioStats = error("RED stub")

    fun audioAvailability(streamingAtMs: Long?, nowMs: Long): Boolean? = error("RED stub")
}
