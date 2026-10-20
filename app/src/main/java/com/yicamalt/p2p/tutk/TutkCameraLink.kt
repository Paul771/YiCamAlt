// FILE: TutkCameraLink.kt
// VERSION: 0.2.0
// START_MODULE_CONTRACT
//   PURPOSE: One TUTK IOTC/AV session per camera behind the shared CameraLink port, vendor call order.
//   SCOPE: TutkCameraLink (IOTC_Get_SessionID -> IOTC_Connect_ByUID_Parallel with the cloud uid -> Session_Check
//     mode mapping -> avClientStart2 with nonce + HMAC (or admin) -> 8190 / 511 start -> avRecvFrameData2 loop with
//     little-endian FrameInfo and the vendor fatal-code list, connect and first-frame timers, thread guard, bounded
//     idempotent teardown with separate AV / session release) and TutkLinkFactory (credential checks, process-once
//     TutkRuntime init, one session per camera).
//   DEPENDS: M-TUTK-LINK (TutkNative, TutkCodes, TutkRuntime), M-STREAM-CORE (CameraLink, FrameHeadCodec,
//     IoCtrlMessages, YiP2pAuth), M-P2P-CREDS (P2pCredentials), M-TNP-LINK (FailedLink)
//   LINKS: M-TUTK-LINK, V-M-TUTK-LINK
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.p2p.tutk

import com.yicamalt.camera.P2pCredentials
import com.yicamalt.camera.TransportType
import com.yicamalt.p2p.tnp.FailedLink
import com.yicamalt.stream.CameraLink
import com.yicamalt.stream.CameraLinkFactory
import com.yicamalt.stream.FrameHeadCodec
import com.yicamalt.stream.IoCtrlMessages
import com.yicamalt.stream.LinkError
import com.yicamalt.stream.LinkErrorKind
import com.yicamalt.stream.LinkInfo
import com.yicamalt.stream.LinkMode
import com.yicamalt.stream.LinkOptions
import com.yicamalt.stream.LinkState
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

// START_MODULE_MAP
//   TutkCameraLink - CameraLink over TutkNative: start(sink) spawns tutk-<n>-watch / -connect (-rx later), close(reason).
//   TutkCameraLink.Timeouts - connectMs 30000, firstFrameMs 20000, clientStartS 15, noDataSleepMs 30,
//     maxNoDataPolls 300, joinMs 2000, pollMs 20, ioJoinMarginMs 200.
//   TutkCameraLink.modeOf - vendor Session_Check mode 0 P2P, 1 RELAY, 2 LAN, else UNKNOWN.
//   TutkCameraLink.AV_CHANNEL / FRAME_BUF_SIZE / LARGE_FRAME_BUF_SIZE / NONCE_LEN / PLAIN_USER - 0, 300000, 1000000,
//     15, "admin".
//   TutkCameraLink.FATAL_READ_CODES - avRecvFrameData2 codes that end the session (vendor TutkCamera ~1176).
//   TutkLinkFactory - @Singleton CameraLinkFactory for TUTK cameras; open() applies the checks of its contract.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.2.0 - Phase 2 review fixes.
//     MED-5: the read loop ends the session only on the vendor fatal codes -20015, -20016, -20010, -20000, -20018,
//       -20019, -20025, -20026 (FATAL_READ_CODES); any other negative code is logged once per code (symbolic name)
//       and reading continues after noDataSleepMs; maxNoDataPolls such codes in a row end the session. -20001 (buffer
//       too small) enlarges the frame buffer once to LARGE_FRAME_BUF_SIZE and drops to the next key frame.
//     MED-6: the AV channel and the IOTC session are released independently (avReleased / sidReleased), so an
//       avClientStart2 that returns after close() already released the session still gets avClientStop.
//     MED-7: every link thread body runs under a guard: an uncaught Throwable ends the link as Failed(INTERNAL, 0)
//       with full teardown; the factory refuses an empty password when p2pEncrypt is on.
//     LOW-8: onClosed (registry release) runs only after every link thread has exited (else tutk-<n>-reap waits),
//       and before the final state is published.
//     LOW-9: the reader uses a local av copy and re-checks the stop flags before each avRecvFrameData2; the vendor
//       stop (767 -> SendIOCtrlExit -> ClientStop -> ClientExit -> Session_Close) runs after the reader was joined
//       (bounded by noDataSleepMs + ioJoinMarginMs).
//     INVARIANTS: one connectByUid per start(), no reconnect and no retry inside the link (the single automatic retry
//       on WRONG_PASSWORD lives in LiveProbeViewModel); -20012 polls sleep noDataSleepMs and end the session as
//       TIMEOUT(-20012) after maxNoDataPolls in a row; -20013 / -20014 drop frames until the next key frame without
//       leaving Streaming; every native call runs on a named thread of this link (tutk-<n>-connect | -rx | -close);
//       the cloud uid, password, nonce and HMAC never reach logs, LinkError, LinkInfo or toString; cam=<n> is a
//       per-process counter. LinkOptions.forceRelay has no IOTC equivalent and is ignored.
//   PREVIOUS: v0.1.0 - GSD plan 02-11: created from 02-RESEARCH.md Pattern 8, mirroring TnpCameraLink.
// END_CHANGE_SUMMARY

