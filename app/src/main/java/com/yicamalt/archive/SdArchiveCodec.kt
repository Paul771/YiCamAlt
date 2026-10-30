// FILE: SdArchiveCodec.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Byte layer of the SD card archive: STimeDay, archive IOCTRL request builders and reply parsers.
//   SCOPE: 816 device info request / 817 parse, 9031 / 792 index request, 9032 (TNP) and 793 (TUTK page) parse,
//          9030 TNP play record, 12718 / 794 play control (cmd 16 start/seek, cmd 1 stop); transport byte order
//          (TNP = big-endian, TUTK = little-endian). Pure, no logging.
//   DEPENDS: none
//   LINKS: M-SD-ARCHIVE, V-M-SD-ARCHIVE
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.archive

import java.nio.ByteBuffer
import java.nio.ByteOrder

// START_MODULE_MAP
//   STimeDay - 8-byte wire time: year, month, day, wday (Sunday = 1 .. Saturday = 7), hour, minute, second.
//   IndexEntry - one recorded run: start STimeDay + duration seconds.
//   TutkIndexPage - one accepted 793 page: total, index, endFlag, entries.
//   DeviceInfo - 817 fields used by the archive: interfaceVersion, tfstat, hardwareVersion, isUtc, totalMb, freeMb.
//   SdArchiveCodec - object: IOTYPE constants, deviceInfoRequest, listEventsRequest, tnpPlayRecord, playControl,
//     playControlType, readTimeDay, parseTnpIndex, parseTutkIndexPage, parseDeviceInfo.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 04-01: byte layer from 04-RESEARCH §P0-P5 (vendor-verified layouts; public trace
//     9032 = 12 + 472 x 12).
// END_CHANGE_SUMMARY

data class STimeDay(
    val year: Int,
    val month: Int,
    val day: Int,
    val wday: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
)

data class IndexEntry(val start: STimeDay, val durationS: Int)

data class TutkIndexPage(val total: Int, val index: Int, val endFlag: Boolean, val entries: List<IndexEntry>)

data class DeviceInfo(
    val interfaceVersion: Int,
    val tfstat: Int,
    val hardwareVersion: Int,
    val isUtc: Int,
    val totalMb: Int,
    val freeMb: Int,
)

object SdArchiveCodec {
    const val DEVICE_INFO_REQ = 816
    const val DEVICE_INFO_RESP = 817
    const val TNP_START_RECORD = 9030
    const val TNP_LIST_EVENTS_REQ = 9031
    const val TNP_LIST_EVENTS_RESP = 9032
    const val TUTK_LIST_EVENTS_REQ = 792
    const val TUTK_LIST_EVENTS_RESP = 793
    const val RECORD_PLAYCONTROL = 794
    const val RECORD_PLAYCONTROL2 = 12718
    const val PLAY_START = 16
    const val PLAY_STOP = 1

    // START_CONTRACT: deviceInfoRequest
    //   PURPOSE: 816 payload.
    //   INPUTS: none
    //   OUTPUTS: { ByteArray - 4 zero bytes }
    //   SIDE_EFFECTS: none
    //   LINKS: M-SD-ARCHIVE
    // END_CONTRACT: deviceInfoRequest
    fun deviceInfoRequest(): ByteArray = ByteArray(4)

    // START_CONTRACT: listEventsRequest
    //   PURPOSE: 9031 (TNP, BE) / 792 (TUTK, LE) index request payload.
    //   INPUTS: { start: STimeDay - range start; end: STimeDay - range end; bigEndian: Boolean - transport order }
    //   OUTPUTS: { ByteArray - 24 B: channel 0, start and end with second forced 0, event 0, status 0 }
    //   SIDE_EFFECTS: none
    //   LINKS: M-SD-ARCHIVE
    // END_CONTRACT: listEventsRequest
    fun listEventsRequest(start: STimeDay, end: STimeDay, bigEndian: Boolean): ByteArray {
        // START_BLOCK_LIST_EVENTS_REQUEST
        val buf = ByteBuffer.allocate(24).order(order(bigEndian))
        buf.putInt(0) // channel
        putTime(buf, start.copy(second = 0))
        putTime(buf, end.copy(second = 0))
        // [20] event 0 (all), [21] status 0, [22..24) reserved: already zero
        return buf.array()
        // END_BLOCK_LIST_EVENTS_REQUEST
    }

