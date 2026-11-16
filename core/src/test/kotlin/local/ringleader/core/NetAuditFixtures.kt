package local.ringleader.core

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.TrustManagerFactory
import org.junit.jupiter.api.Assumptions

/*
 * Network audit fixtures (2026-11-16). Local servers only: loopback, a throwaway self-signed key made with the JDK's keytool
 * (the same way the existing TLS tests do it), invented names, documentation addresses. Nothing here talks to a lab host.
 */

/** A throwaway self-signed leaf: [pin] is its lower-case SHA-256, [server] serves it, [trusting] is a client context that trusts only it. */
internal class AuditCert(val pin: String, val server: SSLContext, val trusting: SSLContext, val certificate: java.security.cert.X509Certificate)

internal object NetAuditCerts {
    private fun make(san: String): AuditCert {
        val dir = Files.createTempDirectory("ringmaster-netaudit")
        val key = dir.resolve("fixture.p12")
        val executable = if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool"
        val command = mutableListOf(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
            "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
            "-dname", "CN=fixture.invalid", "-storetype", "PKCS12", "-keystore", key.toString(),
            "-storepass", "fixture-only", "-keypass", "fixture-only", "-noprompt", "-ext", "SAN=$san")
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.readAllBytes()
        check(process.waitFor() == 0) { "keytool failed: " + output.toString(Charsets.UTF_8) }
        val store = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(key).use { load(it, "fixture-only".toCharArray()) } }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "fixture-only".toCharArray()) }
        val server = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
        val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); setCertificateEntry("fixture", store.getCertificate("fixture")) }
        val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        val trusting = SSLContext.getInstance("TLS").apply { init(null, trustManagers.trustManagers, null) }
        val pin = MessageDigest.getInstance("SHA-256").digest(store.getCertificate("fixture").encoded).joinToString("") { "%02x".format(it) }
        Files.deleteIfExists(key); Files.deleteIfExists(dir)
        return AuditCert(pin, server, trusting, store.getCertificate("fixture") as java.security.cert.X509Certificate)
    }

    /** SANs for 127.0.0.1, ::1 and localhost: a certificate a normal client accepts for those literals once it trusts it. */
    val loopback: AuditCert by lazy { make("dns:localhost,ip:127.0.0.1,ip:0:0:0:0:0:0:0:1") }
    /** SAN for another name only: valid when trusted, but not for 127.0.0.1. */
    val otherName: AuditCert by lazy { make("dns:other.invalid") }
}

/** True when this machine can bind and connect to the IPv6 loopback (some CI containers cannot). */
internal fun ipv6LoopbackAvailable(): Boolean = runCatching { ServerSocket(0, 1, InetAddress.getByName("::1")).use { true } }.getOrDefault(false)

internal fun assumeIpv6() = Assumptions.assumeTrue(ipv6LoopbackAvailable(), "no IPv6 loopback on this machine")

/** Slow evidence tests (a minute or more) run only with RINGMASTER_NET_AUDIT_SLOW=1. */
internal fun assumeSlowAudit() = Assumptions.assumeTrue(System.getenv("RINGMASTER_NET_AUDIT_SLOW") == "1", "set RINGMASTER_NET_AUDIT_SLOW=1 to run this slow evidence test")

/**
 * Runs [block] on a computer that trusts [cert]: the certificate is put into the store an unpinned connection consults
 * ([PlatformTrust]), through its test seam, and taken out again. Unpinned connections no longer read the JVM default context.
 */
internal fun <T> withTrustedCertificate(cert: AuditCert, block: () -> T): T {
    PlatformTrust.override = PlatformTrust({ OsTrustStore(listOf(cert.certificate), "test store") })
    try { return block() } finally { PlatformTrust.override = null }
}

internal class WsUpgrade(val requestLine: String, val headers: Map<String, String>)
internal class WsFrame(val opcode: Int, val payload: ByteArray) {
    val text: String get() = payload.toString(Charsets.UTF_8)
}

