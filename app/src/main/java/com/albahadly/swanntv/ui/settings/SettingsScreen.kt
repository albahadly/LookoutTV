package com.albahadly.swanntv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.albahadly.swanntv.BuildConfig
import com.albahadly.swanntv.data.AppSettings
import com.albahadly.swanntv.data.CameraConfig
import com.albahadly.swanntv.data.CameraGroup
import com.albahadly.swanntv.data.LayoutPreference
import com.albahadly.swanntv.data.FloatingSize
import com.albahadly.swanntv.data.FloatingCorner
import com.albahadly.swanntv.floating.FloatingCamera
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import com.albahadly.swanntv.data.MotionSensitivity
import com.albahadly.swanntv.data.MotionMode
import com.albahadly.swanntv.ui.AppController
import com.albahadly.swanntv.ui.Palette
import com.albahadly.swanntv.ui.isSelect
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type

private enum class Pane(val label: String) {
    CAMERAS("Cameras"), PHONE("Phone setup"), LAYOUT("Viewing"), SECURITY("PIN lock"), ABOUT("About")
}

private enum class PinFlow { SET, REMOVE }

/** Settings, opened from the grid's header. Every change is saved at once. */
@Composable
fun SettingsScreen(app: AppController) {
    val settings = app.settings
    var pane by remember { mutableStateOf(Pane.CAMERAS) }
    var editing by remember { mutableStateOf<Pair<CameraConfig, Boolean>?>(null) } // camera, isNew
    var editingGroup by remember { mutableStateOf<Pair<CameraGroup, Boolean>?>(null) } // group, isNew
    var nvrOpen by remember { mutableStateOf(false) }
    var pinFlow by remember { mutableStateOf<PinFlow?>(null) }
    val paneFocus = remember { Pane.entries.associateWith { FocusRequester() } }
    val addFocus = remember { FocusRequester() }
    /** Set when a form opens: closing it returns focus to the camera list. */
    var returnToList by remember { mutableStateOf(false) }

    BackHandler {
        when {
            pinFlow != null -> pinFlow = null
            editing != null -> editing = null
            editingGroup != null -> editingGroup = null
            nvrOpen -> nvrOpen = false
            else -> app.closeSettings()
        }
    }

    // One place decides focus: on opening Settings and after a PIN screen, the current menu
    // item; after closing a camera form, back on the camera list.
    LaunchedEffect(pinFlow, editing == null && editingGroup == null && !nvrOpen) {
        if (pinFlow != null || editing != null || editingGroup != null || nvrOpen) return@LaunchedEffect
        withFrameNanos { }
        runCatching {
            if (returnToList && pane == Pane.CAMERAS) addFocus.requestFocus()
            else paneFocus.getValue(pane).requestFocus()
        }
        returnToList = false
    }

    pinFlow?.let { flow ->
        when (flow) {
            PinFlow.SET -> PinPad(
                title = "Set a PIN",
                subtitle = "Choose 4 digits. Settings will ask for them.",
                confirm = true,
                onCancel = { pinFlow = null },
            ) { pin ->
                app.update(settings.withPin(pin))
                pinFlow = null
                true
            }
            PinFlow.REMOVE -> PinPad(
                title = "Remove the PIN",
                subtitle = "Enter the current PIN",
                onCancel = { pinFlow = null },
            ) { pin ->
                if (!settings.pinMatches(pin)) return@PinPad false
                app.update(settings.withPin(null))
                pinFlow = null
                true
            }
        }
        return
    }

    Row(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .padding(horizontal = 32.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        // ---------------------------------------------------------------- menu
        Column(
            // Entering the menu from the right lands on the current section, not the nearest
            // item (which, from a low camera row, was "About" and switched the screen).
            Modifier
                .width(160.dp)
                .fillMaxHeight()
                .focusRestorer(paneFocus.getValue(pane))
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Settings", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
            Text("Back returns to the cameras", fontSize = 9.sp, color = Palette.Quiet)
            Spacer(Modifier.height(12.dp))
            Pane.entries.forEach { p ->
                NavItem(
                    p.label,
                    selected = p == pane && editing == null && editingGroup == null && !nvrOpen,
                    modifier = Modifier.focusRequester(paneFocus.getValue(p)),
                    onFocused = {
                        // Moving onto the menu shows the section, but never while a form is open:
                        // focus passes over the menu for a moment when a form opens, and what was
                        // typed must survive that.
                        if (editing == null && editingGroup == null && !nvrOpen) pane = p
                    },
                    onPressed = {
                        // OK on a menu item while a form is open closes the form (unsaved changes
                        // are dropped, as with Back) and shows that section.
                        editing = null
                        editingGroup = null
                        nvrOpen = false
                        returnToList = false
                        pane = p
                    },
                )
            }
        }

        // ---------------------------------------------------------------- content
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when {
                editing != null -> {
                    val (camera, isNew) = editing!!
                    CameraForm(
                        initial = camera,
                        isNew = isNew,
                        tester = app.tester,
                        onSave = { saved ->
                            // Merge into the current list: the phone may have changed it meanwhile.
                            val current = app.settings
                            val exists = current.cameras.any { it.id == saved.id }
                            when {
                                !exists && current.cameras.size >= AppSettings.MAX_CAMERAS ->
                                    "16 cameras is the maximum. Delete one first."
                                else -> {
                                    val updated = if (exists) current.cameras.map { if (it.id == saved.id) saved else it }
                                    else current.cameras + saved
                                    app.update(current.copy(cameras = updated))
                                    editing = null
                                    null
                                }
                            }
                        },
                        onDelete = {
                            app.update(app.settings.copy(cameras = app.settings.cameras.filter { it.id != camera.id }))
                            editing = null
                        },
                        onCancel = { editing = null },
                    )
                }
                editingGroup != null -> {
                    val (group, isNew) = editingGroup!!
                    GroupForm(
                        initial = group,
                        isNew = isNew,
                        cameras = app.settings.cameras,
                        onSave = { saved ->
                            val current = app.settings
                            val exists = current.groups.any { it.id == saved.id }
                            when {
                                !exists && current.groups.size >= AppSettings.MAX_GROUPS -> "${AppSettings.MAX_GROUPS} groups is the maximum."
                                else -> {
                                    val updated = if (exists) current.groups.map { if (it.id == saved.id) saved else it }
                                    else current.groups + saved
                                    app.update(current.copy(groups = updated))
                                    editingGroup = null
                                    null
                                }
                            }
                        },
                        onDelete = {
                            val current = app.settings
                            app.update(current.copy(
                                groups = current.groups.filter { it.id != group.id },
                                currentGroupId = if (current.currentGroupId == group.id) "" else current.currentGroupId,
                            ))
                            editingGroup = null
                        },
                        onCancel = { editingGroup = null },
                    )
                }
                nvrOpen -> NvrForm(
                    existingCount = settings.cameras.size,
                    tester = app.tester,
                    onCreate = { created, replace ->
                        val current = app.settings
                        val list = if (replace) created else current.cameras + created
                        app.update(current.copy(cameras = list.take(AppSettings.MAX_CAMERAS)))
                        nvrOpen = false
                    },
                    onCancel = { nvrOpen = false },
                )
                pane == Pane.CAMERAS -> CamerasPane(
                    settings = settings,
                    unreadablePasswordIds = app.unreadablePasswordIds,
                    saveProblem = app.saveProblem,
                    addFocus = addFocus,
                    onAdd = {
                        returnToList = true
                        editing = CameraConfig(name = "", mainUrl = "rtsp://") to true
                    },
                    onAddFromNvr = {
                        returnToList = true
                        nvrOpen = true
                    },
                    onEdit = {
                        returnToList = true
                        editing = it to false
                    },
                    onChange = { app.update(settings.copy(cameras = it)) },
                    onAddGroup = {
                        returnToList = true
                        editingGroup = CameraGroup(name = "", cameraIds = emptyList()) to true
                    },
                    onEditGroup = {
                        returnToList = true
                        editingGroup = it to false
                    },
                )
                pane == Pane.PHONE -> PhoneSetupPane(
                    app.phoneSetupUrl, app.phoneSetupAddress, app.phoneSetupCode, app.phoneSetupIdle,
                    keepConnected = settings.phoneControl,
                    onKeepConnected = { app.update(settings.copy(phoneControl = it)) },
                    onRetry = app::startPhoneSetup,
                )
                pane == Pane.LAYOUT -> ViewingPane(settings) { app.update(it) }
                pane == Pane.SECURITY -> SecurityPane(
                    hasPin = settings.hasPin,
                    onSet = { pinFlow = PinFlow.SET },
                    onRemove = { pinFlow = PinFlow.REMOVE },
                )
                else -> AboutPane(settings)
            }
        }
    }
}

