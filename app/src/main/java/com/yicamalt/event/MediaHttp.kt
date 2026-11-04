// FILE: MediaHttp.kt
// VERSION: 0.3.0
// START_MODULE_CONTRACT
//   PURPOSE: Plain HTTP client for alert previews and clips on the vendor CDN.
//   SCOPE: plainClient - lazily built OkHttpClient (connect 5 s, read 20 s, redirects on, transparent reconnect of a
//     dead pooled connection on, no app-level retry)
//     with no interceptors, no authenticator and no logger; secureUrl - http -> https rewrite of media URLs.
//   DEPENDS: none
//   LINKS: M-EVENT-PLAYBACK, V-M-EVENT-PLAYBACK, EVT-03
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.event

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

// START_MODULE_MAP
//   MediaHttp - object: plainClient() shared plain client for signed media URLs.
//   MediaHttp.secureUrl - https form of an http media URL (path and query kept).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.3.0 - Phase 5 device bug A: a clip opened a few minutes after the previous one failed in ~10 ms
//     with status=-1 (no request reached the CDN). The previous download left a keep-alive connection in the pool,
//     the CDN closed it while idle, and with retryOnConnectionFailure(false) OkHttp surfaced the dead socket as an
//     IOException instead of reconnecting. Reconnect is on now (GET only, same request, OkHttp's own bounded
//     recovery); the app still never repeats a request on its own. Evidence: PlainMediaFetcherTest.
//   PREV_CHANGE: v0.2.0 - GSD plan 05-01: secureUrl. 05-PROBE: pic_urls / video_urls are http, the app
//     forbids cleartext (UnknownServiceException), the same path over https works (200, decrypt ok).
//     Media URLs are rewritten to https; the network security config stays cleartext-off (no global or
//     per-host cleartext exception).
//   OLDER_CHANGE: v0.1.0 - GSD plan 05-00: separate client for media downloads (research §2 "Network client").
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
            // A pooled keep-alive connection that the CDN closed while idle must be replaced, not reported as a
            // failed download (Phase 5 bug A). OkHttp only recovers connection-level failures here.
            .retryOnConnectionFailure(true)
            .build()
    }

    // START_CONTRACT: plainClient
    //   PURPOSE: Client for one-shot media GETs (no auth header, no logging, no app-level retry; OkHttp reconnects
    //     a dead pooled connection inside the same call).
    //   INPUTS: none
    //   OUTPUTS: { OkHttpClient - process-wide plain client }
    //   SIDE_EFFECTS: builds the client on first use
    //   LINKS: M-EVENT-PLAYBACK
    // END_CONTRACT: plainClient
    fun plainClient(): OkHttpClient = client

    // START_CONTRACT: secureUrl
    //   PURPOSE: https form of a media URL (05-PROBE D-PROBE-5/6: the feed gives http URLs, the app forbids
    //     cleartext, the same path works over https). Only for media URLs; cleartext is never enabled.
    //   INPUTS: { url: String - media URL from pic_urls / video_urls }
    //   OUTPUTS: { String - same URL with scheme https (path, query, non-default port kept, default port 80
    //     dropped); https or unparseable input returned trimmed and otherwise unchanged }
    //   SIDE_EFFECTS: none
    //   LINKS: AlertMapper.page, M-EVENT-PLAYBACK
    // END_CONTRACT: secureUrl
    fun secureUrl(url: String): String {
        // START_BLOCK_MEDIA_HTTPS_REWRITE
        val trimmed = url.trim()
        val parsed = trimmed.toHttpUrlOrNull() ?: return trimmed
        if (parsed.scheme != "http") return trimmed
        return parsed.newBuilder().scheme("https").build().toString()
        // END_BLOCK_MEDIA_HTTPS_REWRITE
    }
}
