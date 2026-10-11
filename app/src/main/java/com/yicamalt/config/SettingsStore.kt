// FILE: SettingsStore.kt
// VERSION: 0.2.0
// START_MODULE_CONTRACT
//   PURPOSE: Persist user-editable runtime settings (API base URL override) so the app can point at a real Yi Cloud endpoint.
//   SCOPE: read/write an optional API base URL override and the manual-server flag; in-memory variant for tests.
//   DEPENDS: none
//   LINKS: M-CONFIG
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.config

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

// START_MODULE_MAP
//   SettingsStore - persisted runtime settings (API base URL override, manual-server flag).
//   PrefsSettingsStore - SharedPreferences-backed implementation.
//   InMemorySettingsStore - test-only in-memory implementation.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.2.0 - CAM-02: manual-server flag (prefs key api_base_url_manual, default false).
//     true = the user typed the server in Settings; region hop and region discovery are then disabled.
// END_CHANGE_SUMMARY

/** Runtime settings store. Kept as an interface so tests can inject an in-memory variant. */
interface SettingsStore {
    /** Optional user-configured API base URL override, or null to use the BuildConfig default. */
    fun getApiBaseUrl(): String?

    /** Persist an API base URL override; pass null/blank to clear and fall back to the default. */
    fun setApiBaseUrl(url: String?)

    /** True when the override was typed by the user in Settings (never changed automatically). */
    fun isManualBaseUrl(): Boolean

    /** Mark the override as user-typed (true) or automatic (false). */
    fun setManualBaseUrl(manual: Boolean)
}

/** SharedPreferences-backed implementation used in production. */
@Singleton
class PrefsSettingsStore @Inject constructor(
    @ApplicationContext context: Context,
) : SettingsStore {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("yicamalt_settings", Context.MODE_PRIVATE)

    override fun getApiBaseUrl(): String? = prefs.getString(KEY_API_BASE_URL, null)

    override fun setApiBaseUrl(url: String?) {
        prefs.edit().putString(KEY_API_BASE_URL, url).apply()
    }

    override fun isManualBaseUrl(): Boolean = prefs.getBoolean(KEY_API_BASE_URL_MANUAL, false)

    override fun setManualBaseUrl(manual: Boolean) {
        prefs.edit().putBoolean(KEY_API_BASE_URL_MANUAL, manual).apply()
    }

    private companion object {
        const val KEY_API_BASE_URL = "api_base_url"
        const val KEY_API_BASE_URL_MANUAL = "api_base_url_manual"
    }
}

/** In-memory implementation for unit tests. */
class InMemorySettingsStore : SettingsStore {
    private var url: String? = null
    override fun getApiBaseUrl(): String? = url
    override fun setApiBaseUrl(url: String?) { this.url = url }
    private var manual: Boolean = false
    override fun isManualBaseUrl(): Boolean = manual
    override fun setManualBaseUrl(manual: Boolean) { this.manual = manual }
}
