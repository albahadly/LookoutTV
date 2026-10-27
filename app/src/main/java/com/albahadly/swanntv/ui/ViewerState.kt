package com.albahadly.swanntv.ui

import android.view.TextureView
import android.os.SystemClock
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.albahadly.swanntv.AppLog
import com.albahadly.swanntv.model.Camera
import com.albahadly.swanntv.playback.PlaybackManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Grid layouts. 3x3 is the maximum: the TCL 55C6L can't play 16 live streams smoothly. */
enum class GridLayout(val cells: Int, val columns: Int, val label: String) {
    ONE(1, 1, "1"),
    FOUR(4, 2, "2×2"),
    NINE(9, 3, "3×3"),
}

/**
 * Everything the viewer screen shows, and what the remote does to it.
 *
 * Full screen in two steps (measured on the TCL):
 *  1. OK grows the tile itself to full screen; its sub-stream never stops (picture < 110 ms).
 *  2. After that frame is drawn the 4K main stream starts underneath and fades in on its first
 *     frame (~400 ms); only then is the tile's stream released.
 * Left/right switches camera on the same main player; a fallback sub-stream shows meanwhile.
 */
@Stable
class ViewerState(
    val cameras: List<Camera>,
    val manager: PlaybackManager,
    private val scope: CoroutineScope,
    /** The saved layout; null = choose from the camera count. */
    initialLayout: GridLayout? = null,
    /** Called when the user picks a layout in the grid, so it can be saved. */
    private val onLayoutChanged: (GridLayout) -> Unit = {},
) {
    var layout by mutableStateOf(initialLayout ?: defaultLayout(cameras.size))
        private set
    var page by mutableIntStateOf(0)
        private set

    val pageCount: Int get() = maxOf(1, (cameras.size + layout.cells - 1) / layout.cells)
    val visibleCameras: List<Camera> get() = cameras.drop(page * layout.cells).take(layout.cells)

    /** Camera shown full screen, or null in the grid. */
    var fullIndex by mutableStateOf<Int?>(null)
        private set
    /** The grid tile that was grown to full screen (it stays in place behind the main stream). */
    var expandedIndex by mutableStateOf<Int?>(null)
        private set
    /** Whether the expanded tile is still playing (until the main stream's first frame). */
    var expandedPlaying by mutableStateOf(false)
        private set
    var mainShowing by mutableStateOf(false)
        private set
    /**
     * Opened from the 1-camera layout, whose tile already plays the full-quality stream: the
     * tile simply stays on screen (no second stream, no quality change) until left/right.
     */
    var tileIsFullQuality by mutableStateOf(false)
        private set
    var fallbackShowing by mutableStateOf(false)
        private set
    var overlayVisible by mutableStateOf(false)
        private set
    var paused by mutableStateOf(false)
        private set
    /** Bumped on every resume so tiles re-attach their streams (they were stopped directly). */
    var session by mutableIntStateOf(0)
        private set

    /** Camera index the grid should move focus to next (consumed by the screen). */
    var focusTarget by mutableStateOf<Int?>(cameras.firstOrNull()?.index)

    private var overlayJob: Job? = null
    /** The full-screen 4K surface, once the screen has created it. */
    private var mainView: TextureView? = null
    /** True while the tile's frame is being frozen, before the 4K stream starts. */
    private var mainStartPending = false
    /** Bumped on every main start and close, so a late freeze callback can tell it is stale. */
    private var mainStartToken = 0
    /** When OK was pressed, for the open timings in the log. */
    private var openedAt = 0L

    val isFullscreen: Boolean get() = fullIndex != null
    val fullCamera: Camera? get() = fullIndex?.let { cameras.getOrNull(it) }

    fun isTileActive(cameraIndex: Int): Boolean = when {
        paused -> false
        !isFullscreen -> true
        else -> cameraIndex == expandedIndex && expandedPlaying
    }

    // ------------------------------------------------------------------ grid

    fun changeLayout(newLayout: GridLayout) {
        if (newLayout == layout) return
        val keep = focusTarget ?: visibleCameras.firstOrNull()?.index ?: 0
        manager.stopDetachedTilesSoon()
        layout = newLayout
        onLayoutChanged(newLayout)
        page = (keep / newLayout.cells).coerceIn(0, pageCount - 1)
        focusTarget = keep
    }

    fun nextPage(focusSlot: Int = 0) = goToPage(page + 1, focusSlot)

    fun previousPage(focusSlot: Int = 0) = goToPage(page - 1, focusSlot)

    private fun goToPage(newPage: Int, focusSlot: Int) {
        if (newPage !in 0 until pageCount || newPage == page) return
        manager.stopDetachedTilesSoon()
        page = newPage
        val cams = visibleCameras
        focusTarget = cams.getOrNull(focusSlot.coerceAtMost(cams.lastIndex))?.index
    }

    /** Number keys 1–9: open that tile of the current page. */
    fun openSlot(slot: Int) {
        visibleCameras.getOrNull(slot)?.let { open(it) }
    }

    // ------------------------------------------------------------------ full screen

    fun open(camera: Camera) {
        if (isFullscreen || paused) return
        AppLog.i("open ${camera.name} full screen")
        openedAt = SystemClock.elapsedRealtime()
        // Don't stop the other tiles yet: their views are removed in this frame, and ExoPlayer
        // makes the screen wait for a stopping player to let go of its view (630-940 ms on the
        // TCL). Running players let go at once; they are released right after the frame.
        fullIndex = camera.index
        expandedIndex = camera.index
        expandedPlaying = true
        tileIsFullQuality = false
        mainShowing = false
        fallbackShowing = false
        showOverlay()
    }

    /** Called by the screen once the enlarged tile has been drawn. */
    fun startMain(view: TextureView) {
        val camera = fullCamera ?: return
        AppLog.i("enlarged ${camera.name} on screen after ${SystemClock.elapsedRealtime() - openedAt} ms")
        // On the TCL the 4K HEVC decoder fails if any other decoder is running (and repeated
        // failures jammed it until a power cycle). So the 4K stream always starts alone:
        // free the other tiles, freeze the enlarged tile's current frame, release it, then start.
        mainView = view
        val tileIndex = expandedIndex
        if (tileIndex != null && manager.fullQualityTile(tileIndex) != null) {
            tileIsFullQuality = true
            AppLog.i("${camera.name} already in full quality: keeping its stream")
            return
        }
        manager.releaseTilesExcept(expandedIndex)
        mainStartPending = true
        val token = ++mainStartToken
        val begin = begin@{
            if (token != mainStartToken || !isFullscreen) return@begin // closed meanwhile
            mainStartPending = false
            tileIndex?.let { manager.releaseTileNow(it) }
            expandedPlaying = false
            AppLog.i("decoders free, starting 4K ${SystemClock.elapsedRealtime() - openedAt} ms after OK")
            // Left/right may have moved on during the freeze: start whichever camera is current.
            val current = fullCamera
            if (current != null && !paused) launchMain(current, view)
        }
        if (tileIndex != null) manager.freezeTile(tileIndex) { begin() } else begin()
    }

    private fun launchMain(camera: Camera, view: TextureView) {
        manager.showMain(camera, view).also {
            it.onFirstFrame = ::onMainReady
            it.onError = ::onMainFailed
        }
    }

    fun step(delta: Int) {
        val current = fullIndex ?: return
        if (cameras.size < 2) return
        val next = (current + delta).mod(cameras.size)
        AppLog.i("step to ${cameras[next].name}")
        fullIndex = next
        // The previous camera's picture must not linger: drop the tile and show the new camera's
        // last picture until its 4K stream is ready. No sub-stream alongside: the 4K decoder
        // must run alone on this TV.
        expandedIndex?.let { manager.releaseTileNow(it) }
        expandedPlaying = false
        tileIsFullQuality = false
        mainShowing = false
        fallbackShowing = false
        val main = manager.main
        val view = mainView
        when {
            main != null -> main.switchTo(cameras[next].mainUrl, cameras[next].useTcp)
            !mainStartPending && view != null -> launchMain(cameras[next], view)
            // else: the pending start picks up the new camera.
        }
        showOverlay()
    }

    fun close() {
        val index = fullIndex ?: return
        AppLog.i("close full screen (main was ${if (mainShowing) "showing" else "not showing"})")
        manager.captureMainPicture(index)
        // Stop the full-screen streams directly rather than waiting for the screen to recompose.
        manager.stopMain()
        manager.stopFallback()
        mainView = null
        mainStartPending = false
        mainStartToken++
        fullIndex = null
        expandedIndex = null
        expandedPlaying = false
        tileIsFullQuality = false
        mainShowing = false
        fallbackShowing = false
        overlayJob?.cancel()
        overlayVisible = false
        // After left/right the camera may be on another page: go there, then focus it.
        page = (index / layout.cells).coerceIn(0, pageCount - 1)
        focusTarget = index
    }

    private fun onMainReady() {
        if (!isFullscreen) return
        AppLog.i("main stream showing" +
            if (openedAt > 0) " (${SystemClock.elapsedRealtime() - openedAt} ms after OK)" else "")
        openedAt = 0
        mainShowing = true
        expandedPlaying = false // frees the tile's decoder (released after the frame)
        fallbackShowing = false
    }

    /**
     * The main stream failed (it retries by itself).
     *  - Decoder error: the TV ran out of decoder capacity, so free every other decoder (the
     *    enlarged tile and any fallback) and let the main stream retry alone; the tile's last
     *    picture stays on screen meanwhile.
     *  - Other errors (network): keep a moving picture with the fallback sub-stream (the 4K
     *    decoder isn't running then, so the two don't compete).
     */
    private fun onMainFailed() {
        val error = manager.main?.lastError.orEmpty()
        AppLog.w("main stream failed: $error (tile still playing: $expandedPlaying)")
        if (!isFullscreen) return
        mainShowing = false
        if (error.startsWith("DECOD")) {
            expandedIndex?.let { manager.releaseTileNow(it) }
            expandedPlaying = false
            fallbackShowing = false
        } else if (!expandedPlaying) {
            fallbackShowing = true
        }
    }

    fun showOverlay() {
        overlayVisible = true
        overlayJob?.cancel()
        overlayJob = scope.launch {
            delay(4_000)
            overlayVisible = false
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /** App left the screen: stop every stream now, not on the next (paused) recomposition. */
    fun pause() {
        close()
        paused = true
        manager.stopAll()
    }

    fun resume() {
        // Only after a real pause: bumping on first start restarted every tile once for nothing.
        if (paused) session++
        paused = false
        manager.resume()
    }

    private companion object {
        fun defaultLayout(count: Int) = when {
            count <= 1 -> GridLayout.ONE
            count <= 4 -> GridLayout.FOUR
            else -> GridLayout.NINE
        }
    }
}
