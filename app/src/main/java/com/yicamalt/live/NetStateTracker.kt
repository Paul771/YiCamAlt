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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// START_MODULE_MAP
//   NetStateTracker(initialAvailable) - state, onAvailable(key), onLost(key).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 03-08: created. Keys are compared with ==, android.net.Network equality is by
//     network id. INVARIANT: the key is never exposed or logged; NetState carries only a flag and a counter.
// END_CHANGE_SUMMARY

class NetStateTracker(initialAvailable: Boolean) {

    private val _state = MutableStateFlow(NetState(initialAvailable, 0))
    val state: StateFlow<NetState> = _state.asStateFlow()

    // Last network that was reported as default; kept after onLost so "same network back" keeps the generation.
    private var last: Any? = null

    // START_CONTRACT: onAvailable
    //   PURPOSE: A network became the default network.
    //   INPUTS: { key: Any - opaque network identity }
    //   OUTPUTS: { Unit }
    //   SIDE_EFFECTS: state available=true; generation +1 when a different key replaces a previous one
    //   LINKS: M-LIVE-SESSION
    // END_CONTRACT: onAvailable
    @Synchronized
    fun onAvailable(key: Any) {
        val prev = last
        val changed = prev != null && prev != key
        last = key
        val cur = _state.value
        _state.value = NetState(available = true, generation = if (changed) cur.generation + 1 else cur.generation)
    }

    // START_CONTRACT: onLost
    //   PURPOSE: A network was lost; only the current default network counts.
    //   INPUTS: { key: Any - opaque network identity }
    //   OUTPUTS: { Unit }
    //   SIDE_EFFECTS: state available=false when key is the current network; stale keys are ignored
    //   LINKS: M-LIVE-SESSION
    // END_CONTRACT: onLost
    @Synchronized
    fun onLost(key: Any) {
        if (last == null || last != key) return
        _state.value = _state.value.copy(available = false)
    }
}
