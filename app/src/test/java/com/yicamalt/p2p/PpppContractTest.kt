// FILE: PpppContractTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Executable contract for the Phase 2 port types (StreamTypes, CameraLink, PpppNative/PpppCodes) and
//     the scripted FakePpppNative every TNP link test builds on.
//   SCOPE: VideoCodec wire ids, FrameInfo key flag, PpppCodes classify/name, fake read/write/close/connect model,
//     value-free toString of link and frame types.
//   DEPENDS: M-STREAM-CORE, M-P2P-NATIVE, FakePpppNative, RedactionScanner, RedactionFixtures
//   LINKS: V-M-P2P-NATIVE, V-M-STREAM-CORE
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.p2p

import com.yicamalt.camera.TransportType
import com.yicamalt.stream.DecodableFrame
import com.yicamalt.stream.FrameInfo
import com.yicamalt.stream.LinkError
import com.yicamalt.stream.LinkErrorKind
import com.yicamalt.stream.LinkInfo
import com.yicamalt.stream.LinkMode
import com.yicamalt.stream.LinkState
import com.yicamalt.stream.VideoCodec
import com.yicamalt.stream.VideoUnit
import com.yicamalt.test.RedactionFixtures
import com.yicamalt.test.RedactionScanner
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

// START_MODULE_MAP
//   frame(flags) - FrameInfo builder with only flags varying.
//   codec / key-frame tests - VideoCodec.fromWireId and FrameInfo.isKeyFrame.
//   classify / name tests - PpppCodes table from 02-RESEARCH.md.
//   fake read / write / close / connect tests - FakePpppNative behaviour model.
//   toString hygiene test - RedactionScanner over every port type's toString.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 02-00: created with the port types.
// END_CHANGE_SUMMARY

class PpppContractTest {

    private fun frame(flags: Int) = FrameInfo(
        codecId = 78, flags = flags, liveFlag = 1, onlineNum = 1, useCount = 0, seq = 7, width = 1920, height = 1080,
        timestampSec = 1_700_000L, isDay = 1, cover = 0, outLoss = 0, inLoss = 0, timestampMs = 120L,
    )

    // START_BLOCK_STREAM_TYPES
    @Test
    fun `video codec maps wire ids and mimes`() {
        assertEquals(VideoCodec.H264, VideoCodec.fromWireId(78))
        assertEquals(VideoCodec.H265, VideoCodec.fromWireId(81))
        assertNull(VideoCodec.fromWireId(79))
        assertEquals("video/avc", VideoCodec.H264.mime)
        assertEquals("video/hevc", VideoCodec.H265.mime)
    }

    @Test
    fun `frame info key flag is bit 0`() {
        assertTrue(frame(1).isKeyFrame)
        assertFalse(frame(0).isKeyFrame)
        assertTrue(frame(3).isKeyFrame)
    }
    // END_BLOCK_STREAM_TYPES

    // START_BLOCK_CODES
    @Test
    fun `classify follows the PPPP error table`() {
        val expected = mapOf(
            -3006 to LinkErrorKind.OFFLINE,
            -3004 to LinkErrorKind.CREDENTIALS,
            -3023 to LinkErrorKind.CREDENTIALS,
            -3035 to LinkErrorKind.CREDENTIALS,
            -3026 to LinkErrorKind.NETWORK,
            -3012 to LinkErrorKind.REMOTE_CLOSED,
            -3029 to LinkErrorKind.BUSY,
            -3101 to LinkErrorKind.KICKED,
            -20009 to LinkErrorKind.WRONG_PASSWORD,
            -9000 to LinkErrorKind.PROTOCOL,
            -1 to LinkErrorKind.INTERNAL,
        )
        expected.forEach { (code, kind) -> assertEquals(kind, PpppCodes.classify(code), "code $code") }
    }

    @Test
    fun `name renders known and unknown codes`() {
        assertEquals("TIME_OUT", PpppCodes.name(-3003))
        assertEquals("UNKNOWN_-1", PpppCodes.name(-1))
    }
    // END_BLOCK_CODES

    // START_BLOCK_FAKE_NATIVE
    @Test
    fun `fake read fills the requested size from a fuller queue`() {
        val fake = FakePpppNative()
        val h = fake.connect("SYNTH", 0x49, false, "SYNTH", "SYNTH")
        fake.enqueue(2, ByteArray(10) { it.toByte() })
        val buf = ByteArray(16)
        val size = intArrayOf(4)
        assertEquals(0, fake.read(h, 2, buf, size, 1000))
        assertEquals(4, size[0])
        assertArrayEquals(byteArrayOf(0, 1, 2, 3), buf.copyOf(4))
    }

    @Test
    fun `fake read on empty queue times out with zero bytes`() {
        val fake = FakePpppNative()
        val h = fake.connect("SYNTH", 0x49, false, "SYNTH", "SYNTH")
        val size = intArrayOf(8)
        assertEquals(PpppCodes.TIME_OUT, fake.read(h, 2, ByteArray(8), size, 1000))
        assertEquals(0, size[0])
    }

