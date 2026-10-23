// FILE: LiveModule.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Hilt bindings of the live-session ports.
//   SCOPE: NetworkMonitor -> AndroidNetworkMonitor.
//   DEPENDS: M-LIVE-SESSION (NetworkMonitor, AndroidNetworkMonitor)
//   LINKS: M-LIVE-SESSION, V-M-LIVE-SESSION
//   ROLE: CONFIG
//   MAP_MODE: NONE
// END_MODULE_CONTRACT
package com.yicamalt.live

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 03-08: created with the NetworkMonitor binding.
// END_CHANGE_SUMMARY

@Module
@InstallIn(SingletonComponent::class)
abstract class LiveModule {

    @Binds
    @Singleton
    abstract fun bindNetworkMonitor(impl: AndroidNetworkMonitor): NetworkMonitor
}
