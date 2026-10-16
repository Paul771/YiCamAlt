// FILE: P2pLinkModule.kt
// VERSION: 0.2.0
// START_MODULE_CONTRACT
//   PURPOSE: Hilt binding of the CameraLinkFactory port to the transport-dispatching factory.
//   SCOPE: one @Binds; PpppNative, NativeLibs and PpppRuntime are bound in P2pRuntimeModule (NativeLibs.kt),
//     TutkNative in TutkRuntimeModule (TutkRuntime.kt).
//   DEPENDS: TransportLinkFactory (M-TNP-LINK + M-TUTK-LINK), M-STREAM-CORE (CameraLinkFactory)
//   LINKS: M-TNP-LINK, M-TUTK-LINK, V-M-TNP-LINK, V-M-TUTK-LINK
//   ROLE: CONFIG
//   MAP_MODE: NONE
// END_MODULE_CONTRACT
package com.yicamalt.p2p

import com.yicamalt.stream.CameraLinkFactory
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.2.0 - GSD plan 02-11: CameraLinkFactory -> TransportLinkFactory (TNP -> TnpLinkFactory,
//     TUTK -> TutkLinkFactory, others refused), so TUTK cameras open through the same port.
//   PREVIOUS: v0.1.0 - GSD plan 02-07: created, bound to TnpLinkFactory.
// END_CHANGE_SUMMARY

@Module
@InstallIn(SingletonComponent::class)
abstract class P2pLinkModule {

    @Binds
    @Singleton
    abstract fun bindCameraLinkFactory(impl: TransportLinkFactory): CameraLinkFactory
}
