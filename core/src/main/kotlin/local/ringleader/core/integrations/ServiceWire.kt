package local.ringleader.core.integrations

import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import local.ringleader.core.AnswerRefusedException
import local.ringleader.core.UploadCancel
import local.ringleader.core.oneShotHttp1Config
import local.ringleader.core.oneShotSslContext
import local.ringleader.core.requireJsonDepth
import local.ringleader.core.requireTlsFloor
import local.ringleader.core.tlsFloor
import org.apache.hc.core5.http.ContentType
import org.apache.hc.core5.http.impl.io.DefaultBHttpClientConnection
import org.apache.hc.core5.http.io.entity.ByteArrayEntity
import org.apache.hc.core5.http.message.BasicClassicHttpRequest
import org.apache.hc.core5.io.CloseMode

/** How a link's https is trusted, or that it is plain http. Never "trust anything". */
sealed interface WireTrust {
    /** The platform's trust store and a host name check. */
    data object NormalTls : WireTrust
    /** The exact leaf certificate, by SHA-256 ([local.ringleader.core.pinnedSslContext]). */
    data class Pinned(val sha256: String) : WireTrust
    /** Plain http. [ack] is the owner's acknowledgement for this endpoint, if any; [PlainHttpRule] decides. */
    data class PlainHttp(val ack: PlainHttpAck?) : WireTrust
}

enum class WireMethod { GET, POST, DELETE }

/**
 * One request. [path] is absolute (`/api/stats/summary?count=8`), already encoded by the engine. [headers] are the
 * template's auth header and nothing else the engine does not set itself. [body] only for POST (a session login).
 * [carriesCredential] tells the plain-HTTP rule whether a secret travels in this request.
 */
class WireRequest(
    val method: WireMethod,
    val path: String,
    val headers: List<Pair<String, String>> = emptyList(),
    val body: ByteArray? = null,
    val carriesCredential: Boolean = false,
    val accept: String = "application/json",
    val expectJson: Boolean = true,
) {
    override fun toString() = "$method ${path.substringBefore('?')}" // never the query, never a header
}

/** Bounds of one exchange. The defaults are the design's (§3.3); a template may lower [maxBytes], never raise it. */
data class WireLimits(
    val lookupMs: Int = 3_000,
    val connectMs: Int = 5_000,
    val readMs: Int = 10_000,
    val totalMs: Int = 15_000,
    val maxBytes: Int = SERVICE_ANSWER_DEFAULT,
) {
    init { require(maxBytes in 1..SERVICE_ANSWER_DEFAULT && lookupMs > 0 && connectMs > 0 && readMs > 0 && totalMs > 0) }
}

/** The answer-size cap of a linked-service request, unless its template sets a lower one. */
const val SERVICE_ANSWER_DEFAULT = 2 * 1024 * 1024

/** A redirect the wire reported instead of following. [path] is set only when it points at the same endpoint. */
data class WireRedirect(val sameEndpoint: Boolean, val path: String?)

class WireAnswer(
    val status: Int,
    /** Only allow-listed response headers, lower-case names: content-type, retry-after, location and [ServiceWire.READABLE_HEADERS]. */
    val headers: Map<String, String>,
    val body: ByteArray,
    val redirect: WireRedirect?,
    /** Set for plain http: how the standing chip reads. */
    val unencrypted: UnencryptedMark?,
) {
    val text: String get() = String(body, Charsets.UTF_8)
    /** Seconds from `Retry-After` when it is a plain number, capped at an hour. */
    val retryAfterSeconds: Long? get() = headers["retry-after"]?.trim()?.toLongOrNull()?.coerceIn(0, 3600)
}

/** Why an exchange failed, by class of failure. Mapped from exception classes and states, never from message text. */
enum class WireFailure {
    NameNotResolved, ConnectionRefused, Timeout, Unreachable, NoAnswer,
    CertificateChanged, CertificateUntrusted, TlsFailed,
    PlainHttpRefused, PlainHttpNeedsAcknowledgement, AddressRefused,
    TooLarge, TooDeep, EncodingRefused, Malformed, RequestRefused, Cancelled,
}

