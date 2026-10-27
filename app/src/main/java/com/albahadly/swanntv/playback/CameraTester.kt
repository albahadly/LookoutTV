package com.albahadly.swanntv.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.SampleStream
import com.albahadly.swanntv.AppLog

/**
 * Checks that a stream answers, without decoding it (so a test never competes for the TV's
 * few video decoders): connects, reads the stream description, and reports what it found.
 */
@OptIn(UnstableApi::class)
class CameraTester(private val context: Context) {

    sealed interface Result {
        data class Ok(val codec: String, val resolution: String, val millis: Long) : Result
        data class Failed(val reason: String) : Result
    }

    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null

    /** Tests [url]; [onResult] is called once on the main thread. A new test cancels the last. */
    fun test(url: String, useTcp: Boolean, onResult: (Result) -> Unit) {
        cancel()
        val started = SystemClock.elapsedRealtime()
        var finished = false
        fun finish(result: Result) {
            if (finished) return
            finished = true
            AppLog.i("camera test: $result")
            cancel()
            onResult(result)
        }

        // The video track is "played" by a renderer that throws the data away, so no decoder is
        // opened. (Disabling every track instead crashes Media3's RTSP client: it then sends PLAY
        // without a session.)
        val p = ExoPlayer.Builder(context) { _, _, _, _, _ -> arrayOf<Renderer>(DiscardVideoRenderer()) }.build()
        player = p
        p.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                if (tracks.groups.isEmpty()) return
                val video = tracks.groups
                    .filter { it.type == C.TRACK_TYPE_VIDEO }
                    .flatMap { group -> (0 until group.length).map { group.getTrackFormat(it) } }
                    .firstOrNull()
                if (video == null) {
                    finish(Result.Failed("The camera answers, but that stream has no video."))
                    return
                }
                val codec = when (video.sampleMimeType) {
                    MimeTypes.VIDEO_H265 -> "H.265"
                    MimeTypes.VIDEO_H264 -> "H.264"
                    else -> video.sampleMimeType ?: "unknown codec"
                }
                val resolution = if (video.width > 0) "${video.width}×${video.height}" else "size unknown"
                finish(Result.Ok(codec, resolution, SystemClock.elapsedRealtime() - started))
            }

            override fun onPlayerError(error: PlaybackException) {
                AppLog.w("camera test error ${error.errorCodeName}", error)
                finish(Result.Failed(describe(error)))
            }
        })
        p.setMediaSource(
            RtspMediaSource.Factory()
                .setForceUseRtpTcp(useTcp)
                .setTimeoutMs(8_000)
                .createMediaSource(MediaItem.fromUri(url))
        )
        p.prepare()
        handler.postDelayed({ finish(Result.Failed("No answer within 10 seconds. Check the address and port.")) }, 10_000)
    }

    /** Accepts any video track and discards its data: enough for the RTSP session to run. */
    private class DiscardVideoRenderer : BaseRenderer(C.TRACK_TYPE_VIDEO) {
        private val formatHolder = FormatHolder()
        private val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
        private var ended = false

        override fun getName() = "DiscardVideoRenderer"

        override fun supportsFormat(format: Format): Int = RendererCapabilities.create(
            if (MimeTypes.isVideo(format.sampleMimeType)) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_TYPE,
        )

        override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
            repeat(50) {
                buffer.clear()
                // Sample data is skipped, not copied: only the stream's progress matters.
                when (readSource(formatHolder, buffer, SampleStream.FLAG_OMIT_SAMPLE_DATA)) {
                    C.RESULT_BUFFER_READ -> if (buffer.isEndOfStream) { ended = true; return }
                    C.RESULT_NOTHING_READ -> return
                }
            }
        }

        override fun onPositionReset(positionUs: Long, joining: Boolean, sampleStreamWasReset: Boolean) {
            ended = false
        }

        override fun isReady() = true
        override fun isEnded() = ended
    }

    fun cancel() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
    }

    private fun describe(error: PlaybackException): String {
        val messages = generateSequence<Throwable>(error) { it.cause }.mapNotNull { it.message }.joinToString(" ")
        return when {
            "401" in messages || "nauthorized" in messages -> "Sign-in failed. Check the username and password."
            "404" in messages || "Not Found" in messages -> "The camera answered but has no stream at that path."
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                "ECONNREFUSED" in messages || "Connection refused" in messages ->
                "Couldn't connect. Check the address and port."
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                "No answer from that address. Is the camera on this network?"
            else -> {
                // Unknown: say what actually went wrong, so it can be looked into.
                val root = generateSequence<Throwable>(error) { it.cause }.last()
                val detail = (root.message ?: "").let { AppLog.redact(it) }.take(160)
                val code = error.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')
                "Failed ($code): ${root.javaClass.simpleName}" + if (detail.isNotEmpty()) " – $detail" else ""
            }
        }
    }
}
