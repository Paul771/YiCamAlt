// FILE: NetStateTracker.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Pure rules turning default-network callbacks into a value-free NetState(available, generation).
//   SCOPE: onAvailable / onLost by opaque network key; generation bumps when a different network becomes default.
//   DEPENDS: M-LIVE-SESSION (NetState)
//   LINKS: M-LIVE-SESSION, V-M-LIVE-SESSION
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.live

import kotlinx.coroutines.flow.StateFlow

// START_MODULE_MAP
//   NetStateTracker(initialAvailable) - state, onAvailable(key), onLost(key).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 03-08: RED stub.
// END_CHANGE_SUMMARY

class NetStateTracker(initialAvailable: Boolean) {
    val state: StateFlow<NetState> get() = error("RED stub")
    fun onAvailable(key: Any): Unit = error("RED stub")
    fun onLost(key: Any): Unit = error("RED stub")
}
