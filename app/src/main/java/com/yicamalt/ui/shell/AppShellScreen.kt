// FILE: AppShellScreen.kt
// VERSION: 0.5.0
// START_MODULE_CONTRACT
//   PURPOSE: Top-level post-login shell: tab host (Cameras / Settings / Events) with session-aware header.
//   SCOPE: renders active tab content; emits BLOCK_RENDER_SHELL / BLOCK_NAVIGATE_SCREEN markers.
//     Tabs in order Камеры, Настройки, События (D-01; indices 0 / 1 unchanged). A camera row tap shows
//     LiveViewScreen, a long press the debug LiveProbeScreen, in place of the tab content (bottom bar hidden) until
//     back / tab switch / logout. On the Events tab a card tap shows ClipDialog over the list; its «Открыть запись на
//     SD» opens LiveViewScreen of that camera with initialArchiveMs = event time (EVT-03, EVT-04).
//   DEPENDS: M-UI-SHELL, M-CAMERA-LIST, M-UI-SETTINGS, M-UI-LIVEVIEW, M-UI-LIVE-PROBE, M-UI-EVENTS, M-AUTH (logout via caller)
//   LINKS: M-UI-SHELL, M-UI-LIVEVIEW, M-UI-LIVE-PROBE, M-UI-EVENTS
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
import androidx.compose.material.icons.filled.Notifications
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
import androidx.hilt.navigation.compose.hiltViewModel
import com.yicamalt.camera.CameraInfo
import com.yicamalt.ui.events.ClipDialog
import com.yicamalt.ui.events.EventsScreen
import com.yicamalt.ui.events.EventsViewModel
import com.yicamalt.ui.live.LiveProbeScreen
import com.yicamalt.ui.live.LiveViewScreen
import timber.log.Timber

// START_MODULE_MAP
//   AppShellScreen - bottom-nav host for Cameras / Settings / Events tabs (post-login) plus the live view and debug
//     probe overlays.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.5.0 - GSD plan 05-05: clip dialog and SD jump. clipKey renders ClipDialog while the Events tab is
//     shown; onOpenSd(deviceId, timeMs) clears clipKey, resolves the camera name from the EventsViewModel chips (the
//     same cached camera list the dialog used to enable the button) and sets liveCamera + liveArchiveMs (both plain
//     remember, never saveable). LiveViewScreen gets initialArchiveMs = liveArchiveMs; closing it clears both and the
//     Events tab stays selected; opening a camera from the list resets liveArchiveMs. Navigate logs tab=live
//     source=event|list only.
//   PREV_CHANGE: v0.4.0 - GSD plan 05-04: third tab «События» (EventsScreen, D-01). clipKey is a plain remember
//     (never saveable: the event key stays out of the Bundle) set by a card tap; 05-05 renders the clip dialog for
//     it, until then a non-null clipKey is ignored. Tab switch / logout clears it.
//   PREV_CHANGE: v0.3.0 - GSD plan 03-11: tap -> LiveViewScreen, long-press -> LiveProbeScreen (debug, TNP H1-H3
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
    Events("События", Icons.Filled.Notifications),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShellScreen(userId: String, onLogout: () -> Unit) {
    var selected by remember { mutableIntStateOf(0) }
    // Plain remember on purpose: the cloud uid must not be written into the saved-instance Bundle.
    var liveCamera by remember { mutableStateOf<CameraInfo?>(null) }
    // Same reason: the debug probe camera stays out of the Bundle too.
    var probeCamera by remember { mutableStateOf<CameraInfo?>(null) }
    // Same reason: the event key of a tapped card stays out of the Bundle; it drives ClipDialog.
    var clipKey by remember { mutableStateOf<String?>(null) }
    // Same reason: the event time of an SD jump (EVT-04) stays out of the Bundle; null for a plain camera open.
    var liveArchiveMs by remember { mutableStateOf<Long?>(null) }
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
            onClose = {
                liveCamera = null
                liveArchiveMs = null
            },
            initialArchiveMs = liveArchiveMs,
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
                            liveArchiveMs = null
                            probeCamera = null
                            clipKey = null
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
                        Timber.d("[Shell][navigate][BLOCK_NAVIGATE_SCREEN] tab=live source=list")
                        liveArchiveMs = null
                        liveCamera = camera
                    },
                    onLongPressCamera = { camera ->
                        Timber.d("[Shell][navigate][BLOCK_NAVIGATE_SCREEN] tab=probe")
                        probeCamera = camera
                    },
                )
                ShellTab.Settings -> com.yicamalt.ui.settings.SettingsScreen(onBack = {})
                ShellTab.Events -> {
                    val eventsVm: EventsViewModel = hiltViewModel()
                    EventsScreen(
                        onSessionExpired = onLogout,
                        onOpenClip = { key -> clipKey = key },
                        viewModel = eventsVm,
                    )
                    clipKey?.let { key ->
                        ClipDialog(
                            key = key,
                            onDismiss = { clipKey = null },
                            onOpenSd = { deviceId, timeMs ->
                                clipKey = null
                                // LiveViewScreen needs only the uid and the title; the button is enabled only for a
                                // camera of the cached list, which also feeds these chips.
                                val name = eventsVm.state.value.cameras.firstOrNull { it.deviceId == deviceId }?.name
                                Timber.d("[Shell][navigate][BLOCK_NAVIGATE_SCREEN] tab=live source=event")
                                liveArchiveMs = timeMs
                                liveCamera = CameraInfo(deviceId, name.orEmpty(), model = "", online = true, streamUrl = null)
                            },
                        )
                    }
                }
            }
        }
    }
}