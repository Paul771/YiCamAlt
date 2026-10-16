// FILE: LiveProbeViewModel.kt
// VERSION: 0.2.0
// START_MODULE_CONTRACT
//   PURPOSE: Debug live-probe orchestration for one camera: credentials -> CameraLinkFactory -> FramePipeline ->
//     VideoDecoder -> Surface, with a value-free status line and explicit Russian refusal / error texts.
//   SCOPE: open(deviceId), forceRelay toggle, connect (one factory.open + start per press, off Main), exactly one
//     automatic retry per press after WRONG_PASSWORD (2 s pause, credentials refreshed from the cloud, new session),
//     stop / close (link closed once, decoder released once, idempotent, cancels a pending retry), Connect disabled
//     until the closed link reports Closed / Failed, surface attach / detach, decoder reset -> pipeline reset, 1 s
//     status ticker only while a session is active. No reconnect after any other error, no retry loop.
//   DEPENDS: M-STREAM-CORE (CameraLink ports, FramePipeline), M-TNP-LINK (CameraLinkFactory binding),
//     M-MEDIA-RENDER (VideoDecoder, DecoderStatus, DecoderCatalog), M-P2P-CREDS (P2pCredentialsSource),
//     M-P2P-NATIVE (PpppCodes), M-TUTK-LINK (TutkCodes)
//   LINKS: M-UI-LIVE-PROBE, V-M-UI-LIVE-PROBE
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.ui.live

import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yicamalt.camera.P2pCredentials
import com.yicamalt.camera.TransportType
import com.yicamalt.media.DecoderCatalog
import com.yicamalt.media.DecoderStatus
import com.yicamalt.media.VideoDecoder
import com.yicamalt.p2p.PpppCodes
import com.yicamalt.p2p.tutk.TutkCodes
import com.yicamalt.stream.CameraLink
import com.yicamalt.stream.CameraLinkFactory
import com.yicamalt.stream.DecodableFrame
import com.yicamalt.stream.DecodableFrameSink
import com.yicamalt.stream.FramePipeline
import com.yicamalt.stream.LinkError
import com.yicamalt.stream.LinkErrorKind
import com.yicamalt.stream.LinkInfo
import com.yicamalt.stream.LinkOptions
import com.yicamalt.stream.LinkState
import com.yicamalt.stream.P2pCredentialsSource
import com.yicamalt.stream.PipelineStats
import com.yicamalt.stream.VideoCodec
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

// START_MODULE_MAP
//   VideoSinkPort - the decoder surface the VM uses (status, resetEvents, surface attach/detach, release, frames).
//   VideoSinkProvider - injectable factory of VideoSinkPort (VideoDecoderAdapter over a new VideoDecoder).
//   HevcProbe - injectable "HEVC-декодер: <name> (hw|sw)" / "HEVC-декодер: нет" text from DecoderCatalog.
//   LiveProbeUi - title, canConnect, canStop, statusLine, message, forceRelay, hevcInfo.
//   LiveProbeViewModel - open / setForceRelay / connect / stop / onStop / close / attachSurface / detachSurface.
//   linkErrorMessage - Russian text for every LinkErrorKind (UNSUPPORTED_TRANSPORT and the INTERNAL code name depend
//     on the transport).
//   codeName - symbolic name of a vendor code from the table of the camera's transport (TutkCodes for TUTK, else
//     PpppCodes).
//   LiveProbeTexts - fixed Russian refusal and status texts (shared with tests and the device checkpoint).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.2.0 - Phase 2 review fixes.
//     MED-4: a camera can answer WRONG_PASSWORD once and accept the same password moments later (vendor treats
//       -20009 as possibly temporary and reconnects). One automatic retry per press: on Failed(WRONG_PASSWORD) of the
//       first session the session is finished (link teardown already complete), the UI shows "Повторная попытка…",
//       after RETRY_DELAY_MS (2 s, coroutine delay: virtual time in tests) the credentials are refreshed through
//       P2pCredentialsSource.refreshCredentials (one cloud list refresh; the stored value on error) and a new session
//       is opened. A second WRONG_PASSWORD is final. Never more than 2 factory.open per press; stop / close / leaving
//       the screen cancels a pending retry; other errors never retry. BLOCK_RETRY log line, value-free.
//     LOW-8: Connect stays disabled after stop until the closed link reports Closed / Failed (its teardown, and with
//       it the one-session registry entry, is done), so a quick Stop -> Connect no longer hits "Сессия уже открыта".
//     LOW-10: status auth= is "pending" while streaming without a 4882 answer, "ok" / "fail" only when known, "-"
//       otherwise; error codes are named from the transport's table (TutkCodes for TUTK).
//   PREVIOUS: v0.1.0 - GSD plan 02-09: debug live probe. Each press owns a Session (decoder + pipeline + link);
//     the press guard is "a session exists" (set synchronously before launching), close() ends the session under
//     the session lock so a factory.open that returns after close() is closed and never started. Link close runs
//     on a "live-probe-close" thread because TnpCameraLink.close waits up to ~2.2 s for its teardown thread.
// END_CHANGE_SUMMARY

