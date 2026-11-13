package local.ringleader.core

import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import local.ringleader.core.tools.PortOutcome
import local.ringleader.core.tools.connectPort

enum class PortState { Open, Closed, NoAnswer }

/**
 * What one port of a guest answered. [secure] is true for TLS, false for plain HTTP, null when the port is not open or
 * answered neither. [untrusted] says in plain words why the certificate does not validate under normal TLS rules
 * ("self-signed"); it is null when it validates or when there was no TLS. Nothing here is ever turned into trust.
 */
data class ServiceAnswer(
    val host: String, val port: Int, val state: PortState,
    val secure: Boolean? = null, val status: Int? = null, val title: String? = null, val server: String? = null,
    val location: String? = null, val untrusted: String? = null,
)

data class FindProgress(val checked: Int, val total: Int, val answers: List<ServiceAnswer>, val finished: Boolean = false)

enum class TestLevel { Good, Warn, Bad }

/** The result of Test, in plain words. [fix] is a corrected address to offer, when there is one. [fromPage] means the message quotes the page's title. */
data class ServiceTest(val level: TestLevel, val message: String, val fix: String? = null, val fromPage: Boolean = false)

/** Ports where TLS is tried before plain HTTP when the catalogue says nothing. */
internal val TLS_FIRST_PORTS = setOf(443, 8006, 8007, 8043, 8443, 8971, 9443, 11443)

/** What each Find services and Test request says. Kept here so the dialog's help text and the tests quote the same thing. */
const val SERVICE_PROBE_REQUEST = "GET <path> HTTP/1.1 with Host, User-Agent: RingMASTER, Accept: text/html and Connection: close"

/**
 * Looks for web services on a guest, from this computer, only when the user asks. A TCP connect to each port, then at
 * most one GET per open port to tell http from https and read the page title. No credentials, cookies or request
 * bodies. Redirects are reported, never followed. TLS uses the system's trust store (or [trust] in tests) with the
 * host name checked: a certificate that does not validate is reported and the exchange stops there, so nothing is read
 * from or sent to a server whose certificate failed. Cancelling closes the sockets at once.
 */
