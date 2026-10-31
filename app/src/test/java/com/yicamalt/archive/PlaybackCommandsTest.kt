// FILE: PlaybackCommandsTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Executable contract for the per-transport seek / pause / live command lists.
//   SCOPE: TNP 9030 seek, TUTK 12718 / 794 seek, pause (767 + stop record), live (stop record + 9029 / 8190 / 511),
//          PlaybackCmd equality and redacted toString.
//   DEPENDS: M-SD-ARCHIVE (PlaybackCommands, PlaybackCmd, SdArchiveCodec), M-STREAM-CORE (IoCtrlMessages)
//   LINKS: V-M-SD-ARCHIVE
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.archive

import com.yicamalt.camera.TransportType
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

// START_MODULE_MAP
//   at - synthetic STimeDay target.
//   stopBe / stopLe - expected play control stop payloads.
//   PlaybackCommandsTest - byte-exact command lists per transport and p2pEncrypt.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 04-03: research §P4-P6 command sequences.
// END_CHANGE_SUMMARY

class PlaybackCommandsTest {

    private val at = STimeDay(2026, 10, 30, 6, 12, 34, 56)
    private val stopBe = SdArchiveCodec.playControl(1, null, 0, bigEndian = true)
    private val stopLe = SdArchiveCodec.playControl(1, null, 0, bigEndian = false)

    @Test
    fun `TNP seek = one 9030`() {
        assertEquals(
            listOf(PlaybackCmd(9030, SdArchiveCodec.tnpPlayRecord(7, 1, at))),
            PlaybackCommands.seek(TransportType.TNP, encrypted = true, useCount = 7, resolution = 1, at = at),
        )
    }

    @Test
    fun `TUTK seek encrypted = 12718 cmd 16 LE`() {
        val cmds = PlaybackCommands.seek(TransportType.TUTK, encrypted = true, useCount = 7, resolution = 1, at = at)
        assertEquals(listOf(PlaybackCmd(12718, SdArchiveCodec.playControl(16, at, 7, bigEndian = false))), cmds)
        // Spot-check the little-endian layout independently of the builder: command u32 LE at offset 4, year LE at 12.
        val p = cmds[0].payload
        assertArrayEquals(byteArrayOf(16, 0, 0, 0), p.copyOfRange(4, 8))
        assertArrayEquals(byteArrayOf(0xEA.toByte(), 0x07), p.copyOfRange(12, 14))
        assertEquals(7, p[20].toInt())
    }

    @Test
    fun `TUTK seek unencrypted = 794`() {
        assertEquals(
            listOf(PlaybackCmd(794, SdArchiveCodec.playControl(16, at, 7, bigEndian = false))),
            PlaybackCommands.seek(TransportType.TUTK, encrypted = false, useCount = 7, resolution = 1, at = at),
        )
    }

    @Test
    fun `pause = 767 then stop record TNP encrypted`() {
        assertEquals(
            listOf(PlaybackCmd(767, ByteArray(8)), PlaybackCmd(12718, stopBe)),
            PlaybackCommands.pause(TransportType.TNP, encrypted = true),
        )
    }

    @Test
    fun `pause = 767 then stop record TUTK unencrypted`() {
        assertEquals(
            listOf(PlaybackCmd(767, ByteArray(8)), PlaybackCmd(794, stopLe)),
            PlaybackCommands.pause(TransportType.TUTK, encrypted = false),
        )
    }

    @Test
    fun `live TNP = stop record then 9029`() {
        assertEquals(
            listOf(PlaybackCmd(12718, stopBe), PlaybackCmd(9029, byteArrayOf(9, 2, 1, 0))),
            PlaybackCommands.live(TransportType.TNP, encrypted = true, useCount = 9, resolution = 2),
        )
    }

    @Test
    fun `live TUTK encrypted = stop record then 8190`() {
        assertEquals(
            listOf(PlaybackCmd(12718, stopLe), PlaybackCmd(8190, byteArrayOf(9, 0, 0, 0))),
            PlaybackCommands.live(TransportType.TUTK, encrypted = true, useCount = 9, resolution = 2),
        )
    }

    @Test
    fun `live TUTK unencrypted = stop record then 511`() {
        assertEquals(
            listOf(PlaybackCmd(794, stopLe), PlaybackCmd(511, byteArrayOf(9, 0, 0, 0))),
            PlaybackCommands.live(TransportType.TUTK, encrypted = false, useCount = 9, resolution = 2),
        )
    }

    @Test
    fun `stop record byte order differs per transport`() {
        assertNotEquals(PlaybackCmd(12718, stopBe), PlaybackCmd(12718, stopLe))
        assertArrayEquals(byteArrayOf(0, 0, 0, 1), stopBe.copyOfRange(4, 8))
        assertArrayEquals(byteArrayOf(1, 0, 0, 0), stopLe.copyOfRange(4, 8))
    }

    @Test
    fun `PlaybackCmd toString has no bytes`() {
        assertEquals("PlaybackCmd(type=9030, bytes=12)", PlaybackCmd(9030, SdArchiveCodec.tnpPlayRecord(7, 1, at)).toString())
    }
}
