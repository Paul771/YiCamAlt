// FILE: TnpCameraLink.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: One TNP/CS2 PPPP session per camera, vendor call order.
//   SCOPE: connect (PPPP_Connect / WakeUp_And_Connect) with the device_info DID, session check and mode mapping,
//     readers on ch 0,1,2,3,6, the 4881 -> 9029 -> 768 startup burst from a single writer, auth reply, header
//     version adoption, kick handling, connect and first-frame timeouts, bounded idempotent teardown.
//   DEPENDS: M-P2P-NATIVE (PpppNative, PpppCodes), M-STREAM-CORE (CameraLink, TnpCodec, IoCtrlMessages, YiP2pAuth),
//     M-P2P-CREDS (P2pCredentials)
//   LINKS: M-TNP-LINK, V-M-TNP-LINK
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.p2p.tnp

import com.yicamalt.camera.P2pCredentials
import com.yicamalt.p2p.PpppCodes
import com.yicamalt.p2p.PpppNative
import com.yicamalt.stream.CameraLink
import com.yicamalt.stream.IoCtrlMessages
import com.yicamalt.stream.LinkError
import com.yicamalt.stream.LinkErrorKind
import com.yicamalt.stream.LinkInfo
import com.yicamalt.stream.LinkMode
import com.yicamalt.stream.LinkOptions
import com.yicamalt.stream.LinkState
import com.yicamalt.stream.TnpCodec
import com.yicamalt.stream.VideoUnit
import com.yicamalt.stream.VideoUnitSink
import com.yicamalt.stream.YiP2pAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber
import java.security.SecureRandom
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

// START_MODULE_MAP
//   TnpCameraLink - CameraLink over PpppNative: start(sink) spawns the link threads, close(reason) tears down.
//   TnpCameraLink.Timeouts - connectMs 30000, firstFrameMs 20000, readTimeoutMs 300, joinMs 2000, stopDrainMs 200, pollMs 20.
//   TnpCameraLink.modeOf - vendor bMode 0..3 -> P2P, RELAY, TCP_RELAY, SDEV_RELAY, else UNKNOWN.
//   TnpCameraLink.FLAG_P2P / FLAG_RELAY - connect flag bytes 0x49 / 0xDF.
//   TnpCameraLink.READER_CHANNELS - 0, 1, 2, 3, 6.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 02-07: created from 02-RESEARCH.md Pattern 3 / 4 / 7, Pitfalls 3, 9, 11.
//     INVARIANTS: one connect per start(), no reconnect, no retry; 9033 is never sent; every native call runs on a
//     named thread of this link (p2p-<n>-connect | -rx<ch> | -tx | -close); secrets never reach logs, LinkError or
//     LinkInfo; cam=<n> is a per-process counter, never an identifier.
// END_CHANGE_SUMMARY

