// FILE: CameraListViewModel.kt
// VERSION: 0.2.0
// START_MODULE_CONTRACT
//   PURPOSE: Camera-list view-model: shows the cached list first, refreshes it from CameraListPort, exposes UI state.
//   SCOPE: cache-first start, single-flight refresh, Loading/Content/Empty/Error with typed ErrorKind and a stale
//     banner over a kept list; emits value-free BLOCK_LOAD_CAMERAS markers. No timers, no polling: a refresh runs
//     only on screen open (init) and on an explicit user action (pull / retry button).
//   DEPENDS: M-CAMERA-LIST
//   LINKS: M-UI-SHELL, M-CAMERA-LIST, V-M-UI-SHELL
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.ui.cameras

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yicamalt.camera.CameraError
import com.yicamalt.camera.CameraInfo
import com.yicamalt.camera.CameraListPort
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

// START_MODULE_MAP
//   CameraListViewModel - cache-first load, single-flight refresh(), load() alias.
//   CameraListUiState - Loading | Content(cameras, refreshing, staleError) | Empty(refreshing) | Error(kind, message).
//   ErrorKind - NETWORK | SERVER | UNAUTHORIZED.
//   userMessage - Russian user-facing text per ErrorKind.
//   isRefreshing - true while a refresh is in flight for the given state.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.2.0 - CAM-04/CAM-05: cached list first, Empty and typed Error states, stale banner,
//     single-flight refresh, cancellation-safe (CancellationException is rethrown, not turned into Error).
//   PREV_CHANGE: v0.1.0 - Loading/Content/Error with init { load() }.
// END_CHANGE_SUMMARY

enum class ErrorKind { NETWORK, SERVER, UNAUTHORIZED }

sealed interface CameraListUiState {
    data object Loading : CameraListUiState
    data class Content(
        val cameras: List<CameraInfo>,
        val refreshing: Boolean = false,
        val staleError: ErrorKind? = null,
    ) : CameraListUiState
    data class Empty(val refreshing: Boolean = false) : CameraListUiState
    data class Error(val kind: ErrorKind, val message: String) : CameraListUiState
}

internal fun ErrorKind.userMessage(): String = when (this) {
    ErrorKind.NETWORK -> "Нет связи с сервером Yi. Проверьте интернет."
    ErrorKind.SERVER -> "Сервер Yi вернул ошибку. Попробуйте позже."
    ErrorKind.UNAUTHORIZED -> "Сессия истекла. Войдите снова."
}

fun CameraListUiState.isRefreshing(): Boolean =
    this is CameraListUiState.Loading ||
        (this as? CameraListUiState.Content)?.refreshing == true ||
        (this as? CameraListUiState.Empty)?.refreshing == true

@HiltViewModel
class CameraListViewModel @Inject constructor(
    private val port: CameraListPort,
) : ViewModel() {

    private val _state = MutableStateFlow<CameraListUiState>(CameraListUiState.Loading)
    val state: StateFlow<CameraListUiState> = _state.asStateFlow()

    /** The running refresh (init or user); a second refresh() while it is active is ignored. Main thread only. */
    private var refreshJob: Job? = null

    init {
        refreshJob = viewModelScope.launch {
            val cached = port.cachedCameras()
            Timber.d("[Cameras][load][BLOCK_LOAD_CAMERAS] source=cache count=${cached.size}")
            _state.value = if (cached.isNotEmpty()) {
                CameraListUiState.Content(cached, refreshing = true)
            } else {
                CameraListUiState.Loading
            }
            fetchFromNetwork()
        }
    }

    // START_CONTRACT: refresh
    //   PURPOSE: Reload the list from the network once; ignored while another refresh is running.
    //   INPUTS: none
    //   OUTPUTS: none (state transitions on [state])
    //   SIDE_EFFECTS: sets the transitional (refreshing) state synchronously, then one CameraListPort.getCameraList().
    //   LINKS: M-CAMERA-LIST, V-M-UI-SHELL
    // END_CONTRACT: refresh
    fun refresh() {
        if (refreshJob?.isActive == true) return
        _state.value = when (val current = _state.value) {
            is CameraListUiState.Content -> current.copy(refreshing = true, staleError = null)
            is CameraListUiState.Empty -> CameraListUiState.Empty(refreshing = true)
            is CameraListUiState.Error, CameraListUiState.Loading -> CameraListUiState.Loading
        }
        refreshJob = viewModelScope.launch { fetchFromNetwork() }
    }

    fun load() = refresh()

    private suspend fun fetchFromNetwork() {
        // START_BLOCK_LOAD_CAMERAS
        try {
            val cameras = port.getCameraList()
            if (cameras.isEmpty()) {
                Timber.d("[Cameras][load][BLOCK_LOAD_CAMERAS] source=network result=empty count=0 kind=-")
                _state.value = CameraListUiState.Empty(refreshing = false)
            } else {
                Timber.d("[Cameras][load][BLOCK_LOAD_CAMERAS] source=network result=content count=${cameras.size} kind=-")
                _state.value = CameraListUiState.Content(cameras, refreshing = false, staleError = null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // CameraError extends java.lang.Error, so Throwable is the narrowest common type here.
            val kind = when (e) {
                is CameraError.Unauthorized -> ErrorKind.UNAUTHORIZED
                is CameraError.ServerRejected -> ErrorKind.SERVER
                else -> ErrorKind.NETWORK
            }
            val current = _state.value
            if (current is CameraListUiState.Content && current.cameras.isNotEmpty()) {
                Timber.d("[Cameras][load][BLOCK_LOAD_CAMERAS] source=network result=stale count=${current.cameras.size} kind=$kind")
                _state.value = current.copy(refreshing = false, staleError = kind)
            } else {
                Timber.d("[Cameras][load][BLOCK_LOAD_CAMERAS] source=network result=error count=0 kind=$kind")
                _state.value = CameraListUiState.Error(kind, kind.userMessage())
            }
        }
        // END_BLOCK_LOAD_CAMERAS
    }
}
