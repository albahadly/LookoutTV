package com.albahadly.swanntv.model

import com.albahadly.swanntv.data.AppSettings
import com.albahadly.swanntv.data.CameraMotion
import com.albahadly.swanntv.data.MotionMode
import com.albahadly.swanntv.data.MotionSensitivity
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
    /** The saved camera's id (for the phone remote and groups). */
    val id: String = "",
    /** Fraction of the picture that must change for motion, or null when motion is off for it. */
    val motionThreshold: Float? = null,
    val motionBeep: Boolean = false,
    /** Play the main stream in the grid too (2×2 and 1+5 layouts). */
    val preferMain: Boolean = false,
) {
    /** True when the grid stream is a separate, lighter stream. */
    val hasSubStream: Boolean get() = subUrl != mainUrl

    /** The number shown on screen and used by the remote's number keys. */
    val number: Int get() = index + 1
}

object CameraRepository {

    fun fromSettings(settings: AppSettings): List<Camera> =
        settings.shownCameras()
            .filter { it.mainUrl.isNotBlank() }
            .take(AppSettings.MAX_CAMERAS)
            .mapIndexed { i, c ->
                val main = SettingsStore.withCredentials(c.mainUrl, c.username, c.password)
                val sub = if (c.subUrl.isBlank()) main
                else SettingsStore.withCredentials(c.subUrl, c.username, c.password)
                val threshold = when (c.motion) {
                    CameraMotion.DEFAULT -> if (settings.motionMode == MotionMode.OFF) null else settings.motionSensitivity.changedFraction
                    CameraMotion.OFF -> null
                    CameraMotion.LOW -> MotionSensitivity.LOW.changedFraction
                    CameraMotion.MEDIUM -> MotionSensitivity.MEDIUM.changedFraction
                    CameraMotion.HIGH -> MotionSensitivity.HIGH.changedFraction
                }
                Camera(
                    index = i,
                    name = c.name.ifBlank { "Camera ${i + 1}" },
                    mainUrl = main,
                    subUrl = sub,
                    useTcp = c.useTcp,
                    id = c.id,
                    motionThreshold = threshold,
                    motionBeep = c.motionBeep,
                    preferMain = c.fullQuality,
                )
            }
}