class ServiceProbe(
    val connectTimeoutMs: Int = 800,
    val readTimeoutMs: Int = 2500,
    val concurrency: Int = 6,
    private val trust: KeyStore? = null,
) {
    init { require(concurrency in 1..16 && connectTimeoutMs in 50..10_000 && readTimeoutMs in 100..20_000) }

    /**
     * Connects to every host and port pair, a few at a time, and looks at each open one. [hint] gives the catalogue's
     * scheme and path for a port. Progress is sent after each pair.
     */
    fun find(hosts: List<String>, ports: List<Int>, hint: (Int) -> ServiceEndpoint? = { null }): Flow<FindProgress> = channelFlow {
        require(hosts.size in 1..4 && ports.size in 1..96 && ports.all { it in 1..65535 })
        val trace = Trace.begin(TraceSource.Tools, "Find services", mapOf("hosts" to hosts.size.toString(), "ports" to ports.size.toString()))
        try {
            val pairs = hosts.flatMap { h -> ports.map { h to it } }
            val lock = Mutex()
            var next = 0
            var checked = 0
            val answers = mutableListOf<ServiceAnswer>()
            send(FindProgress(0, pairs.size, emptyList()))
            coroutineScope {
                repeat(minOf(concurrency, pairs.size)) {
                    launch {
                        while (true) {
                            ensureActive()
                            val (host, port) = lock.withLock { pairs.getOrNull(next++) } ?: break
                            val found = when (withContext(Dispatchers.IO) { connectPort(host, port, connectTimeoutMs) }) {
                                is PortOutcome.Open -> hint(port).let { look(host, port, it?.secure, it?.path ?: "/") }
                                PortOutcome.Closed -> ServiceAnswer(host, port, PortState.Closed)
                                PortOutcome.Filtered -> ServiceAnswer(host, port, PortState.NoAnswer)
                            }
                            lock.withLock {
                                answers += found
                                checked++
                                send(FindProgress(checked, pairs.size, sortedAnswers(answers, hosts, ports)))
                            }
                        }
                    }
                }
            }
            send(FindProgress(checked, pairs.size, sortedAnswers(answers, hosts, ports), finished = true))
        } catch (t: Throwable) { trace.end(error = t); throw t } finally { trace.end() }
    }.flowOn(Dispatchers.IO)

    /** Checks one address the user typed: a connect, then the same look as [find], then a verdict in plain words. */
    suspend fun test(url: String): ServiceTest {
        val target = parseTarget(url) ?: return ServiceTest(TestLevel.Bad, "Use an http or https address with a host.")
        val outcome = withContext(Dispatchers.IO) { connectPort(target.host, target.port, maxOf(connectTimeoutMs, 2000)) }
        val answer = when (outcome) {
            is PortOutcome.Open -> look(target.host, target.port, target.secure, target.path)
            PortOutcome.Closed -> ServiceAnswer(target.host, target.port, PortState.Closed)
            PortOutcome.Filtered -> ServiceAnswer(target.host, target.port, PortState.NoAnswer)
        }
        return describeTest(url, answer)
    }

    /**
     * One look at an open port. [secure] is the scheme to try first (null: by port). TLS first: a validated handshake,
     * then the GET over TLS; a certificate failure is reported without a GET; a server that is not TLS gets the GET in
     * plain HTTP. Plain first: the GET; an answer that is not HTTP, or an error page saying the port wants TLS, is
     * followed by a handshake only.
     */
    suspend fun look(host: String, port: Int, secure: Boolean?, path: String = "/"): ServiceAnswer {
        val trace = Trace.begin(TraceSource.Tools, "Service look", mapOf("host" to host, "port" to port.toString()))
        try {
            val tlsFirst = secure ?: (port in TLS_FIRST_PORTS)
            if (tlsFirst) {
                return when (val tls = handshake(host, port, path, get = true)) {
                    is Handshake.Ok -> tls.reply.toAnswer(host, port, true)
                    is Handshake.Untrusted -> ServiceAnswer(host, port, PortState.Open, secure = true, untrusted = tls.reason)
                    is Handshake.Refused -> ServiceAnswer(host, port, tls.state)
                    Handshake.NotTls -> when (val plain = exchange(host, port, path)) {
                        is Reply.Http -> plain.toAnswer(host, port, false)
                        is Reply.Refused -> ServiceAnswer(host, port, plain.state)
                        Reply.NotHttp -> ServiceAnswer(host, port, PortState.Open)
                    }
                    Handshake.Failed -> ServiceAnswer(host, port, PortState.Open)
                }
            }
            val plain = exchange(host, port, path)
            if (plain is Reply.Refused) return ServiceAnswer(host, port, plain.state)
            if (plain is Reply.Http && !plain.wantsTls()) return plain.toAnswer(host, port, false)
            return when (val tls = handshake(host, port, path, get = false)) {
                is Handshake.Ok -> ServiceAnswer(host, port, PortState.Open, secure = true)
                is Handshake.Untrusted -> ServiceAnswer(host, port, PortState.Open, secure = true, untrusted = tls.reason)
                is Handshake.Refused -> ServiceAnswer(host, port, tls.state)
                Handshake.NotTls, Handshake.Failed -> if (plain is Reply.Http) plain.toAnswer(host, port, false) else ServiceAnswer(host, port, PortState.Open)
            }
        } catch (t: Throwable) { trace.end(error = t); throw t } finally { trace.end() }
    }

    private sealed interface Reply {
        data class Http(val status: Int, val server: String?, val location: String?, val body: String) : Reply
        data object NotHttp : Reply
        data class Refused(val state: PortState) : Reply
    }

    private sealed interface Handshake {
        data class Ok(val reply: Reply) : Handshake
        data class Untrusted(val reason: String) : Handshake
        data object NotTls : Handshake
        data object Failed : Handshake
        data class Refused(val state: PortState) : Handshake
    }

    private fun Reply.toAnswer(host: String, port: Int, secure: Boolean): ServiceAnswer = when (this) {
        is Reply.Http -> ServiceAnswer(host, port, PortState.Open, secure, status, ScanRules.title(body), server, location)
        is Reply.Refused -> ServiceAnswer(host, port, state)
        Reply.NotHttp -> ServiceAnswer(host, port, PortState.Open, secure = if (secure) true else null)
    }

    private fun Reply.Http.wantsTls(): Boolean {
        val text = body.lowercase()
        return status == 400 && ("plain http request was sent to https port" in text || "http request to an https server" in text ||
            "requires tls" in text || "speaking plain http to an ssl-enabled server" in text)
    }

    private suspend fun handshake(host: String, port: Int, path: String, get: Boolean): Handshake {
        val checker = CheckingTrust(baseTrust())
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(checker), null) }
        return withSocket(host, port, { Handshake.Refused(it) }, Handshake.Failed) { raw ->
            val tls = context.socketFactory.createSocket(raw, host, port, true) as SSLSocket
            tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            tls.soTimeout = readTimeoutMs
            try {
                tls.startHandshake()
            } catch (e: java.io.IOException) {
                // A plain HTTP server answers the ClientHello with text, or waits for a request line until the read times out.
                return@withSocket when {
                    checker.failure != null -> Handshake.Untrusted(untrustedReason(checker.chain, checker.failure))
                    checker.chain.isEmpty() -> Handshake.NotTls
                    else -> Handshake.Failed
                }
            }
            if (!get) Handshake.Ok(Reply.NotHttp) else Handshake.Ok(request(tls, host, port, path, secure = true))
        }
    }

    private suspend fun exchange(host: String, port: Int, path: String): Reply =
        withSocket(host, port, { Reply.Refused(it) }, Reply.NotHttp) { raw -> request(raw, host, port, path, secure = false) }

    private fun request(socket: Socket, host: String, port: Int, path: String, secure: Boolean): Reply {
        val literal = if (':' in host && !host.startsWith("[")) "[$host]" else host
        val hostHeader = if ((secure && port == 443) || (!secure && port == 80)) literal else "$literal:$port"
        socket.getOutputStream().apply {
            write("GET $path HTTP/1.1\r\nHost: $hostHeader\r\nUser-Agent: RingMASTER\r\nAccept: text/html\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            flush()
        }
        val data = java.io.ByteArrayOutputStream()
        val input = socket.getInputStream()
        val buffer = ByteArray(4096)
        val until = System.nanoTime() + readTimeoutMs * 1_000_000L
        try {
            while (data.size() < MAX_READ && System.nanoTime() < until) {
                val n = input.read(buffer, 0, minOf(buffer.size, MAX_READ - data.size()))
                if (n < 0) break
                data.write(buffer, 0, n)
                if (data.size() >= 5 && !data.toString(Charsets.ISO_8859_1).startsWith("HTTP/")) break
            }
        } catch (_: java.net.SocketTimeoutException) {
        } catch (_: java.io.IOException) {
            if (data.size() == 0) return Reply.NotHttp
        }
        val text = data.toString(Charsets.UTF_8)
        if (!text.startsWith("HTTP/")) return Reply.NotHttp
        val head = text.substringBefore("\r\n\r\n").lines()
        val status = head.first().split(' ').getOrNull(1)?.toIntOrNull() ?: return Reply.NotHttp
        fun header(name: String) = head.drop(1).firstOrNull { it.startsWith("$name:", true) }?.substringAfter(':')?.trim()?.take(200)
        return Reply.Http(status, header("Server"), header("Location"), text.substringAfter("\r\n\r\n", ""))
    }

    /** Opens a socket, runs [block] on it, and closes it as soon as the caller is cancelled, even mid-read. */
    private suspend fun <T> withSocket(host: String, port: Int, refused: (PortState) -> T, failed: T, block: (Socket) -> T): T = withContext(Dispatchers.IO) {
        val raw = Socket()
        val closer = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { runCatching { raw.close() } } }
        try {
            ensureActive()
            try {
                raw.connect(InetSocketAddress(host, port), connectTimeoutMs.coerceAtLeast(1000))
            } catch (_: java.net.SocketTimeoutException) {
                return@withContext refused(PortState.NoAnswer)
            } catch (_: java.net.ConnectException) {
                return@withContext refused(PortState.Closed)
            } catch (e: java.io.IOException) {
                currentCoroutineContext().ensureActive()
                return@withContext refused(PortState.NoAnswer)
            }
            raw.soTimeout = readTimeoutMs
            try { block(raw) } catch (e: CancellationException) { throw e } catch (e: java.io.IOException) {
                currentCoroutineContext().ensureActive()
                failed
            }
        } finally { closer.cancel(); runCatching { raw.close() } }
    }

    private fun baseTrust(): X509ExtendedTrustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).let { factory ->
        factory.init(trust)
        factory.trustManagers.filterIsInstance<X509ExtendedTrustManager>().first()
    }

    private companion object { const val MAX_READ = 32 * 1024 }
}

