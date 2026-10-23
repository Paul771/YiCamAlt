// FILE: AppShellScreen.kt
// VERSION: 0.3.0
// START_MODULE_CONTRACT
//   PURPOSE: Top-level post-login shell: tab host (Cameras / Settings) with session-aware header.
//   SCOPE: renders active tab content; emits BLOCK_RENDER_SHELL / BLOCK_NAVIGATE_SCREEN markers.
//     Live-View / Events tabs arrive in Phase-3; only Cameras + Settings are wired now. A camera row tap shows
//     LiveViewScreen, a long press the debug LiveProbeScreen, in place of the tab content (bottom bar hidden) until
//     back / tab switch / logout.
//   DEPENDS: M-UI-SHELL, M-CAMERA-LIST, M-UI-SETTINGS, M-UI-LIVEVIEW, M-UI-LIVE-PROBE, M-AUTH (logout via caller)
//   LINKS: M-UI-SHELL, M-UI-LIVEVIEW, M-UI-LIVE-PROBE
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.yicamalt.camera.CameraInfo
import com.yicamalt.ui.live.LiveProbeScreen
import com.yicamalt.ui.live.LiveViewScreen
import timber.log.Timber

// START_MODULE_MAP
//   AppShellScreen - bottom-nav host for Cameras/Settings tabs (post-login) plus the live view and debug probe overlays.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.3.0 - GSD plan 03-11: tap -> LiveViewScreen, long-press -> LiveProbeScreen (debug, TNP H1-H3
//     relay-only). probeCamera is a plain remember like liveCamera (no cloud uid in the saved-instance Bundle); tab
//     switch clears both; render / navigate markers log tab=live|probe only.
//   PREV_CHANGE: v0.2.0 - GSD plan 02-09: onOpenCamera from the camera list shows LiveProbeScreen (plain remember,
//     not the saveable remember: the cloud uid never enters the saved-instance Bundle; never logged). Tab switch or
//     logout clears it; the probe screen's DisposableEffect then closes the P2P session.
//   OLDER_CHANGE: v0.1.2 - CAM-04: camera list UNAUTHORIZED state routes "Войти снова" to onLogout (back to login)
//   OLDER_CHANGE: v0.1.1 - SEC-01: render marker logs hasUser instead of the user id
// END_CHANGE_SUMMARY

private enum class ShellTab(val label: String, val icon: ImageVector) {
    Cameras("Камеры", Icons.Filled.Videocam),
    Settings("Настройки", Icons.Filled.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShellScreen(userId: String, onLogout: () -> Unit) {
    var selected by remember { mutableIntStateOf(0) }
    // Plain remember on purpose: the cloud uid must not be written into the saved-instance Bundle.
    var liveCamera by remember { mutableStateOf<CameraInfo?>(null) }
    // Same reason: the debug probe camera stays out of the Bundle too.
    var probeCamera by remember { mutableStateOf<CameraInfo?>(null) }
    // START_BLOCK_RENDER_SHELL
    val tabLabel = when {
        liveCamera != null -> "live"
        probeCamera != null -> "probe"
        else -> ShellTab.entries[selected].label
    }
    Timber.d("[Shell][render][BLOCK_RENDER_SHELL] hasUser=${userId.isNotBlank()} tab=$tabLabel")
    // END_BLOCK_RENDER_SHELL

    val live = liveCamera
    if (live != null) {
        LiveViewScreen(
            deviceId = live.deviceId,
            title = live.name.ifBlank { "Камера" },
            onClose = { liveCamera = null },
        )
        return
    }
    val probe = probeCamera
    if (probe != null) {
        LiveProbeScreen(
            deviceId = probe.deviceId,
            title = probe.name.ifBlank { "Камера" },
            onClose = { probeCamera = null },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ShellTab.entries[selected].label) },
                actions = {
                    IconButton(onClick = onLogout) {
                        Icon(Icons.Filled.Logout, contentDescription = "Выйти")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                ShellTab.entries.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = {
                            // START_BLOCK_NAVIGATE_SCREEN
                            Timber.d("[Shell][navigate][BLOCK_NAVIGATE_SCREEN] tab=${tab.label}")
                            liveCamera = null
                            probeCamera = null
                            selected = index
                            // END_BLOCK_NAVIGATE_SCREEN
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (ShellTab.entries[selected]) {
                ShellTab.Cameras -> com.yicamalt.ui.cameras.CameraListScreen(
                    onSessionExpired = onLogout,
                    onOpenCamera = { camera ->
                        Timber.d("[Shell][navigate][BLOCK_NAVIGATE_SCREEN] tab=live")
                        liveCamera = camera
                    },
                    onLongPressCamera = { camera ->
                        Timber.d("[Shell][navigate][BLOCK_NAVIGATE_SCREEN] tab=probe")
                        probeCamera = camera
                    },
                )
                ShellTab.Settings -> com.yicamalt.ui.settings.SettingsScreen(onBack = {})
            }
        }
    }
}