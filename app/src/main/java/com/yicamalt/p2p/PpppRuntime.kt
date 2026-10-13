// FILE: PpppRuntime.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Initialise the vendor PPPP library once per process, with native debug off, and report its API version.
//   SCOPE: RuntimeState family; ensureInitialized() (load lib, Initialize, Config_Debug, GetAPIVersion) on a
//     dedicated "p2p-runtime" thread; observable state.
//   DEPENDS: M-P2P-NATIVE (PpppNative, PpppCodes, NativeLibs)
//   LINKS: M-P2P-NATIVE, V-M-P2P-NATIVE, M-TNP-LINK
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.p2p

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.inject.Singleton

// START_MODULE_MAP
//   RuntimeState - NotStarted | Unavailable | InitFailed(code) | Ready(apiVersion, apiHex).
//   PpppRuntime - ensureInitialized(), state; THREAD_NAME, MAX_SESSIONS, INIT_TIMEOUT_MS.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 02-03: created. Init once per process; never de-initialised; debug off.
// END_CHANGE_SUMMARY

sealed interface RuntimeState {
    object NotStarted : RuntimeState {
        override fun toString() = "NotStarted"
    }

    object Unavailable : RuntimeState {
        override fun toString() = "Unavailable"
    }

    data class InitFailed(val code: Int) : RuntimeState

    data class Ready(val apiVersion: Int) : RuntimeState {
        val apiHex: String get() = "0x%08X".format(apiVersion)
    }
}

@Singleton
class PpppRuntime @Inject constructor(
    private val native: PpppNative,
    private val libs: NativeLibs,
) {
    private val lock = Any()
    private val _state = MutableStateFlow<RuntimeState>(RuntimeState.NotStarted)
    val state: StateFlow<RuntimeState> = _state.asStateFlow()

    private var pending: Future<RuntimeState>? = null

    private val executor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, THREAD_NAME).apply { isDaemon = true } }
    }

    // START_CONTRACT: ensureInitialized
    //   PURPOSE: Make the PPPP library usable: once per process, never de-initialised, native debug off because
    //     native logs contain the DID and the server string.
    //   INPUTS: none
    //   OUTPUTS: { RuntimeState - Ready(apiVersion) when Initialize returned 0 or -3002; InitFailed(code) for any
    //     other negative code or a 10 s timeout (TIME_OUT); Unavailable when libPPPP_API.so did not load }
    //   SIDE_EFFECTS: first call only: System.loadLibrary, PPPP_Initialize, PPPP_Config_Debug(0, 4194311),
    //     PPPP_GetAPIVersion on the "p2p-runtime" thread; one BLOCK_INIT_PPPP log line. Later calls: none
    //     (a timed-out init is adopted once its native call finishes).
    //   LINKS: M-P2P-NATIVE, M-TNP-LINK
    // END_CONTRACT: ensureInitialized
    fun ensureInitialized(): RuntimeState = synchronized(lock) {
        val current = _state.value
        if (current != RuntimeState.NotStarted) {
            val late = pending
            if (current == RuntimeState.InitFailed(PpppCodes.TIME_OUT) && late != null && late.isDone) {
                pending = null
                _state.value = runCatching { late.get() }.getOrDefault(current)
            }
            return _state.value
        }
        if (libs.loadPppp() != LoadState.Loaded) {
            Timber.d("[P2P][runtime][BLOCK_INIT_PPPP] result=unavailable reason=libs")
            _state.value = RuntimeState.Unavailable
            return RuntimeState.Unavailable
        }
        val future = executor.submit<RuntimeState> { initOnRuntimeThread() }
        val result = try {
            future.get(INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            pending = future
            Timber.d("[P2P][runtime][BLOCK_INIT_PPPP] result=timeout boundMs=%d", INIT_TIMEOUT_MS)
            RuntimeState.InitFailed(PpppCodes.TIME_OUT)
        }
        _state.value = result
        result
    }

    // START_BLOCK_INIT_PPPP
    private fun initOnRuntimeThread(): RuntimeState {
        val init = native.initialize(INIT_STRING.copyOf(), MAX_SESSIONS)
        if (init != PpppCodes.OK && init != PpppCodes.ALREADY_INITIALIZED) {
            Timber.d("[P2P][runtime][BLOCK_INIT_PPPP] init=%d name=%s result=failed", init, PpppCodes.name(init))
            return RuntimeState.InitFailed(init)
        }
        native.configDebug(0, 4194311)
        val ready = RuntimeState.Ready(native.apiVersion())
        Timber.d("[P2P][runtime][BLOCK_INIT_PPPP] init=%d api=%s debug=off", init, ready.apiHex)
        return ready
    }
    // END_BLOCK_INIT_PPPP

    companion object {
        const val THREAD_NAME = "p2p-runtime"
        const val MAX_SESSIONS = 12
        const val INIT_TIMEOUT_MS = 10_000L
        private val INIT_STRING = byteArrayOf(0)
    }
}
