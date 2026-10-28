package com.albahadly.swanntv.ui

import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Text
import com.albahadly.swanntv.BuildConfig
import com.albahadly.swanntv.model.Camera
import com.albahadly.swanntv.update.UpdateManager
import androidx.compose.foundation.border
import com.albahadly.swanntv.playback.StreamPlayer

@Composable
fun ViewerScreen(state: ViewerState, onOpenSettings: () -> Unit, updates: UpdateManager? = null) {
    val focusRequesters = remember { HashMap<Int, FocusRequester>() }
    fun requester(index: Int) = focusRequesters.getOrPut(index) { FocusRequester() }

    BackHandler(enabled = state.isFullscreen) { state.back() }

    // Move focus where the state asks (start-up, page change, back from full screen).
    LaunchedEffect(state.focusTarget, state.isFullscreen, state.page, state.layout) {
        val target = state.focusTarget ?: return@LaunchedEffect
        if (state.isFullscreen) return@LaunchedEffect
        withFrameNanos { }
        runCatching { requester(target).requestFocus() }
        state.focusTarget = null
    }

    // Full screen keeps only the opened tile composed: give it focus, so the remote keeps
    // working even when a number key opened a tile that wasn't focused.
    LaunchedEffect(state.expandedIndex) {
        val index = state.expandedIndex ?: return@LaunchedEffect
        withFrameNanos { }
        runCatching { requester(index).requestFocus() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .onPreviewKeyEvent { onScreenKey(it, state) }
    ) {
        if (state.cameras.isEmpty()) {
            NoCameras(onOpenSettings)
            return@Box
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val grid = GridGeometry(maxWidth, maxHeight, state.layout)

            // Tiles. key() keeps each tile's SurfaceView alive when it grows to full screen.
            state.visibleCameras.forEachIndexed { slot, camera ->
                key(camera.index) {
                    if (!state.isFullscreen || camera.index == state.expandedIndex) {
                        val tileModifier = if (state.isFullscreen) {
                            Modifier.fillMaxSize()
                        } else {
                            val cell = grid.cell(slot)
                            Modifier
                                .offset(cell.x, cell.y)
                                .size(cell.width, cell.height)
                        }
                        CameraTile(camera, slot, state, requester(camera.index), tileModifier)
                    }
                }
            }

            if (state.isFullscreen) {
                FullscreenLayer(state)
            } else {
                Header(state, onOpenSettings, Modifier.align(Alignment.TopCenter))
                Footer(state, Modifier.align(Alignment.BottomCenter))
                if (updates != null && updates.bannerVisible) {
                    UpdateBanner(updates, Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp))
                }
            }
        }
    }
}

/** Keys handled for the whole screen, before the focused tile sees them. */
private fun onScreenKey(event: KeyEvent, state: ViewerState): Boolean {
    val down = event.type == KeyEventType.KeyDown
    if (down) state.userActivity()
    if (event.key in PLAY_KEYS) {
        if (down) state.toggleTour()
        return true
    }
    if (state.isFullscreen) {
        if (down) state.showOverlay()
        val zoomed = state.zoom > 1
        return when {
            event.key in SOUND_KEYS -> { if (down) state.toggleSound(); true }
            event.key == Key.DirectionLeft -> { if (down) { if (zoomed) state.pan(-1, 0) else state.stepByUser(-1) }; true }
            event.key == Key.DirectionRight -> { if (down) { if (zoomed) state.pan(1, 0) else state.stepByUser(+1) }; true }
            event.key == Key.DirectionUp -> { if (down) { if (zoomed) state.pan(0, -1) else state.zoomIn() }; true }
            event.key == Key.DirectionDown -> { if (down && zoomed) state.pan(0, 1); true }
            event.key.isSelect() -> { if (!down) { if (zoomed) state.zoomIn() else state.close() }; true }
            else -> false
        }
    }
    val digit = digitOf(event.key)
    return when {
        event.key in SOUND_KEYS -> { if (down) state.toggleSound(); true }
        digit != null -> { if (down) state.openSlot(digit - 1); true }
        event.key in NEXT_PAGE_KEYS -> { if (down) state.nextPage(); true }
        event.key in PREVIOUS_PAGE_KEYS -> { if (down) state.previousPage(); true }
        else -> false
    }
}

