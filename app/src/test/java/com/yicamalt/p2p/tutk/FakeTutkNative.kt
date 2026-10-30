// FILE: FakeTutkNative.kt
// VERSION: 0.5.0
// START_MODULE_CONTRACT
//   PURPOSE: Scriptable JVM fake of TutkNative: records every call (with the calling thread name) and returns
//     configured codes, so runtime and link tests run without the vendor libraries.
//   SCOPE: scripted init / version / session / client / ioctrl / recv / audio / ioctrl-reply results; blocking initialize gate; value-free
//     call trace (the uid and password are never recorded).
//   DEPENDS: M-TUTK-LINK (TutkNative, TutkCheck, ClientStart, RecvResult, IoCtrlResult, TutkCodes)
//   LINKS: V-M-TUTK-LINK
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.p2p.tutk

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// START_MODULE_MAP
//   FakeTutkNative - TutkNative fake; calls (names), threads (thread per call), callCount(name).
//   initGate - when set, initialize() waits on it (bounded 10 s) to simulate a slow native init.
//   recvQueue - scripted RecvResult values with optional payload; empty queue returns AV_ER_DATA_NOREADY.
//   connectGate / clientStartGate - block connectByUid / clientStart; connectStop / stop abort them.
//   lastUid / lastUser / lastPass - last secret inputs, kept in memory for assertions only.
//   abortedClientStartResult - what an aborted clientStart returns (default AV_ER_CLIENT_EXIT; a late av >= 0
//     simulates avClientStart2 returning a channel after the session was released).
//   connectIgnoresStop - connectStop does not release connectGate (a native connect that cannot be aborted).
//   recvDelayMs / recvInFlight / recvInFlightAtStop / bufSizes - slow recvFrame (delay also applies to recvAudio and
//     recvIoCtrl), reads running now / at the first
//     stop call, and the buffer size passed to each recvFrame. recvInFlight also counts running recvAudio and
//     recvIoCtrl calls, so recvInFlightAtStop proves every reader left before stop.
//   audioQueue - scripted recvAudio frames; empty queue returns AV_ER_DATA_NOREADY, or audioEndCode when set.
//   ioCtrlQueue / IoCtrlReply - scripted recvIoCtrl replies (type + payload); empty queue returns AV_ER_TIMEOUT.
//   cleanAudioBufResult - return code of cleanAudioBuf.
//   ioCtrlEndCode - recvIoCtrl returns this code once ioCtrlQueue is drained (else AV_ER_TIMEOUT).
//   recvAudioAtNs - System.nanoTime() of every recvAudio call (poll pacing assertions).
//   sendIoCtrlBlock / sendThreads / sendInFlight / sendInFlightAtStop - a latch that holds every sendIoCtrl except
//     767 (a writer blocked in avSendIOCtrl), the calling thread name per sendIoCtrl, sends running now / at the
//     first stop call.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.5.0 - GSD plan 03-04: sendIoCtrlBlock latch, sendThreads, sendInFlight(AtStop), ioCtrlEndCode,
//     recvAudioAtNs for the TUTK tx / rxc / rxa thread tests.
//   PREVIOUS: v0.4.0 - GSD plan 03-03: audioQueue / audioEndCode, ioCtrlQueue / IoCtrlReply, cleanAudioBuf;
//     in-flight tracking covers recvAudio and recvIoCtrl.
//   PREVIOUS: v0.3.0 - Phase 2 review MED-5 / MED-6 / LOW-8 / LOW-9: abortedClientStartResult, connectIgnoresStop,
//     recvDelayMs with in-flight tracking, recorded buffer sizes.
//   PREVIOUS: v0.2.0 - GSD plan 02-11: blocking connect / clientStart gates with vendor-like aborts
//     (IOTC_ER_ABORTED, AV_ER_CLIENT_EXIT) and in-memory capture of uid / user / pass for TutkCameraLinkTest.
//   PREVIOUS: v0.1.0 - GSD plan 02-10: created for TutkRuntimeTest.
// END_CHANGE_SUMMARY

class FakeTutkNative : TutkNative {

