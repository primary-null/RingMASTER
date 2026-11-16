package local.ringleader.core

import java.io.ByteArrayInputStream
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertPathBuilderException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager

/**
 * Which certificate store accepted a connection made with normal TLS (no certificate pin): the roots that ship inside
 * RingMASTER's Java runtime, or the certificates this computer's operating system trusts.
 */
enum class TrustSource(val label: String) {
    JavaRoots("public certificate"),
    ThisComputer("trusted by this computer"),
}

/**
 * The certificates the operating system trusts, as read for normal TLS. [source] names where they came from ("Windows
 * certificate store" or a bundle path). [problem] says, in one sentence, why there are none or fewer than expected; it is
 * null when the store was read. Never contains anything secret.
 */
data class OsTrustStore(val certificates: List<X509Certificate>, val source: String, val problem: String? = null)

/** The Linux bundle files, in the order the families put them (Debian, Ubuntu and Arch; Fedora; others). */
val LINUX_TRUST_BUNDLES: List<Path> = listOf(
    "/etc/ssl/certs/ca-certificates.crt",
    "/etc/pki/tls/certs/ca-bundle.crt",
    "/etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem",
    "/etc/ssl/cert.pem",
    "/etc/ca-certificates/extracted/tls-ca-bundle.pem",
).map { Path.of(it) }

/** A bundle larger than this is not read: the largest distribution bundles are well under one MiB. */
internal const val MAX_TRUST_BUNDLE_BYTES = 8 * 1024 * 1024
internal const val MAX_TRUST_CERTIFICATES = 4096

/**
 * Read this computer's trusted roots. Windows: the `Windows-ROOT` store (the current user's view, which includes the
 * machine's roots and those set by group policy), which needs the `jdk.crypto.mscapi` module in the runtime. Linux: the
 * first bundle of [bundles] that exists. Any failure gives an empty list with the reason; it never throws.
 */
fun readOsTrustStore(
    osName: String = System.getProperty("os.name").orEmpty(),
    bundles: List<Path> = LINUX_TRUST_BUNDLES,
    windowsStore: () -> KeyStore = { KeyStore.getInstance("Windows-ROOT").apply { load(null, null) } },
): OsTrustStore = when {
    osName.startsWith("Windows", ignoreCase = true) -> readWindowsStore(windowsStore)
    osName.startsWith("Linux", ignoreCase = true) || osName.contains("BSD", ignoreCase = true) -> readFirstBundle(bundles)
    else -> OsTrustStore(emptyList(), "", "This computer's certificate store is not read on this system; the bundled roots are used.")
}

private fun readWindowsStore(open: () -> KeyStore): OsTrustStore {
    val name = "Windows certificate store"
    val store = try { open() } catch (_: java.security.KeyStoreException) {
        return OsTrustStore(emptyList(), name, "Windows certificate support is missing from this build of RingMASTER; only the bundled roots are used.")
    } catch (_: Exception) {
        return OsTrustStore(emptyList(), name, "The Windows certificate store could not be read; only the bundled roots are used.")
    }
    val certificates = try {
        store.aliases().toList().asSequence().mapNotNull { alias -> runCatching { store.getCertificate(alias) as? X509Certificate }.getOrNull() }
            .take(MAX_TRUST_CERTIFICATES).toList()
    } catch (_: Exception) {
        return OsTrustStore(emptyList(), name, "The Windows certificate store could not be read; only the bundled roots are used.")
    }
    return OsTrustStore(certificates, name, if (certificates.isEmpty()) "The Windows certificate store holds no root certificates." else null)
}

private fun readFirstBundle(bundles: List<Path>): OsTrustStore {
    val file = bundles.firstOrNull { runCatching { Files.isRegularFile(it) && Files.isReadable(it) }.getOrDefault(false) }
        ?: return OsTrustStore(emptyList(), "", "No system certificate bundle was found; only the bundled roots are used.")
    return readCertificateBundle(file)
}

/**
 * Read a PEM bundle: every `CERTIFICATE` block that parses, in file order, at most [maxCertificates]. A block that does not
 * parse is skipped and the rest is still used. A file over [maxBytes] is not read at all.
 */
