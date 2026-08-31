// FILE: MainActivity.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Single-activity host for the Compose navigation graph.
//   SCOPE: setContent + Hilt-enabled Compose host. No business logic.
//   DEPENDS: M-UI-SHELL, M-AUTH
//   LINKS: M-UI-SHELL, M-AUTH
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // START_BLOCK_RENDER_HOST
        setContent {
            // Shell graph is wired in M-UI-SHELL (Phase-2). For Phase-1 the host
            // mounts the LoginScreen directly so the auth pipeline is testable.
            com.yicamalt.ui.login.LoginScreen(onLoggedIn = { /* nav -> shell in Phase-2 */ })
        }
        // END_BLOCK_RENDER_HOST
    }
}