    var initResult: Int = TutkCodes.OK
    var avInitResult: Int = TutkRuntime.MAX_AV_CHANNELS
    var iotcVersionValue: Int = 0x01020304
    var avVersionValue: Int = 0x04030201
    var sessionIdResult: Int = 0
    var connectResult: Int = 0
    var connectStopResult: Int = TutkCodes.OK
    var checkResult: TutkCheck = TutkCheck(TutkCodes.OK, 0, false)
    var clientStartResult: ClientStart = ClientStart(0, 0)
    var sendIoCtrlResult: Int = TutkCodes.OK
    var initGate: CountDownLatch? = null

    // When set, connectByUid blocks until connectStop (-> IOTC_ER_ABORTED) or the gate is released (-> connectResult).
    var connectGate: CountDownLatch? = null

    // When set, clientStart blocks until stop (-> AV_ER_CLIENT_EXIT) or the gate is released (-> clientStartResult).
    var clientStartGate: CountDownLatch? = null

    @Volatile var abortedClientStartResult: ClientStart = ClientStart(TutkCodes.AV_CLIENT_EXIT, 0)
    @Volatile var connectIgnoresStop = false
    @Volatile var recvDelayMs = 0L
    val recvInFlight = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile var recvInFlightAtStop = -1
    val bufSizes = CopyOnWriteArrayList<Int>()

    @Volatile private var connectAborted = false
    @Volatile private var clientAborted = false

    // Held in memory only for assertions; never written into calls / threads.
    @Volatile var lastUid: String? = null
    @Volatile var lastUser: String? = null
    @Volatile var lastPass: String? = null

    data class Frame(val result: RecvResult, val payload: ByteArray = ByteArray(0), val info: ByteArray = ByteArray(0))

    val recvQueue = ConcurrentLinkedQueue<Frame>()

    data class IoCtrlReply(val type: Int, val payload: ByteArray = ByteArray(0))

    val audioQueue = ConcurrentLinkedQueue<Frame>()
    val ioCtrlQueue = ConcurrentLinkedQueue<IoCtrlReply>()

    // When set, recvAudio returns this code once audioQueue is drained (e.g. AV_ER_SESSION_CLOSE_BY_REMOTE).
    @Volatile var audioEndCode: Int? = null
    @Volatile var cleanAudioBufResult: Int = TutkCodes.OK

    // When set, recvIoCtrl returns this code once ioCtrlQueue is drained (e.g. AV_ER_SESSION_CLOSE_BY_REMOTE).
    @Volatile var ioCtrlEndCode: Int? = null
    val recvAudioAtNs = CopyOnWriteArrayList<Long>()

    // When set, every sendIoCtrl except 767 waits on it (bounded 10 s): a writer blocked inside avSendIOCtrl.
    @Volatile var sendIoCtrlBlock: CountDownLatch? = null
    val sendThreads = CopyOnWriteArrayList<String>()
    val sendInFlight = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile var sendInFlightAtStop = -1

    val calls = CopyOnWriteArrayList<String>()
    val threads = CopyOnWriteArrayList<String>()
    val sentIoCtrl = CopyOnWriteArrayList<Pair<Int, ByteArray>>()

    fun callCount(name: String): Int = calls.count { it == name || it.startsWith("$name ") }

    private fun record(entry: String) {
        calls += entry
        threads += Thread.currentThread().name
    }

    override fun initialize(): Int {
        record("initialize")
        initGate?.await(10, TimeUnit.SECONDS)
        return initResult
    }

    override fun avInitialize(maxChannels: Int): Int {
        record("avInitialize $maxChannels")
        return avInitResult
    }

    override fun iotcVersion(): Int {
        record("iotcVersion")
        return iotcVersionValue
    }

    override fun avVersion(): Int {
        record("avVersion")
        return avVersionValue
    }

    override fun sessionId(): Int {
        record("sessionId")
        return sessionIdResult
    }

    override fun connectByUid(uid: String, sid0: Int): Int {
        record("connectByUid sid0=$sid0")
        lastUid = uid
        connectGate?.await(10, TimeUnit.SECONDS)
        return if (connectAborted) TutkCodes.IOTC_ABORTED else connectResult
    }

