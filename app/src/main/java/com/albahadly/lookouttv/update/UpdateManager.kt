package com.albahadly.lookouttv.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.albahadly.lookouttv.AppLog
import com.albahadly.lookouttv.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Checks GitHub Releases for a newer Lookout TV, downloads the APK and hands it to Android's
 * package installer. A sideloaded app can't install itself silently: the installer always
 * asks once on the TV, so the flow is "new version → Update now / Later → one OK".
 *
 * Works with a public repository as is. For a private one, a GitHub token (fine-grained,
 * read-only on this repository's contents) entered in Settings is sent with the requests.
 * All state is Compose state read by the viewer's banner and the About pane.
 */
class UpdateManager(private val context: Context, private val scope: CoroutineScope) {

    data class Release(
        val version: String,
        val notes: String,
        val apkName: String,
        /** Download address: the public one, or the API asset address for a private repository. */
        val apkUrl: String,
        val apkBytes: Long,
        val sha256Url: String?,
    )

    sealed interface Status {
        object Idle : Status
        object Checking : Status
        object UpToDate : Status
        data class Downloading(val percent: Int) : Status
        object Installing : Status
        data class Failed(val message: String) : Status
    }

    private companion object {
        const val REPO = "albahadly/LookoutTV"
        const val CHECK_EVERY_MS = 60 * 60_000L
        const val RETRY_AFTER_MS = 5 * 60_000L
        const val SNOOZE_MS = 24 * 60 * 60_000L
        const val PREFS = "updates"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** A newer release than the installed version, once found (even if snoozed). */
    var available by mutableStateOf<Release?>(null)
        private set
    var status by mutableStateOf<Status>(Status.Idle)
        private set
    /** When the last check ran (wall clock), for the About pane. */
    var lastCheckedAt by mutableLongStateOf(prefs.getLong("lastCheckedAt", 0L))
        private set
    /** "Later": Compose state, so the banner goes at once. */
    private var snoozedVersion by mutableStateOf(prefs.getString("snoozedVersion", "").orEmpty())
    private var snoozedUntil by mutableLongStateOf(prefs.getLong("snoozedUntil", 0L))
    /** When [checkIfDue] may look again ([SystemClock.elapsedRealtime], which also runs while the TV is off). */
    private var nextCheckAt = 0L
    private var job: Job? = null

    /** Token for a private repository, or empty. Held in memory; the store keeps it encrypted. */
    var token: String = ""

    /** True when the banner should show: a release is available and "Later" hasn't hidden it. */
    val bannerVisible: Boolean
        get() {
            val release = available ?: return false
            if (status is Status.Downloading || status is Status.Installing) return true
            return !(snoozedVersion == release.version && System.currentTimeMillis() < snoozedUntil)
        }

    /**
     * Called when the app opens and regularly while it's on screen. Not kept across restarts:
     * [available] isn't either, so a newly started app always looks once.
     */
    fun checkIfDue(enabled: Boolean) {
        if (!enabled || status is Status.Installing) return
        if (SystemClock.elapsedRealtime() < nextCheckAt) return
        check()
    }

    /** Looks for a newer release now (Settings → About → Check now, or the phone page). */
    fun check() {
        if (status is Status.Checking || status is Status.Downloading) return
        status = Status.Checking
        job = scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { fetchLatest() } }
            lastCheckedAt = System.currentTimeMillis()
            prefs.edit().putLong("lastCheckedAt", lastCheckedAt).apply()
            // A failure is often just the TV's Wi-Fi still connecting after it wakes up.
            nextCheckAt = SystemClock.elapsedRealtime() + if (result.isSuccess) CHECK_EVERY_MS else RETRY_AFTER_MS
            if (System.currentTimeMillis() >= snoozedUntil) snoozedUntil = 0L // "Later" is over: show the banner again
            result.onSuccess { release ->
                if (release != null && isNewer(release.version, BuildConfig.VERSION_NAME)) {
                    AppLog.i("update available: ${release.version} (installed ${BuildConfig.VERSION_NAME})")
                    available = release
                    status = Status.Idle
                } else {
                    available = null
                    status = Status.UpToDate
                }
            }.onFailure { e ->
                AppLog.w("update check failed: ${e.message}")
                status = Status.Failed(friendly(e))
            }
        }
    }

    /** "Later": hide the banner for this version for a day. */
    fun snooze() {
        val release = available ?: return
        snoozedVersion = release.version
        snoozedUntil = System.currentTimeMillis() + SNOOZE_MS
        prefs.edit().putString("snoozedVersion", snoozedVersion).putLong("snoozedUntil", snoozedUntil).apply()
    }

    /** "Update now": download the APK, check it, and open the installer. */
    fun install() {
        val release = available ?: return
        if (status is Status.Downloading || status is Status.Installing) return
        if (!canInstall()) {
            status = Status.Failed("The TV doesn't allow Lookout TV to install updates yet. See Settings → About for the one-time permission.")
            return
        }
        status = Status.Downloading(0)
        job = scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() } // only ever one update on disk
                    val file = File(dir, release.apkName)
                    download(release.apkUrl, file, release.apkBytes) { percent ->
                        scope.launch { status = Status.Downloading(percent) }
                    }
                    release.sha256Url?.let { verifySha256(file, it) }
                    file
                }
            }
            result.onSuccess { file ->
                status = Status.Installing
                AppLog.i("update ${release.version} downloaded (${file.length()} bytes), opening the installer")
                runCatching { openInstaller(file) }.onFailure { e ->
                    AppLog.w("could not open the installer", e)
                    status = Status.Failed("The TV refused to open the installer: ${e.message}")
                }
            }.onFailure { e ->
                AppLog.w("update download failed: ${e.message}")
                status = Status.Failed(friendly(e))
            }
        }
    }

    /** Back from the installer (installed, or cancelled): the banner comes back if still older. */
    fun onResumed() {
        if (status is Status.Installing) status = Status.Idle
    }

    /** One line for the About pane and the phone page. */
    fun statusText(): String = when (val s = status) {
        Status.Idle -> available?.let { "Version ${it.version} is available (installed: ${BuildConfig.VERSION_NAME})." }
            ?: "Installed: ${BuildConfig.VERSION_NAME}."
        Status.Checking -> "Checking GitHub…"
        Status.UpToDate -> "Up to date: ${BuildConfig.VERSION_NAME} is the latest."
        is Status.Downloading -> "Downloading ${available?.version ?: ""}… ${s.percent}%"
        Status.Installing -> "Follow the installer on the TV."
        is Status.Failed -> s.message
    }

    /** Whether Android lets this app start installs (the "Install unknown apps" permission). */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens the TV's permission screen for installs, when it has one. False if it doesn't. */
    fun openInstallPermission(): Boolean = runCatching {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    // ------------------------------------------------------------------ GitHub

    private fun fetchLatest(): Release? {
        val connection = open("https://api.github.com/repos/$REPO/releases/latest", accept = "application/vnd.github+json")
        try {
            when (connection.responseCode) {
                200 -> Unit
                404 -> throw IllegalStateException(if (token.isEmpty()) "no-access" else "no-release")
                401, 403 -> throw IllegalStateException("denied")
                else -> throw IllegalStateException("http ${connection.responseCode}")
            }
            val json = JSONObject(connection.inputStream.bufferedReader().readText())
            val assets = json.optJSONArray("assets") ?: return null
            var apk: JSONObject? = null
            var sha: JSONObject? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name")
                if (name.endsWith(".apk")) apk = a
                if (name.endsWith(".apk.sha256")) sha = a
            }
            val apkAsset = apk ?: return null
            // A private repository's assets download only through their API address.
            fun url(asset: JSONObject) = if (token.isEmpty()) asset.optString("browser_download_url") else asset.optString("url")
            return Release(
                version = json.optString("tag_name").removePrefix("v").trim(),
                notes = json.optString("body").trim(),
                apkName = apkAsset.optString("name"),
                apkUrl = url(apkAsset),
                apkBytes = apkAsset.optLong("size"),
                sha256Url = sha?.let { url(it) },
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun download(url: String, target: File, expectedBytes: Long, onProgress: (Int) -> Unit) {
        val connection = open(url, accept = "application/octet-stream")
        try {
            if (connection.responseCode != 200) throw IllegalStateException("http ${connection.responseCode}")
            val total = if (expectedBytes > 0) expectedBytes else connection.contentLengthLong
            var read = 0L
            var lastPercent = -1
            connection.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        read += n
                        if (total > 0) {
                            val percent = (read * 100 / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) { lastPercent = percent; onProgress(percent) }
                        }
                    }
                }
            }
            if (expectedBytes > 0 && target.length() != expectedBytes) throw IllegalStateException("short download")
        } finally {
            connection.disconnect()
        }
    }

    private fun verifySha256(file: File, shaUrl: String) {
        val connection = open(shaUrl, accept = "application/octet-stream")
        val expected = try {
            if (connection.responseCode != 200) return // no checksum file: skip, the installer checks the signature
            connection.inputStream.bufferedReader().readText().trim().split(Regex("\\s+")).firstOrNull().orEmpty().lowercase()
        } finally {
            connection.disconnect()
        }
        if (expected.length != 64) return
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) throw IllegalStateException("checksum")
    }

    /**
     * Opens [url], following one redirect by hand: GitHub answers asset downloads with a
     * redirect to its file store, which rejects requests that still carry the token.
     */
    private fun open(url: String, accept: String): HttpURLConnection {
        var current = url
        var withAuth = true
        repeat(4) {
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("Accept", accept)
                setRequestProperty("User-Agent", "LookoutTV/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE})")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                if (withAuth && token.isNotEmpty() && current.contains("github.com")) setRequestProperty("Authorization", "Bearer $token")
            }
            val code = connection.responseCode
            if (code in 301..308) {
                val next = connection.getHeaderField("Location") ?: throw IllegalStateException("redirect without address")
                connection.disconnect()
                current = next
                withAuth = false
                return@repeat
            }
            return connection
        }
        throw IllegalStateException("too many redirects")
    }

    private fun openInstaller(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
    }

    private fun friendly(e: Throwable): String = when (e.message) {
        "no-access" -> "GitHub didn't show a release. If the repository is private, enter a GitHub token in Settings → About."
        "no-release" -> "No release has been published yet."
        "denied" -> "GitHub refused the token. Check it in Settings → About."
        "checksum" -> "The downloaded file didn't match its checksum, so it wasn't installed."
        "short download" -> "The download stopped early. Try again."
        else -> when (e) {
            is java.net.UnknownHostException -> "The TV can't reach GitHub. Is it online?"
            is java.net.SocketTimeoutException -> "GitHub didn't answer in time. Try again."
            else -> "Update failed: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** "1.2.0" newer than "1.1.9"; extra parts count as 0. */
    private fun isNewer(candidate: String, installed: String): Boolean {
        fun parts(v: String) = v.split('.', '-').mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
        val a = parts(candidate)
        val b = parts(installed)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
