// FILE: MediaHttp.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Plain HTTP client for alert previews and clips on the vendor CDN.
//   SCOPE: plainClient - lazily built OkHttpClient (connect 5 s, read 20 s, redirects on, no connection retry)
//     with no interceptors, no authenticator and no logger.
//   DEPENDS: none
//   LINKS: M-EVENT-PLAYBACK, V-M-EVENT-PLAYBACK, EVT-03
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.event

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

// START_MODULE_MAP
//   MediaHttp - object: plainClient() shared plain client for signed media URLs.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 05-00: separate client for media downloads (research §2 "Network client").
//   HAZARD: media URLs are signed CDN URLs on a foreign host. The shared Yi client would add our Authorization
//     header (token leak, CDN rejects) and its logger would print the signed URL. Never add an interceptor,
//     authenticator or logger here, and never log the URL at call sites.
// END_CHANGE_SUMMARY

object MediaHttp {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(false)
            .build()
    }

    // START_CONTRACT: plainClient
    //   PURPOSE: Client for one-shot media GETs (no auth header, no logging, no automatic retry).
    //   INPUTS: none
    //   OUTPUTS: { OkHttpClient - process-wide plain client }
    //   SIDE_EFFECTS: builds the client on first use
    //   LINKS: M-EVENT-PLAYBACK
    // END_CONTRACT: plainClient
    fun plainClient(): OkHttpClient = client
}
