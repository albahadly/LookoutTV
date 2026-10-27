package com.albahadly.swanntv.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.albahadly.swanntv.AppLog
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import kotlin.math.min

/**
 * One RTSP stream on its own ExoPlayer. Proven on the TCL 55C6L with the probe:
 *  - RTSP over TCP, small buffers for low latency.
 *  - Sound only with [withAudio] (full screen); grid tiles never open an audio decoder.
 *    If the camera's audio can't be decoded, the stream carries on without it.
 *  - Network errors: re-prepare with backoff 1 s, 2 s, 4 s ... 30 s, forever.
 *  - Decoder errors: rebuild the whole player (re-preparing a failed hardware decoder fails again).
 *  - Frozen picture (live but no frames for 5 s): restart.
 *  - [switchTo] plays another camera on the same player, keeping its decoder.
 * All methods must be called on the main thread.
 */
@OptIn(UnstableApi::class)
class StreamPlayer(
    private val context: Context,
    url: String,
    val debugName: String,
    useTcp: Boolean = true,
    withAudio: Boolean = false,
) {
    enum class Status { CONNECTING, LIVE, RECONNECTING, OFFLINE, AUTH_FAILED }

    private companion object {
        const val MAX_BACKOFF_MS = 30_000L
        const val OFFLINE_AFTER_FAILURES = 3
        const val FROZEN_AFTER_TICKS = 5
        val AUTH_WORDS = Regex("\\b401\\b|[Uu]nauthorized")
    }

    private val handler = Handler(Looper.getMainLooper())
    private var url = url
    private var useTcp = useTcp
    private var audio = withAudio
    private var player: ExoPlayer? = null
    private var released = false

    /** The surface the stream is drawn on (SurfaceView in the grid, TextureView full screen). */
    var view: View? = null
        private set

    /** Observed by the UI. */
    var status by mutableStateOf(Status.CONNECTING)
        private set

    /** Seconds until the next reconnect attempt while RECONNECTING / OFFLINE. */
    var retryDelaySec by mutableIntStateOf(0)
        private set

    /** Decoder in use, e.g. "c2.mtk.hevc.decoder" (for the log and diagnostics). */
    var decoderName: String = ""
        private set

    /** Last error, short, for the full-screen status line. */
    var lastError by mutableStateOf("")
        private set

    /** Video size once known, e.g. "3840x2160". */
    var resolution by mutableStateOf("")
        private set

    /** Main thread: the first frame after start, a camera switch or a recovery is on screen. */
    var onFirstFrame: (() -> Unit)? = null

    /** Main thread: playback failed (a retry is already scheduled). */
    var onError: (() -> Unit)? = null

    /** A failed player being released after a rebuild (released off the critical path). */
    private var retiring: ExoPlayer? = null
    /** The pending reconnect, so a camera switch can cancel just that. */
    private var retryTask: Runnable? = null

    /** Set by a decoder error: the next (re)start must build a new player, never re-prepare. */
    private var rebuildOnNext = false
    private var awaitingFrame = true
    private var consecutiveFailures = 0
    private var lastRendered = 0
    private var frozenTicks = 0
    private var startedAt = 0L

    val isLive: Boolean get() = status == Status.LIVE
    val isReleased: Boolean get() = released

    fun start(target: View) {
        startedAt = SystemClock.elapsedRealtime()
        AppLog.i("$debugName start on ${target.javaClass.simpleName}: ${AppLog.redact(url)}")
        view = target
        status = Status.CONNECTING
        awaitingFrame = true
        player = buildPlayer().also { p ->
            attach(p, target)
            p.setMediaSource(mediaSource(url))
            p.prepare()
            p.playWhenReady = true
        }
    }

    /** Plays a different stream on the same player and decoder. */
    fun switchTo(newUrl: String, newUseTcp: Boolean = useTcp) {
        url = newUrl // a rebuild in progress picks this up
        useTcp = newUseTcp
        cancelRetry()
        val p = player ?: return
        startedAt = SystemClock.elapsedRealtime()
        AppLog.i("$debugName switch to ${AppLog.redact(newUrl)}")
        status = Status.CONNECTING
        resolution = ""
        awaitingFrame = true
        consecutiveFailures = 0
        lastRendered = 0
        frozenTicks = 0
        if (rebuildOnNext) {
            // The decoder failed last time: a fresh player, not the same one re-prepared.
            rebuild()
            return
        }
        p.setMediaSource(mediaSource(newUrl))
        p.prepare()
        p.playWhenReady = true
    }

    /** Moves the running stream to another view (same decoder, no new RTSP session). */
    fun retarget(target: View) {
        view = target
        player?.let { attach(it, target) }
    }

    /**
     * Stops playback without blocking, ahead of a deferred release. Never clearVideoSurface()
     * here: ExoPlayer blocks the main thread up to 2 s per player waiting for the surface to
     * detach, which froze the app for seconds on the TCL.
     */
    fun detachSurface() {
        player?.stop()
        view = null
    }

    private fun mediaSource(streamUrl: String) =
        RtspMediaSource.Factory()
            .setForceUseRtpTcp(useTcp)
            .setTimeoutMs(8_000)
            .setSocketFactory(RtspSockets.factory)
            .createMediaSource(MediaItem.fromUri(streamUrl))

    private fun attach(p: ExoPlayer, target: View) {
        when (target) {
            is SurfaceView -> p.setVideoSurfaceView(target)
            is TextureView -> p.setVideoTextureView(target)
            else -> throw IllegalArgumentException("Unsupported video view: $target")
        }
    }

    private fun buildPlayer(): ExoPlayer {
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(500, 2_000, 100, 300)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        return ExoPlayer.Builder(context, renderers)
            .setLoadControl(loadControl)
            // release() normally takes ~50 ms; if a decoder is stuck, wait for it rather than
            // return early and start the next (4K) decoder while this one is still open.
            .setReleaseTimeoutMs(2_000)
            .build()
            .apply {
                if (audio) {
                    // No audio focus handling: a camera must never pause because another sound
                    // played (ExoPlayer would flip playWhenReady off and never back on).
                    setAudioAttributes(
                        AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                        /* handleAudioFocus = */ false,
                    )
                } else {
                    // No audio decoder at all: cheaper, and video timing follows the clock.
                    trackSelectionParameters = trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                        .build()
                    volume = 0f
                }
                addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() = frameShown()
                    override fun onPlayerError(error: PlaybackException) = failed(error)
                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        if (videoSize.width > 0) resolution = "${videoSize.width}x${videoSize.height}"
                    }
                })
                addAnalyticsListener(object : AnalyticsListener {
                    override fun onVideoDecoderInitialized(
                        eventTime: AnalyticsListener.EventTime,
                        decoderName: String,
                        initializedTimestampMs: Long,
                        initializationDurationMs: Long,
                    ) {
                        this@StreamPlayer.decoderName = decoderName
                    }
                })
            }
    }

    private fun frameShown() {
        status = Status.LIVE
        retryDelaySec = 0
        consecutiveFailures = 0
        frozenTicks = 0
        if (!awaitingFrame) return
        awaitingFrame = false
        AppLog.i("$debugName first frame after ${SystemClock.elapsedRealtime() - startedAt} ms, " +
            "$resolution, $decoderName")
        onFirstFrame?.invoke()
    }

    private fun failed(error: PlaybackException) {
        if (released) return
        consecutiveFailures++
        val delayMs = min(MAX_BACKOFF_MS, 1_000L shl (consecutiveFailures - 1).coerceAtMost(5))
        // Every 4xxx code is a decoder problem, including "resources reclaimed" (another
        // stream took the hardware decoder), which a weak TV chip raises during transitions.
        val decoderProblem = error.errorCode / 1000 == 4
        if (decoderProblem) rebuildOnNext = true
        val audioProblem = audio && (error as? ExoPlaybackException)
            ?.rendererFormat?.sampleMimeType?.startsWith("audio/") == true
        if (audioProblem) {
            // The camera's sound can't be played here: keep the picture, drop the sound.
            AppLog.w("$debugName audio can't be played (${error.errorCodeName}), continuing without sound", error)
            audio = false
            cancelRetry()
            consecutiveFailures = 0
            awaitingFrame = true
            startedAt = SystemClock.elapsedRealtime()
            rebuild()
            return
        }
        retryDelaySec = (delayMs / 1000).toInt()
        status = when {
            isAuthError(error) -> Status.AUTH_FAILED
            consecutiveFailures >= OFFLINE_AFTER_FAILURES -> Status.OFFLINE
            else -> Status.RECONNECTING
        }
        lastError = error.errorCodeName.removePrefix("ERROR_CODE_")
        AppLog.w("$debugName failed (${error.errorCodeName}), retry in ${retryDelaySec}s" +
            (if (decoderProblem) ", rebuilding player" else ""), error)
        onError?.invoke()

        cancelRetry()
        retryTask = Runnable {
            retryTask = null
            if (released) return@Runnable
            awaitingFrame = true
            startedAt = SystemClock.elapsedRealtime()
            if (rebuildOnNext) {
                rebuild()
            } else {
                player?.let { p ->
                    p.prepare()
                    p.playWhenReady = true // may have been switched off by the error
                }
            }
        }.also { handler.postDelayed(it, delayMs) }
    }

    private fun cancelRetry() {
        retryTask?.let { handler.removeCallbacks(it) }
        retryTask = null
    }

    private fun isAuthError(error: PlaybackException): Boolean {
        var t: Throwable? = error
        while (t != null) {
            val msg = t.message.orEmpty()
            // Whole word: "401" inside a port number ("port 40412") must not count.
            if (AUTH_WORDS.containsMatchIn(msg)) return true
            t = t.cause
        }
        return false
    }

    /**
     * Throws the failed player away and starts a fresh one. The old player is detached at once
     * but released on the next loop pass, because release() blocks until its decoder closes;
     * the new player is built only after that, so two decoders are never open together.
     */
    private fun rebuild() {
        val target = view ?: return
        val old = player ?: return
        player = null
        old.stop()
        retiring = old
        handler.post {
            retiring?.release()
            retiring = null
            if (released) return@post
            rebuildOnNext = false
            lastRendered = 0
            val current = view ?: target // the view may have changed meanwhile
            player = buildPlayer().also { p ->
                attach(p, current)
                p.setMediaSource(mediaSource(url))
                p.prepare()
                p.playWhenReady = true
            }
        }
    }

    /** Called once a second by [PlaybackManager]: restarts a stream whose picture has frozen. */
    fun tick() {
        val p = player ?: return
        val counters = p.videoDecoderCounters ?: return
        counters.ensureUpdated()
        val rendered = counters.renderedOutputBufferCount
        val advanced = rendered != lastRendered
        lastRendered = rendered
        if (status != Status.LIVE) return
        if (advanced) {
            frozenTicks = 0
            return
        }
        if (++frozenTicks >= FROZEN_AFTER_TICKS) {
            AppLog.w("$debugName frozen for ${FROZEN_AFTER_TICKS}s, restarting")
            frozenTicks = 0
            status = Status.RECONNECTING
            awaitingFrame = true
            p.stop()
            p.prepare()
            p.playWhenReady = true
        }
    }

    fun release() {
        if (released) return
        released = true
        AppLog.i("$debugName released")
        handler.removeCallbacksAndMessages(null)
        onFirstFrame = null
        onError = null
        retiring?.release()
        retiring = null
        player?.release()
        player = null
        view = null
    }
}
