package local.ringleader.core

import java.lang.management.ManagementFactory
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.time.Instant
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * Diagnostics and support bundle.
 * Designed to capture environment facts, route observations, network reachability,
 * and redacted traces for bug reports and troubleshooting across network boundaries.
 * CRITICAL SAFETY: Passwords, tokens, tickets, and user credentials are NEVER captured.
 */
data class DiagnosticSessionInfo(
    val address: String,
    /** "API token" or "Password": the type only, never the user or token name. */
    val authKind: String,
    val connected: Boolean,
    val overviewLoaded: Boolean,
    val lastError: String? = null,
    /** "pve" or "pdm". */
    val kind: String? = null,
    /** The certificate pin this connection uses (lower-case hex, public data), or null for normal TLS. Compared with the observed leaf; not written to the bundle. */
    val pin: String? = null,
    val routeInterface: String? = null,
    val routeGateway: String? = null,
    val routeKind: String? = null,
    val isVpn: Boolean? = null,
)

data class DiagnosticReachabilityInfo(
    val address: String,
    val host: String,
    val port: Int,
    val tcpSuccess: Boolean,
    val tcpLatencyMs: Long,
    val tcpError: String? = null,
    val tlsHandshakeSuccess: Boolean = false,
    val tlsSubjectCn: String? = null,
    val tlsIssuerCn: String? = null,
    val tlsNotBefore: String? = null,
    val tlsNotAfter: String? = null,
    val tlsSha256Fingerprint: String? = null,
    val tlsError: String? = null,
    /** Whether the observed leaf is the certificate the connection pins. Null when the connection has no pin or no leaf was read. */
    val tlsMatchesPin: Boolean? = null,
    val httpStatusCode: Int? = null,
    val httpVersionBody: String? = null,
    val httpError: String? = null,
)

data class DiagnosticReport(
    val timestamp: String,
    val appVersion: String,
    val platform: String,
    val osName: String,
    val osVersion: String,
    val osArch: String,
    val javaVersion: String,
    val javaVendor: String,
    val memoryUsedMb: Long,
    val memoryMaxMb: Long,
    val threadCount: Int,
    val renderApi: String? = null,
    val sessions: List<DiagnosticSessionInfo> = emptyList(),
    val reachability: List<DiagnosticReachabilityInfo> = emptyList(),
    val recentTrace: List<String> = emptyList(),
) {
    fun toJsonString(): String {
        val root = buildJsonObject {
            put("timestamp", timestamp)
            put("appVersion", appVersion)
            put("platform", platform)
            putJsonObject("system") {
                put("osName", osName)
                put("osVersion", osVersion)
                put("osArch", osArch)
                put("javaVersion", javaVersion)
                put("javaVendor", javaVendor)
                put("memoryUsedMb", memoryUsedMb)
                put("memoryMaxMb", memoryMaxMb)
                put("threadCount", threadCount)
                if (renderApi != null) put("renderApi", renderApi)
            }
            putJsonArray("sessions") {
                sessions.forEach { s ->
                    addJsonObject {
                        put("address", s.address)
                        put("authKind", s.authKind)
                        s.kind?.let { put("kind", it) }
                        put("certificatePinned", s.pin != null)
                        put("connected", s.connected)
                        put("overviewLoaded", s.overviewLoaded)
                        s.lastError?.let { put("lastError", it) }
                        s.routeInterface?.let { put("routeInterface", it) }
                        s.routeGateway?.let { put("routeGateway", it) }
                        s.routeKind?.let { put("routeKind", it) }
                        s.isVpn?.let { put("isVpn", it) }
                    }
                }
            }
            putJsonArray("reachability") {
                reachability.forEach { r ->
                    addJsonObject {
                        put("address", r.address)
                        put("host", r.host)
                        put("port", r.port)
                        put("tcpSuccess", r.tcpSuccess)
                        put("tcpLatencyMs", r.tcpLatencyMs)
                        r.tcpError?.let { put("tcpError", it) }
                        put("tlsHandshakeSuccess", r.tlsHandshakeSuccess)
                        r.tlsSubjectCn?.let { put("tlsSubjectCn", it) }
                        r.tlsIssuerCn?.let { put("tlsIssuerCn", it) }
                        r.tlsNotBefore?.let { put("tlsNotBefore", it) }
                        r.tlsNotAfter?.let { put("tlsNotAfter", it) }
                        r.tlsSha256Fingerprint?.let { put("tlsSha256Fingerprint", it) }
                        r.tlsError?.let { put("tlsError", it) }
                        r.tlsMatchesPin?.let { put("tlsMatchesPin", it) }
                        r.httpStatusCode?.let { put("httpStatusCode", it) }
                        r.httpVersionBody?.let { put("httpVersionBody", it) }
                        r.httpError?.let { put("httpError", it) }
                    }
                }
            }
            putJsonArray("recentTrace") {
                // Each trace line is already redacted JSON: keep it as an object, not as a quoted string.
                recentTrace.forEach { line -> add(runCatching { Json.parseToJsonElement(line) }.getOrElse { JsonPrimitive(line) }) }
            }
        }
        return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), root)
    }
}

