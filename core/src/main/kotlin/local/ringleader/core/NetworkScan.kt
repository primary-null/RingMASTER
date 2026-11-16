package local.ringleader.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.*
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.*

data class ScanInterface(val name: String, val ip: String, val prefix: Int, val up: Boolean = true,
    /** The system's interface number and the device name Java reports, to match what the system says about the card ([NetworkFacts]). */
    val index: Int? = null, val deviceName: String? = null)
data class ScanNetwork(val source: ScanInterface, val cidr: String?, val reason: String?, val addresses: List<String> = emptyList(),
    /** The network as it really is, when [cidr] is only a part of it. */
    val realCidr: String? = null, val origin: PlanSource = PlanSource.Attached,
    /** True for a network this computer is attached to: its neighbours can be asked, and its addresses are one hop away. */
    val onLink: Boolean = false,
    /** The ports to probe on this network's addresses. Null: the scan's own. */
    val ports: List<Int>? = null)
data class NetworkPlan(val networks: List<ScanNetwork>) {
    val addresses get() = networks.flatMap { it.addresses }.distinct()
}
enum class ScanKind { PVE, PBS, PDM, PIHOLE, ADGUARD, HOME_ASSISTANT, WEB }
data class ScanHit(val ip: String, val port: Int, val secure: Boolean, val kind: ScanKind,
    val title: String? = null, val fingerprint: String? = null, val subject: String? = null, val saved: Boolean = false,
    /** Leaf certificate issuer (RFC 2253) and expiry (epoch milliseconds), as observed. Neither is verified. */
    val issuer: String? = null, val notAfter: Long? = null) {
    val origin get() = "${if (secure) "https" else "http"}://$ip:$port"
    val proxmox get() = kind in setOf(ScanKind.PVE, ScanKind.PBS, ScanKind.PDM)
}
data class ScanPeek(val status: Int, val body: String, val server: String = "", val location: String? = null,
    val fingerprint: String? = null, val subject: String? = null, val issuer: String? = null, val notAfter: Long? = null)
data class NetworkProgress(val plan: NetworkPlan, val scanned: Int = 0, val current: String = "",
    val hits: List<ScanHit> = emptyList(), val finished: Boolean = false,
    /** What the sweep is doing now, how many addresses answered something, and how far each network is. */
    val phase: ScanPhase = ScanPhase.Sweeping, val alive: Int = 0, val tallies: List<NetworkTally> = emptyList(),
    /** Things the person should know about how this scan went (the neighbour table could not be read). */
    val notes: List<String> = emptyList(),
    /** How far each step is, every host that proved to be there with how and when, and the milliseconds since the scan started. */
    val steps: ScanSteps = ScanSteps(), val findings: List<HostFinding> = emptyList(), val elapsedMs: Long = 0) { val total get() = plan.addresses.size }

/** Proxmox VE, Backup Server and Datacenter Manager listen here. Product names count as identity only on these ports. */
val PROXMOX_PORTS = listOf(8006, 8007, 8443)
/** Ports the LAN sweep probes, in order: Proxmox, then web, AdGuard Home's first-run page and Home Assistant. */
val LAN_SCAN_PORTS = PROXMOX_PORTS + listOf(80, 443, 3000, 8123)
/** These ports are asked for plain HTTP first. */
internal val PLAIN_HTTP_PORTS = setOf(80, 3000, 8123)
/** A LAN answers a connect quickly; a filtered port costs the whole wait, so the sweep keeps it short. */
const val LAN_CONNECT_TIMEOUT_MS = 400
/** Tailnet peers can be relayed or far away, and the list is short, so they get longer. */
const val TAILNET_CONNECT_TIMEOUT_MS = 1500

