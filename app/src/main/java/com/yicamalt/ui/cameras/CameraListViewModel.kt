// FILE: CameraListViewModel.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Camera-list view-model: loads cameras via CameraListPort, exposes UI state.
//   SCOPE: load()/refresh(), CameraListUiState transitions; emits BLOCK_LOAD_CAMERAS markers.
//   DEPENDS: M-CAMERA-LIST
//   LINKS: M-UI-SHELL, M-CAMERA-LIST
//   ROLE: UI
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.ui.cameras

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yicamalt.camera.CameraInfo
import com.yicamalt.camera.CameraListPort
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

// START_MODULE_MAP
//   CameraListViewModel - drives camera list state + refresh.
//   CameraListUiState - Loading | Content(cameras) | Error(message).
// END_MODULE_MAP

sealed interface CameraListUiState {
    data object Loading : CameraListUiState
    data class Content(val cameras: List<CameraInfo>) : CameraListUiState
    data class Error(val message: String) : CameraListUiState
}

@HiltViewModel
class CameraListViewModel @Inject constructor(
    private val port: CameraListPort,
) : ViewModel() {

    private val _state = MutableStateFlow<CameraListUiState>(CameraListUiState.Loading)
    val state: StateFlow<CameraListUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        // START_BLOCK_LOAD_CAMERAS
        Timber.d("[Cameras][load][BLOCK_LOAD_CAMERAS] loading camera list")
        _state.value = CameraListUiState.Loading
        viewModelScope.launch {
            try {
                val cameras = port.getCameraList()
                Timber.d("[Cameras][load][BLOCK_LOAD_CAMERAS] loaded ${cameras.size} cameras")
                _state.value = CameraListUiState.Content(cameras)
            } catch (e: com.yicamalt.camera.CameraError.Unauthorized) {
                _state.value = CameraListUiState.Error("Сессия истекла. Войдите снова.")
            } catch (e: Throwable) {
                _state.value = CameraListUiState.Error("Не удалось загрузить камеры. Проверьте сеть.")
            }
        }
        // END_BLOCK_LOAD_CAMERAS
    }
}