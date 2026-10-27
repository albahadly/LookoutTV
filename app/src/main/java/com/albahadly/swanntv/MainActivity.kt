package com.albahadly.swanntv

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.albahadly.swanntv.ui.AppController
import com.albahadly.swanntv.ui.AppRoot

class MainActivity : ComponentActivity() {

    private lateinit var app: AppController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        AppLog.init(this)

        app = AppController(this, lifecycleScope)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AppRoot(app)
            }
        }
    }

    // Streams run only while the app is on screen (TV off, Home pressed -> everything stops),
    // except in the picture-in-picture window, which keeps one camera playing.
    override fun onStart() {
        super.onStart()
        app.onStart()
    }

    /** Home pressed: shrink to a small window instead of stopping, if that's turned on. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!app.canEnterPip() || !app.enterPip()) return
        val entered = runCatching {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        }.getOrDefault(false)
        if (!entered) {
            // Turned off for this app in Android settings: just stop, as without PiP (no
            // restart of the streams while the app is leaving).
            AppLog.w("picture-in-picture refused by the TV")
            app.cancelPip()
        }
    }

    /** Back from the small window: whichever of this and onResume comes second does it. */
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (!isInPictureInPictureMode && app.inPip && lifecycle.currentState == Lifecycle.State.RESUMED) app.exitPip()
    }

    /**
     * Back from the small window to full screen. (Closing the window instead stops the
     * activity, and onStop stops everything.)
     */
    override fun onResume() {
        super.onResume()
        if (app.inPip && !isInPictureInPictureMode) app.exitPip()
    }

    override fun onStop() {
        app.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        app.onDestroy()
        super.onDestroy()
    }
}
