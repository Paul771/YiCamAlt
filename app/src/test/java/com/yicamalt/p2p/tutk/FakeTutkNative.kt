// FILE: FakeTutkNative.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Scriptable JVM fake of TutkNative: records every call (with the calling thread name) and returns
//     configured codes, so runtime and link tests run without the vendor libraries.
//   SCOPE: scripted init / version / session / client / ioctrl / recv results; blocking initialize gate; value-free
//     call trace (the uid and password are never recorded).
//   DEPENDS: M-TUTK-LINK (TutkNative, TutkCheck, ClientStart, RecvResult, TutkCodes)
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
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 02-10: created for TutkRuntimeTest; plan 02-11 drives TutkCameraLink with it.
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

    data class Frame(val result: RecvResult, val payload: ByteArray = ByteArray(0), val info: ByteArray = ByteArray(0))

    val recvQueue = ConcurrentLinkedQueue<Frame>()

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
        return connectResult
    }

    override fun connectStop(sid0: Int): Int {
        record("connectStop sid0=$sid0")
        return connectStopResult
    }

    override fun check(sid: Int): TutkCheck {
        record("check sid=$sid")
        return checkResult
    }

    override fun clientStart(sid: Int, user: String, pass: String, timeoutS: Int): ClientStart {
        record("clientStart sid=$sid timeoutS=$timeoutS")
        return clientStartResult
    }

    override fun sendIoCtrl(av: Int, type: Int, payload: ByteArray): Int {
        record("sendIoCtrl av=$av type=$type size=${payload.size}")
        sentIoCtrl += type to payload.copyOf()
        return sendIoCtrlResult
    }

    override fun recvFrame(av: Int, buf: ByteArray, frameInfo: ByteArray): RecvResult {
        record("recvFrame av=$av")
        val next = recvQueue.poll() ?: return RecvResult(TutkCodes.AV_DATA_NOREADY, 0)
        next.payload.copyInto(buf, 0, 0, minOf(next.payload.size, buf.size))
        next.info.copyInto(frameInfo, 0, 0, minOf(next.info.size, frameInfo.size))
        return next.result
    }

    override fun stop(av: Int, sid: Int) {
        record("stop av=$av sid=$sid")
    }
}
