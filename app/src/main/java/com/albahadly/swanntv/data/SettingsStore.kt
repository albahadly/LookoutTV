package com.albahadly.swanntv.data

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import com.albahadly.swanntv.AppLog
import com.albahadly.swanntv.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** One saved camera. URLs are stored without credentials; they are added when playing. */
data class CameraConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val mainUrl: String,
    /** Low-res stream for the grid. Empty = use the main stream in the grid too. */
    val subUrl: String = "",
    val username: String = "",
    val password: String = "",
    /** RTSP over TCP (reliable, the default) or UDP. */
    val useTcp: Boolean = true,
    val enabled: Boolean = true,
)

/** Grid layout preference; AUTO picks by camera count. */
enum class LayoutPreference { AUTO, ONE, FOUR, NINE }

/** What the viewer does when it sees movement on a grid camera. */
enum class MotionMode { OFF, HIGHLIGHT, OPEN }

/** How much of the picture must change to count as movement. */
enum class MotionSensitivity(val changedFraction: Float) { LOW(0.06f), MEDIUM(0.03f), HIGH(0.015f) }

data class AppSettings(
    val cameras: List<CameraConfig> = emptyList(),
    val layout: LayoutPreference = LayoutPreference.AUTO,
    /** SHA-256 of the PIN (with a per-install salt), or empty when no PIN is set. */
    val pinHash: String = "",
    val pinSalt: String = "",
    /** Home pressed while watching: keep the camera playing in a small window. */
    val pictureInPicture: Boolean = false,
    val motionMode: MotionMode = MotionMode.HIGHLIGHT,
    val motionSensitivity: MotionSensitivity = MotionSensitivity.MEDIUM,
    /** Camera tour: seconds on each camera. */
    val tourSeconds: Int = 10,
) {
    val hasPin: Boolean get() = pinHash.isNotEmpty()

    fun withPin(pin: String?): AppSettings {
        if (pin.isNullOrEmpty()) return copy(pinHash = "", pinSalt = "")
        val salt = UUID.randomUUID().toString()
        return copy(pinHash = hash(pin, salt), pinSalt = salt)
    }

    fun pinMatches(pin: String): Boolean = hasPin && hash(pin, pinSalt) == pinHash

    companion object {
        const val MAX_CAMERAS = 16

        private fun hash(pin: String, salt: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest("$salt:$pin".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

/**
 * Saves settings as JSON in the app's private storage. Passwords are encrypted with
 * [SecretBox]; nothing in the file is usable off this TV.
 *
 * Writes go through [AtomicFile] (synced to disk, with a backup), so a power cut never
 * leaves a broken file. A password that can't be decrypted right now keeps its saved
 * ciphertext instead of being overwritten with nothing.
 */
class SettingsStore(context: Context) {

    private val dir = context.filesDir
    private val file = AtomicFile(File(dir, "settings.json"))

    /** Camera id -> (plain password, its ciphertext): avoids re-encrypting unchanged passwords. */
    private val sealedCache = HashMap<String, Pair<String, String>>()
    /** Camera id -> ciphertext that couldn't be decrypted; written back untouched. */
    private val undecryptable = HashMap<String, String>()

    fun load(): AppSettings {
        if (!file.baseFile.exists() && !File(dir, "settings.json.bak").exists()) {
            val seeded = AppSettings(cameras = camerasFromBuild())
            save(seeded)
            AppLog.i("first run: imported ${seeded.cameras.size} camera(s) from the build settings")
            return seeded
        }
        return try {
            parse(JSONObject(String(file.readFully(), Charsets.UTF_8)))
        } catch (e: Exception) {
            // Keep the broken file for inspection rather than overwriting it on the next save.
            AppLog.w("settings file unreadable; kept as settings.bad.json, starting from the build settings", e)
            runCatching { file.baseFile.copyTo(File(dir, "settings.bad.json"), overwrite = true) }
            AppSettings(cameras = camerasFromBuild())
        }
    }

    fun save(settings: AppSettings) {
        val json = JSONObject()
            .put("version", 1)
            .put("layout", settings.layout.name)
            .put("pinHash", settings.pinHash)
            .put("pinSalt", settings.pinSalt)
            .put("pictureInPicture", settings.pictureInPicture)
            .put("motionMode", settings.motionMode.name)
            .put("motionSensitivity", settings.motionSensitivity.name)
            .put("tourSeconds", settings.tourSeconds)
            .put("cameras", JSONArray().apply {
                settings.cameras.forEach { c ->
                    put(JSONObject()
                        .put("id", c.id)
                        .put("name", c.name)
                        .put("mainUrl", c.mainUrl)
                        .put("subUrl", c.subUrl)
                        .put("username", c.username)
                        .put("password", sealedPassword(c))
                        .put("useTcp", c.useTcp)
                        .put("enabled", c.enabled))
                }
            })
        val out = file.startWrite()
        try {
            out.write(json.toString(2).toByteArray(Charsets.UTF_8))
            file.finishWrite(out) // fsync, then replace; the previous file stays as backup until then
        } catch (e: Exception) {
            file.failWrite(out)
            AppLog.w("could not save settings", e)
        }
    }

    /** The ciphertext to store for [c]'s password, never losing a saved one. */
    private fun sealedPassword(c: CameraConfig): String {
        if (c.password.isEmpty()) return undecryptable[c.id] ?: ""
        sealedCache[c.id]?.let { (plain, sealed) -> if (plain == c.password) return sealed }
        val sealed = SecretBox.encrypt(c.password)
            ?: return sealedCache[c.id]?.second ?: undecryptable[c.id] ?: "" // Keystore hiccup: keep the old one
        sealedCache[c.id] = c.password to sealed
        undecryptable.remove(c.id)
        return sealed
    }

    private fun parse(json: JSONObject): AppSettings {
        val array = json.optJSONArray("cameras") ?: JSONArray()
        val cameras = (0 until array.length()).map { i ->
            val c = array.getJSONObject(i)
            val id = c.optString("id").ifEmpty { UUID.randomUUID().toString() }
            val sealed = c.optString("password")
            val plain = SecretBox.decrypt(sealed)
            if (plain == null) {
                undecryptable[id] = sealed // keep it; written back until it decrypts or is replaced
            } else if (sealed.isNotEmpty()) {
                sealedCache[id] = plain to sealed
            }
            CameraConfig(
                id = id,
                name = c.optString("name", "Camera ${i + 1}"),
                mainUrl = c.optString("mainUrl"),
                subUrl = c.optString("subUrl"),
                username = c.optString("username"),
                password = plain ?: "",
                useTcp = c.optBoolean("useTcp", true),
                enabled = c.optBoolean("enabled", true),
            )
        }.take(AppSettings.MAX_CAMERAS)
        return AppSettings(
            cameras = cameras,
            layout = runCatching { LayoutPreference.valueOf(json.optString("layout")) }
                .getOrDefault(LayoutPreference.AUTO),
            pinHash = json.optString("pinHash"),
            pinSalt = json.optString("pinSalt"),
            pictureInPicture = json.optBoolean("pictureInPicture", false),
            motionMode = runCatching { MotionMode.valueOf(json.optString("motionMode")) }.getOrDefault(MotionMode.HIGHLIGHT),
            motionSensitivity = runCatching { MotionSensitivity.valueOf(json.optString("motionSensitivity")) }
                .getOrDefault(MotionSensitivity.MEDIUM),
            tourSeconds = json.optInt("tourSeconds", 10).coerceIn(3, 120),
        )
    }

    /** The cameras configured in local.properties at build time (first run only). */
    private fun camerasFromBuild(): List<CameraConfig> {
        if (BuildConfig.NVR_HOST.isBlank()) return emptyList()
        val base = "rtsp://${BuildConfig.NVR_HOST}:${BuildConfig.NVR_PORT}"
        return BuildConfig.NVR_NAMES.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(AppSettings.MAX_CAMERAS)
            .mapIndexed { i, name ->
                val ch = (i + 1).toString().padStart(2, '0')
                CameraConfig(
                    name = name,
                    mainUrl = base + BuildConfig.NVR_MAIN_PATH.replace("{ch}", ch),
                    subUrl = base + BuildConfig.NVR_SUB_PATH.replace("{ch}", ch),
                    username = BuildConfig.NVR_USER,
                    // The password is never compiled into the app: enter it in Settings.
                )
            }
    }

    companion object {
        /**
         * Splits "rtsp://user:pass@host/..." into the address without the login, and the login.
         * Logins are always kept in their own fields (encrypted), never inside the address.
         */
        fun splitCredentials(url: String): Triple<String, String?, String?> {
            val schemeEnd = url.indexOf("://")
            if (schemeEnd < 0) return Triple(url, null, null)
            val hostStart = schemeEnd + 3
            val pathStart = url.indexOfAny(charArrayOf('/', '?'), hostStart).let { if (it < 0) url.length else it }
            val authority = url.substring(hostStart, pathStart)
            val at = authority.lastIndexOf('@')
            if (at < 0) return Triple(url, null, null)
            val userInfo = authority.substring(0, at)
            val user = Uri.decode(userInfo.substringBefore(':'))
            val pass = if (':' in userInfo) Uri.decode(userInfo.substringAfter(':')) else null
            return Triple(url.substring(0, hostStart) + authority.substring(at + 1) + url.substring(pathStart), user, pass)
        }

        /** "host:port" of an address (port 554 if not given), lower case, or null. */
        fun endpoint(url: String): String? {
            val authority = url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringAfterLast('@').lowercase()
            if (authority.isEmpty()) return null
            return if (':' in authority) authority else "$authority:554"
        }

        /** rtsp://host/... + user/password -> rtsp://user:password@host/... (unless already present). */
        fun withCredentials(url: String, username: String, password: String): String {
            if (username.isEmpty()) return url
            val schemeEnd = url.indexOf("://")
            if (schemeEnd < 0) return url
            val hostStart = schemeEnd + 3
            val pathStart = url.indexOf('/', hostStart).let { if (it < 0) url.length else it }
            if (url.substring(hostStart, pathStart).contains('@')) return url // typed in already
            val userInfo = Uri.encode(username) + if (password.isNotEmpty()) ":" + Uri.encode(password) else ""
            return url.substring(0, hostStart) + userInfo + "@" + url.substring(hostStart)
        }
    }
}
