// FILE: EventClipFlowTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: End-to-end JVM check of the device path «лента загружена -> тап по карточке -> клип» (EVT-01, EVT-03,
//     EVT-04): real AlertSyncService refresh + preview prefetch into a strict in-memory Room (production main-thread
//     guard), feed cards built by EventsFeed from the stored rows, then ClipViewModel over the real
//     RepositoryClipSource / EventMediaRepository opening each card key on the main dispatcher.
//   SCOPE: both key shapes (server id and the uid_time fallback); clip Ready, camera name from the cached list, the
//     SD button enabled; no BLOCK_CLIP_LOAD error line; value-free logs.
//   DEPENDS: M-EVENT (AlertSyncService, MediaRefStore, EventCacheCleaner, EventMediaFiles), M-EVENT-PLAYBACK
//     (EventMediaRepository), M-UI-EVENTS (EventsFeed, ClipViewModel, RepositoryClipSource), TestInfrastructure
//     (InMemoryRoom.buildStrict, TraceRecorder, RedactionScanner, FakeAlertApi, FakeFetcher, MutableSession)
//   LINKS: V-M-UI-EVENTS, V-M-EVENT-PLAYBACK, EVT-03
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT

// START_MODULE_MAP
//   FlowCameras - CameraListPort with the cached «Спальня» camera; network calls fail the test.
//   EventClipFlowTest - refresh + prefetch, cards from the stored rows, tap -> Ready for both key shapes.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - Phase 5 device bug: the clip dialog failed with IllegalStateException before any request
//     because RepositoryClipSource.event ran the blocking EventDao.getByKey on Dispatchers.Main. ClipViewModelTest
//     used a fake ClipSource and every Room test allowed main-thread queries, so no JVM test could see it.
// END_CHANGE_SUMMARY
package com.yicamalt.ui.events

import com.yicamalt.auth.AuthMethod
import com.yicamalt.auth.AuthSession
import com.yicamalt.camera.CameraInfo
import com.yicamalt.camera.CameraListPort
import com.yicamalt.database.YiDatabase
import com.yicamalt.event.AlertEnvelope
import com.yicamalt.event.AlertSyncService
import com.yicamalt.event.AlertWire
import com.yicamalt.event.EventCacheCleaner
import com.yicamalt.event.EventMediaFiles
import com.yicamalt.event.EventMediaRepository
import com.yicamalt.event.FakeAlertApi
import com.yicamalt.event.FakeFetcher
import com.yicamalt.event.FetchResponse
import com.yicamalt.event.MediaHttp
import com.yicamalt.event.MediaRef
import com.yicamalt.event.MediaRefSource
import com.yicamalt.event.MediaRefStore
import com.yicamalt.event.MutableSession
import com.yicamalt.event.SyncResult
import com.yicamalt.event.ThumbEncoder
import com.yicamalt.test.InMemoryRoom
import com.yicamalt.test.RedactionScanner
import com.yicamalt.test.TraceRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.ZoneId
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

private const val FLOW_DEVICE = "synth-cam-flow"
private const val FLOW_CAMERA = "Спальня"

