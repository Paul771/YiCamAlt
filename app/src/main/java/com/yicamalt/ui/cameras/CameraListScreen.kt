// FILE: CameraListScreen.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Compose screen listing the user's cameras with online status.
//   SCOPE: state rendering driven by CameraListViewModel; loading / error / empty / list states.
//   DEPENDS: M-UI-SHELL (host), M-CAMERA-LIST (via ViewModel)
//   LINKS: M-UI-SHELL, M-CAMERA-LIST
//   ROLE: UI
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.ui.cameras

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yicamalt.camera.CameraInfo

// START_MODULE_MAP
//   CameraListScreen - camera list UI (loading/error/empty/content).
// END_MODULE_MAP

@Composable
fun CameraListScreen(viewModel: CameraListViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    when (val s = state) {
        is CameraListUiState.Loading -> CenterBox { CircularProgressIndicator() }
        is CameraListUiState.Error -> CenterBox { Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(s.message, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(12.dp))
            androidx.compose.material3.Button(onClick = { viewModel.load() }) { Text("Повторить") }
        } }
        is CameraListUiState.Content -> {
            if (s.cameras.isEmpty()) {
                CenterBox { Text("Камеры не найдены") }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(s.cameras, key = { it.deviceId }) { cam -> CameraRow(cam) }
                }
            }
        }
    }
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun CameraRow(camera: CameraInfo) {
    Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(if (camera.online) Color(0xFF4CAF50) else Color(0xFF9E9E9E), CircleShape),
            )
            Spacer(Modifier.size(12.dp))
            Column {
                Text(camera.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${camera.model} · ${if (camera.online) "online" else "offline"} · ${camera.deviceId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}