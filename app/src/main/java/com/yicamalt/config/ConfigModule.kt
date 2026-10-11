// FILE: ConfigModule.kt
// VERSION: 0.2.0
// START_MODULE_CONTRACT
//   PURPOSE: Central application configuration: feature flags, API base URLs, build variants, and runtime settings.
//   SCOPE: read BuildConfig fields once, expose typed accessors, validate keys; effective API base URL
//     (override else build default), manual-server flag, RegionSelector implementation (CAM-02).
//   DEPENDS: none
//   LINKS: M-HTTP, M-LOCAL-DB, M-AUTH, V-M-CONFIG
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.config

import com.yicamalt.BuildConfig
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

// START_MODULE_MAP
//   ConfigModule - singleton config provider backed by BuildConfig; implements RegionSelector.
//   ConfigModule.setManualBaseUrl / isManualBaseUrl - user-typed server; disables automatic region changes.
//   SettingsStoreModule - Hilt bindings: SettingsStore, RegionSelector.
//   ConfigError - config error hierarchy (MissingKey).
//   FeatureFlag - well-known runtime feature flag names.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.2.0 - implements RegionSelector; default gateway is real (CAM-02). Manual-server flag:
//     while set, currentRegion() is null and selectRegion/restoreBaseUrl are no-ops, so login never
//     hops away from a server the user typed (even when it is one of the three gateways).
// END_CHANGE_SUMMARY

sealed class ConfigError(message: String) : Error(message) {
    /** Thrown when a required config key is absent or blank. */
    object MissingKey : ConfigError("CONFIG_MISSING_KEY: required config key is missing")
}

/** Well-known feature flag names. Stable identifiers — do not rename. */
object FeatureFlag {
    const val LOCAL_RTSP = "local_rtsp"
    const val CLOUD_RELAY = "cloud_relay"
    const val PUSH_FOREGROUND_SERVICE = "push_foreground_service"
    const val MULTI_CAMERA_VIEW = "multi_camera_view"
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsStoreModule {
    @Binds
    abstract fun bindSettingsStore(impl: PrefsSettingsStore): SettingsStore

    @Binds
    abstract fun bindRegionSelector(impl: ConfigModule): RegionSelector
}

@Singleton
open class ConfigModule @Inject constructor(
    private val settings: SettingsStore,
) : RegionSelector {

    // Secondary no-arg constructor so unit tests can build a config with an in-memory store.
    constructor() : this(InMemorySettingsStore())

    // START_BLOCK_INIT_CONFIG
    private val apiBaseUrl: String = BuildConfig.YI_API_BASE_URL
    private val localRtspEnabled: Boolean = BuildConfig.LOCAL_RTSP_ENABLED
    private val initializedOnce: Unit = Unit // marker: BuildConfig read exactly once at construction
    // END_BLOCK_INIT_CONFIG

    private val featureFlags: Map<String, Boolean> = mapOf(
        FeatureFlag.LOCAL_RTSP to localRtspEnabled,
        FeatureFlag.CLOUD_RELAY to true,
        FeatureFlag.PUSH_FOREGROUND_SERVICE to true,
        FeatureFlag.MULTI_CAMERA_VIEW to false,
    )

    init {
        // START_BLOCK_INIT_CONFIG_LOG
        TimberLog.d("[Config][init][BLOCK_INIT_CONFIG] config loaded apiBaseUrl=${apiBaseUrl.redactHost()} localRtsp=$localRtspEnabled")
        // END_BLOCK_INIT_CONFIG_LOG
    }

    /** Return base URL for the Yi Cloud API. Prefers a user override, else the BuildConfig default. Throws if blank. */
    open fun getApiBaseUrl(): String {
        // START_BLOCK_VALIDATE_KEY
        val override = settings.getApiBaseUrl()
        val url = if (!override.isNullOrBlank()) override else apiBaseUrl
        if (url.isBlank()) throw ConfigError.MissingKey
        // END_BLOCK_VALIDATE_KEY
        return url
    }

    /** Persist a runtime API base URL override; pass null/blank to clear and fall back to the default. */
    open fun setApiBaseUrl(url: String?) {
        settings.setApiBaseUrl(url?.trim()?.takeIf { it.isNotBlank() })
    }

    // START_CONTRACT: setManualBaseUrl
    //   PURPOSE: Store a server typed by the user in Settings and pin it (no automatic region changes).
    //   INPUTS: { url: String? - null/blank clears the override and returns to automatic selection }
    //   OUTPUTS: { Unit }
    //   SIDE_EFFECTS: writes the override and the manual flag to SettingsStore
    //   LINKS: M-UI-SETTINGS, M-AUTH
    // END_CONTRACT: setManualBaseUrl
    open fun setManualBaseUrl(url: String?) {
        setApiBaseUrl(url)
        settings.setManualBaseUrl(!url.isNullOrBlank())
    }

    /** True when the effective server was typed by the user; login then stays on it. */
    fun isManualBaseUrl(): Boolean = settings.isManualBaseUrl()

    /** Region of the effective base URL; null for a manual or unknown server (no hop, no discovery). */
    override fun currentRegion(): YiRegion? =
        if (isManualBaseUrl()) null else YiRegion.fromBaseUrl(getApiBaseUrl())

    // START_CONTRACT: selectRegion
    //   PURPOSE: Make the region's gateway the effective base URL (resolved per request by M-HTTP).
    //   INPUTS: { region: YiRegion }
    //   OUTPUTS: { Unit }
    //   SIDE_EFFECTS: writes the base URL override; never sets the manual flag; no-op while manual
    //   LINKS: M-AUTH, M-CAMERA-LIST
    // END_CONTRACT: selectRegion
    override fun selectRegion(region: YiRegion) {
        // START_BLOCK_SELECT_REGION
        if (isManualBaseUrl()) return
        setApiBaseUrl(region.baseUrl)
        TimberLog.d("[Config][region][BLOCK_SELECT_REGION] region=${region.name}")
        // END_BLOCK_SELECT_REGION
    }

    override fun currentBaseUrl(): String = getApiBaseUrl()

    /** Put a previously read effective base URL back; never sets the manual flag; no-op while manual. */
    override fun restoreBaseUrl(url: String) {
        if (isManualBaseUrl()) return
        setApiBaseUrl(url)
    }

    /** Return runtime feature flag map. */
    fun getFeatureFlags(): Map<String, Boolean> = featureFlags.toMap()

    /** Check whether local RTSP streaming is allowed by config. */
    fun isLocalRtspEnabled(): Boolean = featureFlags[FeatureFlag.LOCAL_RTSP] ?: false

    /** Look up a single flag by name. Throws MissingKey if absent. */
    fun flag(name: String): Boolean {
        // START_BLOCK_VALIDATE_KEY
        if (!featureFlags.containsKey(name)) throw ConfigError.MissingKey
        // END_BLOCK_VALIDATE_KEY
        return featureFlags.getValue(name)
    }

    private fun String.redactHost(): String =
        this.replace(Regex("://[^@/]+@"), "://***@")
}

// Tiny indirection so unit tests can swap logging without depending on Timber tree state.
internal object TimberLog {
    fun d(message: String) {
        // timber.log.Timber is the production sink; TraceRecorder plants its own tree in tests.
        timber.log.Timber.d(message)
    }
}