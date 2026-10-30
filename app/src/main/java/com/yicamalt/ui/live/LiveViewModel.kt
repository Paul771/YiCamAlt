// FILE: LiveViewModel.kt
// VERSION: 0.3.0
// START_MODULE_CONTRACT
//   PURPOSE: Screen state of the live view over LiveSessionSupervisor (M-UI-LIVEVIEW view model).
//   SCOPE: badge mapping (D-04), auto-connect on open, sound (D-05, D-07), HD/SD (D-06, D-07), snapshot, debug line,
//     lifecycle stop / resume (D-10), decoder / audio outputs behind injectable ports; Phase 4 archive state and
//     actions over ArchiveCoordinator (SD-01..SD-05, D-01..D-07).
//   DEPENDS: M-LIVE-SESSION, M-SD-ARCHIVE (via ArchiveCoordinator), M-MEDIA-RENDER, M-MEDIA-AUDIO, M-MEDIA-SNAPSHOT, M-P2P-CREDS, M-STREAM-CORE
//   LINKS: M-UI-LIVEVIEW, V-M-UI-LIVEVIEW
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.ui.live

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yicamalt.archive.SdTime
import com.yicamalt.camera.TransportType
import com.yicamalt.live.DefaultMediaSession
import com.yicamalt.live.ErrorClass
import com.yicamalt.live.LiveSessionSupervisor
import com.yicamalt.live.MediaSessionFactory
import com.yicamalt.live.NetworkMonitor
import com.yicamalt.live.Quality
import com.yicamalt.live.QualityState
import com.yicamalt.live.SupervisorState
import com.yicamalt.media.AacDecoder
import com.yicamalt.media.AudioOutStatus
import com.yicamalt.media.DecoderStatus
import com.yicamalt.media.SnapshotResult
import com.yicamalt.media.SnapshotStore
import com.yicamalt.media.VideoDecoder
import com.yicamalt.media.VideoSize
import com.yicamalt.stream.AacConfig
import com.yicamalt.stream.AacFrameSink
import com.yicamalt.stream.CameraLinkFactory
import com.yicamalt.stream.DecodableFrame
import com.yicamalt.stream.DecodableFrameSink
import com.yicamalt.stream.LinkError
import com.yicamalt.stream.LinkErrorKind
import com.yicamalt.stream.LinkMode
import com.yicamalt.stream.LinkState
import com.yicamalt.stream.P2pCredentialsSource
import com.yicamalt.stream.VideoCodec
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject

// START_MODULE_MAP
//   LiveVideoOutput - video decoder port (status, videoSize, resetEvents, surface attach / detach, release).
//   LiveAudioOutput - AAC output port (status, release).
//   LiveEnvironment - injectable io dispatcher, clock, wall clock, display zone (the only systemDefault read) and
//     output factories.
//   BadgeTone / LiveBadge / badgeFor - SupervisorState -> badge (D-04).
//   LiveViewUi - value-free screen snapshot.
//   LiveEvent - one-shot messages (Toast).
//   LiveViewTexts - Russian badge and snapshot texts.
//   LiveViewModel - open / closeScreen / onStop / onStart / connect / retry / toggleMute / toggleQuality /
//     toggleDebug / attachSurface / detachSurface / saveSnapshot; archive StateFlow + archiveSeek /
//     archiveTogglePause / archiveGoLive / archiveRetry / archiveOpenDays / archiveCloseDays / archiveSelectDay.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.3.0 - GSD plan 04-06: archive state + actions (D-01..D-07). One ArchiveCoordinator per open,
//     over sup.archivePort(transport, p2pEncrypt), in the per-open scope; forceMute = mute + release audio (D-05);
//     onStop pauses archive playback as Paused(BACKGROUND) before the background stop; closeScreen closes the
//     coordinator; the quality button is hidden while in the archive.
//   PREVIOUS: v0.2.0 - GSD plan 03-10 GREEN. One supervisor + one video output + one per-open scope per open();
//     re-open of the same camera while a supervisor exists only updates the title (rotation / recomposition keeps the
//     session), another camera closes the screen first. INVARIANTS: muted on every open and after onStop (D-05); the
//     session factory reads the current muted value, so a reconnect after unmute keeps sound on; the VM-owned
//     AudioSwitch forwards AAC frames to the current audio output (null while muted); onStart / onStop / toggle*
//     are no-ops while there is no supervisor; VideoDecoder.resetEvents re-arm the current session's pipeline.
//     LiveViewUi, its toString, the debug line and logs carry no device id, DID or password.
//   PREVIOUS: v0.1.0 - GSD plan 03-10 RED: types and signatures; behaviour stubs.
// END_CHANGE_SUMMARY