private class FlowCameras : CameraListPort {
    override suspend fun getCameraList(): List<CameraInfo> = error("network camera list must not be called")
    override suspend fun cachedCameras(): List<CameraInfo> =
        listOf(CameraInfo(FLOW_DEVICE, FLOW_CAMERA, "M", online = true, streamUrl = null))
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class EventClipFlowTest {

    @get:Rule val tmp = TemporaryFolder()

    private val t0 = 1_760_000_000_000L
    private val zone: ZoneId = ZoneId.of("UTC")
    private val mediaHost = "media.synth.example"
    private val pwd = "SYNTHPWDVALUE123"

    private lateinit var db: YiDatabase
    private lateinit var io: ExecutorCoroutineDispatcher
    private val appJob = SupervisorJob()
    private val api = FakeAlertApi()
    private val fetcher = FakeFetcher()
    private val media = MediaRefStore()
    private lateinit var service: AlertSyncService
    private lateinit var repo: EventMediaRepository
    private val main = StandardTestDispatcher()
    private val recorder = TraceRecorder()
    private val scanner = RedactionScanner()

    @Before fun setUp() {
        recorder.plant()
        Dispatchers.setMain(main)
        io = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        db = InMemoryRoom.buildStrict()
        val files = EventMediaFiles(tmp.newFolder("files"), tmp.newFolder("cache")) { zone }
        val cleaner = EventCacheCleaner(db.eventDao(), db.appMetaDao(), media, files)
        val session = MutableSession(AuthSession("SYNTHTOKENVALUE", "SYNTHSECRETVALUE", Long.MAX_VALUE, "synth-user-7", AuthMethod.PASSWORD))
        service = AlertSyncService(
            api, db.eventDao(), db.appMetaDao(), session, media, cleaner,
            clock = { t0 }, zone = { zone }, io = io,
            prefetcher = { repo }, files = files, appScope = CoroutineScope(appJob + io),
        )
        val refs = object : MediaRefSource {
            override fun mediaFor(key: String): MediaRef? = service.mediaFor(key)
            override suspend fun refreshMedia(key: String): MediaRef? = service.refreshMedia(key)
        }
        repo = EventMediaRepository(db.eventDao(), refs, files, ThumbEncoder { it }, fetcher, cleaner, { t0 }, io)
    }

    @After fun tearDown() {
        scanner.assertClean(recorder.bufferText(), extraLiterals = listOf(FLOW_DEVICE, FLOW_CAMERA))
        Dispatchers.resetMain()
        db.close()
        io.close()
        recorder.unplant()
    }

    // START_BLOCK_FIXTURES
    private fun encrypt(plain: ByteArray): ByteArray {
        val padded = plain.copyOf(((plain.size + 15) / 16) * 16)
        val c = Cipher.getInstance("AES/ECB/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(pwd.take(16).toByteArray(Charsets.US_ASCII), "AES"))
        val n = plain.size
        return byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte()) + c.doFinal(padded)
    }

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(40) { it.toByte() }
    private val mp4 = byteArrayOf(0, 0, 0, 0x18) + "ftypisom".toByteArray() + ByteArray(12) +
        "moov....stsd....avc1".toByteArray() + ByteArray(64)

    private fun picUrl(tag: String) = "http://$mediaHost/p/$tag.jpg?sig=x"
    private fun clipUrl(tag: String) = "http://$mediaHost/v/$tag.mp4?sig=x"

    /** One alert in the server shape; [id] empty = no id field (key falls back to uid_time). */
    private fun rec(id: String, tag: String, timeMs: Long): JsonObject = buildJsonObject {
        if (id.isNotEmpty()) put("id", id)
        put("uid", FLOW_DEVICE)
        put("time", timeMs)
        put("type", 1)
        put("sub_type", 0)
        put("file_type", "mp4")
        put("pic_urls", picUrl(tag))
        put("pic_pwd", pwd)
        put("video_urls", clipUrl(tag))
        put("video_pwd", pwd)
        put("expire_time", timeMs + 86_400_000L)
    }
    // END_BLOCK_FIXTURES

    @Test
    fun `feed loaded then tap on a card plays the clip with the camera name and the SD button`() = runTest(main) {
        val withId = rec("a1b2c3d4e5f60718", "with-id", t0 - 60_000L)
        val noId = rec("", "no-id", t0 - 120_000L)
        api.enqueue(AlertEnvelope(code = AlertWire.SUCCESS, data = JsonArray(listOf(withId, noId))))
        for (tag in listOf("with-id", "no-id")) {
            fetcher.answer(MediaHttp.secureUrl(picUrl(tag)), FetchResponse(200, encrypt(jpeg)))
            fetcher.answer(MediaHttp.secureUrl(clipUrl(tag)), FetchResponse(200, encrypt(mp4)))
        }

        // START_BLOCK_FEED_LOADED
        assertTrue(service.refresh() is SyncResult.Fetched)
        appJob.children.toList().forEach { it.join() }
        val rows = runBlocking(io) { db.eventDao().feed(0L, Long.MAX_VALUE) }
        assertEquals(2, rows.size)
        assertTrue("previews prefetched", rows.all { it.thumbPath != null })
        val cards = EventsFeed.build(rows, mapOf(FLOW_DEVICE to FLOW_CAMERA), emptySet(), null, zone, t0)
            .flatMap { it.cards }
        assertEquals(setOf("a1b2c3d4e5f60718", "${FLOW_DEVICE}_${t0 - 120_000L}"), cards.map { it.key }.toSet())
        assertTrue(cards.all { it.cameraName == FLOW_CAMERA })
        // END_BLOCK_FEED_LOADED

        // START_BLOCK_TAP_CARDS
        val vm = ClipViewModel(RepositoryClipSource(repo, db.eventDao()), FlowCameras(), { zone })
        for (card in cards) {
            vm.open(card.key)
            val ui = vm.ui.first { it != null && it.state !is ClipState.Loading }
            assertNotNull(ui)
            val state = ui!!.state
            assertTrue(
                "clip ready, was $state; log: " + recorder.bufferText().lines().filter { "BLOCK_CLIP_LOAD" in it },
                state is ClipState.Ready,
            )
            assertArrayEquals(mp4, (state as ClipState.Ready).file.readBytes())
            assertEquals(FLOW_CAMERA, ui.cameraName)
            assertTrue(ui.sdEnabled)
            assertEquals(ClipTexts.OPEN_SD, ui.sdLabel)
            vm.dismiss()
        }
        // END_BLOCK_TAP_CARDS
        assertFalse(recorder.bufferText().contains("[Events][clip][BLOCK_CLIP_LOAD] error="))
        assertEquals(2, fetcher.calls.count { it.contains("/v/") })
    }
}
