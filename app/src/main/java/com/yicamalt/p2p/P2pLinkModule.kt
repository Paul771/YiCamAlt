// FILE: P2pLinkModule.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Hilt binding of the CameraLinkFactory port to the TNP implementation.
//   SCOPE: one @Binds; PpppNative, NativeLibs and PpppRuntime are bound in P2pRuntimeModule (NativeLibs.kt).
//   DEPENDS: M-TNP-LINK (TnpLinkFactory), M-STREAM-CORE (CameraLinkFactory)
//   LINKS: M-TNP-LINK, V-M-TNP-LINK
//   ROLE: CONFIG
//   MAP_MODE: NONE
// END_MODULE_CONTRACT
package com.yicamalt.p2p

import com.yicamalt.p2p.tnp.TnpLinkFactory
import com.yicamalt.stream.CameraLinkFactory
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 02-07: created. Plan 02-11 may replace the impl with a transport dispatcher.
// END_CHANGE_SUMMARY

@Module
@InstallIn(SingletonComponent::class)
abstract class P2pLinkModule {

    @Binds
    @Singleton
    abstract fun bindCameraLinkFactory(impl: TnpLinkFactory): CameraLinkFactory
}
