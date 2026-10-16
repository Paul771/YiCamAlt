// FILE: TnpLinkFactory.kt
// VERSION: 0.1.1
// START_MODULE_CONTRACT
//   PURPOSE: CameraLinkFactory for TNP cameras: credential checks, process-once runtime init, one session per camera.
//   SCOPE: open(credentials, options) -> TnpCameraLink or a link that is already Failed with a typed refusal;
//     per-process cam counter; registry keyed by deviceId, released by the link's onClosed.
//   DEPENDS: M-P2P-NATIVE (PpppNative, PpppRuntime), M-STREAM-CORE (CameraLink port), M-P2P-CREDS
//   LINKS: M-TNP-LINK, V-M-TNP-LINK
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.p2p.tnp

import com.yicamalt.camera.P2pCredentials
import com.yicamalt.camera.TransportType
import com.yicamalt.p2p.PpppNative
import com.yicamalt.p2p.PpppRuntime
import com.yicamalt.p2p.RuntimeState
import com.yicamalt.stream.CameraLink
import com.yicamalt.stream.CameraLinkFactory
import com.yicamalt.stream.LinkError
import com.yicamalt.stream.LinkErrorKind
import com.yicamalt.stream.LinkOptions
import com.yicamalt.stream.LinkState
import com.yicamalt.stream.VideoUnitSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

// START_MODULE_MAP
//   TnpLinkFactory - @Singleton CameraLinkFactory; open() applies the checks of its contract in order.
//   FailedLink - CameraLink fixed in Failed(error); start/close are no-ops.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.1 - Phase 2 review MED-7: an empty password with p2pEncrypt is refused as NO_CREDENTIALS (an
//     empty HMAC key made SecretKeySpec throw on the connect thread). The registry entry is released by the link only
//     after all of its threads exited (LOW-8).
//   PREVIOUS: v0.1.0 - GSD plan 02-07: created. One session per camera; refusals are typed links, never exceptions.
//     The registry entry lives from open() until the link's teardown, so every opened link must be closed.
// END_CHANGE_SUMMARY

@Singleton
class TnpLinkFactory @Inject constructor(
    private val native: PpppNative,
    private val runtime: PpppRuntime,
) : CameraLinkFactory {

    private val registry = ConcurrentHashMap<String, TnpCameraLink>()
    private val camCounter = AtomicInteger(0)

    // START_CONTRACT: open
    //   PURPOSE: Create the one TNP session object for a camera, or a typed refusal.
    //   INPUTS: { credentials: P2pCredentials; options: LinkOptions }
    //   OUTPUTS: { CameraLink - checks in order:
    //     1. transport != TNP -> Failed(UNSUPPORTED_TRANSPORT, 0)
    //     2. blank p2pId, missing InitString or missing license key -> Failed(NO_CREDENTIALS, 0)
    //     3. needsPincode, or p2pEncrypt with a null or empty password -> Failed(NO_CREDENTIALS, 0)
    //     4. runtime Unavailable -> Failed(NATIVE_UNAVAILABLE, 0); InitFailed(code) -> Failed(NATIVE_UNAVAILABLE, code)
    //     5. a link for credentials.deviceId still open -> Failed(ALREADY_OPEN, 0)
    //     6. else a new idle TnpCameraLink (call start(sink); always close it) }
    //   SIDE_EFFECTS: steps 1-3 make no native call; step 4 may run the process-once PPPP init (blocks up to 10 s on
    //     first use, so call off the main thread); one BLOCK_OPEN_LINK log line per call.
    //   LINKS: M-TNP-LINK, PpppRuntime.ensureInitialized
    // END_CONTRACT: open
    override fun open(credentials: P2pCredentials, options: LinkOptions): CameraLink {
        // START_BLOCK_OPEN_LINK
        val cam = camCounter.incrementAndGet()
        val refusal: LinkError? = when {
            credentials.transport != TransportType.TNP -> LinkError(LinkErrorKind.UNSUPPORTED_TRANSPORT, 0)
            credentials.p2pId.isBlank() || credentials.initString.isNullOrBlank() || credentials.licenseKey.isNullOrBlank() ->
                LinkError(LinkErrorKind.NO_CREDENTIALS, 0)
            credentials.needsPincode || (credentials.p2pEncrypt && credentials.password.isNullOrEmpty()) ->
                LinkError(LinkErrorKind.NO_CREDENTIALS, 0)
            else -> when (val rt = runtime.ensureInitialized()) {
                is RuntimeState.Ready -> null
                is RuntimeState.InitFailed -> LinkError(LinkErrorKind.NATIVE_UNAVAILABLE, rt.code)
                else -> LinkError(LinkErrorKind.NATIVE_UNAVAILABLE, 0)
            }
        }
        if (refusal != null) return refuse(cam, credentials, refusal)

        var created: TnpCameraLink? = null
        registry.compute(credentials.deviceId) { _, existing ->
            existing ?: TnpCameraLink(credentials, options, native, cam, onClosed = { link ->
                registry.remove(link.deviceId, link)
            }).also { created = it }
        }
        val link = created ?: return refuse(cam, credentials, LinkError(LinkErrorKind.ALREADY_OPEN, 0))
        Timber.d("[P2P][factory][BLOCK_OPEN_LINK] cam=%d transport=%s result=started", cam, credentials.transport)
        return link
        // END_BLOCK_OPEN_LINK
    }

    private fun refuse(cam: Int, credentials: P2pCredentials, error: LinkError): CameraLink {
        Timber.d(
            "[P2P][factory][BLOCK_OPEN_LINK] cam=%d transport=%s result=%s",
            cam, credentials.transport, error.kind.name,
        )
        return FailedLink(credentials.deviceId, error)
    }
}

// A link that never touches native code: its state is Failed(error) from the start.
internal class FailedLink(override val deviceId: String, error: LinkError) : CameraLink {
    private val _state = MutableStateFlow<LinkState>(LinkState.Failed(error))
    override val state: StateFlow<LinkState> = _state.asStateFlow()

    override fun start(sink: VideoUnitSink) = Unit

    override fun close(reason: String) = Unit

    override fun toString(): String = "FailedLink(state=${_state.value})"
}
