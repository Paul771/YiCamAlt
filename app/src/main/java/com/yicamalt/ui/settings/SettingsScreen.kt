// FILE: SettingsScreen.kt
// VERSION: 0.2.0
// START_MODULE_CONTRACT
//   PURPOSE: Compose settings screen: edit the runtime API base URL override; debug-only endpoint probe button.
//   SCOPE: stateless rendering driven by SettingsViewModel; save + back callbacks; probe lines (debug builds only).
//   DEPENDS: M-UI-SETTINGS (SettingsViewModel), M-CONFIG
//   LINKS: M-UI-SHELL (Phase-2 nav host)
//   ROLE: UI
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.ui.settings

import com.yicamalt.BuildConfig
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

// START_MODULE_MAP
//   SettingsScreen - Compose surface to edit the API base URL; debug probe button (BuildConfig.DEBUG only).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.2.0 - Scrollable column (Arrangement.Top); sign-probe button replaced by the debug-only
//     "Проба эндпоинтов (debug)" button (M-DEBUG-PROBE), hidden in release builds.
// END_CHANGE_SUMMARY

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val baseUrl by viewModel.baseUrl.collectAsState()
    val probeResults by viewModel.probeResults.collectAsState()
    val probeRunning by viewModel.probeRunning.collectAsState()
    var text by remember { mutableStateOf(baseUrl) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("API сервер (Yi Cloud)")
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("https://.../v1/") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text("Оставьте пустым, чтобы использовать значение по умолчанию.")
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { viewModel.saveBaseUrl(text) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Сохранить") }
            // START_BLOCK_DEBUG_PROBE_BUTTON
            if (BuildConfig.DEBUG) {
                Spacer(Modifier.height(20.dp))
                Text("Нажмите один раз после входа. Показываются только коды и имена полей.")
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.runEndpointProbe() },
                    enabled = !probeRunning,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (probeRunning) "Проба идёт…" else "Проба эндпоинтов (debug)") }
                probeResults?.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
            // END_BLOCK_DEBUG_PROBE_BUTTON
        }
    }
}
