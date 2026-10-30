// FILE: LiveViewScreen.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Compose live view of one camera (M-UI-LIVEVIEW renderer): TextureView video with badge and controls drawn
//     over the picture, fullscreen, pinch / pan / double-tap zoom, snapshot and lifecycle binding.
//   SCOPE: binds LiveViewModel to the composition (open on enter, ON_STOP / ON_START, closeScreen on dispose);
//     TextureView in one stable composition slot (portrait and fullscreen differ only by modifiers); fullscreen by
//     button or landscape with hidden system bars, controls auto-hide in fullscreen, back leaves fullscreen first
//     (D-08); zoom up to 4x with clamped pan and fit / fill double tap (D-09); keepScreenOn while rendering;
//     snapshot via TextureView.getBitmap(videoW, videoH) with the API 26-28 storage permission (LIVE-08);
//     orientation and system bars restored on leave (D-10). Never shows or logs the device id or camera name.
//   DEPENDS: M-UI-LIVEVIEW (LiveViewModel, ZoomMath), M-MEDIA-SNAPSHOT (via the view model)
//   LINKS: M-UI-LIVEVIEW, V-M-UI-LIVEVIEW, M-UI-SHELL
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.ui.live

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.SurfaceTexture
import android.os.Build
import android.view.OrientationEventListener
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import timber.log.Timber

// START_MODULE_MAP
//   LiveViewScreen - live view of one camera: TextureView video, badge, sound / HD-SD / snapshot / fullscreen controls,
//     retry / connect, zoom gestures, lifecycle binding.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 03-11: created. TextureView instead of SurfaceView (overlays over the picture,
//     graphicsLayer zoom, getBitmap snapshot; no z-order override needed).Portrait look: white Surface with black text (user
//     decision), black video box. Snapshot size = decoded ui.videoWidth x ui.videoHeight (TextureView size only as a
//     fallback); graphicsLayer zoom does not affect getBitmap, so the frame is full and unzoomed.
// END_CHANGE_SUMMARY

private val BadgeGood = Color(0xFF2E7D32)
private val BadgeWarn = Color(0xFFF9A825)
private val BadgeError = Color(0xFFC62828)
private val BadgeNeutral = Color(0xFF616161)
private val OverlayBg = Color(0x88000000)

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

private fun badgeColor(tone: BadgeTone): Color = when (tone) {
    BadgeTone.GOOD -> BadgeGood
    BadgeTone.WARN -> BadgeWarn
    BadgeTone.ERROR -> BadgeError
    BadgeTone.NEUTRAL -> BadgeNeutral
}

