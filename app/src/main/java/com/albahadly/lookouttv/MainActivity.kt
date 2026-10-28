package com.albahadly.lookouttv

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.albahadly.lookouttv.ui.AppController
import com.albahadly.lookouttv.ui.AppRoot

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
    // except the optional floating window, which keeps one camera playing over other apps.
    override fun onStart() {
        super.onStart()
        app.onStart()
    }

    /** Home pressed: maybe keep a camera playing in a floating window over other apps. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        app.onHomePressed()
    }

    override fun onResume() {
        super.onResume()
        app.onResume()
    }

    override fun onStop() {
        app.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        app.onDestroy(isFinishing)
        super.onDestroy()
    }
}
