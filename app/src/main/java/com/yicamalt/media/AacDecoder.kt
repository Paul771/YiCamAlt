// FILE: AacDecoder.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Play live AAC audio: platform MediaCodec (audio/mp4a-latm, async) decodes raw AAC access units to PCM,
//     AudioTrack (configured from the decoder OUTPUT format) plays it.
//   SCOPE: lazy media-audio HandlerThread, codec creation on the first frame and re-creation on an AacConfig change,
//     bounded drop-oldest input queue (AudioFrameQueue), AudioTrack (re)creation on onOutputFormatChanged,
//     non-blocking PCM writes, codec error -> Failed (no retry loop), idempotent bounded release, AudioOutStatus flow.
//   DEPENDS: M-STREAM-CORE (AacConfig, AacFrameSink from AudioPipeline), AudioFrameQueue, android.media.MediaCodec,
//     android.media.AudioTrack, android.os.HandlerThread
//   LINKS: M-MEDIA-AUDIO, V-M-MEDIA-AUDIO
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.yicamalt.stream.AacConfig
import com.yicamalt.stream.AacFrameSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// START_MODULE_MAP
//   AudioOutStatus - Idle | Playing(sampleRate, channels, codecName) | Failed(reason).
//   AacDecoder(queueCapacity) - AacFrameSink over MediaCodec + AudioTrack on the media-audio HandlerThread.
//   AacDecoder.status - StateFlow<AudioOutStatus>.
//   AacDecoder.onAacFrame(config, frame, ptsUs) - non-blocking: queue offer + pump post.
//   AacDecoder.release() - idempotent; frees codec, track and thread (caller waits <= 1 s).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 03-07: created. Unmute creates an AacDecoder, mute releases it (local mute, D-05).
//     INVARIANT: no vendor decoder and no manual resampling; AudioTrack rate/channels come from the codec output
//     format. Logs carry mime, rate, channels, codec name, counts and error class only; never audio bytes.
// END_CHANGE_SUMMARY

sealed interface AudioOutStatus {
    object Idle : AudioOutStatus
    data class Playing(val sampleRate: Int, val channels: Int, val codecName: String) : AudioOutStatus
    // reason = exception simple name, "codec" (onError) or "no-decoder".
    data class Failed(val reason: String) : AudioOutStatus
}

class AacDecoder(private val queueCapacity: Int = AudioFrameQueue.DEFAULT_CAPACITY) : AacFrameSink {

    private class AudioUnit(val config: AacConfig, val frame: ByteArray, val ptsUs: Long)

    private val _status = MutableStateFlow<AudioOutStatus>(AudioOutStatus.Idle)
    val status: StateFlow<AudioOutStatus> = _status.asStateFlow()

    private val queue = AudioFrameQueue<AudioUnit>(queueCapacity)

    private val threadLock = Any()
    private var thread: HandlerThread? = null
    @Volatile private var handler: Handler? = null
    @Volatile private var released = false
    @Volatile private var failed = false

    // Audio-thread state (touched only on the HandlerThread).
    private var codec: MediaCodec? = null
    private var codecName: String = ""
    private var currentConfig: AacConfig? = null
    private var track: AudioTrack? = null
    private var pending: AudioUnit? = null
    private val freeInputs = ArrayDeque<Int>()

    // START_CONTRACT: onAacFrame
    //   PURPOSE: Accept one raw AAC access unit (no ADTS header) with its stream config.
    //   INPUTS: { config: AacConfig - object type / rate / channels / csd-0; frame: ByteArray - raw AAC AU;
    //     ptsUs: Long - presentation time }
    //   OUTPUTS: { Unit - never blocks the caller }
    //   SIDE_EFFECTS: starts the media-audio thread on first use; queue offer (oldest dropped beyond capacity);
    //     posts a pump. No-op after release() or a decoder failure.
    //   LINKS: M-MEDIA-AUDIO, M-STREAM-CORE
    // END_CONTRACT: onAacFrame
    override fun onAacFrame(config: AacConfig, frame: ByteArray, ptsUs: Long) {
        if (released || failed) return
        val h = ensureThread() ?: return
        queue.offer(AudioUnit(config, frame, ptsUs))
        h.post { pump() }
    }