private val PLAY_KEYS = setOf(Key.MediaPlay, Key.MediaPlayPause)
/** Sound for the selected tile: 0, or the remote's mute / audio-track key where the TV passes it on. */
private val SOUND_KEYS = setOf(Key.Zero, Key.NumPad0, Key.VolumeMute, Key.MediaAudioTrack)
private val NEXT_PAGE_KEYS = setOf(Key.ChannelUp, Key.PageDown, Key.MediaNext)
private val PREVIOUS_PAGE_KEYS = setOf(Key.ChannelDown, Key.PageUp, Key.MediaPrevious)

private fun digitOf(key: Key): Int? = when (key) {
    Key.One, Key.NumPad1 -> 1
    Key.Two, Key.NumPad2 -> 2
    Key.Three, Key.NumPad3 -> 3
    Key.Four, Key.NumPad4 -> 4
    Key.Five, Key.NumPad5 -> 5
    Key.Six, Key.NumPad6 -> 6
    Key.Seven, Key.NumPad7 -> 7
    Key.Eight, Key.NumPad8 -> 8
    Key.Nine, Key.NumPad9 -> 9
    else -> null
}

/** One tile's place on screen. */
private class Cell(val x: Dp, val y: Dp, val width: Dp, val height: Dp)

/**
 * Tile positions: 16:9 tiles, centred in the space between header and footer. The 1+5 layout
 * is a 3×3 grid of small tiles where slot 0 covers the top-left 2×2.
 */
private class GridGeometry(width: Dp, height: Dp, private val layout: GridLayout) {
    private val columns = layout.columns
    private val gap = 8.dp
    private val areaTop = 76.dp
    private val areaBottom = height - 44.dp
    private val areaWidth = width - 64.dp
    private val areaHeight = areaBottom - areaTop

    private val tileHeight: Dp
    private val tileWidth: Dp
    private val left: Dp
    private val top: Dp

    init {
        val cellWidth = (areaWidth - gap * (columns - 1)) / columns
        val cellHeight = (areaHeight - gap * (columns - 1)) / columns
        tileHeight = minOf(cellHeight, cellWidth * 9f / 16f)
        tileWidth = tileHeight * 16f / 9f
        val gridWidth = tileWidth * columns + gap * (columns - 1)
        val gridHeight = tileHeight * columns + gap * (columns - 1)
        left = (width - gridWidth) / 2
        top = areaTop + (areaHeight - gridHeight) / 2
    }

    fun cell(slot: Int): Cell {
        if (layout == GridLayout.SIX) {
            // Small-tile grid positions (column, row) of the five small slots; slot 0 is the big one.
            val (col, row) = when (slot) {
                0 -> 0 to 0
                1 -> 2 to 0
                2 -> 2 to 1
                3 -> 0 to 2
                4 -> 1 to 2
                else -> 2 to 2
            }
            val span = if (slot == 0) 2 else 1
            return Cell(
                x = left + (tileWidth + gap) * col,
                y = top + (tileHeight + gap) * row,
                width = tileWidth * span + gap * (span - 1),
                height = tileHeight * span + gap * (span - 1),
            )
        }
        return Cell(left + (tileWidth + gap) * (slot % columns), top + (tileHeight + gap) * (slot / columns), tileWidth, tileHeight)
    }
}

@Composable
private fun Header(state: ViewerState, onOpenSettings: () -> Unit, modifier: Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 32.dp, end = 32.dp, top = 20.dp)
            .height(44.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Cameras", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
            val count = state.cameras.size
            Text(
                "$count camera${if (count == 1) "" else "s"}" +
                    if (state.pageCount > 1) " · Page ${state.page + 1} of ${state.pageCount}" else "",
                fontSize = 10.sp,
                color = Palette.Quiet,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .background(Palette.Surface, RoundedCornerShape(10.dp))
                .padding(3.dp),
        ) {
            GridLayout.entries.forEach { layout ->
                FocusChip(layout.label, selected = layout == state.layout) { state.changeLayout(layout) }
            }
        }
        if (state.groupCount > 0) {
            HSpace(12)
            FocusChip("▣  ${state.groupName ?: "All cameras"}", selected = state.groupName != null) { state.nextGroup() }
        }
        if (state.cameras.size > 1) {
            HSpace(12)
            FocusChip("▶  Tour", selected = false) { state.toggleTour() }
        }
        HSpace(16)
        Text(rememberClock(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text)
        HSpace(16)
        FocusChip("⚙  Settings", selected = false, onClick = onOpenSettings)
    }
}

