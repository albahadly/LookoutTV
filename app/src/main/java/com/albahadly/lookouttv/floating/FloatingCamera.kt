package com.albahadly.lookouttv.floating

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import com.albahadly.lookouttv.AppLog
import com.albahadly.lookouttv.data.FloatingCorner
import com.albahadly.lookouttv.data.FloatingSize
import com.albahadly.lookouttv.model.Camera
import com.albahadly.lookouttv.playback.StreamPlayer

/**
 * A small camera window drawn over other apps, for TVs without picture-in-picture (the TCL
 * 55C6L refuses it). Needs the "display over other apps" permission, which a TV has no menu
 * for: `adb shell appops set com.albahadly.lookouttv SYSTEM_ALERT_WINDOW allow`.
 *
 * Holds views and a player made from the application context, only while showing (cleared
 * in [hide]), so the lint warning about static context fields doesn't apply.
 *
 * Plays the camera's light grid stream without sound. Main thread only. Kept outside the
 * service so the app can close it (and free its decoder) synchronously when it comes back.
 */
@SuppressLint("StaticFieldLeak")
object FloatingCamera {

    private val handler = Handler(Looper.getMainLooper())
    private var root: View? = null
    private var player: StreamPlayer? = null

    private val ticker = object : Runnable {
        override fun run() {
            val p = player ?: return
            p.tick() // restarts a frozen picture
            handler.postDelayed(this, 1_000)
        }
    }

    val isShowing: Boolean get() = root != null

    /** True if the TV lets the app draw over other apps. */
    fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** Shows [camera] in the corner. False if the TV refused the window. */
    fun show(
        context: Context,
        camera: Camera,
        lastPicture: ImageBitmap?,
        size: FloatingSize = FloatingSize.MEDIUM,
        corner: FloatingCorner = FloatingCorner.BOTTOM_RIGHT,
    ): Boolean {
        hide()
        val wm = context.getSystemService(WindowManager::class.java)
        val metrics = context.resources.displayMetrics
        val width = (metrics.widthPixels * size.widthFraction).toInt()
        val height = width * 9 / 16
        val margin = (metrics.widthPixels * 0.025f).toInt()

        val surface = SurfaceView(context)
        val still = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            lastPicture?.let { setImageBitmap(it.asAndroidBitmap()) }
        }
        val label = TextView(context).apply {
            text = camera.name
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(12, 4, 12, 4)
            background = GradientDrawable().apply {
                setColor(0xCC0A0C10.toInt())
                cornerRadius = 8f
            }
        }
        val frame = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            addView(surface, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(still, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(label, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START,
            ).apply { setMargins(12, 12, 12, 12) })
        }
        val params = WindowManager.LayoutParams(
            width, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Never takes the remote's keys or focus away from the app underneath.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = when (corner) {
                FloatingCorner.TOP_LEFT -> Gravity.TOP or Gravity.START
                FloatingCorner.TOP_RIGHT -> Gravity.TOP or Gravity.END
                FloatingCorner.BOTTOM_LEFT -> Gravity.BOTTOM or Gravity.START
                FloatingCorner.BOTTOM_RIGHT -> Gravity.BOTTOM or Gravity.END
            }
            x = margin
            y = margin
        }
        try {
            wm.addView(frame, params)
        } catch (e: Exception) {
            AppLog.w("floating window refused by the TV: ${e.javaClass.simpleName}: ${e.message}")
            return false
        }
        root = frame
        player = StreamPlayer(context.applicationContext, camera.subUrl, "${camera.name} floating", camera.useTcp).also {
            it.onFirstFrame = { still.visibility = View.GONE }
            it.start(surface)
        }
        handler.postDelayed(ticker, 1_000)
        AppLog.i("floating window showing ${camera.name}")
        return true
    }

    /** Closes the window and releases its stream now. True if it was showing. */
    fun hide(): Boolean {
        val view = root ?: return false
        handler.removeCallbacks(ticker)
        player?.release() // blocks until the decoder is closed: the app's streams start next
        player = null
        runCatching { view.context.getSystemService(WindowManager::class.java).removeView(view) }
        root = null
        AppLog.i("floating window closed")
        return true
    }
}
