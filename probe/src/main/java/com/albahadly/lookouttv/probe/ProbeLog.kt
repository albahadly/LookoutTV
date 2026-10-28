package com.albahadly.lookouttv.probe

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes results to logcat AND to a file on the TV, because TV log buffers are small.
 * Pull it with:
 *   adb pull /sdcard/Android/data/com.albahadly.lookouttv.probe/files/probe-log.txt
 */
object ProbeLog {
    private const val TAG = "SwannProbe"
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var file: File? = null

    fun init(context: Context) {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        file = File(dir, "probe-log.txt")
        write("========== Probe started ${Date()} ==========")
    }

    fun write(message: String) {
        Log.i(TAG, message)
        try {
            file?.appendText("${time.format(Date())}  $message\n")
        } catch (e: Exception) {
            Log.w(TAG, "Could not write log file", e)
        }
    }
}