    // START_CONTRACT: tnpPlayRecord
    //   PURPOSE: 9030 TNP start record / seek payload.
    //   INPUTS: { useCount: Int - new generation (low byte sent); resolution: Int - live resolution code; at: STimeDay - target }
    //   OUTPUTS: { ByteArray - 12 B: useCount, resolution, 1, 0, STimeDay BE }
    //   SIDE_EFFECTS: none
    //   LINKS: M-SD-ARCHIVE
    // END_CONTRACT: tnpPlayRecord
    fun tnpPlayRecord(useCount: Int, resolution: Int, at: STimeDay): ByteArray {
        val buf = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        buf.put(useCount.toByte())
        buf.put(resolution.toByte())
        buf.put(1) // version
        buf.put(0)
        putTime(buf, at)
        return buf.array()
    }

    // START_CONTRACT: playControl
    //   PURPOSE: 12718 / 794 play control payload (cmd 16 start/seek, cmd 1 stop).
    //   INPUTS: { command: Int - PLAY_START or PLAY_STOP; at: STimeDay? - target, null = zero time;
    //             useCount: Int - low byte sent; bigEndian: Boolean - transport order }
    //   OUTPUTS: { ByteArray - 24 B: channel 0, command, param 0, STimeDay or zeros, useCount, 3 zero bytes }
    //   SIDE_EFFECTS: none
    //   LINKS: M-SD-ARCHIVE
    // END_CONTRACT: playControl
    fun playControl(command: Int, at: STimeDay?, useCount: Int, bigEndian: Boolean): ByteArray {
        val buf = ByteBuffer.allocate(24).order(order(bigEndian))
        buf.putInt(0) // channel
        buf.putInt(command)
        buf.putInt(0) // param
        if (at != null) putTime(buf, at) else buf.put(ByteArray(8))
        buf.put(useCount.toByte())
        // [21..24) reserved: already zero
        return buf.array()
    }

    // START_CONTRACT: playControlType
    //   PURPOSE: Pick the play control IOTYPE.
    //   INPUTS: { encrypted: Boolean - camera uses p2pEncrypt }
    //   OUTPUTS: { Int - 12718 if encrypted else 794 }
    //   SIDE_EFFECTS: none
    //   LINKS: M-SD-ARCHIVE
    // END_CONTRACT: playControlType
    fun playControlType(encrypted: Boolean): Int = if (encrypted) RECORD_PLAYCONTROL2 else RECORD_PLAYCONTROL

    // START_CONTRACT: readTimeDay
    //   PURPOSE: Read an 8-byte STimeDay.
    //   INPUTS: { p: ByteArray - payload; offset: Int - start; bigEndian: Boolean - year byte order }
    //   OUTPUTS: { STimeDay - all fields unsigned }
    //   SIDE_EFFECTS: none
    //   LINKS: M-SD-ARCHIVE
    // END_CONTRACT: readTimeDay
    fun readTimeDay(p: ByteArray, offset: Int, bigEndian: Boolean): STimeDay {
        val buf = ByteBuffer.wrap(p).order(order(bigEndian))
        return STimeDay(
            year = buf.getShort(offset).toInt() and 0xFFFF,
            month = p[offset + 2].toInt() and 0xFF,
            day = p[offset + 3].toInt() and 0xFF,
            wday = p[offset + 4].toInt() and 0xFF,
            hour = p[offset + 5].toInt() and 0xFF,
            minute = p[offset + 6].toInt() and 0xFF,
            second = p[offset + 7].toInt() and 0xFF,
        )
    }

