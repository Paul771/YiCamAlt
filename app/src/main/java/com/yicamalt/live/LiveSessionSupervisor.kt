// FILE: LiveSessionSupervisor.kt
// VERSION: 0.2.0
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

import com.yicamalt.camera.P2pCredentials
import com.yicamalt.stream.CameraLink
import com.yicamalt.stream.CameraLinkFactory
import com.yicamalt.stream.LinkError
import com.yicamalt.stream.LinkErrorKind
import com.yicamalt.stream.LinkMode
import com.yicamalt.stream.LinkOptions
import com.yicamalt.stream.LinkState
import com.yicamalt.stream.P2pCredentialsSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

// START_MODULE_MAP
//   LiveSessionSupervisor - state / qualityState / currentLinkState; start, retry, stop, onForeground, setQuality.
//   LiveSessionSupervisor.Timing - backoff 2/5/10/20 s, 5 attempts, auth 2 s, kick 10 s, stall 10 s, drain 5 s, ...
//   classify - LinkError -> ErrorClass.
//   classifyClosed - Closed reason -> ErrorClass, null for our own stops.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.2.0 - GSD plan 03-06 GREEN: run loop (network wait, drain, credentials, open, watch, classify,
//     schedule), stall watchdog, network-change debounce, quality requests, stop / resume. INVARIANTS: one link per
//     run; a new open only after the previous link is terminal or 5 s passed; links never retry themselves; an open
//     that returns after stop() is closed with the stop reason and never started (token check under lock). State
//     is mutated on the scope's dispatcher; only the open/close/refresh calls run on ioDispatcher. Logs value-free.
//   PREVIOUS: v0.1.0 - GSD plan 03-06 RED: signatures only.
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

    // Result of one link: cls null = ended by a stop of ours (no retry).
    private class Outcome(val cls: ErrorClass?, val error: LinkError?, val ownReason: String?, val healthy: Boolean)

    // Per-link watch state; touched only on the scope's dispatcher.
    private class Watch {
        var live = false
        var mode: LinkMode = LinkMode.UNKNOWN
        var streamingSince = 0L
        var lastFrames = 0L
        var lastGrowthAt = 0L
        var lastKeyFrame: KeyFrameObservation? = null
        var healthy = false
    }

    private val _state = MutableStateFlow<SupervisorState>(SupervisorState.Idle)
    val state: StateFlow<SupervisorState> = _state.asStateFlow()
    private val _quality = MutableStateFlow(quality.state)
    val qualityState: StateFlow<QualityState> = _quality.asStateFlow()
    private val _linkState = MutableStateFlow<LinkState>(LinkState.Idle)
    val currentLinkState: StateFlow<LinkState> = _linkState.asStateFlow()

    // Closes are fire-and-forget on io and must survive the cancellation of [scope] (screen-left from onCleared).
    private val closer = CoroutineScope(SupervisorJob() + ioDispatcher)

    // Guards token / currentLink / currentSession / stopReason against the io-side open.
    private val lock = Any()
    private var token = 0
    private var currentLink: CameraLink? = null
    private var currentSession: MediaSession? = null
    private var stopReason = REASON_USER

    @Volatile private var job: Job? = null
    @Volatile private var previousLink: CameraLink? = null
    @Volatile private var liveLink: CameraLink? = null
    @Volatile private var stoppedAtMs: Long? = null

    // START_CONTRACT: start
    //   PURPOSE: Fresh run: counters reset, any current link closed ("user"), pending resume cleared.
    //   INPUTS: none
    //   OUTPUTS: { Unit }
    //   SIDE_EFFECTS: launches the run loop in scope; may close the current link on io
    //   LINKS: M-LIVE-SESSION
    // END_CONTRACT: start
    fun start() {
        val (oldLink, oldSession, my) = synchronized(lock) {
            token++
            val l = currentLink
            val s = currentSession
            currentLink = null
            currentSession = null
            Triple(l, s, token)
        }
        job?.cancel()
        liveLink = null
        if (oldLink != null) {
            previousLink = oldLink
            closer.launch { oldLink.close(REASON_USER) }
        }
        oldSession?.release()
        resume.onStarted()
        job = scope.launch { runLoop(my) }
    }

    fun retry() = start()

    // START_CONTRACT: stop
    //   PURPOSE: End the run now: cancel waits, close the link with [reason], release the media session.
    //   INPUTS: { reason: String - user | screen-left | background }
    //   OUTPUTS: { Unit }
    //   SIDE_EFFECTS: state Stopped(reason); ResumePolicy.onStopped; link close on io
    //   LINKS: M-LIVE-SESSION, ResumePolicy
    // END_CONTRACT: stop
    fun stop(reason: String) {
        // START_BLOCK_STOP
        val s = _state.value
        val wasActive = s is SupervisorState.Connecting || s is SupervisorState.Waiting ||
            s is SupervisorState.Live || s is SupervisorState.NoNetwork
        val (link, session) = synchronized(lock) {
            token++
            stopReason = reason
            val l = currentLink
            val ses = currentSession
            currentLink = null
            currentSession = null
            l to ses
        }
        job?.cancel()
        job = null
        liveLink = null
        if (link != null) previousLink = link
        val target = link ?: previousLink
        if (target != null) closer.launch { target.close(reason) }
        session?.release()
        val t = now()
        resume.onStopped(reason, wasActive, t)
        stoppedAtMs = t
        _state.value = SupervisorState.Stopped(reason)
        Timber.d("[LiveSession][supervisor][BLOCK_STOP] reason=%s wasActive=%b", reason, wasActive)
        // END_BLOCK_STOP
    }

    // START_CONTRACT: onForeground
    //   PURPOSE: Auto-resume after a background stop of an active stream within the resume window (D-10).
    //   INPUTS: none
    //   OUTPUTS: { Boolean - true when a new run was started }
    //   SIDE_EFFECTS: may call start()
    //   LINKS: ResumePolicy
    // END_CONTRACT: onForeground
    fun onForeground(): Boolean {
        // START_BLOCK_RESUME
        val t = now()
        val resumed = resume.shouldAutoResume(t)
        val age = stoppedAtMs?.let { t - it } ?: -1L
        Timber.d("[LiveSession][supervisor][BLOCK_RESUME] resumed=%b ageMs=%d", resumed, age)
        if (resumed) start()
        return resumed
        // END_BLOCK_RESUME
    }

    // START_CONTRACT: setQuality
    //   PURPOSE: Manual HD/SD for this screen; sends 4881 at once when Live and the observed quality differs.
    //   INPUTS: { q: Quality }
    //   OUTPUTS: { Unit }
    //   SIDE_EFFECTS: QualityController.setManual; may call link.requestResolution
    //   LINKS: QualityController
    // END_CONTRACT: setQuality
    fun setQuality(q: Quality) {
        val send = quality.setManual(q)
        val link = liveLink
        if (send && link != null) sendQuality(link, q, "manual") else publishQuality()
    }

    private fun isCurrent(my: Int) = synchronized(lock) { token == my }

    private fun publish(my: Int, s: SupervisorState) {
        if (isCurrent(my)) _state.value = s
    }

    private fun publishQuality() {
        _quality.value = quality.state
    }

    // START_CONTRACT: runLoop
    //   PURPOSE: One run: network wait, drain, credentials, open, watch, classify, schedule; ends in a final state.
    //   INPUTS: { my: Int - run token }
    //   OUTPUTS: { Unit }
    //   SIDE_EFFECTS: opens / closes links, publishes state
    //   LINKS: M-LIVE-SESSION
    // END_CONTRACT: runLoop
    private suspend fun runLoop(my: Int) {
        var attempt = 1
        var wrongPasswordUsed = false
        var kickUsed = false
        var refreshNext = false
        while (true) {
            if (!network.state.value.available) {
                awaitNetwork(my)
                attempt = 1
            }
            drainPrevious()
            val creds: P2pCredentials? = if (refreshNext) {
                refreshNext = false
                withContext(ioDispatcher) { credentials.refreshCredentials(deviceId) }
            } else {
                credentials.credentialsFor(deviceId)
            }
            if (creds == null) {
                Timber.w("[LiveSession][supervisor][BLOCK_CLASSIFY] cls=FATAL kind=NO_CREDENTIALS code=0")
                publish(my, SupervisorState.Fatal(LinkError(LinkErrorKind.NO_CREDENTIALS, 0)))
                return
            }
            publish(my, SupervisorState.Connecting(attempt))
            val outcome = runLink(my, creds, attempt) ?: return
            if (!isCurrent(my)) return
            if (outcome.healthy) {
                attempt = 1
                wrongPasswordUsed = false
                kickUsed = false
            }
            // START_BLOCK_CLASSIFY
            val cls = outcome.cls
            Timber.d(
                "[LiveSession][supervisor][BLOCK_CLASSIFY] cls=%s kind=%s code=%d reason=%s",
                cls?.name ?: "none", outcome.error?.kind?.name ?: "none", outcome.error?.code ?: 0, outcome.ownReason ?: "none",
            )
            if (cls == null) {
                publish(my, SupervisorState.Stopped(stopReasonOf(outcome)))
                return
            }
            if (outcome.ownReason == REASON_NETWORK_CHANGE) {
                attempt = 1
                logSchedule(1, 0, ErrorClass.NETWORK)
                continue
            }
            // END_BLOCK_CLASSIFY
            // START_BLOCK_SCHEDULE
            val delayMs: Long
            when (cls) {
                ErrorClass.NETWORK -> {
                    if (attempt >= timing.maxAttempts) {
                        publish(my, SupervisorState.Exhausted(outcome.error))
                        Timber.d("[LiveSession][supervisor][BLOCK_SCHEDULE] result=exhausted attempt=%d", attempt)
                        return
                    }
                    delayMs = timing.networkDelaysMs[(attempt - 1).coerceIn(0, timing.networkDelaysMs.size - 1)]
                }
                ErrorClass.WRONG_PASSWORD -> {
                    if (wrongPasswordUsed) {
                        publish(my, SupervisorState.AuthFailed)
                        Timber.d("[LiveSession][supervisor][BLOCK_SCHEDULE] result=auth-failed")
                        return
                    }
                    wrongPasswordUsed = true
                    refreshNext = true
                    delayMs = timing.wrongPasswordDelayMs
                }
                ErrorClass.KICK -> {
                    if (kickUsed) {
                        publish(my, SupervisorState.Kicked)
                        Timber.d("[LiveSession][supervisor][BLOCK_SCHEDULE] result=kicked")
                        return
                    }
                    kickUsed = true
                    delayMs = timing.kickDelayMs
                }
                ErrorClass.FATAL -> {
                    publish(my, SupervisorState.Fatal(outcome.error ?: LinkError(LinkErrorKind.INTERNAL, 0)))
                    Timber.d("[LiveSession][supervisor][BLOCK_SCHEDULE] result=fatal")
                    return
                }
            }
            val next = attempt + 1
            publish(my, SupervisorState.Waiting(next, delayMs, cls, outcome.error))
            logSchedule(next, delayMs, cls)
            // A lost network during the wait jumps to NoNetwork without consuming attempts.
            val lost = withTimeoutOrNull(delayMs) { network.state.first { !it.available } } != null
            attempt = if (lost) 1 else next
            // END_BLOCK_SCHEDULE
        }
    }

    private fun stopReasonOf(outcome: Outcome): String = outcome.ownReason ?: REASON_USER

    private fun logSchedule(next: Int, delayMs: Long, cause: ErrorClass) {
        Timber.d("[LiveSession][supervisor][BLOCK_SCHEDULE] nextAttempt=%d delayMs=%d cause=%s", next, delayMs, cause.name)
    }

    private suspend fun awaitNetwork(my: Int) {
        // START_BLOCK_NETWORK
        publish(my, SupervisorState.NoNetwork)
        Timber.d("[LiveSession][supervisor][BLOCK_NETWORK] available=false generationChanged=false phase=wait")
        network.state.first { it.available }
        Timber.d("[LiveSession][supervisor][BLOCK_NETWORK] available=true generationChanged=false phase=wait")
        // END_BLOCK_NETWORK
    }

    private suspend fun drainPrevious() {
        // START_BLOCK_DRAIN
        val prev = previousLink ?: return
        val t0 = now()
        val terminal = withTimeoutOrNull(timing.drainMs) { prev.state.first { isTerminal(it) } } != null
        Timber.d(
            "[LiveSession][supervisor][BLOCK_DRAIN] waitedMs=%d result=%s",
            now() - t0, if (terminal) "terminal" else "timeout",
        )
        // END_BLOCK_DRAIN
    }

    // Returns null when the run was stopped meanwhile (stop() owns the cleanup then).
    private suspend fun runLink(my: Int, creds: P2pCredentials, attempt: Int): Outcome? {
        // START_BLOCK_OPEN_LINK
        val session = sessions.create(creds)
        val registered = synchronized(lock) {
            if (token == my) {
                currentSession = session
                true
            } else {
                false
            }
        }
        if (!registered) {
            session.release()
            return null
        }
        var openFailed = false
        val link = withContext(ioDispatcher + NonCancellable) {
            val opened = try {
                factory.open(creds, LinkOptions())
            } catch (e: Exception) {
                Timber.w("[LiveSession][supervisor][BLOCK_OPEN_LINK] result=open-threw error=%s", e.javaClass.simpleName)
                openFailed = true
                null
            }
            if (opened == null) return@withContext null
            val reason = synchronized(lock) {
                if (token == my) {
                    currentLink = opened
                    null
                } else {
                    stopReason
                }
            }
            if (reason != null) {
                // Late open after stop(): close at once, never start.
                opened.close(reason)
                null
            } else {
                opened
            }
        }
        if (link == null) {
            if (!openFailed) return null
            val mine = synchronized(lock) {
                if (currentSession === session) {
                    currentSession = null
                    true
                } else {
                    false
                }
            }
            if (mine) session.release()
            return if (isCurrent(my)) Outcome(ErrorClass.NETWORK, LinkError(LinkErrorKind.INTERNAL, 0), null, false) else null
        }
        quality.onNewLink()
        publishQuality()
        _linkState.value = link.state.value
        Timber.d("[LiveSession][supervisor][BLOCK_OPEN_LINK] attempt=%d", attempt)
        if (!isTerminal(link.state.value)) {
            link.setAudioSink(session.audioSink)
            link.start(session.videoSink)
        }
        // END_BLOCK_OPEN_LINK
        val outcome = watch(my, link, session)
        val mine = synchronized(lock) {
            if (currentLink === link) {
                currentLink = null
                currentSession = null
                true
            } else {
                false
            }
        }
        if (mine) session.release()
        previousLink = link
        if (liveLink === link) liveLink = null
        return outcome
    }

    // Watches one link until it is terminal or we decided to close it.
    private suspend fun watch(my: Int, link: CameraLink, session: MediaSession): Outcome {
        val w = Watch()
        val ownClose = MutableStateFlow<String?>(null)
        val requestClose: (String) -> Unit = { reason ->
            if (ownClose.value == null) {
                ownClose.value = reason
                closer.launch { link.close(reason) }
            }
        }
        val end = coroutineScope {
            val watchers = launch {
                launch {
                    link.state.collect { s ->
                        _linkState.value = s
                        if (s is LinkState.Streaming && !w.live) {
                            w.live = true
                            w.mode = s.info.mode
                            w.streamingSince = now()
                            w.lastFrames = session.framesIn()
                            w.lastGrowthAt = w.streamingSince
                            liveLink = link
                            publish(my, SupervisorState.Live(s.info, s.authOk))
                        }
                    }
                }
                launch {
                    while (true) {
                        delay(timing.tickMs)
                        tick(link, session, w, ownClose.value != null, requestClose)
                    }
                }
                launch {
                    link.capabilities.collect { c ->
                        if (c.resolutionSwitch == false && quality.state.switchSupported) {
                            quality.onUnsupported()
                            publishQuality()
                            Timber.d("[LiveSession][supervisor][BLOCK_QUALITY] result=unsupported source=capabilities")
                        }
                    }
                }
                launch {
                    // START_BLOCK_NETWORK_WATCH
                    var available = network.state.value.available
                    var generation = network.state.value.generation
                    var debounce: Job? = null
                    network.state.collect { ns ->
                        if (ns.available != available) {
                            available = ns.available
                            Timber.d(
                                "[LiveSession][supervisor][BLOCK_NETWORK] available=%b generationChanged=false live=%b",
                                ns.available, w.live,
                            )
                        }
                        if (ns.generation != generation) {
                            generation = ns.generation
                            Timber.d(
                                "[LiveSession][supervisor][BLOCK_NETWORK] available=%b generationChanged=true live=%b",
                                ns.available, w.live,
                            )
                            if (w.live) {
                                debounce?.cancel()
                                debounce = launch {
                                    delay(timing.networkChangeDebounceMs)
                                    requestClose(REASON_NETWORK_CHANGE)
                                }
                            }
                        }
                    }
                    // END_BLOCK_NETWORK_WATCH
                }
            }
            val result = combine(link.state, ownClose) { s, own -> s to own }
                .first { (s, own) -> isTerminal(s) || own != null }
            watchers.cancel()
            result
        }
        val own = end.second
        val terminal = end.first
        return when {
            own != null -> Outcome(classifyClosed(own), null, own, w.healthy)
            terminal is LinkState.Failed -> Outcome(classify(terminal.error), terminal.error, null, w.healthy)
            terminal is LinkState.Closed -> Outcome(classifyClosed(terminal.reason), null, null, w.healthy)
            else -> Outcome(ErrorClass.NETWORK, null, null, w.healthy)
        }
    }

    private fun tick(link: CameraLink, session: MediaSession, w: Watch, closing: Boolean, requestClose: (String) -> Unit) {
        if (!w.live || closing) return
        val t = now()
        // START_BLOCK_STALL
        val frames = session.framesIn()
        if (frames != w.lastFrames) {
            w.lastFrames = frames
            w.lastGrowthAt = t
        } else if (t - w.lastGrowthAt >= timing.stallMs) {
            Timber.w("[LiveSession][supervisor][BLOCK_STALL] idleMs=%d", t - w.lastGrowthAt)
            requestClose(REASON_STALL)
            return
        }
        if (!w.healthy && t - w.streamingSince >= timing.healthyResetMs) w.healthy = true
        // END_BLOCK_STALL
        val kf = session.lastKeyFrame()
        if (kf != null && kf != w.lastKeyFrame) {
            w.lastKeyFrame = kf
            quality.observe(kf)
        }
        if (quality.needsAutoRequest(w.mode)) sendQuality(link, quality.target(w.mode), "auto")
        quality.tick(t)
        publishQuality()
    }

    private fun sendQuality(link: CameraLink, q: Quality, source: String) {
        // START_BLOCK_QUALITY
        val useCount = link.requestResolution(q.resolution)
        quality.beginRequest(q, useCount, now())
        Timber.d(
            "[LiveSession][supervisor][BLOCK_QUALITY] target=%s observed=%s useCount=%s result=%s source=%s",
            q.name, quality.state.observed?.name ?: "none", useCount?.toString() ?: "none",
            if (useCount == null) "unsupported" else "sent", source,
        )
        publishQuality()
        // END_BLOCK_QUALITY
    }

    private fun isTerminal(s: LinkState) = s is LinkState.Failed || s is LinkState.Closed

    companion object {
        const val REASON_USER = "user"
        const val REASON_STALL = "stall"
        const val REASON_NETWORK_CHANGE = "network-change"
    }
}

