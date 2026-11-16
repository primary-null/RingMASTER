package local.ringleader.core.integrations

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/** One received request: the head as text (request line and headers) and the body. */
internal class Received(val head: String, val body: ByteArray) {
    val line get() = head.lineSequence().first().substringBeforeLast(" HTTP/")
    fun header(name: String) = head.lineSequence().drop(1).firstOrNull { it.startsWith("$name:", true) }?.substringAfter(':')?.trim()
}

/** What the scripted server does with a request. */
internal sealed interface Reply {
    /** Raw bytes written after the request, exactly as given. */
    class Raw(val bytes: ByteArray) : Reply
    /** Read the whole request, then close without a status line. */
    data object Drop : Reply
    /** Never answer; hold the connection until the client gives up. */
    data object Hang : Reply
    /** Write [bytes] one at a time with [pauseMs] between them. */
    class Trickle(val bytes: ByteArray, val pauseMs: Long) : Reply
    /** Stream [size] bytes of a JSON-looking body with a Content-Length, writing until the client closes. */
    class Huge(val size: Long) : Reply

    companion object {
        fun json(status: Int, body: String, extra: String = "") = text(status, body, "application/json", extra)
        fun text(status: Int, body: String, type: String = "text/plain", extra: String = ""): Raw {
            val bytes = body.toByteArray()
            return Raw(("HTTP/1.1 $status X\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n$extra" +
                "Connection: close\r\n\r\n").toByteArray() + bytes)
        }
    }
}

/**
 * A raw HTTP/1.1 server on loopback, plain or TLS, for the service wire's tests: every connection, every decrypted request
 * byte and every complete request is counted. [bind] picks the loopback address (IPv4 or IPv6).
 */
internal class ScriptedServer(
    private val tls: SSLContext? = null,
    bind: InetAddress = InetAddress.getByName("127.0.0.1"),
    private val script: (Received) -> Reply,
) : AutoCloseable {
    private val listener = ServerSocket(0, 50, bind)
    val port get() = listener.localPort
    val connections = AtomicInteger()
    val requestBytes = AtomicLong()
    val received: MutableList<Received> = Collections.synchronizedList(mutableListOf())
    private val thread = Thread {
        while (!listener.isClosed) {
            val raw = try { listener.accept() } catch (_: Exception) { break }
            Thread { serve(raw) }.apply { isDaemon = true }.start()
        }
    }.apply { isDaemon = true; start() }

    private fun serve(raw: Socket) {
        connections.incrementAndGet()
        try {
            raw.soTimeout = 20_000
            val socket: Socket = if (tls != null) {
                (tls.socketFactory.createSocket(raw, "127.0.0.1", raw.port, true) as SSLSocket).apply { useClientMode = false; startHandshake() }
            } else raw
            val input = Counting(socket.getInputStream())
            val head = readHead(input) ?: return
            val length = head.lineSequence().firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
            val body = input.readNBytes(length)
            val request = Received(head, body)
            received += request
            val out: OutputStream = socket.getOutputStream()
            when (val reply = script(request)) {
                is Reply.Raw -> { out.write(reply.bytes); out.flush() }
                Reply.Drop -> {}
                Reply.Hang -> runCatching { input.read() }
                is Reply.Trickle -> for (b in reply.bytes) { out.write(b.toInt()); out.flush(); Thread.sleep(reply.pauseMs) }
                is Reply.Huge -> {
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${reply.size}\r\n\r\n".toByteArray())
                    val chunk = ByteArray(64 * 1024) { ' '.code.toByte() }
                    var left = reply.size
                    while (left > 0) { val n = minOf(left, chunk.size.toLong()).toInt(); out.write(chunk, 0, n); left -= n }
                    out.flush()
                }
            }
            socket.close()
        } catch (_: Exception) {
        } finally { runCatching { raw.close() } }
    }

    private inner class Counting(private val inner: InputStream) : InputStream() {
        override fun read(): Int = inner.read().also { if (it >= 0) requestBytes.incrementAndGet() }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) requestBytes.addAndGet(it.toLong()) }
    }

    private fun readHead(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (out.size() <= 65536) {
            val b = input.read()
            if (b < 0) return null
            out.write(b)
            val a = out.toByteArray(); val s = a.size
            if (s >= 4 && a[s - 4] == 13.toByte() && a[s - 3] == 10.toByte() && a[s - 2] == 13.toByte() && a[s - 1] == 10.toByte()) return a.toString(Charsets.ISO_8859_1)
        }
        return null
    }

    override fun close() { listener.close(); thread.join(2000) }
}
