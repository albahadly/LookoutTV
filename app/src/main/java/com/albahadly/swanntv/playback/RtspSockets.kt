package com.albahadly.swanntv.playback

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Sockets for the RTSP client with a connect timeout. Media3's default has none: connecting
 * to an unreachable address blocks until the OS gives up (minutes), during which a player
 * can't be released cleanly (release() times out after 500 ms and reports a stale error).
 * With this, an unplugged camera is "Couldn't connect" within [CONNECT_TIMEOUT_MS].
 */
object RtspSockets {
    const val CONNECT_TIMEOUT_MS = 5_000

    val factory: SocketFactory = object : SocketFactory() {
        override fun createSocket(): Socket = Socket()

        override fun createSocket(host: String, port: Int): Socket =
            Socket().also { it.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }

        override fun createSocket(host: InetAddress, port: Int): Socket =
            Socket().also { it.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            Socket().also {
                it.bind(InetSocketAddress(localHost, localPort))
                it.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            }

        override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket =
            Socket().also {
                it.bind(InetSocketAddress(localHost, localPort))
                it.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            }
    }
}
