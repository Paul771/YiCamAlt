// FILE: NetStateTrackerTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Executable contract for the default-network state rules (LIVE-05).
//   SCOPE: seeding, same-network repeat, network change generation, stale onLost, same network back.
//   DEPENDS: M-LIVE-SESSION (NetStateTracker, NetState)
//   LINKS: V-M-LIVE-SESSION
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.live

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// START_MODULE_MAP
//   A, B, C - opaque network keys (stand-ins for android.net.Network).
//   NetStateTrackerTest - one test per behaviour bullet of plan 03-08.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 03-08: network state tracker vectors.
// END_CHANGE_SUMMARY

class NetStateTrackerTest {

    private val a = Any()
    private val b = Any()
    private val c = Any()

    @Test
    fun `seed false and true give generation 0`() {
        assertEquals(NetState(false, 0), NetStateTracker(initialAvailable = false).state.value)
        assertEquals(NetState(true, 0), NetStateTracker(initialAvailable = true).state.value)
    }

    @Test
    fun `first available keeps generation, same key again is unchanged`() {
        val t = NetStateTracker(initialAvailable = false)
        t.onAvailable(a)
        assertEquals(NetState(true, 0), t.state.value)
        t.onAvailable(a)
        assertEquals(NetState(true, 0), t.state.value)
    }

    @Test
    fun `different network bumps generation`() {
        val t = NetStateTracker(initialAvailable = false)
        t.onAvailable(a)
        t.onAvailable(b)
        assertEquals(NetState(true, 1), t.state.value)
    }

    @Test
    fun `lost of the current network marks unavailable, stale lost is ignored`() {
        val t = NetStateTracker(initialAvailable = false)
        t.onAvailable(a)
        t.onAvailable(b)
        t.onLost(a)
        assertEquals(NetState(true, 1), t.state.value)
        t.onLost(b)
        assertEquals(NetState(false, 1), t.state.value)
    }

    @Test
    fun `new network after loss bumps generation`() {
        val t = NetStateTracker(initialAvailable = false)
        t.onAvailable(a)
        t.onAvailable(b)
        t.onLost(b)
        t.onAvailable(c)
        assertEquals(NetState(true, 2), t.state.value)
    }

    @Test
    fun `same network back after loss keeps generation`() {
        val t = NetStateTracker(initialAvailable = false)
        t.onAvailable(a)
        t.onAvailable(b)
        t.onLost(b)
        t.onAvailable(b)
        assertEquals(NetState(true, 1), t.state.value)
    }
}
