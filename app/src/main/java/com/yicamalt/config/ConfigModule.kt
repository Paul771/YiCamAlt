// FILE: ConfigModule.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Central application configuration: feature flags, API base URLs, build variants, and runtime settings.
//   SCOPE: read BuildConfig fields once, expose typed accessors, validate keys.
//   DEPENDS: none
//   LINKS: M-HTTP, M-LOCAL-DB
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.config

import com.yicamalt.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

// START_MODULE_MAP
//   ConfigModule - singleton config provider backed by BuildConfig.
//   ConfigError - config error hierarchy (MissingKey).
//   FeatureFlag - well-known runtime feature flag names.
// END_MODULE_MAP

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

@Singleton
class ConfigModule @Inject constructor() {

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

    /** Return base URL for the Yi Cloud API. Throws if blank. */
    fun getApiBaseUrl(): String {
        // START_BLOCK_VALIDATE_KEY
        if (apiBaseUrl.isBlank()) throw ConfigError.MissingKey
        // END_BLOCK_VALIDATE_KEY
        return apiBaseUrl
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