// FILE: FakePpppNative.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Scripted JVM fake of the vendor PPPP surface so TNP link logic is testable without native code.
//   SCOPE: scripted init/connect/check results, blocking connect released by connectBreak, per-channel byte queues
//     with partial reads (-3003 + partial count), remote-close injection (-3012), force close, value-free call trace.
//   DEPENDS: M-P2P-NATIVE (PpppNative, PpppCheck, PpppCodes)
//   LINKS: V-M-P2P-NATIVE, V-M-TNP-LINK
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.p2p

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

// START_MODULE_MAP
//   FakePpppNative - PpppNative implementation driven by public scripting vars.
//   initResult / apiVersionValue / connectResult / connectBlocks / checkResult / writeResult - scripted outcomes.
//   enqueue(channel, bytes) - bytes the camera "sends" on a channel.
//   injectRemoteClose(channel) - reads on that channel return -3012 from now on.
//   calls - value-free call trace ("connect flag=0x49 wakeup=false", "write ch=0 bytes=56", "read ch=2").
//   written - every write as (channel, copied bytes).
//   MAX_READ_WAIT_MS - cap on how long a read waits for data (keeps tests fast).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 02-00: created with the PpppNative contract.
// END_CHANGE_SUMMARY

class FakePpppNative : PpppNative {

    var initResult = 0
    var apiVersionValue = 0xA2050401.toInt()
    var connectResult = 0
    @Volatile var connectBlocks = false
    var checkResult = PpppCheck(0, 0, 500, true)
    var writeResult: (Int, ByteArray) -> Int = { _, b -> b.size }

    val written = CopyOnWriteArrayList<Pair<Int, ByteArray>>()
    val calls = CopyOnWriteArrayList<String>()

    private val queues = ConcurrentHashMap<Int, LinkedBlockingDeque<Byte>>()
    private val remoteClosed: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    private val closedHandles: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    @Volatile private var connectGate = CountDownLatch(1)

    private fun queue(channel: Int) = queues.getOrPut(channel) { LinkedBlockingDeque() }

    fun enqueue(channel: Int, bytes: ByteArray) {
        val q = queue(channel)
        bytes.forEach { q.put(it) }
    }

    fun injectRemoteClose(channel: Int) {
        remoteClosed += channel
    }

    override fun initialize(initString: ByteArray, maxSessions: Int): Int {
        calls += "initialize maxSessions=$maxSessions"
        return initResult
    }

    override fun configDebug(enable: Int, level: Int): Int {
        calls += "configDebug enable=$enable level=$level"
        return 0
    }

    override fun apiVersion(): Int {
        calls += "apiVersion"
        return apiVersionValue
    }

    // START_BLOCK_FAKE_CONNECT
    override fun connect(did: String, flag: Byte, wakeup: Boolean, serverString: String, licenseKey: String): Int {
        calls += "connect flag=0x%02X wakeup=%b".format(flag.toInt() and 0xFF, wakeup)
        if (connectBlocks) {
            connectGate.await()
            connectGate = CountDownLatch(1)
            return PpppCodes.USER_CONNECT_BREAK
        }
        return connectResult
    }

    override fun connectBreak(did: String): Int {
        calls += "connectBreak"
        connectGate.countDown()
        return 0
    }
    // END_BLOCK_FAKE_CONNECT

    override fun check(handle: Int): PpppCheck {
        calls += "check"
        return checkResult
    }

    // START_BLOCK_FAKE_READ
    override fun read(handle: Int, channel: Int, buf: ByteArray, size: IntArray, timeoutMs: Int): Int {
        calls += "read ch=$channel"
        if (handle in closedHandles) {
            size[0] = 0
            return PpppCodes.SESSION_CLOSED_CALLED
        }
        if (channel in remoteClosed) {
            size[0] = 0
            return PpppCodes.SESSION_CLOSED_REMOTE
        }
        val want = size[0].coerceIn(0, buf.size)
        val q = queue(channel)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(minOf(timeoutMs, MAX_READ_WAIT_MS).toLong())
        var got = 0
        while (got < want) {
            val remaining = deadline - System.nanoTime()
            val b = if (remaining > 0) q.poll(remaining, TimeUnit.NANOSECONDS) else q.poll()
            if (b == null) break
            buf[got++] = b
        }
        size[0] = got
        return if (got == want) PpppCodes.OK else PpppCodes.TIME_OUT
    }
    // END_BLOCK_FAKE_READ

    override fun write(handle: Int, channel: Int, buf: ByteArray, size: Int): Int {
        calls += "write ch=$channel bytes=$size"
        if (handle in closedHandles) return PpppCodes.INVALID_SESSION_HANDLE
        val copy = buf.copyOf(size)
        written += channel to copy
        return writeResult(channel, copy)
    }

    override fun forceClose(handle: Int): Int {
        calls += "forceClose"
        closedHandles += handle
        return 0
    }

    companion object {
        const val MAX_READ_WAIT_MS = 50
    }
}
