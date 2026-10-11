// FILE: MainActivity.kt
// VERSION: 0.3.0
// START_MODULE_CONTRACT
//   PURPOSE: Single-activity host for the Compose navigation graph.
//   SCOPE: setContent + Hilt-enabled Compose host; Login -> Home -> Settings flow; logout cleanup.
//   DEPENDS: M-UI-SHELL, M-AUTH, M-CAMERA-LIST
//   LINKS: M-UI-SHELL, M-AUTH, M-CAMERA-LIST
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
//
// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.3.0 - logout wipes camera cache and P2P credential store (SEC-04) via CameraRegistry.clearAll.
// END_CHANGE_SUMMARY
package com.yicamalt

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.yicamalt.auth.AuthRepository
import com.yicamalt.camera.CameraRegistry
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject
import timber.log.Timber

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var auth: AuthRepository
    @Inject lateinit var cameraRegistry: CameraRegistry

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // START_BLOCK_RENDER_HOST
        setContent {
            var showSettings by remember { mutableStateOf(false) }
            var loggedIn by remember { mutableStateOf(auth.getSession() != null) }
            when {
                showSettings -> com.yicamalt.ui.settings.SettingsScreen(onBack = { showSettings = false })
                loggedIn -> com.yicamalt.ui.shell.AppShellScreen(
                    userId = auth.getSession()?.userId ?: "",
                    onLogout = {
                        // START_BLOCK_HANDLE_LOGOUT
                        Timber.d("[Shell][logout][BLOCK_HANDLE_LOGOUT] clearing session")
                        auth.logout()
                        // SEC-04: the camera cache and the P2P credential store must not outlive the session.
                        lifecycleScope.launch { cameraRegistry.clearAll() }
                        loggedIn = false
                        // END_BLOCK_HANDLE_LOGOUT
                    },
                )
                else -> com.yicamalt.ui.login.LoginScreen(
                    onLoggedIn = { loggedIn = true },
                    onOpenSettings = { showSettings = true },
                )
            }
        }
        // END_BLOCK_RENDER_HOST
    }
}