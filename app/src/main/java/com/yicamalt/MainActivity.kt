// FILE: MainActivity.kt
// VERSION: 0.4.0
// START_MODULE_CONTRACT
//   PURPOSE: Single-activity host for the Compose navigation graph.
//   SCOPE: setContent + Hilt-enabled Compose host; Login -> Home -> Settings flow; start-up purge of
//     signed-out camera data.
//   DEPENDS: M-UI-SHELL, M-AUTH, M-CAMERA-LIST
//   LINKS: M-UI-SHELL, M-AUTH, M-CAMERA-LIST
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
//
// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.4.0 - SEC-04 cleanup moved out of the UI: AuthRepository.logout wipes the camera cache and
//     the P2P credential store synchronously (LogoutCleanup); the start-up wipe without a session
//     (CameraRegistry.purgeIfSignedOut) runs on a plain thread. No coroutine scope can cancel or skip either.
//   PREV_CHANGE: v0.3.0 - logout wiped camera data from an Activity coroutine (cancellable; replaced).
// END_CHANGE_SUMMARY
package com.yicamalt

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.yicamalt.auth.AuthRepository
import com.yicamalt.camera.CameraRegistry
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.concurrent.thread
import timber.log.Timber

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var auth: AuthRepository
    @Inject lateinit var cameraRegistry: CameraRegistry

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // START_BLOCK_PURGE_SIGNED_OUT_DATA
        // SEC-04 safety net: no session but leftover camera rows / P2P credentials (a logout cleanup failed or the
        // process died mid-logout) -> wipe. Plain thread, not a lifecycle scope; local only, no network.
        if (auth.getSession() == null) thread(name = "camera-purge") { cameraRegistry.purgeIfSignedOut() }
        // END_BLOCK_PURGE_SIGNED_OUT_DATA
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
                        // Also wipes camera cache + P2P credentials before it returns (SEC-04, LogoutCleanup).
                        auth.logout()
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
