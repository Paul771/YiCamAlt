// FILE: EventMediaRepository.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Event previews and clips as local files (M-EVENT-PLAYBACK): download through the plain media client,
//     decrypt, check the container, downscale previews into filesDir, write clips into the cacheDir LRU.
//   SCOPE: FetchResponse, MediaFetcher (+ PlainMediaFetcher over MediaHttp.plainClient), MediaRefSource, ClipResult,
//     EventMediaRepository.prefetchThumbs / clip.
//   DEPENDS: M-EVENT (AlertSyncService media refs, MediaCrypto, MediaHttp, EventMediaFiles, ThumbEncoder,
//     EventCacheCleaner), M-LOCAL-DB (EventDao)
//   LINKS: M-EVENT-PLAYBACK, V-M-EVENT-PLAYBACK, EVT-01, EVT-03, EVT-05
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.event

import com.yicamalt.database.EventDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okio.Buffer
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton

// START_MODULE_MAP
//   FetchResponse - HTTP code (or CODE_IO / CODE_TOO_LARGE) and the body of a 2xx answer within the size cap.
//   MediaFetcher - port: one plain GET with a body size cap.
//   PlainMediaFetcher - MediaFetcher over MediaHttp.plainClient() (no auth header, no logger).
//   MediaRefSource - port over AlertSyncService.mediaFor / refreshMedia.
//   ClipResult - Ready(file, codec) | Unsupported | Expired | Failed(reason in http, decrypt, format, io, no-clip).
//   EventMediaRepository - ThumbPrefetcher; prefetchThumbs(records) (2 downloads at once, no retry), clip(key)
//     (one refresh at most).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 05-03: created. Previews: .yitar skipped, .tar -> tarPreview, JPEG/PNG only,
//     downscaled by ThumbEncoder, saved under a hashed name, thumb_path set. Clips: ts -> Unsupported, stale /
//     missing ref or one CDN 403 -> one refreshMedia, MP4 only, cached file reused, LRU trim after each write.
//   HAZARD: signed URLs and decrypt keys stay in memory; logs carry status, size bucket, extension class,
//     decrypt flag and magic class only. Exceptions are logged by simple class name only (their messages can
//     carry the URL or a path). File writes run under EventCacheCleaner.writeIfCurrent so a logout wipe wins.
// END_CHANGE_SUMMARY

/** One media GET: [code] is the HTTP status (or a negative CODE_*); [body] only for 2xx within the cap. */
internal class FetchResponse(val code: Int, val body: ByteArray?) {
    companion object {
        const val CODE_IO = -1
        const val CODE_TOO_LARGE = -2
    }
}

internal fun interface MediaFetcher {
    suspend fun get(url: String, maxBytes: Int): FetchResponse
}

/** Plain GET through MediaHttp.plainClient(); never the shared Yi client (token leak, URL logging). */
internal class PlainMediaFetcher : MediaFetcher {
    // START_CONTRACT: get
    //   PURPOSE: One GET of a signed media URL with a body size cap.
    //   INPUTS: { url: String - https media URL; maxBytes: Int - cap; larger bodies are a failure }
    //   OUTPUTS: { FetchResponse - (status, body) for 2xx, (status, null) otherwise, CODE_IO on I/O or URL error,
    //     CODE_TOO_LARGE above the cap }
    //   SIDE_EFFECTS: one network request; no retry
    //   LINKS: MediaHttp.plainClient
    // END_CONTRACT: get
    override suspend fun get(url: String, maxBytes: Int): FetchResponse = withContext(Dispatchers.IO) {
        // START_BLOCK_PLAIN_GET
        try {
            val request = Request.Builder().url(url).get().build()
            MediaHttp.plainClient().newCall(request).execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) return@withContext FetchResponse(response.code, null)
                if (body.contentLength() > maxBytes) return@withContext FetchResponse(FetchResponse.CODE_TOO_LARGE, null)
                val source = body.source()
                val buffer = Buffer()
                while (buffer.size <= maxBytes) {
                    if (source.read(buffer, READ_CHUNK) == -1L) break
                }
                if (buffer.size > maxBytes) return@withContext FetchResponse(FetchResponse.CODE_TOO_LARGE, null)
                FetchResponse(response.code, buffer.readByteArray())
            }
        } catch (e: IOException) {
            FetchResponse(FetchResponse.CODE_IO, null)
        } catch (e: IllegalArgumentException) {
            FetchResponse(FetchResponse.CODE_IO, null)
        }
        // END_BLOCK_PLAIN_GET
    }

    private companion object {
        const val READ_CHUNK = 8_192L
    }
}

