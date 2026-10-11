// FILE: SettingsViewModel.kt
// VERSION: 0.2.0
// START_MODULE_CONTRACT
//   PURPOSE: Settings screen view-model: view/edit the runtime API base URL override; debug endpoint probe.
//   SCOPE: read current base URL, persist an override (resolved per request by M-HTTP), run the
//     debug-only EndpointProbe once per press.
//   DEPENDS: M-CONFIG, M-HTTP, M-DEBUG-PROBE
//   LINKS: M-CONFIG, M-HTTP, M-DEBUG-PROBE
//   ROLE: UI
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yicamalt.config.ConfigModule
import com.yicamalt.debug.EndpointProbe
import com.yicamalt.network.HttpClientModule
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// START_MODULE_MAP
//   SettingsViewModel - exposes current base URL, persists changes, runs the debug endpoint probe.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.2.0 - Probe replaced by EndpointProbe (debug only); saveBaseUrl relies on per-request
//     base URL resolution (httpClient.rebuild() is now a logging no-op).
// END_CHANGE_SUMMARY

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val config: ConfigModule,
    private val httpClient: HttpClientModule,
    private val endpointProbe: EndpointProbe,
) : ViewModel() {

    private val _baseUrl = MutableStateFlow(config.getApiBaseUrl())
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    private val _probeResults = MutableStateFlow<List<String>?>(null)
    val probeResults: StateFlow<List<String>?> = _probeResults.asStateFlow()

    private val _probeRunning = MutableStateFlow(false)
    val probeRunning: StateFlow<Boolean> = _probeRunning.asStateFlow()

    /** Persist the API base URL override; M-HTTP resolves it on the very next request. */
    fun saveBaseUrl(url: String) {
        // START_BLOCK_SAVE_BASE_URL
        config.setApiBaseUrl(url)
        httpClient.rebuild()
        _baseUrl.value = config.getApiBaseUrl()
        // END_BLOCK_SAVE_BASE_URL
    }

    /** Debug-only: run the endpoint/region probe matrix once and surface value-free lines. */
    fun runEndpointProbe() {
        if (_probeRunning.value) return
        _probeRunning.value = true
        _probeResults.value = null
        viewModelScope.launch {
            try {
                _probeResults.value = endpointProbe.runOnce()
            } finally {
                _probeRunning.value = false
            }
        }
    }
}
