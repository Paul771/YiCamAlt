// FILE: SdArchiveCodecTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Executable contract for the SD archive byte layer (request builders and reply parsers).
//   SCOPE: 816 / 9031 / 792 / 9030 / 12718 / 794 payload bytes in both byte orders, 9032 / 793 / 817 parsing with
//          the vendor tolerance rules. Synthetic dates only.
//   DEPENDS: M-SD-ARCHIVE (SdArchiveCodec, STimeDay), SdFixtures
//   LINKS: V-M-SD-ARCHIVE
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.archive

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

// START_MODULE_MAP
//   bytes(vararg) - int list to byte array.
//   T_START / T_END / T_NOON - synthetic STimeDay values (2026-10-01T00:00:37Z, 2026-10-30T12:34:56Z, 2026-10-30T12:00Z).
//   SdArchiveCodecTest - builder bytes, constants, TNP index, TUTK page, device info parsing.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 04-01: byte vectors from 04-RESEARCH P1-P5.
// END_CHANGE_SUMMARY

class SdArchiveCodecTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    // 2026-10-01 is a Thursday (wday 5), 2026-10-30 a Friday (wday 6).
    private val tStart = STimeDay(2026, 10, 1, 5, 0, 0, 37)
    private val tEnd = STimeDay(2026, 10, 30, 6, 12, 34, 56)
    private val tNoon = STimeDay(2026, 10, 30, 6, 12, 0, 0)

    @Test
    fun `deviceInfoRequest is 4 zero bytes`() {
        assertArrayEquals(ByteArray(4), SdArchiveCodec.deviceInfoRequest())
    }

    @Test
    fun `iotype constants`() {
        assertEquals(816, SdArchiveCodec.DEVICE_INFO_REQ)
        assertEquals(817, SdArchiveCodec.DEVICE_INFO_RESP)
        assertEquals(9030, SdArchiveCodec.TNP_START_RECORD)
        assertEquals(9031, SdArchiveCodec.TNP_LIST_EVENTS_REQ)
        assertEquals(9032, SdArchiveCodec.TNP_LIST_EVENTS_RESP)
        assertEquals(792, SdArchiveCodec.TUTK_LIST_EVENTS_REQ)
        assertEquals(793, SdArchiveCodec.TUTK_LIST_EVENTS_RESP)
        assertEquals(794, SdArchiveCodec.RECORD_PLAYCONTROL)
        assertEquals(12718, SdArchiveCodec.RECORD_PLAYCONTROL2)
        assertEquals(16, SdArchiveCodec.PLAY_START)
        assertEquals(1, SdArchiveCodec.PLAY_STOP)
    }

    @Test
    fun `listEventsRequest BE bytes`() {
        val expected = bytes(
            0x00, 0x00, 0x00, 0x00,
            0x07, 0xEA, 0x0A, 0x01, 0x05, 0x00, 0x00, 0x00,
            0x07, 0xEA, 0x0A, 0x1E, 0x06, 0x0C, 0x22, 0x00,
            0x00, 0x00, 0x00, 0x00,
        )
        assertArrayEquals(expected, SdArchiveCodec.listEventsRequest(tStart, tEnd, bigEndian = true))
    }

    @Test
    fun `listEventsRequest LE bytes`() {
        val p = SdArchiveCodec.listEventsRequest(tStart, tEnd, bigEndian = false)
        assertEquals(24, p.size)
        assertArrayEquals(ByteArray(4), p.copyOfRange(0, 4))
        assertArrayEquals(bytes(0xEA, 0x07, 0x0A, 0x01, 0x05, 0x00, 0x00, 0x00), p.copyOfRange(4, 12))
        assertArrayEquals(bytes(0xEA, 0x07, 0x0A, 0x1E, 0x06, 0x0C, 0x22, 0x00), p.copyOfRange(12, 20))
        assertArrayEquals(ByteArray(4), p.copyOfRange(20, 24))
    }

    @Test
    fun `tnpPlayRecord bytes`() {
        assertArrayEquals(
            bytes(0x03, 0x01, 0x01, 0x00, 0x07, 0xEA, 0x0A, 0x1E, 0x06, 0x0C, 0x00, 0x00),
            SdArchiveCodec.tnpPlayRecord(useCount = 3, resolution = 1, at = tNoon),
        )
        assertEquals(0x03.toByte(), SdArchiveCodec.tnpPlayRecord(useCount = 259, resolution = 1, at = tNoon)[0])
    }

    @Test
    fun `playControl start LE`() {
        val p = SdArchiveCodec.playControl(SdArchiveCodec.PLAY_START, tNoon, useCount = 5, bigEndian = false)
        assertEquals(24, p.size)
        assertArrayEquals(ByteArray(4), p.copyOfRange(0, 4))
        assertArrayEquals(bytes(0x10, 0x00, 0x00, 0x00), p.copyOfRange(4, 8))
        assertArrayEquals(ByteArray(4), p.copyOfRange(8, 12))
        assertArrayEquals(bytes(0xEA, 0x07, 0x0A, 0x1E, 0x06, 0x0C, 0x00, 0x00), p.copyOfRange(12, 20))
        assertEquals(5.toByte(), p[20])
        assertArrayEquals(ByteArray(3), p.copyOfRange(21, 24))
    }

    @Test
    fun `playControl stop has zero time`() {
        val p = SdArchiveCodec.playControl(SdArchiveCodec.PLAY_STOP, at = null, useCount = 0, bigEndian = true)
        assertEquals(24, p.size)
        assertArrayEquals(bytes(0x00, 0x00, 0x00, 0x01), p.copyOfRange(4, 8))
        assertArrayEquals(ByteArray(8), p.copyOfRange(12, 20))
        assertEquals(0.toByte(), p[20])
        val expected = ByteArray(24).also { it[7] = 1 }
        assertArrayEquals(expected, p)
    }

    @Test
    fun `playControlType`() {
        assertEquals(12718, SdArchiveCodec.playControlType(encrypted = true))
        assertEquals(794, SdArchiveCodec.playControlType(encrypted = false))
    }

    @Test
    fun `readTimeDay reads unsigned fields in both orders`() {
        val be = bytes(0xFF, 0xFE, 0x0C, 0x1F, 0x07, 0x17, 0x3B, 0x3B)
        assertEquals(STimeDay(0xFFFE, 12, 31, 7, 23, 59, 59), SdArchiveCodec.readTimeDay(be, 0, bigEndian = true))
        val le = bytes(0x00, 0xEA, 0x07, 0x0A, 0x1E, 0x06, 0x0C, 0x00, 0x00)
        assertEquals(tNoon, SdArchiveCodec.readTimeDay(le, 1, bigEndian = false))
    }

    @Test
    fun `parseTnpIndex count 0`() {
        assertEquals(emptyList<IndexEntry>(), SdArchiveCodec.parseTnpIndex(SdFixtures.tnpIndex()))
    }

    @Test
    fun `parseTnpIndex count 2`() {
        val list = SdArchiveCodec.parseTnpIndex(SdFixtures.tnpIndex(tStart to 60, tNoon to 3600))
        assertEquals(listOf(IndexEntry(tStart, 60), IndexEntry(tNoon, 3600)), list)
    }

    @Test
    fun `parseTnpIndex truncated payload is empty`() {
        val p = SdFixtures.tnpIndex(tStart to 60, tNoon to 3600, countOverride = 3)
        assertEquals(emptyList<IndexEntry>(), SdArchiveCodec.parseTnpIndex(p))
    }

    @Test
    fun `parseTnpIndex 11 bytes is null`() {
        assertNull(SdArchiveCodec.parseTnpIndex(ByteArray(11)))
    }

    @Test
    fun `parseTnpIndex 472 entries in 5676 bytes`() {
        val entries = Array(472) { STimeDay(2026, 10, 1 + it % 28, 1 + it % 7, it % 24, it % 60, 0) to (it + 1) * 10 }
        val p = SdFixtures.tnpIndex(*entries)
        assertEquals(5676, p.size)
        val list = SdArchiveCodec.parseTnpIndex(p)!!
        assertEquals(472, list.size)
        assertEquals(IndexEntry(entries[471].first, entries[471].second), list[471])
    }

    @Test
    fun `parseTutkIndexPage valid`() {
        val p = SdFixtures.tutkPage(total = 3, index = 0, endFlag = false, entries = listOf(tStart to 60, tNoon to 65535))
        assertEquals(36, p.size)
        assertEquals(
            TutkIndexPage(3, 0, false, listOf(IndexEntry(tStart, 60), IndexEntry(tNoon, 65535))),
            SdArchiveCodec.parseTutkIndexPage(p),
        )
    }

    @Test
    fun `parseTutkIndexPage count mismatch rejected`() {
        val p = SdFixtures.tutkPage(3, 0, false, listOf(tStart to 60, tNoon to 60), countOverride = 3)
        assertNull(SdArchiveCodec.parseTutkIndexPage(p))
        assertNull(SdArchiveCodec.parseTutkIndexPage(ByteArray(11)))
    }

    @Test
    fun `parseTutkIndexPage total 0 page`() {
        val p = SdFixtures.tutkPage(total = 0, index = 0, endFlag = true, entries = emptyList())
        assertEquals(12, p.size)
        assertEquals(TutkIndexPage(0, 0, true, emptyList()), SdArchiveCodec.parseTutkIndexPage(p))
    }

    @Test
    fun `parseDeviceInfo tfstat 5 BE`() {
        val p = SdFixtures.deviceInfo(size = 344, tfstat = 5, hw = 0, isUtcAt9 = 1, total = 0, bigEndian = true)
        assertEquals(
            DeviceInfo(interfaceVersion = 0, tfstat = 5, hardwareVersion = 0, isUtc = 1, totalMb = 0, freeMb = 0),
            SdArchiveCodec.parseDeviceInfo(p, bigEndian = true),
        )
    }

    @Test
    fun `parseDeviceInfo hw 1 reads isUtc at 14`() {
        val v2 = SdFixtures.deviceInfo(hw = 1, isUtcAt9 = 0, isUtcAt14 = 1)
        assertEquals(1, SdArchiveCodec.parseDeviceInfo(v2, bigEndian = true)!!.isUtc)
        val v1 = SdFixtures.deviceInfo(hw = 0, isUtcAt9 = 0, isUtcAt14 = 1)
        assertEquals(0, SdArchiveCodec.parseDeviceInfo(v1, bigEndian = true)!!.isUtc)
    }

    @Test
    fun `parseDeviceInfo LE total 30000 free 1200`() {
        val p = SdFixtures.deviceInfo(interfaceVersion = 200, tfstat = 0, hw = 2, total = 30000, free = 1200, bigEndian = false)
        assertEquals(
            DeviceInfo(interfaceVersion = 200, tfstat = 0, hardwareVersion = 2, isUtc = 0, totalMb = 30000, freeMb = 1200),
            SdArchiveCodec.parseDeviceInfo(p, bigEndian = false),
        )
    }

    @Test
    fun `parseDeviceInfo 47 bytes is null`() {
        assertNull(SdArchiveCodec.parseDeviceInfo(ByteArray(47), bigEndian = true))
    }
}
