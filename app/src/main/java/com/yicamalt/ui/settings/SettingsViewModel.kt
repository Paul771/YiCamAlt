// FILE: SettingsViewModel.kt
// VERSION: 0.4.0
// START_MODULE_CONTRACT
//   PURPOSE: Settings screen view-model: view/edit the runtime API base URL override; debug endpoint probe;
//     debug P2P check.
//   SCOPE: read current base URL, persist an override (resolved per request by M-HTTP), run the
//     debug-only EndpointProbe once per press, run the debug-only native P2P check once per press.
//   DEPENDS: M-CONFIG, M-HTTP, M-DEBUG-PROBE, M-P2P-NATIVE, M-MEDIA-RENDER
//   LINKS: M-CONFIG, M-HTTP, M-DEBUG-PROBE, M-P2P-NATIVE, M-MEDIA-RENDER
//   ROLE: UI
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yicamalt.config.ConfigModule
import com.yicamalt.debug.EndpointProbe
import com.yicamalt.media.DecoderCatalog
import com.yicamalt.media.DecoderChoice
import com.yicamalt.network.HttpClientModule
import com.yicamalt.p2p.LoadState
import com.yicamalt.p2p.NativeLibs
import com.yicamalt.p2p.PpppRuntime
import com.yicamalt.p2p.RuntimeState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

// START_MODULE_MAP
//   SettingsViewModel - exposes current base URL, persists changes, runs the debug endpoint probe and the
//     debug P2P check (nativeCheck line, nativeCheckRunning).
//   formatNativeCheck - builds the single value-free P2P check line.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.4.0 - GSD plan 02-03: debug P2P check (criterion 1/4). One line: libs, ABI, PPPP API hex,
//     init code, TUTK libs, avc/hevc decoder names with hw/sw. Native calls run on PpppRuntime's own thread.
//   PREV_CHANGE: v0.3.0 - CAM-02: saveBaseUrl stores a MANUAL server (ConfigModule.setManualBaseUrl); the field
//     shows only a manual server and stays empty in automatic mode, so pressing Save on an auto-selected
//     gateway cannot pin it by accident. Empty = automatic server selection.
//   OLDER_CHANGE: v0.2.0 - Probe replaced by EndpointProbe (debug only); saveBaseUrl relies on per-request
//     base URL resolution (httpClient.rebuild() is now a logging no-op).
// END_CHANGE_SUMMARY

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val config: ConfigModule,
    private val httpClient: HttpClientModule,
    private val endpointProbe: EndpointProbe,
    private val nativeLibs: NativeLibs,
    private val ppppRuntime: PpppRuntime,
) : ViewModel() {

    private val _nativeCheck = MutableStateFlow<String?>(null)
    val nativeCheck: StateFlow<String?> = _nativeCheck.asStateFlow()

    private val _nativeCheckRunning = MutableStateFlow(false)
    val nativeCheckRunning: StateFlow<Boolean> = _nativeCheckRunning.asStateFlow()

    private val _baseUrl = MutableStateFlow(manualBaseUrlOrEmpty())
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    private val _probeResults = MutableStateFlow<List<String>?>(null)
    val probeResults: StateFlow<List<String>?> = _probeResults.asStateFlow()

    private val _probeRunning = MutableStateFlow(false)
    val probeRunning: StateFlow<Boolean> = _probeRunning.asStateFlow()

    /** Persist a manual server (blank = automatic selection); M-HTTP resolves it on the very next request. */
    fun saveBaseUrl(url: String) {
        // START_BLOCK_SAVE_BASE_URL
        config.setManualBaseUrl(url)
        httpClient.rebuild()
        _baseUrl.value = manualBaseUrlOrEmpty()
        // END_BLOCK_SAVE_BASE_URL
    }

    private fun manualBaseUrlOrEmpty(): String = if (config.isManualBaseUrl()) config.getApiBaseUrl() else ""

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

    // START_CONTRACT: runNativeCheck
    //   PURPOSE: Debug-only: answer "do the vendor libs load, what PPPP API version, which decoders" in one line.
    //   INPUTS: none (ignored while a check is running)
    //   OUTPUTS: { nativeCheck StateFlow - the formatNativeCheck line }
    //   SIDE_EFFECTS: loads vendor libs, initialises PPPP once per process (PpppRuntime thread), queries
    //     MediaCodecList; one BLOCK_NATIVE_CHECK log line. No secret is read or shown.
    //   LINKS: M-P2P-NATIVE, M-MEDIA-RENDER
    // END_CONTRACT: runNativeCheck
    fun runNativeCheck() {
        if (_nativeCheckRunning.value) return
        _nativeCheckRunning.value = true
        _nativeCheck.value = null
        viewModelScope.launch(Dispatchers.Default) {
            try {
                // START_BLOCK_NATIVE_CHECK
                val pppp = nativeLibs.loadPppp()
                val tutk = nativeLibs.loadTutk()
                val rt = ppppRuntime.ensureInitialized()
                val avc = DecoderCatalog.find(DecoderCatalog.AVC)
                val hevc = DecoderCatalog.find(DecoderCatalog.HEVC)
                val line = formatNativeCheck(pppp, nativeLibs.primaryAbi(), rt, tutk, avc, hevc)
                Timber.d("[Settings][nativeCheck][BLOCK_NATIVE_CHECK] %s", line)
                _nativeCheck.value = line
                // END_BLOCK_NATIVE_CHECK
            } finally {
                _nativeCheckRunning.value = false
            }
        }
    }
}

// START_CONTRACT: formatNativeCheck
//   PURPOSE: Single value-free line for the debug P2P check.
//   INPUTS: { pppp, tutk: LoadState; abi: String; rt: RuntimeState; avc, hevc: DecoderChoice }
//   OUTPUTS: { String - "libs=<OK|MISSING|ERROR> abi=<abi> ppppApi=<0xHEX|-> init=<code|-> tutkLibs=<OK|MISSING|ERROR>
//     avc=<name|none>(<hw|sw>) hevc=<name|none>(<hw|sw>)" plus " hevcNote=no-decoder" when hevc is none }
//   SIDE_EFFECTS: none
//   LINKS: M-P2P-NATIVE, M-MEDIA-RENDER
// END_CONTRACT: formatNativeCheck
internal fun formatNativeCheck(
    pppp: LoadState,
    abi: String,
    rt: RuntimeState,
    tutk: LoadState,
    avc: DecoderChoice,
    hevc: DecoderChoice,
): String {
    fun libs(s: LoadState) = NativeLibs.label(s, "skipped").uppercase()
    fun dec(d: DecoderChoice) = "${d.name ?: "none"}(${if (d.hardware) "hw" else "sw"})"
    val api = (rt as? RuntimeState.Ready)?.apiHex ?: "-"
    val init = when (rt) {
        is RuntimeState.Ready -> rt.initCode.toString()
        is RuntimeState.InitFailed -> rt.code.toString()
        else -> "-"
    }
    val base = "libs=${libs(pppp)} abi=$abi ppppApi=$api init=$init tutkLibs=${libs(tutk)} " +
        "avc=${dec(avc)} hevc=${dec(hevc)}"
    return if (hevc.name == null) "$base hevcNote=no-decoder" else base
}
