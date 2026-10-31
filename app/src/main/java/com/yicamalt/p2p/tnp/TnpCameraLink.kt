// FILE: TnpCameraLink.kt
// VERSION: 0.4.0
// START_MODULE_CONTRACT
//   PURPOSE: One TNP/CS2 PPPP session per camera, vendor call order.
//   SCOPE: connect (PPPP_Connect / WakeUp_And_Connect) with the device_info DID, session check and mode mapping,
//     readers on ch 0..7 (ch1 audio -> optional AudioUnitSink; 2/3/6 live and 4/5/7 recorded video -> VideoUnitSink), the 4881 -> 9029 -> 768 startup burst from a single writer, authResult check on every
//     ch0 reply, header version adoption (whole start sequence re-sent once), kick handling, connect and first-frame
//     timeouts, thread guard (any Throwable -> Failed(INTERNAL)), bounded idempotent teardown, in-session
//     resolution requests (4881) through the single writer, 4882 abilityRet -> LinkCapabilities, generic IOCTRL
//     send (archive commands) through the single writer after the burst, non-consumed ch0 replies on ioCtrlReplies,
//     one link-wide useCount shared by 4881 and archive commands.
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
import com.yicamalt.stream.AudioUnit
import com.yicamalt.stream.AudioUnitSink
import com.yicamalt.stream.CameraLink
import com.yicamalt.stream.IoCtrlMessages
import com.yicamalt.stream.LinkCapabilities
import com.yicamalt.stream.LinkError
import com.yicamalt.stream.LinkErrorKind
import com.yicamalt.stream.LinkInfo
import com.yicamalt.stream.LinkIoCtrlReply
import com.yicamalt.stream.LinkMode
import com.yicamalt.stream.LinkOptions
import com.yicamalt.stream.LinkState
import com.yicamalt.stream.TnpCodec
import com.yicamalt.stream.VideoUnit
import com.yicamalt.stream.VideoUnitSink
import com.yicamalt.stream.YiP2pAuth
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
//   TnpCameraLink.Timeouts - connectMs 30000, firstFrameMs 20000, readTimeoutMs 300, joinMs 2000, stopDrainMs 200,
//     pollMs 20, ioJoinMarginMs 200 (readers / writer are joined for readTimeoutMs + margin before ForceClose).
//   TnpCameraLink.modeOf - vendor bMode 0..3 -> P2P, RELAY, TCP_RELAY, SDEV_RELAY, else UNKNOWN.
//   TnpCameraLink.FLAG_P2P / FLAG_RELAY - connect flag bytes 0x49 / 0xDF.
//   TnpCameraLink.READER_CHANNELS - 0, 1, 2, 3, 4, 5, 6, 7 (4 / 5 / 7 = recorded I / P / new record).
//   TnpCameraLink.setAudioSink - ch1 units (ioType 2, body > 24) go to the sink, encrypted = header version >= 2.
//   TnpCameraLink.requestResolution - queues 4881 [res, useCount from the link-wide counter] on the writer; null if
//     not connected.
//   TnpCameraLink.sendIoCtrl - queues any IOCTRL (current header version, next command number) after the burst.
//   TnpCameraLink.ioCtrlReplies - authResult-0 ch0 replies other than 4882 / 9033 (hot, buffer 16 drop-oldest).
//   TnpCameraLink.nextUseCount - link-wide useCount (3, 4, ... after the burst's fixed 1 / 2); null before the burst.
//   TnpCameraLink.transportBigEndian - true. setStreamMode keeps the default: TNP has no no-data limit after the
//     first frame.
//   TnpCameraLink.capabilities - resolutionSwitch=false once a 4882 reply carries abilityRet 1.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.4.0 - GSD plan 04-00: record readers ch4/5/7 (vendor TnpCamera.startThread), generic IOCTRL send
//     via the single writer after the burst, non-consumed ch0 replies on ioCtrlReplies, one link-wide useCount shared
//     by 4881 and archive commands; burst unchanged.
//   PREVIOUS: v0.3.0 - GSD plan 03-02: ch1 audio -> AudioUnitSink (encrypted = header version >= 2), requestResolution
//     via txQueue (useCount from 3), 4882 abilityRet 1 -> resolutionSwitch=false; burst unchanged. A throwing audio
//     sink is logged once (class name) and audio dropped, video keeps running. requestResolution only enqueues, and
//     only after the start burst is queued (so 4881 useCount 1 always leads); never calls native.
//   PREVIOUS: v0.2.0 - Phase 2 review fixes.
//     HIGH-2: header version detection follows vendor TnpCamera: when the FIRST ch0 head carries a version in 1..3
//       other than the one used, the version is adopted, the WHOLE start sequence (4881, 9029, 768; same resolution
//       and useCounts, next command numbers) is queued again with it, and that reply is skipped unparsed ("skip wrong
//       tnp version result"). The start sequence is therefore sent at most twice per connection (9029 included).
//     MED-3: authResult is checked on every ch0 reply (vendor ~1266): 1|2 -> WRONG_PASSWORD(-20009), 4 ->
//       PROTOCOL(-9000), 0 -> continue (authOk only from 4882); any other value drops that reply, logged once.
//     MED-7: every link thread body runs under a guard: an uncaught Throwable ends the link as Failed(INTERNAL, 0)
//       with full teardown (was: thread death with the native session open). The teardown itself always publishes
//       the final state and releases the registry.
//     LOW-8: onClosed (registry release) runs only after every link thread has exited; if the bounded join leaves
//       one alive (e.g. a native connect that ignores Connect_Break), a p2p-<n>-reap thread waits for it first.
//       onClosed runs before the final state is published, so Closed/Failed means "the camera slot is free"
//       whenever all threads ended in time.
//     LOW-9: readers and the writer use a local handle copy and re-check stopping / handleClosed before each native
//       call; ForceClose runs only after they were joined (bounded by readTimeoutMs + ioJoinMarginMs), so no read or
//       write can hit a handle index that ForceClose returned to the pool. Order stays STOP -> Connect_Break ->
//       ForceClose.
//     INVARIANTS: one connect per start(), no reconnect and no retry inside the link (the single automatic retry on
//       WRONG_PASSWORD lives in LiveProbeViewModel, above the factory); 9033 is never sent; every native call runs on
//       a named thread of this link (p2p-<n>-connect | -rx<ch> | -tx | -close); secrets never reach logs, LinkError or
//       LinkInfo; cam=<n> is a per-process counter, never an identifier.
//   PREVIOUS: v0.1.1 - GSD plan 02-12: auth reply (rx0) and first video unit (rx2/3/6) publish Streaming under
//     liveLock; before, the video reader could overwrite Streaming(authOk=true) with a stale authOk=false.
//   PREVIOUS: v0.1.0 - GSD plan 02-07: created from 02-RESEARCH.md Pattern 3 / 4 / 7, Pitfalls 3, 9, 11.
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
        val ioJoinMarginMs: Long = 200,
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
    private val unknownAuthLogged = AtomicBoolean(false)
    private val commandNumber = AtomicInteger(0)
    private val videoUnits = AtomicInteger(0)
    private val audioUnits = AtomicInteger(0)
    private val audioSinkErrorLogged = AtomicBoolean(false)
    // ONE link-wide useCount: the burst uses fixed 1 / 2, every later 4881 or archive command takes the next value.
    private val useCounter = AtomicInteger(USE_COUNT_START_RT)
    private val _capabilities = MutableStateFlow(LinkCapabilities())
    override val capabilities: StateFlow<LinkCapabilities> = _capabilities.asStateFlow()
    private val _ioCtrlReplies = MutableSharedFlow<LinkIoCtrlReply>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val ioCtrlReplies: SharedFlow<LinkIoCtrlReply> = _ioCtrlReplies.asSharedFlow()
    override val transportBigEndian: Boolean = true
    private val threadLock = Any()
    private val liveLock = Any()
    private val threads = CopyOnWriteArrayList<Thread>()
    private val txQueue = LinkedBlockingQueue<TxItem>()
    private val poison = TxItem(ByteArray(0))
    private val noncePrefix: String by lazy { YiP2pAuth.noncePrefix(rnd) }

    @Volatile private var stopping = false
    @Volatile private var connectAttempted = false
    @Volatile private var connectDone = false
    @Volatile private var handle = -1
    @Volatile private var sink: VideoUnitSink? = null
    @Volatile private var audioSink: AudioUnitSink? = null
    @Volatile private var burstQueued = false
    @Volatile private var info: LinkInfo? = null
    @Volatile private var authOk = false
    @Volatile private var streaming = false
    @Volatile private var startMs = 0L
    @Volatile private var burstAtMs = -1L
    @Volatile private var headerVersion = DEFAULT_HEADER_VERSION
    @Volatile private var firstResolution = 0

    // START_CONTRACT: start
    //   PURPOSE: Begin the session without blocking the caller.
    //   INPUTS: { sink: VideoUnitSink - receives ch2/3/6 live and ch4/5/7 recorded units from reader threads }
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

    // START_CONTRACT: setAudioSink
    //   PURPOSE: Route ch1 audio units to [sink]; null (default) discards them as before.
    //   INPUTS: { sink: AudioUnitSink? - called from p2p-<n>-rx1 }
    //   OUTPUTS: { Unit }
    //   SIDE_EFFECTS: none (read by the ch1 reader on its next unit)
    //   LINKS: M-TNP-LINK, AudioUnitSink.onAudioUnit
    // END_CONTRACT: setAudioSink
    override fun setAudioSink(sink: AudioUnitSink?) {
        audioSink = sink
    }

    // START_CONTRACT: requestResolution
    //   PURPOSE: In-session HD/SD switch (D-07): queue IOCTRL 4881 [resolution, useCount] on the single writer.
    //   INPUTS: { resolution: Int - IoCtrlMessages resolution code }
    //   OUTPUTS: { Int? - useCount sent (3, 4, ...), or null when not Connected/Streaming, before the start burst was
    //     queued, or once closing }
    //   SIDE_EFFECTS: one txQueue.offer; never blocks and never calls native (any caller thread)
    //   LINKS: M-TNP-LINK, IoCtrlMessages.setResolution, BLOCK_TX_LOOP
    // END_CONTRACT: requestResolution
    override fun requestResolution(resolution: Int): Int? {
        // START_BLOCK_SET_RESOLUTION
        if (!canSend()) {
            Timber.d(
                "[P2P][tnp][BLOCK_SET_RESOLUTION] cam=%d res=%d useCount=%d queued=%b",
                camIndex, resolution, 0, false,
            )
            return null
        }
        val uc = useCounter.incrementAndGet()
        val queued = txQueue.offer(
            TxItem(buildCommand(IoCtrlMessages.SET_RESOLUTION, IoCtrlMessages.setResolution(resolution, uc))),
        )
        Timber.d(
            "[P2P][tnp][BLOCK_SET_RESOLUTION] cam=%d res=%d useCount=%d queued=%b",
            camIndex, resolution, uc, queued,
        )
        return if (queued) uc else null
        // END_BLOCK_SET_RESOLUTION
    }

    // True while commands may be queued: connected or streaming, start burst queued, not closing.
    private fun canSend(): Boolean {
        val st = _state.value
        return !closing.get() && !stopping && handle >= 0 && burstQueued &&
            (st is LinkState.Connected || st is LinkState.Streaming)
    }

    // START_CONTRACT: nextUseCount
    //   PURPOSE: Hand out the next value of the ONE link-wide useCount (shared with 4881; the burst keeps its fixed
    //     1 / 2, also on the header-version resend).
    //   INPUTS: none
    //   OUTPUTS: { Int? - 3, 4, ... in call order across nextUseCount and requestResolution; null when canSend() is
    //     false (before the burst is queued, not connected, closing) }
    //   SIDE_EFFECTS: increments the counter only when a value is returned
    //   LINKS: M-TNP-LINK, requestResolution, sendIoCtrl
    // END_CONTRACT: nextUseCount
    override fun nextUseCount(): Int? = if (!canSend()) null else useCounter.incrementAndGet()

    // START_CONTRACT: sendIoCtrl
    //   PURPOSE: Queue one arbitrary IOCTRL (archive 9030 / 9029 / 816 ...) on the single writer.
    //   INPUTS: { type: Int - IOCTRL type; payload: ByteArray - big-endian payload built by the caller }
    //   OUTPUTS: { Boolean - true when queued; false before the start burst, when not Connected/Streaming or closing }
    //   SIDE_EFFECTS: one txQueue.offer (current header version, next command number, fresh authInfo); never blocks and
    //     never calls native (any caller thread)
    //   LINKS: M-TNP-LINK, BLOCK_TX_LOOP, TnpCodec.commandUnit
    // END_CONTRACT: sendIoCtrl
    override fun sendIoCtrl(type: Int, payload: ByteArray): Boolean {
        // START_BLOCK_SEND_IOCTRL
        if (!canSend()) {
            Timber.d(
                "[P2P][tnp][BLOCK_SEND_IOCTRL] cam=%d type=%d bytes=%d queued=%b",
                camIndex, type, payload.size, false,
            )
            return false
        }
        val queued = txQueue.offer(TxItem(buildCommand(type, payload)))
        Timber.d(
            "[P2P][tnp][BLOCK_SEND_IOCTRL] cam=%d type=%d bytes=%d queued=%b",
            camIndex, type, payload.size, queued,
        )
        return queued
        // END_BLOCK_SEND_IOCTRL
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
    //     is forced), InitString and the license device key -> PPPP_Check -> readers ch 0..7 (each made its first
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
        firstResolution = resolution
        val burst = startSequence(resolution)
        burstAtMs = now()
        txQueue.addAll(burst)
        burstQueued = true
        Timber.d(
            "[P2P][tnp][BLOCK_START_BURST] cam=%d cmds=4881,9029,768 resolution=%d version=%d",
            camIndex, resolution, headerVersion,
        )
        // END_BLOCK_START_BURST
    }

    // 4881 (useCount 1), 9029 (useCount 2), 768 built with the current header version and the next command numbers.
    private fun startSequence(resolution: Int): List<TxItem> {
        val setRes = buildCommand(
            IoCtrlMessages.SET_RESOLUTION,
            IoCtrlMessages.setResolution(resolution, USE_COUNT_SET_RES),
        )
        val startRt = buildCommand(
            IoCtrlMessages.START_REALTIME,
            IoCtrlMessages.startRealtime(USE_COUNT_START_RT, resolution),
        )
        val audio = buildCommand(IoCtrlMessages.AUDIO_START, IoCtrlMessages.audioStart())
        return listOf(TxItem(setRes), TxItem(startRt), TxItem(audio))
    }

    // Full command unit: current header version, next command number, fresh authInfo with the per-link nonce prefix.
    private fun buildCommand(type: Int, payload: ByteArray): ByteArray {
        val auth = YiP2pAuth.authInfo(credentials.p2pEncrypt, credentials.password.orEmpty(), noncePrefix, rnd)
        return TnpCodec.commandUnit(headerVersion, type, commandNumber.incrementAndGet(), auth, payload)
    }

    // START_BLOCK_TX_LOOP
    // Single writer p2p-<n>-tx: drains the queue in order, so the burst leaves back to back. Writes go to the handle
    // captured at writer start and stop once teardown began or the handle was force-closed.
    private fun txLoop() {
        val h = handle
        while (true) {
            val item = try {
                txQueue.take()
            } catch (e: InterruptedException) {
                return
            }
            if (item === poison) return
            if (stopping || handleClosed.get()) {
                item.done?.countDown()
                continue
            }
            val r = native.write(h, TnpCodec.CH_COMMAND, item.bytes, item.bytes.size)
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
    //   INPUTS: { ch: Int - 0 command, 1 audio (audio sink or discarded), 2/3/6 live and 4/5/7 recorded video; firstRead: CountDownLatch - released after the
    //     first native read returns, so the burst never precedes a running reader }
    //   OUTPUTS: { Unit - exit closed (-3014 / -3019 / stopping) quietly; error -> failWith(classify(code));
    //     dirty head -> failWith(PROTOCOL, -9000) }
    //   SIDE_EFFECTS: ch0 header version rule (vendor mNeedDetectHeaderVersion): on the FIRST ch0 unit only, a
    //     TNPHead.version in 1..3 that differs from the current one is adopted for all later commands, the whole start
    //     sequence (4881, 9029, 768) is queued again once with the same resolution and useCounts and the next command
    //     numbers, and that reply is skipped unparsed; later version changes are ignored. Every other ch0 reply goes
    //     through the authResult check (BLOCK_AUTH_REPLY). Reads use the handle captured at reader start and stop
    //     once stopping or handleClosed is set.
    //   LINKS: M-STREAM-CORE, TnpCodec.validateHead, TnpCodec.splitFrameUnit, VideoUnitSink.onVideoUnit
    // END_CONTRACT: readerLoop
    private fun readerLoop(ch: Int, firstRead: CountDownLatch) {
        val h = handle
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
                val head = TnpCodec.parseHead(readExactly(h, ch, TnpCodec.HEAD_SIZE, prime))
                if (TnpCodec.validateHead(head, ch) is TnpCodec.HeadCheck.Dirty) {
                    throw ReaderExit(EXIT_DIRTY, PpppCodes.APP_MANUAL_RETRY)
                }
                val body = readExactly(h, ch, head.dataSize.toInt(), prime)
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

    // Reads exactly n bytes: OK and -3003 accumulate size[0]; -3014 / -3019, stopping or a force-closed handle end
    // quietly; other codes = error.
    private fun readExactly(h: Int, ch: Int, n: Int, prime: () -> Unit): ByteArray {
        val out = ByteArray(n)
        val chunk = ByteArray(n)
        val size = IntArray(1)
        var off = 0
        while (off < n) {
            if (stopping || handleClosed.get()) throw ReaderExit(EXIT_CLOSED, PpppCodes.SESSION_CLOSED_CALLED)
            size[0] = n - off
            val r = native.read(h, ch, chunk, size, timeouts.readTimeoutMs)
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
            TnpCodec.CH_AUDIO -> onAudioBody(head, body)
            else -> onVideoBody(ch, body)
        }
    }

    private fun onCommandUnit(head: TnpCodec.TnpHead, body: ByteArray) {
        if (versionChecked.compareAndSet(false, true) && head.version in 1..3 && head.version != headerVersion) {
            // START_BLOCK_HEADER_VERSION
            // Vendor: adopt the camera's version, send the start sequence again with it, skip this reply unparsed.
            headerVersion = head.version
            txQueue.addAll(startSequence(firstResolution))
            burstAtMs = now()
            Timber.d(
                "[P2P][tnp][BLOCK_HEADER_VERSION] cam=%d version=%d resent=4881,9029,768 reply=skipped",
                camIndex, head.version,
            )
            return
            // END_BLOCK_HEADER_VERSION
        }
        val reply = TnpCodec.parseIoCtrlReply(body) ?: return
        if (reply.type == IoCtrlMessages.SET_RESOLUTION_RESP && head.abilityRet == 1) {
            _capabilities.value = LinkCapabilities(resolutionSwitch = false)
        }
        if (!onAuthResult(reply.type, reply.authResult, head.abilityRet)) return
        if (reply.type == IoCtrlMessages.KICK) {
            Timber.d(
                "[P2P][tnp][BLOCK_KICKED] cam=%d code=%s",
                camIndex, PpppCodes.name(IoCtrlMessages.kickCode(reply.payload)),
            )
            terminate(REASON_KICKED, LinkState.Closed(REASON_KICKED))
            return
        }
        if (reply.type != IoCtrlMessages.SET_RESOLUTION_RESP) {
            // START_BLOCK_IOCTRL_REPLY
            // Replies the link does not consume (archive 9031 / 817 ...) go to collectors; dropped when none listen.
            _ioCtrlReplies.tryEmit(LinkIoCtrlReply(reply.type, reply.payload))
            Timber.d(
                "[P2P][tnp][BLOCK_IOCTRL_REPLY] cam=%d type=%d bytes=%d",
                camIndex, reply.type, reply.payload.size,
            )
            // END_BLOCK_IOCTRL_REPLY
        }
    }
    // END_BLOCK_READ_LOOP

    // START_BLOCK_AUTH_REPLY
    // START_CONTRACT: onAuthResult
    //   PURPOSE: Vendor authResult rule for every ch0 reply (TnpCamera ~1266).
    //   INPUTS: { type: Int - IOCTRL type of the reply; authResult: Int - s32 at IOCtrl offset 8;
    //     abilityRet: Int - TNPHead byte 2, logged only }
    //   OUTPUTS: { Boolean - true: authResult 0, the reply may be handled further }
    //   SIDE_EFFECTS: 0 on 4882 -> authOk (Streaming republished under liveLock); 1|2 -> Failed(WRONG_PASSWORD, -20009);
    //     4 -> Failed(PROTOCOL, -9000); any other value -> reply dropped, one value-free UNKNOWN log line per link.
    //     The link itself never retries.
    //   LINKS: IoCtrlMessages.authOutcome, M-TNP-LINK
    // END_CONTRACT: onAuthResult
    private fun onAuthResult(type: Int, authResult: Int, abilityRet: Int = 0): Boolean {
        val outcome = IoCtrlMessages.authOutcome(authResult)
        if (outcome is IoCtrlMessages.AuthOutcome.Unknown) {
            if (unknownAuthLogged.compareAndSet(false, true)) {
                Timber.d("[P2P][tnp][BLOCK_AUTH_REPLY] cam=%d outcome=UNKNOWN action=ignored", camIndex)
            }
            return false
        }
        if (type == IoCtrlMessages.SET_RESOLUTION_RESP || outcome != IoCtrlMessages.AuthOutcome.OK) {
            Timber.d(
                "[P2P][tnp][BLOCK_AUTH_REPLY] cam=%d type=%d authResult=%d outcome=%s version=%d abilityRet=%d",
                camIndex, type, authResult, outcome.toString(), headerVersion, abilityRet,
            )
        }
        when (outcome) {
            IoCtrlMessages.AuthOutcome.OK -> {
                if (type == IoCtrlMessages.SET_RESOLUTION_RESP) {
                    synchronized(liveLock) {
                        authOk = true
                        val i = info
                        if (streaming && i != null) setLiveState(LinkState.Streaming(i, true))
                    }
                }
                return true
            }
            IoCtrlMessages.AuthOutcome.WRONG_PASSWORD ->
                failWith(LinkError(LinkErrorKind.WRONG_PASSWORD, PpppCodes.APP_WRONG_PASSWORD), REASON_ERROR)
            else -> failWith(LinkError(LinkErrorKind.PROTOCOL, PpppCodes.APP_MANUAL_RETRY), REASON_ERROR)
        }
        return false
    }
    // END_BLOCK_AUTH_REPLY

    // START_BLOCK_FIRST_VIDEO
    private fun onVideoBody(ch: Int, body: ByteArray) {
        val (frameInfo, payload) = TnpCodec.splitFrameUnit(body, bigEndian = true) ?: return
        val unit = VideoUnit(ch, frameInfo, payload)
        if (videoUnits.incrementAndGet() == 1) {
            Timber.d(
                "[P2P][tnp][BLOCK_FIRST_VIDEO] cam=%d ch=%d codecId=%d key=%b ms=%d",
                camIndex, ch, frameInfo.codecId, frameInfo.isKeyFrame, now() - startMs,
            )
            // Same lock as the auth reply, so whichever runs second publishes the final authOk.
            synchronized(liveLock) {
                streaming = true
                info?.let { setLiveState(LinkState.Streaming(it, authOk)) }
            }
        }
        sink?.onVideoUnit(unit)
    }
    // END_BLOCK_FIRST_VIDEO

    // START_BLOCK_AUDIO_UNIT
    // ch1: only ioType 2 with a body longer than the 24-byte frame head. Encrypted = adopted header version >= 2
    // (vendor: v1 audio is plain). A sink exception drops that unit only (audio is optional), logged once.
    private fun onAudioBody(head: TnpCodec.TnpHead, body: ByteArray) {
        val s = audioSink ?: return
        if (head.ioType != TnpCodec.IOTYPE_AUDIO || body.size <= TnpCodec.FRAME_HEAD_SIZE) return
        val (frameInfo, payload) = TnpCodec.splitFrameUnit(body, bigEndian = true) ?: return
        val encrypted = headerVersion >= 2
        if (audioUnits.incrementAndGet() == 1) {
            Timber.d(
                "[P2P][tnp][BLOCK_AUDIO_UNIT] cam=%d first=true codecId=%d enc=%b",
                camIndex, frameInfo.codecId, encrypted,
            )
        }
        try {
            s.onAudioUnit(AudioUnit(TnpCodec.CH_AUDIO, frameInfo, payload, encrypted))
        } catch (e: Exception) {
            if (audioSinkErrorLogged.compareAndSet(false, true)) {
                Timber.w("[P2P][tnp][BLOCK_AUDIO_UNIT] cam=%d sinkError=%s", camIndex, e.javaClass.simpleName)
            }
        }
    }
    // END_BLOCK_AUDIO_UNIT

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
    //     (only if a connect was attempted), join of the link threads bounded by readTimeoutMs + ioJoinMarginMs,
    //     ForceClose (only with a handle), bounded join (joinMs in total), onClosed once every link thread has exited
    //     (else deferred to p2p-<n>-reap), final state.
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
        var snapshot: List<Thread> = emptyList()
        var released = "now"
        try {
            if (streaming && handle >= 0 && !handleClosed.get()) {
                val done = CountDownLatch(1)
                txQueue.offer(TxItem(buildCommand(IoCtrlMessages.STOP, IoCtrlMessages.stop()), done))
                done.await(timeouts.stopDrainMs, TimeUnit.MILLISECONDS)
            }
        } catch (e: Throwable) {
            logGuard("close", e)
        }
        try {
            snapshot = synchronized(threadLock) {
                stopping = true
                threads.toList()
            }
            if (connectAttempted) native.connectBreak(credentials.p2pId)
            txQueue.offer(poison)
            // Readers / writer leave at their next stopping check (<= one read timeout); join them before ForceClose.
            val ioDeadline = minOf(
                deadline,
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeouts.readTimeoutMs + timeouts.ioJoinMarginMs),
            )
            joinUntil(snapshot, ioDeadline)
        } catch (e: Throwable) {
            logGuard("close", e)
        } finally {
            runCatching { closeHandleOnce() }
        }
        try {
            joinUntil(snapshot, deadline)
        } catch (e: Throwable) {
            logGuard("close", e)
        }
        val alive = snapshot.filter { it.isAlive }
        if (alive.isEmpty()) {
            runCatching { onClosed(this) }
        } else {
            released = "deferred"
            Thread({
                alive.forEach { runCatching { it.join() } }
                runCatching { onClosed(this) }
                Timber.d("[P2P][tnp][BLOCK_CLOSE] cam=%d registry=released", camIndex)
            }, "p2p-$camIndex-reap").apply { isDaemon = true }.start()
        }
        val joinMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
        _state.update { if (it is LinkState.Failed || it is LinkState.Closed) it else final }
        Timber.d(
            "[P2P][tnp][BLOCK_CLOSE] cam=%d reason=%s units=%d joinMs=%d registry=%s",
            camIndex, reason, videoUnits.get(), joinMs, released,
        )
    }

    private fun joinUntil(snapshot: List<Thread>, deadlineNs: Long) {
        snapshot.forEach { t ->
            val left = TimeUnit.NANOSECONDS.toMillis(deadlineNs - System.nanoTime())
            if (left > 0) t.join(left)
        }
    }

    private fun closeHandleOnce() {
        val h = handle
        if (h >= 0 && handleClosed.compareAndSet(false, true)) native.forceClose(h)
    }
    // END_BLOCK_CLOSE

    private fun setLiveState(next: LinkState) {
        _state.update { if (closing.get() || it is LinkState.Failed || it is LinkState.Closed) it else next }
    }

    // START_BLOCK_THREAD_GUARD
    // Every link thread body: an escaping Throwable (sink / pipeline bug, bad key material) ends the link as
    // Failed(INTERNAL, 0) through the normal teardown instead of killing the process with the session open.
    private fun spawn(suffix: String, body: () -> Unit) {
        synchronized(threadLock) {
            if (stopping) return
            val guarded = Runnable {
                try {
                    body()
                } catch (e: Throwable) {
                    logGuard(suffix, e)
                    if (!stopping && !closing.get()) failWith(LinkError(LinkErrorKind.INTERNAL, 0), REASON_ERROR)
                }
            }
            val t = Thread(guarded, "p2p-$camIndex-$suffix").apply { isDaemon = true }
            threads += t
            t.start()
        }
    }

    // Class name only: exception messages may carry key material.
    private fun logGuard(thread: String, e: Throwable) {
        runCatching {
            Timber.w(
                "[P2P][tnp][BLOCK_THREAD_GUARD] cam=%d thread=%s error=%s",
                camIndex, thread, e.javaClass.simpleName,
            )
        }
    }
    // END_BLOCK_THREAD_GUARD

    override fun toString(): String = "TnpCameraLink(cam=$camIndex, state=${_state.value::class.simpleName})"

    companion object {
        const val FLAG_P2P: Byte = 0x49
        val FLAG_RELAY: Byte = 0xDF.toByte()
        const val DEFAULT_HEADER_VERSION = 2
        val READER_CHANNELS = listOf(
            TnpCodec.CH_COMMAND, TnpCodec.CH_AUDIO, TnpCodec.CH_IFRAME, TnpCodec.CH_PFRAME,
            TnpCodec.CH_RECORD_I, TnpCodec.CH_RECORD_P, TnpCodec.CH_NEW_LIVE, TnpCodec.CH_NEW_RECORD,
        )
        const val REASON_ERROR = "error"
        const val REASON_TIMEOUT = "timeout"
        const val REASON_KICKED = "kicked"
        private const val USE_COUNT_SET_RES = 1
        private const val USE_COUNT_START_RT = 2
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
