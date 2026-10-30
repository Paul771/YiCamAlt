// FILE: LiveViewModelTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Executable contract of the live view model: badge table (D-04), auto-connect, retry / connect, sound
//     (D-05, D-07), HD/SD (D-06, D-07), snapshot messages, lifecycle stop / resume (D-10), debug line, video size.
//   SCOPE: JVM only, virtual time; real LiveSessionSupervisor + DefaultMediaSession over LiveFakes (FakeLinkFactory,
//     FakeNetworkMonitor, FakeCredentialsSource), fake LiveEnvironment outputs and a fake SnapshotStore.
//   DEPENDS: M-UI-LIVEVIEW (LiveViewModel, badgeFor), M-LIVE-SESSION, LiveFakes, TraceRecorder, RedactionScanner
//   LINKS: V-M-UI-LIVEVIEW, M-UI-LIVEVIEW
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.ui.live

import android.graphics.Bitmap
import android.view.Surface
import com.yicamalt.camera.TransportType
import com.yicamalt.live.ErrorClass
import com.yicamalt.live.FakeCameraLink
import com.yicamalt.live.FakeCredentialsSource
import com.yicamalt.live.FakeLinkFactory
import com.yicamalt.live.FakeNetworkMonitor
import com.yicamalt.live.LIVE_DEVICE_ID
import com.yicamalt.live.LIVE_SECRETS
import com.yicamalt.live.SupervisorState
import com.yicamalt.live.aacUnit
import com.yicamalt.live.keyUnit
import com.yicamalt.live.pUnit
import com.yicamalt.media.AudioOutStatus
import com.yicamalt.media.DecoderStatus
import com.yicamalt.media.SnapshotResult
import com.yicamalt.media.SnapshotStore
import com.yicamalt.media.VideoSize
import com.yicamalt.stream.AacConfig
import com.yicamalt.stream.DecodableFrame
import com.yicamalt.stream.IoCtrlMessages
import com.yicamalt.stream.LinkCapabilities
import com.yicamalt.stream.LinkError
import com.yicamalt.stream.LinkErrorKind
import com.yicamalt.stream.LinkInfo
import com.yicamalt.stream.LinkMode
import com.yicamalt.stream.LinkState
import com.yicamalt.test.RedactionFixtures
import com.yicamalt.test.RedactionScanner
import com.yicamalt.test.TraceRecorder
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

// START_MODULE_MAP
//   FakeVideoOutput - LiveVideoOutput with MutableStateFlows for status / size, reset events, frame + release counts.
//   FakeAudioOutput - LiveAudioOutput counting frames and releases.
//   FakeLiveEnvironment - test dispatcher as io, scheduler clock, records created outputs.
//   FakeSnapshotStore - scripted SnapshotResult, call counter.
//   LiveViewModelTest - badge table + one test per view-model behaviour bullet of plan 03-10 Task 2.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 03-10 RED.
// END_CHANGE_SUMMARY

private class FakeVideoOutput : LiveVideoOutput {
    override val status = MutableStateFlow<DecoderStatus>(DecoderStatus.Idle)
    override val videoSize = MutableStateFlow<VideoSize?>(null)
    override val resetEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val frames = mutableListOf<DecodableFrame>()
    var releaseCalls = 0
    var attachCalls = 0
    override fun attachSurface(surface: Surface) {
        attachCalls++
    }
    override fun detachSurface() = Unit
    override fun release() {
        releaseCalls++
    }
    override fun onFrame(frame: DecodableFrame) {
        frames += frame
    }
    override fun onUnsupportedCodec(codecId: Int) = Unit
}

