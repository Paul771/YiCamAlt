// FILE: TutkCameraLinkTest.kt
// VERSION: 0.2.0
// START_MODULE_CONTRACT
//   PURPOSE: Executable contract for TutkCameraLink and TutkLinkFactory over FakeTutkNative: vendor call order,
//     nonce + HMAC auth, start command, LE frame heads, mode mapping, failure codes, frame drop rule, no-data bound,
//     timers, cancellation, no retry, thread names, log hygiene.
//   SCOPE: JVM only; no real camera, no native code. Synthetic credentials (SYNTH values).
//   DEPENDS: M-TUTK-LINK, FakeTutkNative, TraceRecorder, RedactionScanner, RedactionFixtures
//   LINKS: V-M-TUTK-LINK
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.p2p.tutk

import com.yicamalt.camera.P2pCredentials
import com.yicamalt.camera.TransportType
import com.yicamalt.p2p.NativeLibs
import com.yicamalt.stream.CameraLink
import com.yicamalt.stream.FrameHeadCodec
import com.yicamalt.stream.IoCtrlMessages
import com.yicamalt.stream.LinkError
import com.yicamalt.stream.LinkErrorKind
import com.yicamalt.stream.LinkMode
import com.yicamalt.stream.LinkOptions
import com.yicamalt.stream.LinkState
import com.yicamalt.stream.VideoUnit
import com.yicamalt.stream.VideoUnitSink
import com.yicamalt.stream.YiP2pAuth
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
import java.nio.ByteOrder
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

// START_MODULE_MAP
//   creds / newLink / frame / leHead - builders (LE 24-byte head, scripted RecvResult).
//   awaitTrue / awaitState / assertThreadsGone - polling helpers bounded at 3 s.
//   order + auth tests - call order, cloud uid, 15-char nonce user + HMAC pass, admin, 8190 / 511 with LE useCount.
//   mode tests - 0 P2P, 1 RELAY, 2 LAN (lan true).
//   failure tests - sessionId / connect / check / clientStart -20009 / sendIoCtrl, -20015 mid-stream, 300 x -20012.
//   drop rule - -20013 / -20014 drop until the next key frame, state stays Streaming.
//   cancellation - close during connect / clientStart / streaming, idempotent close, start after close.
//   timers - connect 30 s and first frame 20 s on an injected clock.
//   factory tests - transport / credential refusals, runtime unavailable / failed, ALREADY_OPEN, reopen.
//   review fixes - non-fatal codes skipped (logged once), vendor fatal list, -20001 buffer growth, late av after
//     close (avClientStop once), thread guard, empty password, deferred registry release, stop after reader left.
//   log hygiene - markers present; RedactionScanner over every buffer incl. the generated nonce and HMAC.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.2.0 - Phase 2 review: vendor fatal read codes and -20001 buffer growth (MED-5), late
//     avClientStart2 after close (MED-6), thread guard / empty password (MED-7), deferred registry release (LOW-8),
//     vendor stop only after the reader left avRecvFrameData2 (LOW-9).
//   PREVIOUS: v0.1.0 - GSD plan 02-11: created.
// END_CHANGE_SUMMARY

class TutkCameraLinkTest {

    private val recorder = TraceRecorder()
    private val links = CopyOnWriteArrayList<CameraLink>()
    private val fakes = CopyOnWriteArrayList<FakeTutkNative>()

    @BeforeEach fun setUp() = recorder.plant()

    @AfterEach
    fun tearDown() {
        links.forEach { it.close() }
        val generated = fakes.flatMap { listOfNotNull(it.lastUser, it.lastPass) }.filter { it != PLAIN }
        RedactionScanner().assertClean(recorder.bufferText(), extraLiterals = SECRETS + generated)
        recorder.unplant()
    }

    private class RecordingSink : VideoUnitSink {
        val units = CopyOnWriteArrayList<VideoUnit>()
        override fun onVideoUnit(unit: VideoUnit) {
            units += unit
        }
    }

