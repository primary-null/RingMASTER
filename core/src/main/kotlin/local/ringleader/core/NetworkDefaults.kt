package local.ringleader.core

import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * What every process of this app sets up before it connects to anything. One call, first thing in every entry point
 * (`desktop/Main.kt`), safe to repeat.
 *
 *  - One proxy policy ([LanProxySelector]): a Proxmox server, a guest, a VPN peer or any other host on a private, loopback,
 *    link-local, unique-local, CGNAT or tailnet address, or with a local-looking name, is always reached directly, whatever proxy
 *    the environment names. Everything else (in practice the update feed) uses the operating system's proxy where the JDK can
 *    read it. Before this, the proxy a client used depended on which client it was: the API client, the consoles and the update
 *    check asked the JVM's selector, the storage upload socket, the certificate probe, the scan and the port checks never did.
 *  - The name-lookup hook (see NameResolver.kt): its service entry is read by the JDK at the first name lookup; the install makes
 *    the JDK load it now, on the main thread, so a problem shows at start-up and not at some later first connection.
 */
object NetworkDefaults {
    private val lock = Any()
    private var installed = false

    /** True once [install] has run in this JVM. */
    val isInstalled: Boolean get() = synchronized(lock) { installed }

    fun install() {
        synchronized(lock) {
            if (installed) return
            installed = true
            // The JDK reads the operating system's proxy settings only if java.net.useSystemProxies is true before its default
            // selector exists. A value somebody set on purpose (-Djava.net.useSystemProxies=false in a .cfg) stays.
            runCatching { if (System.getProperty("java.net.useSystemProxies") == null) System.setProperty("java.net.useSystemProxies", "true") }
            // >>> RETRY-POST: another branch adds System.setProperty("sun.net.http.retryPost", "false") on the next line, before the
            // >>> first HttpURLConnection exists. Keep this spot; do not add the line in this change.
            runCatching { val current = ProxySelector.getDefault(); if (current !is LanProxySelector) ProxySelector.setDefault(LanProxySelector(current)) }
            NameLookup.ensureLoaded()
        }
    }

    private val kept: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private const val MAX_KEPT = 256

    /**
     * Declares [host] (a name or an address) to be a server of this app, so it is reached directly even when its name does not look
     * local (`pve.mydomain.example` pointing into a private network). Whoever creates a connection calls this; the registry is small,
     * in memory, and ignores a 257th host.
     */
    fun keepDirect(host: String) {
        val key = hostKey(host) ?: return
        if (kept.size < MAX_KEPT) kept += key
    }

    fun isKeptDirect(host: String): Boolean = hostKey(host)?.let { it in kept } == true
    internal fun forget(host: String) { hostKey(host)?.let { kept -= it } }

    private fun hostKey(host: String): String? = IpLiteral.unbracket(host.trim()).substringBefore('%').trimEnd('.').lowercase().ifEmpty { null }

    /** Names that only a local resolver can answer. A proxy on the internet cannot reach them, so they are never sent to one. */
    private val LOCAL_SUFFIXES = listOf("local", "localhost", "ts.net", "home.arpa", "internal", "lan", "localdomain")

    /**
     * True for a host that must never be sent to a proxy: an IP literal in a private (10/8, 172.16/12, 192.168/16), loopback,
     * link-local, unique-local (fc00::/7), CGNAT (100.64/10, which Tailscale uses) or "this network" range, a single-label name
     * (`pve`, `nas`), and a name ending in `.local`, `.localhost`, `.ts.net`, `.home.arpa`, `.internal`, `.lan` or `.localdomain`.
     * Text that is neither a name nor an address is treated as local too: when in doubt, no proxy.
     */
    fun isLanHost(host: String): Boolean {
        val h = IpLiteral.unbracket(host.trim()).trimEnd('.').lowercase()
        if (h.isEmpty()) return true
        val bare = h.substringBefore('%')
        IpLiteral.parseV4(bare)?.let { return lanV4(it) }
        IpLiteral.parseV6(bare)?.let { return lanV6(it) }
        if (':' in h || IpLiteral.looksLikeBrokenV4(h)) return true
        if ('.' !in h) return true
        return LOCAL_SUFFIXES.any { h == it || h.endsWith(".$it") }
    }