private class FakeAudioOutput : LiveAudioOutput {
    override val status = MutableStateFlow<AudioOutStatus>(AudioOutStatus.Idle)
    var frames = 0
    var releaseCalls = 0
    override fun release() {
        releaseCalls++
    }
    override fun onAacFrame(config: AacConfig, frame: ByteArray, ptsUs: Long) {
        frames++
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class FakeLiveEnvironment(
    private val dispatcher: CoroutineDispatcher,
    private val scheduler: TestCoroutineScheduler,
) : LiveEnvironment() {
    val videos = mutableListOf<FakeVideoOutput>()
    val audios = mutableListOf<FakeAudioOutput>()
    override val io: CoroutineDispatcher get() = dispatcher
    override fun now(): Long = scheduler.currentTime
    override fun video(): LiveVideoOutput = FakeVideoOutput().also { videos += it }
    override fun audio(): LiveAudioOutput = FakeAudioOutput().also { audios += it }
}

private class FakeSnapshotStore : SnapshotStore {
    var result: SnapshotResult = SnapshotResult.Saved
    var calls = 0
    override suspend fun save(bitmap: Bitmap): SnapshotResult {
        calls++
        return result
    }
}

private val RENDERING = DecoderStatus.Rendering(
    DecoderStatus.Ready("video/avc", "c2.synth.avc.decoder", hw = true, lowLatency = true),
    framesRendered = 25,
    fps = 24.5,
)

@OptIn(ExperimentalCoroutinesApi::class)
class LiveViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val recorder = TraceRecorder()
    private val scanner = RedactionScanner()
    private val net = FakeNetworkMonitor()
    private val creds = FakeCredentialsSource()
    private val store = FakeSnapshotStore()
    private val seenUi = mutableListOf<String>()
    private lateinit var links: FakeLinkFactory
    private lateinit var env: FakeLiveEnvironment
    private var vm: LiveViewModel? = null

    private val tnpLan = LinkInfo(TransportType.TNP, LinkMode.LAN, lan = true, connectMs = 80)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        recorder.plant()
    }

    @AfterEach
    fun tearDown() {
        vm?.closeScreen()
        val buffer = recorder.bufferText() + "\n" + seenUi.joinToString("\n")
        scanner.assertClean(buffer, extraLiterals = RedactionFixtures.ALL_SECRETS + LIVE_SECRETS)
        recorder.unplant()
        recorder.reset()
        Dispatchers.resetMain()
    }

    private fun TestScope.newVm(): LiveViewModel {
        links = FakeLinkFactory { testScheduler.currentTime }
        env = FakeLiveEnvironment(dispatcher, testScheduler)
        return LiveViewModel(creds, links, net, store, env).also { vm = it }
    }

    // runTest that closes the view model inside the test body: the per-open ticker and the supervisor loops share
    // the test scheduler, so an open view model would keep runTest's final drain advancing virtual time forever.
    private fun vmTest(body: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try {
            body()
        } finally {
            vm?.closeScreen()
            runCurrent()
        }
    }

    private fun LiveViewModel.snap(): LiveViewUi = ui.value.also { seenUi += it.toString() }

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private val link: FakeCameraLink get() = links.links.last()

    // Opens the screen and brings the first link to Streaming.
    private fun TestScope.liveVm(): LiveViewModel {
        val v = newVm()
        v.open(LIVE_DEVICE_ID, "Кухня")
        runCurrent()
        link.flow.value = LinkState.Streaming(tnpLan, authOk = true)
        runCurrent()
        return v
    }

    // START_BLOCK_BADGE_TABLE
    @Test
    fun `badgeFor connecting and waiting`() {
        assertEquals(LiveBadge("Подключение…", BadgeTone.NEUTRAL), badgeFor(SupervisorState.Connecting(1)))
        assertEquals(LiveBadge("Повтор (3/5)", BadgeTone.WARN), badgeFor(SupervisorState.Connecting(3)))
        assertEquals(
            LiveBadge("Подключение…", BadgeTone.NEUTRAL),
            badgeFor(SupervisorState.Waiting(1, 2_000, ErrorClass.WRONG_PASSWORD, null)),
        )
        assertEquals(
            LiveBadge("Подключение…", BadgeTone.NEUTRAL),
            badgeFor(SupervisorState.Waiting(1, 10_000, ErrorClass.KICK, null)),
        )
        assertEquals(
            LiveBadge("Повтор (4/5)", BadgeTone.WARN),
            badgeFor(SupervisorState.Waiting(4, 10_000, ErrorClass.NETWORK, LinkError(LinkErrorKind.TIMEOUT, -1))),
        )
    }

