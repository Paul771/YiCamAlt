// FILE: EventMediaFiles.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: File layout of event previews and clips: hashed names, atomic writes, purge, clip LRU, wipe.
//   SCOPE: EventMediaFiles - previews in filesDir/event_thumbs/<yyyyMMdd>/<hash>.jpg (relative path stored in
//     Room), clips in cacheDir/event_clips/<hash>.mp4 (LRU about 50 MB).
//   DEPENDS: none
//   LINKS: M-EVENT-PLAYBACK, V-M-EVENT-PLAYBACK, EVT-03, EVT-05
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.event

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// START_MODULE_MAP
//   EventMediaFiles - thumbRelPath, thumbFile, clipFile, writeAtomic, purgeThumbsOlderThan, trimClips, wipeAll.
//   EventMediaFiles.CLIP_CACHE_MAX_BYTES - clip LRU budget (50 MB).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 05-03: created. Names are SHA-256(event key), first 32 hex chars: no id, uid
//     or URL in any path. Writes go to a .tmp sibling and are moved into place.
//   HAZARD: thumbFile only resolves paths inside event_thumbs (the relative path comes from Room).
// END_CHANGE_SUMMARY

class EventMediaFiles(
    private val filesDir: File,
    private val cacheDir: File,
    private val zone: () -> ZoneId,
) {
    private val thumbsRoot get() = File(filesDir, THUMBS_DIR)
    private val clipsRoot get() = File(cacheDir, CLIPS_DIR)

    // START_CONTRACT: thumbRelPath
    //   PURPOSE: Relative preview path of an event: event_thumbs/<yyyyMMdd local day>/<hash>.jpg.
    //   INPUTS: { key: String - event key; timeMs: Long - event time }
    //   OUTPUTS: { String - relative to filesDir, '/' separated }
    //   SIDE_EFFECTS: none
    // END_CONTRACT: thumbRelPath
    fun thumbRelPath(key: String, timeMs: Long): String {
        val day = DAY.withZone(zone()).format(Instant.ofEpochMilli(timeMs))
        return "$THUMBS_DIR/$day/${hash(key)}.jpg"
    }

    /** File of a relative preview path, or null when the path leaves event_thumbs. */
    fun thumbFile(rel: String): File? {
        if (rel.startsWith("/") || rel.startsWith("\\") || !rel.startsWith("$THUMBS_DIR/")) return null
        if (rel.split('/', '\\').any { it == ".." || it == "." || it.isEmpty() }) return null
        return File(filesDir, rel)
    }

    fun clipFile(key: String): File = File(clipsRoot, "${hash(key)}.mp4")

    // START_CONTRACT: writeAtomic
    //   PURPOSE: Write [bytes] to [file] through a .tmp sibling and a replacing move.
    //   INPUTS: { file: File; bytes: ByteArray }
    //   OUTPUTS: { Boolean - true when the file is in place }
    //   SIDE_EFFECTS: creates parent folders; removes the .tmp on failure
    // END_CONTRACT: writeAtomic
    fun writeAtomic(file: File, bytes: ByteArray): Boolean {
        // START_BLOCK_WRITE_ATOMIC
        val tmp = File(file.parentFile, file.name + TMP_SUFFIX)
        return try {
            file.parentFile?.mkdirs()
            tmp.writeBytes(bytes)
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (e: IOException) {
            tmp.delete()
            false
        }
        // END_BLOCK_WRITE_ATOMIC
    }

    // START_CONTRACT: purgeThumbsOlderThan
    //   PURPOSE: Delete previews of purged rows plus day folders older than the cutoff day, then empty day folders.
    //   INPUTS: { cutoffMs: Long - retention cutoff; paths: List<String> - thumb_path values of rows older than it }
    //   OUTPUTS: { Int - number of preview files deleted }
    //   SIDE_EFFECTS: file deletes under filesDir/event_thumbs
    // END_CONTRACT: purgeThumbsOlderThan
    fun purgeThumbsOlderThan(cutoffMs: Long, paths: List<String>): Int {
        // START_BLOCK_PURGE_THUMBS
        var deleted = 0
        paths.forEach { rel -> if (thumbFile(rel)?.delete() == true) deleted++ }
        // A day folder strictly before the cutoff day holds only expired previews (orphans of a crash).
        val cutoffDay = DAY.withZone(zone()).format(Instant.ofEpochMilli(cutoffMs))
        thumbsRoot.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            if (dir.name < cutoffDay) {
                dir.listFiles()?.forEach { if (it.isFile && it.delete()) deleted++ }
            }
            if (dir.list()?.isEmpty() == true) dir.delete()
        }
        return deleted
        // END_BLOCK_PURGE_THUMBS
    }

    // START_CONTRACT: trimClips
    //   PURPOSE: Keep the clip cache within [maxBytes]: newest (lastModified) files kept, older ones deleted.
    //   INPUTS: { maxBytes: Long - budget, default 50 MB }
    //   OUTPUTS: { Int - number of files deleted }
    //   SIDE_EFFECTS: file deletes under cacheDir/event_clips (stray .tmp files included)
    // END_CONTRACT: trimClips
    fun trimClips(maxBytes: Long = CLIP_CACHE_MAX_BYTES): Int {
        // START_BLOCK_TRIM_CLIPS
        val all = clipsRoot.listFiles()?.filter { it.isFile } ?: return 0
        var deleted = 0
        all.filter { it.name.endsWith(TMP_SUFFIX) }.forEach { if (it.delete()) deleted++ }
        var total = 0L
        all.filter { !it.name.endsWith(TMP_SUFFIX) }
            .sortedByDescending { it.lastModified() }
            .forEach { f ->
                total += f.length()
                if (total > maxBytes && f.delete()) deleted++
            }
        return deleted
        // END_BLOCK_TRIM_CLIPS
    }

    /** Delete every preview and clip file (logout). */
    fun wipeAll() {
        thumbsRoot.deleteRecursively()
        clipsRoot.deleteRecursively()
    }

    companion object {
        const val CLIP_CACHE_MAX_BYTES = 50L * 1024 * 1024
        private const val THUMBS_DIR = "event_thumbs"
        private const val CLIPS_DIR = "event_clips"
        private const val TMP_SUFFIX = ".tmp"
        private const val HASH_HEX_CHARS = 32
        private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

        private fun hash(key: String): String =
            MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(HASH_HEX_CHARS)
    }
}
