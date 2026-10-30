// FILE: SdIndexRepository.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Fetch the SD card status (816 / 817) and the recording index (9031 / 9032 TNP, 792 / 793 TUTK) over
//            ArchiveLinkPort as typed results.
//   SCOPE: subscribe-before-send reply wait, per-reply timeout, TUTK paging (endflag or total), D-07 error results
//          (Timeout, Malformed, NotReady; status Unknown). Value-light logs.
//   DEPENDS: SdTypes (ArchiveLinkPort, SdStatus, IndexResult), SdArchiveCodec, SdTime, SdTimeline
//   LINKS: M-SD-ARCHIVE, V-M-SD-ARCHIVE
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.archive

import java.time.ZoneId

// START_MODULE_MAP
//   SdIndexRepository - fetchStatus (816 -> 817 -> sdStatusOf), fetchIndex (30-day range -> segments).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 04-03: research Patterns 3 / 6.
// END_CHANGE_SUMMARY

class SdIndexRepository(
    private val port: ArchiveLinkPort,
    private val wireZone: ZoneId = SdTime.wireZone(),
    private val timeoutMs: Long = 10_000,
    private val clock: () -> Long = System::currentTimeMillis, // only for the `ms` log field
) {
    suspend fun fetchStatus(): SdStatus = error("RED stub")

    suspend fun fetchIndex(wallNowMs: Long): IndexResult = error("RED stub")
}
