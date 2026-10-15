// FILE: TnpCameraLinkTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Executable contract for TnpCameraLink over FakePpppNative: vendor call order, burst bytes, mode mapping,
//     every failure code, partial reads, header version rule, kick, timeouts, cancellation, no retry, log hygiene.
//   SCOPE: JVM only; no real camera, no native code. Synthetic credentials (SYNTH values).
//   DEPENDS: M-TNP-LINK, FakePpppNative, TraceRecorder, RedactionScanner, RedactionFixtures
//   LINKS: V-M-TNP-LINK
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.p2p.tnp

import com.yicamalt.camera.P2pCredentials
import com.yicamalt.camera.TransportType
import com.yicamalt.p2p.FakePpppNative
import com.yicamalt.p2p.PpppCheck
import com.yicamalt.p2p.PpppCodes
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
import com.yicamalt.test.RedactionFixtures
import com.yicamalt.test.RedactionScanner
import com.yicamalt.test.TraceRecorder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

// START_MODULE_MAP
//   creds / newLink / videoUnit / audioUnit / typeOf / numberOf - builders and wire readers.
//   awaitTrue / awaitState - polling helpers bounded at 3 s.
//   order tests - connect, check, five readers before the burst, 56/52/56 bytes, no ch0 data inside the burst.
//   burst bytes - payloads, numbers 1..3, auth comma at 15, one nonce prefix per link.
//   flag / mode tests - 0x49 / 0xDF, wakeup, bMode 0..3, lan, resolution 1 / 2.
//   failure tests - -3006, check failure, dirty head, -3012 without retry, auth 1 / 4, 9033 kick.
//   header version - adopt once, re-send only 4881 with the same useCount and resolution.
//   cancellation - close during connect, close while streaming (STOP once, threads gone), idempotent close/start.
//   timeouts - connect 30 s and first frame 20 s on an injected clock.
//   log hygiene - marker presence and RedactionScanner over every captured buffer.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 02-07: created.
// END_CHANGE_SUMMARY

class TnpCameraLinkTest {

    private val recorder = TraceRecorder()
    private val links = CopyOnWriteArrayList<TnpCameraLink>()

    @BeforeEach fun setUp() = recorder.plant()

    @AfterEach
    fun tearDown() {
        links.forEach { it.close() }
        RedactionScanner().assertClean(recorder.bufferText(), extraLiterals = SECRETS)
        recorder.unplant()
    }

    private class RecordingSink : VideoUnitSink {
        val units = CopyOnWriteArrayList<VideoUnit>()
        override fun onVideoUnit(unit: VideoUnit) {
            units += unit
        }
    }

    // START_BLOCK_BUILDERS
    private fun creds(wakeup: Boolean = false) = P2pCredentials(
        deviceId = RedactionFixtures.CLOUD_UID,
        p2pId = RedactionFixtures.DID,
        transport = TransportType.TNP,
        password = PASSWORD,
        initString = RedactionFixtures.INIT_STRING,
        licenseKey = LICENSE_KEY,
        p2pEncrypt = true,
        wakeup = wakeup,
        needsPincode = false,
        transportVerified = true,
    )

    private val closedCount = AtomicInteger(0)

    private fun newLink(
        fake: FakePpppNative,
        options: LinkOptions = LinkOptions(),
        clock: AtomicLong? = null,
        credentials: P2pCredentials = creds(),
        cam: Int = CAM.incrementAndGet(),
    ): TnpCameraLink {
        val now: () -> Long = if (clock != null) ({ clock.get() }) else ({ System.nanoTime() / 1_000_000L })
        val link = TnpCameraLink(
            credentials, options, fake, cam, Random(7),
            TnpCameraLink.Timeouts(pollMs = 5), now,
        ) { closedCount.incrementAndGet() }
        links += link
        return link
    }

    private fun frameHead(codec: Int, flags: Int, seq: Int): ByteArray =
        ByteBuffer.allocate(TnpCodec.FRAME_HEAD_SIZE)
            .putShort(codec.toShort()).put(flags.toByte()).put(1).put(1).put(1)
            .putShort(seq.toShort()).putShort(1920).putShort(1080).putInt(1_700_000_000)
            .put(1).put(0).put(0).put(0).putInt(123)
            .array()

