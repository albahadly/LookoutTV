package com.albahadly.swanntv

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logs to logcat AND to a small rotating file on the TV, because TV log buffers are tiny.
 * Pull it with:
 *   adb pull /sdcard/Android/data/com.albahadly.swanntv/files/swanntv-log.txt
 * Never log passwords: pass anything containing a stream URL through [redact].
 */
object AppLog {
    private const val TAG = "SwannTV"
    private const val MAX_BYTES = 1_000_000L
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private var file: File? = null

    fun init(context: Context) {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        file = File(dir, "swanntv-log.txt")
        i("===== SwannTV ${BuildConfig.VERSION_NAME} started =====")
    }

    fun i(message: String) = write(Log.INFO, message, null)

    fun w(message: String, error: Throwable? = null) = write(Log.WARN, message, error)

    /** Replaces the user:password part of a stream URL with asterisks. */
    fun redact(url: String): String = url.replace(Regex("//[^/@]*@"), "//***@")

    /** Synchronized: the phone-setup server logs from its own threads (SimpleDateFormat isn't thread-safe). */
    @Synchronized
    private fun write(priority: Int, message: String, error: Throwable?) {
        val safeMessage = redact(message) // no login ever reaches logcat or the file
        Log.println(priority, TAG, safeMessage + (error?.let { "\n" + redact(Log.getStackTraceString(it)) } ?: ""))
        val f = file ?: return
        try {
            if (f.length() > MAX_BYTES) f.renameTo(File(f.parentFile, "swanntv-log.old.txt"))
            val level = if (priority == Log.WARN) "W" else "I"
            val cause = error?.let { "  | ${causeChain(it)}" } ?: ""
            f.appendText("${time.format(Date())} $level $safeMessage$cause\n")
        } catch (e: Exception) {
            Log.w(TAG, "Could not write log file", e)
        }
    }

    private fun causeChain(t: Throwable): String =
        generateSequence(t) { it.cause }.take(6)
            .joinToString(" <- ") { "${it.javaClass.simpleName}: ${redact(it.message.orEmpty()).take(160)}" +
                (it.stackTrace.firstOrNull()?.let { f -> " @${f.className.substringAfterLast('.')}.${f.methodName}:${f.lineNumber}" } ?: "") }
}
