// FILE: LiveViewModel.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Screen state of the live view over LiveSessionSupervisor (M-UI-LIVEVIEW view model).
//   SCOPE: badge mapping (D-04), auto-connect on open, sound (D-05, D-07), HD/SD (D-06, D-07), snapshot, debug line,
//     lifecycle stop / resume (D-10), decoder / audio outputs behind injectable ports.
//   DEPENDS: M-LIVE-SESSION, M-MEDIA-RENDER, M-MEDIA-AUDIO, M-MEDIA-SNAPSHOT, M-P2P-CREDS, M-STREAM-CORE
//   LINKS: M-UI-LIVEVIEW, V-M-UI-LIVEVIEW
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.ui.live

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.Surface
import androidx.lifecycle.ViewModel
import com.yicamalt.live.NetworkMonitor
import com.yicamalt.live.SupervisorState
import com.yicamalt.media.AacDecoder
import com.yicamalt.media.AudioOutStatus
import com.yicamalt.media.DecoderStatus
import com.yicamalt.media.SnapshotStore
import com.yicamalt.media.VideoDecoder
import com.yicamalt.media.VideoSize
import com.yicamalt.stream.AacConfig
import com.yicamalt.stream.AacFrameSink
import com.yicamalt.stream.CameraLinkFactory
import com.yicamalt.stream.DecodableFrame
import com.yicamalt.stream.DecodableFrameSink
import com.yicamalt.stream.P2pCredentialsSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

// START_MODULE_MAP
//   LiveVideoOutput - video decoder port (status, videoSize, resetEvents, surface attach / detach, release).
//   LiveAudioOutput - AAC output port (status, release).
//   LiveEnvironment - injectable io dispatcher, clock and output factories.
//   BadgeTone / LiveBadge / badgeFor - SupervisorState -> badge (D-04).
//   LiveViewUi - value-free screen snapshot.
//   LiveEvent - one-shot messages (Toast).
//   LiveViewTexts - Russian badge and snapshot texts.
//   LiveViewModel - open / closeScreen / onStop / onStart / connect / retry / toggleMute / toggleQuality /
//     toggleDebug / attachSurface / detachSurface / saveSnapshot.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 03-10 RED: types and signatures; behaviour stubs.
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

fun badgeFor(state: SupervisorState): LiveBadge = error("RED stub")

@HiltViewModel
class LiveViewModel @Inject constructor(
    private val credentials: P2pCredentialsSource,
    private val factory: CameraLinkFactory,
    private val network: NetworkMonitor,
    private val snapshots: SnapshotStore,
    private val env: LiveEnvironment,
) : ViewModel() {
    val ui: StateFlow<LiveViewUi> get() = error("RED stub")
    val events: SharedFlow<LiveEvent> get() = error("RED stub")

    fun open(deviceId: String, title: String): Unit = error("RED stub")
    fun closeScreen(): Unit = error("RED stub")
    fun onStop(): Unit = error("RED stub")
    fun onStart(): Unit = error("RED stub")
    fun connect(): Unit = error("RED stub")
    fun retry(): Unit = error("RED stub")
    fun toggleMute(): Unit = error("RED stub")
    fun toggleQuality(): Unit = error("RED stub")
    fun toggleDebug(): Unit = error("RED stub")
    fun attachSurface(surface: Surface): Unit = error("RED stub")
    fun detachSurface(): Unit = error("RED stub")
    fun saveSnapshot(bitmap: Bitmap?): Unit = error("RED stub")
}