// START_CONTRACT: LiveViewScreen
//   PURPOSE: Show the live stream of one camera with all Phase 3 controls (D-04..D-10).
//   INPUTS: { deviceId: String - cloud uid, never shown or logged; title: String - camera name for the title row;
//     onClose: () -> Unit - leave the screen; viewModel: LiveViewModel }
//   OUTPUTS: { Unit - composable }
//   SIDE_EFFECTS: LiveViewModel.open / onStop / onStart / closeScreen; activity requestedOrientation and system bars
//     (restored on dispose); Toasts; BLOCK_RENDER_VIDEO_SURFACE / BLOCK_FULLSCREEN / BLOCK_LIFECYCLE logs
//   LINKS: M-UI-LIVEVIEW, LiveViewModel, ZoomMath
// END_CONTRACT: LiveViewScreen
@Composable
fun LiveViewScreen(
    deviceId: String,
    title: String,
    onClose: () -> Unit,
    viewModel: LiveViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = remember(context) { context.findActivity() }
    val ui by viewModel.ui.collectAsState()

    // START_BLOCK_BIND_LIVE
    LaunchedEffect(deviceId) { viewModel.open(deviceId, title) }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { e ->
            when (e) {
                is LiveEvent.Toast -> Toast.makeText(context, e.text, Toast.LENGTH_SHORT).show()
            }
        }
    }
    // END_BLOCK_BIND_LIVE

    // START_BLOCK_LIFECYCLE
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    Timber.d("[LiveView][lifecycle][BLOCK_LIFECYCLE] event=%s", "stop")
                    viewModel.onStop()
                }
                Lifecycle.Event.ON_START -> {
                    Timber.d("[LiveView][lifecycle][BLOCK_LIFECYCLE] event=%s", "start")
                    viewModel.onStart()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // END_BLOCK_LIFECYCLE

    // Screen state.
    var requestedFullscreen by remember { mutableStateOf(false) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val fullscreen = ZoomMath.isFullscreen(landscape, requestedFullscreen)
    var zoom by remember { mutableStateOf(Zoom()) }
    var controlsVisible by remember { mutableStateOf(true) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val textureRef = remember { mutableStateOf<TextureView?>(null) }

    // START_BLOCK_FULLSCREEN
    // After a forced PORTRAIT exit, give the sensor back only once the phone is physically portrait (Pitfall 9).
    val portraitWaiter = remember(activity) {
        object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                if (orientation in 0..30 || orientation in 330..359) {
                    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    disable()
                }
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            portraitWaiter.disable()
            viewModel.closeScreen()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.let { w ->
                WindowCompat.getInsetsController(w, view).show(WindowInsetsCompat.Type.systemBars())
            }
            Timber.d("[LiveView][lifecycle][BLOCK_LIFECYCLE] event=%s", "dispose")
        }
    }
    LaunchedEffect(fullscreen) {
        Timber.d(
            "[LiveView][render][BLOCK_FULLSCREEN] on=%b source=%s",
            fullscreen,
            if (requestedFullscreen) "button" else "rotation",
        )
        activity?.window?.let { w ->
            WindowCompat.getInsetsController(w, view).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (fullscreen) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
            }
        }
        zoom = Zoom()
        controlsVisible = true
    }
    LaunchedEffect(controlsVisible, fullscreen) {
        if (fullscreen && controlsVisible) {
            delay(ZoomMath.CONTROLS_AUTO_HIDE_MS)
            controlsVisible = false
        }
    }
    val enterFullscreen = {
        requestedFullscreen = true
        portraitWaiter.disable()
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
    val exitFullscreen = {
        requestedFullscreen = false
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        if (portraitWaiter.canDetectOrientation()) {
            portraitWaiter.enable()
        } else {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    BackHandler {
        if (fullscreen) exitFullscreen() else onClose()
    }
    // END_BLOCK_FULLSCREEN

    // START_BLOCK_SNAPSHOT_CAPTURE
    // Main thread (TextureView requirement). Decoded stream size from LiveViewUi; the TextureView size is a fallback.
    val capture = {
        val tv = textureRef.value
        val bmp = if (ui.videoRendering && tv != null && tv.isAvailable) {
            tv.getBitmap(ui.videoWidth ?: tv.width, ui.videoHeight ?: tv.height)
        } else {
            null
        }
        viewModel.saveSnapshot(bmp)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            capture()
        } else {
            Toast.makeText(context, LiveViewTexts.SNAPSHOT_DENIED, Toast.LENGTH_SHORT).show()
        }
    }
    val onSnapshot = {
        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) else capture()
    }
    // END_BLOCK_SNAPSHOT_CAPTURE

    val fg = if (fullscreen) Color.White else Color.Black
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = if (fullscreen) Color.Black else Color.White,
        contentColor = fg,
    ) {
        Column(
            modifier = if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxSize().safeDrawingPadding(),
            verticalArrangement = Arrangement.spacedBy(if (fullscreen) 0.dp else 8.dp),
        ) {
            if (!fullscreen) {
                Text(
                    ui.title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp),
                )
            }
            // Stable slot: this Box (and the TextureView inside) stays at the same position in both modes (Pitfall 8).
            Box(
                modifier = (
                    if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(ui.videoAspect ?: (16f / 9f))
                    )
                    .background(Color.Black)
                    .clipToBounds()
                    .onSizeChanged { boxSize = it },
            ) {
                // START_BLOCK_RENDER_VIDEO_SURFACE
                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                                    Timber.d("[LiveView][render][BLOCK_RENDER_VIDEO_SURFACE] event=available w=%d h=%d", width, height)
                                    viewModel.attachSurface(Surface(st))
                                }
                                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit
                                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                    Timber.d("[LiveView][render][BLOCK_RENDER_VIDEO_SURFACE] event=destroyed w=%d h=%d", width, height)
                                    viewModel.detachSurface()
                                    return true
                                }
                                override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                            }
                            textureRef.value = this
                        }
                    },
                    update = { it.keepScreenOn = ui.videoRendering },
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        // Stopped / given-up session: hide the last decoded frame so it does not look like live video.
                        alpha = if (ui.showConnect || ui.showRetry) 0f else 1f
                        scaleX = zoom.scale
                        scaleY = zoom.scale
                        translationX = zoom.tx
                        translationY = zoom.ty
                    },
                )
                // END_BLOCK_RENDER_VIDEO_SURFACE

                // START_BLOCK_GESTURES
                val vw = ui.videoWidth
                val vh = ui.videoHeight
                Box(
                    Modifier
                        .matchParentSize()
                        .pointerInput(boxSize) {
                            detectTransformGestures { _, pan, zoomChange, _ ->
                                zoom = ZoomMath.applyGesture(
                                    zoom, zoomChange, pan.x, pan.y,
                                    boxSize.width.toFloat(), boxSize.height.toFloat(),
                                )
                            }
                        }
                        .pointerInput(boxSize, ui.videoWidth, ui.videoHeight) {
                            detectTapGestures(
                                onTap = { controlsVisible = !controlsVisible },
                                onDoubleTap = {
                                    val fill = if (vw != null && vh != null) {
                                        ZoomMath.fillScale(boxSize.width.toFloat(), boxSize.height.toFloat(), vw, vh)
                                    } else {
                                        1f
                                    }
                                    zoom = ZoomMath.doubleTap(zoom, fill)
                                },
                            )
                        },
                )
                // END_BLOCK_GESTURES

                // START_BLOCK_OVERLAYS
                Column(
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        ui.badge.text,
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .background(badgeColor(ui.badge.tone), RoundedCornerShape(12.dp))
                            .clickable { viewModel.toggleDebug() }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                    if (ui.debugExpanded) {
                        Text(
                            ui.debugLine,
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.background(OverlayBg).padding(4.dp),
                        )
                    }
                }
                if (ui.showRetry) {
                    Button(onClick = { viewModel.retry() }, modifier = Modifier.align(Alignment.Center)) { Text("Повторить") }
                } else if (ui.showConnect) {
                    Button(onClick = { viewModel.connect() }, modifier = Modifier.align(Alignment.Center)) { Text("Подключить") }
                }
                if (controlsVisible || !fullscreen) {
                    Row(
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(OverlayBg)
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                    ) {
                        if (ui.soundVisible) {
                            IconButton(onClick = { viewModel.toggleMute() }) {
                                Icon(
                                    if (ui.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = if (ui.muted) "Включить звук" else "Выключить звук",
                                    tint = Color.White,
                                )
                            }
                        }
                        if (ui.qualityVisible) {
                            TextButton(
                                onClick = { viewModel.toggleQuality() },
                                enabled = !ui.qualityPending,
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = Color.White,
                                    disabledContentColor = Color.Gray,
                                ),
                            ) { Text(ui.qualityLabel ?: "") }
                        }
                        IconButton(onClick = onSnapshot) {
                            Icon(Icons.Filled.PhotoCamera, contentDescription = "Снимок", tint = Color.White)
                        }
                        IconButton(onClick = { if (fullscreen) exitFullscreen() else enterFullscreen() }) {
                            Icon(
                                if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                                contentDescription = if (fullscreen) "Свернуть" else "На весь экран",
                                tint = Color.White,
                            )
                        }
                    }
                }
                // END_BLOCK_OVERLAYS
            }
            if (!fullscreen) {
                ui.message?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 12.dp))
                }
            }
        }
    }
}