    override fun connectStop(sid0: Int): Int {
        record("connectStop sid0=$sid0")
        connectAborted = true
        if (!connectIgnoresStop) connectGate?.countDown()
        return connectStopResult
    }

    override fun check(sid: Int): TutkCheck {
        record("check sid=$sid")
        return checkResult
    }

    override fun clientStart(sid: Int, user: String, pass: String, timeoutS: Int): ClientStart {
        record("clientStart sid=$sid timeoutS=$timeoutS")
        lastUser = user
        lastPass = pass
        clientStartGate?.await(10, TimeUnit.SECONDS)
        return if (clientAborted) abortedClientStartResult else clientStartResult
    }

    override fun sendIoCtrl(av: Int, type: Int, payload: ByteArray): Int {
        sendInFlight.incrementAndGet()
        try {
            record("sendIoCtrl av=$av type=$type size=${payload.size}")
            sendThreads += Thread.currentThread().name
            sentIoCtrl += type to payload.copyOf()
            if (type != STOP_TYPE) sendIoCtrlBlock?.await(10, TimeUnit.SECONDS)
            return sendIoCtrlResult
        } finally {
            sendInFlight.decrementAndGet()
        }
    }

    override fun recvFrame(av: Int, buf: ByteArray, frameInfo: ByteArray): RecvResult {
        recvInFlight.incrementAndGet()
        try {
            record("recvFrame av=$av")
            bufSizes += buf.size
            if (recvDelayMs > 0) Thread.sleep(recvDelayMs)
            return recvNext(buf, frameInfo)
        } finally {
            recvInFlight.decrementAndGet()
        }
    }

    private fun recvNext(buf: ByteArray, frameInfo: ByteArray): RecvResult {
        val next = recvQueue.poll() ?: return RecvResult(TutkCodes.AV_DATA_NOREADY, 0)
        next.payload.copyInto(buf, 0, 0, minOf(next.payload.size, buf.size))
        next.info.copyInto(frameInfo, 0, 0, minOf(next.info.size, frameInfo.size))
        return next.result
    }

    override fun cleanAudioBuf(av: Int): Int {
        record("cleanAudioBuf av=$av")
        return cleanAudioBufResult
    }

    override fun recvAudio(av: Int, buf: ByteArray, frameInfo: ByteArray): RecvResult {
        recvInFlight.incrementAndGet()
        try {
            record("recvAudio av=$av")
            recvAudioAtNs += System.nanoTime()
            if (recvDelayMs > 0) Thread.sleep(recvDelayMs)
            val next = audioQueue.poll()
                ?: return RecvResult(audioEndCode ?: TutkCodes.AV_DATA_NOREADY, 0)
            next.payload.copyInto(buf, 0, 0, minOf(next.payload.size, buf.size))
            next.info.copyInto(frameInfo, 0, 0, minOf(next.info.size, frameInfo.size))
            return next.result
        } finally {
            recvInFlight.decrementAndGet()
        }
    }

    override fun recvIoCtrl(av: Int, buf: ByteArray, timeoutMs: Int): IoCtrlResult {
        recvInFlight.incrementAndGet()
        try {
            record("recvIoCtrl av=$av timeoutMs=$timeoutMs")
            if (recvDelayMs > 0) Thread.sleep(recvDelayMs)
            val next = ioCtrlQueue.poll() ?: return IoCtrlResult(ioCtrlEndCode ?: TutkCodes.AV_TIMEOUT, 0)
            val n = minOf(next.payload.size, buf.size)
            next.payload.copyInto(buf, 0, 0, n)
            return IoCtrlResult(n, next.type)
        } finally {
            recvInFlight.decrementAndGet()
        }
    }

    override fun stop(av: Int, sid: Int) {
        if (recvInFlightAtStop < 0) recvInFlightAtStop = recvInFlight.get()
        if (sendInFlightAtStop < 0) sendInFlightAtStop = sendInFlight.get()
        record("stop av=$av sid=$sid")
        if (sid >= 0) {
            clientAborted = true
            clientStartGate?.countDown()
        }
    }

    private companion object {
        const val STOP_TYPE = 767
    }
}
