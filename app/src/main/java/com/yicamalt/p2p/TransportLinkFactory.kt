// FILE: TransportLinkFactory.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: The CameraLinkFactory the app uses: dispatch by the camera's transport to the TNP or TUTK factory.
//   SCOPE: open(credentials, options) -> TnpLinkFactory for TNP, TutkLinkFactory for TUTK, a Failed link for
//     LANGTAO / UNKNOWN. No native call and no state of its own; each delegate keeps its own one-session registry.
//   DEPENDS: M-TNP-LINK (TnpLinkFactory, FailedLink), M-TUTK-LINK (TutkLinkFactory), M-STREAM-CORE (CameraLinkFactory)
//   LINKS: M-TNP-LINK, M-TUTK-LINK, V-M-TUTK-LINK
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.p2p

import com.yicamalt.camera.P2pCredentials
import com.yicamalt.camera.TransportType
import com.yicamalt.p2p.tnp.FailedLink
import com.yicamalt.p2p.tnp.TnpLinkFactory
import com.yicamalt.p2p.tutk.TutkLinkFactory
import com.yicamalt.stream.CameraLink
import com.yicamalt.stream.CameraLinkFactory
import com.yicamalt.stream.LinkError
import com.yicamalt.stream.LinkErrorKind
import com.yicamalt.stream.LinkOptions
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

// START_MODULE_MAP
//   TransportLinkFactory - @Singleton CameraLinkFactory dispatching on P2pCredentials.transport.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 02-11: created; P2pLinkModule binds CameraLinkFactory to it, so the debug live
//     screen opens TNP and TUTK cameras through the same port without UI changes.
// END_CHANGE_SUMMARY

@Singleton
class TransportLinkFactory @Inject constructor(
    private val tnp: TnpLinkFactory,
    private val tutk: TutkLinkFactory,
) : CameraLinkFactory {

    // START_CONTRACT: open
    //   PURPOSE: Route one open() to the factory of the camera's transport.
    //   INPUTS: { credentials: P2pCredentials; options: LinkOptions }
    //   OUTPUTS: { CameraLink - TransportType.TNP -> TnpLinkFactory.open; TransportType.TUTK -> TutkLinkFactory.open;
    //     LANGTAO / UNKNOWN -> a link already Failed(UNSUPPORTED_TRANSPORT, 0) }
    //   SIDE_EFFECTS: exactly one delegate open (or none); one BLOCK_DISPATCH_LINK log line for refusals only
    //   LINKS: TnpLinkFactory.open, TutkLinkFactory.open
    // END_CONTRACT: open
    override fun open(credentials: P2pCredentials, options: LinkOptions): CameraLink {
        // START_BLOCK_DISPATCH_LINK
        return when (credentials.transport) {
            TransportType.TNP -> tnp.open(credentials, options)
            TransportType.TUTK -> tutk.open(credentials, options)
            TransportType.LANGTAO, TransportType.UNKNOWN -> {
                Timber.d(
                    "[P2P][factory][BLOCK_DISPATCH_LINK] transport=%s result=%s",
                    credentials.transport, LinkErrorKind.UNSUPPORTED_TRANSPORT.name,
                )
                FailedLink(credentials.deviceId, LinkError(LinkErrorKind.UNSUPPORTED_TRANSPORT, 0))
            }
        }
        // END_BLOCK_DISPATCH_LINK
    }
}
