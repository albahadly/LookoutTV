package com.albahadly.lookouttv.ui

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.albahadly.lookouttv.AppLog
import com.albahadly.lookouttv.data.AppSettings
import com.albahadly.lookouttv.data.LayoutPreference
import com.albahadly.lookouttv.data.MotionMode
import com.albahadly.lookouttv.data.SettingsStore
import com.albahadly.lookouttv.floating.FloatingCamera
import com.albahadly.lookouttv.floating.FloatingCameraService
import com.albahadly.lookouttv.model.CameraRepository
import com.albahadly.lookouttv.playback.CameraTester
import com.albahadly.lookouttv.playback.PlaybackManager
import com.albahadly.lookouttv.setup.PhoneSetupApi
import com.albahadly.lookouttv.setup.PhoneSetupServer
import com.albahadly.lookouttv.ui.settings.PinPad
import com.albahadly.lookouttv.ui.settings.SettingsScreen
import com.albahadly.lookouttv.update.UpdateManager
import com.albahadly.lookouttv.BuildConfig
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Screen { VIEWER, PIN, SETTINGS }

/**
 * App-level state: the saved settings, which screen is showing, and the viewer built from
 * the current camera list. Streams play only on the viewer screen; Settings and the PIN
 * prompt stop them, so a camera Test never competes with them for the TV's decoders.
 */
@Stable
class AppController(context: Context, private val scope: CoroutineScope) {