    @Test
    fun `badgeFor live modes`() {
        fun live(mode: LinkMode, lan: Boolean) =
            badgeFor(SupervisorState.Live(LinkInfo(TransportType.TNP, mode, lan, 10), authOk = true))
        for (mode in LinkMode.entries) assertEquals(LiveBadge("LAN", BadgeTone.GOOD), live(mode, true), mode.name)
        assertEquals(LiveBadge("LAN", BadgeTone.GOOD), live(LinkMode.LAN, false))
        assertEquals(LiveBadge("P2P", BadgeTone.GOOD), live(LinkMode.P2P, false))
        assertEquals(LiveBadge("Relay", BadgeTone.GOOD), live(LinkMode.RELAY, false))
        assertEquals(LiveBadge("Relay", BadgeTone.GOOD), live(LinkMode.TCP_RELAY, false))
        assertEquals(LiveBadge("Relay", BadgeTone.GOOD), live(LinkMode.SDEV_RELAY, false))
        assertEquals(LiveBadge("P2P?", BadgeTone.GOOD), live(LinkMode.UNKNOWN, false))
    }

    @Test
    fun `badgeFor final and idle states`() {
        assertEquals(LiveBadge("Офлайн", BadgeTone.ERROR), badgeFor(SupervisorState.NoNetwork))
        assertEquals("Офлайн", badgeFor(SupervisorState.Exhausted(LinkError(LinkErrorKind.OFFLINE, -3))).text)
        assertEquals("Нет связи", badgeFor(SupervisorState.Exhausted(LinkError(LinkErrorKind.TIMEOUT, -3))).text)
        assertEquals("Нет связи", badgeFor(SupervisorState.Exhausted(null)).text)
        assertEquals("Ошибка пароля", badgeFor(SupervisorState.AuthFailed).text)
        assertEquals("Камера занята", badgeFor(SupervisorState.Kicked).text)
        assertEquals("Ошибка", badgeFor(SupervisorState.Fatal(LinkError(LinkErrorKind.NATIVE_UNAVAILABLE, 0))).text)
        assertEquals(LiveBadge("Не подключено", BadgeTone.NEUTRAL), badgeFor(SupervisorState.Idle))
        for (r in listOf("user", "screen-left", "background")) {
            assertEquals(LiveBadge("Не подключено", BadgeTone.NEUTRAL), badgeFor(SupervisorState.Stopped(r)))
        }
    }
    // END_BLOCK_BADGE_TABLE

    // START_BLOCK_OPEN_AND_RETRY
    @Test
    fun `open connects without any user action`() = vmTest {
        val v = newVm()
        v.open(LIVE_DEVICE_ID, "Кухня")
        runCurrent()
        assertEquals(1, links.opens)
        val ui = v.snap()
        assertEquals("Кухня", ui.title)
        assertEquals("Подключение…", ui.badge.text)
        assertFalse(ui.showConnect)
        assertFalse(ui.showRetry)
        recorder.assertMarkerAppeared("BLOCK_OPEN_LIVE")
        link.flow.value = LinkState.Streaming(tnpLan, authOk = true)
        runCurrent()
        assertEquals(LiveBadge("LAN", BadgeTone.GOOD), v.snap().badge)
        recorder.assertMarkerAppeared("BLOCK_BADGE")
    }

    @Test
    fun `re-open of the same camera keeps the session, another camera restarts`() = vmTest {
        val v = liveVm()
        v.open(LIVE_DEVICE_ID, "Кухня")
        runCurrent()
        assertEquals(1, links.opens)
        assertEquals("LAN", v.snap().badge.text)
        v.open("cam-OTHER-SYNTH", "Двор")
        runCurrent()
        assertTrue(links.links[0].closeReasons.contains("screen-left"))
        assertEquals(1, env.videos[0].releaseCalls)
        assertEquals(2, env.videos.size)
        assertEquals("Двор", v.snap().title)
    }

