// FILE: VideoDecoder.kt
// VERSION: 0.2.0
// START_MODULE_CONTRACT
//   PURPOSE: Render a DecodableFrame stream (H.264 / H.265) to a Surface with MediaCodec in async mode.
//   SCOPE: decoder creation on the first key frame (csd from that frame), input feeding from the FrameQueue,
//     render on release, reset on codec error / surface loss / queue overflow / format change, explicit
//     NoDecoder / Unsupported / Failed outcomes, DecoderStatus StateFlow for the debug screen.
//   DEPENDS: M-STREAM-CORE (DecodableFrame, DecodableFrameSink), DecoderPolicy, DecoderCatalog,
//     android.media.MediaCodec, android.os.HandlerThread
//   LINKS: M-MEDIA-RENDER, V-M-MEDIA-RENDER
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import com.yicamalt.stream.DecodableFrame
import com.yicamalt.stream.DecodableFrameSink
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// START_MODULE_MAP
//   VideoDecoder(catalog) - DecodableFrameSink over MediaCodec (async, dedicated "media-decoder" HandlerThread).
//   VideoDecoder.status - StateFlow<DecoderStatus> (Idle, Ready, Rendering, NoDecoder, Unsupported, Failed).
//   VideoDecoder.resetEvents - SharedFlow<Unit>; owner calls FramePipeline.reset() on each.
//   VideoDecoder.attachSurface(surface) / detachSurface() - surface lifecycle; detach releases the codec only.
//   VideoDecoder.onFrame(frame) / onUnsupportedCodec(codecId) - DecodableFrameSink.
//   VideoDecoder.release() - idempotent teardown.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.2.0 - device fix: no spurious reason=queue-overflow reset at stream start. The FrameQueue is
//     told codecRunning=true right after codec start and false on every releaseCodec; while false it holds up to
//     HOLD_CAPACITY frames (latest key frame chain, BLOCK_STARTUP_HOLD debug log) instead of resetting.
//   PREVIOUS: v0.1.0 - GSD plan 02-08: created. All codec work runs on the decoder HandlerThread; callers only
//     offer to the FrameQueue and post a pump. Extra reset reasons beyond the plan: format-change (mime, size or csd
//     differ on a key frame, e.g. SD/HD switch) and oversize-frame (access unit larger than the input buffer).
//     INVARIANT: logs carry mime, decoder name, flags, sizes, counts and ms only; never frame bytes.
// END_CHANGE_SUMMARY

data class VideoSize(val width: Int, val height: Int)

fun visibleSize(width: Int, height: Int, cropLeft: Int?, cropRight: Int?, cropTop: Int?, cropBottom: Int?): VideoSize? =
    error("RED stub")

