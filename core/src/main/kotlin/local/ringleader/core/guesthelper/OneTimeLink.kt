package local.ringleader.core.guesthelper

import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import local.ringleader.core.integrations.ServiceOrigin

/*
 * The one-time link: the app serves one Engine bundle, once, at a random path, for a few minutes, on the address of this
 * computer that faces the guest, and then closes. Plain HTTP is enough because the command the user pastes carries the
 * bundle's SHA-256 and the guest checks it before anything runs.
 */

enum class OneTimeLinkState { Waiting, Served, Expired, Failed }

class OneTimeLink private constructor(
    private val listener: ServerSocket,
    private val bundle: GuestHelperBundle,
    private val path: String,
    lifetime: Duration,
) : AutoCloseable {
    private val state = MutableStateFlow(OneTimeLinkState.Waiting)
    val status: StateFlow<OneTimeLinkState> = state.asStateFlow()
    private val closed = AtomicBoolean(false)
    private val deadline = System.nanoTime() + lifetime.toNanos()

    /** `http://<this computer's address facing the guest>:<port>/<random>/<bundle name>`. */
    val url: String = run {
        val host = ServiceOrigin.of("http", listener.inetAddress.hostAddress, listener.localPort).authority
        "http://$host$path"
    }

    /** The line to paste in the guest. */
    val command: String get() = linkCommand(url, bundle.sha256)

    private val thread = Thread(::serve, "ringmaster-engine-link").apply { isDaemon = true }

    private fun serve() {
        try {
            while (!closed.get()) {
                val left = (deadline - System.nanoTime()) / 1_000_000
                if (left <= 0) { finish(OneTimeLinkState.Expired); return }
                listener.soTimeout = left.coerceAtMost(1_000).toInt()
                val socket = try { listener.accept() } catch (_: SocketTimeoutException) { continue }
                if (answer(socket)) { finish(OneTimeLinkState.Served); return }
            }
        } catch (_: SocketException) {
            // close() closes the listener to stop this loop; the state was set by whoever closed it.
            if (!closed.get()) finish(OneTimeLinkState.Failed)
        } catch (_: IOException) {
            finish(OneTimeLinkState.Failed)
        }
    }

    /** Serves the bundle when [socket] asks for the exact path; true when it did. Anything else gets 404 and the link stays. */
    private fun answer(socket: Socket): Boolean = socket.use { s ->
        s.soTimeout = 10_000
        val request = readHead(s) ?: return false
        val line = request.lineSequence().firstOrNull().orEmpty()
        val out = s.getOutputStream()
        if (line != "GET $path HTTP/1.1" && line != "GET $path HTTP/1.0") {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            return false
        }
        val head = "HTTP/1.1 200 OK\r\nContent-Type: application/gzip\r\nContent-Length: ${bundle.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray())
        Files.newInputStream(bundle.file).use { it.copyTo(out) }
        out.flush()
        true
    }

    private fun readHead(s: Socket): String? {
        val input = s.getInputStream()
        val bytes = java.io.ByteArrayOutputStream()
        while (bytes.size() < 8192) {
            val b = try { input.read() } catch (_: SocketTimeoutException) { return null }
            if (b < 0) return null
            bytes.write(b)
            if (bytes.toString(Charsets.ISO_8859_1).endsWith("\r\n\r\n")) return bytes.toString(Charsets.ISO_8859_1)
        }
        return null
    }

    private fun finish(end: OneTimeLinkState) {
        if (state.value == OneTimeLinkState.Waiting) state.value = end
        close()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (state.value == OneTimeLinkState.Waiting) state.value = OneTimeLinkState.Expired
        try {
            listener.close()
        } catch (_: IOException) {
            // The listener is going away either way; there is nothing left to tell the user.
        }
    }

    companion object {
        val LIFETIME: Duration = Duration.ofMinutes(10)

        /**
         * Opens the link for [bundle] on the address of this computer that a packet to [guestHost] would leave from. No
         * packet is sent to find it: a UDP socket is only connected, which picks the route.
         */
        fun open(bundle: GuestHelperBundle, guestHost: String, lifetime: Duration = LIFETIME, random: SecureRandom = SecureRandom()): OneTimeLink {
            val local = facingAddress(guestHost)
            val listener = ServerSocket()
            listener.bind(InetSocketAddress(local, 0), 4)
            val secret = ByteArray(18).also(random::nextBytes)
            val path = "/" + Base64.getUrlEncoder().withoutPadding().encodeToString(secret) + "/" + bundle.file.fileName
            return OneTimeLink(listener, bundle, path, lifetime).also { it.thread.start() }
        }

        fun facingAddress(guestHost: String): InetAddress = DatagramSocket().use { socket ->
            socket.connect(InetSocketAddress(InetAddress.getByName(guestHost), 9))
            socket.localAddress
        }
    }
}