internal class TutkCameraLink(
    private val credentials: P2pCredentials,
    @Suppress("unused") private val options: LinkOptions,
    private val native: TutkNative,
    private val camIndex: Int,
    private val rnd: Random = SecureRandom(),
    private val timeouts: Timeouts = Timeouts(),
    private val now: () -> Long = { System.nanoTime() / 1_000_000L },
    private val onClosed: (TutkCameraLink) -> Unit,
) : CameraLink {

    data class Timeouts(
        val connectMs: Long = 30_000,
        val firstFrameMs: Long = 20_000,
        val clientStartS: Int = 15,
        val noDataSleepMs: Long = 30,
        val maxNoDataPolls: Int = 300,
        val joinMs: Long = 2_000,
        val pollMs: Long = 20,
        val ioJoinMarginMs: Long = 200,
    )

    override val deviceId: String = credentials.deviceId

    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    override val state: StateFlow<LinkState> = _state.asStateFlow()

    private val started = AtomicBoolean(false)
    private val closing = AtomicBoolean(false)
    private val avReleased = AtomicBoolean(false)
    private val sidReleased = AtomicBoolean(false)
    private val videoUnits = AtomicInteger(0)
    private val dropped = AtomicInteger(0)
    private val threadLock = Any()
    private val releaseLock = Any()
    private val threads = CopyOnWriteArrayList<Thread>()

    @Volatile private var stopping = false
    @Volatile private var connectAttempted = false
    @Volatile private var connectReturned = false
    @Volatile private var connectDone = false
    @Volatile private var sid0 = -1
    @Volatile private var sid = -1
    @Volatile private var av = -1
    @Volatile private var sink: VideoUnitSink? = null
    @Volatile private var info: LinkInfo? = null
    @Volatile private var streaming = false
    @Volatile private var startMs = 0L
    @Volatile private var startCmdAtMs = -1L

    // START_CONTRACT: start
    //   PURPOSE: Begin the session without blocking the caller.
    //   INPUTS: { sink: VideoUnitSink - receives video units from tutk-<n>-rx }
    //   OUTPUTS: { Unit - state goes Connecting; a second call (or a call after close) is ignored }
    //   SIDE_EFFECTS: spawns tutk-<n>-watch and tutk-<n>-connect
    //   LINKS: M-TUTK-LINK
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
    //   LINKS: M-TUTK-LINK
    // END_CONTRACT: close
    override fun close(reason: String) {
        started.set(true)
        terminate(reason, LinkState.Closed(reason))
    }

    // START_CONTRACT: runConnect
    //   PURPOSE: Vendor order on tutk-<n>-connect: sessionId -> connectByUid(cloud uid, sid0) -> check -> clientStart
    //     (15-char nonce user + 15-char HMAC pass when p2pEncrypt, else admin + password) -> sendIoCtrl 8190 (or 511)
    //     with u32 LE useCount 1 -> spawn tutk-<n>-rx.
    //   INPUTS: none (credentials)
    //   OUTPUTS: { Unit - state Connected(info), later Streaming, or a terminal state via failWith }
    //   SIDE_EFFECTS: exactly one connectByUid per link; never initialize / avInitialize (TutkRuntime owns them)
    //   LINKS: TutkNative.sessionId, TutkNative.connectByUid, TutkNative.check, TutkNative.clientStart
    // END_CONTRACT: runConnect
    private fun runConnect() {
        // START_BLOCK_CONNECT
        val t0 = now()
        val s0 = native.sessionId()
        if (s0 < 0) {
            Timber.d("[P2P][tutk][BLOCK_CONNECT] cam=%d step=sessionId result=%s", camIndex, TutkCodes.name(s0))
            if (!stopping && !closing.get()) failWith(LinkError(TutkCodes.classify(s0), s0), REASON_ERROR)
            return
        }
        // Under the thread lock so a concurrent teardown either sees the attempt (and stops it) or we never connect.
        synchronized(threadLock) {
            sid0 = s0
            if (stopping) return
            connectAttempted = true
        }
        val s = native.connectByUid(credentials.p2pId, s0)
        connectReturned = true
        val connectMs = (now() - t0).toInt()
        Timber.d(
            "[P2P][tutk][BLOCK_CONNECT] cam=%d step=connect result=%s ms=%d",
            camIndex, if (s >= 0) "ok" else TutkCodes.name(s), connectMs,
        )
        if (s < 0) {
            if (!stopping && !closing.get()) failWith(LinkError(TutkCodes.classify(s), s), REASON_ERROR)
            return
        }
        sid = s
        if (stopping || closing.get()) {
            releaseOnce()
            return
        }
        // END_BLOCK_CONNECT

        // START_BLOCK_CHECK_SESSION
        val check = native.check(s)
        if (check.code < 0) {
            Timber.d("[P2P][tutk][BLOCK_CHECK_SESSION] cam=%d result=%s", camIndex, TutkCodes.name(check.code))
            if (!stopping && !closing.get()) failWith(LinkError(TutkCodes.classify(check.code), check.code), REASON_ERROR)
            return
        }
        val mode = modeOf(check.mode)
        val lan = mode == LinkMode.LAN || (mode == LinkMode.P2P && check.remoteIsPrivate)
        val linkInfo = LinkInfo(TransportType.TUTK, mode, lan, connectMs)
        info = linkInfo
        Timber.d(
            "[P2P][tutk][BLOCK_CHECK_SESSION] cam=%d mode=%s lan=%b connectMs=%d",
            camIndex, mode, lan, connectMs,
        )
        setLiveState(LinkState.Connected(linkInfo))
        // END_BLOCK_CHECK_SESSION

        // START_BLOCK_CLIENT_START
        if (stopping || closing.get()) return
        val encrypted = credentials.p2pEncrypt
        val password = credentials.password.orEmpty()
        val user: String
        val pass: String
        if (encrypted) {
            user = YiP2pAuth.nonce(NONCE_LEN, rnd)
            pass = YiP2pAuth.hmacPassword(password, user)
        } else {
            user = PLAIN_USER
            pass = password
        }
        val cs = native.clientStart(s, user, pass, timeouts.clientStartS)
        Timber.d(
            "[P2P][tutk][BLOCK_CLIENT_START] cam=%d encrypted=%b result=%s",
            camIndex, encrypted, if (cs.av >= 0) "ok" else TutkCodes.name(cs.av),
        )
        if (cs.av < 0) {
            if (!stopping && !closing.get()) failWith(LinkError(TutkCodes.classify(cs.av), cs.av), REASON_ERROR)
            return
        }
        av = cs.av
        if (stopping || closing.get()) {
            // A clientStart that returns after close(): the session may already be gone, the AV channel is not.
            releaseOnce()
            return
        }
        connectDone = true

        val cmd = if (encrypted) IoCtrlMessages.TUTK_START_ENC else IoCtrlMessages.TUTK_START_PLAIN
        startCmdAtMs = now()
        spawn("rx") { readLoop() }
        if (stopping || closing.get()) return
        val sent = native.sendIoCtrl(cs.av, cmd, IoCtrlMessages.tutkStart(START_USE_COUNT))
        Timber.d(
            "[P2P][tutk][BLOCK_CLIENT_START] cam=%d cmd=%d result=%s",
            camIndex, cmd, if (sent >= 0) "ok" else TutkCodes.name(sent),
        )
        if (sent < 0 && !stopping && !closing.get()) failWith(LinkError(TutkCodes.classify(sent), sent), REASON_ERROR)
        // END_BLOCK_CLIENT_START
    }

    // START_BLOCK_READ_LOOP
    // START_CONTRACT: readLoop
    //   PURPOSE: tutk-<n>-rx: avRecvFrameData2 polling into a 300000-byte buffer and a 24-byte FRAMEINFO.
    //   INPUTS: none
    //   OUTPUTS: { Unit - > 0: one VideoUnit (FrameInfo parsed little-endian) to the sink; -20012: sleep
    //     noDataSleepMs, after maxNoDataPolls in a row Failed(TIMEOUT, -20012); -20013 / -20014: drop frames until the
    //     next key frame; -20001: buffer enlarged once to LARGE_FRAME_BUF_SIZE, drop to the next key frame;
    //     FATAL_READ_CODES: Failed(classify(code)) (e.g. -20015 REMOTE_CLOSED), no reconnect; any other negative code:
    //     logged once per code, sleep noDataSleepMs, keep reading (maxNoDataPolls in a row -> Failed(classify(code)));
    //     stopping / released AV channel: quiet exit }
    //   SIDE_EFFECTS: one BLOCK_READ_LOOP log line on exit; reads only the av captured at start
    //   LINKS: TutkNative.recvFrame, FrameHeadCodec.parse, VideoUnitSink.onVideoUnit
    // END_CONTRACT: readLoop
    private fun readLoop() {
        val avLocal = av
        var buf = ByteArray(FRAME_BUF_SIZE)
        val head = ByteArray(FrameHeadCodec.SIZE)
        val skipped = HashSet<Int>()
        var noData = 0
        var otherErrors = 0
        var dropping = false
        var exit = EXIT_CLOSED
        var code = 0
        while (!stopping && !closing.get() && !avReleased.get()) {
            val r = native.recvFrame(avLocal, buf, head)
            when {
                r.code > 0 -> {
                    noData = 0
                    otherErrors = 0
                    val size = r.size.coerceIn(0, buf.size)
                    if (size == 0) continue
                    val frameInfo = FrameHeadCodec.parse(head, 0, bigEndian = false)
                    if (dropping) {
                        if (!frameInfo.isKeyFrame) {
                            dropped.incrementAndGet()
                            continue
                        }
                        dropping = false
                    }
                    onVideo(VideoUnit(AV_CHANNEL, frameInfo, buf.copyOf(size)))
                }
                r.code == TutkCodes.AV_DATA_NOREADY -> {
                    noData++
                    if (noData >= timeouts.maxNoDataPolls) {
                        exit = EXIT_NO_DATA
                        code = r.code
                        break
                    }
                    if (!pause()) break
                }
                r.code == TutkCodes.AV_INCOMPLETE_FRAME || r.code == TutkCodes.AV_LOSED_THIS_FRAME -> {
                    noData = 0
                    dropping = true
                    dropped.incrementAndGet()
                }
                r.code in FATAL_READ_CODES -> {
                    exit = EXIT_ERROR
                    code = r.code
                    break
                }
                else -> {
                    if (r.code == TutkCodes.AV_BUFPARA_MAXSIZE_INSUFF) {
                        if (buf.size < LARGE_FRAME_BUF_SIZE) buf = ByteArray(LARGE_FRAME_BUF_SIZE)
                        dropping = true
                        dropped.incrementAndGet()
                    }
                    if (skipped.add(r.code)) {
                        Timber.d(
                            "[P2P][tutk][BLOCK_READ_LOOP] cam=%d skip=%s buf=%d",
                            camIndex, TutkCodes.name(r.code), buf.size,
                        )
                    }
                    otherErrors++
                    if (otherErrors >= timeouts.maxNoDataPolls) {
                        exit = EXIT_ERROR
                        code = r.code
                        break
                    }
                    if (!pause()) break
                }
            }
        }
        Timber.d(
            "[P2P][tutk][BLOCK_READ_LOOP] cam=%d exit=%s code=%s units=%d dropped=%d",
            camIndex, exit, TutkCodes.name(code), videoUnits.get(), dropped.get(),
        )
        if (stopping || closing.get()) return
        when (exit) {
            EXIT_NO_DATA -> failWith(LinkError(LinkErrorKind.TIMEOUT, code), REASON_TIMEOUT)
            EXIT_ERROR -> failWith(LinkError(TutkCodes.classify(code), code), REASON_ERROR)
        }
    }

    // The vendor 30 ms pause between polls; false when interrupted.
    private fun pause(): Boolean = try {
        Thread.sleep(timeouts.noDataSleepMs)
        true
    } catch (e: InterruptedException) {
        false
    }

    private fun onVideo(unit: VideoUnit) {
        if (videoUnits.incrementAndGet() == 1) {
            streaming = true
            Timber.d(
                "[P2P][tutk][BLOCK_READ_LOOP] cam=%d first=video codecId=%d key=%b ms=%d",
                camIndex, unit.info.codecId, unit.info.isKeyFrame, now() - startMs,
            )
            info?.let { setLiveState(LinkState.Streaming(it, true)) }
        }
        sink?.onVideoUnit(unit)
    }
    // END_BLOCK_READ_LOOP

    // START_BLOCK_WATCH
    // tutk-<n>-watch: connect (connect + check + clientStart) > connectMs -> TIMEOUT(-13);
    // no video unit firstFrameMs after the start command -> TIMEOUT(0). Exits on the first video unit or on close.
    private fun runWatch() {
        while (!closing.get()) {
            val t = now()
            if (!connectDone) {
                if (t - startMs >= timeouts.connectMs) {
                    failWith(LinkError(LinkErrorKind.TIMEOUT, TutkCodes.IOTC_TIMEOUT), REASON_TIMEOUT)
                    return
                }
            } else {
                if (videoUnits.get() > 0) return
                val b = startCmdAtMs
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
    //   PURPOSE: The single teardown path for close(), failures and timeouts; runs once.
    //   INPUTS: { reason: String - BLOCK_CLOSE reason; final: LinkState - Closed(reason) or Failed(error) }
    //   OUTPUTS: { Unit - external callers wait for tutk-<n>-close up to joinMs; link threads do not wait }
    //   SIDE_EFFECTS: on tutk-<n>-close: stop flag; connectStop(sid0) while connectByUid is still blocking; join of
    //     tutk-<n>-rx bounded by noDataSleepMs + ioJoinMarginMs; vendor stop for what exists (AV channel and session
    //     released independently, each at most once; stop also aborts a blocking clientStart); bounded join (joinMs
    //     in total); stop(-1, sid0) once when only the pre-allocated session id exists; onClosed once every link
    //     thread has exited (else deferred to tutk-<n>-reap); final state.
    //   LINKS: TutkNative.connectStop, TutkNative.stop
    // END_CONTRACT: terminate
    private fun terminate(reason: String, final: LinkState) {
        if (!closing.compareAndSet(false, true)) return
        val closer = Thread({ teardown(reason, final) }, "tutk-$camIndex-close").apply { isDaemon = true }
        closer.start()
        if (Thread.currentThread() !in threads) closer.join(timeouts.joinMs + timeouts.pollMs)
    }

    private fun teardown(reason: String, final: LinkState) {
        val t0 = System.nanoTime()
        val deadline = t0 + TimeUnit.MILLISECONDS.toNanos(timeouts.joinMs)
        var snapshot: List<Thread> = emptyList()
        var registry = "now"
        try {
            snapshot = synchronized(threadLock) {
                stopping = true
                threads.toList()
            }
            if (connectAttempted && !connectReturned) native.connectStop(sid0)
            // The reader leaves at its next flag check (<= one no-data sleep); join it before the AV channel goes.
            val ioDeadline = minOf(
                deadline,
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeouts.noDataSleepMs + timeouts.ioJoinMarginMs),
            )
            joinUntil(snapshot.filter { it.name == "tutk-$camIndex-rx" }, ioDeadline)
            if (sid >= 0 || av >= 0) releaseOnce()
            joinUntil(snapshot, deadline)
            releaseOnce()
        } catch (e: Throwable) {
            logGuard("close", e)
            runCatching { releaseOnce() }
        }
        val alive = snapshot.filter { it.isAlive }
        if (alive.isEmpty()) {
            runCatching { onClosed(this) }
        } else {
            registry = "deferred"
            Thread({
                alive.forEach { runCatching { it.join() } }
                runCatching { onClosed(this) }
                Timber.d("[P2P][tutk][BLOCK_CLOSE] cam=%d registry=released", camIndex)
            }, "tutk-$camIndex-reap").apply { isDaemon = true }.start()
        }
        val joinMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
        _state.update { if (it is LinkState.Failed || it is LinkState.Closed) it else final }
        Timber.d(
            "[P2P][tutk][BLOCK_CLOSE] cam=%d reason=%s units=%d joinMs=%d registry=%s",
            camIndex, reason, videoUnits.get(), joinMs, registry,
        )
    }

    private fun joinUntil(list: List<Thread>, deadlineNs: Long) {
        list.forEach { t ->
            val left = TimeUnit.NANOSECONDS.toMillis(deadlineNs - System.nanoTime())
            if (left > 0) t.join(left)
        }
    }

    // START_CONTRACT: releaseOnce
    //   PURPOSE: Vendor stop for whatever exists, each half at most once: the AV channel (767, SendIOCtrlExit,
    //     avClientStop) and the session (avClientExit, Session_Close; sid, else the pre-allocated sid0).
    //   INPUTS: none (av, sid, sid0)
    //   OUTPUTS: none
    //   SIDE_EFFECTS: at most one native.stop per call; serialised so the halves never interleave
    //   LINKS: TutkNative.stop
    // END_CONTRACT: releaseOnce
    private fun releaseOnce() {
        synchronized(releaseLock) {
            val a = av
            val s = if (sid >= 0) sid else sid0
            val doAv = a >= 0 && avReleased.compareAndSet(false, true)
            val doSid = s >= 0 && sidReleased.compareAndSet(false, true)
            if (doAv || doSid) native.stop(if (doAv) a else -1, if (doSid) s else -1)
        }
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
            val t = Thread(guarded, "tutk-$camIndex-$suffix").apply { isDaemon = true }
            threads += t
            t.start()
        }
    }

    // Class name only: exception messages may carry key material.
    private fun logGuard(thread: String, e: Throwable) {
        runCatching {
            Timber.w(
                "[P2P][tutk][BLOCK_THREAD_GUARD] cam=%d thread=%s error=%s",
                camIndex, thread, e.javaClass.simpleName,
            )
        }
    }
    // END_BLOCK_THREAD_GUARD

    override fun toString(): String = "TutkCameraLink(cam=$camIndex, state=${_state.value::class.simpleName})"

    companion object {
        const val AV_CHANNEL = 0
        const val FRAME_BUF_SIZE = 300_000
        const val LARGE_FRAME_BUF_SIZE = 1_000_000
        const val NONCE_LEN = 15
        const val PLAIN_USER = "admin"
        const val START_USE_COUNT = 1
        const val REASON_ERROR = "error"
        const val REASON_TIMEOUT = "timeout"
        private const val EXIT_CLOSED = "closed"
        private const val EXIT_ERROR = "error"
        private const val EXIT_NO_DATA = "no-data"

        // Vendor TutkCamera read loop closes the camera only on these avRecvFrameData2 codes.
        val FATAL_READ_CODES: Set<Int> = setOf(
            TutkCodes.AV_SESSION_CLOSE_BY_REMOTE, TutkCodes.AV_REMOTE_TIMEOUT_DISCONNECT, TutkCodes.AV_INVALID_SID,
            TutkCodes.AV_INVALID_ARG, TutkCodes.AV_CLIENT_EXIT, TutkCodes.AV_NOT_INITIALIZED,
            TutkCodes.AV_IOTC_SESSION_CLOSED, TutkCodes.AV_IOTC_DEINITIALIZED,
        )

        // Vendor Session_Check mode: 0 P2P, 1 Relay, 2 LAN.
        fun modeOf(mode: Int): LinkMode = when (mode) {
            0 -> LinkMode.P2P
            1 -> LinkMode.RELAY
            2 -> LinkMode.LAN
            else -> LinkMode.UNKNOWN
        }
    }
}

@Singleton
class TutkLinkFactory @Inject constructor(
    private val native: TutkNative,
    private val runtime: TutkRuntime,
) : CameraLinkFactory {

    private val registry = ConcurrentHashMap<String, TutkCameraLink>()
    private val camCounter = AtomicInteger(0)

    // START_CONTRACT: open
    //   PURPOSE: Create the one TUTK session object for a camera, or a typed refusal.
    //   INPUTS: { credentials: P2pCredentials; options: LinkOptions }
    //   OUTPUTS: { CameraLink - checks in order:
    //     1. transport != TUTK -> Failed(UNSUPPORTED_TRANSPORT, 0)
    //     2. blank p2pId (the cloud uid) -> Failed(NO_CREDENTIALS, 0)
    //     3. needsPincode, or p2pEncrypt with a null or empty password -> Failed(NO_CREDENTIALS, 0)
    //     4. runtime Unavailable -> Failed(NATIVE_UNAVAILABLE, 0); InitFailed(code) -> Failed(NATIVE_UNAVAILABLE, code)
    //     5. a link for credentials.deviceId still open (its threads not yet exited) -> Failed(ALREADY_OPEN, 0)
    //     6. else a new idle TutkCameraLink (call start(sink); always close it) }
    //   SIDE_EFFECTS: steps 1-3 make no native call; step 4 may run the process-once TUTK init (blocks up to 10 s on
    //     first use, so call off the main thread); one BLOCK_OPEN_LINK log line per call.
    //   LINKS: M-TUTK-LINK, TutkRuntime.ensureInitialized
    // END_CONTRACT: open
    override fun open(credentials: P2pCredentials, options: LinkOptions): CameraLink {
        // START_BLOCK_OPEN_TUTK_LINK
        val cam = camCounter.incrementAndGet()
        val refusal: LinkError? = when {
            credentials.transport != TransportType.TUTK -> LinkError(LinkErrorKind.UNSUPPORTED_TRANSPORT, 0)
            credentials.p2pId.isBlank() -> LinkError(LinkErrorKind.NO_CREDENTIALS, 0)
            credentials.needsPincode || (credentials.p2pEncrypt && credentials.password.isNullOrEmpty()) ->
                LinkError(LinkErrorKind.NO_CREDENTIALS, 0)
            else -> when (val rt = runtime.ensureInitialized()) {
                is TutkRuntimeState.Ready -> null
                is TutkRuntimeState.InitFailed -> LinkError(LinkErrorKind.NATIVE_UNAVAILABLE, rt.code)
                else -> LinkError(LinkErrorKind.NATIVE_UNAVAILABLE, 0)
            }
        }
        if (refusal != null) return refuse(cam, credentials, refusal)

        var created: TutkCameraLink? = null
        registry.compute(credentials.deviceId) { _, existing ->
            existing ?: TutkCameraLink(credentials, options, native, cam, onClosed = { link ->
                registry.remove(link.deviceId, link)
            }).also { created = it }
        }
        val link = created ?: return refuse(cam, credentials, LinkError(LinkErrorKind.ALREADY_OPEN, 0))
        Timber.d("[P2P][factory][BLOCK_OPEN_LINK] cam=%d transport=%s result=started", cam, credentials.transport)
        return link
        // END_BLOCK_OPEN_TUTK_LINK
    }

    private fun refuse(cam: Int, credentials: P2pCredentials, error: LinkError): CameraLink {
        Timber.d(
            "[P2P][factory][BLOCK_OPEN_LINK] cam=%d transport=%s result=%s",
            cam, credentials.transport, error.kind.name,
        )
        return FailedLink(credentials.deviceId, error)
    }
}
