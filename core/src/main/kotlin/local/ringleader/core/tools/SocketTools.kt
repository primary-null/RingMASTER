package local.ringleader.core.tools

import local.ringleader.core.Trace
import local.ringleader.core.TraceSource

import java.net.ConnectException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.time.format.DateTimeFormatter
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLParameters
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class PortCheckPlugin : ToolPlugin {
    override val id = "port-check"
    override val name = "Port check"
    override val summary = "TCP connect to a host and a list of ports."
    override val inputs = listOf(
        ToolInput("host", "Host", ToolInputKind.Host, "127.0.0.1", "Hostname or IP address"),
        ToolInput("ports", "Ports", ToolInputKind.Text, "22,80,443,8006", "Up to 32 ports, separated by commas"),
        ToolInput("timeout", "Timeout (ms)", ToolInputKind.Number, "1000", "How long to wait for each port", min = 100, max = 10000),
    )

    override fun available() = ToolAvailability(true)

    override suspend fun run(values: Map<String, String>, emit: (ToolEvent) -> Unit): ToolResult {
        val clean = try { validateToolValues(inputs, values) } catch (e: ToolInputException) { return ToolResult(false, e.message ?: "Check the inputs.") }
        val ports = try { parsePortList(clean.getValue("ports")) } catch (e: ToolInputException) { return ToolResult(false, e.message ?: "Check the ports.") }
        val host = clean.getValue("host")
        val timeout = clean.getValue("timeout").toInt()
        emit(ToolEvent.Status("Checking $host"))
        val results = mutableListOf<String>()
        try {
            for (index in ports.indices) {
                coroutineContext.ensureActive()
                val port = ports[index]
                val outcome = withContext(Dispatchers.IO) { connectPort(host, port, timeout) }
                val line = when (outcome) {
                    is PortOutcome.Open -> "$port open ${formatNumber(outcome.millis)} ms"
                    PortOutcome.Closed -> "$port closed"
                    PortOutcome.Filtered -> "$port filtered"
                }
                results += line
                emit(ToolEvent.Line(line))
                if (outcome is PortOutcome.Open) emit(ToolEvent.Sample("connect", (index + 1).toDouble(), outcome.millis, "ms"))
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            return ToolResult(false, oneLine(e.message ?: "Port check failed."))
        }
        val open = results.count { " open " in it }
        return ToolResult(true, "$open of ${ports.size} ports open", mapOf("open" to open.toString(), "checked" to ports.size.toString()))
    }
}

internal sealed class PortOutcome {
    data class Open(val millis: Double) : PortOutcome()
    data object Closed : PortOutcome()
    data object Filtered : PortOutcome()
}

internal fun connectPort(host: String, port: Int, timeoutMs: Int): PortOutcome {
    val trace = Trace.begin(TraceSource.Tools, "Socket connect", mapOf("host" to host, "port" to port.toString()))
    val started = System.nanoTime()
    return try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            PortOutcome.Open((System.nanoTime() - started) / 1_000_000.0)
        }
    } catch (_: SocketTimeoutException) {
        PortOutcome.Filtered
    } catch (_: ConnectException) {
        PortOutcome.Closed
    } catch (_: NoRouteToHostException) {
        PortOutcome.Filtered
    } catch (e: Exception) {
        val text = e.message.orEmpty()
        if (text.contains("refused", ignoreCase = true)) PortOutcome.Closed else PortOutcome.Filtered
    } finally { trace.end() }
}