object ScanRules {
    fun ipv4(ip: String): Long? {
        val parts = ip.split('.')
        if (parts.size != 4) return null
        // ASCII digits only: toIntOrNull also reads a leading '+' or '-' and digits of other scripts.
        val numbers = parts.map { part -> part.takeIf { p -> p.isNotEmpty() && p.all { c -> c in '0'..'9' } }?.toIntOrNull()?.takeIf { n -> n in 0..255 } ?: return null }
        return numbers.fold(0L) { a, n -> (a shl 8) or n.toLong() }
    }
    private fun format(n: Long) = (3 downTo 0).joinToString(".") { ((n shr (it * 8)) and 255).toString() }
    /**
     * The plan for this computer's own cards alone, with no routes, no known servers and no remembered choices: what the sweep
     * has always taken, as one network per card (cut to the /24 around the card's address), and the cards left out with a reason.
     * [ScanPlanner.plan] is the full plan.
     */
    fun plan(interfaces: List<ScanInterface>): NetworkPlan = ScanPlanner.plan(interfaces).toNetworkPlan()
    /**
     * A subnet the user typed for the sweep, as `a.b.c.d` or `a.b.c.d/prefix`: the main network [ScanTyped] makes of it. A private
     * network down to a /22 is swept as typed, a `/32` is that one host, anything wider is cut down to the /24 around the typed
     * address; a network that is not private is swept on the Proxmox ports only. Null when the text is not such a network
     * (not IPv4, not a unicast network, or Tailscale/CGNAT space, which is never swept).
     */
    fun planSubnet(cidr: String, label: String = "Additional subnet"): ScanNetwork? {
        val trimmed = cidr.trim()
        if (trimmed.isEmpty()) return null
        val n = ipv4(trimmed.substringBefore('/')) ?: return null
        val asked = if ('/' in trimmed) trimmed.substringAfter('/').takeIf { p -> p.isNotEmpty() && p.all { c -> c in '0'..'9' } }?.toIntOrNull()?.takeIf { it in 1..32 } ?: return null else 24
        val private = (n ushr 24 == 10L || n ushr 20 == 0xac1L || n ushr 16 == 0xc0a8L)
        val main = (ScanTyped.network(n, asked, private) as? TypedNetwork.Rows)?.rows?.firstOrNull() ?: return null
        val sweep = parseCidr(main.sweep!!) ?: return null
        return ScanNetwork(ScanInterface(label, format(sweep.first), sweep.second, up = true), main.sweep, null, main.addresses, realCidr = main.real, origin = PlanSource.Typed, ports = main.ports)
    }
    /** True when [planSubnet] sweeps less than the text asked for (a /16 becomes one /24, a /23 or /22 of public space too). */
    fun subnetNarrowed(cidr: String): Boolean = (ScanTyped.parse(cidr) as? TypedNetwork.Rows)?.rows?.firstOrNull()?.let { it.realPrefix < (parseCidr(it.sweep!!)?.second ?: 0) } == true
    fun title(body: String): String? = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        .find(body)?.groupValues?.get(1)?.replace(Regex("\\s+"), " ")?.replace("&amp;", "&")?.trim()?.take(60)
    fun classify(ip: String, port: Int, secure: Boolean, p: ScanPeek): ScanHit? {
        if (p.status !in 200..399 && p.status != 401) return null
        val text = "${p.body} ${p.server} ${p.subject}".lowercase()
        val server = p.server.lowercase()
        // Any page can name a product (a wiki, a proxy error page). The names count only on the products' own ports;
        // elsewhere only the product's own Server header does.
        val home = port in PROXMOX_PORTS
        val kind = when {
            "pbs-api" in server || (home && "proxmox backup server" in text) -> ScanKind.PBS
            "pdm-api" in server || (home && "proxmox datacenter manager" in text) -> ScanKind.PDM
            "pve-api-daemon" in server || (home && ("proxmox virtual environment" in text || "ou=pve cluster node" in text)) -> ScanKind.PVE
            "pi-hole" in text -> ScanKind.PIHOLE
            "adguard home" in text -> ScanKind.ADGUARD
            "home assistant" in text || "home-assistant" in text -> ScanKind.HOME_ASSISTANT
            else -> ScanKind.WEB
        }
        // Never follow a redirect. Suppress only the same host's HTTPS copy.
        if (!secure && p.status in 300..399 && runCatching { URI(p.location ?: "").let { it.scheme == "https" && it.host == ip } }.getOrDefault(false)) return null
        return ScanHit(ip, port, secure, kind, title(p.body), p.fingerprint, p.subject, issuer = p.issuer, notAfter = p.notAfter)
    }
    fun merge(hits: List<ScanHit>): List<ScanHit> {
        val named = hits.filter { it.kind != ScanKind.WEB }.groupBy { it.ip to it.kind }.values.map { same -> same.firstOrNull { it.secure } ?: same.first() }
        return (named + hits.filter { it.kind == ScanKind.WEB && named.none { n -> n.ip == it.ip } }
            .groupBy { it.ip }.values.map { same -> same.firstOrNull { it.secure } ?: same.first() })
            .sortedWith(compareBy({ !it.proxmox }, { it.kind.ordinal }, { ipv4(it.ip) }, { it.port }))
    }
    /**
     * [merge] without the sort: [found] added to [current] in the order they arrive, with the same rules. A named hit replaces the
     * host's plain web page, one host has one web row, the secure copy of the same service wins, and nothing already shown moves.
     */
    fun mergeArrival(current: List<ScanHit>, found: List<ScanHit>): List<ScanHit> {
        val result = current.toMutableList()
        for (hit in found) {
            if (hit.kind != ScanKind.WEB) {
                val same = result.indexOfFirst { it.ip == hit.ip && it.kind == hit.kind }
                if (same >= 0) { if (!result[same].secure && hit.secure) result[same] = hit }
                else { result.removeAll { it.ip == hit.ip && it.kind == ScanKind.WEB }; result += hit }
            } else {
                val there = result.indexOfFirst { it.ip == hit.ip }
                if (there < 0) result += hit
                else if (result[there].kind == ScanKind.WEB && !result[there].secure && hit.secure) result[there] = hit
            }
        }
        return result
    }
    fun saved(hit: ScanHit, origins: List<String>): Boolean = origins.any { origin -> runCatching {
        val u = URI(origin); u.scheme.equals(if (hit.secure) "https" else "http", true) && u.host.equals(hit.ip, true) &&
            (if (u.port >= 0) u.port else if (u.scheme == "https") 443 else 80) == hit.port
    }.getOrDefault(false) }
}

