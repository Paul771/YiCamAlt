// FILE: NativeLibs.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Load the vendor native libraries as a reported state (loaded / missing / error), never a crash.
//   SCOPE: loadPppp (libPPPP_API.so), loadTutk (IOTCAPIs, sCHL, AVAPIs in that order), cached per process;
//     primary ABI name; Hilt module P2pRuntimeModule (NativeLibs singleton, PpppNative -> AndroidPpppNative).
//   DEPENDS: vendor libs extracted at build time (app/build.gradle.kts extractVendorNatives)
//   LINKS: M-P2P-NATIVE, V-M-P2P-NATIVE
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.p2p

import android.os.Build
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import timber.log.Timber
import javax.inject.Singleton

// START_MODULE_MAP
//   LoadState - Loaded | Missing | Error(kind = exception simple class name only).
//   NativeLibs - loadPppp(), loadTutk(), primaryAbi(); loader injectable for JVM tests.
//   P2pRuntimeModule - Hilt: @Provides NativeLibs, @Binds PpppNative to AndroidPpppNative.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 02-03: created. Loading JNI_OnLoad-bearing libs happens only here; a missing
//     or wrong-ABI lib becomes LoadState.Missing / Error so the UI can report it. TUTK natives are never called here.
// END_CHANGE_SUMMARY

sealed interface LoadState {
    object Loaded : LoadState {
        override fun toString() = "Loaded"
    }

    object Missing : LoadState {
        override fun toString() = "Missing"
    }

    data class Error(val kind: String) : LoadState
}

class NativeLibs(private val loader: (String) -> Unit = System::loadLibrary) {

    private var pppp: LoadState? = null
    private var tutk: LoadState? = null

    // START_CONTRACT: loadPppp
    //   PURPOSE: Load libPPPP_API.so once (its JNI_OnLoad binds the com.p2p.pppp_api shim).
    //   INPUTS: none
    //   OUTPUTS: { LoadState - cached after the first call; never throws }
    //   SIDE_EFFECTS: System.loadLibrary on the first call; one BLOCK_LOAD_NATIVE log line
    //   LINKS: M-P2P-NATIVE
    // END_CONTRACT: loadPppp
    @Synchronized
    fun loadPppp(): LoadState {
        pppp?.let { return it }
        val state = load(listOf(PPPP_LIB))
        pppp = state
        logState()
        return state
    }

    // START_CONTRACT: loadTutk
    //   PURPOSE: Load the TUTK libs once, in dependency order; report one aggregated state.
    //   INPUTS: none
    //   OUTPUTS: { LoadState - first failure, else Loaded; cached; never throws }
    //   SIDE_EFFECTS: System.loadLibrary on the first call; one BLOCK_LOAD_NATIVE log line
    //   LINKS: M-P2P-NATIVE, M-TUTK-LINK
    // END_CONTRACT: loadTutk
    @Synchronized
    fun loadTutk(): LoadState {
        tutk?.let { return it }
        val state = load(TUTK_LIBS)
        tutk = state
        logState()
        return state
    }

    // Primary ABI of the device; "jvm" in unit tests where android.os.Build fields are stubs.
    fun primaryAbi(): String {
        val abis: Array<String>? = try {
            Build.SUPPORTED_ABIS
        } catch (t: Throwable) {
            null
        }
        if (abis == null) return "jvm"
        return abis.firstOrNull() ?: "unknown"
    }

    // START_BLOCK_LOAD_NATIVE
    private fun load(names: List<String>): LoadState {
        for (name in names) {
            try {
                loader(name)
            } catch (e: UnsatisfiedLinkError) {
                return LoadState.Missing
            } catch (e: SecurityException) {
                return LoadState.Error(e.javaClass.simpleName)
            } catch (e: LinkageError) {
                return LoadState.Error(e.javaClass.simpleName)
            }
        }
        return LoadState.Loaded
    }

    private fun logState() {
        Timber.d(
            "[P2P][native][BLOCK_LOAD_NATIVE] abi=%s pppp=%s tutk=%s",
            primaryAbi(),
            label(pppp, "skipped"),
            label(tutk, "skipped"),
        )
    }
    // END_BLOCK_LOAD_NATIVE

    companion object {
        const val PPPP_LIB = "PPPP_API"
        val TUTK_LIBS = listOf("IOTCAPIs", "sCHL", "AVAPIs")

        // ok | missing | error; `none` is used for a lib set that was not attempted yet.
        fun label(state: LoadState?, none: String): String = when (state) {
            null -> none
            LoadState.Loaded -> "ok"
            LoadState.Missing -> "missing"
            is LoadState.Error -> "error"
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class P2pRuntimeModule {

    @Binds
    @Singleton
    abstract fun bindPpppNative(impl: AndroidPpppNative): PpppNative

    companion object {
        @Provides
        @Singleton
        fun nativeLibs(): NativeLibs = NativeLibs()
    }
}
