// FILE: LiveFakes.kt
// VERSION: 0.3.0
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
import com.yicamalt.stream.AudioUnit
import com.yicamalt.stream.AudioUnitSink
import com.yicamalt.stream.CameraLink
import com.yicamalt.stream.CameraLinkFactory
import com.yicamalt.stream.FrameInfo
import com.yicamalt.stream.FrameObservation
import com.yicamalt.stream.LinkCapabilities
import com.yicamalt.stream.LinkIoCtrlReply
import com.yicamalt.stream.LinkOptions
import com.yicamalt.stream.LinkState
import com.yicamalt.stream.P2pCredentialsSource
import com.yicamalt.stream.StreamMode
import com.yicamalt.stream.VideoUnit
import com.yicamalt.stream.VideoUnitSink
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

// START_MODULE_MAP
//   LIVE_DEVICE_ID / LIVE_SYNTH_* - synthetic id and credential literals (also fed to RedactionScanner).
//   liveCreds(password) - synthetic P2pCredentials.
//   FakeCameraLink - controllable state flow; records start / close reasons / audio-sink order / resolution requests;
//     Phase 4: replies (ioCtrlReplies), sentIoCtrl / sendIoCtrlResult / onIoCtrl, nextUseCount (shared counter), modes.
//   FakeLinkFactory - returns scripted links (initial state per open or a lambda); records open times + passwords.
//   FakeNetworkMonitor - MutableStateFlow<NetState> with set(available) / bump() helpers.
//   FakeMediaSession / FakeMediaSessionFactory - frames counter, key frame, release count; expected / expectCalls /
//     frame (archive generation gate, 04-06).
//   FakeCredentialsSource - counts credentialsFor / refreshCredentials; scripted stored / refreshed values.
//   SYNTH_I264 / SYNTH_P264 / videoInfo / keyUnit / pUnit - plain H.264 transport units (default 1920x1080, useCount 3).
//   adtsFrame / aacUnit - one valid ADTS frame and an unencrypted AAC AudioUnit (codec 138).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.3.0 - GSD plan 04-06: archive port members on FakeCameraLink (replies, sendIoCtrl, nextUseCount,
//     setStreamMode) and FakeMediaSession (expectGeneration, lastFrame).
//   PREVIOUS: v0.2.0 - GSD plan 03-10: synthetic video / audio unit builders for DefaultMediaSessionTest and
//     LiveViewModelTest (FakeCameraLink already records videoSink and audioSinkSet).
//   PREVIOUS: v0.1.0 - GSD plan 03-06: fakes for LiveSessionSupervisorTest.
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

    // Phase 4 archive port members.
    val replies = MutableSharedFlow<LinkIoCtrlReply>(extraBufferCapacity = 16)
    override val ioCtrlReplies: SharedFlow<LinkIoCtrlReply> get() = replies
    val sentIoCtrl = mutableListOf<Pair<Int, ByteArray>>()
    var sendIoCtrlResult = true
    // Called after an accepted send (e.g. to script a reply on [replies]).
    var onIoCtrl: (type: Int, payload: ByteArray) -> Unit = { _, _ -> }
    val modes = mutableListOf<StreamMode>()

    override fun sendIoCtrl(type: Int, payload: ByteArray): Boolean {
        sentIoCtrl += type to payload
        if (sendIoCtrlResult) onIoCtrl(type, payload)
        return sendIoCtrlResult
    }

    override fun nextUseCount(): Int? = ++useCount

    override fun setStreamMode(mode: StreamMode) {
        modes += mode
    }

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
    var expected: Int? = null
    var expectCalls = 0
    var frame: FrameObservation? = null
    override val videoSink = VideoUnitSink { frames++ }
    override val audioSink = AudioUnitSink { }
    override fun framesIn(): Long = frames
    override fun lastKeyFrame(): KeyFrameObservation? = keyFrame
    override fun expectGeneration(useCount: Int?) {
        expected = useCount
        expectCalls++
    }
    override fun lastFrame(): FrameObservation? = frame
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

// START_BLOCK_SYNTH_UNITS
private fun synthNal(vararg header: Int, size: Int): ByteArray =
    ByteArray(size) { i -> if (i < header.size) header[i].toByte() else (0x21 + i % 90).toByte() }

private val SC4 = byteArrayOf(0, 0, 0, 1)

// Plain (not encrypted) H.264 access units in Annex-B: SPS + PPS + IDR and one P slice.
val SYNTH_I264: ByteArray = SC4 + synthNal(0x67, 0x4D, 0x00, 0x28, size = 20) + SC4 + synthNal(0x68, 0xCE, 0x3C, 0x80, size = 4) +
    SC4 + synthNal(0x65, 0x88, 0x84, size = 60)
val SYNTH_P264: ByteArray = SC4 + synthNal(0x41, 0x9A, size = 30)

fun videoInfo(key: Boolean, seq: Int, useCount: Int = 3, width: Int = 1920, height: Int = 1080, codecId: Int = 78) =
    FrameInfo(
        codecId = codecId, flags = if (key) 1 else 0, liveFlag = 1, onlineNum = 1, useCount = useCount, seq = seq,
        width = width, height = height, timestampSec = 0, isDay = 1, cover = 0, outLoss = 0, inLoss = 0, timestampMs = 0,
    )

fun keyUnit(seq: Int = 10, useCount: Int = 3, width: Int = 1920, height: Int = 1080) =
    VideoUnit(2, videoInfo(true, seq, useCount, width, height), SYNTH_I264)

fun pUnit(seq: Int = 11, useCount: Int = 3, width: Int = 1920, height: Int = 1080) =
    VideoUnit(3, videoInfo(false, seq, useCount, width, height), SYNTH_P264)

// One valid ADTS frame (AAC LC, 16 kHz, mono) with a patterned payload.
fun adtsFrame(payloadLen: Int = 32, freqIndex: Int = 8, channels: Int = 1): ByteArray {
    val len = 7 + payloadLen
    val h = byteArrayOf(
        0xFF.toByte(),
        0xF1.toByte(),
        ((1 shl 6) or (freqIndex shl 2) or (channels shr 2)).toByte(),
        (((channels and 3) shl 6) or ((len shr 11) and 3)).toByte(),
        ((len shr 3) and 0xFF).toByte(),
        (((len and 7) shl 5) or 0x1F).toByte(),
        0xFC.toByte(),
    )
    return h + ByteArray(payloadLen) { (it * 3 + 1).toByte() }
}

fun aacUnit(payload: ByteArray = adtsFrame(), codecId: Int = 138) =
    AudioUnit(1, FrameInfo(codecId, 0, 0, 0, 0, 1, 0, 0, 0L, 1, 0, 0, 0, 0L), payload, encrypted = false)
// END_BLOCK_SYNTH_UNITS

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
