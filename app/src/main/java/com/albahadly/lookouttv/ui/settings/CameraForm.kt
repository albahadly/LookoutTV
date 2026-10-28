package com.albahadly.lookouttv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.albahadly.lookouttv.data.CameraConfig
import androidx.tv.material3.Text
import androidx.compose.ui.unit.sp
import com.albahadly.lookouttv.data.CameraMotion
import com.albahadly.lookouttv.data.SettingsStore
import com.albahadly.lookouttv.playback.CameraTester
import com.albahadly.lookouttv.ui.Palette

/** Add or edit one camera. Nothing is saved until Save. */
@Composable
fun CameraForm(
    initial: CameraConfig,
    isNew: Boolean,
    tester: CameraTester,
    /** Saves the camera; returns an error to show instead, or null when saved. */
    onSave: (CameraConfig) -> String?,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var mainUrl by remember { mutableStateOf(initial.mainUrl) }
    var subUrl by remember { mutableStateOf(initial.subUrl) }
    var username by remember { mutableStateOf(initial.username) }
    var password by remember { mutableStateOf(initial.password) }
    /** False while the password field still holds the saved password. */
    var passwordTyped by remember { mutableStateOf(false) }
    var useTcp by remember { mutableStateOf(initial.useTcp) }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var motion by remember { mutableStateOf(initial.motion) }
    var motionBeep by remember { mutableStateOf(initial.motionBeep) }
    var fullQuality by remember { mutableStateOf(initial.fullQuality) }
    var testLines by remember { mutableStateOf<List<Pair<String, Boolean>>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val firstField = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstField.requestFocus() }
    }
    DisposableEffect(Unit) { onDispose { tester.cancel() } }

    /** Moves a login typed inside an address (rtsp://user:pass@host) into the login fields. */
    fun takeLoginFromAddresses() {
        val (main, mainUser, mainPass) = SettingsStore.splitCredentials(mainUrl.trim())
        val (sub, subUser, subPass) = SettingsStore.splitCredentials(subUrl.trim())
        mainUrl = main
        subUrl = sub
        (mainUser ?: subUser)?.let { if (it.isNotEmpty()) username = it }
        (mainPass ?: subPass)?.let { password = it; passwordTyped = true }
    }

    /** The saved password only goes to the address(es) it was saved with. */
    fun passwordCheck(): String? {
        if (passwordTyped || initial.password.isEmpty() || password != initial.password) return null
        val known = setOf(initial.mainUrl, initial.subUrl).filter { it.isNotBlank() }.map { SettingsStore.endpoint(it) }.toSet()
        val now = listOf(mainUrl, subUrl).filter { it.isNotBlank() }.map { SettingsStore.endpoint(it.trim()) }
        return if (now.all { it in known }) null else "The address changed: type the password again."
    }

    fun runTest() {
        takeLoginFromAddresses()
        error = validate(mainUrl, subUrl) ?: passwordCheck()
        if (error != null) return
        testLines = listOf("Testing main stream…" to true)
        tester.test(SettingsStore.withCredentials(mainUrl.trim(), username, password), useTcp) { main ->
            val mainLine = "Main: " + describe(main) to (main is CameraTester.Result.Ok)
            if (subUrl.isBlank()) {
                testLines = listOf(mainLine)
                return@test
            }
            testLines = listOf(mainLine, "Testing grid stream…" to true)
            tester.test(SettingsStore.withCredentials(subUrl.trim(), username, password), useTcp) { sub ->
                testLines = listOf(mainLine, "Grid: " + describe(sub) to (sub is CameraTester.Result.Ok))
            }
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
    ) {
        SectionTitle(if (isNew) "Add camera" else "Edit ${initial.name}", "Any camera or recorder with an RTSP stream")

        TvTextField("Name", name, { name = it }, Modifier.focusRequester(firstField), placeholder = "Front door")
        TvTextField(
            "Main stream (full quality)", mainUrl, { mainUrl = it },
            placeholder = "rtsp://192.168.1.20:554/stream1", keyboardType = KeyboardType.Uri,
        )
        TvTextField(
            "Grid stream (low resolution, optional)", subUrl, { subUrl = it },
            placeholder = "rtsp://192.168.1.20:554/stream2", keyboardType = KeyboardType.Uri,
        )
        Hint("Without a grid stream the main stream is used in the grid too, which costs more on the TV.")

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvTextField("Username", username, { username = it }, width = 200.dp)
            TvTextField("Password", password, { password = it; passwordTyped = true }, password = true, width = 200.dp)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Hint("Connection")
                TvChoice(listOf(true to "TCP (reliable)", false to "UDP"), useTcp) { useTcp = it }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Hint("Show in grid")
                TvSwitch(enabled, "Show in grid") { enabled = it }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Hint("Always full quality in the grid")
                TvSwitch(fullQuality, "Always full quality in the grid") { fullQuality = it }
            }
        }
        Hint("Full quality in the grid plays the main stream in the 2×2 and 1+5 layouts (one camera per page; 3×3 keeps the grid stream). If the TV can't decode it next to the other tiles, the camera falls back to its grid stream.")

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Hint("Motion detection for this camera")
            TvChoice(
                listOf(
                    CameraMotion.DEFAULT to "As in Viewing settings",
                    CameraMotion.OFF to "Off",
                    CameraMotion.LOW to "Low",
                    CameraMotion.MEDIUM to "Medium",
                    CameraMotion.HIGH to "High",
                ),
                motion,
            ) { motion = it }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TvSwitch(motionBeep, "Beep on motion") { motionBeep = it }
            Text("Beep on the TV when this camera sees movement", fontSize = 11.sp, color = Palette.Text)
        }

        testLines.forEach { (line, ok) -> Hint(line, if (ok) Palette.Text else Palette.Error) }
        if (testLines.isNotEmpty() && !useTcp) {
            Hint("Note: with UDP the test checks the connection only; a firewall can still block the video.")
        }
        error?.let { Hint(it, Palette.Error) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvButton("Test", onClick = ::runTest)
            TvButton("Save", primary = true) {
                takeLoginFromAddresses()
                error = validate(mainUrl, subUrl) ?: passwordCheck() ?: if (name.isBlank()) "Give the camera a name." else null
                if (error == null) {
                    error = onSave(
                        initial.copy(
                            name = name.trim(),
                            mainUrl = mainUrl.trim(),
                            subUrl = subUrl.trim(),
                            username = username.trim(),
                            password = password,
                            useTcp = useTcp,
                            enabled = enabled,
                            motion = motion,
                            motionBeep = motionBeep,
                            fullQuality = fullQuality,
                        )
                    )
                }
            }
            TvButton("Cancel", onClick = onCancel)
            if (!isNew) {
                Spacer(Modifier.width(24.dp))
                TvButton("Delete camera", danger = true, onClick = onDelete)
            }
        }
    }
}

private fun validate(mainUrl: String, subUrl: String): String? = when {
    mainUrl.any { it.isWhitespace() } || subUrl.any { it.isWhitespace() } -> "Addresses can't contain spaces."
    !mainUrl.trim().startsWith("rtsp://", ignoreCase = true) -> "The main stream must start with rtsp://"
    mainUrl.trim().length <= "rtsp://".length -> "Enter the main stream address, e.g. rtsp://192.168.1.20:554/stream1"
    subUrl.isNotBlank() && !subUrl.trim().startsWith("rtsp://", ignoreCase = true) ->
        "The grid stream must start with rtsp:// (or be left empty)"
    else -> null
}

private fun describe(result: CameraTester.Result): String = when (result) {
    is CameraTester.Result.Ok -> "OK, ${result.codec} ${result.resolution} (answered in ${result.millis} ms)"
    is CameraTester.Result.Failed -> result.reason
}
