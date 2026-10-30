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

// START_MODULE_MAP
//   STimeDay - 8-byte wire time: year, month, day, wday (Sunday = 1 .. Saturday = 7), hour, minute, second.
//   IndexEntry - one recorded run: start STimeDay + duration seconds.
//   TutkIndexPage - one accepted 793 page: total, index, endFlag, entries.
//   DeviceInfo - 817 fields used by the archive: interfaceVersion, tfstat, hardwareVersion, isUtc, totalMb, freeMb.
//   SdArchiveCodec - object: IOTYPE constants, deviceInfoRequest, listEventsRequest, tnpPlayRecord, playControl,
//     playControlType, readTimeDay, parseTnpIndex, parseTutkIndexPage, parseDeviceInfo.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 04-01: RED stub signatures.
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
    fun deviceInfoRequest(): ByteArray = error("RED stub")

    // START_CONTRACT: listEventsRequest
    //   PURPOSE: 9031 (TNP, BE) / 792 (TUTK, LE) index request payload.
    //   INPUTS: { start: STimeDay - range start; end: STimeDay - range end; bigEndian: Boolean - transport order }
    //   OUTPUTS: { ByteArray - 24 B: channel 0, start and end with second forced 0, event 0, status 0 }
    //   SIDE_EFFECTS: none
    //   LINKS: M-SD-ARCHIVE
    // END_CONTRACT: listEventsRequest
    fun listEventsRequest(start: STimeDay, end: STimeDay, bigEndian: Boolean): ByteArray = error("RED stub")

    // START_CONTRACT: tnpPlayRecord
    //   PURPOSE: 9030 TNP start record / seek payload.
    //   INPUTS: { useCount: Int - new generation (low byte sent); resolution: Int - live resolution code; at: STimeDay - target }
    //   OUTPUTS: { ByteArray - 12 B: useCount, resolution, 1, 0, STimeDay BE }
    //   SIDE_EFFECTS: none
    //   LINKS: M-SD-ARCHIVE
    // END_CONTRACT: tnpPlayRecord
    fun tnpPlayRecord(useCount: Int, resolution: Int, at: STimeDay): ByteArray = error("RED stub")

    // START_CONTRACT: playControl
    //   PURPOSE: 12718 / 794 play control payload (cmd 16 start/seek, cmd 1 stop).
    //   INPUTS: { command: Int - PLAY_START or PLAY_STOP; at: STimeDay? - target, null = zero time;
    //             useCount: Int - low byte sent; bigEndian: Boolean - transport order }
    //   OUTPUTS: { ByteArray - 24 B: channel 0, command, param 0, STimeDay or zeros, useCount, 3 zero bytes }
    //   SIDE_EFFECTS: none
    //   LINKS: M-SD-ARCHIVE
    // END_CONTRACT: playControl
    fun playControl(command: Int, at: STimeDay?, useCount: Int, bigEndian: Boolean): ByteArray = error("RED stub")

    // START_CONTRACT: playControlType
    //   PURPOSE: Pick the play control IOTYPE.
    //   INPUTS: { encrypted: Boolean - camera uses p2pEncrypt }
    //   OUTPUTS: { Int - 12718 if encrypted else 794 }
    //   SIDE_EFFECTS: none
    //   LINKS: M-SD-ARCHIVE
    // END_CONTRACT: playControlType
    fun playControlType(encrypted: Boolean): Int = error("RED stub")

    // START_CONTRACT: readTimeDay
    //   PURPOSE: Read an 8-byte STimeDay.
    //   INPUTS: { p: ByteArray - payload; offset: Int - start; bigEndian: Boolean - year byte order }
    //   OUTPUTS: { STimeDay - all fields unsigned }
    //   SIDE_EFFECTS: none
    //   LINKS: M-SD-ARCHIVE
    // END_CONTRACT: readTimeDay
    fun readTimeDay(p: ByteArray, offset: Int, bigEndian: Boolean): STimeDay = error("RED stub")

    // START_CONTRACT: parseTnpIndex
    //   PURPOSE: Parse a 9032 reply payload (BE).
    //   INPUTS: { p: ByteArray - payload }
    //   OUTPUTS: { List<IndexEntry>? - null when shorter than 12 B; empty when truncated (vendor rule) }
    //   SIDE_EFFECTS: none
    //   LINKS: M-SD-ARCHIVE, SdTime.fromWire
    // END_CONTRACT: parseTnpIndex
    fun parseTnpIndex(p: ByteArray): List<IndexEntry>? = error("RED stub")

    // START_CONTRACT: parseTutkIndexPage
    //   PURPOSE: Parse one 793 reply page (LE).
    //   INPUTS: { p: ByteArray - payload }
    //   OUTPUTS: { TutkIndexPage? - null unless size >= 12 and size / 12 - 1 == count }
    //   SIDE_EFFECTS: none
    //   LINKS: M-SD-ARCHIVE
    // END_CONTRACT: parseTutkIndexPage
    fun parseTutkIndexPage(p: ByteArray): TutkIndexPage? = error("RED stub")

    // START_CONTRACT: parseDeviceInfo
    //   PURPOSE: Parse the archive fields of an 817 reply.
    //   INPUTS: { p: ByteArray - payload; bigEndian: Boolean - transport order for total / free }
    //   OUTPUTS: { DeviceInfo? - null when shorter than 48 B }
    //   SIDE_EFFECTS: none
    //   LINKS: M-SD-ARCHIVE
    // END_CONTRACT: parseDeviceInfo
    fun parseDeviceInfo(p: ByteArray, bigEndian: Boolean): DeviceInfo? = error("RED stub")
}