    private fun lanV4(b: ByteArray): Boolean {
        val a = b[0].toInt() and 255
        val c = b[1].toInt() and 255
        return a == 0 || a == 10 || a == 127 || (a == 169 && c == 254) || (a == 172 && c in 16..31) || (a == 192 && c == 168) || (a == 100 && c in 64..127)
    }

    private fun lanV6(b: ByteArray): Boolean {
        val first = b[0].toInt() and 255
        val second = b[1].toInt() and 255
        if (b.all { it.toInt() == 0 }) return true
        if (b.take(15).all { it.toInt() == 0 } && b[15].toInt() == 1) return true
        if ((first and 0xfe) == 0xfc) return true
        if (first == 0xfe && (second and 0xc0) in setOf(0x80, 0xc0)) return true
        if (b.take(10).all { it.toInt() == 0 } && (b[10].toInt() and 255) == 0xff && (b[11].toInt() and 255) == 0xff) return lanV4(b.copyOfRange(12, 16))
        return false
    }
}

/**
 * The JVM's proxy selector for this app: [NetworkDefaults.isLanHost] hosts (and hosts registered with [NetworkDefaults.keepDirect]) get
 * `Proxy.NO_PROXY`, every other host is asked of [delegate], the JDK's own selector (which reads the system settings and the
 * https.proxyHost family of properties).
 *
 * It must never stop a connection that has nothing to do with a proxy: it does not throw, a delegate that fails is DIRECT, and a
 * delegate that does not answer within [delegateTimeoutMillis] (a PAC script, WPAD detection on a network without one) is DIRECT
 * and is not asked again for ten minutes. The LAN rule comes first, so a broken proxy setting cannot reach a LAN connection at all.
 */
class LanProxySelector(
    private val delegate: ProxySelector?,
    private val delegateTimeoutMillis: Long = 3_000,
    private val kept: (String) -> Boolean = NetworkDefaults::isKeptDirect,
    private val clock: () -> Long = System::nanoTime,
) : ProxySelector() {
    private val direct: List<Proxy> = listOf(Proxy.NO_PROXY)
    @Volatile private var skipSince = 0L
    @Volatile private var skipping = false

    override fun select(uri: URI?): List<Proxy> = try {
        val host = hostOf(uri)
        when {
            uri == null || host == null -> direct
            NetworkDefaults.isLanHost(host) || kept(host) -> direct
            else -> ask(uri)
        }
    } catch (_: Throwable) {
        direct
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        runCatching { delegate?.connectFailed(uri, sa, ioe) }
    }

    private fun ask(uri: URI): List<Proxy> {
        val d = delegate ?: return direct
        if (skipping && clock() - skipSince < SKIP_NANOS) return direct
        val task = FutureTask<List<Proxy>> { d.select(uri) }
        try { threads.execute(task) } catch (_: RejectedExecutionException) { task.run() }
        return try {
            val answer = task.get(delegateTimeoutMillis, TimeUnit.MILLISECONDS)
            skipping = false
            if (answer.isNullOrEmpty()) direct else answer
        } catch (_: TimeoutException) {
            task.cancel(true)
            skipSince = clock(); skipping = true
            direct
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            direct
        } catch (_: ExecutionException) {
            direct
        }
    }

    /** The host of [uri], also when java.net.URI cannot say (an unbracketed IPv6 literal in a `socket://` URI has no parsed host). */
    private fun hostOf(uri: URI?): String? {
        if (uri == null) return null
        uri.host?.let { return it }
        val authority = uri.rawAuthority?.substringAfterLast('@') ?: return null
        val host = if (authority.startsWith("[")) authority.substringAfter('[').substringBefore(']') else authority.substringBeforeLast(':', authority)
        return host.ifEmpty { null }
    }

    private companion object {
        val SKIP_NANOS = TimeUnit.MINUTES.toNanos(10)
        val number = AtomicInteger()
        val threads = ThreadPoolExecutor(0, 4, 20, TimeUnit.SECONDS, SynchronousQueue<Runnable>()) { task ->
            Thread(null, task, "ringmaster-proxy-${number.incrementAndGet()}").apply { isDaemon = true }
        }
    }
}
