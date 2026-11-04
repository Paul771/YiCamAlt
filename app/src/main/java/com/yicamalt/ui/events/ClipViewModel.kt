// FILE: ClipViewModel.kt
// VERSION: 0.2.0
// START_MODULE_CONTRACT
//   PURPOSE: State of the event clip dialog (EVT-03, EVT-04; D-02): load the decrypted local clip of one event, map
//     every failure to a short Russian text, enable «Открыть запись на SD» only for a camera in the cached list.
//   SCOPE: ClipLoad / ClipSource port (impl RepositoryClipSource over EventMediaRepository.clip + EventDao.getByKey,
//     bound in ClipUiModule), ClipState / ClipUi / ClipEvent / ClipTexts, ClipViewModel open / onPlayerError / openSd /
//     dismiss. One load job at a time; a new key cancels the old one.
//   DEPENDS: M-EVENT-PLAYBACK (EventMediaRepository, ClipResult), M-DATABASE (EventDao, EventEntity),
//     M-CAMERA-LIST (CameraListPort), M-UI-EVENTS (EventsFeed.UNKNOWN_CAMERA)
//   LINKS: M-UI-EVENTS, M-EVENT-PLAYBACK, V-M-UI-EVENTS, EVT-03, EVT-04
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.ui.events

import androidx.lifecycle.ViewModel
import com.yicamalt.camera.CameraListPort
import com.yicamalt.database.EventDao
import com.yicamalt.database.EventEntity
import com.yicamalt.event.ClipResult
import com.yicamalt.event.EventMediaRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.components.SingletonComponent
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

// START_MODULE_MAP
//   ClipLoad - Ready(file) | Unsupported | Expired | Failed(reason): public mirror of ClipResult.
//   ClipSource - port: clip(key), event(key) (impl RepositoryClipSource; ClipUiModule binds it).
//   ClipState - Loading | Ready(file) | Message(text).
//   ClipUi - dialog state (state, cameraName, timeLabel, sdEnabled, sdLabel); null in ClipViewModel.ui = closed.
//   ClipEvent - one-shot OpenSd(deviceId, timeMs) with a redacted toString.
//   ClipTexts - Russian dialog texts.
//   ClipViewModel - ui, events, open, onPlayerError, openSd, dismiss.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.2.0 - GSD plan 05-05 GREEN: open (same key while shown = no-op, a new key cancels the old job;
//     a late result of an old key is dropped), row + cached camera -> name, «d MMMM, HH:mm:ss» label and the SD rule
//     (a row whose camera is not in the cached list -> «Камера недоступна»), ClipLoad -> texts, onPlayerError keeps
//     the SD button, openSd -> OpenSd + dismiss, dismiss cancels. Logs: state names and booleans only.
//   PREVIOUS: v0.1.0 - GSD plan 05-05 RED: types and signatures.
// END_CHANGE_SUMMARY

sealed interface ClipLoad {
    data class Ready(val file: File) : ClipLoad {
        override fun toString(): String = "Ready"
    }
    data object Unsupported : ClipLoad
    data object Expired : ClipLoad
    data class Failed(val reason: String) : ClipLoad
}

interface ClipSource {
    suspend fun clip(key: String): ClipLoad
    suspend fun event(key: String): EventEntity?
}