internal class TnpCameraLink(
    private val credentials: P2pCredentials,
    private val options: LinkOptions,
    private val native: PpppNative,
    private val camIndex: Int,
    private val rnd: Random = SecureRandom(),
    private val timeouts: Timeouts = Timeouts(),
    private val now: () -> Long = { System.nanoTime() / 1_000_000L },
    private val onClosed: (TnpCameraLink) -> Unit,
) : CameraLink {

    data class Timeouts(
        val connectMs: Long = 30_000,
        val firstFrameMs: Long = 20_000,
        val readTimeoutMs: Int = 300,
        val joinMs: Long = 2_000,
        val stopDrainMs: Long = 200,
        val pollMs: Long = 20,
    )

    private class TxItem(val bytes: ByteArray, val done: CountDownLatch? = null)

    private class ReaderExit(val exit: String, val code: Int) : RuntimeException(null, null, false, false)

    override val deviceId: String = credentials.deviceId

    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    override val state: StateFlow<LinkState> = _state.asStateFlow()

    private val started = AtomicBoolean(false)
    private val closing = AtomicBoolean(false)
    private val handleClosed = AtomicBoolean(false)
    private val versionChecked = AtomicBoolean(false)
    private val commandNumber = AtomicInteger(0)
    private val videoUnits = AtomicInteger(0)
    private val threadLock = Any()
    private val threads = CopyOnWriteArrayList<Thread>()
    private val txQueue = LinkedBlockingQueue<TxItem>()
    private val poison = TxItem(ByteArray(0))
    private val noncePrefix: String by lazy { YiP2pAuth.noncePrefix(rnd) }

    @Volatile private var stopping = false
    @Volatile private var connectAttempted = false
    @Volatile private var connectDone = false
    @Volatile private var handle = -1
    @Volatile private var sink: VideoUnitSink? = null
    @Volatile private var info: LinkInfo? = null
    @Volatile private var authOk = false
    @Volatile private var streaming = false
    @Volatile private var startMs = 0L
    @Volatile private var burstAtMs = -1L
    @Volatile private var headerVersion = DEFAULT_HEADER_VERSION
    @Volatile private var firstResolution = 0
    @Volatile private var firstUseCount = 0

    // START_CONTRACT: start
    //   PURPOSE: Begin the session without blocking the caller.
    //   INPUTS: { sink: VideoUnitSink - receives ch2/ch3/ch6 units from reader threads }
    //   OUTPUTS: { Unit - state goes Connecting; a second call (or a call after close) is ignored }
    //   SIDE_EFFECTS: spawns p2p-<n>-watch and p2p-<n>-connect
    //   LINKS: M-TNP-LINK
    // END_CONTRACT: start
    override fun start(sink: VideoUnitSink) {
        if (!started.compareAndSet(false, true)) return
        this.sink = sink
        startMs = now()
        setLiveState(LinkState.Connecting)
        spawn("watch") { runWatch() }
        spawn("connect") { runConnect() }
    }

    // START_CONTRACT: close
    //   PURPOSE: Idempotent stop from any thread; bounded by Timeouts.joinMs.
    //   INPUTS: { reason: String - user | screen-left | background }
    //   OUTPUTS: { Unit - final state Closed(reason) unless the link already failed or closed }
    //   SIDE_EFFECTS: see teardown (BLOCK_CLOSE)
    //   LINKS: M-TNP-LINK
    // END_CONTRACT: close
    override fun close(reason: String) {
        started.set(true)
        terminate(reason, LinkState.Closed(reason))
    }

    // START_BLOCK_CONNECT
    // START_CONTRACT: runConnect
    //   PURPOSE: Vendor order on p2p-<n>-connect: PPPP_Connect (or WakeUp_And_Connect) with flag 0x49 (0xDF when relay
    //     is forced), InitString and the license device key -> PPPP_Check -> readers ch 0,1,2,3,6 (each made its first
    //     read) -> burst 4881, 9029, 768 queued back to back to the single writer.
    //   INPUTS: none (credentials, options)
    //   OUTPUTS: { Unit - state Connected(info) or a terminal state via failWith }
    //   SIDE_EFFECTS: exactly one native.connect per link; never initialize/configDebug (the runtime owns them)
    //   LINKS: M-P2P-NATIVE, PpppNative.connect, PpppNative.check
    // END_CONTRACT: runConnect
    private fun runConnect() {
        // Under the thread lock so a concurrent teardown either sees the attempt (and breaks it) or we never connect.
        synchronized(threadLock) {
            if (stopping) return
            connectAttempted = true
        }
        val flag = if (options.forceRelay) FLAG_RELAY else FLAG_P2P
        val t0 = now()
        val h = native.connect(
            credentials.p2pId,
            flag,
            credentials.wakeup,
            credentials.initString.orEmpty(),
            credentials.licenseKey.orEmpty(),
        )
        val result = if (h >= 0) "ok" else PpppCodes.name(h)
        Timber.d(
            "[P2P][tnp][BLOCK_CONNECT] cam=%d relayForced=%b wakeup=%b result=%s ms=%d",
            camIndex, options.forceRelay, credentials.wakeup, result, now() - t0,
        )
        if (h < 0) {
            if (!stopping && !closing.get()) failWith(LinkError(PpppCodes.classify(h), h), REASON_ERROR)
            return
        }
        handle = h
        if (closing.get() || stopping) {
            closeHandleOnce()
            return
        }
        // END_BLOCK_CONNECT

        // START_BLOCK_CHECK_SESSION
        val check = native.check(h)
        if (check.code != PpppCodes.OK) {
            if (!stopping && !closing.get()) failWith(LinkError(LinkErrorKind.INTERNAL, check.code), REASON_ERROR)
            return
        }
        val mode = modeOf(check.mode)
        val lan = mode == LinkMode.P2P && check.remoteIsPrivate
        val linkInfo = LinkInfo(credentials.transport, mode, lan, check.connectTimeMs)
        info = linkInfo
        connectDone = true
        Timber.d(
            "[P2P][tnp][BLOCK_CHECK_SESSION] cam=%d mode=%s lan=%b connectMs=%d",
            camIndex, mode, lan, check.connectTimeMs,
        )
        setLiveState(LinkState.Connected(linkInfo))
        // END_BLOCK_CHECK_SESSION

        val firstRead = CountDownLatch(READER_CHANNELS.size)
        READER_CHANNELS.forEach { ch -> spawn("rx$ch") { readerLoop(ch, firstRead) } }
        firstRead.await(timeouts.joinMs, TimeUnit.MILLISECONDS)
        if (stopping || closing.get()) return
        spawn("tx") { txLoop() }

        // START_BLOCK_START_BURST
        val resolution = IoCtrlMessages.ResolutionPolicy.forMode(mode)
        var useCount = 0
        useCount++
        firstResolution = resolution
        firstUseCount = useCount
        val setRes = buildCommand(IoCtrlMessages.SET_RESOLUTION, IoCtrlMessages.setResolution(resolution, useCount))
        useCount++
        val startRt = buildCommand(IoCtrlMessages.START_REALTIME, IoCtrlMessages.startRealtime(useCount, resolution))
        val audio = buildCommand(IoCtrlMessages.AUDIO_START, IoCtrlMessages.audioStart())
        burstAtMs = now()
        txQueue.addAll(listOf(TxItem(setRes), TxItem(startRt), TxItem(audio)))
        Timber.d(
            "[P2P][tnp][BLOCK_START_BURST] cam=%d cmds=4881,9029,768 resolution=%d version=%d",
            camIndex, resolution, headerVersion,
        )
        // END_BLOCK_START_BURST
    }

    // Full command unit: current header version, next command number, fresh authInfo with the per-link nonce prefix.
    private fun buildCommand(type: Int, payload: ByteArray): ByteArray {
        val auth = YiP2pAuth.authInfo(credentials.p2pEncrypt, credentials.password.orEmpty(), noncePrefix, rnd)
        return TnpCodec.commandUnit(headerVersion, type, commandNumber.incrementAndGet(), auth, payload)
    }

    // START_BLOCK_TX_LOOP
    // Single writer p2p-<n>-tx: drains the queue in order, so the burst leaves back to back.
    private fun txLoop() {
        while (true) {
            val item = try {
                txQueue.take()
            } catch (e: InterruptedException) {
                return
            }
            if (item === poison) return
            if (stopping) {
                item.done?.countDown()
                continue
            }
            val r = native.write(handle, TnpCodec.CH_COMMAND, item.bytes, item.bytes.size)
            item.done?.countDown()
            if (r < 0 && !stopping && !closing.get()) {
                failWith(LinkError(PpppCodes.classify(r), r), REASON_ERROR)
                return
            }
        }
    }
    // END_BLOCK_TX_LOOP

    // START_BLOCK_READ_LOOP
    // START_CONTRACT: readerLoop
    //   PURPOSE: Per-channel reader p2p-<n>-rx<ch>: exact 8-byte head, validateHead, exact body, dispatch.
    //   INPUTS: { ch: Int - 0 command, 1 audio (discarded), 2/3/6 video; firstRead: CountDownLatch - released after the
    //     first native read returns, so the burst never precedes a running reader }
    //   OUTPUTS: { Unit - exit closed (-3014 / -3019 / stopping) quietly; error -> failWith(classify(code));
    //     dirty head -> failWith(PROTOCOL, -9000) }
    //   SIDE_EFFECTS: ch0 header version rule (vendor mNeedDetectHeaderVersion): on the FIRST ch0 unit only, a
    //     TNPHead.version in 1..3 that differs from the current one is adopted for all later commands and ONLY the first
    //     command (4881) is re-sent once, with the same resolution and useCount and the next command number; 9029 and
    //     768 are not re-sent; later version changes are ignored.
    //   LINKS: M-STREAM-CORE, TnpCodec.validateHead, TnpCodec.splitFrameUnit, VideoUnitSink.onVideoUnit
    // END_CONTRACT: readerLoop
    private fun readerLoop(ch: Int, firstRead: CountDownLatch) {
        var units = 0
        val exit: String
        val code: Int
        var primed = false
        val prime = {
            if (!primed) {
                primed = true
                firstRead.countDown()
            }
        }
        try {
            while (true) {
                val head = TnpCodec.parseHead(readExactly(ch, TnpCodec.HEAD_SIZE, prime))
                if (TnpCodec.validateHead(head, ch) is TnpCodec.HeadCheck.Dirty) {
                    throw ReaderExit(EXIT_DIRTY, PpppCodes.APP_MANUAL_RETRY)
                }
                val body = readExactly(ch, head.dataSize.toInt(), prime)
                units++
                onUnit(ch, head, body)
                if (closing.get()) throw ReaderExit(EXIT_CLOSED, PpppCodes.SESSION_CLOSED_CALLED)
            }
        } catch (e: ReaderExit) {
            exit = e.exit
            code = e.code
        } catch (e: RuntimeException) {
            exit = EXIT_ERROR
            code = 0
        } finally {
            prime()
        }
        Timber.d(
            "[P2P][tnp][BLOCK_READ_LOOP] cam=%d ch=%d exit=%s code=%s units=%d",
            camIndex, ch, exit, PpppCodes.name(code), units,
        )
        if (stopping || closing.get()) return
        when (exit) {
            EXIT_DIRTY -> failWith(LinkError(LinkErrorKind.PROTOCOL, PpppCodes.APP_MANUAL_RETRY), REASON_ERROR)
            EXIT_ERROR ->
                failWith(LinkError(if (code == 0) LinkErrorKind.INTERNAL else PpppCodes.classify(code), code), REASON_ERROR)
        }
    }

    // Reads exactly n bytes: OK and -3003 accumulate size[0]; -3014 / -3019 or stopping end quietly; other codes = error.
    private fun readExactly(ch: Int, n: Int, prime: () -> Unit): ByteArray {
        val out = ByteArray(n)
        val chunk = ByteArray(n)
        val size = IntArray(1)
        var off = 0
        while (off < n) {
            if (stopping) throw ReaderExit(EXIT_CLOSED, PpppCodes.SESSION_CLOSED_CALLED)
            size[0] = n - off
            val r = native.read(handle, ch, chunk, size, timeouts.readTimeoutMs)
            prime()
            val got = size[0].coerceIn(0, n - off)
            when {
                r >= 0 || r == PpppCodes.TIME_OUT -> {
                    if (got > 0) {
                        System.arraycopy(chunk, 0, out, off, got)
                        off += got
                    }
                }
                r == PpppCodes.SESSION_CLOSED_CALLED || r == PpppCodes.USER_CONNECT_BREAK || stopping ->
                    throw ReaderExit(EXIT_CLOSED, r)
                else -> throw ReaderExit(EXIT_ERROR, r)
            }
        }
        return out
    }

    private fun onUnit(ch: Int, head: TnpCodec.TnpHead, body: ByteArray) {
        when (ch) {
            TnpCodec.CH_COMMAND -> onCommandUnit(head, body)
            TnpCodec.CH_AUDIO -> Unit
            else -> onVideoBody(ch, body)
        }
    }

    private fun onCommandUnit(head: TnpCodec.TnpHead, body: ByteArray) {
        if (versionChecked.compareAndSet(false, true) && head.version in 1..3 && head.version != headerVersion) {
            headerVersion = head.version
            txQueue.offer(
                TxItem(
                    buildCommand(
                        IoCtrlMessages.SET_RESOLUTION,
                        IoCtrlMessages.setResolution(firstResolution, firstUseCount),
                    ),
                ),
            )
            Timber.d("[P2P][tnp][BLOCK_HEADER_VERSION] cam=%d version=%d resent=4881", camIndex, head.version)
        }
        val reply = TnpCodec.parseIoCtrlReply(body) ?: return
        when (reply.type) {
            IoCtrlMessages.SET_RESOLUTION_RESP -> onAuthReply(reply.authResult)
            IoCtrlMessages.KICK -> {
                Timber.d("[P2P][tnp][BLOCK_KICKED] cam=%d code=%s", camIndex, PpppCodes.name(IoCtrlMessages.kickCode(reply.payload)))
                terminate(REASON_KICKED, LinkState.Closed(REASON_KICKED))
            }
            else -> Unit
        }
    }
    // END_BLOCK_READ_LOOP

    // START_BLOCK_AUTH_REPLY
    // 4882 authResult: 0 -> authOk; 1|2 -> WRONG_PASSWORD (-20009); 4 or unknown -> PROTOCOL (-9000). No retry.
    private fun onAuthReply(authResult: Int) {
        val outcome = IoCtrlMessages.authOutcome(authResult)
        val label = if (outcome is IoCtrlMessages.AuthOutcome.Unknown) "UNKNOWN" else outcome.toString()
        Timber.d(
            "[P2P][tnp][BLOCK_AUTH_REPLY] cam=%d authResult=%d outcome=%s version=%d",
            camIndex, authResult, label, headerVersion,
        )
        when (outcome) {
            IoCtrlMessages.AuthOutcome.OK -> {
                authOk = true
                val i = info
                if (streaming && i != null) setLiveState(LinkState.Streaming(i, true))
            }
            IoCtrlMessages.AuthOutcome.WRONG_PASSWORD ->
                failWith(LinkError(LinkErrorKind.WRONG_PASSWORD, PpppCodes.APP_WRONG_PASSWORD), REASON_ERROR)
            else -> failWith(LinkError(LinkErrorKind.PROTOCOL, PpppCodes.APP_MANUAL_RETRY), REASON_ERROR)
        }
    }
    // END_BLOCK_AUTH_REPLY

    // START_BLOCK_FIRST_VIDEO
    private fun onVideoBody(ch: Int, body: ByteArray) {
        val (frameInfo, payload) = TnpCodec.splitFrameUnit(body, bigEndian = true) ?: return
        val unit = VideoUnit(ch, frameInfo, payload)
        if (videoUnits.incrementAndGet() == 1) {
            streaming = true
            Timber.d(
                "[P2P][tnp][BLOCK_FIRST_VIDEO] cam=%d ch=%d codecId=%d key=%b ms=%d",
                camIndex, ch, frameInfo.codecId, frameInfo.isKeyFrame, now() - startMs,
            )
            info?.let { setLiveState(LinkState.Streaming(it, authOk)) }
        }
        sink?.onVideoUnit(unit)
    }
    // END_BLOCK_FIRST_VIDEO

    // START_BLOCK_WATCH
    // p2p-<n>-watch: the only two timers. Connect (connect + check) > connectMs -> TIMEOUT(-3003);
    // no video unit firstFrameMs after the burst -> TIMEOUT(0). Exits on the first video unit or on close.
    private fun runWatch() {
        while (!closing.get()) {
            val t = now()
            if (!connectDone) {
                if (t - startMs >= timeouts.connectMs) {
                    failWith(LinkError(LinkErrorKind.TIMEOUT, PpppCodes.TIME_OUT), REASON_TIMEOUT)
                    return
                }
            } else {
                if (videoUnits.get() > 0) return
                val b = burstAtMs
                if (b >= 0 && t - b >= timeouts.firstFrameMs) {
                    failWith(LinkError(LinkErrorKind.TIMEOUT, 0), REASON_TIMEOUT)
                    return
                }
            }
            try {
                Thread.sleep(timeouts.pollMs)
            } catch (e: InterruptedException) {
                return
            }
        }
    }
    // END_BLOCK_WATCH

    private fun failWith(error: LinkError, reason: String) = terminate(reason, LinkState.Failed(error))

    // START_BLOCK_CLOSE
    // START_CONTRACT: terminate
    //   PURPOSE: The single teardown path for close(), failures, timeouts and kicks; runs once.
    //   INPUTS: { reason: String - BLOCK_CLOSE reason; final: LinkState - Closed(reason) or Failed(error) }
    //   OUTPUTS: { Unit - external callers wait for the p2p-<n>-close thread up to joinMs; link threads do not wait }
    //   SIDE_EFFECTS: on p2p-<n>-close: 767 STOP if streaming (writer gets <= stopDrainMs), stop flag, Connect_Break
    //     (only if a connect was attempted), ForceClose (only with a handle), bounded join, final state, onClosed.
    //   LINKS: PpppNative.connectBreak, PpppNative.forceClose
    // END_CONTRACT: terminate
    private fun terminate(reason: String, final: LinkState) {
        if (!closing.compareAndSet(false, true)) return
        val closer = Thread({ teardown(reason, final) }, "p2p-$camIndex-close").apply { isDaemon = true }
        closer.start()
        if (Thread.currentThread() !in threads) closer.join(timeouts.joinMs + timeouts.stopDrainMs)
    }

    private fun teardown(reason: String, final: LinkState) {
        val t0 = System.nanoTime()
        val deadline = t0 + TimeUnit.MILLISECONDS.toNanos(timeouts.joinMs)
        if (streaming && handle >= 0 && !handleClosed.get()) {
            val done = CountDownLatch(1)
            txQueue.offer(TxItem(buildCommand(IoCtrlMessages.STOP, IoCtrlMessages.stop()), done))
            done.await(timeouts.stopDrainMs, TimeUnit.MILLISECONDS)
        }
        val snapshot = synchronized(threadLock) {
            stopping = true
            threads.toList()
        }
        if (connectAttempted) native.connectBreak(credentials.p2pId)
        closeHandleOnce()
        txQueue.offer(poison)
        snapshot.forEach { t ->
            val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (left > 0) t.join(left)
        }
        val joinMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
        _state.update { if (it is LinkState.Failed || it is LinkState.Closed) it else final }
        Timber.d(
            "[P2P][tnp][BLOCK_CLOSE] cam=%d reason=%s units=%d joinMs=%d",
            camIndex, reason, videoUnits.get(), joinMs,
        )
        runCatching { onClosed(this) }
    }

    private fun closeHandleOnce() {
        val h = handle
        if (h >= 0 && handleClosed.compareAndSet(false, true)) native.forceClose(h)
    }
    // END_BLOCK_CLOSE

    private fun setLiveState(next: LinkState) {
        _state.update { if (closing.get() || it is LinkState.Failed || it is LinkState.Closed) it else next }
    }

    private fun spawn(suffix: String, body: () -> Unit) {
        synchronized(threadLock) {
            if (stopping) return
            val t = Thread(body, "p2p-$camIndex-$suffix").apply { isDaemon = true }
            threads += t
            t.start()
        }
    }

    override fun toString(): String = "TnpCameraLink(cam=$camIndex, state=${_state.value::class.simpleName})"

    companion object {
        const val FLAG_P2P: Byte = 0x49
        val FLAG_RELAY: Byte = 0xDF.toByte()
        const val DEFAULT_HEADER_VERSION = 2
        val READER_CHANNELS = listOf(
            TnpCodec.CH_COMMAND, TnpCodec.CH_AUDIO, TnpCodec.CH_IFRAME, TnpCodec.CH_PFRAME, TnpCodec.CH_NEW_LIVE,
        )
        const val REASON_ERROR = "error"
        const val REASON_TIMEOUT = "timeout"
        const val REASON_KICKED = "kicked"
        private const val EXIT_CLOSED = "closed"
        private const val EXIT_ERROR = "error"
        private const val EXIT_DIRTY = "dirty"

        // Vendor bMode mapping: 0 P2P, 1 Relay, 2 TCP relay, 3 SDEV relay.
        fun modeOf(bMode: Int): LinkMode = when (bMode and 0xFF) {
            0 -> LinkMode.P2P
            1 -> LinkMode.RELAY
            2 -> LinkMode.TCP_RELAY
            3 -> LinkMode.SDEV_RELAY
            else -> LinkMode.UNKNOWN
        }
    }
}
