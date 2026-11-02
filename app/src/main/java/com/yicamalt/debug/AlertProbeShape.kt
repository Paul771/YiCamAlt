// FILE: AlertProbeShape.kt
// VERSION: 0.0.1
// START_MODULE_CONTRACT
//   PURPOSE: Value-free shape analyzer and line formatter for the debug alert probe.
//   SCOPE: analyzeList, formatList, formatMedia. Pure; no I/O, no logging.
//   DEPENDS: M-EVENT-PLAYBACK (MediaCrypto classes)
//   LINKS: M-DEBUG-PROBE, V-M-DEBUG-PROBE, SEC-01
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.debug

import com.yicamalt.event.MediaCrypto

// START_MODULE_MAP
//   MediaCandidate - in-memory media URLs and keys of one event (RED stub).
//   ListShape - counters of one list response (RED stub).
//   AlertProbeShape - analyzeList, formatList, formatMedia (RED stub).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.0.1 - GSD plan 05-00: RED stub for the test-first cycle.
// END_CHANGE_SUMMARY

internal class MediaCandidate(
    val picUrl: String,
    val picPwd: String,
    val videoUrl: String,
    val videoPwd: String,
    val fileType: String,
) {
    val picExt: String get() = error("RED stub")
}

internal class ListShape(val code: String, val candidate: MediaCandidate?)

internal object AlertProbeShape {
    fun analyzeList(body: String?, nowMs: Long, cameras: List<Pair<String, Boolean>>): ListShape = error("RED stub")
    fun formatList(shape: ListShape): String = error("RED stub")
    fun formatMedia(
        kind: String,
        http: Int?,
        bytes: Long?,
        decryptOk: Boolean?,
        magic: MediaCrypto.Magic?,
        codec: MediaCrypto.Codec?,
        tarEntry: Boolean?,
        error: String? = null,
    ): String = kotlin.error("RED stub")
}