    @Test
    fun `fatal shows retry with the error text and retry opens again`() = vmTest {
        val v = newVm()
        links.defaultInitial = LinkState.Failed(LinkError(LinkErrorKind.NATIVE_UNAVAILABLE, 0))
        v.open(LIVE_DEVICE_ID, "Кухня")
        runCurrent()
        val ui = v.snap()
        assertEquals("Ошибка", ui.badge.text)
        assertTrue(ui.showRetry)
        assertFalse(ui.showConnect)
        assertEquals("Нативные библиотеки не загружены", ui.message)
        v.retry()
        runCurrent()
        assertEquals(2, links.opens)
    }

    @Test
    fun `auth failed, kicked and exhausted show retry`() = vmTest {
        val v = newVm()
        links.defaultInitial = LinkState.Failed(LinkError(LinkErrorKind.WRONG_PASSWORD, -1))
        v.open(LIVE_DEVICE_ID, "Кухня")
        advance(3_000)
        assertEquals("Ошибка пароля", v.snap().badge.text)
        assertTrue(v.snap().showRetry)
        assertEquals("Неверный пароль камеры", v.snap().message)

        links.defaultInitial = LinkState.Failed(LinkError(LinkErrorKind.KICKED, -1))
        v.retry()
        advance(11_000)
        assertEquals("Камера занята", v.snap().badge.text)
        assertTrue(v.snap().showRetry)
        assertEquals(LiveProbeTexts.KICKED, v.snap().message)

        links.defaultInitial = LinkState.Failed(LinkError(LinkErrorKind.OFFLINE, -3))
        v.retry()
        runCurrent()
        assertEquals("Повтор (2/5)", v.snap().badge.text)
        assertEquals(BadgeTone.WARN, v.snap().badge.tone)
        advance(40_000)
        assertEquals("Офлайн", v.snap().badge.text)
        assertTrue(v.snap().showRetry)
    }

    @Test
    fun `idle and stopped show connect and connect opens`() = vmTest {
        val fresh = newVm()
        assertTrue(fresh.snap().showConnect)
        assertEquals("Не подключено", fresh.snap().badge.text)
        fresh.connect()
        runCurrent()
        assertEquals(0, links.opens)

        val v = liveVm()
        v.onStop()
        runCurrent()
        assertTrue(v.snap().showConnect)
        assertFalse(v.snap().showRetry)
        v.connect()
        runCurrent()
        assertEquals(2, links.opens)
        assertFalse(v.snap().showConnect)
    }
    // END_BLOCK_OPEN_AND_RETRY

    // START_BLOCK_SOUND
    @Test
    fun `muted after open, sound shown once audio was recognised`() = vmTest {
        val v = liveVm()
        assertTrue(v.snap().muted)
        advance(1_000)
        assertFalse(v.snap().soundVisible)
        link.audioSinkSet!!.onAudioUnit(aacUnit())
        advance(1_000)
        assertTrue(v.snap().soundVisible)
        assertTrue(v.snap().muted)
        assertTrue(env.audios.isEmpty())
    }

    @Test
    fun `toggleMute creates the audio output once and releases it on mute`() = vmTest {
        val v = liveVm()
        v.toggleMute()
        runCurrent()
        assertEquals(1, env.audios.size)
        assertFalse(v.snap().muted)
        v.toggleMute()
        runCurrent()
        assertEquals(1, env.audios.size)
        assertEquals(1, env.audios[0].releaseCalls)
        assertTrue(v.snap().muted)
        recorder.assertMarkerAppeared("BLOCK_TOGGLE_AUDIO")
    }

    @Test
    fun `unmute reaches the audio output`() = vmTest {
        val v = liveVm()
        v.toggleMute()
        link.audioSinkSet!!.onAudioUnit(aacUnit())
        assertTrue(env.audios[0].frames >= 1)
        val before = env.audios.sumOf { it.frames }
        v.toggleMute()
        link.audioSinkSet!!.onAudioUnit(aacUnit())
        assertEquals(before, env.audios.sumOf { it.frames })
    }