    private val store = SettingsStore(context)
    /** New releases on GitHub: banner in the grid, controls in Settings → About. */
    val updates = UpdateManager(context, scope)
    private val appContext = context.applicationContext
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
        PhoneSetupApi(
            settings = { settings },
            update = ::update,
            tester = CameraTester(context),
            isCurrentSession = isCurrent,
            control = ::phoneControl,
            logTail = AppLog::tail,
            updateAction = ::phoneUpdate,
            updateJson = ::updateJson,
        ).also { phoneApi = it }
    }
    private lateinit var phoneApi: PhoneSetupApi
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
    /** Android hinted the user is leaving, and the app hasn't stopped or come back yet. */
    private var userLeaving = false
    /** Looks for updates while the app is on screen: the TV may stay on the cameras for days. */
    private var updateTicker: Job? = null
    private var wrongPins = 0
    private var pinLockedUntil = 0L

    /** Cameras whose saved password the TV can't read right now (Settings warns about them). */
    val unreadablePasswordIds: Set<String> get() = store.unreadablePasswordIds
    /** Why the last save didn't fully work, or null. */
    val saveProblem: String? get() = store.saveProblem

    fun update(newSettings: AppSettings) {
        settings = newSettings.normalized()
        store.save(settings)
        updates.token = settings.githubToken
    }

    /**
     * The phone page's remote buttons (main thread). Works on the camera screen; from Settings
     * it first goes back to the cameras. Returns a message when it can't be done.
     */
    private fun phoneControl(action: String, id: String): String? {
        if (!started) return "The TV app isn't on screen."
        if (screen == Screen.PIN) return "The TV is asking for the PIN."
        if (screen == Screen.SETTINGS) {
            if (!settings.phoneControl) return "Turn on \"Keep the phone connected\" in Phone setup on the TV first."
            closeSettings()
        }
        when (action) {
            "grid" -> viewer.close()
            "tour" -> viewer.toggleTour()
            "next" -> viewer.stepByUser(+1)
            "prev" -> viewer.stepByUser(-1)
            "group" -> showGroup(id)
            "camera" -> {
                val config = settings.cameras.firstOrNull { it.id == id } ?: return "That camera doesn't exist any more."
                if (!config.enabled) return "${config.name} is hidden: show it in the grid first."
                if (viewer.cameras.none { it.id == id }) showGroup("") // not in the current group
                val index = viewer.cameras.firstOrNull { it.id == id }?.index ?: return "${config.name} isn't on screen."
                viewer.showCamera(index)
            }
        }
        return null
    }

    fun openSettings() {
        viewer.pause()
        if (settings.hasPin) screen = Screen.PIN else showSettings()
    }

    private fun showSettings() {
        screen = Screen.SETTINGS
        phoneApi.settingsOpen = true
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

    /** The header's group chip: All → first group → … → All. The grid keeps its layout. */
    private fun showNextGroup() {
        val groups = settings.groups
        if (groups.isEmpty()) return
        val current = groups.indexOfFirst { it.id == settings.currentGroupId }
        val next = if (current < 0) groups.first().id else groups.getOrNull(current + 1)?.id ?: ""
        showGroup(next)
    }

    /** Shows [groupId] (or all cameras for ""), from the grid or the phone. */
    fun showGroup(groupId: String) {
        if (groupId.isNotEmpty() && settings.groups.none { it.id == groupId }) return
        if (groupId == settings.currentGroupId) return
        update(settings.copy(currentGroupId = groupId))
        if (screen != Screen.VIEWER) return // picked up when Settings closes
        viewer.close()
        manager.stopDetachedTilesSoon() // tiles about to show another camera must not be reused
        val keepFocusOnChip = viewer.focusTarget == null
        viewer = buildViewer().also { if (keepFocusOnChip) it.focusTarget = null }
        manager.clearPictures() // positions mean other cameras now
        AppLog.i("showing group ${settings.currentGroup?.name ?: "all"}: ${viewer.cameras.size} camera(s)")
        if (started) viewer.resume()
    }

    fun closeSettings() {
        tester.cancel()
        phoneApi.settingsOpen = false
        // The phone may stay connected as a remote for up to 15 minutes of use.
        if (!settings.phoneControl) stopPhoneSetup()
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
        // Back in the app: close the floating window first; its decoder must be free before
        // the grid (maybe a 4K tile) starts.
        if (FloatingCamera.hide()) FloatingCameraService.stop(appContext)
        started = true
        when (screen) {
            Screen.VIEWER -> viewer.resume()
            Screen.SETTINGS -> startPhoneSetup() // back in Settings without a PIN: new QR code
            Screen.PIN -> Unit
        }
        updates.token = settings.githubToken
        updateTicker?.cancel()
        updateTicker = scope.launch {
            while (true) {
                updates.checkIfDue(settings.updateCheck)
                delay(60_000)
            }
        }
    }

    /** Back from the installer or another app. */
    fun onResume() {
        userLeaving = false // the app never left: whatever covered it has gone
        updates.onResumed()
    }

    private fun phoneUpdate(action: String, value: String): String? {
        when (action) {
            "check" -> updates.check()
            "install" -> {
                if (updates.available == null) return "No update to install."
                updates.install()
            }
            "later" -> updates.snooze()
            "token" -> update(settings.copy(githubToken = value))
        }
        return null
    }

    private fun updateJson(): JSONObject = JSONObject()
        .put("installed", BuildConfig.VERSION_NAME)
        .put("available", updates.available?.version ?: "")
        .put("notes", updates.available?.notes ?: "")
        .put("status", updates.statusText())
        .put("busy", updates.status is UpdateManager.Status.Checking || updates.status is UpdateManager.Status.Downloading)
        .put("canInstall", updates.canInstall())
        .put("hasToken", settings.githubToken.isNotEmpty())
        .put("autoCheck", settings.updateCheck)

    /**
     * Android's hint that the user is leaving. It also comes when the TV puts its own prompt
     * over the app after a long time without keys, and then the app stays on screen and never
     * stops, so the floating window waits for [onStop].
     */
    fun onUserLeaving() {
        userLeaving = true
    }

    /**
     * Left the app while watching: if the floating window is on and allowed, keep the camera
     * playing in a small window over other apps. Everything else stops first, so the floating
     * stream never overlaps another decoder (the 4K one fails on this TV if it does).
     */
    private fun showFloatingWindow() {
        val allowed = FloatingCamera.canShow(appContext)
        // Still "interactive" after Home; not when the TV went to sleep under its own prompt.
        val screenOn = appContext.getSystemService(PowerManager::class.java).isInteractive
        AppLog.i("left the app: floating window setting=${settings.pictureInPicture} permission=$allowed screen=$screen screenOn=$screenOn")
        if (!settings.pictureInPicture || !allowed || !screenOn || screen != Screen.VIEWER || !started) return
        val camera = viewer.floatingCandidate() ?: return // only from full screen
        viewer.pause() // releases every stream now (the 4K one included)
        if (FloatingCamera.show(
                appContext, camera, manager.lastPictures[camera.index], settings.floatingSize, settings.floatingCorner,
            )) {
            FloatingCameraService.start(appContext)
        }
    }

    fun onStop() {
        if (userLeaving) showFloatingWindow()
        userLeaving = false
        started = false
        updateTicker?.cancel()
        tester.cancel()
        viewer.pause()
        stopPhoneSetup() // never left running in the background
        // Leaving the app (Home, TV off) re-locks Settings: the next person needs the PIN.
        if (screen == Screen.SETTINGS && settings.hasPin) screen = Screen.PIN
        phoneApi.settingsOpen = screen == Screen.SETTINGS
    }

    /**
     * [finishing]: the user closed the app (Back out, or Android replacing the activity),
     * so the floating window goes too. Otherwise Android only reclaimed the hidden screen to
     * free memory while the window plays over another app: that must keep going.
     */
    fun onDestroy(finishing: Boolean) {
        if (finishing && FloatingCamera.hide()) FloatingCameraService.stop(appContext)
        stopPhoneSetup()
        manager.releaseAll()
    }

    private fun buildViewer() = ViewerState(
        motionOpens = settings.motionMode == MotionMode.OPEN,
        tourSeconds = settings.tourSeconds,
        cameras = CameraRepository.fromSettings(settings),
        manager = manager,
        scope = scope,
        initialLayout = when (settings.layout) {
            LayoutPreference.AUTO -> null
            LayoutPreference.ONE -> GridLayout.ONE
            LayoutPreference.FOUR -> GridLayout.FOUR
            LayoutPreference.SIX -> GridLayout.SIX
            LayoutPreference.NINE -> GridLayout.NINE
        },
        onLayoutChanged = { layout ->
            update(settings.copy(layout = when (layout) {
                GridLayout.ONE -> LayoutPreference.ONE
                GridLayout.FOUR -> LayoutPreference.FOUR
                GridLayout.SIX -> LayoutPreference.SIX
                GridLayout.NINE -> LayoutPreference.NINE
            }))
        },
        groupName = settings.currentGroup?.takeIf { settings.groupCameras() != null }?.name,
        groupCount = settings.groups.size,
        onNextGroup = ::showNextGroup,
    )
}

@Composable
fun AppRoot(app: AppController) {
    when (app.screen) {
        Screen.VIEWER -> ViewerScreen(app.viewer, onOpenSettings = app::openSettings, updates = app.updates)
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