    private fun videoUnit(payload: ByteArray = ByteArray(40) { it.toByte() }, codec: Int = 78, key: Boolean = true, seq: Int = 5) =
        TnpCodec.encodeHead(2, TnpCodec.IOTYPE_VIDEO, TnpCodec.FRAME_HEAD_SIZE + payload.size) +
            frameHead(codec, if (key) 1 else 0, seq) + payload

    private fun audioUnit() =
        TnpCodec.encodeHead(2, TnpCodec.IOTYPE_AUDIO, TnpCodec.FRAME_HEAD_SIZE + 16) + frameHead(140, 0, 1) + ByteArray(16)

    private fun typeOf(unit: ByteArray) = ((unit[8].toInt() and 0xFF) shl 8) or (unit[9].toInt() and 0xFF)
    private fun numberOf(unit: ByteArray) = ((unit[10].toInt() and 0xFF) shl 8) or (unit[11].toInt() and 0xFF)
    private fun payloadOf(unit: ByteArray) = unit.copyOfRange(48, unit.size)
    private fun authOf(unit: ByteArray) = unit.copyOfRange(16, 48)
    private fun beInt(v: Int) = ByteBuffer.allocate(4).putInt(v).array()

    private fun awaitTrue(ms: Long = 3_000, what: () -> String = { "condition" }, cond: () -> Boolean) {
        val deadline = System.nanoTime() + ms * 1_000_000
        while (!cond()) {
            check(System.nanoTime() < deadline) { "timed out waiting for ${what()}" }
            Thread.sleep(5)
        }
    }

    private fun awaitState(link: TnpCameraLink, ms: Long = 3_000, pred: (LinkState) -> Boolean): LinkState {
        awaitTrue(ms, { "state, last=${link.state.value}" }) { pred(link.state.value) }
        return link.state.value
    }

    private fun awaitBurst(fake: FakePpppNative) = awaitTrue(what = { fake.calls.toString() }) { fake.written.size >= 3 }

    private fun count(fake: FakePpppNative, entry: String) = fake.calls.count { it == entry }
    // END_BLOCK_BUILDERS

    // START_BLOCK_ORDER_TESTS
    @Test
    fun `happy path follows the vendor order and streams with authOk`() {
        val fake = FakePpppNative()
        val sink = RecordingSink()
        val link = newLink(fake)
        val seen = CopyOnWriteArrayList<LinkState>()
        link.start(sink)
        seen += link.state.value
        awaitBurst(fake)
        seen += link.state.value

        val calls = fake.calls.toList()
        assertEquals("connect flag=0x49 wakeup=false", calls[0])
        assertEquals("check", calls[1])
        val firstWrite = calls.indexOfFirst { it.startsWith("write") }
        listOf(0, 1, 2, 3, 6).forEach { ch ->
            val r = calls.indexOf("read ch=$ch")
            assertTrue(r in 2 until firstWrite, "read ch=$ch at $r, first write at $firstWrite: $calls")
        }
        val writes = calls.withIndex().filter { it.value.startsWith("write") }.take(3)
        assertEquals(listOf("write ch=0 bytes=56", "write ch=0 bytes=52", "write ch=0 bytes=56"), writes.map { it.value })
        val inBurst = calls.subList(writes.first().index, writes.last().index)
        assertTrue(inBurst.none { it.startsWith("data ch=0") }, inBurst.toString())

        val info = LinkInfo(TransportType.TNP, LinkMode.P2P, lan = true, connectMs = 500)
        assertTrue(seen.first() == LinkState.Connecting || seen.first() == LinkState.Connected(info), seen.toString())
        assertEquals(LinkState.Connected(info), seen.last())

        fake.replyIoCtrl(IoCtrlMessages.SET_RESOLUTION_RESP, 0)
        fake.enqueueUnit(2, videoUnit())
        assertEquals(LinkState.Streaming(info, authOk = true), awaitState(link) { it is LinkState.Streaming && it.authOk })
        awaitTrue { sink.units.size == 1 }
        val unit = sink.units.single()
        assertEquals(2, unit.channel)
        assertEquals(78, unit.info.codecId)
        assertTrue(unit.info.isKeyFrame)
        assertEquals(5, unit.info.seq)
        assertEquals(1920, unit.info.width)
        assertEquals(1080, unit.info.height)
        assertEquals(1_700_000_000L, unit.info.timestampSec)
        assertEquals(123L, unit.info.timestampMs)
        assertArrayEquals(ByteArray(40) { it.toByte() }, unit.payload)
        assertEquals(1, count(fake, "connect flag=0x49 wakeup=false"))
        recorder.assertSequence("BLOCK_CONNECT", "BLOCK_CHECK_SESSION")
    }