class ServiceWireException(val failure: WireFailure, message: String, cause: Throwable? = null) : IOException(message, cause)

/** Name lookup with a deadline. Tests swap in their own. */
fun interface ServiceResolver {
    /** One address for [host], within [timeoutMs]. A literal is parsed, never looked up. */
    fun resolve(host: String, timeoutMs: Int): InetAddress
}

/**
 * The default resolver: literals are parsed; names are looked up on a small bounded pool so a hanging resolver costs a
 * thread for at most the deadline and can never pile up more than [maxLookups] threads.
 * MERGE NOTE: when the JVM-wide bounded name lookup lands, [lookup] becomes a call to it.
 */
class BoundedResolver(
    private val maxLookups: Int = 4,
    private val lookup: (String) -> InetAddress = { InetAddress.getAllByName(it).first() },
) : ServiceResolver {
    private val pool = ThreadPoolExecutor(0, maxLookups, 30, TimeUnit.SECONDS, SynchronousQueue()) { r ->
        Thread(r, "service-lookup").apply { isDaemon = true }
    }

    override fun resolve(host: String, timeoutMs: Int): InetAddress {
        literal(host)?.let { return it }
        if (ServiceOrigin.canonicalHost(host) == null) throw UnknownHostException("not a host name")
        val future = try { pool.submit<InetAddress> { lookup(host) } }
        catch (_: java.util.concurrent.RejectedExecutionException) { throw UnknownHostException("lookups busy") }
        return try { future.get(timeoutMs.toLong(), TimeUnit.MILLISECONDS) }
        catch (_: TimeoutException) { future.cancel(true); throw UnknownHostException("lookup timed out") }
        catch (e: java.util.concurrent.ExecutionException) { throw (e.cause as? UnknownHostException) ?: UnknownHostException("lookup failed") }
    }

    companion object {
        val Default = BoundedResolver()

        /** Parses an IPv4 or IPv6 literal without any lookup; null for a name. */
        fun literal(host: String): InetAddress? {
            val canonical = ServiceOrigin.canonicalHost(host) ?: return null
            val isLiteral = ':' in canonical || canonical.all { it.isDigit() || it == '.' }
            return if (isLiteral) InetAddress.getByName(canonical) else null
        }
    }
}

/**
 * THE one request helper for linked-service reads (design §3.3). One request on one fresh connection; never pooled,
 * retried or redirected. Shares the one-shot wire's framing limits, TLS floor and pinned trust with `storageWire`
 * (StorageWire.kt) and the answer bounds with `readAnswer` / `requireJsonDepth`.
 *
 * - Resolves the host once, within the lookup deadline, and connects to exactly that address, so the plain-HTTP rule
 *   judges the address really used (a rebinding resolver cannot swap it between check and connect).
 * - https: certificate pin or normal TLS, TLS 1.2 floor; a certificate failure stops before any HTTP byte.
 * - http: only under [PlainHttpRule], asked on every connect.
 * - `Proxy.NO_PROXY`: a LAN service never goes through a JVM proxy setting.
 * - `Connection: close`, `Accept-Encoding: identity` (a compressed answer is refused), `Host` with IPv6 brackets.
 * - A 3xx is reported with [WireRedirect], never followed.
 * - The answer is read up to [WireLimits.maxBytes] and depth-checked when JSON is expected; larger is refused and the socket
 *   closed at once.
 * - A whole-exchange deadline closes the socket.
 */
