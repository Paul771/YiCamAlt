// FILE: CameraListScreen.kt
// VERSION: 0.3.0
// START_MODULE_CONTRACT
//   PURPOSE: Compose screen listing the user's cameras (name, model, online/offline, transport, shared mark).
//   SCOPE: renders every CameraListUiState (loading / content + stale banner / empty / error) inside one LazyColumn
//     under a material3 1.2.0 pull-to-refresh container; retry buttons and the pull call CameraListViewModel.refresh;
//     an expired session offers a way back to login; a row tap calls onOpenCamera (debug live screen).
//     Never renders device id / serial / DID (SEC-01).
//   DEPENDS: M-UI-SHELL (host), M-CAMERA-LIST (via ViewModel)
//   LINKS: M-UI-SHELL, M-CAMERA-LIST, V-M-UI-SHELL, M-UI-LIVE-PROBE
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.ui.cameras

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yicamalt.camera.CameraInfo
import com.yicamalt.camera.TransportType
import kotlinx.coroutines.flow.first

// START_MODULE_MAP
//   CameraListScreen - camera list UI: pull-to-refresh, loading / content + stale banner / empty / error, retry;
//     onSessionExpired is offered on UNAUTHORIZED (host routes it to logout -> login screen); onOpenCamera on row tap.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.3.0 - GSD plan 02-09: row tap opens the debug live screen (onOpenCamera(CameraInfo)).
//   PREV_CHANGE: v0.2.0 - CAM-01/04/05: pull-to-refresh (material3 1.2.0 PullToRefreshContainer), Empty/Error screens
//     with retry, stale banner, transport + shared in the row; device id removed from the UI (SEC-01);
//     UNAUTHORIZED offers "Войти снова" (onSessionExpired).
//   OLDER_CHANGE: v0.1.0 - loading / error / content, row subtitle showed the device id.
// END_CHANGE_SUMMARY

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraListScreen(
    viewModel: CameraListViewModel = hiltViewModel(),
    onSessionExpired: () -> Unit = {},
    onOpenCamera: (CameraInfo) -> Unit = {},
) {
    // START_BLOCK_RENDER_CAMERA_LIST
    val state by viewModel.state.collectAsState()
    val ptr = rememberPullToRefreshState()
    // One pull = one refresh: start it, wait until the ViewModel reports "not refreshing", then end the indicator.
    if (ptr.isRefreshing) {
        LaunchedEffect(Unit) {
            viewModel.refresh()
            viewModel.state.first { st -> !st.isRefreshing() }
            ptr.endRefresh()
        }
    }
    Box(Modifier.fillMaxSize().nestedScroll(ptr.nestedScrollConnection)) {
        // Every state is rendered as LazyColumn items so the pull gesture works from every state.
        LazyColumn(Modifier.fillMaxSize()) {
            when (val s = state) {
                is CameraListUiState.Loading -> item {
                    Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is CameraListUiState.Content -> {
                    val stale = s.staleError
                    if (stale != null) {
                        item {
                            StaleBanner(
                                kind = stale,
                                onRetry = { viewModel.refresh() },
                                onSessionExpired = onSessionExpired,
                            )
                        }
                    }
                    items(s.cameras, key = { it.deviceId }) { CameraRow(it, onOpenCamera) }
                }
                is CameraListUiState.Empty -> item {
                    CenteredMessage(Modifier.fillParentMaxSize()) {
                        Text("Камеры не найдены", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Камеры добавляются в официальном приложении Yi Home. Потяните вниз, чтобы обновить.",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { viewModel.refresh() }, enabled = !s.refreshing) { Text("Повторить") }
                    }
                }
                is CameraListUiState.Error -> item {
                    CenteredMessage(Modifier.fillParentMaxSize()) {
                        Text(s.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        if (s.kind == ErrorKind.UNAUTHORIZED) {
                            Button(onClick = onSessionExpired) { Text("Войти снова") }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { viewModel.refresh() }) { Text("Повторить") }
                        } else {
                            Button(onClick = { viewModel.refresh() }) { Text("Повторить") }
                        }
                    }
                }
            }
        }
        PullToRefreshContainer(state = ptr, modifier = Modifier.align(Alignment.TopCenter))
    }
    // END_BLOCK_RENDER_CAMERA_LIST
}

@Composable
private fun CenteredMessage(modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@Composable
private fun StaleBanner(kind: ErrorKind, onRetry: () -> Unit, onSessionExpired: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Не удалось обновить: ${kind.userMessage()}",
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (kind == ErrorKind.UNAUTHORIZED) {
                TextButton(onClick = onSessionExpired) { Text("Войти") }
            }
            TextButton(onClick = onRetry) { Text("Повторить") }
        }
    }
}

@Composable
private fun CameraRow(camera: CameraInfo, onClick: (CameraInfo) -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable { onClick(camera) },
    ) {
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
                Text(camera.name.ifBlank { "Камера" }, style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(
                        camera.model.takeIf { it.isNotBlank() },
                        if (camera.online) "онлайн" else "офлайн",
                        transportLabel(camera.transport),
                        "общая".takeIf { camera.shared },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun transportLabel(t: TransportType): String = when (t) {
    TransportType.TNP -> "TNP"
    TransportType.TUTK -> "TUTK"
    TransportType.LANGTAO -> "Langtao"
    TransportType.UNKNOWN -> "транспорт ?"
}
