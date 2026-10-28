package com.albahadly.lookouttv.probe

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import java.util.Locale
import kotlin.math.min

/**
 * One RTSP stream played by its own ExoPlayer, with the numbers the probe needs:
 * decoder name (hardware or software), resolution, fps, dropped frames, time to first frame.
 *
 * Recovery:
 *  - Network/stream errors: re-prepare with backoff 1 s, 2 s, 4 s ... up to 30 s.
 *  - Decoder errors: the whole player (and its decoder) is rebuilt, because re-preparing
 *    a player whose hardware decoder failed tends to fail again.
 *  - Frozen picture (live but 0 fps for 5 s): restart.
 */
@OptIn(UnstableApi::class)
class StreamProbe(
    private val context: Context,
    label: String,
    url: String,
) {
    enum class State { CONNECTING, LIVE, RETRYING, FROZEN, ERROR, RELEASED }

    private companion object {
        const val MAX_BACKOFF_MS = 30_000L
    }

    private val handler = Handler(Looper.getMainLooper())

    var label: String = label
        private set
    private var url: String = url
    private var view: View? = null

    var player: ExoPlayer? = null
        private set
    var state = State.CONNECTING
        private set
    var lastError: String? = null
        private set
    var decoderName: String? = null
        private set
    var firstFrameMs = -1L
        private set
    var fps = 0.0
        private set
    var dropped = 0
        private set
    var retries = 0
        private set
    var rebuilds = 0
        private set

    /** Main thread, ms since [startOn] / [moveTo] / [switchTo] (or since recovery) when a frame shows. */
    var onFirstFrame: ((Long) -> Unit)? = null

    /** Main thread, after any playback error (before the retry is scheduled). */
    var onError: ((String) -> Unit)? = null

    val isHardware: Boolean? get() = decoderName?.let { DeviceInfo.isHardwareName(it) }

    val resolution: String
        get() = player?.videoSize?.takeIf { it.width > 0 }
            ?.let { "${it.width}x${it.height}" } ?: "-"

    private var markAt = 0L
    private var awaitingFrame = true
    private var consecutiveFailures = 0
    private var lastRendered = 0
    private var lastTickAt = 0L
    private var frozenTicks = 0

    fun startOn(view: View) {
        this.view = view
        markAt = SystemClock.elapsedRealtime()
        awaitingFrame = true
        state = State.CONNECTING
        player = buildPlayer().also { p ->
            attach(p, view)
            p.setMediaSource(mediaSource(url))
            p.prepare()
            p.playWhenReady = true
        }
    }

    /** Moves the already-playing stream to another surface. */
    fun moveTo(view: View, markAtMs: Long) {
        val p = player ?: return
        this.view = view
        markAt = markAtMs
        awaitingFrame = true
        attach(p, view)
    }

    /**
     * Plays a different stream on the SAME player, keeping its decoder.
     * Used when switching cameras in full screen: creating a second 4K decoder while the
     * old one is still closing is what made one camera fail on the TCL.
     */
    fun switchTo(newUrl: String, newLabel: String, markAtMs: Long) {
        val p = player ?: return
        handler.removeCallbacksAndMessages(null)
        url = newUrl
        label = newLabel
        markAt = markAtMs
        awaitingFrame = true
        consecutiveFailures = 0
        firstFrameMs = -1
        lastRendered = 0
        state = State.CONNECTING
        p.setMediaSource(mediaSource(newUrl))
        p.prepare()
        p.playWhenReady = true
    }

    private fun mediaSource(streamUrl: String) =
        RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .setTimeoutMs(8_000)
            .createMediaSource(MediaItem.fromUri(streamUrl))

    private fun attach(p: ExoPlayer, view: View) {
        when (view) {
            is SurfaceView -> p.setVideoSurfaceView(view)
            is TextureView -> p.setVideoTextureView(view)
            else -> throw IllegalArgumentException("Unsupported video view: $view")
        }
    }

    private fun buildPlayer(): ExoPlayer {
        val renderers = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true) // fall back to a software decoder rather than fail
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(500, 2_000, 100, 300) // low latency for live cameras
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        return ExoPlayer.Builder(context, renderers)
            .setLoadControl(loadControl)
            .build()
            .apply {
                volume = 0f
                addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() = frameShown()
                    override fun onPlayerError(error: PlaybackException) = failed(error)
                })
                addAnalyticsListener(object : AnalyticsListener {
                    override fun onVideoDecoderInitialized(
                        eventTime: AnalyticsListener.EventTime,
                        decoderName: String,
                        initializedTimestampMs: Long,
                        initializationDurationMs: Long,
                    ) {
                        this@StreamProbe.decoderName = decoderName
                    }
                })
            }
    }

    private fun frameShown() {
        state = State.LIVE
        frozenTicks = 0
        consecutiveFailures = 0
        if (!awaitingFrame) return
        awaitingFrame = false
        val ms = SystemClock.elapsedRealtime() - markAt
        if (firstFrameMs < 0) firstFrameMs = ms
        onFirstFrame?.invoke(ms)
    }

    private fun failed(error: PlaybackException) {
        if (state == State.RELEASED) return
        state = State.ERROR
        lastError = error.errorCodeName + (error.cause?.message?.let { ": ${it.take(80)}" } ?: "")
        retries++
        consecutiveFailures++
        val decoderProblem = error.errorCode in
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
        val delay = min(MAX_BACKOFF_MS, 1_000L shl (consecutiveFailures - 1).coerceAtMost(5))
        ProbeLog.write("$label error (retry $retries, next in ${delay / 1000} s" +
            "${if (decoderProblem) ", rebuilding player" else ""}): $lastError")
        onError?.invoke(lastError ?: "")

        handler.postDelayed({
            if (state == State.RELEASED) return@postDelayed
            state = State.RETRYING
            awaitingFrame = true
            markAt = SystemClock.elapsedRealtime()
            if (decoderProblem) rebuild() else player?.prepare()
        }, delay)
    }

    /** Throws the player and its decoder away and starts a fresh one on the same surface. */
    private fun rebuild() {
        val v = view ?: return
        rebuilds++
        player?.release()
        decoderName = null
        lastRendered = 0
        player = buildPlayer().also { p ->
            attach(p, v)
            p.setMediaSource(mediaSource(url))
            p.prepare()
            p.playWhenReady = true
        }
    }

    /** Call once a second: updates fps and restarts a stream whose picture has frozen. */
    fun tick(nowMs: Long) {
        val p = player ?: return
        val counters = p.videoDecoderCounters
        if (counters != null) {
            counters.ensureUpdated()
            val rendered = counters.renderedOutputBufferCount
            dropped = counters.droppedBufferCount
            val dt = nowMs - lastTickAt
            if (lastTickAt > 0 && dt > 0) {
                fps = (rendered - lastRendered).coerceAtLeast(0) * 1000.0 / dt
            }
            lastRendered = rendered
        }
        lastTickAt = nowMs

        if (state == State.LIVE && fps == 0.0) {
            frozenTicks++
            if (frozenTicks >= 5) {
                frozenTicks = 0
                retries++
                state = State.FROZEN
                ProbeLog.write("$label frozen for 5 s, restarting")
                awaitingFrame = true
                markAt = nowMs
                p.stop()
                p.prepare()
            }
        } else if (fps > 0) {
            frozenTicks = 0
        }
    }

    fun statusLine(): String = when (state) {
        State.LIVE -> String.format(
            Locale.US, "%s | %s | %.1f fps | drop %d | 1st %d ms%s",
            if (isHardware == false) "SW" else "HW", resolution, fps, dropped, firstFrameMs,
            if (retries > 0) " | retry $retries" else "",
        )
        State.CONNECTING -> "Connecting..."
        State.RETRYING -> "Reconnecting... (retry $retries)"
        State.FROZEN -> "Frozen, restarting..."
        State.ERROR -> "Error: ${lastError ?: "unknown"}"
        State.RELEASED -> "Stopped"
    }

    fun release() {
        state = State.RELEASED
        handler.removeCallbacksAndMessages(null)
        onFirstFrame = null
        onError = null
        player?.release()
        player = null
        view = null
    }
}