/**
 * Read timeout of the diagnostic `GET /version`. The GET carries no credentials, and pveproxy answers every unauthenticated
 * request only after a deliberate 3-second delay (pve-http-server, src/PVE/APIServer/AnyEvent.pm: "always delay unauthorized
 * calls by 3 seconds"). With 3000 ms a field bundle showed `SocketTimeoutException: Read timed out` against a healthy server;
 * 6000 ms covers the delay and a slow link.
 */
internal const val DIAGNOSTIC_HTTP_READ_TIMEOUT_MS = 6_000
/** The bundle keeps 500 characters of the answer; at most this many bytes are read. */
private const val DIAGNOSTIC_BODY_BYTES = 2048

suspend fun probeDiagnosticReachability(address: String, timeoutMs: Int = 3000): DiagnosticReachabilityInfo = withContext(Dispatchers.IO) {
    // The same host and port the connection uses: an origin without a port means 443.
    val target = routeTarget(address) ?: return@withContext DiagnosticReachabilityInfo(
        address = address, host = "", port = 0, tcpSuccess = false, tcpLatencyMs = 0, tcpError = "Not an HTTPS server address")
    val host = target.host
    val port = target.port

    var tcpSuccess = false
    var tcpDurationMs = 0L
    var tcpError: String? = null
    val startTcp = System.nanoTime()

    val rawSocket = Socket()
    try {
        rawSocket.connect(InetSocketAddress(host, port), timeoutMs)
        tcpSuccess = true
        tcpDurationMs = (System.nanoTime() - startTcp) / 1_000_000
    } catch (e: Exception) {
        tcpDurationMs = (System.nanoTime() - startTcp) / 1_000_000
        tcpError = "${e.javaClass.simpleName}: ${e.message}"
    }

    if (!tcpSuccess) {
        runCatching { rawSocket.close() }
        return@withContext DiagnosticReachabilityInfo(
            address = address,
            host = host,
            port = port,
            tcpSuccess = false,
            tcpLatencyMs = tcpDurationMs,
            tcpError = tcpError,
        )
    }

    var tlsSuccess = false
    var tlsSubjectCn: String? = null
    var tlsIssuerCn: String? = null
    var tlsNotBefore: String? = null
    var tlsNotAfter: String? = null
    var tlsSha256: String? = null
    var tlsError: String? = null

    try {
        val tls = scanTlsContext()
        val sslSocket = tls.socketFactory.createSocket(rawSocket, host, port, true) as SSLSocket
        sslSocket.soTimeout = timeoutMs
        sslSocket.startHandshake()
        val cert = sslSocket.session.peerCertificates.firstOrNull() as? X509Certificate
        if (cert != null) {
            tlsSuccess = true
            tlsSubjectCn = cert.subjectX500Principal.name
            tlsIssuerCn = cert.issuerX500Principal.name
            tlsNotBefore = cert.notBefore.toInstant().toString()
            tlsNotAfter = cert.notAfter.toInstant().toString()
            tlsSha256 = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }
        }
    } catch (e: Exception) {
        tlsError = "${e.javaClass.simpleName}: ${e.message}"
    } finally {
        runCatching { rawSocket.close() }
    }

    var httpStatus: Int? = null
    var httpBody: String? = null
    var httpErr: String? = null

    if (tlsSuccess) {
        try {
            val url = URI("https", null, host, port, "/api2/json/version", null, null).toURL()
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = maxOf(timeoutMs, DIAGNOSTIC_HTTP_READ_TIMEOUT_MS)
                // Trust-all and credential-free, so it never goes anywhere else: a redirect is recorded as its status.
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                if (this is HttpsURLConnection) {
                    sslSocketFactory = scanTlsContext().socketFactory
                    hostnameVerifier = HostnameVerifier { _, _ -> true }
                }
            }
            httpStatus = conn.responseCode
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { it.readNBytes(DIAGNOSTIC_BODY_BYTES) }?.let { bytes -> String(bytes, Charsets.UTF_8) }
            if (text != null) {
                httpBody = text.take(500)
            }
        } catch (e: Exception) {
            httpErr = "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    DiagnosticReachabilityInfo(
        address = address,
        host = host,
        port = port,
        tcpSuccess = true,
        tcpLatencyMs = tcpDurationMs,
        tcpError = null,
        tlsHandshakeSuccess = tlsSuccess,
        tlsSubjectCn = tlsSubjectCn,
        tlsIssuerCn = tlsIssuerCn,
        tlsNotBefore = tlsNotBefore,
        tlsNotAfter = tlsNotAfter,
        tlsSha256Fingerprint = tlsSha256,
        tlsError = tlsError,
        httpStatusCode = httpStatus,
        httpVersionBody = httpBody,
        httpError = httpErr,
    )
}