    @Test
    fun `burst payloads, numbers and auth fields match the vendor layout`() {
        val fake = FakePpppNative()
        newLink(fake).start(RecordingSink())
        awaitBurst(fake)
        val (setRes, startRt, audio) = fake.written.take(3).map { it.second }
        assertEquals(listOf(4881, 9029, 768), listOf(typeOf(setRes), typeOf(startRt), typeOf(audio)))
        assertEquals(listOf(1, 2, 3), listOf(numberOf(setRes), numberOf(startRt), numberOf(audio)))
        assertArrayEquals(IoCtrlMessages.setResolution(1, 1), payloadOf(setRes))
        assertArrayEquals(IoCtrlMessages.startRealtime(2, 1), payloadOf(startRt))
        assertArrayEquals(IoCtrlMessages.audioStart(), payloadOf(audio))
        listOf(setRes, startRt, audio).forEach {
            assertEquals(2, it[0].toInt())
            assertEquals(TnpCodec.IOTYPE_COMMAND, it[1].toInt())
        }
        val auths = listOf(setRes, startRt, audio).map { authOf(it) }
        auths.forEach { a ->
            assertEquals(32, a.size)
            assertEquals(','.code.toByte(), a[15])
        }
        val prefixes = auths.map { String(it.copyOfRange(0, 7), Charsets.US_ASCII) }.toSet()
        assertEquals(1, prefixes.size)
        assertEquals(3, auths.map { String(it, Charsets.US_ASCII) }.toSet().size)
    }
    // END_BLOCK_ORDER_TESTS

    // START_BLOCK_FLAG_MODE_TESTS
    @Test
    fun `forced relay uses flag 0xDF and wakeup credentials use WakeUp_And_Connect`() {
        val relay = FakePpppNative()
        newLink(relay, LinkOptions(forceRelay = true)).start(RecordingSink())
        awaitTrue { relay.calls.isNotEmpty() }
        assertEquals("connect flag=0xDF wakeup=false", relay.calls.first())

        val wake = FakePpppNative()
        newLink(wake, credentials = creds(wakeup = true)).start(RecordingSink())
        awaitTrue { wake.calls.isNotEmpty() }
        assertEquals("connect flag=0x49 wakeup=true", wake.calls.first())
    }

    @Test
    fun `check mode maps to link mode, lan flag and resolution`() {
        data class Case(val bMode: Int, val private: Boolean, val mode: LinkMode, val lan: Boolean, val res: Int)
        listOf(
            Case(1, true, LinkMode.RELAY, false, 2),
            Case(2, true, LinkMode.TCP_RELAY, false, 2),
            Case(3, true, LinkMode.SDEV_RELAY, false, 2),
            Case(0, false, LinkMode.P2P, false, 1),
            Case(0, true, LinkMode.P2P, true, 1),
        ).forEach { c ->
            val fake = FakePpppNative().apply { checkResult = PpppCheck(0, c.bMode, 900, c.private) }
            val link = newLink(fake)
            link.start(RecordingSink())
            awaitBurst(fake)
            assertEquals(LinkState.Connected(LinkInfo(TransportType.TNP, c.mode, c.lan, 900)), link.state.value, c.toString())
            assertArrayEquals(IoCtrlMessages.setResolution(c.res, 1), payloadOf(fake.written[0].second), c.toString())
            assertArrayEquals(IoCtrlMessages.startRealtime(2, c.res), payloadOf(fake.written[1].second), c.toString())
            link.close()
        }
        assertEquals(LinkMode.UNKNOWN, TnpCameraLink.modeOf(7))
    }
    // END_BLOCK_FLAG_MODE_TESTS

    // START_BLOCK_FAILURE_TESTS
    @Test
    fun `offline connect fails without check, readers or force close`() {
        val fake = FakePpppNative().apply { connectResult = PpppCodes.DEVICE_NOT_ONLINE }
        val link = newLink(fake)
        link.start(RecordingSink())
        assertEquals(LinkState.Failed(LinkError(LinkErrorKind.OFFLINE, -3006)), awaitState(link) { it is LinkState.Failed })
        awaitTrue { closedCount.get() == 1 }
        val calls = fake.calls.toList()
        assertFalse(calls.contains("check"), calls.toString())
        assertTrue(calls.none { it.startsWith("read") }, calls.toString())
        assertFalse(calls.contains("forceClose"), calls.toString())
        assertEquals(1, count(fake, "connectBreak"))
    }

