// FILE: TnpCameraLinkTest.kt
// VERSION: 0.3.0
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
import com.yicamalt.stream.AudioUnit
import com.yicamalt.stream.AudioUnitSink
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

// START_MODULE_MAP
//   creds / newLink / videoUnit / audioUnit / typeOf / numberOf - builders and wire readers.
//   awaitTrue / awaitState - polling helpers bounded at 3 s.
//   order tests - connect, check, five readers before the burst, 56/52/56 bytes, no ch0 data inside the burst.
//   burst bytes - payloads, numbers 1..3, auth comma at 15, one nonce prefix per link.
//   flag / mode tests - 0x49 / 0xDF, wakeup, bMode 0..3, lan, resolution 1 / 2.
//   failure tests - -3006, check failure, dirty head, -3012 without retry, auth 1 / 4, 9033 kick.
//   header version - adopt once (camera answers 3 or 1), re-send the whole start sequence once with the same useCounts
//     and resolution, the mismatched reply is skipped unparsed (its authResult never fails the link).
//   auth on every reply - authResult 1|2 on a non-4882 reply fails WRONG_PASSWORD; unknown authResult is ignored and
//     logged once.
//   thread guard - a sink that throws an Error and an empty encrypted password end as Failed(INTERNAL, 0) with teardown.
//   registry release - onClosed waits for a connect thread that ignores Connect_Break.
//   handle reuse - no PPPP_Read is in flight when ForceClose runs.
//   cancellation - close during connect, close while streaming (STOP once, threads gone), idempotent close/start.
//   timeouts - connect 30 s and first frame 20 s on an injected clock.
//   log hygiene - marker presence and RedactionScanner over every captured buffer.
//   auth/video race - auth reply and first video unit together, 15 rounds, always Streaming(authOk=true).
//   RecordingAudioSink / abilityReply - audio capture sink; ch0 reply with a chosen TNPHead abilityRet.
//   audio routing - ch1 units reach the audio sink (encrypted = header version >= 2), never the video sink; a
//     throwing audio sink does not stop video.
//   requestResolution - 4881 [res, useCount 3, 4, ...] through the single writer; null before connect / after close.
//   abilityRet - 4882 with abilityRet 1 -> capabilities.resolutionSwitch == false; 0 keeps null.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.3.0 - GSD plan 03-02: ch1 audio routing, requestResolution through txQueue, 4882 abilityRet
//     capability; log hygiene covers BLOCK_AUDIO_UNIT and BLOCK_SET_RESOLUTION.
//   PREVIOUS: v0.2.0 - Phase 2 review: HIGH-2 (whole start sequence re-sent once, mismatched reply skipped),
//     MED-3 (authResult on every reply, unknown ignored), MED-7 (thread guard, empty password), LOW-8 (deferred
//     registry release), LOW-9 (ForceClose after the readers left).
//   PREVIOUS: v0.1.1 - GSD plan 02-12: regression for the rx0 auth / rx2 first-video race on authOk.
//   PREVIOUS: v0.1.0 - GSD plan 02-07: created.
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

    private class RecordingAudioSink : AudioUnitSink {
        val units = CopyOnWriteArrayList<AudioUnit>()
        override fun onAudioUnit(unit: AudioUnit) {
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
        timeouts: TnpCameraLink.Timeouts = TnpCameraLink.Timeouts(pollMs = 5),
    ): TnpCameraLink {
        val now: () -> Long = if (clock != null) ({ clock.get() }) else ({ System.nanoTime() / 1_000_000L })
        val link = TnpCameraLink(
            credentials, options, fake, cam, Random(7),
            timeouts, now,
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

    private fun audioUnit(codec: Int = 140, payload: ByteArray = ByteArray(16), version: Int = 2) =
        TnpCodec.encodeHead(version, TnpCodec.IOTYPE_AUDIO, TnpCodec.FRAME_HEAD_SIZE + payload.size) +
            frameHead(codec, 0, 1) + payload

    // ch0 reply whose TNPHead carries the given abilityRet (byte 2), authResult s32 BE at IOCtrl offset 8.
    private fun abilityReply(type: Int, authResult: Int, abilityRet: Int): ByteArray {
        val auth = ByteBuffer.allocate(TnpCodec.AUTH_SIZE).putInt(authResult).array()
        val unit = TnpCodec.commandUnit(2, type, 1, auth, ByteArray(0))
        unit[2] = abilityRet.toByte()
        return unit
    }

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
    private fun assertVersionResend(cameraVersion: Int) {
        val fake = FakePpppNative()
        val sink = RecordingSink()
        val link = newLink(fake)
        link.start(sink)
        awaitBurst(fake)
        // The mismatched first reply is skipped unparsed: its authResult 1 must not fail the link.
        fake.replyIoCtrl(IoCtrlMessages.SET_RESOLUTION_RESP, 1, version = cameraVersion)
        awaitTrue(what = { "resent sequence, written=${fake.written.size}" }) { fake.written.size == 6 }
        val resent = fake.written.drop(3).map { it.second }
        assertEquals(listOf(4881, 9029, 768), resent.map { typeOf(it) })
        assertEquals(listOf(4, 5, 6), resent.map { numberOf(it) })
        resent.forEach { assertEquals(cameraVersion, it[0].toInt()) }
        assertArrayEquals(IoCtrlMessages.setResolution(1, 1), payloadOf(resent[0]))
        assertArrayEquals(IoCtrlMessages.startRealtime(2, 1), payloadOf(resent[1]))
        assertArrayEquals(IoCtrlMessages.audioStart(), payloadOf(resent[2]))
        assertFalse(link.state.value is LinkState.Failed, link.state.value.toString())

        fake.replyIoCtrl(IoCtrlMessages.SET_RESOLUTION_RESP, 0, version = cameraVersion)
        fake.enqueueUnit(2, videoUnit())
        awaitState(link) { it is LinkState.Streaming && it.authOk }
        // A later version change is ignored: no further resend.
        fake.replyIoCtrl(IoCtrlMessages.SET_RESOLUTION_RESP, 0, version = if (cameraVersion == 1) 3 else 1)
        Thread.sleep(100)
        assertEquals(6, fake.written.size)
        assertEquals(2, fake.written.count { typeOf(it.second) == IoCtrlMessages.START_REALTIME })

        link.close()
        val stop = fake.written.last().second
        assertEquals(IoCtrlMessages.STOP, typeOf(stop))
        assertEquals(cameraVersion, stop[0].toInt())
        assertFalse(recorder.bufferText().contains("outcome=WRONG_PASSWORD"))
    }

    @Test
    fun `camera answering with header version 3 gets the whole start sequence once more and streams`() {
        assertVersionResend(3)
        recorder.assertMarkerAppeared("BLOCK_HEADER_VERSION")
    }

    @Test
    fun `camera answering with header version 1 gets the whole start sequence once more and streams`() {
        assertVersionResend(1)
    }

    @Test
    fun `first reply with the same header version resends nothing`() {
        val fake = FakePpppNative()
        val link = newLink(fake)
        link.start(RecordingSink())
        awaitBurst(fake)
        fake.replyIoCtrl(IoCtrlMessages.SET_RESOLUTION_RESP, 0, version = 2)
        fake.enqueueUnit(2, videoUnit())
        awaitState(link) { it is LinkState.Streaming && it.authOk }
        Thread.sleep(100)
        assertEquals(3, fake.written.size)
    }
    // END_BLOCK_HEADER_VERSION_TESTS

    // START_BLOCK_AUTH_EVERY_REPLY_TESTS
    @Test
    fun `wrong password on a reply other than 4882 fails WRONG_PASSWORD`() {
        val fake = FakePpppNative()
        val link = newLink(fake)
        link.start(RecordingSink())
        awaitBurst(fake)
        fake.replyIoCtrl(OTHER_REPLY_TYPE, 2)
        assertEquals(
            LinkState.Failed(LinkError(LinkErrorKind.WRONG_PASSWORD, -20009)),
            awaitState(link) { it is LinkState.Failed },
        )
    }

    @Test
    fun `unknown authResult drops the reply without failing and is logged once`() {
        val fake = FakePpppNative()
        val link = newLink(fake)
        link.start(RecordingSink())
        awaitBurst(fake)
        fake.replyIoCtrl(IoCtrlMessages.SET_RESOLUTION_RESP, 7)
        fake.replyIoCtrl(IoCtrlMessages.KICK, 9, beInt(1))
        fake.replyIoCtrl(IoCtrlMessages.SET_RESOLUTION_RESP, 0)
        fake.enqueueUnit(2, videoUnit())
        awaitState(link) { it is LinkState.Streaming && it.authOk }
        Thread.sleep(50)
        assertTrue(link.state.value is LinkState.Streaming, link.state.value.toString())
        val text = recorder.bufferText()
        assertEquals(1, Regex("outcome=UNKNOWN").findAll(text).count(), text)
    }
    // END_BLOCK_AUTH_EVERY_REPLY_TESTS

    // START_BLOCK_THREAD_GUARD_TESTS
    @Test
    fun `a sink that throws an Error ends the link as INTERNAL with teardown`() {
        val fake = FakePpppNative()
        val link = newLink(fake)
        link.start(object : VideoUnitSink {
            override fun onVideoUnit(unit: VideoUnit) = throw AssertionError("SYNTH sink failure")
        })
        awaitBurst(fake)
        fake.enqueueUnit(2, videoUnit())
        assertEquals(LinkState.Failed(LinkError(LinkErrorKind.INTERNAL, 0)), awaitState(link) { it is LinkState.Failed })
        awaitTrue { fake.calls.contains("forceClose") }
        awaitTrue { closedCount.get() == 1 }
        recorder.assertMarkerAppeared("BLOCK_THREAD_GUARD")
    }

    @Test
    fun `an empty encrypted password fails INTERNAL instead of killing the connect thread`() {
        val fake = FakePpppNative()
        val link = newLink(fake, credentials = creds().copy(password = ""))
        link.start(RecordingSink())
        assertEquals(LinkState.Failed(LinkError(LinkErrorKind.INTERNAL, 0)), awaitState(link) { it is LinkState.Failed })
        awaitTrue { fake.calls.contains("forceClose") }
        assertTrue(fake.written.isEmpty())
    }
    // END_BLOCK_THREAD_GUARD_TESTS

    // START_BLOCK_RELEASE_ORDER_TESTS
    @Test
    fun `registry entry is released only after a stuck connect thread exits`() {
        val hold = CountDownLatch(1)
        val fake = FakePpppNative().apply { connectHold = hold }
        val link = newLink(fake, timeouts = TnpCameraLink.Timeouts(pollMs = 5, joinMs = 300))
        link.start(RecordingSink())
        awaitTrue { fake.calls.any { it.startsWith("connect ") } }
        link.close()
        assertEquals(LinkState.Closed("user"), awaitState(link) { it is LinkState.Closed })
        Thread.sleep(100)
        assertEquals(0, closedCount.get())
        hold.countDown()
        awaitTrue { closedCount.get() == 1 }
        assertTrue(recorder.bufferText().contains("registry=deferred"))
    }

    @Test
    fun `force close runs only after every reader left PPPP_Read`() {
        repeat(5) {
            val fake = FakePpppNative()
            val link = newLink(fake)
            link.start(RecordingSink())
            awaitBurst(fake)
            fake.enqueueUnit(2, videoUnit())
            awaitState(link) { it is LinkState.Streaming }
            link.close()
            assertEquals(0, fake.readsInFlightAtForceClose, fake.calls.takeLast(20).toString())
            val calls = fake.calls.toList()
            assertTrue(calls.indexOf("connectBreak") < calls.indexOf("forceClose"), calls.toString())
            val afterClose = calls.subList(calls.indexOf("forceClose"), calls.size)
            assertTrue(afterClose.none { c -> c.startsWith("read") }, calls.toString())
        }
    }
    // END_BLOCK_RELEASE_ORDER_TESTS

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
        assertTrue(sink.units.none { it.channel == TnpCodec.CH_AUDIO })
    }

    @Test
    fun `audio units go to the audio sink with encrypted true at header version 2`() {
        val fake = FakePpppNative()
        val sink = RecordingSink()
        val audio = RecordingAudioSink()
        val link = newLink(fake)
        link.setAudioSink(audio)
        link.start(sink)
        awaitBurst(fake)
        fake.enqueueUnit(1, audioUnit(codec = 138, payload = ByteArray(32) { it.toByte() }))
        awaitTrue(what = { "audio units=${audio.units.size}" }) { audio.units.size == 1 }
        val unit = audio.units.single()
        assertEquals(TnpCodec.CH_AUDIO, unit.channel)
        assertEquals(138, unit.info.codecId)
        assertEquals(32, unit.payload.size)
        assertArrayEquals(ByteArray(32) { it.toByte() }, unit.payload)
        assertTrue(unit.encrypted)
        assertTrue(sink.units.isEmpty(), sink.units.toString())
        recorder.assertMarkerAppeared("BLOCK_AUDIO_UNIT")
    }

    @Test
    fun `audio after header version 1 adoption is not encrypted`() {
        val fake = FakePpppNative()
        val audio = RecordingAudioSink()
        val link = newLink(fake)
        link.setAudioSink(audio)
        link.start(RecordingSink())
        awaitBurst(fake)
        fake.replyIoCtrl(IoCtrlMessages.SET_RESOLUTION_RESP, 0, version = 1)
        awaitTrue(what = { "resent sequence, written=${fake.written.size}" }) { fake.written.size == 6 }
        fake.enqueueUnit(1, audioUnit(codec = 138, payload = ByteArray(32), version = 1))
        awaitTrue { audio.units.size == 1 }
        assertFalse(audio.units.single().encrypted)
    }

    @Test
    fun `a throwing audio sink does not stop video`() {
        val fake = FakePpppNative()
        val sink = RecordingSink()
        val link = newLink(fake)
        link.setAudioSink { throw IllegalStateException("SYNTH audio failure") }
        link.start(sink)
        awaitBurst(fake)
        fake.enqueueUnit(1, audioUnit(payload = ByteArray(32)))
        fake.enqueueUnit(1, audioUnit(payload = ByteArray(32)))
        fake.enqueueUnit(2, videoUnit())
        awaitTrue { sink.units.size == 1 }
        Thread.sleep(100)
        assertTrue(link.state.value is LinkState.Streaming, link.state.value.toString())
        assertEquals(1, Regex("sinkError=IllegalStateException").findAll(recorder.bufferText()).count())
    }
    // END_BLOCK_CHANNEL_TESTS

    // START_BLOCK_RESOLUTION_TESTS
    @Test
    fun `requestResolution queues 4881 with useCount 3 then 4 on the writer`() {
        val fake = FakePpppNative()
        val link = newLink(fake)
        link.start(RecordingSink())
        awaitBurst(fake)
        fake.replyIoCtrl(IoCtrlMessages.SET_RESOLUTION_RESP, 0)
        fake.enqueueUnit(2, videoUnit())
        awaitState(link) { it is LinkState.Streaming && it.authOk }
        val burst = fake.written.take(3).map { it.second.copyOf() }

        assertEquals(3, link.requestResolution(2))
        assertEquals(4, link.requestResolution(1))
        awaitTrue(what = { "written=${fake.written.size}" }) { fake.written.size == 5 }
        val extra = fake.written.drop(3)
        extra.forEach { (ch, bytes) ->
            assertEquals(TnpCodec.CH_COMMAND, ch)
            assertEquals(IoCtrlMessages.SET_RESOLUTION, typeOf(bytes))
            assertEquals(2, bytes[0].toInt())
        }
        assertArrayEquals(IoCtrlMessages.setResolution(2, 3), payloadOf(extra[0].second))
        assertArrayEquals(IoCtrlMessages.setResolution(1, 4), payloadOf(extra[1].second))
        assertEquals(listOf(4, 5), extra.map { numberOf(it.second) })
        assertTrue(fake.calls.filter { it.startsWith("write") }.all { it.startsWith("write ch=0") })
        // Burst bytes are untouched by the later requests.
        fake.written.take(3).map { it.second }.zip(burst).forEach { (a, b) -> assertArrayEquals(b, a) }
        assertArrayEquals(IoCtrlMessages.setResolution(1, 1), payloadOf(burst[0]))
        assertArrayEquals(IoCtrlMessages.startRealtime(2, 1), payloadOf(burst[1]))
        recorder.assertMarkerAppeared("BLOCK_SET_RESOLUTION")
    }

    @Test
    fun `requestResolution before connect or after close returns null and writes nothing`() {
        val fake = FakePpppNative()
        val link = newLink(fake)
        assertEquals(null, link.requestResolution(2))
        link.start(RecordingSink())
        awaitBurst(fake)
        link.close()
        val before = fake.written.size
        assertEquals(null, link.requestResolution(2))
        Thread.sleep(50)
        assertEquals(before, fake.written.size)
        assertTrue(fake.written.none { typeOf(it.second) == IoCtrlMessages.SET_RESOLUTION && numberOf(it.second) > 3 })
    }

    @Test
    fun `4882 with abilityRet 1 marks resolution switch unsupported`() {
        val fake = FakePpppNative()
        val link = newLink(fake)
        assertEquals(null, link.capabilities.value.resolutionSwitch)
        link.start(RecordingSink())
        awaitBurst(fake)
        fake.enqueueUnit(TnpCodec.CH_COMMAND, abilityReply(IoCtrlMessages.SET_RESOLUTION_RESP, 0, abilityRet = 1))
        awaitTrue(what = { "capabilities=${link.capabilities.value}" }) {
            link.capabilities.value.resolutionSwitch == false
        }
        awaitTrue { recorder.bufferText().contains("abilityRet=1") }

        val fake0 = FakePpppNative()
        val link0 = newLink(fake0)
        link0.start(RecordingSink())
        awaitBurst(fake0)
        fake0.enqueueUnit(TnpCodec.CH_COMMAND, abilityReply(IoCtrlMessages.SET_RESOLUTION_RESP, 0, abilityRet = 0))
        fake0.enqueueUnit(2, videoUnit())
        awaitState(link0) { it is LinkState.Streaming && it.authOk }
        assertEquals(null, link0.capabilities.value.resolutionSwitch)
    }
    // END_BLOCK_RESOLUTION_TESTS

    // START_BLOCK_LOG_HYGIENE
    @Test
    fun `happy and failure paths log every marker and no secret`() {
        val ok = FakePpppNative()
        val link = newLink(ok)
        val audio = RecordingAudioSink()
        link.setAudioSink(audio)
        link.start(RecordingSink())
        awaitBurst(ok)
        ok.replyIoCtrl(IoCtrlMessages.SET_RESOLUTION_RESP, 0)
        ok.enqueueUnit(2, videoUnit())
        awaitState(link) { it is LinkState.Streaming && it.authOk }
        ok.enqueueUnit(1, audioUnit(payload = ByteArray(32)))
        awaitTrue { audio.units.size == 1 }
        assertEquals(3, link.requestResolution(2))
        awaitTrue { ok.written.size == 4 }
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
            "BLOCK_READ_LOOP", "BLOCK_FIRST_VIDEO", "BLOCK_CLOSE", "BLOCK_AUDIO_UNIT", "BLOCK_SET_RESOLUTION",
        ).forEach { recorder.assertMarkerAppeared(it) }
        val text = recorder.bufferText()
        assertTrue(text.contains("cmds=4881,9029,768"), text)
        assertTrue(text.contains("outcome=WRONG_PASSWORD"), text)
        assertTrue(text.contains("result=DEVICE_NOT_ONLINE"), text)
        RedactionScanner().assertClean(text, extraLiterals = SECRETS)
        RedactionScanner().assertClean(link.toString() + l2.state.value + l3.state.value, extraLiterals = SECRETS)
    }
    // END_BLOCK_LOG_HYGIENE

    // START_BLOCK_AUTH_VIDEO_RACE
    // Regression (plan 02-12): the auth reply (rx0) and the first video unit (rx2) arrive together; the final
    // state must always be Streaming(authOk=true), never a stale authOk=false written by the video reader.
    @Test
    fun `auth reply racing the first video unit always ends with authOk`() {
        repeat(15) {
            val fake = FakePpppNative()
            val link = newLink(fake)
            link.start(RecordingSink())
            awaitBurst(fake)
            fake.enqueueUnit(2, videoUnit())
            fake.replyIoCtrl(IoCtrlMessages.SET_RESOLUTION_RESP, 0)
            awaitState(link) { it is LinkState.Streaming && it.authOk }
            link.close()
        }
    }
    // END_BLOCK_AUTH_VIDEO_RACE

    companion object {
        private const val PASSWORD = "SYNTH1234567890"
        private const val LICENSE_KEY = "SYNTHLIC01"
        private val SECRETS = RedactionFixtures.ALL_SECRETS + listOf(PASSWORD, LICENSE_KEY)
        private val CAM = AtomicInteger(100)
        private const val OTHER_REPLY_TYPE = 0x2346
    }
}