/**
 * Discovery-only sockets. Each [scan] builds one private SSL context that accepts any certificate so the leaf can be shown.
 * It is never installed as a default or shared with API clients, and nothing it sees becomes trust.
 * [probe] replaces the network probe in tests.
 *
 * What a scan does and in what order is [ScanEngine]: known hosts first, one cheap pass on the Proxmox ports (at most
 * [MAX_QUICK_IN_FLIGHT] connects at once), then the other ports of the hosts that answered or that the neighbour table lists.
 */
class NetworkScanner internal constructor(
    internal val connectTimeoutMs: Int,
    private val probe: (suspend (String, Int) -> List<ScanHit>)?,
    private val injected: ScanProber?,
    private val neighbours: NeighbourSource?,
    private val tuning: ScanTuning,
) {
    constructor(connectTimeoutMs: Int = LAN_CONNECT_TIMEOUT_MS, probe: (suspend (String, Int) -> List<ScanHit>)? = null) : this(connectTimeoutMs, probe, null, null, ScanTuning())

    /**
     * Sweeps [plan] (and the networks the typed [extraSubnets] make). [origins] mark what is already saved; [knownHosts] are probed first,
     * with the hosts of [origins]. [concurrency] is the most full probes (a TLS exchange and a request) at once.
     */
    fun scan(plan: NetworkPlan = interfaces(), origins: List<String> = emptyList(), concurrency: Int = 24, ports: List<Int> = LAN_SCAN_PORTS, extraSubnets: List<String> = emptyList(),
        knownHosts: List<String> = emptyList()): Flow<NetworkProgress> = channelFlow {
        val extraNetworks = extraSubnets.mapNotNull { ScanRules.planSubnet(it) }
        val effectivePlan = if (extraNetworks.isEmpty()) plan else NetworkPlan(plan.networks + extraNetworks)
        val trace = Trace.begin(TraceSource.Scan, "Network discovery", session=true)
        try {
        require(concurrency in 1..64)
        require(ports.isNotEmpty() && ports.all { it in 1..65535 })
        require(effectivePlan.addresses.size <= MAX_SCAN_ADDRESSES) { "A scan sweeps at most $MAX_SCAN_ADDRESSES addresses." }
        val prober = injected ?: probe?.let { LambdaProber(it) } ?: SocketProber(connectTimeoutMs, scanTlsContext(), Dispatchers.IO.limitedParallelism(tuning.quickInFlight))
        val table = neighbours ?: if (injected == null && probe == null) LocalNeighbours() else NeighbourSource.NONE
        ScanEngine(prober, table, tuning, connectTimeoutMs).run(effectivePlan, origins, knownHosts, concurrency, ports).collect { send(it) }
        } catch (t: Throwable) { trace.end(error=t); throw t } finally { trace.end() }
    }.flowOn(Dispatchers.IO)
    companion object {
        fun interfaces(): NetworkPlan = ScanRules.plan(attachedInterfaces())
        /** Every IPv4 address of every network card of this computer, with the system's interface number to match its facts. Up or down. */
        fun attachedInterfaces(): List<ScanInterface> = (NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()).flatMap { nic ->
            val index = runCatching { nic.index }.getOrNull()?.takeIf { it > 0 }
            nic.interfaceAddresses.filter { it.address is Inet4Address }.map {
                ScanInterface("${nic.name} ${nic.displayName}", it.address.hostAddress, it.networkPrefixLength.toInt(), runCatching { nic.isUp }.getOrDefault(false), index, nic.name)
            }
        }
    }
}

