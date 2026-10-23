// FILE: LiveFakes.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Deterministic fakes of the live-session ports for virtual-time supervisor tests.
//   SCOPE: FakeCameraLink, FakeLinkFactory, FakeNetworkMonitor, FakeMediaSession / FakeMediaSessionFactory,
//     FakeCredentialsSource, synthetic credential constants. No threads, no real time.
//   DEPENDS: M-STREAM-CORE (CameraLink ports), M-P2P-CREDS (P2pCredentials), M-LIVE-SESSION (ports)
//   LINKS: V-M-LIVE-SESSION
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.live

import com.yicamalt.camera.P2pCredentials
import com.yicamalt.camera.TransportType
import com.yicamalt.stream.AudioUnitSink
import com.yicamalt.stream.CameraLink
import com.yicamalt.stream.CameraLinkFactory
import com.yicamalt.stream.LinkCapabilities
import com.yicamalt.stream.LinkOptions
import com.yicamalt.stream.LinkState
import com.yicamalt.stream.P2pCredentialsSource
import com.yicamalt.stream.VideoUnitSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

// START_MODULE_MAP
//   LIVE_DEVICE_ID / LIVE_SYNTH_* - synthetic id and credential literals (also fed to RedactionScanner).
//   liveCreds(password) - synthetic P2pCredentials.
//   FakeCameraLink - controllable state flow; records start / close reasons / audio-sink order / resolution requests.
//   FakeLinkFactory - returns scripted links (initial state per open or a lambda); records open times + passwords.
//   FakeNetworkMonitor - MutableStateFlow<NetState> with set(available) / bump() helpers.
//   FakeMediaSession / FakeMediaSessionFactory - frames counter, key frame, release count.
//   FakeCredentialsSource - counts credentialsFor / refreshCredentials; scripted stored / refreshed values.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 03-06: fakes for LiveSessionSupervisorTest.
// END_CHANGE_SUMMARY

const val LIVE_DEVICE_ID = "cam-SYNTH-LIVE"
const val LIVE_SYNTH_DID = "TNPSYN-654321-QWERT"
const val LIVE_SYNTH_PW = "SYNTHpw1"
const val LIVE_SYNTH_PW2 = "SYNTHpw2"
const val LIVE_SYNTH_INIT = "SYNTHINITSTRINGSYNTHINITSTRINGLIVE"
const val LIVE_SYNTH_LIC = "SYNTHLIC02"

val LIVE_SECRETS = listOf(LIVE_DEVICE_ID, LIVE_SYNTH_DID, LIVE_SYNTH_PW, LIVE_SYNTH_PW2, LIVE_SYNTH_INIT, LIVE_SYNTH_LIC)

fun liveCreds(password: String = LIVE_SYNTH_PW, transport: TransportType = TransportType.TNP) = P2pCredentials(
    deviceId = LIVE_DEVICE_ID,
    p2pId = LIVE_SYNTH_DID,
    transport = transport,
    password = password,
    initString = LIVE_SYNTH_INIT,
    licenseKey = LIVE_SYNTH_LIC,
    p2pEncrypt = false,
    wakeup = false,
    needsPincode = false,
    transportVerified = true,
)

class FakeCameraLink(override val deviceId: String, initial: LinkState = LinkState.Connecting) : CameraLink {
    val flow = MutableStateFlow(initial)
    override val state: StateFlow<LinkState> = flow
    var startCalls = 0
    val closeReasons = mutableListOf<String>()
    var terminalOnClose = true
    var audioSinkSet: AudioUnitSink? = null
    var audioSinkSetBeforeStart: Boolean? = null
    var videoSink: VideoUnitSink? = null

    // Scripted 4881 result; null = cannot send. Default: incrementing useCount.
    var resolutionResult: (Int) -> Int? = { ++useCount }
    private var useCount = 0
    val resolutions = mutableListOf<Int>()
    val capabilitiesFlow = MutableStateFlow(LinkCapabilities())
    override val capabilities: StateFlow<LinkCapabilities> get() = capabilitiesFlow

    override fun start(sink: VideoUnitSink) {
        startCalls++
        videoSink = sink
        if (audioSinkSetBeforeStart == null) audioSinkSetBeforeStart = false
    }

    override fun close(reason: String) {
        closeReasons += reason
        if (terminalOnClose) {
            flow.update { if (it is LinkState.Failed || it is LinkState.Closed) it else LinkState.Closed(reason) }
        }
    }

    override fun setAudioSink(sink: AudioUnitSink?) {
        audioSinkSet = sink
        if (startCalls == 0) audioSinkSetBeforeStart = true
    }

    override fun requestResolution(resolution: Int): Int? {
        resolutions += resolution
        return resolutionResult(resolution)
    }

    fun terminal(): Boolean = flow.value is LinkState.Failed || flow.value is LinkState.Closed
}

class FakeLinkFactory(private val now: () -> Long) : CameraLinkFactory {
    val links = mutableListOf<FakeCameraLink>()
    val openTimes = mutableListOf<Long>()
    val passwords = mutableListOf<String?>()

    // Initial states for successive opens; when exhausted, [defaultInitial] is used.
    val script = ArrayDeque<LinkState>()
    var defaultInitial: LinkState = LinkState.Connecting

    // Called inside open() after the link is created (e.g. to stop the supervisor mid-open).
    var onOpen: (FakeCameraLink) -> Unit = {}
    var configure: (FakeCameraLink) -> Unit = {}

    val opens: Int get() = links.size

    override fun open(credentials: P2pCredentials, options: LinkOptions): CameraLink {
        openTimes += now()
        passwords += credentials.password
        val link = FakeCameraLink(credentials.deviceId, script.removeFirstOrNull() ?: defaultInitial)
        configure(link)
        links += link
        onOpen(link)
        return link
    }
}

class FakeNetworkMonitor(available: Boolean = true) : NetworkMonitor {
    val flow = MutableStateFlow(NetState(available, 1))
    override val state: StateFlow<NetState> = flow

    fun set(available: Boolean) = flow.update { it.copy(available = available) }
    fun bump() = flow.update { it.copy(generation = it.generation + 1) }
}

class FakeMediaSession : MediaSession {
    var frames = 0L
    var keyFrame: KeyFrameObservation? = null
    var releaseCalls = 0
    override val videoSink = VideoUnitSink { frames++ }
    override val audioSink = AudioUnitSink { }
    override fun framesIn(): Long = frames
    override fun lastKeyFrame(): KeyFrameObservation? = keyFrame
    override fun release() {
        releaseCalls++
    }
}

class FakeMediaSessionFactory : MediaSessionFactory {
    val created = mutableListOf<FakeMediaSession>()
    val passwords = mutableListOf<String?>()
    override fun create(credentials: P2pCredentials): MediaSession {
        passwords += credentials.password
        return FakeMediaSession().also { created += it }
    }
}

class FakeCredentialsSource(var stored: P2pCredentials? = liveCreds()) : P2pCredentialsSource {
    var credentialsForCalls = 0
    var refreshCalls = 0
    var refreshed: P2pCredentials? = liveCreds(LIVE_SYNTH_PW2)

    override fun credentialsFor(deviceId: String): P2pCredentials? {
        credentialsForCalls++
        return if (deviceId == LIVE_DEVICE_ID) stored else null
    }

    override suspend fun refreshCredentials(deviceId: String): P2pCredentials? {
        refreshCalls++
        return if (deviceId == LIVE_DEVICE_ID) refreshed ?: stored else null
    }
}
