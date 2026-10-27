package com.albahadly.swanntv.model

import com.albahadly.swanntv.data.AppSettings
import com.albahadly.swanntv.data.SettingsStore

/**
 * A camera as the viewer plays it: URLs ready to play (credentials included), in screen order.
 * Built from the saved [com.albahadly.swanntv.data.CameraConfig] list; disabled cameras are left out.
 */
data class Camera(
    /** Position in the viewer's list, 0-based. */
    val index: Int,
    val name: String,
    val mainUrl: String,
    /** Grid stream; the main stream when the camera has no sub-stream. */
    val subUrl: String,
    val useTcp: Boolean,
) {
    /** The number shown on screen and used by the remote's number keys. */
    val number: Int get() = index + 1
}

object CameraRepository {

    fun fromSettings(settings: AppSettings): List<Camera> =
        settings.cameras
            .filter { it.enabled && it.mainUrl.isNotBlank() }
            .take(AppSettings.MAX_CAMERAS)
            .mapIndexed { i, c ->
                val main = SettingsStore.withCredentials(c.mainUrl, c.username, c.password)
                val sub = if (c.subUrl.isBlank()) main
                else SettingsStore.withCredentials(c.subUrl, c.username, c.password)
                Camera(index = i, name = c.name.ifBlank { "Camera ${i + 1}" }, mainUrl = main, subUrl = sub, useTcp = c.useTcp)
            }
}