/** One context per scan: it records the leaf and trusts nothing beyond this discovery socket. */
internal fun scanTlsContext(): SSLContext = SSLContext.getInstance("TLS").apply {
    init(null, arrayOf<TrustManager>(object : X509TrustManager {
        override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
        override fun checkClientTrusted(c: Array<X509Certificate>, a: String) = Unit
        override fun checkServerTrusted(c: Array<X509Certificate>, a: String) = Unit
    }), null)
}

/**
 * What a typed server address means. A bare host or host:port becomes an HTTPS origin, and a bare host also gets the
 * product's usual port (8006 for Proxmox VE, 8443 for PDM). Text that already has a scheme is returned as typed:
 * `https://host` without a port means 443 there, exactly as the connection itself reads it ([serverOrigin]).
 */
fun completeServerAddress(text: String, kind: String = DIRECT_PVE_KIND): String {
    val typed = text.trim()
    if (typed.isEmpty() || "://" in typed) return typed
    var host = typed.trimEnd('/')
    // A bare IPv6 literal has several colons and no brackets.
    if (!host.startsWith("[") && host.count { it == ':' } > 1) host = "[$host]"
    val hasPort = if (host.startsWith("[")) "]:" in host else ':' in host
    return "https://$host" + if (hasPort) "" else ":" + if (kind == PDM_KIND) 8443 else 8006
}

/**
 * Read the leaf certificate's SHA-256 from the HTTPS origin [address] (upper-case, colon separated). A thin wrapper of
 * [probeTlsCertificate] for callers that need only the fingerprint.
 */
suspend fun probeTlsFingerprint(address: String, timeoutMs: Int = 5000): String? =
    probeTlsCertificate(address, timeoutMs)?.fingerprint?.chunked(2)?.joinToString(":") { it.uppercase() }

/**
 * Read the leaf certificate from the HTTPS origin [address], on the same host and port the connection would use
 * ([routeTarget]: no port means 443): its fingerprint, the SHA-256 of its public key, subject, issuer and validity. The
 * socket accepts any certificate only to read it; nothing seen here is trust until the user approves it. Null when
 * [address] is not an HTTPS origin or no certificate could be read.
 */
