// FILE: LiveProbeScreen.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Minimal debug live screen for one camera: SurfaceView video, Connect / Stop, "relay only" toggle,
//     HEVC decoder info, explicit message and the value-free status line (full live view is GSD Phase 3).
//   SCOPE: binds LiveProbeViewModel to the composition: open on enter; close on back ("user"), on ON_STOP
//     ("background") and when the screen leaves composition ("screen-left"); SurfaceHolder callbacks feed
//     attachSurface / detachSurface. Never shows the device id, DID, password, InitString, license or IP.
//   DEPENDS: M-UI-LIVE-PROBE (LiveProbeViewModel)
//   LINKS: M-UI-LIVE-PROBE, V-M-UI-LIVE-PROBE, M-UI-SHELL
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.ui.live

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

// START_MODULE_MAP
//   LiveProbeScreen - debug live screen: SurfaceView + Connect / Stop / relay toggle + message + status line.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 02-09: initial debug live screen opened from the camera list.
// END_CHANGE_SUMMARY

@Composable
fun LiveProbeScreen(
    deviceId: String,
    onClose: () -> Unit,
    title: String = LiveProbeTexts.DEFAULT_TITLE,
    viewModel: LiveProbeViewModel = hiltViewModel(),
) {
    // START_BLOCK_BIND_PROBE
    LaunchedEffect(deviceId) { viewModel.open(deviceId, title) }
    BackHandler {
        viewModel.close("user")
        onClose()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.onStop()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.close("screen-left") }
    }
    // END_BLOCK_BIND_PROBE

    // START_BLOCK_RENDER_PROBE
    val ui by viewModel.ui.collectAsState()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(ui.title, style = MaterialTheme.typography.titleLarge)
        AndroidView(
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            factory = { context ->
                SurfaceView(context).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) = viewModel.attachSurface(holder.surface)
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                        override fun surfaceDestroyed(holder: SurfaceHolder) = viewModel.detachSurface()
                    })
                }
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Checkbox(checked = ui.forceRelay, onCheckedChange = { viewModel.setForceRelay(it) }, enabled = ui.canConnect)
            Text("Только relay")
            Button(onClick = { viewModel.connect() }, enabled = ui.canConnect) { Text("Подключить") }
            OutlinedButton(onClick = { viewModel.stop() }, enabled = ui.canStop) { Text("Стоп") }
        }
        Text(ui.hevcInfo, style = MaterialTheme.typography.bodySmall)
        ui.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        SelectionContainer {
            Text(ui.statusLine, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
    // END_BLOCK_RENDER_PROBE
}