    @Test
    fun `failed check force-closes the handle and reports INTERNAL`() {
        val fake = FakePpppNative().apply { checkResult = PpppCheck(PpppCodes.INVALID_SESSION_HANDLE, 0, 0, false) }
        val link = newLink(fake)
        link.start(RecordingSink())
        assertEquals(
            LinkState.Failed(LinkError(LinkErrorKind.INTERNAL, PpppCodes.INVALID_SESSION_HANDLE)),
            awaitState(link) { it is LinkState.Failed },
        )
        awaitTrue { fake.calls.contains("forceClose") }
        assertTrue(fake.calls.none { it.startsWith("read") })
    }

    @Test
    fun `partial reads with timeouts in between deliver one intact unit`() {
        val fake = FakePpppNative()
        val sink = RecordingSink()
        newLink(fake).start(sink)
        awaitBurst(fake)
        val payload = ByteArray(200) { (it * 3).toByte() }
        val unit = videoUnit(payload)
        fake.enqueueUnit(2, unit.copyOfRange(0, 5))
        Thread.sleep(120)
        fake.enqueueUnit(2, unit.copyOfRange(5, 60))
        Thread.sleep(120)
        fake.enqueueUnit(2, unit.copyOfRange(60, unit.size))
        awaitTrue { sink.units.size == 1 }
        assertArrayEquals(payload, sink.units.single().payload)
        Thread.sleep(60)
        assertEquals(1, sink.units.size)
    }

    @Test
    fun `dirty head fails with PROTOCOL -9000`() {
        listOf(
            2 to TnpCodec.encodeHead(2, TnpCodec.IOTYPE_VIDEO, 0),
            3 to TnpCodec.encodeHead(2, TnpCodec.IOTYPE_VIDEO, 307_201),
        ).forEach { (ch, head) ->
            val fake = FakePpppNative()
            val link = newLink(fake)
            link.start(RecordingSink())
            awaitBurst(fake)
            fake.enqueueUnit(ch, head)
            assertEquals(
                LinkState.Failed(LinkError(LinkErrorKind.PROTOCOL, -9000)),
                awaitState(link) { it is LinkState.Failed },
            )
            awaitTrue { fake.calls.contains("forceClose") }
        }
        recorder.assertMarkerAppeared("BLOCK_READ_LOOP")
    }

    @Test
    fun `remote close fails with REMOTE_CLOSED and never reconnects`() {
        val fake = FakePpppNative()
        val link = newLink(fake)
        link.start(RecordingSink())
        awaitBurst(fake)
        fake.injectRemoteClose(2)
        assertEquals(
            LinkState.Failed(LinkError(LinkErrorKind.REMOTE_CLOSED, -3012)),
            awaitState(link) { it is LinkState.Failed },
        )
        Thread.sleep(150)
        assertEquals(1, fake.calls.count { it.startsWith("connect ") })
    }

    @Test
    fun `wrong password and retry auth replies fail without retry`() {
        listOf(
            1 to LinkError(LinkErrorKind.WRONG_PASSWORD, -20009),
            4 to LinkError(LinkErrorKind.PROTOCOL, -9000),
        ).forEach { (authResult, expected) ->
            val fake = FakePpppNative()
            val link = newLink(fake)
            link.start(RecordingSink())
            awaitBurst(fake)
            fake.replyIoCtrl(IoCtrlMessages.SET_RESOLUTION_RESP, authResult)
            assertEquals(LinkState.Failed(expected), awaitState(link) { it is LinkState.Failed })
            Thread.sleep(100)
            assertEquals(1, fake.calls.count { it.startsWith("connect ") })
            assertEquals(3, fake.written.size)
        }
    }

    @Test
    fun `kick reply closes as kicked and we never send 9033`() {
        val fake = FakePpppNative()
        val link = newLink(fake)
        link.start(RecordingSink())
        awaitBurst(fake)
        fake.replyIoCtrl(IoCtrlMessages.KICK, 0, beInt(1))
        assertEquals(LinkState.Closed("kicked"), awaitState(link) { it is LinkState.Closed })
        awaitTrue { fake.calls.contains("forceClose") }
        assertTrue(fake.written.none { typeOf(it.second) == IoCtrlMessages.KICK })
        assertEquals(1, fake.calls.count { it.startsWith("connect ") })
    }
    // END_BLOCK_FAILURE_TESTS

