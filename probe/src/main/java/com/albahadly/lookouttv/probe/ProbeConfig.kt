package com.albahadly.lookouttv.probe

import android.net.Uri

data class Camera(val name: String, val channel: Int)

/** Swann NVR cameras. Credentials come from local.properties via BuildConfig. */
object ProbeConfig {

    val cameras = listOf(
        Camera("Front door", 1),
        Camera("Driveway", 2),
        Camera("Back yard", 3),
        Camera("Garage", 4),
    )

    /** main = true -> subtype=0 (full resolution), false -> subtype=1 (sub-stream). */
    fun url(camera: Camera, main: Boolean): String {
        val user = Uri.encode(BuildConfig.NVR_USER)
        val pass = Uri.encode(BuildConfig.NVR_PASSWORD)
        val channel = camera.channel.toString().padStart(2, '0')
        val subtype = if (main) 0 else 1
        return "rtsp://$user:$pass@${BuildConfig.NVR_HOST}:${BuildConfig.NVR_PORT}" +
            "/rtsp/streaming?channel=$channel&subtype=$subtype"
    }
}