@Composable
private fun Footer(state: ViewerState, modifier: Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 32.dp, end = 32.dp, bottom = 16.dp)
            .height(24.dp),
    ) {
        KeyHint("OK", "Full screen")
        KeyHint("◀ ▲ ▼ ▶", "Move")
        KeyHint("1–9", "Camera")
        KeyHint("0 / hold OK", "Sound")
        if (state.cameras.size > 1) KeyHint("▶", "Tour")
        if (state.pageCount > 1) {
            KeyHint("CH ±", "Page")
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                Text("Page ${state.page + 1} of ${state.pageCount}", fontSize = 10.sp, color = Palette.Quiet)
            }
        }
    }
}

/** "Version x.y.z is available": Update now / Later, with the download progress. */
@Composable
private fun UpdateBanner(updates: UpdateManager, modifier: Modifier) {
    val release = updates.available ?: return
    val status = updates.status
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .background(Palette.Surface, RoundedCornerShape(12.dp))
            .border(1.dp, Palette.Accent, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Column {
            Text("SwannTV ${release.version} is available", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Palette.TextBright)
            Text(
                when (status) {
                    is UpdateManager.Status.Downloading -> "Downloading… ${status.percent}%"
                    UpdateManager.Status.Installing -> "Follow the installer, then open SwannTV again."
                    is UpdateManager.Status.Failed -> status.message
                    else -> release.notes.lineSequence().map { it.trim().trimStart('-', '*', ' ') }
                        .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }?.take(90) ?: "Installed: ${BuildConfig.VERSION_NAME}"
                },
                fontSize = 10.sp,
                color = if (status is UpdateManager.Status.Failed) Palette.Error else Palette.Quiet,
            )
        }
        if (status !is UpdateManager.Status.Downloading && status !is UpdateManager.Status.Installing) {
            FocusChip("Update now", selected = true) { updates.install() }
            FocusChip("Later", selected = false) { updates.snooze() }
        }
    }
}

