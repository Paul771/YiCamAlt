// FILE: LiveSessionSupervisor.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: One live screen's session owner: one link at a time, bounded reconnect, network events, stall watchdog,
//     quality requests and the background-resume rule (D-01..D-03, D-10, LIVE-04/05/10/11).
//   SCOPE: start / retry / stop / onForeground / setQuality; classify(LinkError), classifyClosed(reason).
//   DEPENDS: M-STREAM-CORE (CameraLink ports), M-P2P-CREDS, live/LiveSessionTypes.kt, QualityController, ResumePolicy
//   LINKS: M-LIVE-SESSION, V-M-LIVE-SESSION
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.live

import com.yicamalt.stream.CameraLinkFactory
import com.yicamalt.stream.LinkError
import com.yicamalt.stream.LinkState
import com.yicamalt.stream.P2pCredentialsSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

// START_MODULE_MAP
//   LiveSessionSupervisor - state / qualityState / currentLinkState; start, retry, stop, onForeground, setQuality.
//   LiveSessionSupervisor.Timing - backoff 2/5/10/20 s, 5 attempts, auth 2 s, kick 10 s, stall 10 s, drain 5 s, ...
//   classify - LinkError -> ErrorClass.
//   classifyClosed - Closed reason -> ErrorClass, null for our own stops.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 03-06 RED: signatures only.
// END_CHANGE_SUMMARY

class LiveSessionSupervisor(
    private val deviceId: String,
    private val credentials: P2pCredentialsSource,
    private val factory: CameraLinkFactory,
    private val sessions: MediaSessionFactory,
    private val network: NetworkMonitor,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val now: () -> Long,
    private val timing: Timing = Timing(),
    private val quality: QualityController = QualityController(timing.qualitySwitchMs),
    private val resume: ResumePolicy = ResumePolicy(timing.resumeWindowMs),
) {
    data class Timing(
        val networkDelaysMs: List<Long> = listOf(2_000, 5_000, 10_000, 20_000),
        val maxAttempts: Int = 5,
        val wrongPasswordDelayMs: Long = 2_000,
        val kickDelayMs: Long = 10_000,
        val stallMs: Long = 10_000,
        val healthyResetMs: Long = 30_000,
        val drainMs: Long = 5_000,
        val networkChangeDebounceMs: Long = 1_500,
        val tickMs: Long = 1_000,
        val qualitySwitchMs: Long = 8_000,
        val resumeWindowMs: Long = 300_000,
    )

    val state: StateFlow<SupervisorState> get() = error("RED stub")
    val qualityState: StateFlow<QualityState> get() = error("RED stub")
    val currentLinkState: StateFlow<LinkState> get() = error("RED stub")

    fun start(): Unit = error("RED stub")
    fun retry() = start()
    fun stop(reason: String): Unit = error("RED stub")
    fun onForeground(): Boolean = error("RED stub")
    fun setQuality(q: Quality): Unit = error("RED stub")
}

fun classify(error: LinkError): ErrorClass = kotlin.error("RED stub")

fun classifyClosed(reason: String): ErrorClass? = kotlin.error("RED stub")