/** Open ports first (web pages before others), then in the order asked. */
private fun sortedAnswers(answers: List<ServiceAnswer>, hosts: List<String>, ports: List<Int>): List<ServiceAnswer> =
    answers.sortedWith(compareBy({ it.state != PortState.Open }, { it.secure == null }, { hosts.indexOf(it.host) }, { ports.indexOf(it.port) }))

/**
 * Normal TLS checking, with the chain and the failure kept so they can be described. It never accepts what the system's
 * trust manager refuses.
 */
private class CheckingTrust(private val base: X509ExtendedTrustManager) : X509ExtendedTrustManager() {
    @Volatile var chain: List<X509Certificate> = emptyList()
    @Volatile var failure: Exception? = null
    private inline fun check(certs: Array<X509Certificate>?, run: () -> Unit) {
        chain = certs?.toList().orEmpty()
        try { run() } catch (e: CertificateException) { failure = e; throw e }
    }
    override fun checkServerTrusted(certs: Array<X509Certificate>?, authType: String?) = check(certs) { base.checkServerTrusted(certs, authType) }
    override fun checkServerTrusted(certs: Array<X509Certificate>?, authType: String?, socket: Socket?) = check(certs) { base.checkServerTrusted(certs, authType, socket) }
    override fun checkServerTrusted(certs: Array<X509Certificate>?, authType: String?, engine: SSLEngine?) = check(certs) { base.checkServerTrusted(certs, authType, engine) }
    override fun checkClientTrusted(certs: Array<X509Certificate>?, authType: String?) = throw CertificateException("Not a server.")
    override fun checkClientTrusted(certs: Array<X509Certificate>?, authType: String?, socket: Socket?) = throw CertificateException("Not a server.")
    override fun checkClientTrusted(certs: Array<X509Certificate>?, authType: String?, engine: SSLEngine?) = throw CertificateException("Not a server.")
    override fun getAcceptedIssuers(): Array<X509Certificate> = base.acceptedIssuers
}