@Composable
private fun FullscreenLayer(state: ViewerState) {
    val camera = state.fullCamera ?: return
    var mainView by remember { mutableStateOf<TextureView?>(null) }

    // Start the 4K stream only after the enlarged tile has been drawn, so it shows at once.
    LaunchedEffect(mainView) {
        val view = mainView ?: return@LaunchedEffect
        withFrameNanos { } // the enlarged tile is on screen after this frame
        state.startMain(view)
    }
    DisposableEffect(Unit) {
        onDispose { state.manager.stopMain() }
    }

    Box(Modifier.fillMaxSize()) {
      // Zoom scales the picture layer (TextureView and still pictures; a SurfaceView can't be).
      Box(
          Modifier
              .fillMaxSize()
              .clipToBounds()
              .graphicsLayer {
                  val z = state.zoom.toFloat()
                  scaleX = z
                  scaleY = z
                  translationX = -state.panX * (z - 1f) / 2f * size.width
                  translationY = -state.panY * (z - 1f) / 2f * size.height
              }
      ) {
        // Once the grown tile stops: black, then this camera's last picture (the frozen frame
        // on open, the grid's latest after left/right) until the 4K stream is ready.
        if (!state.expandedPlaying) {
            Box(Modifier.fillMaxSize().background(Color.Black))
            if (!state.mainShowing) {
                state.manager.lastPictures[camera.index]?.let { picture ->
                    Image(picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
        }

        AndroidView(
            factory = { context -> TextureView(context).also { mainView = it } },
            // 1%, not 0: a fully transparent view may never be drawn, and then the decoder's
            // frames are never consumed. 1% over the enlarged tile is invisible.
            update = { it.alpha = if (state.mainShowing) 1f else 0.01f },
            modifier = Modifier.fillMaxSize(),
        )

      }

        // Always visible (not only with the overlay): zoom level and tour countdown.
        if (state.zoom > 1 || state.touring || state.soundMuted) {
            Text(
                listOfNotNull(
                    if (state.touring) "▶ Tour · next camera in ${state.tourCountdown} s" else null,
                    if (state.zoom > 1) "Zoom ${state.zoom}×" else null,
                    if (state.soundMuted) "♪ Muted" else null,
                ).joinToString("   "),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Palette.TextBright,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 20.dp)
                    .background(Palette.Scrim, RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }

        AnimatedVisibility(state.overlayVisible, enter = fadeIn(), exit = fadeOut()) {
            FullscreenOverlay(state, camera)
        }
    }
}

@Composable
private fun FullscreenOverlay(state: ViewerState, camera: Camera) {
    val cameras = state.cameras
    val main = state.manager.main
    val fullTile = if (state.tileIsFullQuality) state.manager.fullQualityTile(camera.index) else null
    val quality = when {
        fullTile != null -> if (fullTile.isLive) qualityLabel(fullTile.resolution) else "Loading full quality…"
        state.mainShowing && main != null -> qualityLabel(main.resolution)
        main == null -> "Starting full quality…"
        else -> when (main.status) {
            StreamPlayer.Status.CONNECTING -> "Loading full quality…"
            StreamPlayer.Status.LIVE -> "Full quality ready (${main.resolution})"
            StreamPlayer.Status.RECONNECTING, StreamPlayer.Status.OFFLINE ->
                "Full quality failed: ${main.lastError}, retry in ${main.retryDelaySec} s"
            StreamPlayer.Status.AUTH_FAILED -> "Full quality: sign-in failed"
        }
    }
    Box(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 20.dp)) {
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .background(Palette.Scrim, RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(camera.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.TextBright)
                LiveBadge()
            }
            Text("Camera ${camera.number} of ${cameras.size} · $quality", fontSize = 10.sp, color = Palette.Quiet)
        }

        Text(
            rememberClock(),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Palette.TextBright,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .background(Palette.Scrim, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )

        if (cameras.size > 1) {
            val previous = cameras[(camera.index - 1).mod(cameras.size)]
            val next = cameras[(camera.index + 1).mod(cameras.size)]
            NavPill("‹  ${previous.name}", Modifier.align(Alignment.CenterStart))
            NavPill("${next.name}  ›", Modifier.align(Alignment.CenterEnd))
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .background(Palette.Scrim, RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            when {
                state.zoom > 1 -> {
                    KeyHint("◀ ▲ ▼ ▶", "Move")
                    KeyHint("OK", if (state.zoom < 4) "Zoom more" else "Zoom out")
                    KeyHint("Back", "Zoom out")
                }
                state.touring -> {
                    KeyHint("◀ ▶", "Previous / next camera")
                    KeyHint("OK / ▶", "Stop tour")
                }
                else -> {
                    if (cameras.size > 1) KeyHint("◀ ▶", "Previous / next camera")
                    KeyHint("▲", "Zoom")
                    KeyHint("0", if (state.soundMuted) "Sound on" else "Mute")
                    KeyHint("OK", "Back to grid")
                }
            }
        }
    }
}

@Composable
private fun NavPill(text: String, modifier: Modifier) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = Palette.TextBright,
        modifier = modifier
            .background(Palette.Scrim, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

private fun qualityLabel(resolution: String): String {
    val width = resolution.substringBefore('x').toIntOrNull() ?: return "Full quality"
    return when {
        width >= 3840 -> "4K"
        width >= 2560 -> "2K"
        width >= 1920 -> "1080p"
        width >= 1280 -> "720p"
        else -> resolution
    }
}

@Composable
private fun NoCameras(onOpenSettings: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("No cameras to show", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
            Text("Add your recorder or cameras in Settings.", fontSize = 12.sp, color = Palette.Quiet)
            Box(Modifier.focusRequester(focus)) {
                FocusChip("Open Settings", selected = true, onClick = onOpenSettings)
            }
        }
    }
}