/** [resolve] and [reverse] are the system resolver; tests replace them. */
class DnsPlugin(
    private val resolve: (String) -> Array<InetAddress> = { InetAddress.getAllByName(it) },
    private val reverse: (InetAddress) -> String? = { InetAddress.getByAddress(it.address).canonicalHostName },
) : ToolPlugin {
    override val id = "dns"
    override val name = "DNS lookup"
    override val summary = "A and AAAA records, and an optional reverse lookup."
    override val inputs = listOf(
        ToolInput("name", "Name", ToolInputKind.Host, "localhost", "Hostname or IP address"),
        ToolInput("reverse", "Reverse lookup", ToolInputKind.Toggle, "false", "Also look up the names of the addresses"),
        ToolInput("timeout", "Timeout (s)", ToolInputKind.Number, "5", "How long to wait for an answer", min = 1, max = 30),
    )

    override fun available() = ToolAvailability(true)

    override suspend fun run(values: Map<String, String>, emit: (ToolEvent) -> Unit): ToolResult = withContext(Dispatchers.IO) {
        val clean = try { validateToolValues(inputs, values) } catch (e: ToolInputException) { return@withContext ToolResult(false, e.message ?: "Check the inputs.") }
        val name = clean.getValue("name")
        val seconds = clean.getValue("timeout").toLong()
        emit(ToolEvent.Status("Looking up $name"))
        val started = System.nanoTime()
        val addresses = try {
            Trace.call(TraceSource.Tools, "DNS lookup", mapOf("host" to name)) { withDeadline(seconds * 1000) { resolve(name) } }
                ?: return@withContext ToolResult(false, "No answer for $name within $seconds s.")
        } catch (_: UnknownHostException) {
            return@withContext ToolResult(false, "No address for $name.")
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            return@withContext ToolResult(false, oneLine(e.message ?: "DNS lookup failed."))
        }
        val millis = (System.nanoTime() - started) / 1_000_000.0
        addresses.forEach { address ->
            ensureActive()
            val kind = if (address is Inet6Address) "AAAA" else "A"
            emit(ToolEvent.Line("$kind ${address.hostAddress}"))
        }
        if (clean["reverse"] == "true") {
            // One deadline for all reverse lookups together.
            val until = System.nanoTime() + seconds * 1_000_000_000
            addresses.forEach { address ->
                ensureActive()
                val left = (until - System.nanoTime()) / 1_000_000
                val answer = if (left <= 0) null else runCatching { withDeadline(left) { reverse(address) ?: "" } }.getOrElse { e -> if (e is CancellationException) throw e; "" }
                val shown = when {
                    answer == null -> "no answer in time"
                    answer.isBlank() || answer == address.hostAddress -> "no name"
                    else -> answer
                }
                emit(ToolEvent.Line("PTR ${address.hostAddress} $shown"))
            }
        }
        val count = addresses.count { it is Inet4Address || it is Inet6Address }
        val summary = "${count} ${if (count == 1) "address" else "addresses"} in ${formatNumber(millis)} ms"
        ToolResult(count > 0, summary, mapOf("count" to count.toString(), "ms" to formatNumber(millis)))
    }
}

private val lookupThreads = Executors.newCachedThreadPool { task -> Thread(task, "ringmaster-dns").apply { isDaemon = true } }

/**
 * Runs a blocking call such as a system DNS lookup on its own thread and waits at most [timeoutMs]. Null when the time
 * runs out. Cancelling returns at once. The JDK resolver cannot be interrupted, so a late answer is dropped.
 */
internal suspend fun <T : Any> withDeadline(timeoutMs: Long, block: () -> T): T? {
    val future = CompletableFuture.supplyAsync(block, lookupThreads)
    return try { withTimeoutOrNull(timeoutMs.coerceAtLeast(1)) { future.await() } } finally { if (!future.isDone) future.cancel(false) }
}

class HttpTlsPlugin : ToolPlugin {
    override val id = "http-tls"
    override val name = "HTTP / TLS"
    override val summary = "Request a URL and report the status, timing and certificate."
    override val inputs = listOf(
        ToolInput("url", "URL", ToolInputKind.Text, "https://example.com", "http or https URL"),
        ToolInput("method", "Method", ToolInputKind.Choice, "HEAD", "HEAD or GET", choices = listOf("HEAD", "GET")),
        ToolInput("timeout", "Timeout (s)", ToolInputKind.Number, "10", "How long to wait", min = 1, max = 60),
        ToolInput("insecure", "Accept any certificate", ToolInputKind.Toggle, "false", "Inspection only. The certificate is not verified."),
    )

    override fun available() = ToolAvailability(true)

    override suspend fun run(values: Map<String, String>, emit: (ToolEvent) -> Unit): ToolResult = withContext(Dispatchers.IO) {
        val clean = try { validateToolValues(inputs, values) } catch (e: ToolInputException) { return@withContext ToolResult(false, e.message ?: "Check the inputs.") }
        val uri = try { validateHttpUrl(clean.getValue("url")) } catch (e: ToolInputException) { return@withContext ToolResult(false, e.message ?: "Check the URL.") }
        val insecure = clean["insecure"] == "true"
        val timeoutMs = clean.getValue("timeout").toLong() * 1000
        emit(ToolEvent.Status("Requesting ${uri.host}"))
        try {
            inspectHttp(uri, clean.getValue("method"), timeoutMs, insecure, emit)
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            ToolResult(false, oneLine(e.message ?: "The request failed."))
        }
    }
}

