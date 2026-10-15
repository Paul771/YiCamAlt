// FILE: TutkRuntime.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Initialise the vendor TUTK IOTC and AV libraries once per process and report their versions.
//   SCOPE: TutkRuntimeState family; ensureInitialized() (load IOTCAPIs/sCHL/AVAPIs, IOTC_Initialize2(0),
//     avInitialize(32), versions) on a dedicated "tutk-runtime" thread; observable state; Hilt TutkRuntimeModule.
//   DEPENDS: M-TUTK-LINK (TutkNative, TutkCodes), M-P2P-NATIVE (NativeLibs, LoadState)
//   LINKS: M-TUTK-LINK, V-M-TUTK-LINK, M-P2P-NATIVE
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.p2p.tutk

import com.yicamalt.p2p.LoadState
import com.yicamalt.p2p.NativeLibs
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
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
//   TutkRuntimeState - NotStarted | Unavailable | InitFailed(code) | Ready(iotcVersion, avVersion; hex strings).
//   TutkRuntime - ensureInitialized(), state; THREAD_NAME, MAX_AV_CHANNELS, INIT_TIMEOUT_MS.
//   TutkRuntimeModule - Hilt: @Binds TutkNative to AndroidTutkNative.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 02-10: created, mirroring PpppRuntime. Init once per process; never
//     de-initialised; a missing library is the Unavailable state with zero native calls.
// END_CHANGE_SUMMARY

sealed interface TutkRuntimeState {
    object NotStarted : TutkRuntimeState {
        override fun toString() = "NotStarted"
    }

    object Unavailable : TutkRuntimeState {
        override fun toString() = "Unavailable"
    }

    data class InitFailed(val code: Int) : TutkRuntimeState

    data class Ready(val iotcVersion: Int, val avVersion: Int) : TutkRuntimeState {
        val iotcVersionHex: String get() = "0x%08X".format(iotcVersion)
        val avVersionHex: String get() = "0x%08X".format(avVersion)
    }
}

@Singleton
class TutkRuntime @Inject constructor(
    private val native: TutkNative,
    private val libs: NativeLibs,
) {
    private val lock = Any()
    private val _state = MutableStateFlow<TutkRuntimeState>(TutkRuntimeState.NotStarted)
    val state: StateFlow<TutkRuntimeState> = _state.asStateFlow()

    // Bound for the first init; tests shorten it.
    internal var initTimeoutMs: Long = INIT_TIMEOUT_MS

    private var pending: Future<TutkRuntimeState>? = null

    private val executor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, THREAD_NAME).apply { isDaemon = true } }
    }

    // START_CONTRACT: ensureInitialized
    //   PURPOSE: Make the TUTK IOTC and AV libraries usable: once per process, never de-initialised.
    //   INPUTS: none
    //   OUTPUTS: { TutkRuntimeState - Ready(iotcVersion, avVersion) when IOTC_Initialize2 returned >= 0 or -3 and
    //     avInitialize returned >= 0 or -20031; InitFailed(code) for any other negative code, or IOTC_ER_TIMEOUT
    //     (-13) after a 10 s bound; Unavailable when a TUTK library did not load (no native call made) }
    //   SIDE_EFFECTS: first call only: System.loadLibrary x3, IOTC_Initialize2(0), avInitialize(32),
    //     IOTC_Get_Version, avGetAVApiVer on the "tutk-runtime" thread; one BLOCK_INIT_TUTK log line. Later calls:
    //     none (a timed-out init is adopted once its native call finishes).
    //   LINKS: M-TUTK-LINK, M-P2P-NATIVE
    // END_CONTRACT: ensureInitialized
    fun ensureInitialized(): TutkRuntimeState = synchronized(lock) {
        val current = _state.value
        if (current != TutkRuntimeState.NotStarted) {
            val late = pending
            if (current == TutkRuntimeState.InitFailed(TutkCodes.IOTC_TIMEOUT) && late != null && late.isDone) {
                pending = null
                _state.value = runCatching { late.get() }.getOrDefault(current)
            }
            return _state.value
        }
        if (libs.loadTutk() != LoadState.Loaded) {
            Timber.d("[P2P][tutk][BLOCK_INIT_TUTK] result=unavailable reason=libs")
            _state.value = TutkRuntimeState.Unavailable
            return TutkRuntimeState.Unavailable
        }
        val future = executor.submit<TutkRuntimeState> { initOnRuntimeThread() }
        val result = try {
            future.get(initTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            pending = future
            Timber.d("[P2P][tutk][BLOCK_INIT_TUTK] result=timeout boundMs=%d", initTimeoutMs)
            TutkRuntimeState.InitFailed(TutkCodes.IOTC_TIMEOUT)
        }
        _state.value = result
        result
    }

    // START_BLOCK_INIT_TUTK
    private fun initOnRuntimeThread(): TutkRuntimeState {
        val iotc = native.initialize()
        if (!TutkCodes.iotcInitOk(iotc)) {
            Timber.d("[P2P][tutk][BLOCK_INIT_TUTK] iotc=%d name=%s result=failed", iotc, TutkCodes.name(iotc))
            return TutkRuntimeState.InitFailed(iotc)
        }
        val av = native.avInitialize(MAX_AV_CHANNELS)
        if (!TutkCodes.avInitOk(av)) {
            Timber.d("[P2P][tutk][BLOCK_INIT_TUTK] iotc=%d av=%d name=%s result=failed", iotc, av, TutkCodes.name(av))
            return TutkRuntimeState.InitFailed(av)
        }
        val ready = TutkRuntimeState.Ready(native.iotcVersion(), native.avVersion())
        Timber.d(
            "[P2P][tutk][BLOCK_INIT_TUTK] iotc=%d av=%d iotcVer=%s avVer=%s result=ready",
            iotc, av, ready.iotcVersionHex, ready.avVersionHex,
        )
        return ready
    }
    // END_BLOCK_INIT_TUTK

    companion object {
        const val THREAD_NAME = "tutk-runtime"

        // Vendor value: maxCameras (2) * 16.
        const val MAX_AV_CHANNELS = 32
        const val INIT_TIMEOUT_MS = 10_000L
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class TutkRuntimeModule {

    @Binds
    @Singleton
    abstract fun bindTutkNative(impl: AndroidTutkNative): TutkNative
}