suspend fun probeTlsCertificate(address: String, timeoutMs: Int = 5000): ProbedCertificate? = withContext(Dispatchers.IO) {
    val target = routeTarget(address) ?: return@withContext null
    val raw = Socket()
    var socket: Socket = raw
    val closer = launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { runCatching { socket.close() }; runCatching { raw.close() } }
    }
    try {
        raw.connect(InetSocketAddress(target.host, target.port), timeoutMs)
        raw.soTimeout = timeoutMs
        socket = scanTlsContext().socketFactory.createSocket(raw, target.host, target.port, true)
        (socket as SSLSocket).startHandshake()
        val cert = socket.session.peerCertificates.firstOrNull() as? X509Certificate
        cert?.let { ProbedCertificate.of(it) }
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { ensureActive(); null }
    finally {
        closer.cancel()
        runCatching { socket.close() }
        runCatching { raw.close() }
    }
}

/** What the platform's own TLS validation says about a server, before anything is trusted by hand. */
enum class NormalTls { Accepted, Refused, Unknown }

/**
 * Ask whether the HTTPS origin [address] passes the platform's own TLS validation: [PlatformTrust] (the bundled Java roots
 * and this computer's store) and the HTTPS host name check, which is what a connection without a certificate pin applies
 * (`PveClient.open`, `PveTicket.request`, the storage wire and the consoles).
 * One socket and one handshake on the host and port the connection would use ([routeTarget]); no request is sent, nothing
 * is trusted and nothing is stored.
 *
 * [NormalTls.Accepted]: the handshake completed. [NormalTls.Refused]: it failed because of the server's certificate, see
 * [refusedOnCertificate]. [NormalTls.Unknown]: anything else, including an address that is not an HTTPS origin, an
 * unreachable server, a timeout, a reset connection and a TLS failure that is not about the certificate.
 */
suspend fun probeNormalTls(address: String, timeoutMs: Int = 4000): NormalTls = probeTlsValidation(address, timeoutMs, null)

/**
 * [probeNormalTls] with the trust to validate against. Null is [platformSslContext], looked up inside the guarded block: it
 * can fail on a broken trust store, and a probe must end as [NormalTls.Unknown] then. Tests pass a context of their own so
 * that no global JVM setting changes.
 */
internal suspend fun probeTlsValidation(address: String, timeoutMs: Int, context: SSLContext?): NormalTls = withContext(Dispatchers.IO) {
    val target = routeTarget(address) ?: return@withContext NormalTls.Unknown
    val raw = Socket()
    var socket: Socket = raw
    val closer = launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { runCatching { socket.close() }; runCatching { raw.close() } }
    }
    try {
        raw.connect(InetSocketAddress(target.host, target.port), timeoutMs)
        raw.soTimeout = timeoutMs
        val secure = (context ?: platformSslContext()).socketFactory.createSocket(raw, target.host, target.port, true) as SSLSocket
        socket = secure
        // The same check HttpsURLConnection asks of the socket when it has the default host name verifier.
        secure.sslParameters = secure.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        secure.startHandshake()
        NormalTls.Accepted
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { ensureActive(); if (refusedOnCertificate(e)) NormalTls.Refused else NormalTls.Unknown }
    finally {
        closer.cancel()
        runCatching { socket.close() }
        runCatching { raw.close() }
    }
}

/**
 * True when [error] is the platform refusing the server's certificate: an [SSLPeerUnverifiedException], or an
 * [SSLException] with a [CertificateException] among its causes. That is how the JDK reports a chain that is not trusted,
 * an expired or not yet valid certificate and a host name the certificate does not name. A TLS failure without such a cause
 * (no common protocol, a handshake the server ended, plain HTTP on the port) says nothing about the certificate.
 */
