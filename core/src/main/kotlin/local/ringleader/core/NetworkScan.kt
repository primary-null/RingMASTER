package local.ringleader.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.*
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.*

data class ScanInterface(val name: String, val ip: String, val prefix: Int, val up: Boolean = true)
data class ScanNetwork(val source: ScanInterface, val cidr: String?, val reason: String?, val addresses: List<String> = emptyList())
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
    val hits: List<ScanHit> = emptyList(), val finished: Boolean = false) { val total get() = plan.addresses.size }

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

/** Interface names of virtual switches and container or VM bridges. Their networks are not swept. */
private val VIRTUAL_ADAPTER = Regex(
    "vethernet|hyper-v|wsl|virtual|docker|vmware|vbox|virbr\\d*|lxcbr\\d*|lxdbr\\d*|\\bcni\\d+|\\bpodman\\d+|\\bbr-[0-9a-f]{12}\\b",
    RegexOption.IGNORE_CASE)

object ScanRules {
    fun ipv4(ip: String): Long? {
        val parts = ip.split('.')
        if (parts.size != 4) return null
        val numbers = parts.map { it.toIntOrNull()?.takeIf { n -> n in 0..255 } ?: return null }
        return numbers.fold(0L) { a, n -> (a shl 8) or n.toLong() }
    }
    private fun format(n: Long) = (3 downTo 0).joinToString(".") { ((n shr (it * 8)) and 255).toString() }
    fun plan(interfaces: List<ScanInterface>): NetworkPlan = NetworkPlan(interfaces.map { i ->
        val n = ipv4(i.ip)
        val private = n != null && (n ushr 24 == 10L || n ushr 20 == 0xac1L || n ushr 16 == 0xc0a8L)
        val reason = when {
            !i.up -> "Interface down"
            n == null -> "Invalid IPv4"
            n ushr 22 == 0x191L -> "Mesh / CGNAT space is not swept"
            n ushr 24 == 127L -> "Loopback"
            n ushr 16 == 0xa9feL -> "Link-local"
            VIRTUAL_ADAPTER.containsMatchIn(i.name) -> "Virtual-switch adapter"
            !private -> "Not RFC1918"
            i.prefix !in 1..30 -> "Point-to-point or invalid prefix"
            else -> null
        }
        if (reason != null) ScanNetwork(i, null, reason) else {
            val prefix = maxOf(24, i.prefix)
            val mask = (0xffffffffL shl (32-prefix)) and 0xffffffffL
            val network = n!! and mask
            val end = network or (mask xor 0xffffffffL)
            ScanNetwork(i, "${format(network)}/$prefix", null, (network+1 until end).map(::format))
        }
    })
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
    fun saved(hit: ScanHit, origins: List<String>): Boolean = origins.any { origin -> runCatching {
        val u = URI(origin); u.scheme.equals(if (hit.secure) "https" else "http", true) && u.host.equals(hit.ip, true) &&
            (if (u.port >= 0) u.port else if (u.scheme == "https") 443 else 80) == hit.port
    }.getOrDefault(false) }
}

/**
 * Discovery-only sockets. Each [scan] builds one private SSL context that accepts any certificate so the leaf can be shown.
 * It is never installed as a default or shared with API clients, and nothing it sees becomes trust.
 * [probe] replaces the network probe in tests.
 */
class NetworkScanner(internal val connectTimeoutMs: Int = LAN_CONNECT_TIMEOUT_MS, private val probe: (suspend (String, Int) -> List<ScanHit>)? = null) {
    fun scan(plan: NetworkPlan = interfaces(), origins: List<String> = emptyList(), concurrency: Int = 24, ports: List<Int> = LAN_SCAN_PORTS): Flow<NetworkProgress> = channelFlow {
        val trace = Trace.begin(TraceSource.Scan, "Network discovery", session=true)
        try {
        require(concurrency in 1..64)
        require(ports.isNotEmpty() && ports.all { it in 1..65535 })
        val probeOne: suspend (String, Int) -> List<ScanHit> = probe ?: run {
            val tls = scanTlsContext()
            val timeout = connectTimeoutMs
            ({ ip: String, port: Int -> probeScanPort(ip, port, timeout, tls) })
        }
        val addresses = plan.addresses
        val lock = Mutex()
        var next = 0
        var count = 0
        var hits = emptyList<ScanHit>()
        send(NetworkProgress(plan))
        coroutineScope {
            repeat(concurrency) { launch {
                while (true) {
                    ensureActive()
                    val ip = lock.withLock { addresses.getOrNull(next++) } ?: break
                    val found = mutableListOf<ScanHit>()
                    for (port in ports) {
                        ensureActive(); found += probeOne(ip, port)
                    }
                    lock.withLock {
                        val previous = hits.map { it.ip to it.port }.toSet()
                        hits = ScanRules.merge(hits + found.map { it.copy(saved = ScanRules.saved(it, origins)) })
                        hits.filter { (it.ip to it.port) !in previous }.forEach { Trace.emit(TraceSource.Scan, "service", "Discovered ${it.kind}", mapOf("url" to it.origin)) }
                        count++
                        send(NetworkProgress(plan, count, ip, hits))
                    }
                }
            } }
        }
        send(NetworkProgress(plan, count, hits = hits, finished = true))
        } catch (t: Throwable) { trace.end(error=t); throw t } finally { trace.end() }
    }.flowOn(Dispatchers.IO)
    companion object {
        fun interfaces(): NetworkPlan = ScanRules.plan(NetworkInterface.getNetworkInterfaces().toList().flatMap { nic ->
            nic.interfaceAddresses.filter { it.address is Inet4Address }.map {
                ScanInterface("${nic.name} ${nic.displayName}", it.address.hostAddress, it.networkPrefixLength.toInt(), nic.isUp)
            }
        })
    }
}

/** One context per scan: it records the leaf and trusts nothing beyond this discovery socket. */
private fun scanTlsContext(): SSLContext = SSLContext.getInstance("TLS").apply {
    init(null, arrayOf<TrustManager>(object : X509TrustManager {
        override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
        override fun checkClientTrusted(c: Array<X509Certificate>, a: String) = Unit
        override fun checkServerTrusted(c: Array<X509Certificate>, a: String) = Unit
    }), null)
}

private suspend fun probeScanPort(ip: String, port: Int, connectTimeoutMs: Int, tls: SSLContext): List<ScanHit> {
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
                val n = input.read(buffer, 0, minOf(buffer.size, 16384-data.size()))
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
