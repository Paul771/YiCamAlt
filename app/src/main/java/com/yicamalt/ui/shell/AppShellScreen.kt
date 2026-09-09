// FILE: AppShellScreen.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Top-level post-login shell: tab host (Cameras / Settings) with session-aware header.
//   SCOPE: renders active tab content; emits BLOCK_RENDER_SHELL / BLOCK_NAVIGATE_SCREEN markers.
//     Live-View / Events tabs arrive in Phase-3; only Cameras + Settings are wired now.
//   DEPENDS: M-UI-SHELL, M-CAMERA-LIST, M-UI-SETTINGS, M-AUTH (logout via caller)
//   LINKS: M-UI-SHELL
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import timber.log.Timber

// START_MODULE_MAP
//   AppShellScreen - bottom-nav host for Cameras/Settings tabs (post-login).
// END_MODULE_MAP

private enum class ShellTab(val label: String, val icon: ImageVector) {
    Cameras("Камеры", Icons.Filled.Videocam),
    Settings("Настройки", Icons.Filled.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShellScreen(userId: String, onLogout: () -> Unit) {
    var selected by remember { mutableIntStateOf(0) }
    // START_BLOCK_RENDER_SHELL
    Timber.d("[Shell][render][BLOCK_RENDER_SHELL] user=$userId tab=${ShellTab.entries[selected].label}")
    // END_BLOCK_RENDER_SHELL

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
                ShellTab.Cameras -> com.yicamalt.ui.cameras.CameraListScreen()
                ShellTab.Settings -> com.yicamalt.ui.settings.SettingsScreen(onBack = {})
            }
        }
    }
}