    // START_BLOCK_BUILDERS
    private fun creds(
        p2pEncrypt: Boolean = true,
        password: String? = PASSWORD,
        transport: TransportType = TransportType.TUTK,
        deviceId: String = RedactionFixtures.TUTK_UID,
        p2pId: String = RedactionFixtures.TUTK_UID,
        needsPincode: Boolean = false,
    ) = P2pCredentials(
        deviceId = deviceId,
        p2pId = p2pId,
        transport = transport,
        password = password,
        initString = null,
        licenseKey = null,
        p2pEncrypt = p2pEncrypt,
        wakeup = false,
        needsPincode = needsPincode,
        transportVerified = false,
    )

    private fun fake() = FakeTutkNative().also { fakes += it }

    private fun newLink(
        fake: FakeTutkNative,
        credentials: P2pCredentials = creds(),
        clock: AtomicLong? = null,
        timeouts: TutkCameraLink.Timeouts = TutkCameraLink.Timeouts(pollMs = 5),
        cam: Int = CAM.incrementAndGet(),
    ): TutkCameraLink {
        val now: () -> Long = if (clock != null) ({ clock.get() }) else ({ System.nanoTime() / 1_000_000L })
        val link = TutkCameraLink(credentials, LinkOptions(), fake, cam, Random(7), timeouts, now) {
            closedCount.incrementAndGet()
        }
        links += link
        return link
    }

    private val closedCount = AtomicInteger(0)