internal fun refusedOnCertificate(error: Throwable): Boolean {
    if (error is SSLPeerUnverifiedException) return true
    if (error !is SSLException) return false
    // The bound stops a chain of causes that loops back on itself.
    return generateSequence<Throwable>(error) { it.cause?.takeIf { cause -> cause !== it } }.take(16).any { it is CertificateException }
}

internal suspend fun probeScanPort(ip: String, port: Int, connectTimeoutMs: Int, tls: SSLContext): List<ScanHit> {
    var connected = false
    suspend fun peek(path: String, secure: Boolean): ScanPeek? = withContext(Dispatchers.IO) {
        val raw = java.net.Socket()
        var socket: java.net.Socket = raw
        // Close as soon as cancellation is requested, including during a blocking read/handshake.
        val closer = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { runCatching { socket.close() }; runCatching { raw.close() } } }
        try {
            ensureActive()
            raw.connect(InetSocketAddress(ip, port), connectTimeoutMs)
            connected = true
            raw.soTimeout = 1500
            var cert: X509Certificate? = null
            if (secure) {
                socket = tls.socketFactory.createSocket(raw, ip, port, true)
                socket.soTimeout = 1500
                (socket as SSLSocket).startHandshake()
                cert = socket.session.peerCertificates.first() as X509Certificate
            }
            socket.getOutputStream().apply { write("GET $path HTTP/1.0\r\nHost: $ip:$port\r\nUser-Agent: RingMASTER-scan\r\nAccept: text/html\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)); flush() }
            val data = java.io.ByteArrayOutputStream()
            val input = socket.getInputStream()
            val start = System.nanoTime()
            val buffer = ByteArray(2048)
            while (data.size() < 16384 && System.nanoTime() - start < 3_000_000_000L) {
                ensureActive()
                // A server that sent its answer and keeps the connection open times out here: what arrived is the answer.
                val n = try { input.read(buffer, 0, minOf(buffer.size, 16384-data.size())) } catch (_: java.net.SocketTimeoutException) { break }
                if (n < 0) break
                data.write(buffer, 0, n)
            }
            val text = data.toString("UTF-8")
            val head = text.substringBefore("\r\n\r\n").lines()
            val status = head.first().split(' ').getOrNull(1)?.toIntOrNull() ?: return@withContext null
            fun header(name: String) = head.firstOrNull { it.startsWith("$name:", true) }?.substringAfter(':')?.trim()
            ScanPeek(status, text.substringAfter("\r\n\r\n", ""), header("Server").orEmpty(), header("Location"),
                cert?.let { MessageDigest.getInstance("SHA-256").digest(it.encoded).joinToString(":") { b -> "%02X".format(b) } }, cert?.subjectX500Principal?.name,
                cert?.issuerX500Principal?.name, cert?.notAfter?.time)
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) { currentCoroutineContext().ensureActive(); null
        } finally { closer.cancel(); runCatching { socket.close() }; runCatching { raw.close() } }
    }
    var secure = port !in PLAIN_HTTP_PORTS
    var root = peek("/", secure)
    // Home Assistant is plain HTTP on 8123 unless its owner set up TLS there. Retry with TLS only when the port did answer.
    if (root == null && port == 8123 && connected) { secure = true; root = peek("/", true) }
    root ?: return emptyList()
    val result = ScanRules.classify(ip, port, secure, root)
    if (port == 80 || port == 443) {
        val admin = peek("/admin/", secure)
        val pi = admin?.let { ScanRules.classify(ip, port, secure, it) }?.takeIf { it.kind == ScanKind.PIHOLE }
        if (pi != null) return listOf(pi)
    }
    // AdGuard Home's first-run page sends / to /install.html. Ask for that page by name; redirects are never followed.
    if (port == 3000 && result?.kind != ScanKind.ADGUARD) {
        val setup = peek("/install.html", secure)?.let { ScanRules.classify(ip, port, secure, it) }?.takeIf { it.kind == ScanKind.ADGUARD }
        if (setup != null) return listOf(setup)
    }
    return listOfNotNull(result)
}
