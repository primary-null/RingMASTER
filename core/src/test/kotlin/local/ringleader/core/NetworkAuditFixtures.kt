package local.ringleader.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509KeyManager

/**
 * Network audit 2026-11-16: shared fixtures. Certificates come from the JDK's keytool, as in TlsBoundaryTest. Nothing
 * here names a real server; every address is loopback.
 */
internal object AuditCerts {
    private const val PASS = "fixture-only"

    /** One self-signed RSA leaf. [extra] is passed to keytool (for example a start date in the past). */
    fun generate(alias: String = "fixture", vararg extra: String): KeyStore {
        val dir = Files.createTempDirectory("ring-audit-tls")
        val file = dir.resolve("$alias.p12")
        val executable = if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool"
        val command = listOf(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
            "-genkeypair", "-alias", alias, "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
            "-dname", "CN=$alias.example.test", "-storetype", "PKCS12", "-keystore", file.toString(),
            "-storepass", PASS, "-keypass", PASS, "-noprompt") + extra
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        check(process.waitFor() == 0) { "keytool failed: $output" }
        return try {
            KeyStore.getInstance("PKCS12").apply { Files.newInputStream(file).use { load(it, PASS.toCharArray()) } }
        } finally { Files.deleteIfExists(file); Files.deleteIfExists(dir) }
    }

    fun cert(store: KeyStore, alias: String = store.aliases().nextElement()) = store.getCertificate(alias) as X509Certificate
    fun key(store: KeyStore, alias: String = store.aliases().nextElement()) = store.getKey(alias, PASS.toCharArray()) as PrivateKey
    fun pin(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }

    fun serverContext(store: KeyStore): SSLContext {
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, PASS.toCharArray()) }
        return SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
    }

    /** A server key manager that presents [chain] (in that order) signed by [key]; [current] switches it at run time. */
    class Switching(initial: Pair<PrivateKey, Array<X509Certificate>>) : X509ExtendedKeyManager() {
        val current = AtomicReference(initial)
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = null
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = null
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf("current")
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = if (keyType == "RSA" || keyType == "RSASSA-PSS") "current" else null
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = chooseServerAlias(keyType, issuers, null)
        override fun getCertificateChain(alias: String?) = current.get().second
        override fun getPrivateKey(alias: String?) = current.get().first
    }

    fun switchingContext(manager: X509KeyManager): SSLContext =
        SSLContext.getInstance("TLS").apply { init(arrayOf(manager), null, null) }
}

/**
 * A minimal HTTPS/1.1 server on a raw SSLServerSocket, so a test can drop a connection after the request arrived and
 * before any answer: the failure a NAT timeout, a Wi-Fi change or a restarting pveproxy produces. [dropFirst] connections
 * are read completely and then closed without a status line. Every request line that arrived is recorded.
 */
internal class RawTlsServer(context: SSLContext, private val answer: (String) -> Pair<Int, String>) : AutoCloseable {
    private val socket = context.serverSocketFactory.createServerSocket(0, 50, InetAddress.getLoopbackAddress()) as SSLServerSocket
    val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val dropFirst = AtomicInteger(0)
    val address get() = "https://127.0.0.1:${socket.localPort}"
    private val thread = Thread {
        while (!socket.isClosed) {
            val client = try { socket.accept() } catch (_: Exception) { break }
            Thread { serve(client) }.apply { isDaemon = true }.start()
        }
    }.apply { isDaemon = true; start() }

    private fun serve(client: Socket) {
        try {
            client.soTimeout = 10_000
            val input = client.getInputStream()
            val head = readHead(input) ?: return
            val line = head.lineSequence().first()
            val length = head.lineSequence().firstOrNull { it.startsWith("Content-Length:", true) }
                ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
            input.readNBytes(length)
            requests += line.substringBeforeLast(" HTTP/")
            if (dropFirst.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) return
            val (status, body) = answer(line)
            val bytes = body.toByteArray()
            client.getOutputStream().apply {
                write("HTTP/1.1 $status X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(bytes); flush()
            }
        } catch (_: Exception) {
        } finally { runCatching { client.close() } }
    }

    private fun readHead(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return null
            out.write(b)
            val s = out.size()
            if (s >= 4) {
                val a = out.toByteArray()
                if (a[s - 4] == '\r'.code.toByte() && a[s - 3] == '\n'.code.toByte() && a[s - 2] == '\r'.code.toByte() && a[s - 1] == '\n'.code.toByte())
                    return a.toString(Charsets.ISO_8859_1)
            }
            if (s > 65536) return null
        }
    }

    override fun close() { socket.close(); thread.join(2000) }
}

internal fun loopback(port: Int) = InetSocketAddress(InetAddress.getLoopbackAddress(), port)
