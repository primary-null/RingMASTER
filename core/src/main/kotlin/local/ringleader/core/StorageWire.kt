package local.ringleader.core

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import org.apache.hc.core5.http.ContentType
import org.apache.hc.core5.http.config.Http1Config
import org.apache.hc.core5.http.impl.io.DefaultBHttpClientConnection
import org.apache.hc.core5.http.io.entity.EntityTemplate
import org.apache.hc.core5.http.message.BasicClassicHttpRequest
import org.apache.hc.core5.io.CloseMode

/**
 * Large fleets and long lists: `cluster/resources`, PDM `resources/list`, `access/permissions`, a storage's content list,
 * and the log pages (task logs up to 10,000 lines, the system log up to 2,000 lines a page).
 */
internal const val ANSWER_LIMIT_LARGE = 16 * 1024 * 1024
/** Every other API answer. */
internal const val ANSWER_LIMIT_DEFAULT = 4 * 1024 * 1024
/** The ticket answer: a ticket, a CSRF token and the capability map. */
internal const val ANSWER_LIMIT_TICKET = 64 * 1024
/** Deeper than any Proxmox answer (the deepest, guest agent interfaces, is 6), far below what the JSON parser's recursion survives. */
internal const val JSON_DEPTH_LIMIT = 64
const val ANSWER_TOO_LARGE = "The server's answer was too large."
const val ANSWER_TOO_DEEP = "The server's answer was not readable."

/** An answer refused before it was parsed. A read shows its sentence; after a sent mutation the outcome is unknown instead. */
class AnswerRefusedException(message: String) : IllegalStateException(message)

internal fun answerLimit(path: String): Int {
    val route = path.substringBefore('?')
    return when {
        route == "/api2/json/cluster/resources" || route == "/api2/json/resources/list" || route == "/api2/json/access/permissions" -> ANSWER_LIMIT_LARGE
        largeContentRoute.matches(route) -> ANSWER_LIMIT_LARGE
        else -> ANSWER_LIMIT_DEFAULT
    }
}
private val largeContentRoute = Regex("/api2/json/nodes/[^/]+/(storage/[^/]+/content|tasks/[^/]+/log|syslog)")

/** Reads at most [limit] bytes of an answer and checks its nesting before anyone parses it. The byte buffer is wiped. */
internal fun readAnswer(input: InputStream, limit: Int): String {
    val bytes = input.readNBytes(limit + 1)
    try {
        if (bytes.size > limit) throw AnswerRefusedException(ANSWER_TOO_LARGE)
        return String(bytes, Charsets.UTF_8).also { requireJsonDepth(it) }
    } finally { bytes.fill(0) }
}

/**
 * Refuses JSON nested deeper than [limit] arrays and objects, counted outside strings. kotlinx-serialization parses arrays
 * recursively, and a few hundred thousand brackets throw StackOverflowError, an Error that passes every `catch (Exception)`.
 */
internal fun requireJsonDepth(text: CharSequence, limit: Int = JSON_DEPTH_LIMIT) {
    var depth = 0
    var inString = false
    var escaped = false
    for (c in text) {
        if (inString) {
            if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') inString = false
        } else when (c) {
            '"' -> inString = true
            '[', '{' -> if (++depth > limit) throw AnswerRefusedException(ANSWER_TOO_DEEP)
            ']', '}' -> depth--
        }
    }
}

/** Parses server JSON after the depth check. Every parser of a raw server body in the transport files goes through here. */
internal fun serverJson(text: String): kotlinx.serialization.json.JsonElement {
    requireJsonDepth(text)
    return kotlinx.serialization.json.Json.parseToJsonElement(text)
}

/**
 * The TLS floor, set in code on every credentialed path (the one-shot wire, the reads and the login, the pinned trust manager
 * and the consoles), so an edited `java.security`, `https.protocols` or `jdk.tls.client.protocols` cannot lower it. TLS 1.3 and
 * 1.2 only; Proxmox VE 7 and later speak both. Cipher suites stay the JDK's.
 */
internal val TLS_FLOOR = listOf("TLSv1.3", "TLSv1.2")

/** The floor's protocols among those a socket supports, in the floor's order. */
internal fun tlsFloor(supported: Array<String>): Array<String> = TLS_FLOOR.filter { it in supported }.toTypedArray()

/** Refuses a negotiated protocol below the floor, after the handshake and before any request byte. */
internal fun requireTlsFloor(protocol: String?) {
    if (protocol !in TLS_FLOOR) throw javax.net.ssl.SSLHandshakeException("The server negotiated $protocol. TLS 1.2 or newer is required.")
}

/** For java.net.http clients: only the protocols; cipher suites and endpoint identification stay the client's own. */
internal fun tlsFloorParameters(): javax.net.ssl.SSLParameters = javax.net.ssl.SSLParameters().apply { protocols = TLS_FLOOR.toTypedArray() }