/** One accepted WebSocket. Frames the client sends land in [received]; pings are answered unless [ignorePings]. */
internal class WsConn(private val socket: Socket, private val input: InputStream) : AutoCloseable {
    private val output = socket.getOutputStream()
    val received = LinkedBlockingQueue<WsFrame>()
    @Volatile var ignorePings = false

    fun startReader() {
        Thread({
            try {
                while (true) {
                    val frame = readFrame() ?: break
                    if (frame.opcode == 9 && !ignorePings) sendFrame(10, frame.payload)
                    received.add(frame)
                    if (frame.opcode == 8) break
                }
            } catch (_: Exception) { }
        }, "netaudit-ws-reader").apply { isDaemon = true; start() }
    }

    @Synchronized fun sendFrame(opcode: Int, payload: ByteArray) {
        val out = ByteArrayOutputStream()
        out.write(0x80 or opcode)
        when {
            payload.size < 126 -> out.write(payload.size)
            payload.size < 65536 -> { out.write(126); out.write(payload.size ushr 8); out.write(payload.size and 255) }
            else -> { out.write(127); for (shift in 56 downTo 0 step 8) out.write(((payload.size.toLong() ushr shift) and 255).toInt()) }
        }
        out.write(payload)
        output.write(out.toByteArray()); output.flush()
    }
    fun sendBinary(bytes: ByteArray) = sendFrame(2, bytes)
    fun sendText(text: String) = sendFrame(1, text.toByteArray(Charsets.UTF_8))

    private fun readFully(n: Int): ByteArray? {
        val buffer = ByteArray(n); var got = 0
        while (got < n) { val r = input.read(buffer, got, n - got); if (r < 0) return null; got += r }
        return buffer
    }
    private fun readFrame(): WsFrame? {
        val head = readFully(2) ?: return null
        val opcode = head[0].toInt() and 15
        val masked = head[1].toInt() and 128 != 0
        var length = (head[1].toInt() and 127).toLong()
        if (length == 126L) { val b = readFully(2) ?: return null; length = ((b[0].toInt() and 255) shl 8 or (b[1].toInt() and 255)).toLong() }
        else if (length == 127L) { val b = readFully(8) ?: return null; length = b.fold(0L) { acc, x -> (acc shl 8) or (x.toLong() and 255) } }
        val mask = if (masked) (readFully(4) ?: return null) else null
        val payload = readFully(length.toInt()) ?: return null
        if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        return WsFrame(opcode, payload)
    }

    override fun close() { runCatching { socket.close() } }
}

/**
 * A WebSocket endpoint on loopback, plain or TLS. [onConnected] runs on its own thread after the 101 reply.
 * [upgrades] records every upgrade request it saw (request line and headers), so a test can prove what was or was not sent.
 */
internal class MiniWsServer(private val context: SSLContext?, host: String = "127.0.0.1", private val onConnected: (WsConn) -> Unit = {}) : AutoCloseable {
    private val server: ServerSocket = if (context != null) {
        (context.serverSocketFactory.createServerSocket(0, 50, InetAddress.getByName(host)) as SSLServerSocket)
    } else ServerSocket(0, 50, InetAddress.getByName(host))
    val port: Int = server.localPort
    val upgrades = CopyOnWriteArrayList<WsUpgrade>()
    val accepted = AtomicInteger()
    /** Connections that ended before a complete HTTP upgrade request arrived (a refused handshake lands here). */
    val noRequest = AtomicInteger()
    private val sockets = CopyOnWriteArrayList<Socket>()

