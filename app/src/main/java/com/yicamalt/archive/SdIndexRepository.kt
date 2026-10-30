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

import com.yicamalt.stream.LinkIoCtrlReply
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
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
    // START_CONTRACT: fetchStatus
    //   PURPOSE: Ask the camera for its device info and derive the SD card status.
    //   INPUTS: none
    //   OUTPUTS: { SdStatus - sdStatusOf(817); Unknown on refused send, timeout or unparsable reply }
    //   SIDE_EFFECTS: sends 816 over the port; one log line
    //   LINKS: M-SD-ARCHIVE, SdArchiveCodec.parseDeviceInfo, sdStatusOf
    // END_CONTRACT: fetchStatus
    suspend fun fetchStatus(): SdStatus {
        // START_BLOCK_SD_STATUS
        val started = clock()
        var info: DeviceInfo? = null
        exchange(SdArchiveCodec.DEVICE_INFO_REQ, SdArchiveCodec.deviceInfoRequest(), SdArchiveCodec.DEVICE_INFO_RESP) { ch ->
            val reply = withTimeoutOrNull(timeoutMs) { ch.receive() }
            if (reply != null) info = SdArchiveCodec.parseDeviceInfo(reply.payload, port.bigEndian)
        }
        val status = sdStatusOf(info)
        Timber.i(
            "[Archive][status][BLOCK_SD_STATUS] transport=%s tfstat=%s status=%s ms=%d",
            port.transport, info?.tfstat, status, clock() - started,
        )
        return status
        // END_BLOCK_SD_STATUS
    }

    // START_CONTRACT: fetchIndex
    //   PURPOSE: Fetch the recording index of the last INDEX_RANGE_MS and merge it into segments.
    //   INPUTS: { wallNowMs: Long - current wall time, end of the range }
    //   OUTPUTS: { IndexResult - Ok(segments); NotReady on refused send; Timeout when a reply / page does not arrive
    //              within timeoutMs; Malformed for a TNP reply under 12 B }
    //   SIDE_EFFECTS: sends 9031 (TNP, BE) or 792 (TUTK, LE) over the port; one log line
    //   LINKS: M-SD-ARCHIVE, SdArchiveCodec.parseTnpIndex, SdArchiveCodec.parseTutkIndexPage, SdTimeline.fromEntries
    // END_CONTRACT: fetchIndex
    suspend fun fetchIndex(wallNowMs: Long): IndexResult {
        // START_BLOCK_FETCH_INDEX
        val started = clock()
        val be = port.bigEndian
        val request = SdArchiveCodec.listEventsRequest(
            SdTime.toWire(wallNowMs - SdTimeline.INDEX_RANGE_MS, wireZone),
            SdTime.toWire(wallNowMs, wireZone),
            bigEndian = be,
        )
        var pages = 0
        var rejected = 0
        val result: IndexResult = if (be) {
            exchange(SdArchiveCodec.TNP_LIST_EVENTS_REQ, request, SdArchiveCodec.TNP_LIST_EVENTS_RESP) { ch ->
                val reply = withTimeoutOrNull(timeoutMs) { ch.receive() } ?: return@exchange IndexResult.Timeout
                pages = 1
                val entries = SdArchiveCodec.parseTnpIndex(reply.payload) ?: return@exchange IndexResult.Malformed
                IndexResult.Ok(SdTimeline.fromEntries(entries, wireZone))
            } ?: IndexResult.NotReady
        } else {
            exchange(SdArchiveCodec.TUTK_LIST_EVENTS_REQ, request, SdArchiveCodec.TUTK_LIST_EVENTS_RESP) { ch ->
                val acc = ArrayList<IndexEntry>()
                while (pages + rejected < MAX_PAGES) {
                    val reply = withTimeoutOrNull(timeoutMs) { ch.receive() } ?: return@exchange IndexResult.Timeout
                    val page = SdArchiveCodec.parseTutkIndexPage(reply.payload)
                    if (page == null) {
                        rejected++
                        continue
                    }
                    pages++
                    if (page.total == 0) return@exchange IndexResult.Ok(emptyList())
                    acc += page.entries
                    if (page.endFlag || acc.size >= page.total) break
                }
                IndexResult.Ok(SdTimeline.fromEntries(acc, wireZone))
            } ?: IndexResult.NotReady
        }
        Timber.i(
            "[Archive][index][BLOCK_FETCH_INDEX] transport=%s result=%s count=%d pages=%d rejected=%d ms=%d",
            port.transport, result, (result as? IndexResult.Ok)?.segments?.size ?: 0, pages, rejected, clock() - started,
        )
        return result
        // END_BLOCK_FETCH_INDEX
    }

    // Subscribe before send so a fast reply is never lost; null = the port refused the send.
    private suspend fun <T> exchange(
        type: Int,
        payload: ByteArray,
        want: Int,
        await: suspend (ReceiveChannel<LinkIoCtrlReply>) -> T,
    ): T? = coroutineScope {
        val ch = Channel<LinkIoCtrlReply>(Channel.UNLIMITED)
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            port.ioCtrlReplies.collect { if (it.type == want) ch.trySend(it) }
        }
        try {
            if (!port.sendIoCtrl(type, payload)) null else await(ch)
        } finally {
            job.cancel()
        }
    }

    private companion object {
        const val MAX_PAGES = 1000
    }
}
