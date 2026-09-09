// FILE: SettingsViewModel.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Settings screen view-model: view/edit the runtime API base URL override.
//   SCOPE: read current base URL, persist an override, rebuild the HTTP client on save.
//   DEPENDS: M-CONFIG, M-HTTP
//   LINKS: M-CONFIG, M-HTTP
//   ROLE: UI
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yicamalt.config.ConfigModule
import com.yicamalt.debug.DeviceSignProbe
import com.yicamalt.network.HttpClientModule
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// START_MODULE_MAP
//   SettingsViewModel - exposes current base URL, persists changes, runs the dev sign probe.
// END_MODULE_MAP

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val config: ConfigModule,
    private val httpClient: HttpClientModule,
    private val signProbe: DeviceSignProbe,
) : ViewModel() {

    private val _baseUrl = MutableStateFlow(config.getApiBaseUrl())
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    private val _probeResults = MutableStateFlow<List<String>?>(null)
    val probeResults: StateFlow<List<String>?> = _probeResults.asStateFlow()

    private val _probeRunning = MutableStateFlow(false)
    val probeRunning: StateFlow<Boolean> = _probeRunning.asStateFlow()

    /** Persist the API base URL override and rebuild the HTTP client so it takes effect immediately. */
    fun saveBaseUrl(url: String) {
        // START_BLOCK_SAVE_BASE_URL
        config.setApiBaseUrl(url)
        httpClient.rebuild()
        _baseUrl.value = config.getApiBaseUrl()
        // END_BLOCK_SAVE_BASE_URL
    }

    /** Debug-only: run the signed-request probe and surface response codes. */
    fun runSignProbe() {
        _probeRunning.value = true
        _probeResults.value = null
        viewModelScope.launch {
            _probeResults.value = signProbe.runAll()
            _probeRunning.value = false
        }
    }
}
