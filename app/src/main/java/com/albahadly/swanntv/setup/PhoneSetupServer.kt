package com.albahadly.swanntv.setup

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import com.albahadly.swanntv.AppLog
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A tiny web server for setting the app up from a phone. It runs only while Settings is open,
 * answers only on the home network, and every request must carry the one-time key from the
 * QR code (a new key each time Settings opens), so nobody else on the network can use it.
 * No library: plain sockets, one short-lived connection per request.
 *
 * Limits keep a misbehaving device from tying it up: 4 worker threads with a short queue,
 * 2 connections at a time per device, 5 seconds to send a request, and it stops by itself
 * after 15 minutes without use.
 *
 * @param apiFactory builds the API, given a check for "is this key the running session's".
 * @param onIdleStop called on the main thread when it stopped itself for lack of use.
 */
class PhoneSetupServer(
    private val context: Context,
    private val onIdleStop: () -> Unit = {},
    apiFactory: (isCurrentSession: (String) -> Boolean) -> PhoneSetupApi,
) {

    data class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: ByteArray,
    ) {
        val bodyText: String get() = String(body, Charsets.UTF_8)
    }

    data class Response(val status: Int, val contentType: String, val body: ByteArray) {
        companion object {
            fun json(text: String, status: Int = 200) = Response(status, "application/json; charset=utf-8", text.toByteArray())
            fun text(text: String, status: Int) = Response(status, "text/plain; charset=utf-8", text.toByteArray())
        }
    }

    private class TooSlow : Exception()

    private companion object {
        /** Uncommon ports, so phone setup rarely clashes with other apps on the TV. */
        val PORTS = 8765..8775
        const val MAX_HEADER_BYTES = 16 * 1024
        const val MAX_BODY_BYTES = 256 * 1024
        const val REQUEST_DEADLINE_MS = 5_000L
        // Browsers open spare connections ahead of time (plus the favicon request), so a phone
        // can hold 3-4 sockets at once; the pool and queue bound the total anyway.
        const val MAX_PER_CLIENT = 6
        const val IDLE_STOP_MS = 15 * 60_000L
    }

    private val api = apiFactory { candidate -> tokenMatches(candidate) }
    private val main = Handler(Looper.getMainLooper())
    private val perClient = ConcurrentHashMap<InetAddress, AtomicInteger>()

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var pool: ThreadPoolExecutor = newPool()
    /** "ip:port" names the page may be opened by (blocks DNS-rebinding pages from other sites). */
    @Volatile private var allowedHosts: Set<String> = emptySet()
    @Volatile private var lastUsed = 0L

    /**
     * 6-digit code for typing the address by hand: the page swaps it for the key. Five wrong
     * codes switch it off until phone setup is started again, so it can't be guessed.
     */
    @Volatile var pairCode: String = ""
        private set
    private val wrongCodes = AtomicInteger()

    /** The one-time key; changes on every start. */
    @Volatile var token: String = ""
        private set
    @Volatile var port: Int = 0
        private set

    val isRunning: Boolean get() = serverSocket?.isClosed == false

    private val idleCheck = object : Runnable {
        override fun run() {
            if (!isRunning) return
            val idle = SystemClock.elapsedRealtime() - lastUsed
            if (idle >= IDLE_STOP_MS) {
                AppLog.i("phone setup unused for 15 minutes, stopping")
                stop()
                onIdleStop()
            } else {
                main.postDelayed(this, IDLE_STOP_MS - idle)
            }
        }
    }

    /** The address to type by hand (with [pairCode]), or null without a home-network address. */
    fun plainUrl(): String? = localIpv4()?.let { "http://$it:$port" }

    /** The address to put in the QR code, or null if the TV has no home-network address. */
    fun url(): String? {
        val ip = localIpv4() ?: return null
        return "http://$ip:$port/?t=$token"
    }

    /** Starts the server (main thread). True if it's running. */
    fun start(): Boolean {
        if (isRunning) return true
        token = newToken()
        // ASCII digits whatever the TV's language (String.format would use the locale's digits).
        pairCode = SecureRandom().nextInt(1_000_000).toString().padStart(6, '0')
        wrongCodes.set(0)
        for (candidate in PORTS) {
            try {
                val socket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(candidate), 8)
                }
                serverSocket = socket
                port = candidate
                allowedHosts = (localIpv4s() + listOf("127.0.0.1", "localhost")).map { "$it:$candidate" }.toSet()
                if (pool.isShutdown) pool = newPool()
                lastUsed = SystemClock.elapsedRealtime()
                main.removeCallbacks(idleCheck)
                main.postDelayed(idleCheck, IDLE_STOP_MS)
                Thread({ acceptLoop(socket) }, "phone-setup-accept").apply { isDaemon = true }.start()
                AppLog.i("phone setup started on port $candidate")
                return true
            } catch (e: Exception) {
                // Port busy: try the next one.
            }
        }
        token = ""
        pairCode = ""
        AppLog.w("phone setup could not start: no free port in $PORTS")
        return false
    }

    fun stop() {
        main.removeCallbacks(idleCheck)
        val socket = serverSocket ?: return
        serverSocket = null
        token = "" // the old QR code stops working at once, even for requests already queued
        pairCode = ""
        runCatching { socket.close() }
        pool.shutdownNow()
        api.close()
        perClient.clear()
        AppLog.i("phone setup stopped")
    }

    private fun newPool() = ThreadPoolExecutor(
        4, 4, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(8), ThreadPoolExecutor.AbortPolicy(),
    )

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: SocketException) {
                break // closed by stop()
            } catch (e: Exception) {
                continue
            }
            val address = client.inetAddress
            if (address == null || !isLocal(address)) {
                runCatching { client.close() }
                continue
            }
            val count = perClient.getOrPut(address) { AtomicInteger() }
            if (count.incrementAndGet() > MAX_PER_CLIENT) {
                count.decrementAndGet()
                runCatching { client.close() }
                continue
            }
            try {
                pool.execute {
                    try {
                        serve(client)
                    } finally {
                        count.decrementAndGet()
                    }
                }
            } catch (e: RejectedExecutionException) {
                count.decrementAndGet()
                AppLog.w("phone setup busy, connection dropped")
                runCatching { client.close() }
            }
        }
    }

    private fun serve(client: Socket) {
        client.use { s ->
            val response = try {
                val request = read(s, BufferedInputStream(s.getInputStream()))
                if (request == null) Response.text("Bad request", 400) else route(request)
            } catch (e: TooSlow) {
                AppLog.w("phone setup request timed out")
                return
            } catch (e: SocketTimeoutException) {
                AppLog.w("phone setup request timed out")
                return
            } catch (e: Exception) {
                AppLog.w("phone setup request failed: ${e.javaClass.simpleName}")
                Response.text("Server error", 500)
            }
            write(s, response)
        }
    }

    private fun route(r: Request): Response {
        // The Host (and Origin, if sent) must be this TV's address: a page from another site
        // that tricks the browser into calling us by a different name is refused.
        val host = r.headers["host"]?.lowercase()
        if (host == null || host !in allowedHosts) return Response.text("Wrong address", 403)
        r.headers["origin"]?.let { if (it.lowercase() != "http://$host") return Response.text("Wrong origin", 403) }

        // The page holds no settings, so it is served with the key from the QR code or, on a
        // reload, without one (the page removes the key from its address and keeps it for the
        // tab). An old key gets the "expired" message. The API needs the key in a header, which
        // a web page from another site can't add (blocks cross-site use).
        if (r.method == "GET" && (r.path == "/" || r.path == "/index.html")) {
            val key = r.query["t"]
            if (key != null && !tokenMatches(key)) {
                return Response.text("This link has expired. Open Settings on the TV and scan the QR code again.", 403)
            }
            if (key != null) lastUsed = SystemClock.elapsedRealtime() // a stranger loading / must not keep it alive
            val page = context.assets.open("setup.html").use { it.readBytes() }
            return Response(200, "text/html; charset=utf-8", page)
        }
        if (r.method == "POST" && r.path == "/pair") return pair(r)
        if (!r.path.startsWith("/api/")) return Response.text("Not found", 404)
        val key = r.headers["x-token"]
        if (key == null || !tokenMatches(key)) {
            return Response.json("""{"error":"This link has expired. Scan the QR code on the TV again."}""", 403)
        }
        lastUsed = SystemClock.elapsedRealtime()
        val result = api.handle(key, r.method, r.path.removePrefix("/api"), r.query, r.bodyText)
        AppLog.i("phone setup ${r.method} ${r.path} -> ${result.status}")
        return result
    }

    /** Swaps the code typed on the phone for the session key. */
    private fun pair(r: Request): Response {
        val code = runCatching { org.json.JSONObject(r.bodyText).optString("code") }.getOrDefault("")
        val expected = pairCode
        if (expected.isEmpty() || wrongCodes.get() >= 5) {
            return Response.json("""{"error":"Too many wrong codes. On the TV, leave Settings and open Phone setup again for a new code."}""", 403)
        }
        if (!MessageDigest.isEqual(code.toByteArray(), expected.toByteArray())) {
            val left = 5 - wrongCodes.incrementAndGet()
            AppLog.w("phone setup: wrong code ($left tries left)")
            val text = if (left > 0) "That code isn't right. Check the TV ($left tries left)."
            else "Too many wrong codes. On the TV, leave Settings and open Phone setup again for a new code."
            return Response.json(org.json.JSONObject().put("error", text).toString(), 403)
        }
        lastUsed = SystemClock.elapsedRealtime()
        AppLog.i("phone setup: phone connected with the code")
        return Response.json(org.json.JSONObject().put("token", token).toString())
    }

    private fun tokenMatches(candidate: String?): Boolean {
        val expected = token
        if (candidate.isNullOrEmpty() || expected.isEmpty()) return false
        return MessageDigest.isEqual(candidate.toByteArray(), expected.toByteArray())
    }

    // ------------------------------------------------------------------ HTTP plumbing

    /** Reads one request; the whole request must arrive within [REQUEST_DEADLINE_MS]. */
    private fun read(socket: Socket, input: InputStream): Request? {
        val deadline = SystemClock.elapsedRealtime() + REQUEST_DEADLINE_MS
        fun timeLeft(): Int {
            val left = deadline - SystemClock.elapsedRealtime()
            if (left <= 0) throw TooSlow()
            return left.toInt()
        }
        socket.soTimeout = timeLeft()
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val left = timeLeft() // every byte: a client trickling bytes must not hold a worker past the deadline
            if (head.size() and 0xFF == 0) socket.soTimeout = left
            val b = input.read()
            if (b < 0) return null
            head.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break // \r\n\r\n
            if (head.size() > MAX_HEADER_BYTES) return null
        }
        val lines = head.toString(Charsets.ISO_8859_1.name()).split("\r\n")
        val parts = lines.first().split(' ')
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val target = parts[1]
        val headers = lines.drop(1)
            .mapNotNull { line -> line.indexOf(':').takeIf { it > 0 }?.let { line.substring(0, it).trim().lowercase() to line.substring(it + 1).trim() } }
            .toMap()
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length < 0 || length > MAX_BODY_BYTES) return null
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            socket.soTimeout = timeLeft()
            val n = input.read(body, read, length - read)
            if (n < 0) return null
            read += n
        }
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { it.contains('=') }.mapNotNull {
            // A malformed %-escape must not crash the request: just ignore that part.
            runCatching {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }.getOrNull()
        }.toMap()
        return Request(method, path, query, headers, body)
    }

    private fun write(socket: Socket, r: Response) {
        val reason = when (r.status) {
            200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; 409 -> "Conflict"
            503 -> "Service Unavailable"
            else -> "Error"
        }
        val head = buildString {
            append("HTTP/1.1 ${r.status} $reason\r\n")
            append("Content-Type: ${r.contentType}\r\n")
            append("Content-Length: ${r.body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("X-Frame-Options: DENY\r\n")
            append("Content-Security-Policy: default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; img-src data:; form-action 'none'; frame-ancestors 'none'\r\n")
            append("Connection: close\r\n\r\n")
        }
        runCatching {
            val out = socket.getOutputStream()
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.write(r.body)
            out.flush()
        }
    }

    /** Only answer devices on the home network (private addresses), never the internet. */
    private fun isLocal(address: InetAddress): Boolean =
        address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress

    private fun newToken(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /** The TV's addresses on the home network, Ethernet first. */
    private fun localIpv4s(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .sortedBy { if (it.name.startsWith("eth")) 0 else 1 } // prefer Ethernet
            .flatMap { it.inetAddresses.toList() }
            .filter { it is Inet4Address && it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())

    private fun localIpv4(): String? = localIpv4s().firstOrNull()
}
