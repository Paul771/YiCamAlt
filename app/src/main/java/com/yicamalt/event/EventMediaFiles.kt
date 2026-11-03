// FILE: EventMediaFiles.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: File layout of event previews and clips: hashed names, atomic writes, purge, clip LRU, wipe.
//   SCOPE: EventMediaFiles.
//   DEPENDS: none
//   LINKS: M-EVENT-PLAYBACK, EVT-03, EVT-05
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.event

import java.io.File
import java.time.ZoneId

// START_MODULE_MAP
//   EventMediaFiles - thumbRelPath, thumbFile, clipFile, writeAtomic, purgeThumbsOlderThan, trimClips, wipeAll.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 05-03: created.
// END_CHANGE_SUMMARY

class EventMediaFiles(
    private val filesDir: File,
    private val cacheDir: File,
    private val zone: () -> ZoneId,
) {
    fun thumbRelPath(key: String, timeMs: Long): String = error("RED stub")
    fun thumbFile(rel: String): File? = error("RED stub")
    fun clipFile(key: String): File = error("RED stub")
    fun writeAtomic(file: File, bytes: ByteArray): Boolean = error("RED stub")
    fun purgeThumbsOlderThan(cutoffMs: Long, paths: List<String>): Int = error("RED stub")
    fun trimClips(maxBytes: Long = CLIP_CACHE_MAX_BYTES): Int = error("RED stub")
    fun wipeAll(): Unit = error("RED stub")

    companion object {
        const val CLIP_CACHE_MAX_BYTES = 50L * 1024 * 1024
    }
}