interface LiveVideoOutput : DecodableFrameSink {
    val status: StateFlow<DecoderStatus>
    val videoSize: StateFlow<VideoSize?>
    val resetEvents: SharedFlow<Unit>
    fun attachSurface(surface: Surface)
    fun detachSurface()
    fun release()
}

interface LiveAudioOutput : AacFrameSink {
    val status: StateFlow<AudioOutStatus>
    fun release()
}

internal class VideoDecoderOutput(private val decoder: VideoDecoder) : LiveVideoOutput {
    override val status: StateFlow<DecoderStatus> get() = decoder.status
    override val videoSize: StateFlow<VideoSize?> get() = decoder.videoSize
    override val resetEvents: SharedFlow<Unit> get() = decoder.resetEvents
    override fun attachSurface(surface: Surface) = decoder.attachSurface(surface)
    override fun detachSurface() = decoder.detachSurface()
    override fun release() = decoder.release()
    override fun onFrame(frame: DecodableFrame) = decoder.onFrame(frame)
    override fun onUnsupportedCodec(codecId: Int) = decoder.onUnsupportedCodec(codecId)
}

internal class AacDecoderOutput(private val decoder: AacDecoder) : LiveAudioOutput {
    override val status: StateFlow<AudioOutStatus> get() = decoder.status
    override fun release() = decoder.release()
    override fun onAacFrame(config: AacConfig, frame: ByteArray, ptsUs: Long) = decoder.onAacFrame(config, frame, ptsUs)
}

open class LiveEnvironment @Inject constructor() {
    open val io: CoroutineDispatcher get() = Dispatchers.IO
    open fun now(): Long = SystemClock.elapsedRealtime()
    open fun video(): LiveVideoOutput = VideoDecoderOutput(VideoDecoder())
    open fun audio(): LiveAudioOutput = AacDecoderOutput(AacDecoder())
    open fun wallNow(): Long = System.currentTimeMillis()
    open fun zone(): ZoneId = ZoneId.systemDefault()
}

enum class BadgeTone { NEUTRAL, GOOD, WARN, ERROR }

data class LiveBadge(val text: String, val tone: BadgeTone)

data class LiveViewUi(
    val title: String,
    val badge: LiveBadge,
    val showRetry: Boolean,
    val showConnect: Boolean,
    val message: String?,
    val soundVisible: Boolean,
    val muted: Boolean,
    val qualityVisible: Boolean,
    val qualityLabel: String?,
    val qualityPending: Boolean,
    val debugExpanded: Boolean,
    val debugLine: String,
    val videoAspect: Float?,
    val videoWidth: Int?,
    val videoHeight: Int?,
    val videoRendering: Boolean,
)

sealed interface LiveEvent {
    data class Toast(val text: String) : LiveEvent
}

object LiveViewTexts {
    const val DEFAULT_TITLE = "Камера"
    const val CONNECTING = "Подключение…"
    const val OFFLINE = "Офлайн"
    const val NO_LINK = "Нет связи"
    const val AUTH = "Ошибка пароля"
    const val BUSY = "Камера занята"
    const val ERROR = "Ошибка"
    const val NOT_CONNECTED = "Не подключено"
    const val SNAPSHOT_SAVED = "Снимок сохранён в галерею"
    const val SNAPSHOT_NOT_READY = "Нет кадра для снимка"
    const val SNAPSHOT_DENIED = "Нет разрешения на запись в галерею"
    const val SNAPSHOT_FAILED = "Не удалось сохранить снимок"
    fun retry(n: Int) = "Повтор ($n/5)"
}

