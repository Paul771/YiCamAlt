// FILE: SdStatusTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Executable contract for the SD card status rule over an 817 reply.
//   SCOPE: sdStatusOf: no reply, no card, abnormal tfstat codes, zero capacity, ok; ArchiveSupport transports.
//   DEPENDS: M-SD-ARCHIVE (sdStatusOf, SdStatus, DeviceInfo, ArchiveSupport)
//   LINKS: V-M-SD-ARCHIVE
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.archive

import com.yicamalt.camera.TransportType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// START_MODULE_MAP
//   info(tfstat, total) - DeviceInfo with only the archive-relevant fields set.
//   SdStatusTest - tfstat mapping and capacity rule.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 04-03: research Pattern 6 status rule.
// END_CHANGE_SUMMARY

class SdStatusTest {

    private fun info(tfstat: Int, total: Int) = DeviceInfo(0, tfstat, 0, 0, total, 0)

    @Test
    fun `no reply is Unknown`() {
        assertEquals(SdStatus.Unknown, sdStatusOf(null))
    }

    @Test
    fun `tfstat 5 is NoCard`() {
        assertEquals(SdStatus.NoCard, sdStatusOf(info(5, 30_000)))
    }

    @Test
    fun `tfstat 2 3 8 9 are Abnormal with the code`() {
        for (code in listOf(2, 3, 8, 9)) {
            assertEquals(SdStatus.Abnormal(code), sdStatusOf(info(code, 30_000)))
        }
    }

    @Test
    fun `tfstat 0 with total 0 is Abnormal 0`() {
        assertEquals(SdStatus.Abnormal(0), sdStatusOf(info(0, 0)))
    }

    @Test
    fun `tfstat 0 with capacity is Ok`() {
        assertEquals(SdStatus.Ok, sdStatusOf(info(0, 30_000)))
    }

    @Test
    fun `tfstat 1 with capacity is Ok`() {
        assertEquals(SdStatus.Ok, sdStatusOf(info(1, 100)))
    }

    @Test
    fun `archive supports TNP and TUTK only`() {
        assertTrue(ArchiveSupport.supports(TransportType.TNP))
        assertTrue(ArchiveSupport.supports(TransportType.TUTK))
        assertFalse(ArchiveSupport.supports(TransportType.LANGTAO))
        assertFalse(ArchiveSupport.supports(TransportType.UNKNOWN))
        assertFalse(ArchiveSupport.supports(null))
    }
}
