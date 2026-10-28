package com.albahadly.lookouttv.playback

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.albahadly.lookouttv.AppLog
import com.albahadly.lookouttv.model.Camera

/**
 * Owns every player, so the app never runs more streams than the TV can decode:
 * at most 9 grid tiles (one page) plus the full-screen main stream.
 *
 * Lessons from the TCL 55C6L, built in here:
 *  - A 4K main stream fails to decode if other decoders are still open, so before it starts
 *    every other tile is released right away ([releaseTilesExcept]).
 *  - Releasing blocks the main thread until the decoder closes, so routine releases wait
 *    until after the next frame ([releaseLater]); the screen updates first.
 *  - Every player is registered in [alive]; once a second any player nobody uses is released
 *    ([sweep]), so a missed release can never pile up and starve the decoder.
 *  - The main stream reuses one player when switching camera.
 *  - Each camera's last picture is kept, so a connecting page never shows black tiles.
 */
class PlaybackManager(private val context: Context) {

    private companion object {
        const val PICTURE_WIDTH = 480
        const val PICTURE_HEIGHT = 270
        const val PICTURE_EVERY_MS = 15_000L
        const val FREEZE_WIDTH = 960
        const val FREEZE_HEIGHT = 540
        const val SUMMARY_EVERY_TICKS = 10
        // Motion: compare a tiny greyscale copy of each tile once a second.
        const val MOTION_WIDTH = 64
        const val MOTION_HEIGHT = 36
        /** A pixel counts as changed if its brightness moved by more than this (0-255). */
        const val MOTION_PIXEL_DELTA = 28
        /** More than this much of the picture changing at once is light, not movement. */
        const val MOTION_LIGHT_CHANGE = 0.5f
        const val MOTION_SHOW_MS = 10_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    /** Playing grid tiles by camera index. Observable: tiles redraw when their player changes. */
    val tiles = mutableStateMapOf<Int, StreamPlayer>()
    /** The camera each attached tile shows (for motion settings and restarts). */
    private val tileCameras = HashMap<Int, Camera>()
    /**
     * Tiles just detached, kept running for a moment. Compose sometimes recreates a tile's view
     * (which detaches and re-attaches it); a parked stream is handed to the new view instead
     * of opening a second RTSP session. Released after two frames if nobody takes it back.
     */
    private val parked = HashMap<Int, StreamPlayer>()
    /** Which camera each parked player shows: only that camera may take it back. */
    private val parkedCameras = HashMap<Int, Camera>()
    /** Until this time, detached tiles are stopped at once instead of parked (page change). */
    private var stopDetachedUntil = 0L
    private val pictureTakenAt = HashMap<Int, Long>()

    /** Tiles playing the full-quality main stream. */
    private val fullQuality = HashSet<StreamPlayer>()
    /**
     * Tiles waiting for a full-quality (4K) tile to get going first: on this TV the 4K decoder
     * must start alone, so the others start once it shows a frame (or fails, or 5 s pass).
     */
    private val waiting = LinkedHashMap<Int, Pair<Camera, SurfaceView>>()
    private var fullStarting: StreamPlayer? = null
    private val fullStartTimeout = Runnable { startWaiting("after 5 s") }
    /** Cameras (by id) whose 4K stream failed in the grid this session: back to the grid stream for them. */
    private val downgraded = HashSet<String>()

    /** The grid tile whose sound is on (camera index), or null. Observable. */
    var listening by mutableStateOf<Int?>(null)
        private set

    /** Every player created and not yet released. */
    private val alive = LinkedHashSet<StreamPlayer>()
    /** Players handed to [releaseLater] (not orphans, just waiting a frame), with when. */
    private val pendingRelease = HashMap<StreamPlayer, Long>()

    /** Last picture of each camera (by camera index), shown while a tile is not live. */
    val lastPictures = mutableStateMapOf<Int, ImageBitmap>()

    /** Cameras where movement was seen recently (camera index -> when), shown on their tiles. */
    val motion = mutableStateMapOf<Int, Long>()
    /** Main thread: movement just started on this camera's tile. */
    var onMotion: ((Int) -> Unit)? = null
    private val motionLast = HashMap<StreamPlayer, IntArray>()
    private val motionHits = HashMap<StreamPlayer, Int>()
    private var toneGenerator: ToneGenerator? = null

    var main: StreamPlayer? = null
        private set

    private var running = false
    private var ticks = 0
    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            tick()
            handler.postDelayed(this, 1_000)
        }
    }

    // ------------------------------------------------------------------ grid tiles

    /**
     * Plays [camera] on a grid tile: its grid stream, or with [full] its full-quality main
     * stream. A full-quality stream always starts alone: every other tile is released first
     * and restarted once the 4K stream is up (the TCL's 4K decoder fails, and can jam, if
     * another decoder is open when it starts). One full-quality tile per grid; a second
     * request for one plays the grid stream instead.
     */
    fun attachTile(camera: Camera, view: SurfaceView, full: Boolean = false) {
        tileCameras[camera.index] = camera
        val wantFull = full && camera.id !in downgraded && (fullQuality.isEmpty() || tiles[camera.index] in fullQuality)
        tiles[camera.index]?.let { existing ->
            if ((existing in fullQuality) == wantFull) {
                if (existing.view !== view) existing.retarget(view)
                return
            }
            tiles.remove(camera.index)
            releaseNow(existing) // wrong quality for this layout
        }
        parked.remove(camera.index)?.let { player ->
            pendingRelease -= player
            val sameCamera = parkedCameras.remove(camera.index) == camera // same URLs: a group change may put another camera here
            if (!player.isReleased && sameCamera && (player in fullQuality) == wantFull) {
                player.retarget(view)
                tiles[camera.index] = player
                AppLog.i("${player.debugName} kept running on its new view")
                return
            }
            releaseNow(player)
        }
        if (wantFull) {
            startFullTile(camera, view)
            return
        }
        if (fullStarting != null) {
            waiting[camera.index] = camera to view // the 4K tile goes first
            handler.removeCallbacks(fullStartTimeout)
            handler.postDelayed(fullStartTimeout, 5_000) // never wait for a 4K stream that sends nothing
            return
        }
        startSubTile(camera, view)
    }

    private fun startSubTile(camera: Camera, view: SurfaceView) {
        newPlayer(camera.subUrl, "${camera.name} sub", camera.useTcp, withAudio = listening == camera.index).also {
            it.start(view)
            tiles[camera.index] = it
        }
    }

    private fun startFullTile(camera: Camera, view: SurfaceView) {
        // Everything else waits: remembered with its view, released, restarted after the 4K start.
        tiles.filterKeys { it != camera.index }.forEach { (index, player) ->
            val otherView = player.view as? SurfaceView
            val otherCamera = tileCameras[index]
            if (otherView != null && otherCamera != null) waiting[index] = otherCamera to otherView
        }
        releaseTilesExcept(null)
        handler.removeCallbacks(fullStartTimeout)
        val player = newPlayer(camera.mainUrl, "${camera.name} full", camera.useTcp, withAudio = listening == camera.index)
        fullQuality += player
        fullStarting = player
        player.onFirstFrame = { if (fullStarting === player) startWaiting("4K tile is up") }
        player.onError = {
            if (fullStarting === player || tiles[camera.index] === player) {
                val error = player.lastError
                if (error.startsWith("DECOD") && tiles.size + waiting.size > 1) {
                    // The 4K decoder can't run next to the other tiles on this TV: this camera
                    // goes back to its grid stream for the rest of the session.
                    AppLog.w("${camera.name}: 4K in the grid failed ($error); using its grid stream instead")
                    downgraded += camera.id
                    val v = player.view as? SurfaceView
                    tiles.remove(camera.index)
                    releaseNow(player)
                    if (fullStarting === player) fullStarting = null
                    if (v != null) startSubTile(camera, v)
                    startWaiting("4K tile failed")
                } else if (fullStarting === player) {
                    startWaiting("4K tile failed")
                }
            }
        }
        player.start(view)
        tiles[camera.index] = player
        if (waiting.isNotEmpty()) handler.postDelayed(fullStartTimeout, 5_000)
    }

    /** Starts the tiles that waited for the full-quality tile. */
    private fun startWaiting(why: String) {
        handler.removeCallbacks(fullStartTimeout)
        fullStarting = null
        if (waiting.isEmpty()) return
        AppLog.i("starting ${waiting.size} waiting tile(s): $why")
        val toStart = waiting.values.toList()
        waiting.clear()
        toStart.forEach { (camera, view) -> if (tiles[camera.index] == null) startSubTile(camera, view) }
    }

    /** Sound on for one grid tile (its camera index), or off for all with null. */
    fun listenTo(cameraIndex: Int?) {
        if (listening == cameraIndex) return
        listening?.let { tiles[it]?.setAudioEnabled(false) }
        listening = cameraIndex
        cameraIndex?.let { tiles[it]?.setAudioEnabled(true) }
    }

    /** The tile's player if it is playing the full-quality stream, else null. */
    fun fullQualityTile(cameraIndex: Int): StreamPlayer? = tiles[cameraIndex]?.takeIf { it in fullQuality }

    /**
     * A page or layout change is coming: the tiles about to be detached won't come back, so
     * stop them at once (without blocking) instead of parking them, so the old and new pages'
     * decoders never run together (the TV allows 16; two pages of 9 would exceed it).
     */
    fun stopDetachedTilesSoon() {
        stopDetachedUntil = SystemClock.elapsedRealtime() + 1_000
    }

    fun detachTile(cameraIndex: Int) {
        waiting.remove(cameraIndex)
        val player = tiles.remove(cameraIndex)
        if (player == null) {
            tileCameras.remove(cameraIndex)
            return
        }
        if (fullStarting === player) startWaiting("4K tile detached")
        if (SystemClock.elapsedRealtime() < stopDetachedUntil) {
            // Page or layout change. A full-quality stream is released at once, so its 4K
            // decoder is closed before the next streams start; others after the frame.
            if (player in fullQuality) releaseNow(player) else releaseLater(player)
            return
        }
        parked.remove(cameraIndex)?.let { releaseNow(it) }
        parked[cameraIndex] = player
        tileCameras[cameraIndex]?.let { parkedCameras[cameraIndex] = it }
        pendingRelease[player] = SystemClock.elapsedRealtime()
        val releaseIfStillParked = {
            if (parked[cameraIndex] === player) releaseNow(player)
        }
        val choreographer = Choreographer.getInstance()
        choreographer.postFrameCallback {
            choreographer.postFrameCallback { handler.post { releaseIfStillParked() } }
        }
        handler.postDelayed({ releaseIfStillParked() }, 400)
        tileCameras.remove(cameraIndex)
    }

    /** Releases every tile except [keepIndex] now, plus anything waiting, so decoders are free. */
    fun releaseTilesExcept(keepIndex: Int?) {
        val others = tiles.filterKeys { it != keepIndex }
        others.forEach { (index, player) ->
            tiles.remove(index)
            releaseNow(player)
        }
        pendingRelease.keys.toList().forEach { releaseNow(it) }
        // Nothing may start later behind the 4K stream either.
        handler.removeCallbacks(fullStartTimeout)
        waiting.keys.retainAll { it == keepIndex }
        if (fullStarting != null && fullStarting !== tiles[keepIndex]) fullStarting = null
    }

    /**
     * Copies the tile's current frame into [lastPictures] (at a size fit for full screen), then
     * calls [onDone] once, even if the copy fails or never answers. Used before releasing the
     * tile so the screen keeps a still picture while the 4K stream starts on its own.
     */
    fun freezeTile(cameraIndex: Int, onDone: () -> Unit) {
        var done = false
        val finish = {
            if (!done) {
                done = true
                onDone()
            }
        }
        val player = tiles[cameraIndex]
        val view = player?.view as? SurfaceView
        if (player == null || view == null || !player.isLive || !view.holder.surface.isValid) {
            finish()
            return
        }
        val bitmap = Bitmap.createBitmap(FREEZE_WIDTH, FREEZE_HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(view, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) {
                    lastPictures[cameraIndex] = bitmap.asImageBitmap()
                    pictureTakenAt[cameraIndex] = SystemClock.elapsedRealtime()
                }
                finish()
            }, handler)
            handler.postDelayed({ finish() }, 300) // never hold up the 4K stream on a copy
        } catch (e: IllegalArgumentException) {
            finish()
        }
    }

    /** Releases the tile for [cameraIndex] now (used when the main stream needs its decoder). */
    fun releaseTileNow(cameraIndex: Int) {
        waiting.remove(cameraIndex)
        tiles.remove(cameraIndex)?.let { releaseNow(it) }
    }

    // ------------------------------------------------------------------ full screen

    /** Starts the main stream for [camera], or switches the existing main player to it. */
    fun showMain(camera: Camera, view: TextureView): StreamPlayer {
        val existing = main
        if (existing != null) {
            existing.switchTo(camera.mainUrl, camera.useTcp)
            return existing
        }
        return newPlayer(camera.mainUrl, "main", camera.useTcp, withAudio = true).also {
            it.start(view)
            main = it
        }
    }

    fun stopMain() {
        main?.let { releaseLater(it) }
        main = null
    }

    /** Forgets every last picture (the camera list changed, so positions mean other cameras). */
    fun clearPictures() {
        lastPictures.clear()
        pictureTakenAt.clear()
    }

    /** Keeps the full-screen picture as this camera's last picture (called when leaving full screen). */
    fun captureMainPicture(cameraIndex: Int) {
        val player = main ?: return
        val view = player.view as? TextureView ?: return
        if (!player.isLive) return
        view.getBitmap(PICTURE_WIDTH, PICTURE_HEIGHT)?.let {
            lastPictures[cameraIndex] = it.asImageBitmap()
            pictureTakenAt[cameraIndex] = SystemClock.elapsedRealtime()
        }
    }

    // ------------------------------------------------------------------ lifecycle

    fun resume() {
        if (running) return
        running = true
        handler.post(ticker)
    }

    fun pause() {
        running = false
        handler.removeCallbacks(ticker)
    }

    /**
     * Stops every stream right away. Used when the app leaves the screen: Compose stops
     * recomposing then, so tiles can't be relied on to detach themselves.
     */
    fun stopAll() {
        pause()
        handler.removeCallbacks(fullStartTimeout)
        tiles.clear()
        tileCameras.clear()
        waiting.clear()
        fullStarting = null
        parked.clear()
        parkedCameras.clear()
        fullQuality.clear()
        main = null
        alive.toList().forEach { releaseNow(it) }
        AppLog.i("all streams stopped")
    }

    fun releaseAll() = stopAll()

    // ------------------------------------------------------------------ internals

    private fun newPlayer(url: String, name: String, useTcp: Boolean, withAudio: Boolean = false): StreamPlayer =
        StreamPlayer(context, url, name, useTcp, withAudio).also { alive += it }

    private fun releaseNow(player: StreamPlayer) {
        pendingRelease -= player
        parked.entries.firstOrNull { it.value === player }?.key?.let { parked.remove(it); parkedCameras.remove(it) }
        fullQuality -= player
        motionLast -= player
        motionHits -= player
        alive -= player
        player.release()
    }

    /**
     * Releases after the next frame is drawn, so the screen change shows first. The timer is a
     * safety net for when no frame comes (app in the background); release() is idempotent.
     */
    private fun releaseLater(player: StreamPlayer) {
        player.detachSurface()
        pendingRelease[player] = SystemClock.elapsedRealtime()
        Choreographer.getInstance().postFrameCallback { handler.post { releaseNow(player) } }
        handler.postDelayed({ releaseNow(player) }, 500)
    }

    /** Releases any player that nothing uses any more (a missed release must never pile up). */
    private fun sweep() {
        val inUse = HashSet<StreamPlayer>(tiles.values).apply {
            main?.let { add(it) }
        }
        val orphans = alive.filter { it !in inUse && it !in pendingRelease }
        if (orphans.isNotEmpty()) {
            AppLog.w("sweep released ${orphans.size} unused stream(s): ${orphans.joinToString { it.debugName }}")
            orphans.forEach { releaseNow(it) }
        }
        // Anything still pending after a whole second is overdue: release it now.
        val now = SystemClock.elapsedRealtime()
        pendingRelease.filterValues { now - it >= 1_000 }.keys.forEach {
            AppLog.w("overdue release: ${it.debugName}")
            releaseNow(it)
        }
    }

    private fun tick() {
        ticks++
        sweep()
        val now = SystemClock.elapsedRealtime()
        listening?.let { index -> if (tiles[index]?.hasAudio == false) listening = null } // sound couldn't be played
        for ((index, player) in tiles) {
            player.tick()
            val takenAt = pictureTakenAt[index]
            if (player.isLive && (takenAt == null || now - takenAt >= PICTURE_EVERY_MS)) {
                captureTile(index, player)
            }
            val threshold = tileCameras[index]?.motionThreshold
            if (threshold != null) {
                if (player.isLive) checkMotion(index, player, threshold) else motionLast -= player
            }
        }
        motion.entries.removeAll { now - it.value > MOTION_SHOW_MS }
        main?.tick()
        if (ticks % SUMMARY_EVERY_TICKS == 0) {
            AppLog.i("streams alive: ${alive.size} (tiles ${tiles.size}, main ${if (main != null) 1 else 0})")
        }
    }

    /**
     * Compares the tile's picture with the one from a second ago. Movement must show in two
     * checks running, and a change over most of the picture (lights, night mode) is ignored.
     */
    private fun checkMotion(index: Int, player: StreamPlayer, threshold: Float) {
        val view = player.view as? SurfaceView ?: return
        if (!view.holder.surface.isValid) return
        val bitmap = Bitmap.createBitmap(MOTION_WIDTH, MOTION_HEIGHT, Bitmap.Config.ARGB_8888)
        val done = { result: Int ->
            if (result == PixelCopy.SUCCESS && tiles[index] === player) compareMotion(index, player, bitmap, threshold)
            bitmap.recycle()
        }
        try {
            PixelCopy.request(view, bitmap, { done(it) }, handler)
        } catch (e: IllegalArgumentException) {
            bitmap.recycle()
        }
    }

    private fun compareMotion(index: Int, player: StreamPlayer, bitmap: Bitmap, threshold: Float) {
        val pixels = IntArray(MOTION_WIDTH * MOTION_HEIGHT)
        bitmap.getPixels(pixels, 0, MOTION_WIDTH, 0, 0, MOTION_WIDTH, MOTION_HEIGHT)
        val luma = IntArray(pixels.size) { i ->
            val p = pixels[i]
            ((p shr 16 and 0xFF) * 77 + (p shr 8 and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
        }
        val previous = motionLast.put(player, luma) ?: return
        var changed = 0
        for (i in luma.indices) if (kotlin.math.abs(luma[i] - previous[i]) > MOTION_PIXEL_DELTA) changed++
        val fraction = changed.toFloat() / luma.size
        val moving = fraction >= threshold && fraction < MOTION_LIGHT_CHANGE
        val hits = if (moving) (motionHits[player] ?: 0) + 1 else 0
        motionHits[player] = hits
        if (hits < 2) return
        val now = SystemClock.elapsedRealtime()
        val since = motion[index]
        // Refresh only when new or half-way to expiring: every write recomposes the tiles.
        if (since == null || now - since > MOTION_SHOW_MS / 2) motion[index] = now
        if (since == null) {
            AppLog.i("motion on ${player.debugName} (${(fraction * 100).toInt()}% of the picture)")
            if (tileCameras[index]?.motionBeep == true) beep()
            onMotion?.invoke(index)
        }
    }

    private fun beep() {
        try {
            val tones = toneGenerator ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80).also { toneGenerator = it }
            tones.startTone(ToneGenerator.TONE_PROP_BEEP2, 200)
        } catch (e: RuntimeException) {
            AppLog.w("motion beep failed: ${e.message}")
        }
    }

    private fun captureTile(index: Int, player: StreamPlayer) {
        val view = player.view as? SurfaceView ?: return
        if (!view.holder.surface.isValid) return
        pictureTakenAt[index] = SystemClock.elapsedRealtime()
        val bitmap = Bitmap.createBitmap(PICTURE_WIDTH, PICTURE_HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(view, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) lastPictures[index] = bitmap.asImageBitmap()
            }, handler)
        } catch (e: IllegalArgumentException) {
            // Surface went away between the check and the copy; try again next time.
        }
    }
}