/** Wraps a socket factory so every socket it makes enables only the floor's protocols. */
internal class TlsFloorSocketFactory(private val inner: javax.net.ssl.SSLSocketFactory) : javax.net.ssl.SSLSocketFactory() {
    private fun floor(socket: Socket): Socket { (socket as? SSLSocket)?.let { it.enabledProtocols = tlsFloor(it.supportedProtocols) }; return socket }
    override fun getDefaultCipherSuites(): Array<String> = inner.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = inner.supportedCipherSuites
    override fun createSocket(): Socket = floor(inner.createSocket())
    override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket = floor(inner.createSocket(s, host, port, autoClose))
    override fun createSocket(s: Socket?, consumed: InputStream?, autoClose: Boolean): Socket = floor(inner.createSocket(s, consumed, autoClose))
    override fun createSocket(host: String?, port: Int): Socket = floor(inner.createSocket(host, port))
    override fun createSocket(host: String?, port: Int, local: java.net.InetAddress?, localPort: Int): Socket = floor(inner.createSocket(host, port, local, localPort))
    override fun createSocket(host: java.net.InetAddress?, port: Int): Socket = floor(inner.createSocket(host, port))
    override fun createSocket(host: java.net.InetAddress?, port: Int, local: java.net.InetAddress?, localPort: Int): Socket = floor(inner.createSocket(host, port, local, localPort))
}

/** HTTP framing on exactly one verified TLS connection. No pool, reconnect, redirect or retry layer. */
internal fun <T> storageWire(
    origin: URI, fingerprint: String, path: String, method: String, headers: Map<String,String>,
    contentType: String?, length: Long?, write: ((OutputStream) -> Unit)?,
    connectTimeoutMs: Int, readTimeoutMs: Int, cancel: UploadCancel,
    beforeHeaders: () -> Unit, afterHeaders: () -> Unit = {}, response: (Int, InputStream) -> T,
): T {
    require(origin.scheme == "https" && path.startsWith('/') && !path.startsWith("//"))
    val tcp = Socket()
    var tls: SSLSocket? = null
    val connection = DefaultBHttpClientConnection(Http1Config.custom()
        .setBufferSize(8192).setMaxLineLength(8192).setMaxHeaderCount(100).build())
    try {
        cancel.attach { tcp.close() }
        cancel.check()
        tcp.connect(InetSocketAddress(origin.host, if(origin.port < 0) 443 else origin.port), connectTimeoutMs)
        tcp.soTimeout = readTimeoutMs
        val context = pinnedSslContext(fingerprint)
        val secure = (context ?: platformSslContext()).socketFactory.createSocket(tcp, origin.host,
            if(origin.port < 0) 443 else origin.port, true) as SSLSocket
        tls = secure
        secure.soTimeout = readTimeoutMs
        secure.sslParameters = secure.sslParameters.apply {
            protocols = tlsFloor(secure.supportedProtocols)
            if(context == null) endpointIdentificationAlgorithm = "HTTPS"
        }
        // Pin trust manager verifies the exact leaf and validity; normal TLS also verifies the hostname.
        secure.startHandshake()
        requireTlsFloor(secure.session.protocol)
        connection.bind(secure, tcp)
        cancel.check()
        val request = BasicClassicHttpRequest(method, path)
        request.setHeader("Host", origin.rawAuthority)
        request.setHeader("Connection", "close")
        headers.forEach { (name,value) -> request.setHeader(name,value) }
        if(length != null) request.setHeader("Content-Length", length.toString())
        if(contentType != null) request.setHeader("Content-Type", contentType)
        if(write != null) request.entity = EntityTemplate(length!!,ContentType.parse(contentType)) { out -> write(out) }
        beforeHeaders() // Once this boundary is crossed even a header-write failure is ambiguous.
        connection.sendRequestHeader(request)
        connection.flush()
        afterHeaders() // Fault-injection seam: complete zero-body request has reached the wire, no body acquired yet.
        if(write != null) connection.sendRequestEntity(request)
        connection.flush()
        cancel.check()
        var reply = connection.receiveResponseHeader()
        var interim = 0
        while(reply.code in 100..199 && reply.code != 101) {
            check(++interim <= 8) { "Too many interim responses." }
            reply.close()
            reply = connection.receiveResponseHeader()
        }
        // Attach the entity using the library's bounded-line/header and content framing parser.
        connection.receiveResponseEntity(reply)
        return response(reply.code, reply.entity?.content ?: InputStream.nullInputStream())
    } finally {
        cancel.detach()
        // Immediate close avoids draining an untrusted/oversized body or flushing a failed mutation.
        runCatching { connection.close(CloseMode.IMMEDIATE) }
        runCatching { tls?.close() }
        runCatching { tcp.close() }
    }
}

/**
 * The SSL context of a one-shot exchange: the exact-leaf pin when [fingerprint] is set, otherwise the platform's normal TLS.
 * The linked-service wire (`integrations/ServiceWire.kt`) uses it; it is the same choice `storageWire` makes inline above.
 * MERGE NOTE: a change to how the unpinned context is chosen belongs here and in that one expression in `storageWire`.
 */
internal fun oneShotSslContext(fingerprint: String): SSLContext = pinnedSslContext(fingerprint) ?: platformSslContext()

/** The framing limits of every one-shot exchange, the same as `storageWire`'s: 8 KiB lines, 100 headers. */
internal fun oneShotHttp1Config(): Http1Config = Http1Config.custom().setBufferSize(8192).setMaxLineLength(8192).setMaxHeaderCount(100).build()