internal fun validateHttpUrl(raw: String): URI {
    if (raw.any { it.isISOControl() || it.isWhitespace() }) throw ToolInputException("Enter an http or https URL.")
    val uri = try { URI(raw) } catch (_: Exception) { throw ToolInputException("Enter an http or https URL.") }
    if (uri.scheme != "http" && uri.scheme != "https") throw ToolInputException("Enter an http or https URL.")
    val host = uri.host ?: throw ToolInputException("Enter an http or https URL.")
    if (!isHostToken(host)) throw ToolInputException("URL host must be a hostname or an IP address.")
    if (uri.userInfo != null) throw ToolInputException("Enter an http or https URL without a username.")
    return uri
}

/** An IP literal is reached directly, so the report describes that address and not a proxy. A hostname can use the system proxy. */
internal fun bypassProxy(host: String): Boolean = isIpv4(host) || isIpv6(host.removePrefix("[").removeSuffix("]"))

internal fun sha256Fingerprint(cert: X509Certificate): String =
    MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }

private fun inspectHttp(uri: URI, method: String, timeoutMs: Long, insecure: Boolean, emit: (ToolEvent) -> Unit): ToolResult {
    val trace = Trace.begin(TraceSource.Tools, "$method HTTP inspection", mapOf("url" to local.ringleader.core.Redact.url(uri.toString())))
    try {
    val capture = CertCapture(insecure)
    val context = SSLContext.getInstance("TLS")
    context.init(null, arrayOf(capture), SecureRandom())
    val parameters = SSLParameters(context.defaultSSLParameters.cipherSuites, context.defaultSSLParameters.protocols)
    parameters.endpointIdentificationAlgorithm = if (insecure || uri.scheme != "https") "" else "HTTPS"
    val client = java.net.http.HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofMillis(timeoutMs))
        .sslContext(context)
        .sslParameters(parameters)
        .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
        .version(java.net.http.HttpClient.Version.HTTP_1_1)
        .apply { if (bypassProxy(uri.host)) proxy(java.net.http.HttpClient.Builder.NO_PROXY) }
        .build()
    val request = java.net.http.HttpRequest.newBuilder(uri)
        .timeout(java.time.Duration.ofMillis(timeoutMs))
        .header("User-Agent", "RingMASTER")
        .method(if (method == "GET") "GET" else "HEAD", java.net.http.HttpRequest.BodyPublishers.noBody())
        .build()
    return client.use { http ->
        val started = System.nanoTime()
        try {
            val response = http.send(request, java.net.http.HttpResponse.BodyHandlers.ofInputStream())
            trace.end(status=response.statusCode())
            val millis = (System.nanoTime() - started) / 1_000_000.0
            try {
                reportHttp(response.statusCode(), millis, capture, insecure, emit)
            } finally { runCatching { response.body().close() } }
        } catch (e: Exception) {
            if (e is CancellationException || capture.chain.isEmpty()) throw e
            emitCertificate(capture, insecure, emit)
            val reason = if (insecure) "The request failed." else "The certificate was not verified."
            ToolResult(false, "$reason ${oneLine(e.message ?: "")}".trim(), certificateMetrics(capture, insecure))
        }
    }
    } catch(t: Throwable) { trace.end(error=t); throw t } finally { trace.end() }
}

private fun reportHttp(status: Int, millis: Double, capture: CertCapture, insecure: Boolean, emit: (ToolEvent) -> Unit): ToolResult {
    val protocol = capture.protocol()
    val cipher = capture.cipher()
    emit(ToolEvent.Line("HTTP $status in ${formatNumber(millis)} ms"))
    if (protocol != null || cipher != null) emit(ToolEvent.Line(listOfNotNull(protocol, cipher).joinToString(" ")))
    emitCertificate(capture, insecure, emit)
    val leaf = capture.chain.firstOrNull()
    val label = if (insecure) "not verified" else if (capture.chain.isEmpty()) "" else "verified"
    val summary = listOf(
        "$status in ${formatNumber(millis)} ms",
        protocol,
        cipher,
        leaf?.subjectX500Principal?.name,
        label.takeIf { it.isNotBlank() },
    ).filter { !it.isNullOrBlank() }.joinToString(" · ")
    val metrics = certificateMetrics(capture, insecure) + mapOf("status" to status.toString(), "ttfb" to formatNumber(millis))
    return ToolResult(true, summary, metrics)
}