// START_CONTRACT: badgeFor
//   PURPOSE: Connection badge for one supervisor state (D-04).
//   INPUTS: { state: SupervisorState }
//   OUTPUTS: { LiveBadge - text + tone }
//   SIDE_EFFECTS: none
//   LINKS: M-UI-LIVEVIEW, M-LIVE-SESSION
// END_CONTRACT: badgeFor
fun badgeFor(state: SupervisorState): LiveBadge = when (state) {
    SupervisorState.Idle, is SupervisorState.Stopped -> LiveBadge(LiveViewTexts.NOT_CONNECTED, BadgeTone.NEUTRAL)
    is SupervisorState.Connecting -> attemptBadge(state.attempt)
    // A password refresh or a kick pause is not a network retry: shown as a plain (re)connect.
    is SupervisorState.Waiting ->
        if (state.cause == ErrorClass.WRONG_PASSWORD || state.cause == ErrorClass.KICK) {
            LiveBadge(LiveViewTexts.CONNECTING, BadgeTone.NEUTRAL)
        } else {
            attemptBadge(state.nextAttempt)
        }
    is SupervisorState.Live -> LiveBadge(modeText(state.info.mode, state.info.lan), BadgeTone.GOOD)
    SupervisorState.NoNetwork -> LiveBadge(LiveViewTexts.OFFLINE, BadgeTone.ERROR)
    is SupervisorState.Exhausted ->
        LiveBadge(
            if (state.lastError?.kind == LinkErrorKind.OFFLINE) LiveViewTexts.OFFLINE else LiveViewTexts.NO_LINK,
            BadgeTone.ERROR,
        )
    SupervisorState.AuthFailed -> LiveBadge(LiveViewTexts.AUTH, BadgeTone.ERROR)
    SupervisorState.Kicked -> LiveBadge(LiveViewTexts.BUSY, BadgeTone.ERROR)
    is SupervisorState.Fatal -> LiveBadge(LiveViewTexts.ERROR, BadgeTone.ERROR)
}

private fun attemptBadge(attempt: Int): LiveBadge =
    if (attempt <= 1) LiveBadge(LiveViewTexts.CONNECTING, BadgeTone.NEUTRAL) else LiveBadge(LiveViewTexts.retry(attempt), BadgeTone.WARN)

private fun modeText(mode: LinkMode, lan: Boolean): String = when {
    lan || mode == LinkMode.LAN -> "LAN"
    mode == LinkMode.P2P -> "P2P"
    mode == LinkMode.RELAY || mode == LinkMode.TCP_RELAY || mode == LinkMode.SDEV_RELAY -> "Relay"
    else -> "P2P?"
}

// Forwards AAC frames (reader thread) to the current audio output; null target while muted.
private class AudioSwitch : AacFrameSink {
    @Volatile var target: LiveAudioOutput? = null
    override fun onAacFrame(config: AacConfig, frame: ByteArray, ptsUs: Long) {
        target?.onAacFrame(config, frame, ptsUs)
    }
}