/** Why a certificate failed, in a few plain words. */
internal fun untrustedReason(chain: List<X509Certificate>, failure: Throwable?): String {
    val causes = generateSequence(failure) { it.cause }.toList()
    val leaf = chain.firstOrNull()
    return when {
        // The name is checked only after the chain validated, so a name failure means the chain itself was fine.
        causes.any { c -> c.message.orEmpty().let { "No subject alternative" in it || "No name matching" in it } } -> "made for another name"
        causes.any { it is CertificateExpiredException } -> "expired"
        causes.any { it is CertificateNotYetValidException } -> "not valid yet"
        leaf != null && chain.size == 1 && leaf.subjectX500Principal == leaf.issuerX500Principal -> "self-signed"
        else -> "not from an authority this computer trusts"
    }
}

private data class ProbeTarget(val host: String, val port: Int, val secure: Boolean, val path: String)

private fun parseTarget(url: String): ProbeTarget? {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
    val secure = when (uri.scheme?.lowercase()) { "https" -> true; "http" -> false; else -> return null }
    val host = uri.host?.removePrefix("[")?.removeSuffix("]")?.takeIf { it.isNotBlank() } ?: return null
    if (uri.rawUserInfo != null) return null
    val port = if (uri.port > 0) uri.port else if (secure) 443 else 80
    val path = uri.rawPath?.takeIf { it.startsWith("/") } ?: "/"
    return ProbeTarget(host, port, secure, path)
}