@Composable
private fun NavItem(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
    onPressed: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused() // TV pattern: moving onto a section shows it
            }
            .onKeyEvent {
                if (it.type == KeyEventType.KeyUp && it.key.isSelect()) {
                    onPressed(); true
                } else false
            }
            .focusable()
            .background(
                when {
                    focused -> Palette.TextBright
                    selected -> Palette.Surface
                    else -> Palette.Background
                },
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 12.dp),
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Normal,
            color = if (focused) Palette.OnAccent else if (selected) Palette.TextBright else Palette.Quiet,
        )
    }
}

@Composable
private fun CamerasPane(
    settings: AppSettings,
    unreadablePasswordIds: Set<String>,
    saveProblem: String?,
    addFocus: FocusRequester,
    onAdd: () -> Unit,
    onAddFromNvr: () -> Unit,
    onEdit: (CameraConfig) -> Unit,
    onChange: (List<CameraConfig>) -> Unit,
    onAddGroup: () -> Unit,
    onEditGroup: (CameraGroup) -> Unit,
) {
    val cameras = settings.cameras
    val full = cameras.size >= AppSettings.MAX_CAMERAS
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                SectionTitle("Cameras", "${cameras.size} of ${AppSettings.MAX_CAMERAS} · the grid shows up to 9 per page")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvButton("Add from NVR", enabled = !full, onClick = onAddFromNvr)
                TvButton("Add camera", primary = true, enabled = !full, modifier = Modifier.focusRequester(addFocus), onClick = onAdd)
            }
        }

        saveProblem?.let { Hint(it, Palette.Error) }
        val unreadable = cameras.filter { it.id in unreadablePasswordIds }
        if (unreadable.isNotEmpty()) {
            Hint(
                "The saved password for ${unreadable.joinToString { it.name }} can't be read right now. " +
                    "If this stays after restarting the TV, edit the camera and type the password again.",
                Palette.Warning,
            )
        }

        if (cameras.isEmpty()) {
            Hint("No cameras yet. Use Add from NVR for a recorder, or Add camera for a single camera.")
            Hint("Tip: Phone setup in the menu lets you type the addresses on your phone instead.")
            return
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(cameras, key = { _, c -> c.id }) { i, camera ->
                CameraRow(
                    number = i + 1,
                    camera = camera,
                    passwordUnreadable = camera.id in unreadablePasswordIds,
                    canMoveUp = i > 0,
                    canMoveDown = i < cameras.lastIndex,
                    onToggle = { on -> onChange(cameras.map { if (it.id == camera.id) it.copy(enabled = on) else it }) },
                    onMove = { delta ->
                        val target = i + delta
                        if (target in cameras.indices) {
                            onChange(cameras.toMutableList().apply { add(target, removeAt(i)) })
                        }
                    },
                    onEdit = { onEdit(camera) },
                )
            }
            item(key = "groups-title") {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        SectionTitle("Groups", "A group shows only some cameras · switch with the group chip in the grid's header")
                    }
                    TvButton("Add group", enabled = settings.groups.size < AppSettings.MAX_GROUPS, onClick = onAddGroup)
                }
            }
            items(settings.groups, key = { "group-" + it.id }) { group ->
                val names = group.cameraIds.mapNotNull { id -> cameras.firstOrNull { it.id == id }?.name }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Palette.Surface, RoundedCornerShape(10.dp))
                        .border(1.dp, Palette.Border, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(group.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextBright)
                        Text(names.joinToString(", ").ifEmpty { "No cameras" }, fontSize = 9.sp, color = Palette.Quiet, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (group.id == settings.currentGroupId) Text("Showing", fontSize = 9.sp, color = Palette.Accent)
                    TvButton("Edit") { onEditGroup(group) }
                }
            }
        }
    }
}

