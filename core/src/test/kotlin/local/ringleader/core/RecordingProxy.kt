package local.ringleader.core

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A proxy that only listens and writes down who came: the first line of an HTTP request (`CONNECT host:port HTTP/1.1`, or `GET ...`),
 * or `SOCKS4` / `SOCKS5` for a SOCKS client. It answers an HTTP client 502 and drops a SOCKS client, so nothing ever goes through it.
 * One port serves both, so a JVM can name it as its HTTP, HTTPS and SOCKS proxy. Loopback only.
 */
internal class RecordingProxy : AutoCloseable {
    private val server = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    val seen = CopyOnWriteArrayList<String>()

    init {
        thread(isDaemon = true, name = "recording-proxy") {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) {
                    client.use { socket ->
                        try {
                            socket.soTimeout = 2000
                            val input = socket.getInputStream()
                            val first = input.read()
                            when (first) {
                                0x05 -> seen += "SOCKS5"
                                0x04 -> seen += "SOCKS4"
                                -1 -> Unit
                                else -> {
                                    val rest = StringBuilder().append(first.toChar())
                                    while (true) { val c = input.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) rest.append(c.toChar()) }
                                    seen += rest.toString()
                                    socket.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                                    socket.getOutputStream().flush()
                                }
                            }
                        } catch (_: Exception) { }
                    }
                }
            }
        }
    }

    override fun close() { runCatching { server.close() } }
}
