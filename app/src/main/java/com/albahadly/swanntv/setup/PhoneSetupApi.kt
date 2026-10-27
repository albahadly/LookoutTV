package com.albahadly.swanntv.setup

import android.os.Handler
import android.os.Looper
import com.albahadly.swanntv.data.AppSettings
import com.albahadly.swanntv.data.CameraConfig
import com.albahadly.swanntv.data.CameraGroup
import com.albahadly.swanntv.data.CameraMotion
import com.albahadly.swanntv.data.LayoutPreference
import com.albahadly.swanntv.data.SettingsStore
import com.albahadly.swanntv.playback.CameraTester
import com.albahadly.swanntv.ui.settings.NvrPreset
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The phone page's JSON API. Requests arrive on server threads; every read or change of the
 * settings runs on the main thread (where the TV screen reads them), so the TV updates live.
 *
 * Passwords:
 *  - Accepted, never sent back (the page only learns whether one is set).
 *  - A saved password is only reused for the address it was saved with: testing or saving a
 *    camera with a new address needs the password typed again, so a changed (or mistyped)
 *    address can never receive the saved one.
 *  - A login typed inside an address (rtsp://user:pass@host) is moved into the login fields.
 */
class PhoneSetupApi(
    private val settings: () -> AppSettings,
    private val update: (AppSettings) -> Unit,
    private val tester: CameraTester,
    /** True while [token] is the running session's key (false once Settings has closed). */
    private val isCurrentSession: (token: String) -> Boolean,
    /** The TV remote: runs on the main thread; returns an error message, or null when done. */
    private val control: (action: String, id: String) -> String?,
    /** The last lines of the TV's log, newest last. */
    private val logTail: (lines: Int) -> String,
) {
    private class Rejected(val status: Int, message: String) : Exception(message)

    private val main = Handler(Looper.getMainLooper())
    private val testRunning = AtomicBoolean(false)

    /**
     * True while Settings is open on the TV. Afterwards the phone keeps only the remote, the
     * state and the log: changing cameras needs Settings open (and its PIN) on the TV.
     */
    @Volatile var settingsOpen: Boolean = false

    private val alwaysAllowed = setOf("GET /state", "POST /view", "GET /log")

    fun handle(token: String, method: String, path: String, query: Map<String, String>, body: String): PhoneSetupServer.Response = try {
        if (!settingsOpen && "$method $path" !in alwaysAllowed) {
            throw Rejected(409, "Open Settings on the TV to change cameras. The phone stays connected as a remote.")
        }
        when ("$method $path") {
            "GET /state" -> ok(onMain(token) { stateJson(settings()) })
            "POST /camera" -> saveCamera(token, JSONObject(body))
            "POST /delete" -> deleteCamera(token, JSONObject(body).str("id"))
            "POST /order" -> reorder(token, JSONObject(body).getJSONArray("ids"))
            "POST /nvr" -> addFromNvr(token, JSONObject(body))
            "POST /test" -> test(token, JSONObject(body))
            "POST /layout" -> setLayout(token, JSONObject(body).str("layout"))
            "GET /export" -> ok(onMain(token) { exportJson(settings(), query["passwords"] == "1") })
            "POST /import" -> import(token, JSONObject(body))
            "POST /view" -> view(token, JSONObject(body))
            "POST /group" -> saveGroup(token, JSONObject(body))
            "POST /group/delete" -> deleteGroup(token, JSONObject(body).str("id"))
            "GET /log" -> PhoneSetupServer.Response.text(logTail((query["lines"]?.toIntOrNull() ?: 300).coerceIn(10, 2000)), 200)
            else -> error("Unknown request", 404)
        }
    } catch (e: Rejected) {
        error(e.message ?: "Refused", e.status)
    } catch (e: InterruptedException) {
        // The server was stopped (Settings closed) while this request waited.
        error("Settings was closed on the TV. Scan the QR code again.", 403)
    } catch (e: Exception) {
        error("That request wasn't understood: ${e.message ?: e.javaClass.simpleName}", 400)
    }

    /** Stops anything still running for the phone (Settings closed). */
    fun close() {
        main.post { tester.cancel() }
    }

    // ------------------------------------------------------------------ actions

    private fun saveCamera(token: String, json: JSONObject): PhoneSetupServer.Response {
        val name = json.str("name").trim()
        val (mainUrl, mainUser, mainPass) = SettingsStore.splitCredentials(json.str("mainUrl").trim())
        val (subUrl, _, _) = SettingsStore.splitCredentials(json.str("subUrl").trim())
        validate(name, mainUrl, subUrl)?.let { return error(it) }
        val typedPassword: String? = if (json.has("password") && !json.isNull("password")) json.getString("password") else mainPass
        val username = json.str("username").trim().ifEmpty { mainUser.orEmpty() }
        return mutate(token) {
            val current = settings()
            val id = json.str("id")
            val existing = if (id.isEmpty()) null else current.cameras.firstOrNull { it.id == id }
                ?: throw Rejected(409, "That camera was deleted on the TV meanwhile.")
            if (existing == null && current.cameras.size >= AppSettings.MAX_CAMERAS) {
                throw Rejected(400, "16 cameras is the maximum. Delete one first.")
            }
            val password = when {
                typedPassword != null -> typedPassword
                existing == null -> ""
                existing.password.isNotEmpty() && !sameEndpoints(existing, mainUrl, subUrl) ->
                    throw Rejected(400, "The address changed: type the password again.")
                else -> existing.password
            }
            val camera = (existing ?: CameraConfig(name = name, mainUrl = mainUrl)).copy(
                name = name,
                mainUrl = mainUrl,
                subUrl = subUrl,
                username = username,
                password = password,
                useTcp = json.optBoolean("useTcp", true),
                enabled = json.optBoolean("enabled", true),
                motion = runCatching { CameraMotion.valueOf(json.str("motion")) }.getOrDefault(existing?.motion ?: CameraMotion.DEFAULT),
                motionBeep = json.optBoolean("motionBeep", existing?.motionBeep ?: false),
                fullQuality = json.optBoolean("fullQuality", existing?.fullQuality ?: false),
            )
            val list = if (existing == null) current.cameras + camera
            else current.cameras.map { if (it.id == camera.id) camera else it }
            commit(current.copy(cameras = list))
        }
    }

    private fun deleteCamera(token: String, id: String) = mutate(token) {
        val current = settings()
        commit(current.copy(cameras = current.cameras.filter { it.id != id }))
    }

    private fun reorder(token: String, ids: JSONArray) = mutate(token) {
        val current = settings()
        val byId = current.cameras.associateBy { it.id }
        // Unique, known ids only: a crafted list must never duplicate cameras.
        val order = (0 until ids.length()).map { ids.getString(it) }.distinct().filter { it in byId }
        commit(current.copy(cameras = order.map { byId.getValue(it) } + current.cameras.filter { it.id !in order }))
    }

    private fun addFromNvr(token: String, json: JSONObject): PhoneSetupServer.Response {
        val preset = runCatching { NvrPreset.valueOf(json.str("preset")) }.getOrNull()
            ?: return error("Choose the recorder brand.")
        val host = json.str("host").trim()
        val port = json.str("port").trim().ifEmpty { "554" }
        val channels = json.optInt("channels", 0)
        val replace = json.optBoolean("replace", false)
        if (!SettingsStore.isValidHost(host)) return error("Enter the recorder's IP address (like 192.168.1.100).")
        if (port.toIntOrNull() !in 1..65535) return error("The port should be a number, usually 554.")
        if (channels !in 1..AppSettings.MAX_CAMERAS) return error("Choose 1 to 16 channels.")
        return mutate(token) {
            val current = settings()
            val room = if (replace) AppSettings.MAX_CAMERAS else AppSettings.MAX_CAMERAS - current.cameras.size
            if (channels > room) throw Rejected(400, "Only $room more camera(s) fit. Choose Replace or fewer channels.")
            val created = (1..channels).map { ch ->
                CameraConfig(
                    name = "Camera $ch",
                    mainUrl = preset.url(host, port, ch, main = true),
                    subUrl = preset.url(host, port, ch, main = false),
                    username = json.str("username").trim(),
                    password = json.str("password"),
                )
            }
            commit(current.copy(cameras = if (replace) created else current.cameras + created))
        }
    }

    /** Tests a stream. A saved password is only used for the address it was saved with. */
    private fun test(token: String, json: JSONObject): PhoneSetupServer.Response {
        val (url, urlUser, urlPass) = SettingsStore.splitCredentials(json.str("url").trim())
        if (!isRtsp(url)) return error("Enter an rtsp:// address to test.")
        val typedPassword: String? = if (json.has("password") && !json.isNull("password")) json.getString("password") else urlPass
        val (username, password) = onMain(token) {
            val saved = settings().cameras.firstOrNull { it.id == json.str("id") }
            val user = json.str("username").ifEmpty { urlUser ?: saved?.username.orEmpty() }
            val pass = when {
                typedPassword != null -> typedPassword
                saved == null -> ""
                saved.password.isNotEmpty() && SettingsStore.endpoint(url) !in endpointsOf(saved) ->
                    throw Rejected(400, "This is a new address: type the password to test it.")
                else -> saved.password
            }
            user to pass
        }
        // One test at a time: tests share one tester, and each holds a server thread while it runs.
        if (!testRunning.compareAndSet(false, true)) return error("A test is already running. Try again in a moment.", 409)
        try {
            val latch = CountDownLatch(1)
            var result: CameraTester.Result = CameraTester.Result.Failed("No answer within 10 seconds.")
            main.post {
                if (!isCurrentSession(token)) { // Settings closed meanwhile: don't start a stream now
                    latch.countDown()
                    return@post
                }
                tester.test(SettingsStore.withCredentials(url, username, password), json.optBoolean("useTcp", true)) {
                    result = it
                    latch.countDown()
                }
            }
            latch.await(11, TimeUnit.SECONDS)
            val out = when (val r = result) {
                is CameraTester.Result.Ok -> JSONObject().put("ok", true).put("message", "OK, ${r.codec} ${r.resolution} (answered in ${r.millis} ms)")
                is CameraTester.Result.Failed -> JSONObject().put("ok", false).put("message", r.reason)
            }
            return ok(out.toString())
        } finally {
            testRunning.set(false)
        }
    }

    private fun setLayout(token: String, name: String): PhoneSetupServer.Response {
        val layout = runCatching { LayoutPreference.valueOf(name) }.getOrNull() ?: return error("Unknown layout.")
        return mutate(token) { commit(settings().copy(layout = layout)) }
    }

    private fun import(token: String, json: JSONObject): PhoneSetupServer.Response {
        val array = json.optJSONArray("cameras") ?: return error("That file has no cameras in it.")
        val replace = json.optBoolean("replace", false)
        val layout = runCatching { LayoutPreference.valueOf(json.str("layout")) }.getOrNull()
        val imported = (0 until array.length()).map { i ->
            val c = array.getJSONObject(i)
            val name = c.str("name").trim().ifEmpty { "Camera ${i + 1}" }
            val (mainUrl, urlUser, urlPass) = SettingsStore.splitCredentials(c.str("mainUrl").trim())
            val (subUrl, _, _) = SettingsStore.splitCredentials(c.str("subUrl").trim())
            validate(name, mainUrl, subUrl)?.let { return error("Camera ${i + 1} ($name): $it") }
            CameraConfig(
                name = name,
                mainUrl = mainUrl,
                subUrl = subUrl,
                username = c.str("username").trim().ifEmpty { urlUser.orEmpty() },
                password = c.str("password").ifEmpty { urlPass.orEmpty() },
                useTcp = c.optBoolean("useTcp", true),
                enabled = c.optBoolean("enabled", true),
                motion = runCatching { CameraMotion.valueOf(c.str("motion")) }.getOrDefault(CameraMotion.DEFAULT),
                motionBeep = c.optBoolean("motionBeep", false),
                fullQuality = c.optBoolean("fullQuality", false),
            )
        }
        return mutate(token) {
            val current = settings()
            val list = if (replace) imported else current.cameras + imported
            if (list.size > AppSettings.MAX_CAMERAS) {
                throw Rejected(400, "That would make ${list.size} cameras; 16 is the maximum. Choose Replace.")
            }
            commit(current.copy(cameras = list, layout = layout ?: current.layout))
        }
    }

    /** The TV remote: open a camera, back to the grid, tour, previous/next, choose a group. */
    private fun view(token: String, json: JSONObject): PhoneSetupServer.Response {
        val action = json.str("action")
        if (action !in setOf("camera", "grid", "tour", "next", "prev", "group")) return error("Unknown action.")
        return mutate(token) {
            control(action, json.str("id"))?.let { throw Rejected(409, it) }
            stateJson(settings())
        }
    }

    private fun saveGroup(token: String, json: JSONObject): PhoneSetupServer.Response {
        val name = json.str("name").trim()
        if (name.isEmpty()) return error("Give the group a name.")
        if (name.length > 40) return error("Keep the group name under 40 characters.")
        val idsArray = json.optJSONArray("cameraIds") ?: JSONArray()
        val wanted = (0 until idsArray.length()).map { idsArray.optString(it) }.distinct()
        return mutate(token) {
            val current = settings()
            val known = current.cameras.map { it.id }
            val ids = known.filter { it in wanted } // in camera-list order
            if (ids.isEmpty()) throw Rejected(400, "Choose at least one camera.")
            val id = json.str("id")
            val existing = if (id.isEmpty()) null else current.groups.firstOrNull { it.id == id }
                ?: throw Rejected(409, "That group was deleted on the TV meanwhile.")
            if (existing == null && current.groups.size >= AppSettings.MAX_GROUPS) {
                throw Rejected(400, "${AppSettings.MAX_GROUPS} groups is the maximum.")
            }
            val group = (existing ?: CameraGroup(name = name, cameraIds = ids)).copy(name = name, cameraIds = ids)
            val list = if (existing == null) current.groups + group else current.groups.map { if (it.id == group.id) group else it }
            commit(current.copy(groups = list))
        }
    }

    private fun deleteGroup(token: String, id: String) = mutate(token) {
        val current = settings()
        commit(current.copy(
            groups = current.groups.filter { it.id != id },
            currentGroupId = if (current.currentGroupId == id) "" else current.currentGroupId,
        ))
    }

    // ------------------------------------------------------------------ checks and JSON

    /** The last check before any change is saved: at most 16 cameras, each id once. */
    private fun commit(newSettings: AppSettings): String {
        val ids = newSettings.cameras.map { it.id }
        if (ids.size > AppSettings.MAX_CAMERAS || ids.toSet().size != ids.size) {
            throw Rejected(400, "That change would break the camera list, so it wasn't saved.")
        }
        update(newSettings)
        return stateJson(settings())
    }

    private fun endpointsOf(c: CameraConfig): Set<String?> = SettingsStore.endpointsOf(c)

    /** True if the new addresses point only at hosts the camera was saved with. */
    private fun sameEndpoints(existing: CameraConfig, mainUrl: String, subUrl: String): Boolean {
        val known = endpointsOf(existing)
        return SettingsStore.endpoint(mainUrl) in known &&
            (subUrl.isEmpty() || SettingsStore.endpoint(subUrl) in known)
    }

    private fun stateJson(s: AppSettings): String = JSONObject()
        .put("max", AppSettings.MAX_CAMERAS)
        .put("layout", s.layout.name)
        .put("presets", JSONArray().apply { NvrPreset.entries.forEach { put(JSONObject().put("id", it.name).put("label", it.label)) } })
        .put("cameras", JSONArray().apply {
            s.cameras.forEach { c ->
                put(JSONObject()
                    .put("id", c.id)
                    .put("name", c.name)
                    // Without any login (stripped, not masked: the page sends these back on save).
                    .put("mainUrl", SettingsStore.splitCredentials(c.mainUrl).first)
                    .put("subUrl", SettingsStore.splitCredentials(c.subUrl).first)
                    .put("username", c.username)
                    .put("hasPassword", c.password.isNotEmpty()) // never the password itself
                    .put("useTcp", c.useTcp)
                    .put("enabled", c.enabled)
                    .put("motion", c.motion.name)
                    .put("motionBeep", c.motionBeep)
                    .put("fullQuality", c.fullQuality))
            }
        })
        .put("groups", JSONArray().apply {
            s.groups.forEach { g -> put(JSONObject().put("id", g.id).put("name", g.name).put("cameraIds", JSONArray(g.cameraIds))) }
        })
        .put("currentGroupId", s.currentGroupId)
        .put("maxGroups", AppSettings.MAX_GROUPS)
        .put("editable", settingsOpen)
        .toString()

    private fun exportJson(s: AppSettings, withPasswords: Boolean): String = JSONObject()
        .put("app", "SwannTV")
        .put("version", 1)
        .put("layout", s.layout.name)
        .put("cameras", JSONArray().apply {
            s.cameras.forEach { c ->
                put(JSONObject()
                    .put("name", c.name)
                    .put("mainUrl", SettingsStore.splitCredentials(c.mainUrl).first)
                    .put("subUrl", SettingsStore.splitCredentials(c.subUrl).first)
                    .put("username", c.username)
                    .apply { if (withPasswords) put("password", c.password) }
                    .put("useTcp", c.useTcp)
                    .put("enabled", c.enabled)
                    .put("motion", c.motion.name)
                    .put("motionBeep", c.motionBeep)
                    .put("fullQuality", c.fullQuality))
            }
        })
        .toString(2)

    private fun validate(name: String, mainUrl: String, subUrl: String): String? = when {
        name.isEmpty() -> "Give the camera a name."
        name.length > 60 -> "Keep the name under 60 characters."
        !isRtsp(mainUrl) -> "The main stream must be an rtsp:// address."
        subUrl.isNotEmpty() && !isRtsp(subUrl) -> "The grid stream must be an rtsp:// address, or left empty."
        else -> null
    }

    private fun isRtsp(url: String): Boolean =
        url.startsWith("rtsp://", ignoreCase = true) && url.length > 7 && url.length < 1024 &&
            url.none { it.isWhitespace() || it.isISOControl() }

    /** Runs a change on the main thread and answers with the new state. */
    private fun mutate(token: String, block: () -> String) = ok(onMain(token, block))

    private fun ok(json: String) = PhoneSetupServer.Response.json(json)

    private fun error(message: String, status: Int = 400) =
        PhoneSetupServer.Response.json(JSONObject().put("error", message).toString(), status)

    /** org.json's optString turns an explicit null into the text "null"; this doesn't. */
    private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key)

    /**
     * Runs [block] on the main thread and waits for its result. Does nothing (and reports so)
     * if Settings closed meanwhile, or if the wait timed out, so a late request can never
     * change settings outside the session or be applied after the phone was told it failed.
     */
    private fun <T> onMain(token: String, block: () -> T): T {
        val cancelled = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        var value: Result<T>? = null
        main.post {
            value = if (cancelled.get() || !isCurrentSession(token)) {
                Result.failure(Rejected(403, "Settings was closed on the TV. Scan the QR code again."))
            } else {
                runCatching(block)
            }
            latch.countDown()
        }
        if (!latch.await(5, TimeUnit.SECONDS)) {
            cancelled.set(true)
            throw Rejected(503, "The TV is busy, try again.")
        }
        return value!!.getOrThrow()
    }

}