    // START_BLOCK_HEADER_VERSION_TESTS
    @Test
    fun `first ch0 reply with another header version re-sends only 4881 once`() {
        val fake = FakePpppNative()
        val sink = RecordingSink()
        val link = newLink(fake)
        link.start(sink)
        awaitBurst(fake)
        fake.replyIoCtrl(IoCtrlMessages.SET_RESOLUTION_RESP, 0, version = 3)
        awaitTrue { fake.written.size == 4 }
        val resent = fake.written[3].second
        assertEquals(3, resent[0].toInt())
        assertEquals(4881, typeOf(resent))
        assertEquals(4, numberOf(resent))
        assertArrayEquals(IoCtrlMessages.setResolution(1, 1), payloadOf(resent))

        fake.replyIoCtrl(IoCtrlMessages.SET_RESOLUTION_RESP, 0, version = 1)
        fake.enqueueUnit(2, videoUnit())
        awaitState(link) { it is LinkState.Streaming && it.authOk }
        Thread.sleep(100)
        assertEquals(4, fake.written.size)

        link.close()
        val stop = fake.written.last().second
        assertEquals(IoCtrlMessages.STOP, typeOf(stop))
        assertEquals(3, stop[0].toInt())
        recorder.assertMarkerAppeared("BLOCK_HEADER_VERSION")
    }
    // END_BLOCK_HEADER_VERSION_TESTS

    // START_BLOCK_CANCELLATION_TESTS
    @Test
    fun `close during a blocking connect breaks it within 2 s without check`() {
        val fake = FakePpppNative().apply { connectBlocks = true }
        val link = newLink(fake)
        link.start(RecordingSink())
        awaitTrue { fake.calls.any { it.startsWith("connect ") } }
        val t0 = System.nanoTime()
        link.close()
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(ms < 2_000, "close took $ms ms")
        assertEquals(LinkState.Closed("user"), awaitState(link) { it is LinkState.Closed })
        Thread.sleep(50)
        assertFalse(fake.calls.contains("check"), fake.calls.toString())
        assertFalse(fake.calls.contains("forceClose"), fake.calls.toString())
    }

    @Test
    fun `close while streaming sends STOP once, breaks, force-closes and ends every link thread`() {
        val fake = FakePpppNative()
        val cam = CAM.incrementAndGet()
        val link = newLink(fake, cam = cam)
        val sink = RecordingSink()
        link.start(sink)
        link.start(sink)
        awaitBurst(fake)
        fake.enqueueUnit(2, videoUnit())
        awaitState(link) { it is LinkState.Streaming }

        val t0 = System.nanoTime()
        link.close()
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_000)
        assertEquals(LinkState.Closed("user"), link.state.value)
        assertEquals(1, fake.written.count { typeOf(it.second) == IoCtrlMessages.STOP })
        val calls = fake.calls.toList()
        val stopIdx = calls.indexOfLast { it.startsWith("write") }
        assertTrue(stopIdx < calls.indexOf("connectBreak"), calls.toString())
        assertTrue(calls.indexOf("connectBreak") < calls.indexOf("forceClose"), calls.toString())
        awaitTrue(2_000, { "p2p-$cam- threads alive" }) {
            Thread.getAllStackTraces().keys.none { it.isAlive && it.name.startsWith("p2p-$cam-") }
        }
        assertEquals(1, count(fake, "connect flag=0x49 wakeup=false"))

