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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
    /** Motion detection for this camera: the global setting, off, or its own sensitivity. */
    val motion: CameraMotion = CameraMotion.DEFAULT,
    /** Beep on the TV when this camera sees movement. */
    val motionBeep: Boolean = false,
    /** Play the full-quality main stream in the grid too (2×2 and 1+5 layouts; not in 3×3). */
    val fullQuality: Boolean = false,
)

/** Per-camera motion setting. */
enum class CameraMotion { DEFAULT, OFF, LOW, MEDIUM, HIGH }

/** A named subset of the cameras, shown on its own in the grid. */
data class CameraGroup(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val cameraIds: List<String>,
)

/** Grid layout preference; AUTO picks by camera count. SIX is one big tile and five small. */
enum class LayoutPreference { AUTO, ONE, FOUR, SIX, NINE }

/** What the viewer does when it sees movement on a grid camera. */
enum class MotionMode { OFF, HIGHLIGHT, OPEN }

/** Floating camera window size, as a share of the screen width. */
enum class FloatingSize(val widthFraction: Float) { SMALL(0.2f), MEDIUM(0.28f), LARGE(0.38f), EXTRA_LARGE(0.5f) }

/** Which corner of the screen the floating camera window sits in. */
enum class FloatingCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

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
    val floatingSize: FloatingSize = FloatingSize.MEDIUM,
    val floatingCorner: FloatingCorner = FloatingCorner.BOTTOM_RIGHT,
    val groups: List<CameraGroup> = emptyList(),
    /** The group the grid shows, or empty for all cameras. */
    val currentGroupId: String = "",
    /** Keep the phone page connected (TV remote, log) for a while after leaving Settings. */
    val phoneControl: Boolean = true,
    /** Look for a new release on GitHub once a day and offer to install it. */
    val updateCheck: Boolean = true,
    /** GitHub token for a private repository (fine-grained, read-only); stored encrypted. */
    val githubToken: String = "",
) {
    val hasPin: Boolean get() = pinHash.isNotEmpty()

    val currentGroup: CameraGroup? get() = groups.firstOrNull { it.id == currentGroupId }

    /** Groups never point at cameras that are gone, and the current group must exist. */
    fun normalized(): AppSettings {
        val ids = cameras.map { it.id }.toSet()
        val cleanGroups = groups.map { g -> g.copy(cameraIds = g.cameraIds.filter { it in ids }) }
        return copy(groups = cleanGroups, currentGroupId = currentGroupId.takeIf { id -> cleanGroups.any { it.id == id } } ?: "")
    }

    /** The cameras the grid shows: the current group's, or all. Enabled cameras only. */
    fun shownCameras(): List<CameraConfig> = groupCameras() ?: cameras.filter { it.enabled }

    /** The current group's enabled cameras, or null when there is no group or it has none left. */
    fun groupCameras(): List<CameraConfig>? {
        val group = currentGroup ?: return null
        val byId = cameras.associateBy { it.id }
        return group.cameraIds.mapNotNull { byId[it] }.filter { it.enabled }.ifEmpty { null }
    }

    fun withPin(pin: String?): AppSettings {
        if (pin.isNullOrEmpty()) return copy(pinHash = "", pinSalt = "")
        val salt = UUID.randomUUID().toString()
        return copy(pinHash = hash(pin, salt), pinSalt = salt)
    }

    fun pinMatches(pin: String): Boolean = hasPin && hash(pin, pinSalt) == pinHash

    companion object {
        const val MAX_CAMERAS = 16
        const val MAX_GROUPS = 8

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
    /** The host:port endpoints a kept ciphertext belongs to: it must never follow a new address. */
    private val undecryptableEndpoints = HashMap<String, Set<String?>>()

    private var migratedOnLoad = false

    /** Ids of cameras whose saved password can't be read right now (Settings warns about them). */
    var unreadablePasswordIds by mutableStateOf<Set<String>>(emptySet())
        private set
    /** Why the last save didn't fully work, or null (Settings shows it). */
    var saveProblem by mutableStateOf<String?>(null)
        private set

    fun load(): AppSettings {
        if (!file.baseFile.exists() && !File(dir, "settings.json.bak").exists()) {
            val seeded = AppSettings(cameras = camerasFromBuild())
            save(seeded)
            AppLog.i("first run: imported ${seeded.cameras.size} camera(s) from the build settings")
            return seeded
        }
        return try {
            parse(JSONObject(String(file.readFully(), Charsets.UTF_8))).also { if (migratedOnLoad) save(it) }
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
            .put("floatingSize", settings.floatingSize.name)
            .put("floatingCorner", settings.floatingCorner.name)
            .put("currentGroupId", settings.currentGroupId)
            .put("phoneControl", settings.phoneControl)
            .put("updateCheck", settings.updateCheck)
            .put("githubToken", sealedToken(settings.githubToken))
            .put("groups", JSONArray().apply {
                settings.groups.forEach { g ->
                    put(JSONObject().put("id", g.id).put("name", g.name).put("cameraIds", JSONArray(g.cameraIds)))
                }
            })
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
                        .put("enabled", c.enabled)
                        .put("motion", c.motion.name)
                        .put("motionBeep", c.motionBeep)
                        .put("fullQuality", c.fullQuality))
                }
            })
        var out: java.io.FileOutputStream? = null
        try {
            out = file.startWrite()
            out.write(json.toString(2).toByteArray(Charsets.UTF_8))
            file.finishWrite(out) // fsync, then replace; the previous file stays as backup until then
            saveProblem = SecretBox.problem
        } catch (e: Exception) {
            out?.let { file.failWrite(it) }
            AppLog.w("could not save settings", e)
            saveProblem = "Settings couldn't be written to the TV's storage (${e.javaClass.simpleName}). Changes may be lost when the app closes."
        }
        unreadablePasswordIds = undecryptable.keys.toSet()
    }

    private var tokenCache: Pair<String, String>? = null // plain to sealed
    private var undecryptableToken = ""

    private fun sealedToken(plain: String): String {
        if (plain.isEmpty()) return undecryptableToken
        tokenCache?.let { (p, sealed) -> if (p == plain) return sealed }
        val sealed = SecretBox.encrypt(plain) ?: return tokenCache?.second ?: undecryptableToken
        tokenCache = plain to sealed
        undecryptableToken = ""
        return sealed
    }

    /** The ciphertext to store for [c]'s password, never losing a saved one. */
    private fun sealedPassword(c: CameraConfig): String {
        if (c.password.isEmpty()) {
            val kept = undecryptable[c.id] ?: return ""
            // Only for the address it was saved with: a changed address drops it.
            val known = undecryptableEndpoints[c.id] ?: emptySet()
            if (endpointsOf(c).all { it in known }) return kept
            AppLog.i("dropped an unreadable saved password: the camera's address changed")
            undecryptable.remove(c.id)
            undecryptableEndpoints.remove(c.id)
            return ""
        }
        sealedCache[c.id]?.let { (plain, sealed) -> if (plain == c.password) return sealed }
        val sealed = SecretBox.encrypt(c.password)
            ?: return sealedCache[c.id]?.second ?: undecryptable[c.id] ?: "" // Keystore hiccup: keep the old one
        sealedCache[c.id] = c.password to sealed
        undecryptable.remove(c.id)
        undecryptableEndpoints.remove(c.id)
        return sealed
    }

    private fun parse(json: JSONObject): AppSettings {
        val array = json.optJSONArray("cameras") ?: JSONArray()
        val seen = HashSet<String>()
        var migrated = false
        val cameras = (0 until array.length()).map { i ->
            val c = array.getJSONObject(i)
            // A duplicate id (hand-edited file) gets a new one: the camera list needs unique keys.
            val id = c.optString("id").takeIf { it.isNotEmpty() && seen.add(it) } ?: UUID.randomUUID().toString().also { seen.add(it) }
            val sealed = c.optString("password")
            val decrypted = SecretBox.decrypt(sealed)
            // Old files may hold a login inside the address: move it into its own (encrypted) fields.
            val (mainUrl, urlUser, urlPass) = splitCredentials(c.optString("mainUrl"))
            val (subUrl, _, _) = splitCredentials(c.optString("subUrl"))
            var username = c.optString("username")
            if (mainUrl != c.optString("mainUrl") || subUrl != c.optString("subUrl")) migrated = true
            if (username.isEmpty() && !urlUser.isNullOrEmpty()) username = urlUser
            val plain = when {
                decrypted != null && decrypted.isNotEmpty() -> decrypted
                !urlPass.isNullOrEmpty() -> urlPass // from the address; encrypted on the next save
                else -> decrypted
            }
            if (decrypted == null) {
                undecryptable[id] = sealed // keep it; written back until it decrypts or is replaced
                undecryptableEndpoints[id] = setOfNotNull(endpoint(mainUrl), subUrl.takeIf { it.isNotEmpty() }?.let { endpoint(it) })
            } else if (sealed.isNotEmpty() && plain == decrypted) {
                sealedCache[id] = plain to sealed
            }
            CameraConfig(
                id = id,
                name = c.optString("name", "Camera ${i + 1}"),
                mainUrl = mainUrl,
                subUrl = subUrl,
                username = username,
                password = plain ?: "",
                useTcp = c.optBoolean("useTcp", true),
                enabled = c.optBoolean("enabled", true),
                motion = runCatching { CameraMotion.valueOf(c.optString("motion")) }.getOrDefault(CameraMotion.DEFAULT),
                motionBeep = c.optBoolean("motionBeep", false),
                fullQuality = c.optBoolean("fullQuality", false),
            )
        }.take(AppSettings.MAX_CAMERAS)
        val cameraIds = cameras.map { it.id }.toSet()
        val groupsArray = json.optJSONArray("groups") ?: JSONArray()
        val groups = (0 until groupsArray.length()).mapNotNull { i ->
            val g = groupsArray.optJSONObject(i) ?: return@mapNotNull null
            val ids = g.optJSONArray("cameraIds") ?: JSONArray()
            CameraGroup(
                id = g.optString("id").ifEmpty { UUID.randomUUID().toString() },
                name = g.optString("name").ifEmpty { "Group ${i + 1}" },
                cameraIds = (0 until ids.length()).map { ids.optString(it) }.distinct().filter { it in cameraIds },
            )
        }.distinctBy { it.id }.take(AppSettings.MAX_GROUPS)
        unreadablePasswordIds = undecryptable.keys.toSet()
        if (migrated) AppLog.i("moved logins out of camera addresses into the login fields")
        migratedOnLoad = migrated
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
            floatingSize = runCatching { FloatingSize.valueOf(json.optString("floatingSize")) }.getOrDefault(FloatingSize.MEDIUM),
            floatingCorner = runCatching { FloatingCorner.valueOf(json.optString("floatingCorner")) }
                .getOrDefault(FloatingCorner.BOTTOM_RIGHT),
            groups = groups,
            currentGroupId = json.optString("currentGroupId").takeIf { id -> groups.any { it.id == id } } ?: "",
            phoneControl = json.optBoolean("phoneControl", true),
            updateCheck = json.optBoolean("updateCheck", true),
            githubToken = run {
                val sealed = json.optString("githubToken")
                val plain = SecretBox.decrypt(sealed)
                if (plain == null) { undecryptableToken = sealed; "" } else { if (sealed.isNotEmpty()) tokenCache = plain to sealed; plain }
            },
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
            val hasPort = if (authority.startsWith("[")) authority.substringAfterLast(']').startsWith(":") else ':' in authority
            return if (hasPort) authority else "$authority:554"
        }

        /** The endpoints (host:port) a camera's streams point at. */
        fun endpointsOf(c: CameraConfig): Set<String?> =
            setOfNotNull(endpoint(c.mainUrl), c.subUrl.takeIf { it.isNotEmpty() }?.let { endpoint(it) })

        /** A host name, IPv4 address or [IPv6] address: nothing that could smuggle a login or path into a URL. */
        fun isValidHost(host: String): Boolean =
            HOST.matches(host) || (host.startsWith("[") && host.endsWith("]") && host.length > 3 && host.drop(1).dropLast(1).all { it.isLetterOrDigit() || it == ':' || it == '.' })

        private val HOST = Regex("^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$")

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