fun readCertificateBundle(file: Path, maxBytes: Int = MAX_TRUST_BUNDLE_BYTES, maxCertificates: Int = MAX_TRUST_CERTIFICATES): OsTrustStore {
    val source = file.toString()
    val bytes = try {
        Files.newInputStream(file).use { it.readNBytes(maxBytes + 1) }
    } catch (_: Exception) {
        return OsTrustStore(emptyList(), source, "The system certificate bundle could not be read; only the bundled roots are used.")
    }
    if (bytes.size > maxBytes) return OsTrustStore(emptyList(), source, "The system certificate bundle is too large to read; only the bundled roots are used.")
    val text = bytes.toString(Charsets.US_ASCII)
    val factory = CertificateFactory.getInstance("X.509")
    val certificates = mutableListOf<X509Certificate>()
    var skipped = 0
    var at = 0
    while (certificates.size < maxCertificates) {
        val begin = text.indexOf(PEM_BEGIN, at).takeIf { it >= 0 } ?: break
        val end = text.indexOf(PEM_END, begin + PEM_BEGIN.length).takeIf { it >= 0 } ?: break
        at = end + PEM_END.length
        val body = text.substring(begin + PEM_BEGIN.length, end)
        val parsed = runCatching {
            factory.generateCertificate(ByteArrayInputStream(Base64.getMimeDecoder().decode(body))) as X509Certificate
        }.getOrNull()
        if (parsed != null) certificates += parsed else skipped++
    }
    val problem = when {
        certificates.isEmpty() -> "The system certificate bundle holds no readable certificates; only the bundled roots are used."
        skipped > 0 -> "$skipped entries of the system certificate bundle could not be read and were skipped."
        else -> null
    }
    return OsTrustStore(certificates, source, problem)
}

private const val PEM_BEGIN = "-----BEGIN CERTIFICATE-----"
private const val PEM_END = "-----END CERTIFICATE-----"

/**
 * Trust for a connection WITHOUT a certificate pin: the Java roots bundled with RingMASTER, and then this computer's own
 * store ([osStore]). The HTTPS host name check stays on: each store is asked through the JDK's own PKIX trust manager with
 * the socket or engine, which checks the name whenever the caller set the "HTTPS" endpoint identification (HttpsURLConnection
 * with its default verifier, java.net.http.HttpClient, [storageWire] and the normal-TLS probe all do).
 *
 * The operating system's store is read at most once per [refreshMillis], so a certificate added with
 * `update-ca-certificates` or the Windows certificate manager is picked up within that time without a restart.
 *
 * Never used for a pinned connection (a pin trusts one exact certificate and no store), and never for the update check or
 * anything else that talks to the internet: those keep Java's own roots.
 */