interface VideoSinkPort : DecodableFrameSink {
    val status: StateFlow<DecoderStatus>
    val resetEvents: SharedFlow<Unit>
    fun attachSurface(surface: Surface)
    fun detachSurface()
    fun release()
}

internal class VideoDecoderAdapter(private val decoder: VideoDecoder) : VideoSinkPort {
    override val status: StateFlow<DecoderStatus> get() = decoder.status
    override val resetEvents: SharedFlow<Unit> get() = decoder.resetEvents
    override fun attachSurface(surface: Surface) = decoder.attachSurface(surface)
    override fun detachSurface() = decoder.detachSurface()
    override fun release() = decoder.release()
    override fun onFrame(frame: DecodableFrame) = decoder.onFrame(frame)
    override fun onUnsupportedCodec(codecId: Int) = decoder.onUnsupportedCodec(codecId)
}

open class VideoSinkProvider @Inject constructor() {
    open fun create(): VideoSinkPort = VideoDecoderAdapter(VideoDecoder())
}

open class HevcProbe @Inject constructor() {
    open fun describe(): String {
        val choice = DecoderCatalog.find(DecoderCatalog.HEVC)
        val name = choice.name ?: return LiveProbeTexts.HEVC_NONE
        return "HEVC-декодер: $name (${if (choice.hardware) "hw" else "sw"})"
    }
}

object LiveProbeTexts {
    const val DEFAULT_TITLE = "Камера"
    const val NO_CREDENTIALS_ON_OPEN = "Нет P2P-данных камеры: обновите список камер"
    const val TRANSPORT_UNKNOWN = "Транспорт камеры не определён: обновите список камер"
    const val TRANSPORT_LANGTAO = "Транспорт Langtao не поддерживается"
    const val TUTK_UNSUPPORTED = "TUTK-камеры пока не поддерживаются"
    const val TRANSPORT_UNSUPPORTED = "Транспорт камеры не поддерживается"
    const val KICKED = "Сессию закрыла камера (другой клиент)"
    const val RETRYING = "Повторная попытка…"
    const val HEVC_PENDING = "HEVC-декодер: проверка…"
    const val HEVC_NONE = "HEVC-декодер: нет"
}

data class LiveProbeUi(
    val title: String,
    val canConnect: Boolean,
    val canStop: Boolean,
    val statusLine: String,
    val message: String?,
    val forceRelay: Boolean,
    val hevcInfo: String,
)

// START_CONTRACT: linkErrorMessage
//   PURPOSE: Russian user text for a link failure; value-free (INTERNAL shows only the symbolic code name).
//   INPUTS: { error: LinkError; transport: TransportType? - decides the UNSUPPORTED_TRANSPORT text }
//   OUTPUTS: { String - non-blank }
//   SIDE_EFFECTS: none
//   LINKS: M-UI-LIVE-PROBE, PpppCodes.name, TutkCodes.name
// END_CONTRACT: linkErrorMessage
fun linkErrorMessage(error: LinkError, transport: TransportType?): String = when (error.kind) {
    LinkErrorKind.NATIVE_UNAVAILABLE -> "Нативные библиотеки не загружены"
    LinkErrorKind.NO_CREDENTIALS -> "Нет P2P-данных камеры (пароль/лицензия): обновите список камер"
    LinkErrorKind.UNSUPPORTED_TRANSPORT -> when (transport) {
        TransportType.TUTK -> LiveProbeTexts.TUTK_UNSUPPORTED
        TransportType.LANGTAO -> LiveProbeTexts.TRANSPORT_LANGTAO
        TransportType.UNKNOWN -> LiveProbeTexts.TRANSPORT_UNKNOWN
        else -> LiveProbeTexts.TRANSPORT_UNSUPPORTED
    }
    LinkErrorKind.ALREADY_OPEN -> "Сессия уже открыта"
    LinkErrorKind.OFFLINE -> "Камера не в сети"
    LinkErrorKind.CREDENTIALS -> "P2P-данные камеры устарели: выйдите и войдите снова"
    LinkErrorKind.NETWORK -> "Нет связи с P2P-серверами"
    LinkErrorKind.BUSY -> "Камера занята (много клиентов)"
    LinkErrorKind.WRONG_PASSWORD -> "Неверный пароль камеры"
    LinkErrorKind.REMOTE_CLOSED -> "Камера закрыла соединение"
    LinkErrorKind.KICKED -> LiveProbeTexts.KICKED
    LinkErrorKind.TIMEOUT -> "Нет ответа камеры"
    LinkErrorKind.PROTOCOL -> "Ошибка протокола"
    LinkErrorKind.INTERNAL -> "Внутренняя ошибка P2P (код ${codeName(error.code, transport)})"
}

