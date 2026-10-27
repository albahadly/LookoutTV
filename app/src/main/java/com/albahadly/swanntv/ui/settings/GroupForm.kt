package com.albahadly.swanntv.ui.settings

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.albahadly.swanntv.data.CameraConfig
import com.albahadly.swanntv.data.CameraGroup
import com.albahadly.swanntv.ui.Palette

/** Add or edit a camera group: a name and which cameras belong to it, in list order. */
@Composable
fun GroupForm(
    initial: CameraGroup,
    isNew: Boolean,
    cameras: List<CameraConfig>,
    onSave: (CameraGroup) -> String?,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var chosen by remember { mutableStateOf(initial.cameraIds.toSet()) }
    var error by remember { mutableStateOf<String?>(null) }
    val firstField = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstField.requestFocus() }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
    ) {
        SectionTitle(if (isNew) "Add group" else "Edit ${initial.name}", "A group shows only its cameras in the grid")
        TvTextField("Name", name, { name = it }, Modifier.focusRequester(firstField), placeholder = "Front")
        Hint("Cameras in this group (shown in the order of the camera list):")
        cameras.forEach { camera ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TvSwitch(camera.id in chosen, camera.name) { on ->
                    chosen = if (on) chosen + camera.id else chosen - camera.id
                }
                Text(camera.name, fontSize = 12.sp, color = if (camera.enabled) Palette.Text else Palette.Quiet)
                if (!camera.enabled) Text("(hidden)", fontSize = 10.sp, color = Palette.Quiet)
            }
        }
        error?.let { Hint(it, Palette.Error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvButton("Save", primary = true) {
                error = when {
                    name.isBlank() -> "Give the group a name."
                    chosen.isEmpty() -> "Choose at least one camera."
                    else -> onSave(initial.copy(name = name.trim(), cameraIds = cameras.map { it.id }.filter { it in chosen }))
                }
            }
            TvButton("Cancel", onClick = onCancel)
            if (!isNew) {
                Spacer(Modifier.width(24.dp))
                TvButton("Delete group", danger = true, onClick = onDelete)
            }
        }
    }
}