/** Media refs of the last sync and the plus minus 5 s refresh (AlertSyncService). */
internal interface MediaRefSource {
    fun mediaFor(key: String): MediaRef?
    suspend fun refreshMedia(key: String): MediaRef?
}

internal sealed interface ClipResult {
    data class Ready(val file: File, val codec: MediaCrypto.Codec) : ClipResult
    object Unsupported : ClipResult
    object Expired : ClipResult
    data class Failed(val reason: String) : ClipResult
}

@Singleton
class EventMediaRepository internal constructor(
    private val eventDao: EventDao,
    private val source: MediaRefSource,
    private val files: EventMediaFiles,
    private val encoder: ThumbEncoder,
    private val fetcher: MediaFetcher,
    private val cleaner: EventCacheCleaner,
    private val clock: () -> Long,
    private val io: CoroutineDispatcher,
) : ThumbPrefetcher {
    @Inject constructor(
        eventDao: EventDao,
        sync: AlertSyncService,
        files: EventMediaFiles,
        encoder: BitmapThumbEncoder,
        cleaner: EventCacheCleaner,
    ) : this(
        eventDao,
        object : MediaRefSource {
            override fun mediaFor(key: String): MediaRef? = sync.mediaFor(key)
            override suspend fun refreshMedia(key: String): MediaRef? = sync.refreshMedia(key)
        },
        files, encoder, PlainMediaFetcher(), cleaner, System::currentTimeMillis, Dispatchers.IO,
    )

    /** ThumbPrefetcher port used by AlertSyncService after a refresh. */
    override suspend fun prefetch(records: List<AlertRecord>) = prefetchThumbs(records)

    // START_CONTRACT: prefetchThumbs
    //   PURPOSE: Turn the previews of [records] into local downscaled files for the offline list (EVT-01, EVT-05).
    //   INPUTS: { records: List<AlertRecord> - rows already written to Room, newest first }
    //   OUTPUTS: { Unit - returns when every record was tried once }
    //   SIDE_EFFECTS: at most 2 concurrent plain GETs; files under filesDir/event_thumbs; EventDao.setThumb.
    //     Rows that already have a thumb, rows without a media ref or with a .yitar preview are skipped;
    //     each failure is logged and skipped, never retried.
    //   LINKS: MediaFetcher, MediaCrypto, ThumbEncoder, EventMediaFiles.thumbRelPath
    // END_CONTRACT: prefetchThumbs
    suspend fun prefetchThumbs(records: List<AlertRecord>) = withContext(io) {
        // START_BLOCK_PREFETCH_PASS
        val permits = Semaphore(2)
        val outcomes = java.util.concurrent.ConcurrentHashMap<String, Int>()
        coroutineScope {
            records.forEach { record ->
                launch { permits.withPermit { outcomes.merge(prefetchOne(record), 1, Int::plus) } }
            }
        }
        Timber.d(
            "[EventMedia][thumb][BLOCK_PREFETCH_PASS] total=%d saved=%d skipped=%d failed=%d",
            records.size, outcomes[SAVED] ?: 0, outcomes[SKIPPED] ?: 0, outcomes[FAILED] ?: 0,
        )
        // END_BLOCK_PREFETCH_PASS
    }

    private suspend fun prefetchOne(record: AlertRecord): String {
        // START_BLOCK_FETCH_THUMB
        val generation = cleaner.generation
        val row = eventDao.getByKey(record.key) ?: return SKIPPED
        if (row.thumbPath != null) return SKIPPED
        val ref = source.mediaFor(record.key)
        val url = ref?.picUrl
        if (url.isNullOrBlank()) return SKIPPED
        val ext = extClass(url)
        if (ext == "yitar") return SKIPPED
        val response = try {
            fetcher.get(MediaHttp.secureUrl(url), THUMB_MAX_BYTES)
        } catch (e: IOException) {
            Timber.d("[EventMedia][thumb][BLOCK_FETCH_THUMB] error=%s", e.javaClass.simpleName)
            return FAILED
        }
        val body = response.body
        if (response.code !in 200..299 || body == null) {
            logFetch("thumb", "BLOCK_FETCH_THUMB", response.code, 0, ext, false, "none")
            return FAILED
        }
        val plain = decryptOrRaw(body, ref.picPwd)
        val container = plain?.let(MediaCrypto::magic)
        val image = if (plain != null && ext == "tar") MediaCrypto.tarPreview(plain) else plain
        val imageMagic = image?.let(MediaCrypto::magic)
        logFetch("thumb", "BLOCK_FETCH_THUMB", response.code, body.size, ext, plain != null, magicLabel(container))
        if (image == null || (imageMagic != MediaCrypto.Magic.JPEG && imageMagic != MediaCrypto.Magic.PNG)) return FAILED
        val small = runCatching { encoder.encode(image) }.getOrNull()
        if (small == null) {
            Timber.d("[EventMedia][thumb][BLOCK_FETCH_THUMB] encode=failed")
            return FAILED
        }
        val rel = files.thumbRelPath(record.key, record.timeMs)
        val file = files.thumbFile(rel) ?: return FAILED
        val saved = cleaner.writeIfCurrent(generation) {
            if (eventDao.getByKey(record.key) == null) return@writeIfCurrent false
            if (!files.writeAtomic(file, small)) return@writeIfCurrent false
            eventDao.setThumb(record.key, rel)
            true
        }
        return if (saved == true) SAVED else FAILED
        // END_BLOCK_FETCH_THUMB
    }

    // START_CONTRACT: clip
    //   PURPOSE: A decrypted local MP4 of one cached event, ready for MediaPlayer (EVT-03).
    //   INPUTS: { key: String - event_key of a cached row }
    //   OUTPUTS: { ClipResult - Ready(file in cacheDir/event_clips, codec) | Unsupported (file_type ts) |
    //     Expired (no fresh ref) | Failed(no-clip | http | decrypt | format | io) }
    //   SIDE_EFFECTS: at most one refreshMedia (missing / stale ref or the first CDN 403) and at most two plain
    //     GETs; writes the clip file and trims the clip cache to 50 MB. A cached clip file is returned without
    //     network.
    //   LINKS: MediaRefSource.refreshMedia, MediaCrypto, EventMediaFiles.clipFile / trimClips
    // END_CONTRACT: clip
    internal suspend fun clip(key: String): ClipResult = withContext(io) {
        // START_BLOCK_CLIP_ROW
        val row = eventDao.getByKey(key) ?: return@withContext ClipResult.Failed(REASON_NO_CLIP)
        if (row.fileType.equals("ts", ignoreCase = true)) return@withContext ClipResult.Unsupported
        if (!row.hasClip) return@withContext ClipResult.Failed(REASON_NO_CLIP)
        val cached = files.clipFile(key)
        if (cached.isFile && cached.length() > 0) {
            cached.setLastModified(clock())
            return@withContext ClipResult.Ready(cached, MediaCrypto.codec(head(cached)))
        }
        // END_BLOCK_CLIP_ROW

        // START_BLOCK_CLIP_REFRESH
        val generation = cleaner.generation
        var refreshed = false
        var ref = source.mediaFor(key)
        if (ref == null || ref.isStale(clock())) {
            val reason = if (ref == null) "missing" else "stale"
            refreshed = true
            ref = source.refreshMedia(key)
            logRefresh(reason, ref != null)
            if (ref == null) return@withContext ClipResult.Expired
        }
        // END_BLOCK_CLIP_REFRESH

        // START_BLOCK_FETCH_CLIP
        var url = ref.videoUrl ?: return@withContext ClipResult.Failed(REASON_NO_CLIP)
        var response = fetchClip(url) ?: return@withContext ClipResult.Failed(REASON_IO)
        if (response.code == HTTP_FORBIDDEN && !refreshed) {
            ref = source.refreshMedia(key)
            logRefresh("403", ref != null)
            if (ref == null) return@withContext ClipResult.Expired
            url = ref.videoUrl ?: return@withContext ClipResult.Failed(REASON_NO_CLIP)
            response = fetchClip(url) ?: return@withContext ClipResult.Failed(REASON_IO)
        }
        val body = response.body
        val ext = extClass(url)
        if (response.code !in 200..299 || body == null) {
            logFetch("clip", "BLOCK_FETCH_CLIP", response.code, 0, ext, false, "none")
            return@withContext ClipResult.Failed(
                when (response.code) {
                    FetchResponse.CODE_IO -> REASON_IO
                    FetchResponse.CODE_TOO_LARGE -> REASON_FORMAT
                    else -> REASON_HTTP
                },
            )
        }
        val plain = decryptOrRaw(body, ref.videoPwd)
        val magic = plain?.let(MediaCrypto::magic)
        logFetch("clip", "BLOCK_FETCH_CLIP", response.code, body.size, ext, plain != null, magicLabel(magic))
        if (plain == null) return@withContext ClipResult.Failed(REASON_DECRYPT)
        if (magic != MediaCrypto.Magic.MP4) return@withContext ClipResult.Failed(REASON_FORMAT)
        val file = files.clipFile(key)
        val written = cleaner.writeIfCurrent(generation) { files.writeAtomic(file, plain) } == true
        if (!written) return@withContext ClipResult.Failed(REASON_IO)
        files.trimClips()
        ClipResult.Ready(file, MediaCrypto.codec(plain))
        // END_BLOCK_FETCH_CLIP
    }

    private suspend fun fetchClip(url: String): FetchResponse? = try {
        fetcher.get(MediaHttp.secureUrl(url), CLIP_MAX_BYTES)
    } catch (e: IOException) {
        Timber.d("[EventMedia][clip][BLOCK_FETCH_CLIP] error=%s", e.javaClass.simpleName)
        null
    }

    private fun logRefresh(reason: String, ok: Boolean) {
        Timber.d("[EventMedia][clip][BLOCK_CLIP_REFRESH] reason=%s result=%s", reason, if (ok) "ok" else "none")
    }

    private fun logFetch(kind: String, block: String, status: Int, bytes: Int, ext: String, decryptOk: Boolean, magic: String) {
        Timber.d(
            "[EventMedia][%s][%s] status=%d bytes=%s ext=%s decryptOk=%s magic=%s",
            kind, block, status, sizeBucket(bytes), ext, decryptOk, magic,
        )
    }

    /** Decrypt with [pwd]; a blank key means the body is not encrypted. */
    private fun decryptOrRaw(body: ByteArray, pwd: String?): ByteArray? =
        if (pwd.isNullOrBlank()) body else MediaCrypto.decrypt(body, pwd)

    private fun head(file: File): ByteArray = try {
        RandomAccessFile(file, "r").use { raf ->
            val n = minOf(raf.length(), HEAD_BYTES.toLong()).toInt()
            ByteArray(n).also { raf.readFully(it) }
        }
    } catch (e: IOException) {
        ByteArray(0)
    }

    internal companion object {
        const val THUMB_MAX_BYTES = 2 * 1024 * 1024
        const val CLIP_MAX_BYTES = 8 * 1024 * 1024
        private const val HEAD_BYTES = 1 shl 20
        private const val HTTP_FORBIDDEN = 403
        private const val SAVED = "saved"
        private const val SKIPPED = "skipped"
        private const val FAILED = "failed"
        const val REASON_HTTP = "http"
        const val REASON_DECRYPT = "decrypt"
        const val REASON_FORMAT = "format"
        const val REASON_IO = "io"
        const val REASON_NO_CLIP = "no-clip"

        /** Extension class of the URL path: jpg | tar | yitar | mp4 | ts | other (never the URL itself). */
        fun extClass(url: String): String {
            val path = (url.toHttpUrlOrNull()?.encodedPath ?: url.substringBefore('?')).lowercase()
            return when {
                path.contains(".yitar") -> "yitar"
                path.contains(".tar") -> "tar"
                path.endsWith(".jpg") || path.endsWith(".jpeg") -> "jpg"
                path.endsWith(".mp4") -> "mp4"
                path.endsWith(".ts") || path.endsWith(".m3u8") -> "ts"
                else -> "other"
            }
        }

        fun sizeBucket(bytes: Int): String = when {
            bytes <= 0 -> "0"
            bytes < 16 * 1024 -> "lt16k"
            bytes < 64 * 1024 -> "lt64k"
            bytes < 256 * 1024 -> "lt256k"
            bytes < 1024 * 1024 -> "lt1m"
            else -> "ge1m"
        }

        fun magicLabel(magic: MediaCrypto.Magic?): String = magic?.name?.lowercase() ?: "none"
    }
}