    @Test
    fun `fake partial read returns time out with the partial count`() {
        val fake = FakePpppNative()
        val h = fake.connect("SYNTH", 0x49, false, "SYNTH", "SYNTH")
        fake.enqueue(1, byteArrayOf(9, 8, 7))
        val buf = ByteArray(8)
        val size = intArrayOf(8)
        assertEquals(PpppCodes.TIME_OUT, fake.read(h, 1, buf, size, 1000))
        assertEquals(3, size[0])
        assertArrayEquals(byteArrayOf(9, 8, 7), buf.copyOf(3))
    }

    @Test
    fun `fake force close makes reads return session closed called`() {
        val fake = FakePpppNative()
        val h = fake.connect("SYNTH", 0x49, false, "SYNTH", "SYNTH")
        fake.enqueue(2, ByteArray(4))
        fake.forceClose(h)
        assertEquals(PpppCodes.SESSION_CLOSED_CALLED, fake.read(h, 2, ByteArray(4), intArrayOf(4), 1000))
        assertEquals(PpppCodes.SESSION_CLOSED_CALLED, fake.read(h, 0, ByteArray(4), intArrayOf(4), 1000))
        assertEquals(PpppCodes.INVALID_SESSION_HANDLE, fake.write(h, 0, ByteArray(4), 4))
    }

    @Test
    fun `fake remote close injection hits the next read on that channel`() {
        val fake = FakePpppNative()
        val h = fake.connect("SYNTH", 0x49, false, "SYNTH", "SYNTH")
        fake.injectRemoteClose(3)
        assertEquals(PpppCodes.SESSION_CLOSED_REMOTE, fake.read(h, 3, ByteArray(4), intArrayOf(4), 1000))
        assertEquals(PpppCodes.TIME_OUT, fake.read(h, 2, ByteArray(4), intArrayOf(4), 10))
    }

    @Test
    fun `fake connect returns the scripted result`() {
        val fake = FakePpppNative().apply { connectResult = PpppCodes.DEVICE_NOT_ONLINE }
        assertEquals(PpppCodes.DEVICE_NOT_ONLINE, fake.connect("SYNTH", 0x49, false, "SYNTH", "SYNTH"))
    }

    @Test
    fun `fake blocking connect is released by connect break`() {
        val fake = FakePpppNative().apply { connectBlocks = true }
        val result = AtomicInteger(Int.MIN_VALUE)
        val t = thread(name = "pppp-test-connect") { result.set(fake.connect("SYNTH", 0x49, true, "SYNTH", "SYNTH")) }
        Thread.sleep(100)
        assertEquals(Int.MIN_VALUE, result.get(), "connect must still be blocked")
        fake.connectBreak("SYNTH")
        t.join(2000)
        assertEquals(PpppCodes.USER_CONNECT_BREAK, result.get())
    }

    @Test
    fun `fake records value-free calls and written bytes`() {
        val fake = FakePpppNative()
        val h = fake.connect("SYNTH", 0x49, false, "SYNTH", "SYNTH")
        assertEquals(56, fake.write(h, 0, ByteArray(56), 56))
        fake.read(h, 2, ByteArray(4), intArrayOf(4), 1)
        assertTrue(fake.calls.contains("connect flag=0x49 wakeup=false"), fake.calls.toString())
        assertTrue(fake.calls.contains("write ch=0 bytes=56"), fake.calls.toString())
        assertTrue(fake.calls.contains("read ch=2"), fake.calls.toString())
        assertEquals(1, fake.written.size)
        assertEquals(0, fake.written[0].first)
        assertEquals(56, fake.written[0].second.size)
        RedactionScanner().assertClean(fake.calls.joinToString("\n"), RedactionFixtures.ALL_SECRETS)
    }
    // END_BLOCK_FAKE_NATIVE

    // START_BLOCK_TOSTRING_HYGIENE
    @Test
    fun `port type toString carries no byte content`() {
        val info = LinkInfo(TransportType.TNP, LinkMode.P2P, lan = true, connectMs = 812)
        val secretBytes = RedactionFixtures.AUTH_INFO.toByteArray()
        val lines = listOf(
            LinkState.Idle.toString(),
            LinkState.Connecting.toString(),
            LinkState.Connected(info).toString(),
            LinkState.Streaming(info, authOk = true).toString(),
            LinkState.Failed(LinkError(LinkErrorKind.OFFLINE, -3006)).toString(),
            LinkState.Closed("user").toString(),
            VideoUnit(2, frame(1), secretBytes).toString(),
            DecodableFrame(VideoCodec.H265, true, secretBytes, listOf(secretBytes), 0L, 1920, 1080).toString(),
            PpppCheck(0, 0, 812, true).toString(),
        )
        RedactionScanner().assertClean(lines.joinToString("\n"), RedactionFixtures.ALL_SECRETS)
    }
    // END_BLOCK_TOSTRING_HYGIENE
}