    private fun leHead(codec: Int = 78, key: Boolean = true, seq: Int = 5): ByteArray =
        ByteBuffer.allocate(FrameHeadCodec.SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(codec.toShort()).put((if (key) 1 else 0).toByte()).put(1).put(1).put(1)
            .putShort(seq.toShort()).putShort(1920).putShort(1080).putInt(1_700_000_000)
            .put(1).put(0).put(0).put(0).putInt(123)
            .array()

    private fun frame(seq: Int, key: Boolean = true, payload: ByteArray = ByteArray(40) { (it + seq).toByte() }) =
        FakeTutkNative.Frame(RecvResult(payload.size, payload.size), payload, leHead(key = key, seq = seq))

    private fun code(c: Int) = FakeTutkNative.Frame(RecvResult(c, 0))

    private fun awaitTrue(ms: Long = 3_000, what: () -> String = { "condition" }, cond: () -> Boolean) {
        val deadline = System.nanoTime() + ms * 1_000_000
        while (!cond()) {
            check(System.nanoTime() < deadline) { "timed out waiting for ${what()}" }
            Thread.sleep(5)
        }
    }

    private fun awaitState(link: CameraLink, ms: Long = 3_000, pred: (LinkState) -> Boolean): LinkState {
        awaitTrue(ms, { "state, last=${link.state.value}" }) { pred(link.state.value) }
        return link.state.value
    }

    private fun assertThreadsGone(cam: Int) = awaitTrue(2_000, { "threads tutk-$cam-*" }) {
        Thread.getAllStackTraces().keys.none { it.isAlive && it.name.startsWith("tutk-$cam-") }
    }

    private fun nonRecv(fake: FakeTutkNative) = fake.calls.filterNot { it.startsWith("recvFrame") }

    private fun failed(kind: LinkErrorKind, code: Int) = LinkState.Failed(LinkError(kind, code))
    // END_BLOCK_BUILDERS

    // START_BLOCK_ORDER_AND_AUTH_TESTS
    @Test
    fun `encrypted happy path follows the vendor order and streams LE-parsed frames`() {
        val fake = fake()
        fake.recvQueue += frame(seq = 0x0102)
        val cam = CAM.incrementAndGet()
        val link = newLink(fake, cam = cam)
        val sink = RecordingSink()
        link.start(sink)

        val st = awaitState(link) { it is LinkState.Streaming } as LinkState.Streaming
        assertTrue(st.authOk)
        assertEquals(TransportType.TUTK, st.info.transport)
        assertEquals(LinkMode.P2P, st.info.mode)
        assertFalse(st.info.lan)
        awaitTrue { nonRecv(fake).size >= 5 }
        assertEquals(
            listOf("sessionId", "connectByUid sid0=0", "check sid=0", "clientStart sid=0 timeoutS=15", "sendIoCtrl av=0 type=8190 size=4"),
            nonRecv(fake).take(5),
        )
        assertEquals(RedactionFixtures.TUTK_UID, fake.lastUid)
        val user = fake.lastUser!!
        assertEquals(15, user.length)
        assertTrue(user.all { it in YiP2pAuth.ALPHABET }, "nonce alphabet")
        assertEquals(YiP2pAuth.hmacPassword(PASSWORD, user), fake.lastPass)
        assertEquals(15, fake.lastPass!!.length)
        val (type, payload) = fake.sentIoCtrl.single()
        assertEquals(IoCtrlMessages.TUTK_START_ENC, type)
        assertArrayEquals(byteArrayOf(1, 0, 0, 0), payload)

        val unit = sink.units.single()
        assertEquals(0x0102, unit.info.seq)
        assertEquals(1920, unit.info.width)
        assertEquals(1080, unit.info.height)
        assertEquals(1_700_000_000L, unit.info.timestampSec)
        assertEquals(123L, unit.info.timestampMs)
        assertTrue(unit.info.isKeyFrame)
        assertArrayEquals(ByteArray(40) { (it + 0x0102).toByte() }, unit.payload)
        assertTrue(fake.threads.all { it.startsWith("tutk-$cam-") }, fake.threads.toString())
        assertEquals(1, fake.callCount("connectByUid"))
        assertEquals(0, fake.callCount("initialize"))
        assertEquals(0, fake.callCount("avInitialize"))
    }

    @Test
    fun `plain camera authenticates as admin with the password and sends 511`() {
        val fake = fake()
        fake.recvQueue += frame(seq = 1)
        val link = newLink(fake, creds(p2pEncrypt = false))
        link.start(RecordingSink())
        awaitState(link) { it is LinkState.Streaming }
        assertEquals(PLAIN, fake.lastUser)
        assertEquals(PASSWORD, fake.lastPass)
        val (type, payload) = fake.sentIoCtrl.single()
        assertEquals(IoCtrlMessages.TUTK_START_PLAIN, type)
        assertArrayEquals(IoCtrlMessages.tutkStart(1), payload)
    }

    @Test
    fun `vendor modes map to P2P RELAY and LAN`() {
        listOf(0 to LinkMode.P2P, 1 to LinkMode.RELAY, 2 to LinkMode.LAN, 7 to LinkMode.UNKNOWN).forEach { (raw, mode) ->
            val fake = fake().apply { checkResult = TutkCheck(TutkCodes.OK, raw, false) }
            val link = newLink(fake)
            link.start(RecordingSink())
            val st = awaitState(link) { it is LinkState.Connected || it is LinkState.Streaming }
            val info = if (st is LinkState.Connected) st.info else (st as LinkState.Streaming).info
            assertEquals(mode, info.mode, "raw=$raw")
            assertEquals(mode == LinkMode.LAN, info.lan, "raw=$raw")
            link.close()
        }
        assertEquals(LinkMode.LAN, TutkCameraLink.modeOf(2))
    }
    // END_BLOCK_ORDER_AND_AUTH_TESTS

    // START_BLOCK_FAILURE_TESTS
    @Test
    fun `wrong password from clientStart fails WRONG_PASSWORD without a start command or retry`() {
        val fake = fake().apply { clientStartResult = ClientStart(TutkCodes.AV_WRONG_VIEWACCORPWD, 0) }
        val link = newLink(fake)
        link.start(RecordingSink())
        assertEquals(failed(LinkErrorKind.WRONG_PASSWORD, -20009), awaitState(link) { it is LinkState.Failed })
        Thread.sleep(50)
        assertEquals(0, fake.callCount("sendIoCtrl"))
        assertEquals(1, fake.callCount("clientStart"))
        assertEquals(1, fake.callCount("connectByUid"))
        assertEquals(listOf("stop av=-1 sid=0"), fake.calls.filter { it.startsWith("stop") })
    }

    @Test
    fun `negative connect and session id results are classified and never retried`() {
        val offline = fake().apply { connectResult = TutkCodes.IOTC_DEVICE_OFFLINE }
        val a = newLink(offline)
        a.start(RecordingSink())
        assertEquals(failed(LinkErrorKind.OFFLINE, -90), awaitState(a) { it is LinkState.Failed })
        Thread.sleep(50)
        assertEquals(1, offline.callCount("connectByUid"))
        assertEquals(0, offline.callCount("check"))
        assertEquals(listOf("stop av=-1 sid=0"), offline.calls.filter { it.startsWith("stop") })

        val noSession = fake().apply { sessionIdResult = TutkCodes.IOTC_EXCEED_MAX_SESSION }
        val b = newLink(noSession)
        b.start(RecordingSink())
        assertEquals(failed(LinkErrorKind.BUSY, -18), awaitState(b) { it is LinkState.Failed })
        assertEquals(0, noSession.callCount("connectByUid"))
        assertEquals(0, noSession.callCount("stop"))
    }

    @Test
    fun `failed session check and failed start command are terminal`() {
        val badCheck = fake().apply { checkResult = TutkCheck(TutkCodes.IOTC_SESSION_CLOSE_BY_REMOTE, 0, false) }
        val a = newLink(badCheck)
        a.start(RecordingSink())
        assertEquals(failed(LinkErrorKind.REMOTE_CLOSED, -22), awaitState(a) { it is LinkState.Failed })
        assertEquals(0, badCheck.callCount("clientStart"))

        val badSend = fake().apply { sendIoCtrlResult = TutkCodes.AV_SESSION_CLOSE_BY_REMOTE }
        val b = newLink(badSend)
        b.start(RecordingSink())
        assertEquals(failed(LinkErrorKind.REMOTE_CLOSED, -20015), awaitState(b) { it is LinkState.Failed })
        awaitTrue { badSend.callCount("stop") == 1 }
        assertEquals(listOf("stop av=0 sid=0"), badSend.calls.filter { it.startsWith("stop") })
    }

    @Test
    fun `remote close mid-stream fails REMOTE_CLOSED and does not reconnect`() {
        val fake = fake()
        fake.recvQueue += frame(seq = 1)
        fake.recvQueue += code(TutkCodes.AV_SESSION_CLOSE_BY_REMOTE)
        val link = newLink(fake)
        val sink = RecordingSink()
        link.start(sink)
        assertEquals(failed(LinkErrorKind.REMOTE_CLOSED, -20015), awaitState(link) { it is LinkState.Failed })
        Thread.sleep(100)
        assertEquals(1, sink.units.size)
        assertEquals(1, fake.callCount("connectByUid"))
        assertEquals(1, fake.callCount("sessionId"))
        assertEquals(1, fake.callCount("stop"))
    }

    @Test
    fun `300 consecutive no-data polls end the session as TIMEOUT`() {
        val fake = fake()
        val link = newLink(fake, timeouts = TutkCameraLink.Timeouts(pollMs = 5, noDataSleepMs = 0))
        link.start(RecordingSink())
        assertEquals(failed(LinkErrorKind.TIMEOUT, -20012), awaitState(link) { it is LinkState.Failed })
        assertEquals(300, fake.callCount("recvFrame"))
        assertTrue(recorder.bufferText().contains("exit=no-data code=AV_ER_DATA_NOREADY"))
    }

    @Test
    fun `a frame resets the no-data counter`() {
        val fake = fake()
        repeat(299) { fake.recvQueue += code(TutkCodes.AV_DATA_NOREADY) }
        fake.recvQueue += frame(seq = 1)
        repeat(299) { fake.recvQueue += code(TutkCodes.AV_DATA_NOREADY) }
        fake.recvQueue += frame(seq = 2)
        val link = newLink(fake, timeouts = TutkCameraLink.Timeouts(pollMs = 5, noDataSleepMs = 0))
        val sink = RecordingSink()
        link.start(sink)
        assertEquals(failed(LinkErrorKind.TIMEOUT, -20012), awaitState(link) { it is LinkState.Failed })
        assertEquals(listOf(1, 2), sink.units.map { it.info.seq })
        assertEquals(900, fake.callCount("recvFrame"))
    }
    // END_BLOCK_FAILURE_TESTS

    // START_BLOCK_DROP_RULE_TESTS
    @Test
    fun `lost and incomplete frames drop until the next key frame while streaming`() {
        val fake = fake()
        fake.recvQueue += frame(seq = 1, key = true)
        fake.recvQueue += code(TutkCodes.AV_LOSED_THIS_FRAME)
        fake.recvQueue += frame(seq = 2, key = false)
        fake.recvQueue += frame(seq = 3, key = false)
        fake.recvQueue += frame(seq = 4, key = true)
        fake.recvQueue += frame(seq = 5, key = false)
        fake.recvQueue += code(TutkCodes.AV_INCOMPLETE_FRAME)
        fake.recvQueue += frame(seq = 6, key = false)
        fake.recvQueue += frame(seq = 7, key = true)
        val link = newLink(fake)
        val sink = RecordingSink()
        link.start(sink)
        awaitTrue(what = { sink.units.map { it.info.seq }.toString() }) { sink.units.size >= 4 }
        Thread.sleep(50)
        assertEquals(listOf(1, 4, 5, 7), sink.units.map { it.info.seq })
        assertTrue(link.state.value is LinkState.Streaming, link.state.value.toString())
        assertEquals(1, fake.callCount("connectByUid"))
    }
    // END_BLOCK_DROP_RULE_TESTS

    // START_BLOCK_CANCELLATION_TESTS
    @Test
    fun `close during connect stops the connect by sid0 and releases the session once`() {
        val fake = fake().apply { connectGate = CountDownLatch(1) }
        val cam = CAM.incrementAndGet()
        val link = newLink(fake, cam = cam)
        link.start(RecordingSink())
        awaitTrue { fake.callCount("connectByUid") == 1 }
        link.close()
        assertEquals(LinkState.Closed("user"), link.state.value)
        assertEquals(1, fake.callCount("connectStop sid0=0"))
        assertEquals(0, fake.callCount("check"))
        assertEquals(listOf("stop av=-1 sid=0"), fake.calls.filter { it.startsWith("stop") })
        assertThreadsGone(cam)
        assertEquals(1, closedCount.get())
    }

    @Test
    fun `close during clientStart aborts it through the vendor stop and sends no start command`() {
        val fake = fake().apply { clientStartGate = CountDownLatch(1) }
        val cam = CAM.incrementAndGet()
        val link = newLink(fake, cam = cam)
        link.start(RecordingSink())
        awaitTrue { fake.callCount("clientStart") == 1 }
        link.close("screen-left")
        assertEquals(LinkState.Closed("screen-left"), link.state.value)
        assertEquals(0, fake.callCount("connectStop"))
        assertEquals(0, fake.callCount("sendIoCtrl"))
        assertEquals(listOf("stop av=-1 sid=0"), fake.calls.filter { it.startsWith("stop") })
        assertThreadsGone(cam)
    }

    @Test
    fun `close while streaming calls stop once, is idempotent and ignores a later start`() {
        val fake = fake()
        fake.recvQueue += frame(seq = 1)
        val cam = CAM.incrementAndGet()
        val link = newLink(fake, cam = cam)
        link.start(RecordingSink())
        awaitState(link) { it is LinkState.Streaming }
        link.close()
        link.close("background")
        link.start(RecordingSink())
        assertEquals(LinkState.Closed("user"), link.state.value)
        assertEquals(listOf("stop av=0 sid=0"), fake.calls.filter { it.startsWith("stop") })
        assertEquals(0, fake.callCount("connectStop"))
        assertEquals(1, fake.callCount("connectByUid"))
        assertThreadsGone(cam)
        assertEquals(1, closedCount.get())
        assertTrue(fake.threads.all { it.startsWith("tutk-$cam-") }, fake.threads.toString())
        assertTrue(recorder.bufferText().contains("[P2P][tutk][BLOCK_CLOSE] cam=$cam reason=user units=1"))
    }

    @Test
    fun `close before start makes no native call`() {
        val fake = fake()
        val link = newLink(fake)
        link.close()
        link.start(RecordingSink())
        Thread.sleep(50)
        assertEquals(LinkState.Closed("user"), link.state.value)
        assertTrue(fake.calls.isEmpty(), fake.calls.toString())
    }
    // END_BLOCK_CANCELLATION_TESTS

    // START_BLOCK_TIMER_TESTS
    @Test
    fun `connect longer than 30 s fails TIMEOUT and stops the connect`() {
        val clock = AtomicLong(1_000)
        val fake = fake().apply { connectGate = CountDownLatch(1) }
        val link = newLink(fake, clock = clock)
        link.start(RecordingSink())
        awaitTrue { fake.callCount("connectByUid") == 1 }
        clock.addAndGet(29_999)
        Thread.sleep(40)
        assertEquals(LinkState.Connecting, link.state.value)
        clock.addAndGet(1)
        assertEquals(failed(LinkErrorKind.TIMEOUT, TutkCodes.IOTC_TIMEOUT), awaitState(link) { it is LinkState.Failed })
        awaitTrue { fake.callCount("connectStop") == 1 }
    }

    @Test
    fun `no frame 20 s after the start command fails TIMEOUT 0`() {
        val clock = AtomicLong(1_000)
        val fake = fake()
        val link = newLink(fake, clock = clock, timeouts = TutkCameraLink.Timeouts(pollMs = 5, maxNoDataPolls = Int.MAX_VALUE, noDataSleepMs = 1))
        link.start(RecordingSink())
        awaitTrue { fake.callCount("sendIoCtrl") == 1 }
        clock.addAndGet(19_999)
        Thread.sleep(40)
        assertTrue(link.state.value is LinkState.Connected, link.state.value.toString())
        clock.addAndGet(1)
        assertEquals(failed(LinkErrorKind.TIMEOUT, 0), awaitState(link) { it is LinkState.Failed })
        awaitTrue { fake.callCount("stop") == 1 }
    }
    // END_BLOCK_TIMER_TESTS

    // START_BLOCK_REVIEW_FIX_TESTS
    @Test
    fun `non-fatal read codes are skipped, logged once per code, and streaming continues`() {
        val fake = fake()
        fake.recvQueue += frame(seq = 1)
        fake.recvQueue += code(TutkCodes.AV_SERVER_EXIT)
        fake.recvQueue += code(TutkCodes.AV_TIMEOUT)
        fake.recvQueue += code(TutkCodes.AV_SERVER_EXIT)
        fake.recvQueue += frame(seq = 2, key = false)
        val link = newLink(fake, timeouts = TutkCameraLink.Timeouts(pollMs = 5, noDataSleepMs = 1))
        val sink = RecordingSink()
        link.start(sink)
        awaitTrue(what = { sink.units.map { it.info.seq }.toString() }) { sink.units.size == 2 }
        assertTrue(link.state.value is LinkState.Streaming, link.state.value.toString())
        val text = recorder.bufferText()
        assertEquals(1, Regex("skip=AV_ER_SERVER_EXIT").findAll(text).count(), text)
        assertEquals(1, Regex("skip=AV_ER_TIMEOUT").findAll(text).count(), text)
    }

    @Test
    fun `every vendor fatal read code ends the session`() {
        TutkCameraLink.FATAL_READ_CODES.forEach { c ->
            val fake = fake()
            fake.recvQueue += frame(seq = 1)
            fake.recvQueue += code(c)
            val link = newLink(fake)
            link.start(RecordingSink())
            assertEquals(failed(TutkCodes.classify(c), c), awaitState(link) { it is LinkState.Failed }, "code=$c")
        }
        assertEquals(
            setOf(-20015, -20016, -20010, -20000, -20018, -20019, -20025, -20026),
            TutkCameraLink.FATAL_READ_CODES,
        )
    }

    @Test
    fun `buffer too small grows the buffer once and drops to the next key frame`() {
        val fake = fake()
        fake.recvQueue += frame(seq = 1)
        fake.recvQueue += code(TutkCodes.AV_BUFPARA_MAXSIZE_INSUFF)
        fake.recvQueue += frame(seq = 2, key = false)
        fake.recvQueue += frame(seq = 3, key = true)
        fake.recvQueue += code(TutkCodes.AV_BUFPARA_MAXSIZE_INSUFF)
        fake.recvQueue += frame(seq = 4, key = true)
        val link = newLink(fake, timeouts = TutkCameraLink.Timeouts(pollMs = 5, noDataSleepMs = 1))
        val sink = RecordingSink()
        link.start(sink)
        awaitTrue(what = { sink.units.map { it.info.seq }.toString() }) { sink.units.size == 3 }
        assertEquals(listOf(1, 3, 4), sink.units.map { it.info.seq })
        assertEquals(TutkCameraLink.FRAME_BUF_SIZE, fake.bufSizes.first())
        assertEquals(TutkCameraLink.LARGE_FRAME_BUF_SIZE, fake.bufSizes.last())
        assertEquals(setOf(TutkCameraLink.FRAME_BUF_SIZE, TutkCameraLink.LARGE_FRAME_BUF_SIZE), fake.bufSizes.toSet())
        assertTrue(link.state.value is LinkState.Streaming)
    }

    @Test
    fun `avClientStart2 returning after close still gets avClientStop exactly once`() {
        val fake = fake().apply {
            clientStartGate = CountDownLatch(1)
            abortedClientStartResult = ClientStart(0, 0)
        }
        val cam = CAM.incrementAndGet()
        val link = newLink(fake, cam = cam)
        link.start(RecordingSink())
        awaitTrue { fake.callCount("clientStart") == 1 }
        link.close()
        assertThreadsGone(cam)
        assertEquals(listOf("stop av=-1 sid=0", "stop av=0 sid=-1"), fake.calls.filter { it.startsWith("stop") })
        assertEquals(0, fake.callCount("sendIoCtrl"))
        assertEquals(1, closedCount.get())
    }

    @Test
    fun `a sink that throws an Error ends the link as INTERNAL with the vendor stop`() {
        val fake = fake()
        fake.recvQueue += frame(seq = 1)
        val link = newLink(fake)
        link.start(object : VideoUnitSink {
            override fun onVideoUnit(unit: VideoUnit) = throw AssertionError("SYNTH sink failure")
        })
        assertEquals(failed(LinkErrorKind.INTERNAL, 0), awaitState(link) { it is LinkState.Failed })
        awaitTrue { fake.callCount("stop") == 1 }
        assertEquals(listOf("stop av=0 sid=0"), fake.calls.filter { it.startsWith("stop") })
        assertTrue(recorder.bufferText().contains("[P2P][tutk][BLOCK_THREAD_GUARD]"))
    }

    @Test
    fun `an empty encrypted password fails INTERNAL and releases the session`() {
        val fake = fake()
        val link = newLink(fake, creds(password = ""))
        link.start(RecordingSink())
        assertEquals(failed(LinkErrorKind.INTERNAL, 0), awaitState(link) { it is LinkState.Failed })
        awaitTrue { fake.callCount("stop") == 1 }
        assertEquals(0, fake.callCount("clientStart"))
    }

    @Test
    fun `registry entry is released only after a stuck connect thread exits`() {
        val gate = CountDownLatch(1)
        val fake = fake().apply {
            connectGate = gate
            connectIgnoresStop = true
        }
        val link = newLink(fake, timeouts = TutkCameraLink.Timeouts(pollMs = 5, joinMs = 300))
        link.start(RecordingSink())
        awaitTrue { fake.callCount("connectByUid") == 1 }
        link.close()
        assertEquals(LinkState.Closed("user"), link.state.value)
        Thread.sleep(100)
        assertEquals(0, closedCount.get())
        gate.countDown()
        awaitTrue { closedCount.get() == 1 }
        assertTrue(recorder.bufferText().contains("registry=deferred"))
    }

    @Test
    fun `vendor stop runs only after the reader left avRecvFrameData2`() {
        repeat(3) {
            val fake = fake().apply { recvDelayMs = 15 }
            fake.recvQueue += frame(seq = 1)
            // No pause between polls: the reader is almost always inside recvFrame when close() runs.
            val link = newLink(fake, timeouts = TutkCameraLink.Timeouts(pollMs = 5, noDataSleepMs = 0))
            link.start(RecordingSink())
            awaitState(link) { it is LinkState.Streaming }
            Thread.sleep(20)
            link.close()
            assertEquals(0, fake.recvInFlightAtStop)
            val calls = fake.calls.toList()
            val stopAt = calls.indexOfFirst { c -> c.startsWith("stop") }
            assertTrue(calls.subList(stopAt, calls.size).none { c -> c.startsWith("recvFrame") }, calls.toString())
        }
    }
    // END_BLOCK_REVIEW_FIX_TESTS

    // START_BLOCK_FACTORY_TESTS
    private class Loader(private val fail: Boolean = false) : (String) -> Unit {
        val loaded = CopyOnWriteArrayList<String>()
        override fun invoke(name: String) {
            loaded += name
            if (fail) throw UnsatisfiedLinkError("missing")
        }
    }

    private fun factory(fake: FakeTutkNative, loader: Loader = Loader()) =
        TutkLinkFactory(fake, TutkRuntime(fake, NativeLibs(loader)))

    private fun TutkLinkFactory.openTracked(c: P2pCredentials) = open(c, LinkOptions()).also { links += it }

    @Test
    fun `factory refuses other transports and missing credentials without native calls`() {
        val fake = fake()
        val loader = Loader()
        val f = factory(fake, loader)
        listOf(TransportType.TNP, TransportType.LANGTAO, TransportType.UNKNOWN).forEach {
            assertEquals(failed(LinkErrorKind.UNSUPPORTED_TRANSPORT, 0), f.openTracked(creds(transport = it)).state.value)
        }
        listOf(creds(p2pId = " "), creds(needsPincode = true), creds(password = null), creds(password = "")).forEach {
            assertEquals(failed(LinkErrorKind.NO_CREDENTIALS, 0), f.openTracked(it).state.value, it.toString())
        }
        assertTrue(fake.calls.isEmpty(), fake.calls.toString())
        assertTrue(loader.loaded.isEmpty())
        assertEquals(LinkState.Idle, f.openTracked(creds(p2pEncrypt = false, password = null)).state.value)
    }

    @Test
    fun `factory maps runtime Unavailable and InitFailed to NATIVE_UNAVAILABLE`() {
        val missing = fake()
        val link = factory(missing, Loader(fail = true)).openTracked(creds())
        assertEquals(failed(LinkErrorKind.NATIVE_UNAVAILABLE, 0), link.state.value)
        link.start(RecordingSink())
        assertTrue(missing.calls.isEmpty(), missing.calls.toString())

        val broken = fake().apply { initResult = TutkCodes.IOTC_FAIL_CREATE_SOCKET }
        val f = factory(broken)
        assertEquals(failed(LinkErrorKind.NATIVE_UNAVAILABLE, -6), f.openTracked(creds()).state.value)
        assertEquals(failed(LinkErrorKind.NATIVE_UNAVAILABLE, -6), f.openTracked(creds(deviceId = "cam-b")).state.value)
        assertEquals(1, broken.callCount("initialize"))
    }

    @Test
    fun `factory gives one session per camera and frees it on close`() {
        val fake = fake()
        val f = factory(fake)
        val first = f.openTracked(creds())
        first.start(RecordingSink())
        awaitTrue { fake.callCount("clientStart") == 1 }

        val second = f.openTracked(creds())
        assertEquals(failed(LinkErrorKind.ALREADY_OPEN, 0), second.state.value)
        second.start(RecordingSink())
        Thread.sleep(50)
        assertEquals(1, fake.callCount("connectByUid"))

        first.close()
        val third = f.openTracked(creds())
        assertEquals(LinkState.Idle, third.state.value)
        third.start(RecordingSink())
        awaitTrue { fake.callCount("connectByUid") == 2 }
        assertEquals(1, fake.callCount("initialize"))
    }
    // END_BLOCK_FACTORY_TESTS

    // START_BLOCK_LOG_HYGIENE
    @Test
    fun `every link block logs a value-free marker`() {
        val fake = fake()
        fake.recvQueue += frame(seq = 1)
        fake.recvQueue += code(TutkCodes.AV_SESSION_CLOSE_BY_REMOTE)
        val link = newLink(fake)
        link.start(RecordingSink())
        awaitState(link) { it is LinkState.Failed }
        awaitTrue { recorder.bufferText().contains("[BLOCK_CLOSE]") }
        val text = recorder.bufferText()
        listOf("BLOCK_CONNECT", "BLOCK_CHECK_SESSION", "BLOCK_CLIENT_START", "BLOCK_READ_LOOP", "BLOCK_CLOSE").forEach {
            assertTrue(text.contains("[P2P][tutk][$it] cam="), it)
        }
        assertFalse(text.contains(PASSWORD))
        assertFalse(text.contains(RedactionFixtures.TUTK_UID))
        assertFalse(text.contains(fake.lastUser!!))
        assertFalse(text.contains(fake.lastPass!!))
        assertFalse(link.toString().contains(RedactionFixtures.TUTK_UID))
        assertFalse(link.state.value.toString().contains(RedactionFixtures.TUTK_UID))
    }
    // END_BLOCK_LOG_HYGIENE

    companion object {
        private val CAM = AtomicInteger(1000)
        private const val PASSWORD = "SYNTH1234567890"
        private const val PLAIN = "admin"
        private val SECRETS = RedactionFixtures.ALL_SECRETS + listOf(PASSWORD)
    }
}