class PlatformTrust(
    private val osStore: () -> OsTrustStore = { readOsTrustStore() },
    /** Null: the JDK's own `cacerts`. Tests pass a store of their own; nothing global changes. */
    private val javaRoots: KeyStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val refreshMillis: Long = 5 * 60_000L,
) {
    private class Built(val context: SSLContext, val store: OsTrustStore, val at: Long)
    @Volatile private var built: Built? = null
    /** host:port → the store that accepted the last normal-TLS handshake there. Bounded; a clear only loses labels. */
    private val sources = ConcurrentHashMap<String, TrustSource>()

    /** The SSL context for a connection without a pin. Cached; rebuilt after [refreshMillis]. */
    fun sslContext(): SSLContext = current().context

    /** What was read from this computer's store, for a status line. */
    fun osStore(): OsTrustStore = current().store

    /**
     * Which store accepted the last normal-TLS handshake with the server at [address], recorded by the trust manager during
     * that handshake (no second handshake). Null when none has been seen in this run.
     */
    fun sourceFor(address: String): TrustSource? = routeTarget(address)?.let { sources[sourceKey(it.host, it.port)] }

    private fun current(): Built {
        val now = clock()
        built?.takeIf { now - it.at in 0 until refreshMillis }?.let { return it }
        synchronized(this) {
            built?.takeIf { now - it.at in 0 until refreshMillis }?.let { return it }
            val store = runCatching(osStore).getOrElse { OsTrustStore(emptyList(), "", "This computer's certificate store could not be read; only the bundled roots are used.") }
            val java = pkix(javaRoots)
            val os = store.certificates.takeIf { it.isNotEmpty() }?.let { certificates ->
                val anchors = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                    load(null, null)
                    certificates.forEachIndexed { index, certificate -> setCertificateEntry("os-$index", certificate) }
                }
                pkix(anchors)
            }
            val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(PlatformTrustManager(java, os, ::record)), null) }
            return Built(context, store, now).also { built = it }
        }
    }

    private fun record(session: SSLSession?, source: TrustSource) {
        val host = session?.peerHost ?: return
        if (sources.size >= 256) sources.clear()
        sources[sourceKey(host, session.peerPort)] = source
        runCatching { session.putValue(TRUST_SOURCE_KEY, source.name) }
    }

    companion object {
        /** The one instance the transports share. */
        val shared = PlatformTrust()
        /**
         * Tests that drive a whole transport (a console, the storage wire) with a certificate "this computer trusts" put a
         * trust of their own here, and take it away afterwards. Always null in the app: nothing outside tests sets it.
         */
        @Volatile internal var override: PlatformTrust? = null
        /** The [SSLSession] value naming the [TrustSource] that accepted it. */
        const val TRUST_SOURCE_KEY = "local.ringleader.trustSource"
    }
}

/** The SSL context for a connection without a certificate pin. See [PlatformTrust]. */
fun platformSslContext(): SSLContext = (PlatformTrust.override ?: PlatformTrust.shared).sslContext()

private fun sourceKey(host: String, port: Int) = host.removePrefix("[").removeSuffix("]").lowercase() + ":" + port

private fun pkix(anchors: KeyStore?): X509ExtendedTrustManager =
    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(anchors) }
        .trustManagers.filterIsInstance<X509ExtendedTrustManager>().first()

/**
 * Java roots first, then this computer's store. A chain one of them accepts is accepted, with the host name checked by
 * that one. When both refuse, the more telling refusal is thrown: this computer's, if it got past the chain (so the chain is
 * trusted here and the problem is the name or the dates), otherwise the Java roots'.
 */
private class PlatformTrustManager(
    private val java: X509ExtendedTrustManager,
    private val os: X509ExtendedTrustManager?,
    private val record: (SSLSession?, TrustSource) -> Unit,
) : X509ExtendedTrustManager() {
    override fun getAcceptedIssuers(): Array<X509Certificate> = java.acceptedIssuers + (os?.acceptedIssuers ?: emptyArray())

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        check(null) { it.checkServerTrusted(chain, authType) }
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
        check((socket as? SSLSocket)?.handshakeSession) { it.checkServerTrusted(chain, authType, socket) }
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
        check(engine?.handshakeSession) { it.checkServerTrusted(chain, authType, engine) }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?): Unit = throw CertificateException("Client trust is not supported")
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?): Unit = throw CertificateException("Client trust is not supported")
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?): Unit = throw CertificateException("Client trust is not supported")

    private fun check(session: SSLSession?, call: (X509ExtendedTrustManager) -> Unit) {
        val first = try {
            call(java); record(session, TrustSource.JavaRoots); return
        } catch (e: CertificateException) { e }
        val second = os ?: throw first
        try {
            call(second); record(session, TrustSource.ThisComputer)
        } catch (other: CertificateException) {
            throw if (chainProblem(first) && !chainProblem(other)) other else first
        }
    }
}

/** True when [error] says the chain leads to no trusted root (as opposed to a name or date problem with a trusted chain). */
internal fun chainProblem(error: Throwable): Boolean =
    generateSequence(error) { it.cause?.takeIf { cause -> cause !== it } }.take(16)
        .any { it is CertPathBuilderException || (it is CertPathValidatorException && it.reason != CertPathValidatorException.BasicReason.EXPIRED && it.reason != CertPathValidatorException.BasicReason.NOT_YET_VALID) }
