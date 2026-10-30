// FILE: DefaultMediaSessionTest.kt
// VERSION: 0.2.0
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
import com.yicamalt.stream.FrameInfo
import com.yicamalt.stream.FrameObservation
import com.yicamalt.stream.VideoUnit
import com.yicamalt.test.RedactionFixtures
import com.yicamalt.test.RedactionScanner
import com.yicamalt.test.TraceRecorder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

// START_MODULE_MAP
//   RecordingVideo - DecodableFrameSink collecting frames.
//   RecordingAudio - AacFrameSink counting frames.
//   unitOf(...) - VideoUnit with explicit liveFlag, timestamp, size and payload (archive / resolution scenarios).
//   DefaultMediaSessionTest - one test per behaviour bullet of plan 03-10 Task 1 plus pipeline re-arm, plus the
//     04-05 generation gate / frame observation / resolution change scenarios.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.2.0 - GSD plan 04-05: generation gate and FrameObservation tests; resolution change across the
//     gate reaches the decoder sink with new size and csd (VideoDecoder.formatChanged path).
//   PREVIOUS: v0.1.0 - GSD plan 03-10 RED.
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

    // START_BLOCK_GENERATION_GATE_TESTS
    private fun unitOf(
        key: Boolean, seq: Int, useCount: Int, liveFlag: Int, ts: Long,
        width: Int = 1920, height: Int = 1080, payload: ByteArray = if (key) SYNTH_I264 else SYNTH_P264,
    ) = VideoUnit(
        if (key) 2 else 3,
        FrameInfo(
            codecId = 78, flags = if (key) 1 else 0, liveFlag = liveFlag, onlineNum = 1, useCount = useCount, seq = seq,
            width = width, height = height, timestampSec = ts, isDay = 1, cover = 0, outLoss = 0, inLoss = 0,
            timestampMs = 0,
        ),
        payload,
    )

    @Test
    fun `lastFrame is null before any unit, then follows a recorded key unit`() {
        val s = session()
        assertNull(s.lastFrame())
        s.videoSink.onVideoUnit(unitOf(true, seq = 1, useCount = 7, liveFlag = 1, ts = 1000))
        assertEquals(FrameObservation(7, 1000, true, 1), s.lastFrame())
    }

    @Test
    fun `gate filters observation`() {
        val s = session()
        s.videoSink.onVideoUnit(unitOf(true, seq = 1, useCount = 7, liveFlag = 1, ts = 1000))
        s.expectGeneration(8)
        assertNull(s.lastFrame())
        s.videoSink.onVideoUnit(unitOf(true, seq = 2, useCount = 7, liveFlag = 1, ts = 1001))
        assertNull(s.lastFrame())
        s.videoSink.onVideoUnit(unitOf(true, seq = 3, useCount = 8, liveFlag = 0, ts = 2000))
        assertEquals(FrameObservation(8, 2000, false, 1), s.lastFrame())
        s.videoSink.onVideoUnit(unitOf(false, seq = 4, useCount = 8, liveFlag = 0, ts = 2001))
        assertEquals(FrameObservation(8, 2001, false, 1), s.lastFrame())
    }

    @Test
    fun `expectGeneration reaches the pipeline`() {
        val s = session()
        s.expectGeneration(5)
        val before = s.pipelineStats().dropped
        s.videoSink.onVideoUnit(keyUnit(seq = 1, useCount = 4))
        assertEquals(before + 1, s.pipelineStats().dropped)
        assertEquals(0, video.frames.size)
    }

    @Test
    fun `after release expectGeneration and units are ignored`() {
        val s = session()
        s.videoSink.onVideoUnit(unitOf(true, seq = 1, useCount = 7, liveFlag = 1, ts = 1000))
        s.release()
        s.expectGeneration(9)
        s.videoSink.onVideoUnit(unitOf(true, seq = 2, useCount = 9, liveFlag = 1, ts = 1001))
        assertEquals(FrameObservation(7, 1000, true, 1), s.lastFrame())
        assertEquals(1, video.frames.size)
    }

    @Test
    fun `resolution change across the gate reaches the decoder sink`() {
        val s = session()
        s.videoSink.onVideoUnit(unitOf(true, seq = 1, useCount = 2, liveFlag = 0, ts = 10, width = 640, height = 360))
        assertEquals(640, video.frames.last().width)
        assertEquals(360, video.frames.last().height)
        val oldCsd = video.frames.last().csd
        s.expectGeneration(3)
        val sc4 = byteArrayOf(0, 0, 0, 1)
        val sps1080 = byteArrayOf(0x67, 0x64, 0x00, 0x2A) + ByteArray(16) { (0x30 + it).toByte() }
        val pps1080 = byteArrayOf(0x68, 0xEE.toByte(), 0x3C, 0x81.toByte())
        val idr = byteArrayOf(0x65, 0x88.toByte(), 0x84.toByte()) + ByteArray(57) { (0x21 + it).toByte() }
        val key1080 = sc4 + sps1080 + sc4 + pps1080 + sc4 + idr
        s.videoSink.onVideoUnit(
            unitOf(true, seq = 50, useCount = 3, liveFlag = 1, ts = 20, width = 1920, height = 1080, payload = key1080),
        )
        val f = video.frames.last()
        assertEquals(2, video.frames.size)
        assertTrue(f.isKeyFrame)
        assertEquals(1920, f.width)
        assertEquals(1080, f.height)
        assertEquals(2, f.csd.size)
        assertArrayEquals(sc4 + sps1080, f.csd[0])
        assertArrayEquals(sc4 + pps1080, f.csd[1])
        assertFalse(f.csd[0].contentEquals(oldCsd[0]))
    }
    // END_BLOCK_GENERATION_GATE_TESTS
}