    @Test
    fun `reconnect after unmute keeps sound on`() = vmTest {
        val v = liveVm()
        v.toggleMute()
        link.flow.value = LinkState.Failed(LinkError(LinkErrorKind.TIMEOUT, -1))
        advance(3_000)
        assertEquals(2, links.opens)
        link.audioSinkSet!!.onAudioUnit(aacUnit())
        assertEquals(1, env.audios.size)
        assertTrue(env.audios[0].frames >= 1)
        assertFalse(v.snap().muted)
    }

    @Test
    fun `lifecycle calls before open are no-ops`() = vmTest {
        val v = newVm()
        v.onStart()
        v.onStop()
        v.toggleMute()
        v.toggleQuality()
        runCurrent()
        assertEquals(0, links.opens)
        assertTrue(v.snap().muted)
        assertTrue(env.audios.isEmpty())
    }

    @Test
    fun `onStop mutes and releases the audio output, onStart keeps it muted`() = vmTest {
        val v = liveVm()
        v.toggleMute()
        runCurrent()
        assertFalse(v.snap().muted)
        v.onStop()
        runCurrent()
        assertEquals(1, env.audios[0].releaseCalls)
        assertTrue(v.snap().muted)
        advance(60_000)
        v.onStart()
        runCurrent()
        assertEquals(2, links.opens)
        assertTrue(v.snap().muted)
        assertEquals(1, env.audios.size)
    }
    // END_BLOCK_SOUND

    // START_BLOCK_QUALITY
    @Test
    fun `quality shown after the first key frame and toggle asks for SD`() = vmTest {
        val v = liveVm()
        assertFalse(v.snap().qualityVisible)
        link.videoSink!!.onVideoUnit(keyUnit(seq = 1, width = 1920, height = 1080))
        advance(1_000)
        val ui = v.snap()
        assertTrue(ui.qualityVisible)
        assertEquals("HD", ui.qualityLabel)
        v.toggleQuality()
        runCurrent()
        assertEquals(IoCtrlMessages.RESOLUTION_SD, link.resolutions.last())
        assertTrue(v.snap().qualityPending)
        recorder.assertMarkerAppeared("BLOCK_QUALITY_SWITCH")
    }

    @Test
    fun `quality hidden when the link cannot switch`() = vmTest {
        val v = liveVm()
        link.videoSink!!.onVideoUnit(keyUnit(seq = 1))
        advance(1_000)
        assertTrue(v.snap().qualityVisible)
        link.capabilitiesFlow.value = LinkCapabilities(resolutionSwitch = false)
        runCurrent()
        assertFalse(v.snap().qualityVisible)
    }
    // END_BLOCK_QUALITY

    // START_BLOCK_SNAPSHOT_TESTS
    @Test
    fun `snapshot messages`() = vmTest {
        val v = liveVm()
        val events = mutableListOf<LiveEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { v.events.collect { events += it } }

        v.saveSnapshot(null)
        runCurrent()
        assertEquals(LiveEvent.Toast("Нет кадра для снимка"), events.last())
        assertEquals(0, store.calls)

        val bitmap = mockk<Bitmap>(relaxed = true)
        v.saveSnapshot(bitmap)
        runCurrent()
        assertEquals(LiveEvent.Toast("Нет кадра для снимка"), events.last())
        assertEquals(0, store.calls)

        env.videos[0].status.value = RENDERING
        runCurrent()
        store.result = SnapshotResult.Saved
        v.saveSnapshot(bitmap)
        runCurrent()
        assertEquals(LiveEvent.Toast("Снимок сохранён в галерею"), events.last())
        store.result = SnapshotResult.PermissionDenied
        v.saveSnapshot(bitmap)
        runCurrent()
        assertEquals(LiveEvent.Toast("Нет разрешения на запись в галерею"), events.last())
        store.result = SnapshotResult.WriteFailed("IOException")
        v.saveSnapshot(bitmap)
        runCurrent()
        assertEquals(LiveEvent.Toast("Не удалось сохранить снимок"), events.last())
        assertEquals(3, store.calls)
        recorder.assertMarkerAppeared("BLOCK_SNAPSHOT")
    }
    // END_BLOCK_SNAPSHOT_TESTS

