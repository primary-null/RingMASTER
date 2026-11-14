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
        val secure = (context ?: SSLContext.getDefault()).socketFactory.createSocket(tcp, origin.host,
            if(origin.port < 0) 443 else origin.port, true) as SSLSocket
        tls = secure
        secure.soTimeout = readTimeoutMs
        if(context == null) secure.sslParameters = secure.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        // Pin trust manager verifies the exact leaf and validity; normal TLS also verifies the hostname.
        secure.startHandshake()
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