suspend fun createDiagnosticReport(
    appVersion: String,
    renderApi: String? = null,
    sessions: List<DiagnosticSessionInfo> = emptyList(),
    targetAddresses: List<String> = emptyList(),
    recentTraceLimit: Int = 60,
): DiagnosticReport {
    val runtime = Runtime.getRuntime()
    val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
    val maxMemory = runtime.maxMemory() / (1024 * 1024)
    val threads = ManagementFactory.getThreadMXBean().threadCount

    val pins = sessions.filter { it.pin != null }.associate { it.address to it.pin }
    val reachabilityReports = (sessions.map { it.address } + targetAddresses).filter { it.isNotBlank() }.distinct().map { addr ->
        val seen = probeDiagnosticReachability(addr)
        val leaf = seen.tlsSha256Fingerprint?.replace(":", "")?.lowercase()
        if (leaf != null && pins[addr] != null) seen.copy(tlsMatchesPin = leaf == pins[addr]) else seen
    }

    val traces = Trace.snapshot().takeLast(recentTraceLimit).map { Trace.jsonLine(it) }

    return DiagnosticReport(
        timestamp = Instant.now().toString(),
        appVersion = appVersion,
        platform = platformLabel(),
        osName = System.getProperty("os.name").orEmpty(),
        osVersion = System.getProperty("os.version").orEmpty(),
        osArch = System.getProperty("os.arch").orEmpty(),
        javaVersion = System.getProperty("java.version").orEmpty(),
        javaVendor = System.getProperty("java.vendor").orEmpty(),
        memoryUsedMb = usedMemory,
        memoryMaxMb = maxMemory,
        threadCount = threads,
        renderApi = renderApi,
        sessions = sessions,
        reachability = reachabilityReports,
        recentTrace = traces,
    )
}
