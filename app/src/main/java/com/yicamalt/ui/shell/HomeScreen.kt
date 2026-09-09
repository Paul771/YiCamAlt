// FILE: HomeScreen.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Minimal post-login home: confirms the session, shows the user id, offers logout.
//   SCOPE: static session info + logout action. Full shell with bottom nav arrives in Phase-2 (M-UI-SHELL).
//   DEPENDS: M-AUTH (session via caller)
//   LINKS: M-UI-SHELL, M-UI-LOGIN
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import timber.log.Timber

// START_MODULE_MAP
//   HomeScreen - post-login landing screen; temporary shell stub for Phase-1.5.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - Created after device logs showed LoginUiState.Success with no
//     destination (MainActivity mounted LoginScreen unconditionally, onLoggedIn was a no-op).
// END_CHANGE_SUMMARY

@Composable
fun HomeScreen(userId: String, onLogout: () -> Unit) {
    // START_BLOCK_RENDER_SHELL
    Timber.d("[Shell][render][BLOCK_RENDER_SHELL] user=$userId")
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Вход выполнен", style = MaterialTheme.typography.headlineMedium)
        Text("ID пользователя: $userId", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onLogout) {
            Text("Выйти")
        }
    }
    // END_BLOCK_RENDER_SHELL
}