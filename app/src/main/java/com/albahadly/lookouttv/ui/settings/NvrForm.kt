package com.albahadly.lookouttv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.albahadly.lookouttv.data.AppSettings
import com.albahadly.lookouttv.data.CameraConfig
import com.albahadly.lookouttv.data.SettingsStore
import com.albahadly.lookouttv.playback.CameraTester
import com.albahadly.lookouttv.ui.Palette

/**
 * Stream path patterns of common recorders. {ch} = channel as two digits (01), {n} = as a
 * plain number (1). Swann's matches what this app was built and tested with.
 */
enum class NvrPreset(val label: String, val mainPath: String, val subPath: String) {
    SWANN("Swann", "/rtsp/streaming?channel={ch}&subtype=0", "/rtsp/streaming?channel={ch}&subtype=1"),
    HIKVISION("Hikvision", "/Streaming/Channels/{n}01", "/Streaming/Channels/{n}02"),
    DAHUA("Dahua / Lorex / Amcrest", "/cam/realmonitor?channel={n}&subtype=0", "/cam/realmonitor?channel={n}&subtype=1");

    fun url(host: String, port: String, channel: Int, main: Boolean): String {
        val path = (if (main) mainPath else subPath)
            .replace("{ch}", channel.toString().padStart(2, '0'))
            .replace("{n}", channel.toString())
        return "rtsp://$host:$port$path"
    }
}

/** Creates one camera per recorder channel from a single address and login. */
@Composable
fun NvrForm(
    existingCount: Int,
    tester: CameraTester,
    onCreate: (cameras: List<CameraConfig>, replace: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    var preset by remember { mutableStateOf(NvrPreset.SWANN) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("554") }
    var username by remember { mutableStateOf("admin") }
    var password by remember { mutableStateOf("") }
    var channels by remember { mutableIntStateOf(4) }
    var replace by remember { mutableStateOf(false) } // never delete cameras by default
    var confirmReplace by remember { mutableStateOf(false) }
    var testLine by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val firstField = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstField.requestFocus() }
    }
    DisposableEffect(Unit) { onDispose { tester.cancel() } }

    val room = if (replace) AppSettings.MAX_CAMERAS else AppSettings.MAX_CAMERAS - existingCount

    fun check(): String? = when {
        host.isBlank() -> "Enter the recorder's IP address."
        !SettingsStore.isValidHost(host) -> "Enter just the recorder's IP address or name, like 192.168.1.100 (no rtsp://, login, port or path)."
        port.toIntOrNull() !in 1..65535 -> "The port should be a number, usually 554."
        channels > room -> "Only $room more camera${if (room == 1) "" else "s"} fit (16 at most). Choose Replace or fewer channels."
        else -> null
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
    ) {
        SectionTitle("Add from NVR", "Enter the recorder once; a camera is created for each channel")

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Hint("Recorder brand")
            TvChoice(NvrPreset.entries.map { it to it.label }, preset) { preset = it }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvTextField(
                "IP address", host, { host = it.trim() }, Modifier.focusRequester(firstField),
                placeholder = "192.168.1.100", keyboardType = KeyboardType.Uri, width = 220.dp,
            )
            TvTextField("Port", port, { port = it.filter(Char::isDigit).take(5) }, keyboardType = KeyboardType.Number, width = 90.dp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvTextField("Username", username, { username = it }, width = 200.dp)
            TvTextField("Password", password, { password = it }, password = true, width = 200.dp)
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Hint("Number of channels")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TvButton("−", modifier = Modifier.width(44.dp)) { if (channels > 1) channels-- }
                Text("$channels", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Palette.Text,
                    modifier = Modifier.width(36.dp))
                TvButton("+", modifier = Modifier.width(44.dp)) { if (channels < AppSettings.MAX_CAMERAS) channels++ }
            }
        }

        if (existingCount > 0) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Hint("Your $existingCount existing camera${if (existingCount == 1) "" else "s"}")
                TvChoice(listOf(false to "Keep them and add", true to "Replace them"), replace) {
                    replace = it
                    confirmReplace = false
                }
            }
        }

        testLine?.let { (line, ok) -> Hint(line, if (ok) Palette.Text else Palette.Error) }
        error?.let { Hint(it, Palette.Error) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvButton("Test channel 1") {
                error = check()
                if (error != null) return@TvButton
                testLine = "Testing channel 1…" to true
                val url = SettingsStore.withCredentials(preset.url(host, port, 1, main = true), username, password)
                tester.test(url, useTcp = true) { result ->
                    testLine = when (result) {
                        is CameraTester.Result.Ok -> "Channel 1: OK, ${result.codec} ${result.resolution}" to true
                        is CameraTester.Result.Failed -> "Channel 1: ${result.reason}" to false
                    }
                }
            }
            val createLabel = if (confirmReplace) "Press again to replace $existingCount camera${if (existingCount == 1) "" else "s"}"
            else "Create $channels camera${if (channels == 1) "" else "s"}"
            TvButton(createLabel, primary = true, danger = confirmReplace) {
                error = check()
                if (error != null) return@TvButton
                if (replace && existingCount > 0 && !confirmReplace) {
                    confirmReplace = true // deleting the existing cameras needs a second press
                    return@TvButton
                }
                val cameras = (1..channels).map { ch ->
                    CameraConfig(
                        name = "Camera $ch",
                        mainUrl = preset.url(host, port, ch, main = true),
                        subUrl = preset.url(host, port, ch, main = false),
                        username = username.trim(),
                        password = password,
                    )
                }
                onCreate(cameras, replace)
            }
            TvButton("Cancel", onClick = onCancel)
        }
        Hint("Cameras are named Camera 1, 2, 3… Rename them with Edit afterwards.")
    }
}
