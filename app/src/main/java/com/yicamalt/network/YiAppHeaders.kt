// FILE: YiAppHeaders.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Official Yi Home Android app identity headers for Yi Cloud device/deviceshare/tnp requests.
//   SCOPE: header map (appType, packageType, country, appVersion, User-Agent) + the host/path rule
//     deciding where they apply. Pure; no I/O, no logging.
//   DEPENDS: none
//   LINKS: M-HTTP
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.network

// START_MODULE_MAP
//   YiAppHeaders - official app header set (Yi Home 6.9.8, versionCode 454) + appliesTo(host, path).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - Headers from research STACK §2a; version identity read from local Yi Home 6.9.8
//     base.apk via aapt2 (versionCode 454). Login paths (/v4/users/…) are excluded so the verified
//     login request stays byte-identical.
// END_CHANGE_SUMMARY

internal object YiAppHeaders {
    const val APP_VERSION_CODE = 454
    const val APP_VERSION_NAME = "6.9.8_20260826105838"

    // START_CONTRACT: headers
    //   PURPOSE: Build the ordered official app header map.
    //   INPUTS: { countryCode: String - ISO country, osRelease: String? - Android release (null on JVM) }
    //   OUTPUTS: { Map<String,String> - header name to value }
    //   SIDE_EFFECTS: none
    // END_CONTRACT: headers
    fun headers(
        countryCode: String = defaultCountry(),
        osRelease: String? = android.os.Build.VERSION.RELEASE,
    ): Map<String, String> = linkedMapOf(
        "x-kamihome-appType" to "ANDROID",
        "x-kamihome-packageType" to "RELEASE",
        "x-xiaoyi-appCountryCode" to countryCode,
        "x-xiaoyi-appVersion" to "android;$APP_VERSION_CODE;$APP_VERSION_NAME",
        "User-Agent" to "yihome/$APP_VERSION_NAME (Android ${osRelease ?: "14"})",
    )

    fun defaultCountry(): String = java.util.Locale.getDefault().country.ifBlank { "US" }

    // START_CONTRACT: appliesTo
    //   PURPOSE: True for placeholder or *.xiaoyi.com hosts, except login/auth paths.
    //   INPUTS: { host: String, encodedPath: String - path BEFORE base-path rewrite }
    //   OUTPUTS: { Boolean }
    //   SIDE_EFFECTS: none
    // END_CONTRACT: appliesTo
    fun appliesTo(host: String, encodedPath: String): Boolean =
        (host == HttpClientModule.PLACEHOLDER_HOST || host.endsWith(".xiaoyi.com")) && !encodedPath.startsWith("/v4/users/")
}
