// FILE: ClipDialog.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Event clip dialog over the events list (EVT-03, D-02): plays the decrypted local MP4 with the platform
//     MediaPlayer on a TextureView and offers «Открыть запись на SD» (EVT-04).
//   SCOPE: ClipDialog composable (16:9 video box: spinner / player / message; camera name + time; «Повторить»,
//     «Открыть запись на SD», «Закрыть»), ClipPlayerHolder (one MediaPlayer + Surface, idempotent release).
//     TextureView on purpose: the surface-backed platform views render black on the test MIUI phone unless
//     setZOrderOnTop(true), which would draw over the dialog buttons. No new dependency.
//   DEPENDS: M-UI-EVENTS (ClipViewModel, ClipUi, ClipState, ClipEvent, ClipTexts), android.media.MediaPlayer
//   LINKS: M-UI-EVENTS, M-EVENT-PLAYBACK, V-M-UI-EVENTS, EVT-03, EVT-04
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT

// START_MODULE_MAP
//   ClipDialog - dialog for one event key; onDismiss closes it, onOpenSd(deviceId, timeMs) hands the SD jump over.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 05-05: created. Player lifecycle: created in onSurfaceTextureAvailable
//     (setDataSource(file.path), setSurface, prepareAsync, start on prepared, onError -> ClipViewModel.onPlayerError);
//     released in onSurfaceTextureDestroyed and in DisposableEffect onDispose. Logs: BLOCK_CLIP_PLAYER state only.
// END_CHANGE_SUMMARY
package com.yicamalt.ui.events

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface as M3Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import timber.log.Timber
import java.io.File

// Main thread only: one MediaPlayer and its Surface; release() is idempotent.
private class ClipPlayerHolder {
    var player: MediaPlayer? = null
    var surface: Surface? = null

    fun replay() {
        val p = player ?: return
        try {
            p.seekTo(0)
            p.start()
        } catch (e: IllegalStateException) {
            Timber.d("[Events][clip][BLOCK_CLIP_PLAYER] state=replay-ignored")
        }
    }

    fun release() {
        val p = player
        val s = surface
        player = null
        surface = null
        if (p != null) {
            p.release()
            Timber.d("[Events][clip][BLOCK_CLIP_PLAYER] state=released")
        }
        s?.release()
    }
}

// START_CONTRACT: ClipDialog
//   PURPOSE: Show one event clip over the list with «Повторить», «Открыть запись на SD» and «Закрыть».
//   INPUTS: { key: String - event_key (plain remember state of the shell, never saved or logged); onDismiss: () -> Unit;
//     onOpenSd: (deviceId: String, timeMs: Long) -> Unit - camera + event time for the SD jump; viewModel: ClipViewModel }
//   OUTPUTS: { Unit - composable }
//   SIDE_EFFECTS: ClipViewModel.open(key) per key, dismiss on leaving the composition; MediaPlayer on a TextureView
//     while the clip is Ready; BLOCK_CLIP_PLAYER logs
//   LINKS: ClipViewModel, AppShellScreen, LiveViewScreen.initialArchiveMs
// END_CONTRACT: ClipDialog
@Composable
fun ClipDialog(
    key: String,
    onDismiss: () -> Unit,
    onOpenSd: (deviceId: String, timeMs: Long) -> Unit,
    viewModel: ClipViewModel = hiltViewModel(),
) {
    // START_BLOCK_CLIP_DIALOG
    val ui by viewModel.ui.collectAsState()
    val openSd by rememberUpdatedState(onOpenSd)
    val dismiss by rememberUpdatedState(onDismiss)
    val holder = remember { ClipPlayerHolder() }

    LaunchedEffect(key) { viewModel.open(key) }
    DisposableEffect(key) { onDispose { viewModel.dismiss() } }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { e ->
            when (e) {
                is ClipEvent.OpenSd -> openSd(e.deviceId, e.timeMs)
            }
        }
    }
    val close = {
        viewModel.dismiss()
        dismiss()
    }

    Dialog(onDismissRequest = close) {
        M3Surface(shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(16.dp)) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    when (val s = ui?.state) {
                        null, ClipState.Loading -> CircularProgressIndicator()
                        is ClipState.Ready -> key(s.file) {
                            ClipPlayer(s.file, holder, viewModel::onPlayerError)
                        }
                        is ClipState.Message -> Text(
                            s.text,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                val current = ui
                if (current != null) {
                    Text(current.cameraName, style = MaterialTheme.typography.titleMedium)
                    Text(current.timeLabel, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (current?.state is ClipState.Ready) {
                        TextButton(onClick = { holder.replay() }) { Text(ClipTexts.REPLAY) }
                    }
                    TextButton(onClick = close) { Text(ClipTexts.CLOSE) }
                }
                Button(
                    onClick = { viewModel.openSd() },
                    enabled = current?.sdEnabled == true,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(current?.sdLabel ?: ClipTexts.CAMERA_UNAVAILABLE)
                }
            }
        }
    }
    // END_BLOCK_CLIP_DIALOG
}

@Composable
private fun ClipPlayer(file: File, holder: ClipPlayerHolder, onError: (Int) -> Unit) {
    // START_BLOCK_CLIP_PLAYER
    val onPlayerError by rememberUpdatedState(onError)
    DisposableEffect(holder) { onDispose { holder.release() } }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                        holder.release()
                        val surface = Surface(st)
                        val player = MediaPlayer()
                        holder.surface = surface
                        holder.player = player
                        try {
                            player.setDataSource(file.path)
                            player.setSurface(surface)
                            player.setOnPreparedListener {
                                Timber.d("[Events][clip][BLOCK_CLIP_PLAYER] state=prepared")
                                it.start()
                            }
                            player.setOnErrorListener { _, what, _ ->
                                onPlayerError(what)
                                true
                            }
                            player.prepareAsync()
                        } catch (e: Exception) {
                            Timber.d("[Events][clip][BLOCK_CLIP_PLAYER] state=setup-failed error=%s", e.javaClass.simpleName)
                            holder.release()
                            onPlayerError(MediaPlayer.MEDIA_ERROR_UNKNOWN)
                        }
                    }

                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit

                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        holder.release()
                        return true
                    }

                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                }
            }
        },
    )
    // END_BLOCK_CLIP_PLAYER
}