@HiltViewModel
class LiveViewModel @Inject constructor(
    private val credentials: P2pCredentialsSource,
    private val factory: CameraLinkFactory,
    private val network: NetworkMonitor,
    private val snapshots: SnapshotStore,
    private val env: LiveEnvironment,
) : ViewModel() {

    private val _ui = MutableStateFlow(initialUi(LiveViewTexts.DEFAULT_TITLE))
    val ui: StateFlow<LiveViewUi> = _ui.asStateFlow()
    private val _archive = MutableStateFlow(initialArchive())
    val archive: StateFlow<ArchiveUi> = _archive.asStateFlow()
    private var coordinator: ArchiveCoordinator? = null
    private val _events = MutableSharedFlow<LiveEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<LiveEvent> = _events.asSharedFlow()

    // Main-thread state (except the @Volatile fields, also read by the session factory / reader threads).
    private val audioSwitch = AudioSwitch()
    private var deviceId: String? = null
    private var title = LiveViewTexts.DEFAULT_TITLE
    private var transport: TransportType? = null
    private var supervisor: LiveSessionSupervisor? = null
    private var openScope: CoroutineScope? = null
    private var video: LiveVideoOutput? = null
    private var surface: Surface? = null
    @Volatile private var muted = true
    @Volatile private var current: DefaultMediaSession? = null
    private var debugExpanded = false
    private var streamingAtMs: Long? = null
    private var soundSeen = false
    private var lastBadgeText: String? = null

    // START_CONTRACT: open
    //   PURPOSE: Bind the screen to one camera and connect at once (auto-connect, D-04).
    //   INPUTS: { deviceId: String - cloud uid, never logged or shown; title: String - user-visible camera name }
    //   OUTPUTS: { Unit }
    //   SIDE_EFFECTS: new video output, per-open scope, LiveSessionSupervisor.start(); same camera while open = title
    //     only; another camera = closeScreen() first; BLOCK_OPEN_LIVE log
    //   LINKS: M-LIVE-SESSION, LiveSessionSupervisor.start
    // END_CONTRACT: open
    fun open(deviceId: String, title: String) {
        // START_BLOCK_OPEN_LIVE
        this.title = title
        if (supervisor != null && this.deviceId == deviceId) {
            refresh()
            return
        }
        if (supervisor != null) closeScreen()
        this.deviceId = deviceId
        val c = credentials.credentialsFor(deviceId)
        transport = c?.transport
        muted = true
        audioSwitch.target = null
        current = null
        streamingAtMs = null
        soundSeen = false
        val v = env.video()
        video = v
        surface?.let { v.attachSurface(it) }
        val scope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext.job))
        openScope = scope
        val sessions = MediaSessionFactory { creds ->
            DefaultMediaSession(creds, v, audioSwitch, muted = this.muted).also { current = it }
        }
        val sup = LiveSessionSupervisor(deviceId, credentials, factory, sessions, network, scope, env.io, env::now)
        supervisor = sup
        val coord = ArchiveCoordinator(
            sup.archivePort(c?.transport ?: TransportType.UNKNOWN, c?.p2pEncrypt ?: false),
            c?.transport, scope, env::now, env::wallNow, env.zone(),
            forceMute = {
                muteAndReleaseAudio()
                refresh()
            },
        )
        coordinator = coord
        _archive.value = coord.ui.value
        Timber.d("[LiveView][vm][BLOCK_OPEN_LIVE] transport=%s hasCreds=%b", c?.transport?.name ?: "-", c != null)
        scope.launch { sup.state.collect { onSupervisorState(it) } }
        scope.launch { sup.qualityState.collect { refresh() } }
        scope.launch { sup.currentLinkState.collect { refresh() } }
        scope.launch { v.status.collect { refresh() } }
        scope.launch { v.videoSize.collect { refresh() } }
        scope.launch { v.resetEvents.collect { current?.resetPipeline() } }
        scope.launch {
            while (true) {
                delay(TICK_MS)
                refresh()
            }
        }
        scope.launch {
            coord.ui.collect {
                _archive.value = it
                refresh()
            }
        }
        sup.start()
        coord.start()
        _archive.value = coord.ui.value
        refresh()
        // END_BLOCK_OPEN_LIVE
    }

    private fun onSupervisorState(s: SupervisorState) {
        if (s is SupervisorState.Live) {
            if (streamingAtMs == null) streamingAtMs = env.now()
        } else {
            streamingAtMs = null
        }
        refresh()
    }

    // START_CONTRACT: closeScreen
    //   PURPOSE: The user left the screen: stop the session, free the decoders; idempotent.
    //   INPUTS: none
    //   OUTPUTS: { Unit }
    //   SIDE_EFFECTS: supervisor.stop("screen-left"), audio + video outputs released, per-open scope cancelled;
    //     BLOCK_LIFECYCLE log
    //   LINKS: LiveSessionSupervisor.stop
    // END_CONTRACT: closeScreen
    fun closeScreen() {
        // START_BLOCK_LIFECYCLE
        val sup = supervisor ?: return
        Timber.d("[LiveView][vm][BLOCK_LIFECYCLE] event=screen-left")
        supervisor = null
        coordinator?.close()
        coordinator = null
        sup.stop(REASON_SCREEN_LEFT)
        muteAndReleaseAudio()
        current = null
        openScope?.cancel()
        openScope = null
        video?.release()
        video = null
        streamingAtMs = null
        _archive.value = initialArchive()
        refresh()
        // END_BLOCK_LIFECYCLE
    }

    override fun onCleared() {
        closeScreen()
        super.onCleared()
    }

    // START_CONTRACT: onStop
    //   PURPOSE: App went to background (D-10, LIVE-10): mute, free the audio output, stop the session now.
    //   INPUTS: none
    //   OUTPUTS: { Unit }
    //   SIDE_EFFECTS: supervisor.stop("background"); no-op before open / after closeScreen; BLOCK_LIFECYCLE log
    //   LINKS: LiveSessionSupervisor.stop, ResumePolicy
    // END_CONTRACT: onStop
    fun onStop() {
        val sup = supervisor ?: return
        Timber.d("[LiveView][vm][BLOCK_LIFECYCLE] event=background")
        muteAndReleaseAudio()
        coordinator?.onBackground()
        sup.stop(REASON_BACKGROUND)
        refresh()
    }

    // START_CONTRACT: onStart
    //   PURPOSE: Back in foreground: resume within the window (D-10), otherwise "Подключить" stays.
    //   INPUTS: none
    //   OUTPUTS: { Unit }
    //   SIDE_EFFECTS: supervisor.onForeground(); no-op before open / after closeScreen; BLOCK_LIFECYCLE log
    //   LINKS: LiveSessionSupervisor.onForeground
    // END_CONTRACT: onStart
    fun onStart() {
        val sup = supervisor ?: return
        val resumed = sup.onForeground()
        Timber.d("[LiveView][vm][BLOCK_LIFECYCLE] event=foreground resumed=%b", resumed)
        refresh()
    }

    fun connect() {
        supervisor?.start() ?: return
        refresh()
    }

    fun retry() {
        supervisor?.retry() ?: return
        refresh()
    }

    // START_CONTRACT: toggleMute
    //   PURPOSE: Local sound toggle (D-05): unmute creates the AAC output, mute releases it.
    //   INPUTS: none
    //   OUTPUTS: { Unit }
    //   SIDE_EFFECTS: env.audio() / LiveAudioOutput.release(); current session mute gate; BLOCK_TOGGLE_AUDIO log;
    //     no-op before open / after closeScreen
    //   LINKS: M-MEDIA-AUDIO, DefaultMediaSession.setMuted
    // END_CONTRACT: toggleMute
    fun toggleMute() {
        // START_BLOCK_TOGGLE_AUDIO
        if (supervisor == null) return
        if (muted) {
            muted = false
            if (audioSwitch.target == null) audioSwitch.target = env.audio()
            current?.setMuted(false)
        } else {
            muteAndReleaseAudio()
        }
        Timber.d("[LiveView][vm][BLOCK_TOGGLE_AUDIO] muted=%b", muted)
        refresh()
        // END_BLOCK_TOGGLE_AUDIO
    }

    private fun muteAndReleaseAudio() {
        muted = true
        current?.setMuted(true)
        val out = audioSwitch.target
        audioSwitch.target = null
        out?.release()
    }

    // START_CONTRACT: toggleQuality
    //   PURPOSE: Ask for the other quality in the same session (D-06, D-07).
    //   INPUTS: none
    //   OUTPUTS: { Unit }
    //   SIDE_EFFECTS: supervisor.setQuality; BLOCK_QUALITY_SWITCH log; no-op before open / after closeScreen
    //   LINKS: LiveSessionSupervisor.setQuality
    // END_CONTRACT: toggleQuality
    fun toggleQuality() {
        // START_BLOCK_QUALITY_SWITCH
        val sup = supervisor ?: return
        val target = if (sup.qualityState.value.observed == Quality.HD) Quality.SD else Quality.HD
        Timber.d("[LiveView][vm][BLOCK_QUALITY_SWITCH] target=%s", target.name)
        sup.setQuality(target)
        refresh()
        // END_BLOCK_QUALITY_SWITCH
    }

    // START_CONTRACT: archiveSeek
    //   PURPOSE: Archive actions of the camera screen (D-01): seek, pause / continue, live, retry, days dialog, day.
    //   INPUTS: { ms: Long - timeline instant (archiveSeek); day: LocalDate (archiveSelectDay) }
    //   OUTPUTS: { Unit }
    //   SIDE_EFFECTS: ArchiveCoordinator call; archive state + screen refresh; BLOCK_ARCHIVE_ACTION log; no-op without
    //     an open screen
    //   LINKS: ArchiveCoordinator
    // END_CONTRACT: archiveSeek
    fun archiveSeek(ms: Long) = archiveAction("seek") { it.seek(ms) }
    fun archiveTogglePause() = archiveAction("toggle-pause") { it.togglePause() }
    fun archiveGoLive() = archiveAction("live") { it.goLive() }
    fun archiveRetry() = archiveAction("retry") { it.retry() }
    fun archiveOpenDays() = archiveAction("open-days") { it.openDays() }
    fun archiveCloseDays() = archiveAction("close-days") { it.closeDays() }
    fun archiveSelectDay(day: LocalDate) = archiveAction("select-day") { it.selectDay(day) }

    private inline fun archiveAction(name: String, block: (ArchiveCoordinator) -> Unit) {
        // START_BLOCK_ARCHIVE_ACTION
        val c = coordinator ?: return
        Timber.d("[LiveView][vm][BLOCK_ARCHIVE_ACTION] action=%s", name)
        block(c)
        _archive.value = c.ui.value
        refresh()
        // END_BLOCK_ARCHIVE_ACTION
    }

    private fun initialArchive(): ArchiveUi {
        val zone = env.zone()
        val today = SdTime.localDate(env.wallNow(), zone)
        val bounds = SdTime.dayBounds(today, zone)
        return ArchiveUi.initial(today, bounds.first, bounds.last + 1)
    }

    fun toggleDebug() {
        debugExpanded = !debugExpanded
        refresh()
    }

    fun attachSurface(surface: Surface) {
        this.surface = surface
        video?.attachSurface(surface)
    }

    fun detachSurface() {
        surface = null
        video?.detachSurface()
    }

    // START_CONTRACT: saveSnapshot
    //   PURPOSE: Save the current frame (LIVE-08); every outcome becomes a short Toast.
    //   INPUTS: { bitmap: Bitmap? - frame grabbed by the screen; null when nothing is shown }
    //   OUTPUTS: { Unit }
    //   SIDE_EFFECTS: SnapshotStore.save on env.io (only while rendering and bitmap != null); LiveEvent.Toast;
    //     BLOCK_SNAPSHOT log
    //   LINKS: M-MEDIA-SNAPSHOT, SnapshotStore.save
    // END_CONTRACT: saveSnapshot
    fun saveSnapshot(bitmap: Bitmap?) {
        // START_BLOCK_SNAPSHOT
        val rendering = video?.status?.value is DecoderStatus.Rendering
        if (bitmap == null || !rendering) {
            Timber.d("[LiveView][vm][BLOCK_SNAPSHOT] result=not-ready")
            _events.tryEmit(LiveEvent.Toast(LiveViewTexts.SNAPSHOT_NOT_READY))
            return
        }
        viewModelScope.launch {
            val r = withContext(env.io) { snapshots.save(bitmap) }
            val (name, text) = when (r) {
                SnapshotResult.Saved -> "saved" to LiveViewTexts.SNAPSHOT_SAVED
                SnapshotResult.NotReady -> "not-ready" to LiveViewTexts.SNAPSHOT_NOT_READY
                SnapshotResult.PermissionDenied -> "permission-denied" to LiveViewTexts.SNAPSHOT_DENIED
                is SnapshotResult.WriteFailed -> "write-failed" to LiveViewTexts.SNAPSHOT_FAILED
            }
            Timber.d("[LiveView][vm][BLOCK_SNAPSHOT] result=%s", name)
            _events.tryEmit(LiveEvent.Toast(text))
        }
        // END_BLOCK_SNAPSHOT
    }

    // START_BLOCK_REFRESH
    private fun refresh() {
        val sup = supervisor
        val state = sup?.state?.value ?: SupervisorState.Idle
        val quality = sup?.qualityState?.value ?: QualityState()
        val v = video
        val decoder = v?.status?.value ?: DecoderStatus.Idle
        val size = v?.videoSize?.value
        val session = current
        val live = state is SupervisorState.Live

        // START_BLOCK_BADGE
        val badge = badgeFor(state)
        if (badge.text != lastBadgeText) {
            lastBadgeText = badge.text
            Timber.d("[LiveView][vm][BLOCK_BADGE] state=%s tone=%s", stateName(state), badge.tone.name)
        }
        // END_BLOCK_BADGE

        val availability = if (live && session != null) session.audioAvailability(streamingAtMs, env.now()) else null
        if (availability == true) soundSeen = true
        val decoderProblem = when (decoder) {
            is DecoderStatus.NoDecoder, is DecoderStatus.Unsupported, is DecoderStatus.Failed -> decoder.userText()
            else -> null
        }
        _ui.value = LiveViewUi(
            title = title,
            badge = badge,
            showRetry = state is SupervisorState.Exhausted || state is SupervisorState.AuthFailed ||
                state is SupervisorState.Kicked || state is SupervisorState.Fatal,
            showConnect = state is SupervisorState.Idle || state is SupervisorState.Stopped,
            message = messageFor(state) ?: if (live) decoderProblem else null,
            soundVisible = live && soundSeen,
            muted = muted,
            qualityVisible = live && quality.switchSupported && quality.observed != null && !_archive.value.inArchive,
            qualityLabel = quality.observed?.name,
            qualityPending = quality.pending != null,
            debugExpanded = debugExpanded,
            debugLine = debugLine(sup, decoder, session),
            videoAspect = size?.takeIf { it.height > 0 }?.let { it.width.toFloat() / it.height.toFloat() },
            videoWidth = size?.width,
            videoHeight = size?.height,
            videoRendering = decoder is DecoderStatus.Rendering,
        )
    }

    private fun messageFor(state: SupervisorState): String? = when (state) {
        is SupervisorState.Fatal -> linkErrorMessage(state.error, transport)
        SupervisorState.AuthFailed -> linkErrorMessage(LinkError(LinkErrorKind.WRONG_PASSWORD, 0), transport)
        SupervisorState.Kicked -> LiveProbeTexts.KICKED
        is SupervisorState.Exhausted -> state.lastError?.let { linkErrorMessage(it, transport) }
        else -> null
    }

    // Value-free like LiveProbeViewModel.statusLine: names, counters and booleans only.
    private fun debugLine(sup: LiveSessionSupervisor?, decoder: DecoderStatus, session: DefaultMediaSession?): String {
        val link = sup?.currentLinkState?.value ?: LinkState.Idle
        val info = when (link) {
            is LinkState.Connected -> link.info
            is LinkState.Streaming -> link.info
            else -> null
        }
        val ready = when (decoder) {
            is DecoderStatus.Ready -> decoder
            is DecoderStatus.Rendering -> decoder.ready
            else -> null
        }
        val codec = ready?.let { r -> VideoCodec.entries.firstOrNull { it.mime == r.mime }?.name } ?: "-"
        val fps = (decoder as? DecoderStatus.Rendering)?.fps?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "-"
        val auth = when {
            link is LinkState.Streaming -> if (link.authOk) "ok" else "pending"
            link is LinkState.Failed && link.error.kind == LinkErrorKind.WRONG_PASSWORD -> "fail"
            else -> "-"
        }
        val stats = session?.pipelineStats()
        val audio = when {
            muted -> "muted"
            else -> when (audioSwitch.target?.status?.value) {
                is AudioOutStatus.Playing -> "playing"
                is AudioOutStatus.Failed -> "failed"
                AudioOutStatus.Idle -> "idle"
                null -> "-"
            }
        }
        val audioUnits = session?.audioStats()?.unitsIn ?: 0
        val error = (link as? LinkState.Failed)?.error?.let {
            if (it.code != 0) codeName(it.code, transport) else it.kind.name
        } ?: "-"
        return "link=${linkName(link)} transport=${transport?.name ?: "-"} mode=${info?.mode?.name ?: "-"} " +
            "lan=${info?.lan?.let(::yesNo) ?: "-"} codec=$codec decoder=${ready?.name ?: "-"} " +
            "hw=${ready?.hw?.let(::yesNo) ?: "-"} fps=$fps frames=${stats?.emitted ?: 0} " +
            "keyFrames=${stats?.keyFrames ?: 0} decryptOk=${stats?.decryptOk?.let(::yesNo) ?: "-"} auth=$auth " +
            "audio=$audio audioUnits=$audioUnits error=$error"
    }
    // END_BLOCK_REFRESH

    private fun linkName(s: LinkState): String = when (s) {
        LinkState.Idle -> "IDLE"
        LinkState.Connecting -> "CONNECTING"
        is LinkState.Connected -> "CONNECTED"
        is LinkState.Streaming -> "STREAMING"
        is LinkState.Failed -> "FAILED"
        is LinkState.Closed -> "CLOSED"
    }

    private fun stateName(s: SupervisorState): String = s::class.simpleName ?: "-"

    private fun yesNo(b: Boolean) = if (b) "yes" else "no"

    private fun initialUi(title: String) = LiveViewUi(
        title = title,
        badge = badgeFor(SupervisorState.Idle),
        showRetry = false,
        showConnect = true,
        message = null,
        soundVisible = false,
        muted = true,
        qualityVisible = false,
        qualityLabel = null,
        qualityPending = false,
        debugExpanded = false,
        debugLine = "",
        videoAspect = null,
        videoWidth = null,
        videoHeight = null,
        videoRendering = false,
    )

    companion object {
        private const val TICK_MS = 1_000L
        private const val REASON_BACKGROUND = "background"
        private const val REASON_SCREEN_LEFT = "screen-left"
    }
}
