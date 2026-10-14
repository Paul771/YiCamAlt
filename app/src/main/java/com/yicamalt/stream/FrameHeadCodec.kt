// FILE: FrameHeadCodec.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Parse the 24-byte media frame head into FrameInfo (TNP big-endian, TUTK little-endian).
//   SCOPE: field offsets, unsigned u16/u32 reads, bounds check.
//   DEPENDS: StreamTypes (FrameInfo)
//   LINKS: M-STREAM-CORE, V-M-STREAM-CORE
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.stream

import java.nio.ByteBuffer
import java.nio.ByteOrder

// START_MODULE_MAP
//   FrameHeadCodec - object: SIZE = 24, parse(bytes, offset, bigEndian) -> FrameInfo.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 02-04 from 02-RESEARCH.md Pattern 4 (VERIFIED-LOCAL layouts).
//     Layout: [0..2) codec [2] flags [3] liveFlag [4] onlineNum [5] useCount [6..8) seq [8..10) width
//     [10..12) height [12..16) ts s [16] isDay [17] cover [18] outLoss [19] inLoss [20..24) ts ms.
//     INVARIANT: u16/u32 fields are read unsigned (u32 into Long).
// END_CHANGE_SUMMARY

object FrameHeadCodec {
    const val SIZE = 24

    // START_CONTRACT: parse
    //   PURPOSE: Decode one frame head starting at offset.
    //   INPUTS: { bytes: ByteArray, offset: Int - start of the head, bigEndian: Boolean - true for TNP, false for TUTK }
    //   OUTPUTS: { FrameInfo }
    //   SIDE_EFFECTS: none; throws IllegalArgumentException when fewer than 24 bytes are available
    //   LINKS: M-STREAM-CORE, TnpCodec.splitFrameUnit
    // END_CONTRACT: parse
    fun parse(bytes: ByteArray, offset: Int = 0, bigEndian: Boolean): FrameInfo {
        // START_BLOCK_PARSE_FRAME_HEAD
        require(offset >= 0 && bytes.size - offset >= SIZE) { "frame head needs $SIZE bytes" }
        val bb = ByteBuffer.wrap(bytes, offset, SIZE)
            .order(if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        fun u8(i: Int) = bytes[offset + i].toInt() and 0xFF
        fun u16(i: Int) = bb.getShort(offset + i).toInt() and 0xFFFF
        fun u32(i: Int) = bb.getInt(offset + i).toLong() and 0xFFFF_FFFFL
        return FrameInfo(
            codecId = u16(0),
            flags = u8(2),
            liveFlag = u8(3),
            onlineNum = u8(4),
            useCount = u8(5),
            seq = u16(6),
            width = u16(8),
            height = u16(10),
            timestampSec = u32(12),
            isDay = u8(16),
            cover = u8(17),
            outLoss = u8(18),
            inLoss = u8(19),
            timestampMs = u32(20),
        )
        // END_BLOCK_PARSE_FRAME_HEAD
    }
}