// START_CONTRACT: classify
//   PURPOSE: Map a link failure to the supervisor's retry class (D-03).
//   INPUTS: { error: LinkError }
//   OUTPUTS: { ErrorClass }
//   SIDE_EFFECTS: none
//   LINKS: M-LIVE-SESSION
// END_CONTRACT: classify
fun classify(error: LinkError): ErrorClass = when (error.kind) {
    LinkErrorKind.OFFLINE, LinkErrorKind.NETWORK, LinkErrorKind.TIMEOUT, LinkErrorKind.REMOTE_CLOSED,
    LinkErrorKind.PROTOCOL, LinkErrorKind.INTERNAL, LinkErrorKind.ALREADY_OPEN -> ErrorClass.NETWORK
    LinkErrorKind.WRONG_PASSWORD -> ErrorClass.WRONG_PASSWORD
    LinkErrorKind.KICKED, LinkErrorKind.BUSY -> ErrorClass.KICK
    LinkErrorKind.NATIVE_UNAVAILABLE, LinkErrorKind.NO_CREDENTIALS, LinkErrorKind.UNSUPPORTED_TRANSPORT,
    LinkErrorKind.CREDENTIALS -> ErrorClass.FATAL
}

// START_CONTRACT: classifyClosed
//   PURPOSE: Map a Closed(reason) to a retry class; null for our own stops (user, screen-left, background).
//   INPUTS: { reason: String }
//   OUTPUTS: { ErrorClass? }
//   SIDE_EFFECTS: none
//   LINKS: M-LIVE-SESSION
// END_CONTRACT: classifyClosed
fun classifyClosed(reason: String): ErrorClass? = when (reason) {
    "kicked" -> ErrorClass.KICK
    "user", "screen-left", "background" -> null
    // stall, network-change, error, timeout and any unknown reason: treat as a network failure.
    else -> ErrorClass.NETWORK
}
