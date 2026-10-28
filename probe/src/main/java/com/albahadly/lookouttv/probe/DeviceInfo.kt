package com.albahadly.lookouttv.probe

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import java.util.Locale

/** Reads the TV's chip, memory and video decoder limits. */
object DeviceInfo {

    const val AVC = "video/avc"
    const val HEVC = "video/hevc"

    fun decoders(mime: String): List<MediaCodecInfo> =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }

    fun isHardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated
        else isHardwareName(info.name)

    fun isHardwareName(name: String): Boolean =
        !(name.startsWith("c2.android.") || name.startsWith("OMX.google.") || name.contains(".sw."))

    /** Max simultaneous instances the first hardware decoder reports for [mime], or null. */
    fun maxHardwareInstances(mime: String): Int? =
        decoders(mime).firstOrNull { isHardware(it) }
            ?.getCapabilitiesForType(mime)?.maxSupportedInstances

    private fun soc(): String =
        if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}"
        else Build.HARDWARE

    private fun ramGb(context: Context): Double {
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.totalMem / 1_000_000_000.0
    }

    /** One line for the top bar. */
    fun summary(context: Context): String {
        val avc = maxHardwareInstances(AVC)?.toString() ?: "?"
        val hevc = maxHardwareInstances(HEVC)?.toString() ?: "?"
        return String.format(
            Locale.US,
            "%s %s · SoC %s · RAM %.1f GB · Android %s · ABI %s · HW decoders H.264 x%s, H.265 x%s",
            Build.MANUFACTURER, Build.MODEL, soc(), ramGb(context), Build.VERSION.RELEASE,
            Build.SUPPORTED_ABIS.firstOrNull() ?: "?", avc, hevc,
        )
    }

    /** Full multi-line report for the Device info screen and logcat. */
    fun report(context: Context): String = buildString {
        val am = context.getSystemService(ActivityManager::class.java)
        val display = context.getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
        val mode = display?.mode

        appendLine("DEVICE")
        appendLine("  Model:        ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("  SoC:          ${soc()}   hardware=${Build.HARDWARE} board=${Build.BOARD}")
        appendLine(String.format(Locale.US, "  RAM:          %.2f GB total, app heap %d MB, low-RAM device: %s",
            ramGb(context), am.memoryClass, am.isLowRamDevice))
        appendLine("  Android:      ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("  ABIs:         ${Build.SUPPORTED_ABIS.joinToString()}")
        if (mode != null) {
            appendLine(String.format(Locale.US, "  Display:      %dx%d @ %.0f Hz",
                mode.physicalWidth, mode.physicalHeight, mode.refreshRate))
        }
        appendLine()

        for (mime in listOf(AVC, HEVC)) {
            appendLine(if (mime == AVC) "H.264 DECODERS" else "H.265 DECODERS")
            val list = decoders(mime)
            if (list.isEmpty()) appendLine("  (none)")
            for (info in list) {
                val caps = info.getCapabilitiesForType(mime)
                val vc = caps.videoCapabilities ?: continue
                val hw = if (isHardware(info)) "HW" else "SW"
                appendLine("  ${info.name}  [$hw]")
                appendLine("    max instances: ${caps.maxSupportedInstances}" +
                    "   max size: ${vc.supportedWidths.upper}x${vc.supportedHeights.upper}")
                appendLine("    achievable fps  640x360: ${fps(vc, 640, 360)}" +
                    "   1920x1080: ${fps(vc, 1920, 1080)}   2560x1440: ${fps(vc, 2560, 1440)}" +
                    "   3840x2160: ${fps(vc, 3840, 2160)}")
                if (Build.VERSION.SDK_INT >= 29) {
                    val pts = vc.supportedPerformancePoints
                    if (!pts.isNullOrEmpty()) {
                        appendLine("    performance points: ${pts.take(6).joinToString()}")
                    }
                }
            }
            appendLine()
        }
    }

    private fun fps(vc: MediaCodecInfo.VideoCapabilities, w: Int, h: Int): String = try {
        if (!vc.isSizeSupported(w, h)) "unsupported"
        else vc.getAchievableFrameRatesFor(w, h)
            ?.let { String.format(Locale.US, "%.0f-%.0f", it.lower, it.upper) } ?: "n/a"
    } catch (e: IllegalArgumentException) {
        "n/a"
    }
}