@Composable
private fun CameraRow(
    number: Int,
    camera: CameraConfig,
    passwordUnreadable: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: (Boolean) -> Unit,
    onMove: (Int) -> Unit,
    onEdit: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(Palette.Surface, RoundedCornerShape(10.dp))
            .border(1.dp, Palette.Border, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text("$number", fontSize = 12.sp, color = Palette.Quiet, modifier = Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(camera.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = if (camera.enabled) Palette.TextBright else Palette.Quiet)
            Text(summary(camera), fontSize = 9.sp, color = Palette.Quiet, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (passwordUnreadable) Text("password?", fontSize = 9.sp, color = Palette.Warning)
        Text(if (camera.enabled) "Shown" else "Hidden", fontSize = 9.sp, color = Palette.Quiet)
        TvSwitch(camera.enabled, "Show ${camera.name} in grid", onToggle)
        TvButton("▲", enabled = canMoveUp, modifier = Modifier.width(40.dp)) { onMove(-1) }
        TvButton("▼", enabled = canMoveDown, modifier = Modifier.width(40.dp)) { onMove(+1) }
        TvButton("Edit", onClick = onEdit)
    }
}

/** "192.168.1.100 · main + grid · TCP" */
private fun summary(camera: CameraConfig): String {
    val host = camera.mainUrl.substringAfter("://").substringAfter('@').substringBefore('/')
    val streams = if (camera.subUrl.isBlank()) "main only" else "main + grid stream"
    return "$host · $streams · ${if (camera.useTcp) "TCP" else "UDP"}"
}

@Composable
private fun ViewingPane(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    val context = LocalContext.current
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
    ) {
        SectionTitle("Grid layout", "How the cameras are arranged when the app opens")
        TvChoice(
            listOf(
                LayoutPreference.AUTO to "Automatic",
                LayoutPreference.ONE to "1",
                LayoutPreference.FOUR to "2×2",
                LayoutPreference.SIX to "1+5",
                LayoutPreference.NINE to "3×3",
            ),
            settings.layout,
        ) { onChange(settings.copy(layout = it)) }
        Hint("Automatic picks 1, 2×2, 1+5 or 3×3 from the number of cameras. With 1 camera on screen it plays in full quality, with sound. In 1+5 the first camera of each page gets the big tile: reorder the list to choose it.")
        Hint("Sound in the grid: press 0 (or hold OK) on a camera to hear it; press again to mute.")
        Hint("The grid never shows more than 9 at once: this TV can't play more live streams smoothly. More cameras go on a second page.")

        Spacer(Modifier.height(8.dp))
        SectionTitle("Motion", "The grid watches its cameras for movement")
        TvChoice(
            listOf(MotionMode.OFF to "Off", MotionMode.HIGHLIGHT to "Highlight the camera", MotionMode.OPEN to "Open it full screen"),
            settings.motionMode,
        ) { onChange(settings.copy(motionMode = it)) }
        if (settings.motionMode != MotionMode.OFF) {
            Hint("Sensitivity")
            TvChoice(
                listOf(MotionSensitivity.LOW to "Low", MotionSensitivity.MEDIUM to "Medium", MotionSensitivity.HIGH to "High"),
                settings.motionSensitivity,
            ) { onChange(settings.copy(motionSensitivity = it)) }
        }
        Hint("Works for the cameras on screen in the grid. Rain, trees or headlights can count as movement: lower the sensitivity if it triggers too often.")
        if (settings.motionMode == MotionMode.OPEN) {
            Hint("A camera opened by motion goes back to the grid after 30 seconds if no button is pressed.")
        }

        Spacer(Modifier.height(8.dp))
        SectionTitle("Camera tour", "Shows each camera full screen in turn (▶ Tour in the grid, or the Play button)")
        TvChoice(
            listOf(5 to "5 s", 10 to "10 s", 20 to "20 s", 30 to "30 s", 60 to "1 min"),
            settings.tourSeconds,
        ) { onChange(settings.copy(tourSeconds = it)) }

        Spacer(Modifier.height(8.dp))
        SectionTitle("Floating camera window", "Keep a camera playing in a corner while you use other apps")
        val canFloat = remember(settings) { FloatingCamera.canShow(context) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TvSwitch(settings.pictureInPicture, "Floating camera window") { onChange(settings.copy(pictureInPicture = it)) }
            Text(if (settings.pictureInPicture) "On" else "Off", fontSize = 12.sp, color = Palette.Text)
        }
        if (settings.pictureInPicture) {
            Hint("Size")
            TvChoice(
                listOf(
                    FloatingSize.SMALL to "Small",
                    FloatingSize.MEDIUM to "Medium",
                    FloatingSize.LARGE to "Large",
                    FloatingSize.EXTRA_LARGE to "Half screen",
                ),
                settings.floatingSize,
            ) { onChange(settings.copy(floatingSize = it)) }
            Hint("Corner")
            TvChoice(
                listOf(
                    FloatingCorner.TOP_LEFT to "↖ Top left",
                    FloatingCorner.TOP_RIGHT to "↗ Top right",
                    FloatingCorner.BOTTOM_LEFT to "↙ Bottom left",
                    FloatingCorner.BOTTOM_RIGHT to "↘ Bottom right",
                ),
                settings.floatingCorner,
            ) { onChange(settings.copy(floatingCorner = it)) }
        }
        Hint("Press Home while a camera is open full screen: it keeps playing in the corner, without sound. From the grid, Home just leaves the app. Open SwannTV again to close the window.")
        if (canFloat) {
            Hint("Permission to show over other apps: granted.", Palette.Text)
        } else {
            Hint("Needs a one-time permission this TV has no menu for. On the computer, in Android Studio's Terminal, run:", Palette.Warning)
            Text("adb shell appops set com.albahadly.swanntv SYSTEM_ALERT_WINDOW allow", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextBright)
        }
        Hint("While it plays, 4K video in another app may fail on this TV: open SwannTV to close the window if that happens.")
    }
}

@Composable
private fun SecurityPane(hasPin: Boolean, onSet: () -> Unit, onRemove: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("PIN lock", if (hasPin) "On · Settings asks for the PIN" else "Off · anyone can open Settings")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvButton(if (hasPin) "Change PIN" else "Set PIN", primary = true, onClick = onSet)
            if (hasPin) TvButton("Remove PIN", danger = true, onClick = onRemove)
        }
        Hint("The PIN protects Settings only; the cameras can always be watched.")
        if (hasPin) Hint("Forgot it? Clearing the app's data in Android settings removes the PIN (and the camera list).")
    }
}

@Composable
private fun AboutPane(settings: AppSettings) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("About SwannTV", "Version ${BuildConfig.VERSION_NAME}")
        Hint("${settings.cameras.size} camera(s) saved, ${settings.cameras.count { it.enabled }} shown.")
        Hint("Passwords are encrypted with an Android Keystore key (hardware-backed on most TVs).")
        Hint("Diagnostics log: Android/data/com.albahadly.swanntv/files/swanntv-log.txt")
        Hint("Copy it to a computer with: adb pull /sdcard/Android/data/com.albahadly.swanntv/files/swanntv-log.txt")
    }
}