/** [url] with another scheme, keeping host, port and path. A default port is written out so the port stays the same. */
fun withScheme(url: String, secure: Boolean): String? =
    parseTarget(url)?.let { serviceAddress(it.host, ServiceEndpoint(it.port, secure, it.path)) }

/** The plain-words verdict for Test. Never names the host, so the line can stay visible in showcase mode. */
fun describeTest(url: String, answer: ServiceAnswer): ServiceTest {
    val asked = parseTarget(url) ?: return ServiceTest(TestLevel.Bad, "Use an http or https address with a host.")
    val port = answer.port
    when (answer.state) {
        PortState.Closed -> return ServiceTest(TestLevel.Bad, "Port $port is closed.")
        PortState.NoAnswer -> return ServiceTest(TestLevel.Bad, "No answer on port $port. The guest may be off, or a firewall drops it.")
        PortState.Open -> Unit
    }
    val secure = answer.secure ?: return ServiceTest(TestLevel.Bad, "Port $port is open, but it is not a web page.")
    if (secure != asked.secure) {
        val fix = withScheme(url, secure)
        val trust = answer.untrusted?.let { " Its certificate is not trusted: $it." }.orEmpty()
        return ServiceTest(TestLevel.Bad, "Port $port speaks ${if (secure) "https, not http" else "http, not https"}.$trust", fix)
    }
    answer.untrusted?.let {
        return ServiceTest(TestLevel.Warn, "The certificate is not trusted: $it. The browser will warn before it opens the page.")
    }
    val status = answer.status ?: return ServiceTest(TestLevel.Good, "Answers over https.")
    val known = identifyService(port, answer.title, answer.server)
    val label = known?.name ?: answer.title?.take(40) ?: "a web page"
    val fromPage = known == null && answer.title != null
    val onward = answer.location?.let { loc -> runCatching { URI(loc) }.getOrNull() }
    return when (status) {
        in 200..299 -> ServiceTest(TestLevel.Good, "Answers: $label.", fromPage = fromPage)
        in 300..399 -> {
            val where = onward?.rawPath?.takeIf { it.isNotEmpty() } ?: "another page"
            val other = onward?.takeIf { it.isAbsolute && it.host != null && (it.host.removePrefix("[").removeSuffix("]") != asked.host || (it.scheme == "https") != asked.secure) }
            val fix = other?.let { o -> runCatching { serviceAddress(o.host.removePrefix("[").removeSuffix("]"), ServiceEndpoint(if (o.port > 0) o.port else if (o.scheme == "https") 443 else 80, o.scheme == "https", o.rawPath?.takeIf { p -> p.startsWith("/") } ?: "/")) }.getOrNull() }
            ServiceTest(TestLevel.Good, if (other != null) "Answers: $label. It sends the browser to another address." else "Answers: $label. It sends the browser on to $where.", fix, fromPage)
        }
        401, 403 -> ServiceTest(TestLevel.Good, "Answers: $label. It asks for a sign-in.", fromPage = fromPage)
        404 -> ServiceTest(TestLevel.Warn, "Answers, but ${asked.path} was not found (HTTP 404).", if (asked.path != "/") withScheme(url, secure)?.let { root(it) } else null)
        in 500..599 -> ServiceTest(TestLevel.Warn, "Answers with a server error (HTTP $status).")
        else -> ServiceTest(TestLevel.Warn, "Answers with HTTP $status.")
    }
}

private fun root(url: String): String? = parseTarget(url)?.let { serviceAddress(it.host, ServiceEndpoint(it.port, it.secure, "/")) }