class ServiceWire(
    private val userAgent: String,
    private val resolver: ServiceResolver = BoundedResolver.Default,
) {
    init { require(USER_AGENT.matches(userAgent)) { "The user agent is RingMASTER/<version>." } }

    fun exchange(origin: ServiceOrigin, trust: WireTrust, request: WireRequest, limits: WireLimits = WireLimits(), cancel: UploadCancel = UploadCancel()): WireAnswer {
        checkRequest(request)
        when (trust) {
            is WireTrust.PlainHttp -> if (origin.secure) refuse(WireFailure.RequestRefused, "An https address needs a certificate pin or normal TLS.")
            else -> if (!origin.secure) refuse(WireFailure.RequestRefused, "A plain http address cannot use TLS trust.")
        }
        val address = try { resolver.resolve(origin.host, limits.lookupMs) }
        catch (e: UnknownHostException) { throw ServiceWireException(WireFailure.NameNotResolved, "The name did not resolve within ${limits.lookupMs / 1000} s.", e) }
        val scope = addressScope(address)
        var mark: UnencryptedMark? = null
        if (trust is WireTrust.PlainHttp) {
            when (val decision = PlainHttpRule.decide(scope, request.carriesCredential, trust.ack, origin)) {
                is PlainHttpDecision.Allowed -> mark = decision.mark
                PlainHttpDecision.NeedsAcknowledgement -> refuse(WireFailure.PlainHttpNeedsAcknowledgement,
                    "This address is not encrypted. Sending the credential over http needs your acknowledgement first.")
                is PlainHttpDecision.Refused -> refuse(if (scope == AddressScope.Unusable) WireFailure.AddressRefused else WireFailure.PlainHttpRefused, decision.reason)
            }
        } else if (scope == AddressScope.Unusable) refuse(WireFailure.AddressRefused, PlainHttpRule.UNUSABLE)

        val tcp = Socket(Proxy.NO_PROXY)
        var tls: SSLSocket? = null
        val connection = DefaultBHttpClientConnection(oneShotHttp1Config())
        val expired = AtomicBoolean(false)
        val deadline = watchdog.schedule({ expired.set(true); runCatching { tcp.close() } }, limits.totalMs.toLong(), TimeUnit.MILLISECONDS)
        var stage = Stage.Connect
        try {
            cancel.attach { runCatching { tcp.close() } }
            cancel.check()
            tcp.connect(InetSocketAddress(address, origin.port), limits.connectMs)
            tcp.soTimeout = limits.readMs
            if (origin.secure) {
                stage = Stage.Handshake
                val pinned = trust as? WireTrust.Pinned
                val context = oneShotSslContext(pinned?.sha256.orEmpty())
                val secure = context.socketFactory.createSocket(tcp, origin.host, origin.port, true) as SSLSocket
                tls = secure
                secure.soTimeout = limits.readMs
                secure.sslParameters = secure.sslParameters.apply {
                    protocols = tlsFloor(secure.supportedProtocols)
                    if (pinned == null) endpointIdentificationAlgorithm = "HTTPS"
                }
                secure.startHandshake()
                requireTlsFloor(secure.session.protocol)
                connection.bind(secure, tcp)
            } else connection.bind(tcp)
            cancel.check()
            stage = Stage.Send
            val message = BasicClassicHttpRequest(request.method.name, request.path)
            message.setHeader("Host", origin.authority)
            message.setHeader("User-Agent", userAgent)
            message.setHeader("Accept", request.accept)
            message.setHeader("Accept-Encoding", "identity")
            message.setHeader("Connection", "close")
            request.headers.forEach { (name, value) -> message.setHeader(name, value) }
            val body = request.body
            if (body != null) {
                message.setHeader("Content-Type", "application/json")
                message.setHeader("Content-Length", body.size.toString())
                message.entity = ByteArrayEntity(body, ContentType.APPLICATION_JSON)
            }
            connection.sendRequestHeader(message)
            if (body != null) connection.sendRequestEntity(message)
            connection.flush()
            cancel.check()
            stage = Stage.Answer
            var reply = connection.receiveResponseHeader()
            var interim = 0
            while (reply.code in 100..199 && reply.code != 101) {
                if (++interim > 8) refuse(WireFailure.Malformed, "The service sent too many interim answers.")
                reply.close()
                reply = connection.receiveResponseHeader()
            }
            val encoding = reply.getFirstHeader("Content-Encoding")?.value?.trim()?.lowercase()
            if (encoding != null && encoding != "identity") refuse(WireFailure.EncodingRefused, "The service sent a compressed answer, which RingMASTER does not accept.")
            connection.receiveResponseEntity(reply)
            val headers = buildMap {
                for (name in KEPT_HEADERS) reply.getFirstHeader(name)?.value?.take(512)?.let { put(name, it) }
            }
            val bytes = read(reply.entity?.content ?: InputStream.nullInputStream(), limits.maxBytes)
            val redirect = if (reply.code in 300..399) redirectOf(origin, headers["location"]) else null
            if (request.expectJson && reply.code in 200..299 && isJson(headers["content-type"])) {
                try { requireJsonDepth(String(bytes, Charsets.UTF_8)) }
                catch (_: AnswerRefusedException) { refuse(WireFailure.TooDeep, "The answer was nested too deeply to read.") }
            }
            return WireAnswer(reply.code, headers, bytes, redirect, mark)
        } catch (e: ServiceWireException) {
            throw e
        } catch (e: Throwable) {
            if (e is Error && e !is StackOverflowError) throw e
            throw classify(e, stage, expired.get(), cancelled(cancel), trust, limits)
        } finally {
            deadline.cancel(false)
            cancel.detach()
            runCatching { connection.close(CloseMode.IMMEDIATE) }
            runCatching { tls?.close() }
            runCatching { tcp.close() }
        }
    }

    private enum class Stage { Connect, Handshake, Send, Answer }

    private fun cancelled(cancel: UploadCancel) = runCatching { cancel.check(); false }.getOrDefault(true)

    private fun classify(e: Throwable, stage: Stage, expired: Boolean, cancelled: Boolean, trust: WireTrust, limits: WireLimits): ServiceWireException {
        if (cancelled) return ServiceWireException(WireFailure.Cancelled, "Stopped.", e)
        if (expired) return ServiceWireException(WireFailure.Timeout, "No complete answer within ${limits.totalMs / 1000} s.", e)
        return when {
            e is ConnectException && stage == Stage.Connect -> ServiceWireException(WireFailure.ConnectionRefused, "Nothing answered on that port.", e)
            e is NoRouteToHostException -> ServiceWireException(WireFailure.Unreachable, "There is no route to that address.", e)
            e is SocketTimeoutException && stage == Stage.Connect -> ServiceWireException(WireFailure.Timeout, "No answer within ${limits.connectMs / 1000} s. The guest may be off, or a firewall drops it.", e)
            e is SSLHandshakeException && stage == Stage.Handshake && hasCause<CertificateException>(e) ->
                if (trust is WireTrust.Pinned) ServiceWireException(WireFailure.CertificateChanged, "The certificate is not the one you trusted.", e)
                else ServiceWireException(WireFailure.CertificateUntrusted, "The certificate is not trusted by this computer. Check its fingerprint to trust it.", e)
            (e is SSLException || e is SocketTimeoutException) && stage == Stage.Handshake ->
                ServiceWireException(WireFailure.TlsFailed, "The TLS handshake failed. The port may speak http, not https.", e)
            e is SocketTimeoutException || e is InterruptedIOException -> ServiceWireException(WireFailure.Timeout, "The service stopped answering within ${limits.readMs / 1000} s.", e)
            e is AnswerRefusedException -> ServiceWireException(WireFailure.TooLarge, "The answer was too large.", e)
            e is org.apache.hc.core5.http.ProtocolException || e is org.apache.hc.core5.http.MessageConstraintException ->
                ServiceWireException(WireFailure.Malformed, "The service's answer was not valid HTTP.", e)
            e is IOException && stage == Stage.Answer -> ServiceWireException(WireFailure.NoAnswer, "The connection closed before an answer.", e)
            e is IOException -> ServiceWireException(WireFailure.Unreachable, "The connection failed.", e)
            e is StackOverflowError -> ServiceWireException(WireFailure.TooDeep, "The answer was not readable.")
            else -> ServiceWireException(WireFailure.Malformed, "The service's answer could not be read.", e)
        }
    }

    private inline fun <reified T : Throwable> hasCause(e: Throwable): Boolean {
        var c: Throwable? = e
        repeat(8) { if (c is T) return true; c = c?.cause }
        return false
    }

    companion object {
        val USER_AGENT = Regex("RingMASTER/[0-9A-Za-z.+-]{1,32}")
        /** Response headers a template's `header(...)` may read: counts some services send beside a list. */
        val READABLE_HEADERS = listOf("x-total-count", "x-total")
        private val KEPT_HEADERS = listOf("content-type", "retry-after", "location") + READABLE_HEADERS
        private val HEADER_NAME = Regex("[A-Za-z0-9-]{1,40}")
        /** Set by the wire itself or never allowed from a template. */
        private val FORBIDDEN_HEADERS = setOf(
            "host", "user-agent", "accept", "accept-encoding", "connection", "content-length", "content-type", "transfer-encoding",
            "te", "trailer", "upgrade", "expect", "cookie", "proxy-authorization", "proxy-connection", "keep-alive", "forwarded",
            "x-forwarded-for", "x-forwarded-host", "x-real-ip",
        )
        private val watchdog: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "service-wire-deadline").apply { isDaemon = true }
        }

        internal fun checkRequest(request: WireRequest) {
            val path = request.path
            if (!path.startsWith('/') || path.startsWith("//") || '#' in path || path.length > 2048 ||
                path.any { it.isISOControl() || it.isWhitespace() || it.code > 0x7e }) refuse(WireFailure.RequestRefused, "The request path is not allowed.")
            val route = path.substringBefore('?')
            if (route.split('/').any { it == ".." || it == "." || it.equals("%2e%2e", true) }) refuse(WireFailure.RequestRefused, "The request path is not allowed.")
            if (request.body != null && request.method != WireMethod.POST) refuse(WireFailure.RequestRefused, "Only a login may send a body.")
            for ((name, value) in request.headers) {
                if (!HEADER_NAME.matches(name) || name.lowercase() in FORBIDDEN_HEADERS || name.lowercase().startsWith("proxy-"))
                    refuse(WireFailure.RequestRefused, "A header the template may not set.")
                if (value.length > 8192 || value.any { it == '\r' || it == '\n' || it == '\u0000' }) refuse(WireFailure.RequestRefused, "A header value is not allowed.")
            }
        }

        private fun isJson(contentType: String?) = contentType == null || contentType.lowercase().contains("json")

        private fun read(input: InputStream, limit: Int): ByteArray {
            val bytes = input.readNBytes(limit + 1)
            if (bytes.size > limit) { bytes.fill(0); refuse(WireFailure.TooLarge, "The answer was too large (over ${limit / 1024} KiB).") }
            return bytes
        }

        internal fun redirectOf(origin: ServiceOrigin, location: String?): WireRedirect {
            if (location.isNullOrBlank()) return WireRedirect(false, null)
            val text = location.trim()
            if (text.startsWith('/') && !text.startsWith("//")) return WireRedirect(true, text.substringBefore('#'))
            val target = ServiceOrigin.parse(text.substringBefore('?').substringBefore('#')).getOrNull() ?: return WireRedirect(false, null)
            if (!target.sameEndpoint(origin)) return WireRedirect(false, null)
            val rest = text.substringAfter("://").let { a -> a.indexOf('/').let { if (it < 0) "/" else a.substring(it) } }
            return WireRedirect(true, rest.substringBefore('#'))
        }

        private fun refuse(failure: WireFailure, message: String): Nothing = throw ServiceWireException(failure, message)
    }
}
