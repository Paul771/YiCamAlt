// FILE: SdFixtures.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Synthetic byte fixtures for the SD archive replies (9032, 793, 817) built independently of the codec.
//   SCOPE: TNP index payload, TUTK index page, device info payload, ISO-8601 UTC instant helper. Synthetic dates only.
//   DEPENDS: M-SD-ARCHIVE (STimeDay)
//   LINKS: V-M-SD-ARCHIVE
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.archive

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant

// START_MODULE_MAP
//   SdFixtures.utc(iso) - epoch ms of an ISO-8601 instant.
//   SdFixtures.putTime(buf, t) - writes an 8-byte STimeDay in the buffer's order.
//   SdFixtures.tnpIndex(entries, countOverride) - 9032 payload (BE).
//   SdFixtures.tutkPage(total, index, endFlag, entries, countOverride) - 793 page (LE).
//   SdFixtures.deviceInfo(...) - 817 payload with the archive offsets set.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 04-01: fixtures for codec tests.
// END_CHANGE_SUMMARY

object SdFixtures {

    fun utc(iso: String): Long = Instant.parse(iso).toEpochMilli()

    fun putTime(buf: ByteBuffer, t: STimeDay) {
        buf.putShort(t.year.toShort())
        buf.put(t.month.toByte())
        buf.put(t.day.toByte())
        buf.put(t.wday.toByte())
        buf.put(t.hour.toByte())
        buf.put(t.minute.toByte())
        buf.put(t.second.toByte())
    }

    fun tnpIndex(vararg entries: Pair<STimeDay, Int>, countOverride: Int? = null): ByteArray {
        val buf = ByteBuffer.allocate(12 + entries.size * 12).order(ByteOrder.BIG_ENDIAN)
        buf.putShort((countOverride ?: entries.size).toShort())
        buf.put(ByteArray(10))
        for ((t, d) in entries) {
            putTime(buf, t)
            buf.putInt(d)
        }
        return buf.array()
    }

    fun tutkPage(
        total: Int,
        index: Int,
        endFlag: Boolean,
        entries: List<Pair<STimeDay, Int>>,
        countOverride: Int? = null,
    ): ByteArray {
        val buf = ByteBuffer.allocate(12 + entries.size * 12).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0)
        buf.putInt(total)
        buf.put(index.toByte())
        buf.put((if (endFlag) 1 else 0).toByte())
        buf.put((countOverride ?: entries.size).toByte())
        buf.put(0)
        for ((t, d) in entries) {
            putTime(buf, t)
            buf.put(0) // event
            buf.put(0) // status
            buf.putShort(d.toShort())
        }
        return buf.array()
    }

    fun deviceInfo(
        size: Int = 344,
        tfstat: Int = 0,
        hw: Int = 0,
        isUtcAt9: Int = 0,
        isUtcAt14: Int = 0,
        total: Int = 0,
        free: Int = 0,
        bigEndian: Boolean = true,
        interfaceVersion: Int = 0,
    ): ByteArray {
        val p = ByteArray(size)
        val buf = ByteBuffer.wrap(p).order(if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        if (size > 0) p[0] = interfaceVersion.toByte()
        if (size > 2) p[2] = tfstat.toByte()
        if (size > 8) p[8] = hw.toByte()
        if (size > 9) p[9] = isUtcAt9.toByte()
        if (size > 14) p[14] = isUtcAt14.toByte()
        if (size >= 44) buf.putInt(40, total)
        if (size >= 48) buf.putInt(44, free)
        return p
    }
}
