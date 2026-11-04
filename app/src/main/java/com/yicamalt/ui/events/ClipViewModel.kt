// FILE: ClipViewModel.kt
// VERSION: 0.1.0
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.File
import java.time.ZoneId
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
//   LAST_CHANGE: v0.1.0 - GSD plan 05-05 RED: types and signatures.
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

    fun open(key: String) {
        error("RED stub")
    }

    fun onPlayerError(what: Int) {
        error("RED stub")
    }

    fun openSd() {
        error("RED stub")
    }

    fun dismiss() {
        error("RED stub")
    }
}