// Vendor code name from the table of the camera's transport (TUTK codes are not PPPP codes).
fun codeName(code: Int, transport: TransportType?): String =
    if (transport == TransportType.TUTK) TutkCodes.name(code) else PpppCodes.name(code)

@HiltViewModel
class LiveProbeViewModel @Inject constructor(
    private val credentials: P2pCredentialsSource,
    private val factory: CameraLinkFactory,
    private val sinks: VideoSinkProvider,
    private val hevcProbe: HevcProbe,
) : ViewModel() {

    // One Connect press. Fields marked "lock" are shared with the factory thread.
    private class Session(
        val press: Int,
        val attempt: Int,
        val relay: Boolean,
        val creds: P2pCredentials,
        val decoder: VideoSinkPort,
        val pipeline: FramePipeline,
        val scope: CoroutineScope,
    ) {
        val lock = Any()
        var ended = false // lock
        var link: CameraLink? = null // lock
        val linkClosed = AtomicBoolean(false)
        val released = AtomicBoolean(false)
    }

    private val _ui = MutableStateFlow(
        LiveProbeUi(LiveProbeTexts.DEFAULT_TITLE, false, false, "", null, false, LiveProbeTexts.HEVC_PENDING),
    )
    val ui: StateFlow<LiveProbeUi> = _ui.asStateFlow()

    // Main-thread state.
    private var creds: P2pCredentials? = null
    private var deviceId: String? = null
    private var retryJob: Job? = null
    private var draining: CameraLink? = null
    private var refusal: String? = null
    private var title = LiveProbeTexts.DEFAULT_TITLE
    private var forceRelay = false
    private var hevcInfo = LiveProbeTexts.HEVC_PENDING
    private var session: Session? = null
    private var presses = 0
    private var surface: Surface? = null
    private var linkState: LinkState = LinkState.Idle
    private var lastInfo: LinkInfo? = null
    private var linkMessage: String? = null
    private var lastDecoder: DecoderStatus = DecoderStatus.Idle
    private var lastStats: PipelineStats? = null
    private var lastLoggedState: String? = null
    private var opened = false

    // START_CONTRACT: open
    //   PURPOSE: Bind the probe to one camera: read its P2P credentials and decide whether Connect is allowed.
    //   INPUTS: { deviceId: String - cloud uid, never logged or shown; cameraTitle: String - user-visible name }
    //   OUTPUTS: none (ui updated)
    //   SIDE_EFFECTS: one credentialsFor lookup; HevcProbe.describe on Dispatchers.Default; BLOCK_OPEN_PROBE log
    //   LINKS: M-P2P-CREDS, V-M-UI-LIVE-PROBE
    // END_CONTRACT: open
    fun open(deviceId: String, cameraTitle: String = LiveProbeTexts.DEFAULT_TITLE) {
        // START_BLOCK_OPEN_PROBE
        title = cameraTitle
        this.deviceId = deviceId
        val c = credentials.credentialsFor(deviceId)
        creds = c
        refusal = refusalFor(c)
        Timber.d(
            "[LiveProbe][vm][BLOCK_OPEN_PROBE] transport=%s verified=%b hasCreds=%b",
            c?.transport?.name ?: "-", c?.transportVerified == true, c != null,
        )
        if (!opened) {
            opened = true
            viewModelScope.launch(Dispatchers.Default) {
                val text = runCatching { hevcProbe.describe() }.getOrDefault(LiveProbeTexts.HEVC_NONE)
                launch(Dispatchers.Main) {
                    hevcInfo = text
                    publish()
                }
            }
        }
        publish()
        // END_BLOCK_OPEN_PROBE
    }

    private fun refusalFor(c: P2pCredentials?): String? = when {
        c == null -> LiveProbeTexts.NO_CREDENTIALS_ON_OPEN
        c.transport == TransportType.UNKNOWN -> LiveProbeTexts.TRANSPORT_UNKNOWN
        c.transport == TransportType.LANGTAO -> LiveProbeTexts.TRANSPORT_LANGTAO
        else -> null
    }

    fun setForceRelay(value: Boolean) {
        forceRelay = value
        publish()
    }

    // START_CONTRACT: connect
    //   PURPOSE: One P2P attempt per press: FramePipeline -> new decoder, factory.open off Main, then start(pipeline).
    //   INPUTS: none (uses the credentials from open and the forceRelay toggle)
    //   OUTPUTS: none (ui: canConnect false synchronously, then link / decoder status)
    //   SIDE_EFFECTS: one CameraLinkFactory.open and at most one CameraLink.start, plus at most one automatic retry
    //     (second open) after WRONG_PASSWORD (BLOCK_RETRY); ignored while a session, a pending retry or a closing link
    //     exists, or when the camera was refused; BLOCK_CONNECT_PRESSED log.
    //   LINKS: M-TNP-LINK, M-STREAM-CORE, M-MEDIA-RENDER, V-M-UI-LIVE-PROBE
    // END_CONTRACT: connect
    fun connect() {
        // START_BLOCK_CONNECT_PRESSED
        val c = creds ?: return
        if (session != null || retryJob != null || draining != null || refusal != null) return
        startSession(c, forceRelay, attempt = 1, press = ++presses)
        // END_BLOCK_CONNECT_PRESSED
    }

    // Main thread. One session = decoder + pipeline + link; attempt 2 is the automatic retry of the same press.
    private fun startSession(c: P2pCredentials, relay: Boolean, attempt: Int, press: Int) {
        val decoder = sinks.create()
        val pipeline = FramePipeline(c.password, c.p2pEncrypt, decoder)
        val parent = viewModelScope.coroutineContext.job
        val s = Session(
            press, attempt, relay, c, decoder, pipeline,
            CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(parent)),
        )
        session = s
        linkState = LinkState.Connecting
        lastInfo = null
        linkMessage = null
        lastDecoder = DecoderStatus.Idle
        lastStats = null
        surface?.let { decoder.attachSurface(it) }
        Timber.d("[LiveProbe][vm][BLOCK_CONNECT_PRESSED] relayForced=%b press=%d attempt=%d", relay, s.press, attempt)
        publish()

        s.scope.launch { decoder.status.collect { if (session === s) publish() } }
        s.scope.launch { decoder.resetEvents.collect { pipeline.reset() } }
        // Real-time ticker on Default (stats are not a flow); the refresh itself runs on Main.
        s.scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(TICK_MS)
                withContext(Dispatchers.Main) { if (session === s) publish() }
            }
        }
        s.scope.launch(Dispatchers.Default) {
            val link = try {
                factory.open(c, LinkOptions(relay))
            } catch (e: RuntimeException) {
                Timber.w("[LiveProbe][vm][BLOCK_CONNECT_PRESSED] result=open-threw error=%s", e.javaClass.simpleName)
                s.scope.launch(Dispatchers.Main) {
                    onLinkState(s, LinkState.Failed(LinkError(LinkErrorKind.INTERNAL, 0)))
                }
                return@launch
            }
            val adopted = synchronized(s.lock) {
                if (s.ended || !isActive) {
                    false
                } else {
                    s.link = link
                    true
                }
            }
            if (!adopted) {
                closeLinkOnce(s, link, "screen-left")
                return@launch
            }
            s.scope.launch(Dispatchers.Main) { link.state.collect { onLinkState(s, it) } }
            synchronized(s.lock) {
                if (!s.ended) link.start(pipeline)
            }
        }
    }

    fun stop() = close("user")

    fun onStop() = close("background")

    fun attachSurface(s: Surface) {
        surface = s
        session?.decoder?.attachSurface(s)
    }

    fun detachSurface() {
        surface = null
        session?.decoder?.detachSurface()
    }

    // START_CONTRACT: close
    //   PURPOSE: End the current session (if any) or cancel a pending retry: link closed once, decoder released once;
    //     Connect enabled again once the link reports Closed / Failed.
    //   INPUTS: { reason: String - user | screen-left | background }
    //   OUTPUTS: none
    //   SIDE_EFFECTS: CameraLink.close(reason) on a "live-probe-close" thread; VideoSinkPort.release; session coroutines
    //     cancelled; pending retry cancelled (no further factory.open); BLOCK_CLOSE_PROBE log. Idempotent.
    //   LINKS: M-TNP-LINK, M-MEDIA-RENDER, V-M-UI-LIVE-PROBE
    // END_CONTRACT: close
    fun close(reason: String) {
        // START_BLOCK_CLOSE_PROBE
        val pending = retryJob
        if (pending != null) {
            retryJob = null
            pending.cancel()
            linkState = LinkState.Closed(reason)
            linkMessage = null
            Timber.d("[LiveProbe][vm][BLOCK_CLOSE_PROBE] reason=%s frames=0 retry=cancelled", reason)
            publish()
            return
        }
        val s = session ?: return
        val frames = s.pipeline.stats().emitted
        linkState = LinkState.Closed(reason)
        finish(s, reason)
        Timber.d("[LiveProbe][vm][BLOCK_CLOSE_PROBE] reason=%s frames=%d", reason, frames)
        publish()
        // END_BLOCK_CLOSE_PROBE
    }

    override fun onCleared() {
        close("screen-left")
        super.onCleared()
    }

    // START_BLOCK_LINK_STATE
    private fun onLinkState(s: Session, st: LinkState) {
        if (session !== s) return
        linkState = if (st is LinkState.Idle) LinkState.Connecting else st
        when (st) {
            is LinkState.Connected -> lastInfo = st.info
            is LinkState.Streaming -> lastInfo = st.info
            is LinkState.Failed -> linkMessage = linkErrorMessage(st.error, creds?.transport)
            is LinkState.Closed -> linkMessage = if (st.reason == "kicked") LiveProbeTexts.KICKED else null
            else -> Unit
        }
        if (st is LinkState.Failed || st is LinkState.Closed) {
            finish(s, if (st is LinkState.Closed) st.reason else "error")
        }
        if (st is LinkState.Failed && st.error.kind == LinkErrorKind.WRONG_PASSWORD && s.attempt == 1) {
            scheduleRetry(s)
        }
        publish()
    }
    // END_BLOCK_LINK_STATE

    // START_BLOCK_RETRY
    // START_CONTRACT: scheduleRetry
    //   PURPOSE: The one automatic retry of a press after WRONG_PASSWORD (vendor: -20009 may be temporary).
    //   INPUTS: { failed: Session - the finished first session (its link already tore down) }
    //   OUTPUTS: none (ui: state CONNECTING, message RETRYING, Stop enabled, Connect disabled)
    //   SIDE_EFFECTS: after RETRY_DELAY_MS: P2pCredentialsSource.refreshCredentials off Main, then startSession with
    //     attempt 2 (the refreshed value, or the old one when the refresh found nothing); cancelled by close()
    //   LINKS: M-P2P-CREDS, V-M-UI-LIVE-PROBE
    // END_CONTRACT: scheduleRetry
    private fun scheduleRetry(failed: Session) {
        val id = deviceId ?: return
        linkState = LinkState.Connecting
        linkMessage = LiveProbeTexts.RETRYING
        Timber.d("[LiveProbe][vm][BLOCK_RETRY] press=%d attempt=2 delayMs=%d", failed.press, RETRY_DELAY_MS)
        retryJob = viewModelScope.launch {
            delay(RETRY_DELAY_MS)
            val fresh = withContext(Dispatchers.IO) {
                try {
                    credentials.refreshCredentials(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
            retryJob = null
            val c = fresh ?: failed.creds
            creds = c
            val r = refusalFor(c)
            if (r != null) {
                refusal = r
                linkState = LinkState.Closed("error")
                linkMessage = null
                publish()
                return@launch
            }
            Timber.d("[LiveProbe][vm][BLOCK_RETRY] press=%d refreshed=%b", failed.press, fresh != null)
            startSession(c, failed.relay, attempt = 2, press = failed.press)
        }
    }
    // END_BLOCK_RETRY

    // Main thread. Snapshots the session's decoder / pipeline values, then tears the session down.
    private fun finish(s: Session, reason: String) {
        lastDecoder = s.decoder.status.value
        lastStats = s.pipeline.stats()
        session = null
        val link = synchronized(s.lock) {
            s.ended = true
            s.link
        }
        s.scope.cancel()
        if (link != null) {
            closeLinkOnce(s, link, reason)
            watchDrain(link)
        }
        if (s.released.compareAndSet(false, true)) s.decoder.release()
    }

    // Main thread. Connect stays disabled until the closed link reports a terminal state (its teardown is done).
    private fun watchDrain(link: CameraLink) {
        if (isTerminal(link.state.value)) return
        draining = link
        viewModelScope.launch {
            link.state.first { isTerminal(it) }
            if (draining === link) {
                draining = null
                publish()
            }
        }
    }

    private fun isTerminal(st: LinkState) = st is LinkState.Failed || st is LinkState.Closed

    private fun closeLinkOnce(s: Session, link: CameraLink, reason: String) {
        if (!s.linkClosed.compareAndSet(false, true)) return
        Thread({ link.close(reason) }, "live-probe-close").start()
    }

    // START_BLOCK_STATUS
    private fun publish() {
        val s = session
        val decoder = s?.decoder?.status?.value ?: lastDecoder
        val stats = s?.pipeline?.stats() ?: lastStats
        val line = statusLine(decoder, stats)
        val stateName = stateName()
        if (stateName != lastLoggedState) {
            lastLoggedState = stateName
            Timber.d("[LiveProbe][vm][BLOCK_STATUS] %s", line)
        }
        val decoderProblem = when (decoder) {
            is DecoderStatus.NoDecoder, is DecoderStatus.Unsupported, is DecoderStatus.Failed -> decoder.userText()
            else -> null
        }
        _ui.value = LiveProbeUi(
            title = title,
            canConnect = s == null && retryJob == null && draining == null && creds != null && refusal == null,
            canStop = s != null || retryJob != null,
            statusLine = line,
            message = refusal ?: linkMessage ?: decoderProblem,
            forceRelay = forceRelay,
            hevcInfo = hevcInfo,
        )
    }

    private fun stateName(): String = when (linkState) {
        LinkState.Idle -> "IDLE"
        LinkState.Connecting -> "CONNECTING"
        is LinkState.Connected -> "CONNECTED"
        is LinkState.Streaming -> "STREAMING"
        is LinkState.Failed -> "FAILED"
        is LinkState.Closed -> "CLOSED"
    }

    private fun statusLine(decoder: DecoderStatus, stats: PipelineStats?): String {
        val info = lastInfo
        val ready = when (decoder) {
            is DecoderStatus.Ready -> decoder
            is DecoderStatus.Rendering -> decoder.ready
            else -> null
        }
        val codec = when {
            ready != null -> codecName(ready.mime)
            decoder is DecoderStatus.NoDecoder -> codecName(decoder.mime)
            else -> "-"
        }
        val decoderName = when {
            ready != null -> ready.name
            decoder is DecoderStatus.NoDecoder -> "none"
            else -> "-"
        }
        val fps = (decoder as? DecoderStatus.Rendering)?.fps?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "-"
        val st = linkState
        val auth = when {
            st is LinkState.Streaming -> if (st.authOk) "ok" else "pending"
            st is LinkState.Failed && st.error.kind == LinkErrorKind.WRONG_PASSWORD -> "fail"
            else -> "-"
        }
        val error = (st as? LinkState.Failed)?.error?.let {
            if (it.code != 0) codeName(it.code, creds?.transport) else it.kind.name
        } ?: "-"
        return "transport=${creds?.transport?.name ?: "-"} state=${stateName()} " +
            "mode=${info?.mode?.name ?: "-"} lan=${info?.lan?.let(::yesNo) ?: "-"} relayForced=${yesNo(forceRelay)} " +
            "codec=$codec decoder=$decoderName hw=${ready?.hw?.let(::yesNo) ?: "-"} fps=$fps " +
            "frames=${stats?.emitted ?: 0} keyFrames=${stats?.keyFrames ?: 0} " +
            "decryptOk=${stats?.decryptOk?.let(::yesNo) ?: "-"} auth=$auth error=$error"
    }
    // END_BLOCK_STATUS

    private fun yesNo(b: Boolean) = if (b) "yes" else "no"

    private fun codecName(mime: String): String = VideoCodec.entries.firstOrNull { it.mime == mime }?.name ?: "-"

    companion object {
        private const val TICK_MS = 1_000L
        const val RETRY_DELAY_MS = 2_000L
    }
}
