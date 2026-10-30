// FILE: DefaultMediaSessionTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Executable contract of the production MediaSession: key-frame tee, video / audio routing, mute gate,
//     counters, pipeline re-arm and release.
//   SCOPE: plain synthetic H.264 and ADTS units (LiveFakes builders), recording sinks; no Android, no threads.
//   DEPENDS: M-LIVE-SESSION (DefaultMediaSession), M-STREAM-CORE (FramePipeline, AudioPipeline), LiveFakes,
//     TraceRecorder, RedactionScanner
//   LINKS: V-M-LIVE-SESSION, M-LIVE-SESSION
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.live

import com.yicamalt.stream.AacConfig
import com.yicamalt.stream.AacFrameSink
import com.yicamalt.stream.DecodableFrame
import com.yicamalt.stream.DecodableFrameSink
import com.yicamalt.test.RedactionFixtures
import com.yicamalt.test.RedactionScanner
import com.yicamalt.test.TraceRecorder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

// START_MODULE_MAP
//   RecordingVideo - DecodableFrameSink collecting frames.
//   RecordingAudio - AacFrameSink counting frames.
//   DefaultMediaSessionTest - one test per behaviour bullet of plan 03-10 Task 1 plus pipeline re-arm.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 03-10 RED.
// END_CHANGE_SUMMARY

class DefaultMediaSessionTest {

    private class RecordingVideo : DecodableFrameSink {
        val frames = mutableListOf<DecodableFrame>()
        var unsupported = 0
        override fun onFrame(frame: DecodableFrame) {
            frames += frame
        }
        override fun onUnsupportedCodec(codecId: Int) {
            unsupported++
        }
    }

    private class RecordingAudio : AacFrameSink {
        var frames = 0
        override fun onAacFrame(config: AacConfig, frame: ByteArray, ptsUs: Long) {
            frames++
        }
    }

    private val recorder = TraceRecorder()
    private val video = RecordingVideo()
    private val audio = RecordingAudio()

    @BeforeEach
    fun setUp() = recorder.plant()

    @AfterEach
    fun tearDown() {
        RedactionScanner().assertClean(recorder.bufferText(), extraLiterals = RedactionFixtures.ALL_SECRETS + LIVE_SECRETS)
        recorder.unplant()
        recorder.reset()
    }

    private fun session(muted: Boolean = true) = DefaultMediaSession(liveCreds(), video, audio, muted)

    @Test
    fun `key unit is observed and decoded, P unit keeps the observation`() {
        val s = session()
        assertNull(s.lastKeyFrame())
        s.videoSink.onVideoUnit(keyUnit(seq = 10, useCount = 3, width = 1920, height = 1080))
        assertEquals(KeyFrameObservation(3, 1920, 1080), s.lastKeyFrame())
        assertEquals(1, video.frames.size)
        assertTrue(video.frames[0].isKeyFrame)

        s.videoSink.onVideoUnit(pUnit(seq = 11, useCount = 3, width = 640, height = 360))
        assertEquals(KeyFrameObservation(3, 1920, 1080), s.lastKeyFrame())
        assertEquals(2, video.frames.size)
    }

    @Test
    fun `framesIn follows pipeline stats`() {
        val s = session()
        s.videoSink.onVideoUnit(keyUnit(seq = 1))
        s.videoSink.onVideoUnit(pUnit(seq = 2))
        s.videoSink.onVideoUnit(pUnit(seq = 3))
        assertEquals(3L, s.framesIn())
        assertEquals(s.pipelineStats().framesIn, s.framesIn())
    }

    @Test
    fun `audio units reach only the audio pipeline`() {
        val s = session()
        s.audioSink.onAudioUnit(aacUnit())
        assertEquals(1L, s.audioStats().unitsIn)
        assertEquals(0, video.frames.size)
        assertEquals(0L, s.pipelineStats().framesIn)
        assertEquals(0L, s.framesIn())
        assertEquals(true, s.audioAvailability(null, 0))
    }

    @Test
    fun `muted at creation delivers nothing, unmute delivers the next unit`() {
        val s = session(muted = true)
        s.audioSink.onAudioUnit(aacUnit())
        assertEquals(0, audio.frames)
        s.setMuted(false)
        s.audioSink.onAudioUnit(aacUnit())
        assertEquals(1, audio.frames)
        s.setMuted(true)
        s.audioSink.onAudioUnit(aacUnit())
        assertEquals(1, audio.frames)
    }

    @Test
    fun `unmuted at creation delivers at once`() {
        val s = session(muted = false)
        s.audioSink.onAudioUnit(aacUnit())
        assertEquals(1, audio.frames)
    }

    @Test
    fun `after release units on both sinks are ignored`() {
        val s = session(muted = false)
        s.videoSink.onVideoUnit(keyUnit(seq = 1))
        s.audioSink.onAudioUnit(aacUnit())
        s.release()
        s.release()
        s.videoSink.onVideoUnit(keyUnit(seq = 5, useCount = 4, width = 640, height = 360))
        s.audioSink.onAudioUnit(aacUnit())
        assertEquals(1, video.frames.size)
        assertEquals(1, audio.frames)
        assertEquals(1L, s.framesIn())
        assertEquals(1L, s.audioStats().unitsIn)
        assertEquals(KeyFrameObservation(3, 1920, 1080), s.lastKeyFrame())
    }

    @Test
    fun `resetPipeline re-arms the key-frame gate`() {
        val s = session()
        s.videoSink.onVideoUnit(keyUnit(seq = 1))
        s.resetPipeline()
        s.videoSink.onVideoUnit(pUnit(seq = 2))
        assertEquals(1, video.frames.size)
        s.videoSink.onVideoUnit(keyUnit(seq = 3))
        assertEquals(2, video.frames.size)
    }
}