class VideoDecoder(
    private val catalog: (String, Int, Int) -> DecoderChoice = DecoderCatalog::find,
) : DecodableFrameSink {

    private val _status = MutableStateFlow<DecoderStatus>(DecoderStatus.Idle)
    val status: StateFlow<DecoderStatus> = _status.asStateFlow()

    private val _resetEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val resetEvents: SharedFlow<Unit> = _resetEvents.asSharedFlow()

    private val queue = FrameQueue(QUEUE_CAPACITY, HOLD_CAPACITY)
    private val errors = ResetCounter(MAX_CODEC_ERRORS)

    private val threadLock = Any()
    private var thread: HandlerThread? = null
    @Volatile private var handler: Handler? = null
    @Volatile private var surface: Surface? = null
    @Volatile private var released = false
    @Volatile private var failed = false
    @Volatile private var blockedMime: String? = null
    @Volatile private var totalResets = 0

    // Decoder-thread state (touched only on the HandlerThread).
    private var codec: MediaCodec? = null
    private var ready: DecoderStatus.Ready? = null
    private var pending: DecodableFrame? = null
    private val freeInputs = ArrayDeque<Int>()
    private val pts = PtsGuard()
    private var createdAtNs = 0L
    private var createdMime: String? = null
    private var createdWidth = 0
    private var createdHeight = 0
    private var createdCsd: Map<String, ByteArray> = emptyMap()
    private var rendered = 0L
    private var windowStartNs = 0L
    private var windowFrames = 0L

    // START_CONTRACT: attachSurface
    //   PURPOSE: Give the decoder an output Surface; a codec is created on the next key frame.
    //   INPUTS: { surface: Surface - valid output surface (SurfaceHolder.Callback.surfaceCreated) }
    //   OUTPUTS: { Unit }
    //   SIDE_EFFECTS: starts the "media-decoder" HandlerThread on first use; releases a codec bound to an old surface
    //   LINKS: M-MEDIA-RENDER
    // END_CONTRACT: attachSurface
    fun attachSurface(surface: Surface) {
        if (released) return
        val h = ensureThread()
        runOnDecoder(h) {
            if (codec != null) {
                releaseCodec("surface-lost")
                logReset("surface-lost")
            }
            queue.clear()
            pending = null
            this.surface = surface
        }
    }

    // START_CONTRACT: detachSurface
    //   PURPOSE: Surface is going away: stop and release the codec, keep the P2P session and this object usable.
    //   INPUTS: none
    //   OUTPUTS: { Unit - returns after the codec stopped using the surface (bounded wait) }
    //   SIDE_EFFECTS: codec released, queue cleared (key-frame gate closed); BLOCK_DECODER_RESET reason=surface-lost
    //   LINKS: M-MEDIA-RENDER
    // END_CONTRACT: detachSurface
    fun detachSurface() {
        surface = null
        val h = handler ?: return
        runOnDecoder(h) {
            queue.clear()
            pending = null
            if (codec != null) {
                releaseCodec("surface-lost")
                logReset("surface-lost")
            }
            if (!failed && _status.value.let { it is DecoderStatus.Ready || it is DecoderStatus.Rendering }) {
                _status.value = DecoderStatus.Idle
            }
        }
    }

    // START_CONTRACT: onFrame
    //   PURPOSE: Accept one decodable access unit from the pipeline.
    //   INPUTS: { frame: DecodableFrame - Annex-B access unit with csd on key frames }
    //   OUTPUTS: { Unit }
    //   SIDE_EFFECTS: queue offer; posts a pump to the decoder thread; queue overflow with a running codec ->
    //     BLOCK_DECODER_RESET reason=queue-overflow + resetEvents; overflow while no codec runs (being created) ->
    //     BLOCK_STARTUP_HOLD (frames trimmed to the latest key frame), no reset
    //   LINKS: M-MEDIA-RENDER, M-STREAM-CORE
    // END_CONTRACT: onFrame
    override fun onFrame(frame: DecodableFrame) {
        if (released || failed) return
        if (blockedMime == frame.codec.mime) return
        if (surface == null) return
        val h = handler ?: return
        when (val r = queue.offer(frame)) {
            OfferResult.DROPPED_NO_KEY -> return
            OfferResult.ACCEPTED_DROPPED_OLDEST -> {
                logReset("queue-overflow")
                _resetEvents.tryEmit(Unit)
            }
            OfferResult.HELD_TRIMMED, OfferResult.DROPPED_HOLD_FULL ->
                // No codec runs yet (being created): the queue keeps the latest key-frame chain; not a reset.
                Timber.d("[Media][decoder][BLOCK_STARTUP_HOLD] result=%s queued=%d", r.name, queue.size)
            OfferResult.ACCEPTED -> Unit
        }
        h.post { pump() }
    }

    // START_CONTRACT: onUnsupportedCodec
    //   PURPOSE: Pipeline saw a codec id that is neither H.264 nor H.265.
    //   INPUTS: { codecId: Int - frame-head codec id }
    //   OUTPUTS: { Unit }
    //   SIDE_EFFECTS: status Unsupported(codecId)
    //   LINKS: M-MEDIA-RENDER
    // END_CONTRACT: onUnsupportedCodec
    override fun onUnsupportedCodec(codecId: Int) {
        Timber.w("[Media][decoder][onUnsupportedCodec] codecId=%d", codecId)
        _status.value = DecoderStatus.Unsupported(codecId)
    }

    // START_CONTRACT: release
    //   PURPOSE: Tear everything down; safe to call more than once.
    //   INPUTS: none
    //   OUTPUTS: { Unit }
    //   SIDE_EFFECTS: codec stopped and released, HandlerThread quit
    //   LINKS: M-MEDIA-RENDER
    // END_CONTRACT: release
    fun release() {
        if (released) return
        released = true
        surface = null
        val h = handler
        if (h != null) {
            runOnDecoder(h) {
                queue.clear()
                pending = null
                releaseCodec("release")
            }
        }
        synchronized(threadLock) {
            thread?.quitSafely()
            thread = null
            handler = null
        }
    }

    // START_BLOCK_PUMP
    // Decoder thread: move queued frames into free input buffers, creating the codec on a key frame.
    private fun pump() {
        if (released || failed) return
        while (true) {
            val frame = pending ?: queue.poll() ?: return
            pending = frame
            if (codec != null && frame.isKeyFrame && formatChanged(frame)) {
                releaseCodec("format-change")
                logReset("format-change")
            }
            if (codec == null) {
                if (!frame.isKeyFrame) {
                    pending = null
                    continue
                }
                if (!createDecoder(frame)) {
                    pending = null
                    return
                }
            }
            val index = freeInputs.removeFirstOrNull() ?: return
            pending = null
            if (!feed(index, frame)) return
        }
    }
    // END_BLOCK_PUMP

    private fun formatChanged(frame: DecodableFrame): Boolean {
        if (frame.codec.mime != createdMime) return true
        if (frame.width > 0 && frame.height > 0 && (frame.width != createdWidth || frame.height != createdHeight)) return true
        val csd = CsdBuilder.csdFor(frame)
        if (csd.isEmpty() || createdCsd.isEmpty()) return false
        if (csd.keys != createdCsd.keys) return true
        return csd.any { (k, v) -> !v.contentEquals(createdCsd[k]) }
    }

    // START_BLOCK_CREATE_DECODER
    private fun createDecoder(frame: DecodableFrame): Boolean {
        val out = surface ?: return false
        val mime = frame.codec.mime
        val w = if (frame.width > 0) frame.width else DEFAULT_WIDTH
        val h = if (frame.height > 0) frame.height else DEFAULT_HEIGHT
        // catalog defaults to DecoderCatalog.find (MediaCodecList); injectable for tests.
        val choice = catalog(mime, w, h)
        val name = choice.name
        if (name == null) {
            noDecoder(mime)
            return false
        }
        val csd = CsdBuilder.csdFor(frame)
        var created: MediaCodec? = null
        try {
            val format = MediaFormat.createVideoFormat(mime, w, h)
            csd.forEach { (key, bytes) -> format.setByteBuffer(key, java.nio.ByteBuffer.wrap(bytes)) }
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(w * h, frame.annexB.size * 2))
            if (choice.lowLatency && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            val c = MediaCodec.createByCodecName(name)
            created = c
            c.setCallback(callback, handler)
            c.configure(format, out, null, 0)
            c.start()
            codec = c
            // From now on a queue overflow means this codec fell behind (reset); before, frames were held.
            queue.codecRunning = true
        } catch (e: Exception) {
            Timber.w("[Media][decoder][BLOCK_CREATE_DECODER] mime=%s name=%s result=failed error=%s", mime, name, e.javaClass.simpleName)
            runCatching { created?.release() }
            codecError()
            return false
        }
        freeInputs.clear()
        pts.reset()
        createdAtNs = System.nanoTime()
        createdMime = mime
        createdWidth = w
        createdHeight = h
        createdCsd = csd
        rendered = 0
        windowFrames = 0
        windowStartNs = 0
        val r = DecoderStatus.Ready(mime, name, choice.hardware, choice.lowLatency)
        ready = r
        _status.value = r
        Timber.i(
            "[Media][decoder][BLOCK_CREATE_DECODER] mime=%s name=%s hw=%b lowLatency=%b size=%dx%d",
            mime, name, choice.hardware, choice.lowLatency, w, h,
        )
        return true
    }
    // END_BLOCK_CREATE_DECODER

    // START_BLOCK_NO_DECODER
    private fun noDecoder(mime: String) {
        blockedMime = mime
        queue.clear()
        pending = null
        _status.value = DecoderStatus.NoDecoder(mime)
        Timber.w("[Media][decoder][BLOCK_NO_DECODER] mime=%s", mime)
    }
    // END_BLOCK_NO_DECODER

    // START_BLOCK_FEED_INPUT
    // Returns false when the codec was reset while feeding.
    private fun feed(index: Int, frame: DecodableFrame): Boolean {
        val c = codec ?: return false
        try {
            val buffer = c.getInputBuffer(index)
            if (buffer == null) {
                freeInputs.addFirst(index)
                return true
            }
            if (frame.annexB.size > buffer.capacity()) {
                Timber.w("[Media][decoder][feed] bytes=%d capacity=%d result=dropped", frame.annexB.size, buffer.capacity())
                freeInputs.addFirst(index)
                queue.clear()
                logReset("oversize-frame")
                _resetEvents.tryEmit(Unit)
                return true
            }
            buffer.clear()
            buffer.put(frame.annexB)
            val flags = if (frame.isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            c.queueInputBuffer(index, 0, frame.annexB.size, pts.next(frame.ptsUs), flags)
            return true
        } catch (e: IllegalStateException) {
            // MediaCodec.CodecException is an IllegalStateException.
            codecError()
            return false
        }
    }
    // END_BLOCK_FEED_INPUT

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
            if (mc !== codec) return
            freeInputs.addLast(index)
            pump()
        }

        override fun onOutputBufferAvailable(mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (mc !== codec) return
            try {
                val render = info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                if (render) {
                    mc.releaseOutputBuffer(index, true)
                    onRendered()
                } else {
                    mc.releaseOutputBuffer(index, false)
                }
            } catch (e: IllegalStateException) {
                codecError()
            }
        }

        override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
            if (mc !== codec) return
            Timber.w("[Media][decoder][onError] transient=%b recoverable=%b", e.isTransient, e.isRecoverable)
            codecError()
        }

        override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) = Unit
    }

    // START_BLOCK_FIRST_RENDER
    private fun onRendered() {
        val r = ready ?: return
        val now = System.nanoTime()
        rendered += 1
        errors.onRendered()
        if (rendered == 1L) {
            Timber.i("[Media][decoder][BLOCK_FIRST_RENDER] ms=%d", (now - createdAtNs) / 1_000_000)
            windowStartNs = now
            windowFrames = 0
            _status.value = DecoderStatus.Rendering(r, rendered, 0.0)
            return
        }
        windowFrames += 1
        val elapsed = now - windowStartNs
        if (elapsed >= FPS_WINDOW_NS) {
            val fps = windowFrames * 1_000_000_000.0 / elapsed
            windowStartNs = now
            windowFrames = 0
            _status.value = DecoderStatus.Rendering(r, rendered, fps)
        }
    }
    // END_BLOCK_FIRST_RENDER

    // START_BLOCK_DECODER_RESET
    private fun codecError() {
        releaseCodec("codec-error")
        queue.clear()
        pending = null
        val streak = errors.onError()
        logReset("codec-error")
        _resetEvents.tryEmit(Unit)
        if (errors.exhausted) {
            failed = true
            _status.value = DecoderStatus.Failed(streak)
            Timber.e("[Media][decoder][BLOCK_DECODER_RESET] result=failed streak=%d", streak)
        } else {
            _status.value = DecoderStatus.Idle
        }
    }

    private fun logReset(reason: String) {
        totalResets += 1
        Timber.w("[Media][decoder][BLOCK_DECODER_RESET] reason=%s resets=%d", reason, totalResets)
    }

    // Stop and release the current codec; never throws. Callbacks of the old codec are ignored by identity.
    private fun releaseCodec(why: String) {
        queue.codecRunning = false
        val c = codec ?: return
        codec = null
        ready = null
        freeInputs.clear()
        runCatching { c.stop() }
        runCatching { c.release() }
        Timber.d("[Media][decoder][releaseCodec] why=%s rendered=%d", why, rendered)
    }
    // END_BLOCK_DECODER_RESET

    private fun ensureThread(): Handler = synchronized(threadLock) {
        handler ?: HandlerThread("media-decoder").let { t ->
            t.start()
            thread = t
            Handler(t.looper).also { handler = it }
        }
    }

    // Run block on the decoder thread and wait for it (bounded), so surface teardown is ordered.
    private fun runOnDecoder(h: Handler, block: () -> Unit) {
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
        const val QUEUE_CAPACITY = 8
        // Frames held while the codec is created (about 2.4 s at 20 fps covers the startup burst + one GOP).
        const val HOLD_CAPACITY = 48
        const val MAX_CODEC_ERRORS = 3
        const val DEFAULT_WIDTH = 1920
        const val DEFAULT_HEIGHT = 1080
        const val FPS_WINDOW_NS = 2_000_000_000L
        const val BLOCKING_WAIT_MS = 1_000L
    }
}