    // START_CONTRACT: release
    //   PURPOSE: Stop all audio work (mute / session end); safe to call more than once.
    //   INPUTS: none
    //   OUTPUTS: { Unit - returns after teardown ran on the audio thread or after at most 1 s }
    //   SIDE_EFFECTS: codec and AudioTrack stopped + released, HandlerThread quit; BLOCK_AUDIO_RELEASE log
    //   LINKS: M-MEDIA-AUDIO
    // END_CONTRACT: release
    fun release() {
        // START_BLOCK_AUDIO_RELEASE
        val h: Handler?
        synchronized(threadLock) {
            if (released) return
            released = true
            h = handler
        }
        if (h != null) {
            runOnAudio(h) {
                queue.clear()
                pending = null
                releaseCodecAndTrack("release")
            }
        }
        synchronized(threadLock) {
            thread?.quitSafely()
            thread = null
            handler = null
        }
        if (_status.value !is AudioOutStatus.Failed) _status.value = AudioOutStatus.Idle
        Timber.i("[Media][audio][BLOCK_AUDIO_RELEASE] dropped=%d", queue.dropped)
        // END_BLOCK_AUDIO_RELEASE
    }

    // START_BLOCK_AUDIO_PUMP
    // Audio thread: move queued frames into free input buffers; (re)create the codec when the config changes.
    private fun pump() {
        if (released || failed) return
        while (true) {
            val unit = pending ?: queue.poll() ?: return
            pending = unit
            if (codec != null && unit.config != currentConfig) {
                releaseCodecAndTrack("config-change")
            }
            if (codec == null && !createDecoder(unit.config)) {
                pending = null
                return
            }
            val index = freeInputs.removeFirstOrNull() ?: return
            pending = null
            if (!feed(index, unit)) return
        }
    }

    private fun feed(index: Int, unit: AudioUnit): Boolean {
        val c = codec ?: return false
        try {
            val buffer = c.getInputBuffer(index)
            if (buffer == null) {
                freeInputs.addFirst(index)
                return true
            }
            if (unit.frame.size > buffer.capacity()) {
                Timber.w("[Media][audio][feed] bytes=%d capacity=%d result=dropped", unit.frame.size, buffer.capacity())
                freeInputs.addFirst(index)
                return true
            }
            buffer.clear()
            buffer.put(unit.frame)
            c.queueInputBuffer(index, 0, unit.frame.size, unit.ptsUs, 0)
            return true
        } catch (e: IllegalStateException) {
            // MediaCodec.CodecException is an IllegalStateException.
            fail(e.javaClass.simpleName)
            return false
        }
    }
    // END_BLOCK_AUDIO_PUMP

