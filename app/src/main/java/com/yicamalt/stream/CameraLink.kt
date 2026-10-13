// FILE: CameraLink.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Transport-neutral port for one live P2P session to one camera (TNP now, TUTK conditionally).
//   SCOPE: CameraLink / CameraLinkFactory / P2pCredentialsSource ports and the LinkState, LinkInfo, LinkMode,
//     LinkError, LinkErrorKind, LinkOptions values they expose.
//   DEPENDS: M-P2P-CREDS (P2pCredentials, TransportType)
//   LINKS: M-STREAM-CORE, V-M-STREAM-CORE, M-TNP-LINK, M-TUTK-LINK
//   ROLE: TYPES
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.stream

import com.yicamalt.camera.P2pCredentials
import com.yicamalt.camera.TransportType
import kotlinx.coroutines.flow.StateFlow

// START_MODULE_MAP
//   LinkMode - P2P | RELAY | TCP_RELAY | SDEV_RELAY | LAN | UNKNOWN (TNP bMode 0..3; TUTK mode 2 = LAN).
//   LinkInfo - transport, mode, lan flag, connect time; value-free.
//   LinkErrorKind - failure classes shown to the user (OFFLINE, CREDENTIALS, BUSY, KICKED, ...).
//   LinkError - kind + vendor/app code (0 when none).
//   LinkState - Idle | Connecting | Connected | Streaming | Failed (terminal) | Closed (terminal).
//   LinkOptions - per-press options (forceRelay).
//   CameraLink - one session: deviceId, state flow, start(sink), close(reason).
//   CameraLinkFactory - open(credentials, options); one session per camera.
//   P2pCredentialsSource - credentialsFor(deviceId) -> P2pCredentials?.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 02-00: contract-first port types for Phase 2. Doc comments use // only (no block comments).
//     INVARIANTS: no automatic reconnect (Failed / Closed are terminal); one session per camera; close() is
//     idempotent and bounded (~2 s).
// END_CHANGE_SUMMARY

// TNP bMode 0..3 map to P2P, RELAY, TCP_RELAY, SDEV_RELAY; TUTK mode 2 maps to LAN.
enum class LinkMode { P2P, RELAY, TCP_RELAY, SDEV_RELAY, LAN, UNKNOWN }

data class LinkInfo(val transport: TransportType, val mode: LinkMode, val lan: Boolean, val connectMs: Int)

enum class LinkErrorKind {
    NATIVE_UNAVAILABLE, NO_CREDENTIALS, UNSUPPORTED_TRANSPORT, ALREADY_OPEN, OFFLINE, CREDENTIALS,
    NETWORK, BUSY, WRONG_PASSWORD, REMOTE_CLOSED, KICKED, TIMEOUT, PROTOCOL, INTERNAL,
}

// [code] is the vendor or app code (e.g. -3006), 0 when none.
data class LinkError(val kind: LinkErrorKind, val code: Int)

sealed interface LinkState {
    object Idle : LinkState
    object Connecting : LinkState

    // Session up, startup burst not yet answered.
    data class Connected(val info: LinkInfo) : LinkState

    // First video unit received.
    data class Streaming(val info: LinkInfo, val authOk: Boolean) : LinkState

    // Terminal; no auto-retry.
    data class Failed(val error: LinkError) : LinkState

    // Terminal; reason in {user, kicked, screen-left, background, error, timeout}. error and timeout normally end
    // in Failed; the set is shared with the BLOCK_CLOSE log reason.
    data class Closed(val reason: String) : LinkState
}

data class LinkOptions(val forceRelay: Boolean = false)

interface CameraLink {
    val deviceId: String
    val state: StateFlow<LinkState>

    // Non-blocking: spawns the connect thread; units go to [sink] from reader threads. Second call is ignored.
    fun start(sink: VideoUnitSink)

    // Idempotent, callable from any thread, never blocks longer than ~2 s (abort + bounded join).
    fun close(reason: String = "user")
}

interface CameraLinkFactory {
    // One session per camera: when a link for credentials.deviceId is open, returns a link already in Failed(ALREADY_OPEN).
    fun open(credentials: P2pCredentials, options: LinkOptions): CameraLink
}

fun interface P2pCredentialsSource {
    fun credentialsFor(deviceId: String): P2pCredentials?
}
