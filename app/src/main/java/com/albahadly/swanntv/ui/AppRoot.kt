package com.albahadly.swanntv.ui

import android.content.Context
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.albahadly.swanntv.AppLog
import com.albahadly.swanntv.data.AppSettings
import com.albahadly.swanntv.data.LayoutPreference
import com.albahadly.swanntv.data.SettingsStore
import com.albahadly.swanntv.model.CameraRepository
import com.albahadly.swanntv.playback.CameraTester
import com.albahadly.swanntv.playback.PlaybackManager
import com.albahadly.swanntv.setup.PhoneSetupApi
import com.albahadly.swanntv.setup.PhoneSetupServer
import com.albahadly.swanntv.ui.settings.PinPad
import com.albahadly.swanntv.ui.settings.SettingsScreen
import kotlinx.coroutines.CoroutineScope

enum class Screen { VIEWER, PIN, SETTINGS }

/**
 * App-level state: the saved settings, which screen is showing, and the viewer built from
 * the current camera list. Streams play only on the viewer screen; Settings and the PIN
 * prompt stop them, so a camera Test never competes with them for the TV's decoders.
 */
@Stable
class AppController(context: Context, private val scope: CoroutineScope) {

    private val store = SettingsStore(context)
    val manager = PlaybackManager(context)
    val tester = CameraTester(context)

    /** Phone setup: runs only while Settings is on screen. Its own tester, so the TV's isn't cancelled. */
    private val phoneServer = PhoneSetupServer(
        context,
        onIdleStop = {
            phoneSetupUrl = null
            phoneSetupIdle = true
        },
    ) { isCurrent ->
        PhoneSetupApi(settings = { settings }, update = ::update, tester = CameraTester(context), isCurrentSession = isCurrent)
    }
    /** The address in the QR code while phone setup runs, else null. */
    var phoneSetupUrl by mutableStateOf<String?>(null)
        private set
    /** Address and 6-digit code for typing it in by hand, while phone setup runs. */
    var phoneSetupAddress by mutableStateOf<String?>(null)
        private set
    var phoneSetupCode by mutableStateOf("")
        private set
    /** True when phone setup stopped itself after 15 minutes without use. */
    var phoneSetupIdle by mutableStateOf(false)
        private set

    var settings by mutableStateOf(store.load())
        private set
    var screen by mutableStateOf(Screen.VIEWER)
        private set
    var viewer by mutableStateOf(buildViewer())
        private set

    private var started = false
    private var wrongPins = 0
    private var pinLockedUntil = 0L

    fun update(newSettings: AppSettings) {
        settings = newSettings
        store.save(newSettings)
    }

    fun openSettings() {
        viewer.pause()
        if (settings.hasPin) screen = Screen.PIN else showSettings()
    }

    private fun showSettings() {
        screen = Screen.SETTINGS
        startPhoneSetup()
    }

    /** Starts phone setup (also used by the Settings screen's Retry). */
    fun startPhoneSetup() {
        phoneSetupIdle = false
        val running = phoneServer.start()
        phoneSetupUrl = if (running) phoneServer.url() else null
        phoneSetupAddress = if (running) phoneServer.plainUrl() else null
        phoneSetupCode = if (running) phoneServer.pairCode else ""
    }

    private fun stopPhoneSetup() {
        phoneServer.stop()
        phoneSetupUrl = null
        phoneSetupAddress = null
        phoneSetupCode = ""
    }

    /** Non-null while too many wrong PINs have been entered: say how long to wait. */
    fun pinLockMessage(): String? {
        val left = pinLockedUntil - SystemClock.elapsedRealtime()
        return if (left > 0) "Too many tries. Wait ${(left + 999) / 1000} s." else null
    }

    fun pinEntered(pin: String): Boolean {
        if (pinLockMessage() != null) return false
        if (!settings.pinMatches(pin)) {
            // After 5 wrong PINs, wait 30 s, doubling each time: guessing 10,000 PINs is impractical.
            if (++wrongPins >= 5) {
                val wait = 30_000L shl (wrongPins - 5).coerceAtMost(5)
                pinLockedUntil = SystemClock.elapsedRealtime() + wait
                AppLog.w("wrong PIN $wrongPins times, locked for ${wait / 1000} s")
            }
            return false
        }
        wrongPins = 0
        showSettings()
        return true
    }

    fun closeSettings() {
        tester.cancel()
        stopPhoneSetup()
        val before = viewer.cameras
        viewer = buildViewer() // picks up any camera or layout changes
        // Last pictures are kept per position: after a reorder, hide or delete they would
        // show the wrong camera while tiles connect, so start without them.
        if (viewer.cameras != before) manager.clearPictures()
        screen = Screen.VIEWER
        AppLog.i("settings closed: ${viewer.cameras.size} camera(s) shown")
        if (started) viewer.resume()
    }

    fun onStart() {
        started = true
        when (screen) {
            Screen.VIEWER -> viewer.resume()
            Screen.SETTINGS -> startPhoneSetup() // back in Settings without a PIN: new QR code
            Screen.PIN -> Unit
        }
    }

    fun onStop() {
        started = false
        tester.cancel()
        viewer.pause()
        stopPhoneSetup() // never left running in the background
        // Leaving the app (Home, TV off) re-locks Settings: the next person needs the PIN.
        if (screen == Screen.SETTINGS && settings.hasPin) screen = Screen.PIN
    }

    fun onDestroy() {
        stopPhoneSetup()
        manager.releaseAll()
    }

    private fun buildViewer() = ViewerState(
        cameras = CameraRepository.fromSettings(settings),
        manager = manager,
        scope = scope,
        initialLayout = when (settings.layout) {
            LayoutPreference.AUTO -> null
            LayoutPreference.ONE -> GridLayout.ONE
            LayoutPreference.FOUR -> GridLayout.FOUR
            LayoutPreference.NINE -> GridLayout.NINE
        },
        onLayoutChanged = { layout ->
            update(settings.copy(layout = when (layout) {
                GridLayout.ONE -> LayoutPreference.ONE
                GridLayout.FOUR -> LayoutPreference.FOUR
                GridLayout.NINE -> LayoutPreference.NINE
            }))
        },
    )
}

@Composable
fun AppRoot(app: AppController) {
    when (app.screen) {
        Screen.VIEWER -> ViewerScreen(app.viewer, onOpenSettings = app::openSettings)
        Screen.PIN -> {
            BackHandler { app.closeSettings() }
            PinPad(
                title = "Settings",
                subtitle = "Enter the PIN",
                lockMessage = app::pinLockMessage,
                onCancel = app::closeSettings,
                onPin = app::pinEntered,
            )
        }
        Screen.SETTINGS -> SettingsScreen(app)
    }
}
