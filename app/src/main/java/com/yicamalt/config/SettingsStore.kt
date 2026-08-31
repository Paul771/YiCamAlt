// FILE: SettingsStore.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Persist user-editable runtime settings (API base URL override) so the app can point at a real Yi Cloud endpoint.
//   SCOPE: read/write an optional API base URL override; in-memory variant for tests.
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
//   SettingsStore - persisted runtime settings (API base URL override).
//   PrefsSettingsStore - SharedPreferences-backed implementation.
//   InMemorySettingsStore - test-only in-memory implementation.
// END_MODULE_MAP

/** Runtime settings store. Kept as an interface so tests can inject an in-memory variant. */
interface SettingsStore {
    /** Optional user-configured API base URL override, or null to use the BuildConfig default. */
    fun getApiBaseUrl(): String?

    /** Persist an API base URL override; pass null/blank to clear and fall back to the default. */
    fun setApiBaseUrl(url: String?)
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

    private companion object {
        const val KEY_API_BASE_URL = "api_base_url"
    }
}

/** In-memory implementation for unit tests. */
class InMemorySettingsStore : SettingsStore {
    private var url: String? = null
    override fun getApiBaseUrl(): String? = url
    override fun setApiBaseUrl(url: String?) { this.url = url }
}
