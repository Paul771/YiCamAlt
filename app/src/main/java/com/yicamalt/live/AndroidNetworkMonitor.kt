// FILE: AndroidNetworkMonitor.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: NetworkMonitor backed by ConnectivityManager's default-network callback (LIVE-05).
//   SCOPE: seed from activeNetwork, register one default-network callback for the app lifetime, delegate events
//     to NetStateTracker. No VALIDATED gate: a LAN-only Wi-Fi without internet still counts as available.
//   DEPENDS: M-LIVE-SESSION (NetStateTracker, NetworkMonitor, NetState), android.net.ConnectivityManager
//   LINKS: M-LIVE-SESSION, V-M-LIVE-SESSION
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.live

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

// START_MODULE_MAP
//   AndroidNetworkMonitor(context) - @Singleton NetworkMonitor; state from NetStateTracker.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 03-08: created. INVARIANT: logs carry available / generationChanged only; never
//     network handles, ids, SSIDs, IPs or link properties.
// END_CHANGE_SUMMARY

@Singleton
class AndroidNetworkMonitor @Inject constructor(
    @ApplicationContext context: Context,
) : NetworkMonitor {

    private val cm: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)
    private val tracker = NetStateTracker(initialAvailable = cm?.activeNetwork != null)

    override val state: StateFlow<NetState> = tracker.state

    init {
        try {
            cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = event { tracker.onAvailable(network) }
                override fun onLost(network: Network) = event { tracker.onLost(network) }
            })
        } catch (e: SecurityException) {
            Timber.w("[LiveSession][network][init] result=register-failed error=%s", e.javaClass.simpleName)
        } catch (e: RuntimeException) {
            // e.g. TooManyRequestsException on some OEM builds; keep the seeded state.
            Timber.w("[LiveSession][network][init] result=register-failed error=%s", e.javaClass.simpleName)
        }
    }

    // START_BLOCK_NETWORK_EVENT
    private fun event(apply: () -> Unit) {
        val before = tracker.state.value
        apply()
        val after = tracker.state.value
        if (after != before) {
            Timber.i(
                "[LiveSession][network][BLOCK_NETWORK_EVENT] available=%b generationChanged=%b",
                after.available, after.generation != before.generation,
            )
        }
    }
    // END_BLOCK_NETWORK_EVENT
}