    // START_BLOCK_LIFECYCLE_TESTS
    @Test
    fun `background stops the link and a return within 5 minutes resumes`() = vmTest {
        val v = liveVm()
        v.onStop()
        runCurrent()
        assertTrue(links.links[0].closeReasons.contains("background"))
        assertEquals("Не подключено", v.snap().badge.text)
        advance(240_000)
        v.onStart()
        runCurrent()
        assertEquals(2, links.opens)
        recorder.assertMarkerAppeared("BLOCK_LIFECYCLE")
    }

    @Test
    fun `return after 6 minutes does not resume`() = vmTest {
        val v = liveVm()
        v.onStop()
        runCurrent()
        advance(360_000)
        v.onStart()
        runCurrent()
        assertEquals(1, links.opens)
        assertTrue(v.snap().showConnect)
    }

    @Test
    fun `closeScreen stops with screen-left and releases the decoder`() = vmTest {
        val v = liveVm()
        v.closeScreen()
        runCurrent()
        assertTrue(links.links[0].closeReasons.contains("screen-left"))
        assertEquals(1, env.videos[0].releaseCalls)
        advance(600_000)
        v.onStart()
        v.connect()
        runCurrent()
        assertEquals(1, links.opens)
    }
    // END_BLOCK_LIFECYCLE_TESTS

    // START_BLOCK_DEBUG_AND_VIDEO
    @Test
    fun `debug line is value-free and toggles`() = vmTest {
        val v = liveVm()
        assertFalse(v.snap().debugExpanded)
        link.videoSink!!.onVideoUnit(keyUnit(seq = 1))
        link.videoSink!!.onVideoUnit(pUnit(seq = 2))
        env.videos[0].status.value = RENDERING
        advance(1_000)
        v.toggleDebug()
        runCurrent()
        val ui = v.snap()
        assertTrue(ui.debugExpanded)
        for (field in listOf("link=", "mode=", "decoder=", "frames=", "fps=", "auth=", "audio=")) {
            assertTrue(ui.debugLine.contains(field), "$field in ${ui.debugLine}")
        }
        assertTrue(ui.debugLine.contains("fps=24.5"), ui.debugLine)
        assertTrue(ui.debugLine.contains("auth=ok"), ui.debugLine)
        scanner.assertClean(ui.debugLine, extraLiterals = LIVE_SECRETS)
        scanner.assertClean(ui.toString(), extraLiterals = LIVE_SECRETS)
        v.toggleDebug()
        runCurrent()
        assertFalse(v.snap().debugExpanded)
    }

    @Test
    fun `video size, aspect and rendering come from the video output`() = vmTest {
        val v = liveVm()
        assertNull(v.snap().videoWidth)
        assertNull(v.snap().videoHeight)
        assertNull(v.snap().videoAspect)
        assertFalse(v.snap().videoRendering)
        env.videos[0].videoSize.value = VideoSize(1920, 1080)
        env.videos[0].status.value = RENDERING
        runCurrent()
        val ui = v.snap()
        assertEquals(1920, ui.videoWidth)
        assertEquals(1080, ui.videoHeight)
        assertEquals(1920f / 1080f, ui.videoAspect!!, 0.001f)
        assertTrue(ui.videoRendering)
    }

    @Test
    fun `decoder reset re-arms the media session pipeline`() = vmTest {
        liveVm()
        link.videoSink!!.onVideoUnit(keyUnit(seq = 1))
        assertEquals(1, env.videos[0].frames.size)
        env.videos[0].resetEvents.tryEmit(Unit)
        runCurrent()
        link.videoSink!!.onVideoUnit(pUnit(seq = 2))
        assertEquals(1, env.videos[0].frames.size)
        link.videoSink!!.onVideoUnit(keyUnit(seq = 3))
        assertEquals(2, env.videos[0].frames.size)
    }
    // END_BLOCK_DEBUG_AND_VIDEO
}
