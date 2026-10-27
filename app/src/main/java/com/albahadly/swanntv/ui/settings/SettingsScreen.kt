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
import com.albahadly.swanntv.data.LayoutPreference
import com.albahadly.swanntv.ui.AppController
import com.albahadly.swanntv.ui.Palette

private enum class Pane(val label: String) {
    CAMERAS("Cameras"), PHONE("Phone setup"), LAYOUT("Layout"), SECURITY("PIN lock"), ABOUT("About")
}

private enum class PinFlow { SET, REMOVE }

/** Settings, opened from the grid's header. Every change is saved at once. */
@Composable
fun SettingsScreen(app: AppController) {
    val settings = app.settings
    var pane by remember { mutableStateOf(Pane.CAMERAS) }
    var editing by remember { mutableStateOf<Pair<CameraConfig, Boolean>?>(null) } // camera, isNew
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
            nvrOpen -> nvrOpen = false
            else -> app.closeSettings()
        }
    }

    // One place decides focus: on opening Settings and after a PIN screen, the current menu
    // item; after closing a camera form, back on the camera list.
    LaunchedEffect(pinFlow, editing == null && !nvrOpen) {
        if (pinFlow != null || editing != null || nvrOpen) return@LaunchedEffect
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
                    selected = p == pane && editing == null && !nvrOpen,
                    modifier = Modifier.focusRequester(paneFocus.getValue(p)),
                ) {
                    // Moving onto the menu while a form is open must not throw away what was typed.
                    if (editing == null && !nvrOpen) pane = p
                }
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
                )
                pane == Pane.PHONE -> PhoneSetupPane(app.phoneSetupUrl, app.phoneSetupAddress, app.phoneSetupCode, app.phoneSetupIdle, onRetry = app::startPhoneSetup)
                pane == Pane.LAYOUT -> LayoutPane(settings.layout) { app.update(settings.copy(layout = it)) }
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
private fun NavItem(label: String, selected: Boolean, modifier: Modifier = Modifier, onSelect: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onSelect() // TV pattern: moving onto a section shows it
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
    addFocus: FocusRequester,
    onAdd: () -> Unit,
    onAddFromNvr: () -> Unit,
    onEdit: (CameraConfig) -> Unit,
    onChange: (List<CameraConfig>) -> Unit,
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
        }
    }
}

@Composable
private fun CameraRow(
    number: Int,
    camera: CameraConfig,
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
private fun LayoutPane(current: LayoutPreference, onChange: (LayoutPreference) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Grid layout", "How the cameras are arranged when the app opens")
        TvChoice(
            listOf(
                LayoutPreference.AUTO to "Automatic",
                LayoutPreference.ONE to "1",
                LayoutPreference.FOUR to "2×2",
                LayoutPreference.NINE to "3×3",
            ),
            current,
            onChange,
        )
        Hint("Automatic picks 1, 2×2 or 3×3 from the number of cameras. You can also switch layout from the grid's header.")
        Hint("The grid never shows more than 9 at once: this TV can't play more live streams smoothly. More cameras go on a second page.")
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
        Hint("Passwords are encrypted with a key kept in the TV's secure hardware storage.")
        Hint("Diagnostics log: Android/data/com.albahadly.swanntv/files/swanntv-log.txt")
        Hint("Copy it to a computer with: adb pull /sdcard/Android/data/com.albahadly.swanntv/files/swanntv-log.txt")
    }
}