    // START_CONTRACT: parseTnpIndex
    //   PURPOSE: Parse a 9032 reply payload (BE).
    //   INPUTS: { p: ByteArray - payload }
    //   OUTPUTS: { List<IndexEntry>? - null when shorter than 12 B; empty when truncated (vendor rule) }
    //   SIDE_EFFECTS: none
    //   LINKS: M-SD-ARCHIVE, SdTime.fromWire
    // END_CONTRACT: parseTnpIndex
    fun parseTnpIndex(p: ByteArray): List<IndexEntry>? {
        // START_BLOCK_PARSE_TNP_INDEX
        if (p.size < HEAD_SIZE) return null
        val buf = ByteBuffer.wrap(p).order(ByteOrder.BIG_ENDIAN)
        val count = buf.getShort(0).toInt() and 0xFFFF
        // Vendor rule: a payload shorter than the announced count is treated as zero entries.
        if (p.size < HEAD_SIZE + count * ENTRY_SIZE) return emptyList()
        return List(count) { i ->
            val at = HEAD_SIZE + ENTRY_SIZE * i
            IndexEntry(readTimeDay(p, at, bigEndian = true), buf.getInt(at + 8))
        }
        // END_BLOCK_PARSE_TNP_INDEX
    }

    // START_CONTRACT: parseTutkIndexPage
    //   PURPOSE: Parse one 793 reply page (LE).
    //   INPUTS: { p: ByteArray - payload }
    //   OUTPUTS: { TutkIndexPage? - null unless size >= 12 and size / 12 - 1 == count }
    //   SIDE_EFFECTS: none
    //   LINKS: M-SD-ARCHIVE
    // END_CONTRACT: parseTutkIndexPage
    fun parseTutkIndexPage(p: ByteArray): TutkIndexPage? {
        // START_BLOCK_PARSE_TUTK_PAGE
        if (p.size < HEAD_SIZE) return null
        val count = p[10].toInt() and 0xFF
        // Vendor rule: accept a page only when size / 12 - 1 == count.
        if (p.size / ENTRY_SIZE - 1 != count) return null
        val buf = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
        val entries = List(count) { i ->
            val at = HEAD_SIZE + ENTRY_SIZE * i
            // [8] event, [9] status are not used by the archive; duration is u16 on TUTK.
            IndexEntry(readTimeDay(p, at, bigEndian = false), buf.getShort(at + 10).toInt() and 0xFFFF)
        }
        return TutkIndexPage(
            total = buf.getInt(4),
            index = p[8].toInt() and 0xFF,
            endFlag = (p[9].toInt() and 0xFF) == 1,
            entries = entries,
        )
        // END_BLOCK_PARSE_TUTK_PAGE
    }

    // START_CONTRACT: parseDeviceInfo
    //   PURPOSE: Parse the archive fields of an 817 reply.
    //   INPUTS: { p: ByteArray - payload; bigEndian: Boolean - transport order for total / free }
    //   OUTPUTS: { DeviceInfo? - null when shorter than 48 B }
    //   SIDE_EFFECTS: none
    //   LINKS: M-SD-ARCHIVE
    // END_CONTRACT: parseDeviceInfo
    fun parseDeviceInfo(p: ByteArray, bigEndian: Boolean): DeviceInfo? {
        // START_BLOCK_PARSE_DEVICE_INFO
        if (p.size < DEVICE_INFO_MIN_SIZE) return null
        val buf = ByteBuffer.wrap(p).order(order(bigEndian))
        val hw = p[8].toInt() and 0xFF
        return DeviceInfo(
            interfaceVersion = p[0].toInt() and 0xFF,
            tfstat = p[2].toInt() and 0xFF,
            hardwareVersion = hw,
            isUtc = p[if (hw == 1) 14 else 9].toInt() and 0xFF,
            totalMb = buf.getInt(40),
            freeMb = buf.getInt(44),
        )
        // END_BLOCK_PARSE_DEVICE_INFO
    }

    private const val HEAD_SIZE = 12
    private const val ENTRY_SIZE = 12
    private const val DEVICE_INFO_MIN_SIZE = 48

    private fun order(bigEndian: Boolean): ByteOrder = if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN

    private fun putTime(buf: ByteBuffer, t: STimeDay) {
        buf.putShort(t.year.toShort())
        buf.put(t.month.toByte())
        buf.put(t.day.toByte())
        buf.put(t.wday.toByte())
        buf.put(t.hour.toByte())
        buf.put(t.minute.toByte())
        buf.put(t.second.toByte())
    }
}