        val before = fake.calls.size
        link.close()
        link.close("screen-left")
        assertEquals(before, fake.calls.size)
        assertEquals(1, closedCount.get())
        assertEquals(LinkState.Closed("user"), link.state.value)
    }
    // END_BLOCK_CANCELLATION_TESTS

    // START_BLOCK_TIMEOUT_TESTS
    @Test
    fun `connect timeout aborts with TIMEOUT -3003`() {
        val clock = AtomicLong(1_000)
        val fake = FakePpppNative().apply { connectBlocks = true }
        val link = newLink(fake, clock = clock)
        link.start(RecordingSink())
        awaitTrue { fake.calls.any { it.startsWith("connect ") } }
        Thread.sleep(30)
        assertEquals(LinkState.Connecting, link.state.value)
        clock.addAndGet(30_000)
        assertEquals(
            LinkState.Failed(LinkError(LinkErrorKind.TIMEOUT, -3003)),
            awaitState(link) { it is LinkState.Failed },
        )
        assertTrue(fake.calls.contains("connectBreak"))
        Thread.sleep(50)
        assertFalse(fake.calls.contains("check"))
    }

    @Test
    fun `first frame timeout fails with TIMEOUT 0 and closes the session`() {
        val clock = AtomicLong(1_000)
        val fake = FakePpppNative()
        val link = newLink(fake, clock = clock)
        link.start(RecordingSink())
        awaitBurst(fake)
        clock.addAndGet(19_000)
        Thread.sleep(40)
        assertTrue(link.state.value is LinkState.Connected)
        clock.addAndGet(1_000)
        assertEquals(LinkState.Failed(LinkError(LinkErrorKind.TIMEOUT, 0)), awaitState(link) { it is LinkState.Failed })
        awaitTrue { fake.calls.contains("forceClose") }
        recorder.assertMarkerAppeared("BLOCK_CLOSE")
        assertTrue(recorder.bufferText().contains("reason=timeout"))
    }
    // END_BLOCK_TIMEOUT_TESTS

    // START_BLOCK_CHANNEL_TESTS
    @Test
    fun `audio units are discarded and ch6 units are delivered`() {
        val fake = FakePpppNative()
        val sink = RecordingSink()
        val link = newLink(fake)
        link.start(sink)
        awaitBurst(fake)
        fake.enqueueUnit(1, audioUnit())
        fake.enqueueUnit(6, videoUnit(codec = 81, key = false, seq = 9))
        awaitTrue { sink.units.size == 1 }
        Thread.sleep(100)
        assertEquals(1, sink.units.size)
        assertEquals(6, sink.units.single().channel)
        assertEquals(81, sink.units.single().info.codecId)
        assertTrue(link.state.value is LinkState.Streaming)
    }
    // END_BLOCK_CHANNEL_TESTS

    // START_BLOCK_LOG_HYGIENE
    @Test
    fun `happy and failure paths log every marker and no secret`() {
        val ok = FakePpppNative()
        val link = newLink(ok)
        link.start(RecordingSink())
        awaitBurst(ok)
        ok.replyIoCtrl(IoCtrlMessages.SET_RESOLUTION_RESP, 0)
        ok.enqueueUnit(2, videoUnit())
        awaitState(link) { it is LinkState.Streaming && it.authOk }
        link.close()

        val wrong = FakePpppNative()
        val l2 = newLink(wrong)
        l2.start(RecordingSink())
        awaitBurst(wrong)
        wrong.replyIoCtrl(IoCtrlMessages.SET_RESOLUTION_RESP, 2)
        awaitState(l2) { it is LinkState.Failed }

        val offline = FakePpppNative().apply { connectResult = PpppCodes.DEVICE_NOT_ONLINE }
        val l3 = newLink(offline)
        l3.start(RecordingSink())
        awaitState(l3) { it is LinkState.Failed }
        awaitTrue { closedCount.get() == 3 }

        listOf(
            "BLOCK_CONNECT", "BLOCK_CHECK_SESSION", "BLOCK_START_BURST", "BLOCK_AUTH_REPLY",
            "BLOCK_READ_LOOP", "BLOCK_FIRST_VIDEO", "BLOCK_CLOSE",
        ).forEach { recorder.assertMarkerAppeared(it) }
        val text = recorder.bufferText()
        assertTrue(text.contains("cmds=4881,9029,768"), text)
        assertTrue(text.contains("outcome=WRONG_PASSWORD"), text)
        assertTrue(text.contains("result=DEVICE_NOT_ONLINE"), text)
        RedactionScanner().assertClean(text, extraLiterals = SECRETS)
        RedactionScanner().assertClean(link.toString() + l2.state.value + l3.state.value, extraLiterals = SECRETS)
    }
    // END_BLOCK_LOG_HYGIENE

    companion object {
        private const val PASSWORD = "SYNTH1234567890"
        private const val LICENSE_KEY = "SYNTHLIC01"
        private val SECRETS = RedactionFixtures.ALL_SECRETS + listOf(PASSWORD, LICENSE_KEY)
        private val CAM = AtomicInteger(100)
    }
}
