package com.albahadly.swanntv.ui

import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Text
import com.albahadly.swanntv.AppLog
import com.albahadly.swanntv.model.Camera
import com.albahadly.swanntv.playback.StreamPlayer

/**
 * One grid tile: the camera's sub-stream on a SurfaceView, the last picture while it isn't
 * live, its state, and its name. The same tile grows to full screen when opened, so its
 * SurfaceView (and the stream on it) is never interrupted.
 */
@Composable
fun CameraTile(
    camera: Camera,
    slot: Int,
    state: ViewerState,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val fullscreen = state.isFullscreen
    val active = state.isTileActive(camera.index)
    var surfaceView by remember { mutableStateOf<SurfaceView?>(null) }
    var player by remember { mutableStateOf<StreamPlayer?>(null) }
    var focused by remember { mutableStateOf(false) }

    // The stream runs exactly while the tile is active and its surface exists.
    DisposableEffect(surfaceView, active, state.session) {
        val view = surfaceView
        if (view != null && active) player = state.manager.attachTile(camera, view)
        onDispose {
            if (view != null && active) {
                state.manager.detachTile(camera.index)
                player = null
            }
        }
    }

    Box(
        modifier
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event -> !fullscreen && onTileKey(event, camera, slot, state) }
            .focusable()
            .background(Palette.Tile)
    ) {
        AndroidView(
            factory = { context ->
                AppLog.i("tile ${camera.name}: video view created")
                SurfaceView(context).also { surfaceView = it }
            },
            modifier = Modifier.fillMaxSize(),
        )

        val status = player?.status
        if (status != StreamPlayer.Status.LIVE) {
            state.manager.lastPictures[camera.index]?.let { picture ->
                Image(picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            if (!fullscreen && active) TileStatus(status, player?.retryDelaySec ?: 0)
        }

        if (!fullscreen) {
            if (status == StreamPlayer.Status.LIVE) LiveBadge(Modifier.align(Alignment.TopStart).padding(8.dp))
            NameChip(camera, focused, Modifier.align(Alignment.BottomStart).padding(8.dp))
            if (focused) Box(Modifier.fillMaxSize().border(3.dp, Palette.Accent))
        }
    }
}

/** OK opens the camera; left/right past the grid's edge changes page. */
private fun onTileKey(event: KeyEvent, camera: Camera, slot: Int, state: ViewerState): Boolean {
    val columns = state.layout.columns
    val column = slot % columns
    val rowStart = slot - column
    val lastSlot = state.visibleCameras.lastIndex
    return when {
        event.key.isSelect() -> {
            if (event.type == KeyEventType.KeyUp) state.open(camera)
            true
        }
        event.key == Key.DirectionRight && (column == columns - 1 || slot == lastSlot) &&
            state.page < state.pageCount - 1 -> {
            if (event.type == KeyEventType.KeyDown) state.nextPage(focusSlot = rowStart)
            true
        }
        event.key == Key.DirectionLeft && column == 0 && state.page > 0 -> {
            if (event.type == KeyEventType.KeyDown) state.previousPage(focusSlot = rowStart + columns - 1)
            true
        }
        else -> false
    }
}

@Composable
private fun NameChip(camera: Camera, focused: Boolean, modifier: Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .background(if (focused) Palette.Accent else Palette.Scrim, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            camera.number.toString(),
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (focused) Palette.OnAccent else Palette.Quiet,
        )
        Text(
            camera.name,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (focused) Palette.OnAccent else Palette.TextBright,
        )
    }
}

@Composable
private fun TileStatus(status: StreamPlayer.Status?, retrySec: Int) {
    val (title, detail, color) = when (status) {
        StreamPlayer.Status.RECONNECTING -> Triple("Reconnecting…", null, Palette.Warning)
        StreamPlayer.Status.OFFLINE -> Triple("Offline", "Retrying every $retrySec s", Palette.Quiet)
        StreamPlayer.Status.AUTH_FAILED -> Triple("Sign-in failed", "Check the username and password", Palette.Error)
        else -> Triple("Connecting…", null, Palette.Text)
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .background(Palette.Scrim, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color)
            if (detail != null) Text(detail, fontSize = 9.sp, color = Palette.Quiet, textAlign = TextAlign.Center)
        }
    }
}