    // START_BLOCK_AUDIO_DECODER_CREATE
    private fun createDecoder(config: AacConfig): Boolean {
        var created: MediaCodec? = null
        try {
            val format = MediaFormat.createAudioFormat(MIME_AAC, config.sampleRate, config.channels)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(config.csd0))
            format.setInteger(MediaFormat.KEY_IS_ADTS, 0)
            val c = MediaCodec.createDecoderByType(MIME_AAC)
            created = c
            // Callback before configure so the codec runs in async mode on the audio thread.
            c.setCallback(callback, handler)
            c.configure(format, null, null, 0)
            c.start()
            codec = c
            codecName = c.name
            currentConfig = config
            freeInputs.clear()
        } catch (e: Exception) {
            runCatching { created?.release() }
            val reason = if (e is java.io.IOException || e is IllegalArgumentException) "no-decoder" else e.javaClass.simpleName
            Timber.w(
                "[Media][audio][BLOCK_AUDIO_DECODER_CREATE] mime=%s result=failed error=%s",
                MIME_AAC, e.javaClass.simpleName,
            )
            markFailed(reason)
            return false
        }
        Timber.i(
            "[Media][audio][BLOCK_AUDIO_DECODER_CREATE] mime=%s name=%s rate=%d ch=%d",
            MIME_AAC, codecName, config.sampleRate, config.channels,
        )
        return true
    }
    // END_BLOCK_AUDIO_DECODER_CREATE

    // START_BLOCK_AUDIO_TRACK
    // Audio thread: (re)create the AudioTrack from the decoder output format (rate / channel count).
    private fun onOutputFormat(format: MediaFormat) {
        val rate: Int
        val channels: Int
        try {
            rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } catch (e: RuntimeException) {
            Timber.w("[Media][audio][BLOCK_AUDIO_TRACK] result=unreadable error=%s", e.javaClass.simpleName)
            return
        }
        val mask = when (channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> {
                Timber.w("[Media][audio][BLOCK_AUDIO_TRACK] ch=%d result=unsupported", channels)
                fail("channels")
                return
            }
        }
        releaseTrack()
        try {
            val minBuf = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
            val bufferBytes = maxOf(minBuf, rate * channels * 2 / 4)
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(mask)
                        .build(),
                )
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = t
            t.play()
        } catch (e: Exception) {
            Timber.w("[Media][audio][BLOCK_AUDIO_TRACK] result=failed error=%s", e.javaClass.simpleName)
            fail(e.javaClass.simpleName)
            return
        }
        _status.value = AudioOutStatus.Playing(rate, channels, codecName)
        Timber.i("[Media][audio][BLOCK_AUDIO_TRACK] rate=%d ch=%d", rate, channels)
    }

    private fun writePcm(mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        val t = track
        val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
        if (t != null && info.size > 0 && !isConfig) {
            val out = mc.getOutputBuffer(index)
            if (out != null) {
                out.position(info.offset)
                out.limit(info.offset + info.size)
                // Non-blocking: a full track drops the rest of this buffer instead of stalling the codec.
                t.write(out, info.size, AudioTrack.WRITE_NON_BLOCKING)
            }
        }
        mc.releaseOutputBuffer(index, false)
    }
    // END_BLOCK_AUDIO_TRACK

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
            if (mc !== codec) return
            freeInputs.addLast(index)
            pump()
        }

        override fun onOutputBufferAvailable(mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (mc !== codec) return
            try {
                writePcm(mc, index, info)
            } catch (e: IllegalStateException) {
                fail(e.javaClass.simpleName)
            }
        }

        override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
            if (mc !== codec) return
            Timber.w("[Media][audio][onError] transient=%b recoverable=%b", e.isTransient, e.isRecoverable)
            fail("codec")
        }

        override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) {
            if (mc !== codec) return
            onOutputFormat(format)
        }
    }

    // Audio thread: terminal failure; frames are dropped from now on (no retry loop).
    private fun fail(reason: String) {
        releaseCodecAndTrack("error")
        queue.clear()
        pending = null
        markFailed(reason)
    }

    private fun markFailed(reason: String) {
        failed = true
        _status.value = AudioOutStatus.Failed(reason)
        Timber.e("[Media][audio][failed] reason=%s", reason)
    }

    // Never throws. Callbacks of an old codec are ignored by identity.
    private fun releaseCodecAndTrack(why: String) {
        val c = codec
        codec = null
        currentConfig = null
        freeInputs.clear()
        if (c != null) {
            runCatching { c.stop() }
            runCatching { c.release() }
        }
        releaseTrack()
        Timber.d("[Media][audio][releaseCodecAndTrack] why=%s", why)
    }

    private fun releaseTrack() {
        val t = track ?: return
        track = null
        runCatching { t.pause() }
        runCatching { t.flush() }
        runCatching { t.release() }
    }

    private fun ensureThread(): Handler? = synchronized(threadLock) {
        if (released) return null
        handler ?: HandlerThread("media-audio").let { t ->
            t.start()
            thread = t
            Handler(t.looper).also { handler = it }
        }
    }

    // Run block on the audio thread and wait for it (bounded).
    private fun runOnAudio(h: Handler, block: () -> Unit) {
        if (Looper.myLooper() == h.looper) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        val posted = h.post {
            try {
                block()
            } finally {
                latch.countDown()
            }
        }
        if (posted) latch.await(BLOCKING_WAIT_MS, TimeUnit.MILLISECONDS)
    }

    private companion object {
        const val MIME_AAC = "audio/mp4a-latm"
        const val BLOCKING_WAIT_MS = 1_000L
    }
}