private fun emitCertificate(capture: CertCapture, insecure: Boolean, emit: (ToolEvent) -> Unit) {
    val leaf = capture.chain.firstOrNull() ?: return
    emit(ToolEvent.Line("subject ${leaf.subjectX500Principal.name}"))
    emit(ToolEvent.Line("issuer ${leaf.issuerX500Principal.name}"))
    val expiry = DateTimeFormatter.ISO_LOCAL_DATE.format(leaf.notAfter.toInstant().atOffset(ZoneOffset.UTC))
    emit(ToolEvent.Line("expires $expiry"))
    emit(ToolEvent.Line("SHA-256 ${sha256Fingerprint(leaf)}"))
    emit(ToolEvent.Line(if (insecure) "certificate not verified" else "certificate verified"))
}

private fun certificateMetrics(capture: CertCapture, insecure: Boolean): Map<String, String> {
    val leaf = capture.chain.firstOrNull()
    return mapOf(
        "protocol" to (capture.protocol() ?: ""),
        "cipher" to (capture.cipher() ?: ""),
        "subject" to (leaf?.subjectX500Principal?.name ?: ""),
        "issuer" to (leaf?.issuerX500Principal?.name ?: ""),
        "expires" to (leaf?.let { DateTimeFormatter.ISO_LOCAL_DATE.format(it.notAfter.toInstant().atOffset(ZoneOffset.UTC)) } ?: ""),
        "fingerprint" to (leaf?.let(::sha256Fingerprint) ?: ""),
        "verified" to if (insecure || leaf == null) "false" else "true",
    )
}

/**
 * Trust decisions stay inside this object. It is created for one request and then dropped.
 * When [trustAll] is set, the certificate is accepted for this inspection only and labelled "not verified".
 */
private class CertCapture(private val trustAll: Boolean) : X509ExtendedTrustManager() {
    var chain: List<X509Certificate> = emptyList()
    private var engine: SSLEngine? = null
    private var protocolSeen: String? = null
    private var cipherSeen: String? = null
    private val delegate: X509ExtendedTrustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).let { factory ->
        factory.init(null as KeyStore?)
        factory.trustManagers.filterIsInstance<X509ExtendedTrustManager>().first()
    }

    fun protocol(): String? = usable(engine?.session?.protocol) ?: usable(engine?.handshakeSession?.protocol) ?: protocolSeen
    fun cipher(): String? = usable(engine?.session?.cipherSuite) ?: usable(engine?.handshakeSession?.cipherSuite) ?: cipherSeen

    private fun usable(value: String?): String? = value?.takeIf { it.isNotBlank() && it != "NONE" && "NULL" !in it }

    private fun take(certs: Array<X509Certificate>?, socketOrEngine: Any?) {
        if (!certs.isNullOrEmpty()) chain = certs.toList()
        when (socketOrEngine) {
            is SSLEngine -> {
                engine = socketOrEngine
                protocolSeen = usable(socketOrEngine.handshakeSession?.protocol) ?: protocolSeen
                cipherSeen = usable(socketOrEngine.handshakeSession?.cipherSuite) ?: cipherSeen
            }
            is javax.net.ssl.SSLSocket -> {
                protocolSeen = usable(socketOrEngine.session?.protocol) ?: protocolSeen
                cipherSeen = usable(socketOrEngine.session?.cipherSuite) ?: cipherSeen
            }
        }
    }

    private fun check(certs: Array<X509Certificate>?, authType: String?, socketOrEngine: Any?) {
        take(certs, socketOrEngine)
        if (trustAll) return
        when (socketOrEngine) {
            is SSLEngine -> delegate.checkServerTrusted(certs, authType, socketOrEngine)
            is java.net.Socket -> delegate.checkServerTrusted(certs, authType, socketOrEngine)
            else -> delegate.checkServerTrusted(certs, authType)
        }
    }

    override fun checkClientTrusted(certs: Array<X509Certificate>?, authType: String?) = Unit
    override fun checkServerTrusted(certs: Array<X509Certificate>?, authType: String?) = check(certs, authType, null)
    override fun checkServerTrusted(certs: Array<X509Certificate>?, authType: String?, socket: java.net.Socket?) = check(certs, authType, socket)
    override fun checkServerTrusted(certs: Array<X509Certificate>?, authType: String?, engine: SSLEngine?) = check(certs, authType, engine)
    override fun checkClientTrusted(certs: Array<X509Certificate>?, authType: String?, socket: java.net.Socket?) = Unit
    override fun checkClientTrusted(certs: Array<X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
    override fun getAcceptedIssuers(): Array<X509Certificate> = if (trustAll) emptyArray() else delegate.acceptedIssuers
}