class RepositoryClipSource @Inject constructor(
    private val repository: EventMediaRepository,
    private val dao: EventDao,
) : ClipSource {
    override suspend fun clip(key: String): ClipLoad = when (val r = repository.clip(key)) {
        is ClipResult.Ready -> ClipLoad.Ready(r.file)
        ClipResult.Unsupported -> ClipLoad.Unsupported
        ClipResult.Expired -> ClipLoad.Expired
        is ClipResult.Failed -> ClipLoad.Failed(r.reason)
    }

    override suspend fun event(key: String): EventEntity? = dao.getByKey(key)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ClipUiModule {
    @Binds abstract fun bindClipSource(impl: RepositoryClipSource): ClipSource
}

sealed interface ClipState {
    data object Loading : ClipState
    data class Ready(val file: File) : ClipState {
        override fun toString(): String = "Ready"
    }
    data class Message(val text: String) : ClipState
}

data class ClipUi(
    val state: ClipState,
    val cameraName: String,
    val timeLabel: String,
    val sdEnabled: Boolean,
    val sdLabel: String,
)

sealed interface ClipEvent {
    class OpenSd(val deviceId: String, val timeMs: Long) : ClipEvent {
        override fun toString(): String = "OpenSd"
    }
}

object ClipTexts {
    const val UNAVAILABLE = "Клип недоступен"
    const val EXPIRED = "Клип устарел, обновите ленту"
    const val LOAD_FAILED = "Не удалось загрузить клип"
    const val PLAY_FAILED = "Не удалось воспроизвести клип"
    const val OPEN_SD = "Открыть запись на SD"
    const val CAMERA_UNAVAILABLE = "Камера недоступна"
    const val CLOSE = "Закрыть"
    const val REPLAY = "Повторить"
}

@HiltViewModel
class ClipViewModel internal constructor(
    private val source: ClipSource,
    private val cameraPort: CameraListPort,
    private val zone: () -> ZoneId,
) : ViewModel() {

    @Inject constructor(source: ClipSource, cameraPort: CameraListPort) : this(source, cameraPort, ZoneId::systemDefault)

    private val _ui = MutableStateFlow<ClipUi?>(null)
    val ui: StateFlow<ClipUi?> = _ui.asStateFlow()

    private val channel = Channel<ClipEvent>(Channel.BUFFERED)
    val events: Flow<ClipEvent> = channel.receiveAsFlow()

    // Main-thread state (viewModelScope runs on Dispatchers.Main). The device id and the event time stay here only.
    private var currentKey: String? = null
    private var job: Job? = null
    private var deviceId: String? = null
    private var timeMs: Long? = null

    // START_CONTRACT: open
    //   PURPOSE: Show the dialog for one event and load its clip (EVT-03).
    //   INPUTS: { key: String - event_key of a cached row; never logged }
    //   OUTPUTS: none (ui)
    //   SIDE_EFFECTS: same key while shown = no-op; another key cancels the previous load and resets the state to
    //     Loading; ClipSource.event + CameraListPort.cachedCameras (Room only) + ClipSource.clip; BLOCK_CLIP_LOAD log
    //   LINKS: EventMediaRepository.clip, CameraListPort.cachedCameras
    // END_CONTRACT: open
    fun open(key: String) {
        if (key == currentKey && _ui.value != null) return
        job?.cancel()
        currentKey = key
        deviceId = null
        timeMs = null
        _ui.value = ClipUi(ClipState.Loading, "", "", sdEnabled = false, sdLabel = ClipTexts.CAMERA_UNAVAILABLE)
        job = viewModelScope.launch { load(key) }
    }

    private suspend fun load(key: String) {
        // START_BLOCK_CLIP_LOAD
        val row = guarded { source.event(key) }
        val cameras = guarded { cameraPort.cachedCameras() } ?: emptyList()
        val camera = row?.let { r -> cameras.firstOrNull { it.deviceId == r.deviceId } }
        val sdEnabled = row != null && camera != null
        if (row != null && camera != null) {
            deviceId = row.deviceId
            timeMs = row.timeMs
        }
        val meta = ClipUi(
            state = ClipState.Loading,
            cameraName = camera?.name?.takeIf { it.isNotBlank() } ?: EventsFeed.UNKNOWN_CAMERA,
            timeLabel = row?.let { TIME_LABEL.format(Instant.ofEpochMilli(it.timeMs).atZone(zone())) } ?: "",
            sdEnabled = sdEnabled,
            sdLabel = if (sdEnabled) ClipTexts.OPEN_SD else ClipTexts.CAMERA_UNAVAILABLE,
        )
        _ui.value = meta
        val result = if (row == null) ClipLoad.Failed("no-row") else guarded { source.clip(key) } ?: ClipLoad.Failed("error")
        if (currentKey != key) return
        val (state, name) = when (result) {
            is ClipLoad.Ready -> ClipState.Ready(result.file) to "ready"
            ClipLoad.Unsupported -> ClipState.Message(ClipTexts.UNAVAILABLE) to "unsupported"
            ClipLoad.Expired -> ClipState.Message(ClipTexts.EXPIRED) to "expired"
            is ClipLoad.Failed -> ClipState.Message(ClipTexts.LOAD_FAILED) to "failed"
        }
        Timber.d("[Events][clip][BLOCK_CLIP_LOAD] state=%s sd=%b", name, sdEnabled)
        _ui.value = meta.copy(state = state)
        // END_BLOCK_CLIP_LOAD
    }

    private suspend fun <T> guarded(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.d("[Events][clip][BLOCK_CLIP_LOAD] error=%s", e.javaClass.simpleName)
        null
    }

    // START_CONTRACT: onPlayerError
    //   PURPOSE: MediaPlayer could not play the local file: show «Не удалось воспроизвести клип», keep the SD button.
    //   INPUTS: { what: Int - MediaPlayer error code (logged as a number only) }
    //   OUTPUTS: none (ui)
    //   SIDE_EFFECTS: BLOCK_CLIP_PLAYER log
    //   LINKS: ClipDialog
    // END_CONTRACT: onPlayerError
    fun onPlayerError(what: Int) {
        val ui = _ui.value ?: return
        Timber.d("[Events][clip][BLOCK_CLIP_PLAYER] state=error what=%d", what)
        _ui.value = ui.copy(state = ClipState.Message(ClipTexts.PLAY_FAILED))
    }

    // START_CONTRACT: openSd
    //   PURPOSE: «Открыть запись на SD» (EVT-04): hand the camera and the event time to the shell and close.
    //   INPUTS: none
    //   OUTPUTS: none (ClipEvent.OpenSd; ui -> null)
    //   SIDE_EFFECTS: no-op while the button is disabled; dismiss(); BLOCK_CLIP_OPEN_SD log (no values)
    //   LINKS: AppShellScreen, LiveViewScreen.initialArchiveMs
    // END_CONTRACT: openSd
    fun openSd() {
        val d = deviceId
        val t = timeMs
        if (_ui.value?.sdEnabled != true || d == null || t == null) return
        Timber.d("[Events][clip][BLOCK_CLIP_OPEN_SD] state=open")
        channel.trySend(ClipEvent.OpenSd(d, t))
        dismiss()
    }

    // START_CONTRACT: dismiss
    //   PURPOSE: Close the dialog: cancel an in-flight load, forget the event.
    //   INPUTS: none
    //   OUTPUTS: none (ui -> null)
    //   SIDE_EFFECTS: cancels the load job
    //   LINKS: open
    // END_CONTRACT: dismiss
    fun dismiss() {
        job?.cancel()
        job = null
        currentKey = null
        deviceId = null
        timeMs = null
        _ui.value = null
    }

    private companion object {
        val TIME_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM, HH:mm:ss", Locale.forLanguageTag("ru"))
    }
}