    init {
        Thread({
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                accepted.incrementAndGet(); sockets += socket
                Thread({ serve(socket) }, "netaudit-ws-conn").apply { isDaemon = true; start() }
            }
        }, "netaudit-ws-accept").apply { isDaemon = true; start() }
    }

    private fun serve(socket: Socket) {
        try {
            val input = BufferedInputStream(socket.getInputStream())
            val head = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) { noRequest.incrementAndGet(); return }
                head.write(b)
                val s = head.size()
                if (s >= 4) { val a = head.toByteArray(); if (a[s - 4] == 13.toByte() && a[s - 3] == 10.toByte() && a[s - 2] == 13.toByte() && a[s - 1] == 10.toByte()) break }
                if (s > 16384) { noRequest.incrementAndGet(); return }
            }
            val lines = head.toString(Charsets.ISO_8859_1).split("\r\n").filter { it.isNotEmpty() }
            val headers = lines.drop(1).associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            upgrades += WsUpgrade(lines.first(), headers)
            val key = headers["sec-websocket-key"] ?: return
            val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
            val protocol = if (headers["sec-websocket-protocol"]?.contains("binary") == true) "Sec-WebSocket-Protocol: binary\r\n" else ""
            socket.getOutputStream().write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n$protocol\r\n".toByteArray())
            socket.getOutputStream().flush()
            val conn = WsConn(socket, input)
            conn.startReader()
            onConnected(conn)
        } catch (_: Exception) { noRequest.incrementAndGet() }
    }

    override fun close() { runCatching { server.close() }; sockets.forEach { runCatching { it.close() } } }
}

/** What a raw TLS server saw of an HTTP request: the head as text and how many body bytes followed before it stopped reading. */
internal class SeenHttp(val head: String, val bodyBytes: Long) {
    val requestLine: String get() = head.lineSequence().first()
    fun header(name: String): String? = head.lineSequence().drop(1).firstOrNull { it.startsWith("$name:", ignoreCase = true) }?.substringAfter(':')?.trim()
}

/**
 * A raw one-shot-per-connection HTTP/1.1 server over TLS (or plain) that reads the request head and [readBody] bytes of body,
 * then calls [reply] with what it has and writes whatever text [reply] returns. Used where the JDK's own HttpServer would hide
 * the framing (Host header, interim responses, partial bodies).
 */
internal class RawHttpServer(context: SSLContext?, host: String = "127.0.0.1", private val readBody: (SeenHttp) -> Long = { 0L },
    private val reply: (SeenHttp) -> String = { "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}" }) : AutoCloseable {
    private val server: ServerSocket = if (context != null) {
        (context.serverSocketFactory.createServerSocket(0, 50, InetAddress.getByName(host)) as SSLServerSocket)
    } else ServerSocket(0, 50, InetAddress.getByName(host))
    val port: Int = server.localPort
    val seen = CopyOnWriteArrayList<SeenHttp>()
    val accepted = AtomicInteger()
    /** Connections that produced no complete request head (a refused TLS handshake lands here). */
    val noHead = AtomicInteger()
    val bytesReadBeforeHead = AtomicInteger()
    private val sockets = CopyOnWriteArrayList<Socket>()

    init {
        Thread({
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                accepted.incrementAndGet(); sockets += socket
                Thread({ serve(socket) }, "netaudit-http-conn").apply { isDaemon = true; start() }
            }
        }, "netaudit-http-accept").apply { isDaemon = true; start() }
    }

    private fun serve(socket: Socket) {
        try {
            val input = BufferedInputStream(socket.getInputStream())
            val head = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) { noHead.incrementAndGet(); return }
                bytesReadBeforeHead.incrementAndGet()
                head.write(b)
                val s = head.size()
                if (s >= 4) { val a = head.toByteArray(); if (a[s - 4] == 13.toByte() && a[s - 3] == 10.toByte() && a[s - 2] == 13.toByte() && a[s - 1] == 10.toByte()) break }
                if (s > 65536) { noHead.incrementAndGet(); return }
            }
            val partial = SeenHttp(head.toString(Charsets.ISO_8859_1), 0)
            val want = readBody(partial)
            var got = 0L
            val sink = ByteArray(65536)
            while (got < want) { val r = input.read(sink, 0, minOf(sink.size.toLong(), want - got).toInt()); if (r < 0) break; got += r }
            val full = SeenHttp(partial.head, got)
            seen += full
            socket.getOutputStream().write(reply(full).toByteArray(Charsets.ISO_8859_1)); socket.getOutputStream().flush()
            Thread.sleep(50)
        } catch (_: Exception) { noHead.incrementAndGet() } finally { runCatching { socket.close() } }
    }

    override fun close() { runCatching { server.close() }; sockets.forEach { runCatching { it.close() } } }
}
