// FILE: EventMediaRepositoryTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Verify EventMediaRepository and EventMediaFiles: preview prefetch (decrypt, tar pick, yitar skip,
//     raw body, failures, concurrency 2), clip download with the single refresh rule, clip LRU, thumb purge,
//     hashed value-free paths and logs.
//   SCOPE: V-M-EVENT-PLAYBACK media scenarios; plain JVM; synthetic values; fake fetcher / ref source / encoder.
//   DEPENDS: M-EVENT-PLAYBACK (EventMediaRepository, EventMediaFiles), TestInfrastructure (FakeEventCache,
//     TraceRecorder, RedactionScanner)
//   LINKS: V-M-EVENT-PLAYBACK, EVT-01, EVT-03, EVT-05
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.event

// START_MODULE_MAP
//   FakeFetcher - MediaFetcher fake: scripted answers per URL, call log, max concurrency.
//   FakeRefSource - MediaRefSource fake: refs map, scripted refreshMedia answers, call count.
//   encrypt / jpeg / mp4 / tarOf - synthetic media builders (AES-128/ECB/NoPadding like the vendor).
//   EventMediaRepositoryTest - prefetch, clip, LRU, purge and redaction scenarios.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 05-03: created (RED first).
// END_CHANGE_SUMMARY

import com.yicamalt.database.EventEntity
import com.yicamalt.test.FakeEventCache
import com.yicamalt.test.RedactionScanner
import com.yicamalt.test.TraceRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

internal class FakeFetcher : MediaFetcher {
    val calls = mutableListOf<String>()
    val answers = HashMap<String, ArrayDeque<FetchResponse>>()
    var delayMs = 0L
    private val active = AtomicInteger(0)
    val maxActive = AtomicInteger(0)

    fun answer(url: String, vararg responses: FetchResponse) {
        answers.getOrPut(url) { ArrayDeque() }.addAll(responses)
    }

    override suspend fun get(url: String, maxBytes: Int): FetchResponse {
        synchronized(calls) { calls += url }
        val now = active.incrementAndGet()
        maxActive.accumulateAndGet(now) { a, b -> maxOf(a, b) }
        try {
            if (delayMs > 0) delay(delayMs)
            return synchronized(answers) { answers[url]?.removeFirstOrNull() } ?: FetchResponse(404, null)
        } finally {
            active.decrementAndGet()
        }
    }
}

internal class FakeRefSource : MediaRefSource {
    val refs = HashMap<String, MediaRef>()
    val refreshAnswers = ArrayDeque<MediaRef?>()
    var refreshCalls = 0

    override fun mediaFor(key: String): MediaRef? = refs[key]

    override suspend fun refreshMedia(key: String): MediaRef? {
        refreshCalls++
        val ref = refreshAnswers.removeFirstOrNull()
        if (ref != null) refs[key] = ref
        return ref
    }
}

class EventMediaRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private val t0 = 1_760_000_000_000L
    private var now = t0
    private val zone: ZoneId = ZoneId.of("UTC")
    private val device = "synth-cam-1"
    private val host = "media.synth.example"
    private val pwd = "SYNTHPWDVALUE123"

    private lateinit var filesDir: File
    private lateinit var cacheDir: File
    private lateinit var files: EventMediaFiles
    private val cache = FakeEventCache()
    private val fetcher = FakeFetcher()
    private val refs = FakeRefSource()
    private lateinit var repo: EventMediaRepository
    private val recorder = TraceRecorder()

    @Before fun setUp() {
        recorder.plant()
        filesDir = tmp.newFolder("files")
        cacheDir = tmp.newFolder("cache")
        files = EventMediaFiles(filesDir, cacheDir) { zone }
        repo = EventMediaRepository(
            cache.eventDao, refs, files, ThumbEncoder { it }, fetcher, cache.cleaner,
            clock = { now }, io = Dispatchers.Unconfined,
        )
    }

    @After fun tearDown() { recorder.unplant() }

    // START_BLOCK_FIXTURES
    private fun encrypt(plain: ByteArray, key: String = pwd): ByteArray {
        val padded = plain.copyOf(((plain.size + 15) / 16) * 16)
        val c = Cipher.getInstance("AES/ECB/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.take(16).toByteArray(Charsets.US_ASCII), "AES"))
        val n = plain.size
        return byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte()) + c.doFinal(padded)
    }

    private fun jpeg(tag: Int = 1): ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) +
        ByteArray(40) { (it + tag).toByte() }

    private fun mp4(): ByteArray = byteArrayOf(0, 0, 0, 0x18) + "ftypisom".toByteArray() + ByteArray(12) +
        "moov....stsd....avc1".toByteArray() + ByteArray(64)

    private fun tarEntry(name: String, data: ByteArray): ByteArray {
        val header = ByteArray(512)
        name.toByteArray(Charsets.US_ASCII).copyInto(header, 0)
        Integer.toOctalString(data.size).padStart(11, '0').toByteArray(Charsets.US_ASCII).copyInto(header, 124)
        "ustar".toByteArray(Charsets.US_ASCII).copyInto(header, 257)
        return header + data.copyOf(((data.size + 511) / 512) * 512)
    }

    private fun tarOf(vararg entries: ByteArray) = entries.fold(ByteArray(0)) { a, b -> a + b } + ByteArray(1024)

    private fun picUrl(key: String, ext: String = "jpg", v: String = "v1") = "https://$host/p/$key-$v.$ext?sig=x"
    private fun clipUrl(key: String, v: String = "v1") = "https://$host/v/$key-$v.mp4?sig=x"

    private fun ref(key: String, picExt: String = "jpg", picPwd: String? = pwd, v: String = "v1", expire: Long = t0 + 3_600_000L) =
        MediaRef(picUrl(key, picExt, v), picPwd, clipUrl(key, v), pwd, expire)

    private fun record(key: String, timeMs: Long = t0 - 60_000L, fileType: String = "mp4", hasClip: Boolean = true) =
        AlertRecord(key, device, timeMs, 1, 0, fileType, 6, hasClip)

    private fun seed(r: AlertRecord): AlertRecord {
        cache.eventDao.upsertAll(
            listOf(EventEntity(r.key, r.deviceId, r.timeMs, r.type, r.subType, r.fileType, r.hasClip, r.playTimeS, null, t0)),
        )
        return r
    }

    private fun hash(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    private fun day(timeMs: Long): String =
        DateTimeFormatter.ofPattern("yyyyMMdd").withZone(zone).format(Instant.ofEpochMilli(timeMs))

    private fun thumbFiles(): List<File> =
        File(filesDir, "event_thumbs").walkTopDown().filter { it.isFile }.toList()

    private fun ok(body: ByteArray) = FetchResponse(200, body)
    // END_BLOCK_FIXTURES

    @Test
    fun `prefetch saves a decrypted jpeg thumb and sets thumb_path`() = runBlocking {
        val r = seed(record("synth-key-1"))
        refs.refs[r.key] = ref(r.key)
        val image = jpeg()
        fetcher.answer(picUrl(r.key), ok(encrypt(image)))
        repo.prefetchThumbs(listOf(r))
        val rel = cache.eventDao.getByKey(r.key)!!.thumbPath
        assertEquals("event_thumbs/${day(r.timeMs)}/${hash(r.key)}.jpg", rel)
        assertArrayEquals(image, File(filesDir, rel!!).readBytes())
        assertEquals(listOf(picUrl(r.key)), fetcher.calls)
    }

    @Test
    fun `tar preview uses the non face jpg entry`() = runBlocking {
        val r = seed(record("synth-key-2"))
        refs.refs[r.key] = ref(r.key, picExt = "tar")
        val preview = jpeg(7)
        val tar = tarOf(tarEntry("face_detect/face_0.jpg", jpeg(3)), tarEntry("snap.jpg", preview))
        fetcher.answer(picUrl(r.key, "tar"), ok(encrypt(tar)))
        repo.prefetchThumbs(listOf(r))
        val rel = cache.eventDao.getByKey(r.key)!!.thumbPath!!
        assertArrayEquals(preview, File(filesDir, rel).readBytes())
    }

    @Test
    fun `yitar preview is skipped and thumb_path stays null`() = runBlocking {
        val r = seed(record("synth-key-3"))
        refs.refs[r.key] = ref(r.key, picExt = "yitar")
        repo.prefetchThumbs(listOf(r))
        assertTrue(fetcher.calls.isEmpty())
        assertNull(cache.eventDao.getByKey(r.key)!!.thumbPath)
        assertTrue(thumbFiles().isEmpty())
    }

    @Test
    fun `empty pic_pwd uses the raw body`() = runBlocking {
        val r = seed(record("synth-key-4"))
        refs.refs[r.key] = ref(r.key, picPwd = "")
        val image = jpeg(9)
        fetcher.answer(picUrl(r.key), ok(image))
        repo.prefetchThumbs(listOf(r))
        assertArrayEquals(image, File(filesDir, cache.eventDao.getByKey(r.key)!!.thumbPath!!).readBytes())
    }

    @Test
    fun `decrypt failure or wrong magic leaves no file and logs decryptOk=false`() = runBlocking {
        val bad = seed(record("synth-key-5a"))
        val odd = seed(record("synth-key-5b", timeMs = t0 - 120_000L))
        refs.refs[bad.key] = ref(bad.key)
        refs.refs[odd.key] = ref(odd.key)
        fetcher.answer(picUrl(bad.key), ok(ByteArray(4 + 15)))
        fetcher.answer(picUrl(odd.key), ok(encrypt("SYNTH not an image at all".toByteArray())))
        repo.prefetchThumbs(listOf(bad, odd))
        assertNull(cache.eventDao.getByKey(bad.key)!!.thumbPath)
        assertNull(cache.eventDao.getByKey(odd.key)!!.thumbPath)
        assertTrue(thumbFiles().isEmpty())
        val logs = recorder.bufferText()
        assertTrue(logs.contains("decryptOk=false"))
        assertTrue(logs.contains("decryptOk=true magic=unknown"))
        assertTrue(logs.contains("failed=2"))
        assertEquals(2, fetcher.calls.size)
    }

    @Test
    fun `prefetch runs at most 2 downloads at once`() = runBlocking {
        fetcher.delayMs = 30
        val records = (0 until 6).map { i -> seed(record("synth-key-c$i", timeMs = t0 - i * 1_000L)) }
        records.forEach { r -> refs.refs[r.key] = ref(r.key); fetcher.answer(picUrl(r.key), ok(encrypt(jpeg()))) }
        repo.prefetchThumbs(records)
        assertEquals(6, fetcher.calls.size)
        assertEquals(2, fetcher.maxActive.get())
        assertEquals(6, thumbFiles().size)
    }

    @Test
    fun `prefetch with no media ref skips the row`() = runBlocking {
        val r = seed(record("synth-key-6"))
        repo.prefetchThumbs(listOf(r))
        assertTrue(fetcher.calls.isEmpty())
        assertNull(cache.eventDao.getByKey(r.key)!!.thumbPath)
        assertTrue(recorder.bufferText().contains("skipped=1"))
    }

    @Test
    fun `clip returns a decrypted mp4 file in event_clips`() = runBlocking {
        val r = seed(record("synth-key-7"))
        refs.refs[r.key] = ref(r.key)
        val video = mp4()
        fetcher.answer(clipUrl(r.key), ok(encrypt(video)))
        val result = repo.clip(r.key) as ClipResult.Ready
        assertEquals(File(cacheDir, "event_clips/${hash(r.key)}.mp4").canonicalPath, result.file.canonicalPath)
        assertArrayEquals(video, result.file.readBytes())
        assertEquals(MediaCrypto.Codec.AVC, result.codec)
        assertEquals(0, refs.refreshCalls)
    }

    @Test
    fun `cached clip is returned without a download`() = runBlocking {
        val r = seed(record("synth-key-7c"))
        refs.refs[r.key] = ref(r.key)
        fetcher.answer(clipUrl(r.key), ok(encrypt(mp4())))
        assertTrue(repo.clip(r.key) is ClipResult.Ready)
        assertTrue(repo.clip(r.key) is ClipResult.Ready)
        assertEquals(1, fetcher.calls.size)
    }

    @Test
    fun `clip with ts file type returns Unsupported without a download`() = runBlocking {
        val r = seed(record("synth-key-8", fileType = "ts"))
        refs.refs[r.key] = ref(r.key)
        assertEquals(ClipResult.Unsupported, repo.clip(r.key))
        assertTrue(fetcher.calls.isEmpty())
        assertEquals(0, refs.refreshCalls)
    }

    @Test
    fun `stale ref refreshes once before download`() = runBlocking {
        val r = seed(record("synth-key-9"))
        refs.refs[r.key] = ref(r.key, expire = now - 20_000L)
        refs.refreshAnswers.addLast(ref(r.key, v = "v2"))
        fetcher.answer(clipUrl(r.key, "v2"), ok(encrypt(mp4())))
        assertTrue(repo.clip(r.key) is ClipResult.Ready)
        assertEquals(1, refs.refreshCalls)
        assertEquals(listOf(clipUrl(r.key, "v2")), fetcher.calls)
        assertTrue(recorder.bufferText().contains("[EventMedia][clip][BLOCK_CLIP_REFRESH] reason=stale result=ok"))
    }

    @Test
    fun `403 refreshes once then retries once`() = runBlocking {
        val r = seed(record("synth-key-10"))
        refs.refs[r.key] = ref(r.key)
        refs.refreshAnswers.addLast(ref(r.key, v = "v2"))
        fetcher.answer(clipUrl(r.key), FetchResponse(403, null))
        fetcher.answer(clipUrl(r.key, "v2"), ok(encrypt(mp4())))
        assertTrue(repo.clip(r.key) is ClipResult.Ready)
        assertEquals(1, refs.refreshCalls)
        assertEquals(listOf(clipUrl(r.key), clipUrl(r.key, "v2")), fetcher.calls)
        assertTrue(recorder.bufferText().contains("reason=403 result=ok"))
    }

    @Test
    fun `second 403 returns Failed`() = runBlocking {
        val r = seed(record("synth-key-11"))
        refs.refs[r.key] = ref(r.key)
        refs.refreshAnswers.addLast(ref(r.key, v = "v2"))
        fetcher.answer(clipUrl(r.key), FetchResponse(403, null))
        fetcher.answer(clipUrl(r.key, "v2"), FetchResponse(403, null))
        assertEquals(ClipResult.Failed("http"), repo.clip(r.key))
        assertEquals(1, refs.refreshCalls)
        assertEquals(2, fetcher.calls.size)
        assertFalse(File(cacheDir, "event_clips/${hash(r.key)}.mp4").exists())
    }

    @Test
    fun `refresh returning null returns Expired`() = runBlocking {
        val r = seed(record("synth-key-12"))
        refs.refs[r.key] = ref(r.key, expire = now - 20_000L)
        refs.refreshAnswers.addLast(null)
        assertEquals(ClipResult.Expired, repo.clip(r.key))
        assertTrue(fetcher.calls.isEmpty())
        assertEquals(1, refs.refreshCalls)
    }

    @Test
    fun `clip without a media ref calls refreshMedia`() = runBlocking {
        val r = seed(record("synth-key-13"))
        refs.refreshAnswers.addLast(ref(r.key, v = "v2"))
        fetcher.answer(clipUrl(r.key, "v2"), ok(encrypt(mp4())))
        assertTrue(repo.clip(r.key) is ClipResult.Ready)
        assertEquals(1, refs.refreshCalls)
        assertTrue(recorder.bufferText().contains("reason=missing result=ok"))
    }

    @Test
    fun `clip of a row without a clip returns Failed no-clip`() = runBlocking {
        val r = seed(record("synth-key-13n", hasClip = false))
        assertEquals(ClipResult.Failed("no-clip"), repo.clip(r.key))
        assertEquals(ClipResult.Failed("no-clip"), repo.clip("synth-missing"))
        assertTrue(fetcher.calls.isEmpty())
    }

    @Test
    fun `clip with a wrong container returns Failed format and writes nothing`() = runBlocking {
        val r = seed(record("synth-key-13f"))
        refs.refs[r.key] = ref(r.key)
        fetcher.answer(clipUrl(r.key), ok(encrypt(jpeg())))
        assertEquals(ClipResult.Failed("format"), repo.clip(r.key))
        assertFalse(File(cacheDir, "event_clips/${hash(r.key)}.mp4").exists())
    }

    @Test
    fun `clip cache evicts oldest files beyond 50 MB`() = runBlocking {
        val dir = File(cacheDir, "event_clips").apply { mkdirs() }
        val big = 20L * 1024 * 1024
        val old = (0 until 3).map { i ->
            File(dir, "old$i.mp4").also { f ->
                RandomAccessFile(f, "rw").use { it.setLength(big) }
                f.setLastModified(t0 - (10 - i) * 60_000L)
            }
        }
        val r = seed(record("synth-key-14"))
        refs.refs[r.key] = ref(r.key)
        fetcher.answer(clipUrl(r.key), ok(encrypt(mp4())))
        val ready = repo.clip(r.key) as ClipResult.Ready
        assertTrue(ready.file.exists())
        assertFalse(old[0].exists())
        assertTrue(old[1].exists())
        assertTrue(old[2].exists())
        assertTrue(dir.listFiles()!!.sumOf { it.length() } <= EventMediaFiles.CLIP_CACHE_MAX_BYTES)
    }

    @Test
    fun `purge deletes thumbs older than 30 days and their day folders`() {
        val oldTime = t0 - 31L * 86_400_000L
        val newTime = t0 - 1L * 86_400_000L
        val oldRel = files.thumbRelPath("synth-old", oldTime)
        val newRel = files.thumbRelPath("synth-new", newTime)
        assertTrue(files.writeAtomic(files.thumbFile(oldRel)!!, jpeg()))
        assertTrue(files.writeAtomic(files.thumbFile(newRel)!!, jpeg()))
        val deleted = files.purgeThumbsOlderThan(t0 - 30L * 86_400_000L, listOf(oldRel))
        assertEquals(1, deleted)
        assertFalse(File(filesDir, oldRel).exists())
        assertFalse(File(filesDir, "event_thumbs/${day(oldTime)}").exists())
        assertTrue(File(filesDir, newRel).exists())
    }

    @Test
    fun `thumbFile rejects paths outside event_thumbs`() {
        assertNull(files.thumbFile("../outside.jpg"))
        assertNull(files.thumbFile("event_thumbs/../../outside.jpg"))
        assertNull(files.thumbFile("/abs/event_thumbs/x.jpg"))
        assertNotNull(files.thumbFile(files.thumbRelPath("synth-k", t0)))
    }

    @Test
    fun `wipeAll removes thumbs and clips`() = runBlocking {
        val r = seed(record("synth-key-w"))
        refs.refs[r.key] = ref(r.key)
        fetcher.answer(picUrl(r.key), ok(encrypt(jpeg())))
        fetcher.answer(clipUrl(r.key), ok(encrypt(mp4())))
        repo.prefetchThumbs(listOf(r))
        assertTrue(repo.clip(r.key) is ClipResult.Ready)
        files.wipeAll()
        assertFalse(File(filesDir, "event_thumbs").exists())
        assertFalse(File(cacheDir, "event_clips").exists())
    }

    @Test
    fun `paths and logs contain no key, uid, url or pwd`() = runBlocking {
        val r = seed(record("synth-key-15"))
        refs.refs[r.key] = ref(r.key, expire = now - 20_000L)
        refs.refreshAnswers.addLast(ref(r.key, v = "v2"))
        fetcher.answer(picUrl(r.key), ok(encrypt(jpeg())))
        fetcher.answer(clipUrl(r.key, "v2"), FetchResponse(403, null))
        repo.prefetchThumbs(listOf(r))
        repo.clip(r.key)
        val rel = cache.eventDao.getByKey(r.key)!!.thumbPath!!
        val clipPath = files.clipFile(r.key).path
        listOf(rel, clipPath).forEach { p ->
            listOf(r.key, device, host, pwd, "sig=").forEach { assertFalse(p.contains(it)) }
        }
        val logs = recorder.bufferText()
        assertTrue(logs.contains("[EventMedia][thumb][BLOCK_FETCH_THUMB] status=200 bytes=lt16k ext=jpg decryptOk=true magic=jpeg"))
        assertTrue(logs.contains("[EventMedia][clip][BLOCK_FETCH_CLIP] status=403"))
        assertFalse(logs.contains(hash(r.key)))
        RedactionScanner().assertClean(logs, extraLiterals = listOf(r.key, device, host, pwd, "sig=", "https://"))
    }
}
