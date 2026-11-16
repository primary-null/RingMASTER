package local.ringleader.core.audit

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.spi.InetAddressResolver
import java.net.spi.InetAddressResolverProvider
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Stream
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import local.ringleader.core.HttpsUpdateTransport
import local.ringleader.core.NameLookup
import local.ringleader.core.PveClient
import local.ringleader.core.UploadCancel
import local.ringleader.core.javaProxyFor
import local.ringleader.core.pinnedSslContext
import local.ringleader.core.probeDiagnosticReachability
import local.ringleader.core.probeTlsFingerprint
import local.ringleader.core.serverOrigin
import local.ringleader.core.storageWire
import local.ringleader.core.tools.connectPort

/**
 * Shared pieces of the IPv4/IPv6 addressing audit tests. Test code only.
 * Everything here talks to the loopback interface of this computer; no other host is contacted.
 */
internal object AuditTls {
    class Material(val context: SSLContext, val pin: String)

    /** One self-signed certificate for the whole test run (keytool, like TlsBoundaryTest). Pin is lower-case hex. */
    val material: Material by lazy {
        val dir = Files.createTempDirectory("ringmaster-audit-tls")
        try {
            val key = dir.resolve("fixture.p12")
            val executable = if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool"
            val process = ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
                "-dname", "CN=fixture.invalid", "-storetype", "PKCS12", "-keystore", key.toString(),
                "-storepass", "fixture-only", "-keypass", "fixture-only", "-noprompt",
            ).redirectErrorStream(true).start()
            process.inputStream.readAllBytes()
            check(process.waitFor() == 0) { "keytool failed" }
            val store = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(key).use { load(it, "fixture-only".toCharArray()) } }
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "fixture-only".toCharArray()) }
            val context = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
            val pin = MessageDigest.getInstance("SHA-256").digest(store.getCertificate("fixture").encoded).joinToString("") { "%02x".format(it) }
            Material(context, pin)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    class Served(val port: Int, val pin: String) {
        /** `Host` header of every request, in arrival order. */
        val hosts = CopyOnWriteArrayList<String>()
        /** `METHOD /path?query` of every request. */
        val requests = CopyOnWriteArrayList<String>()
    }

    /**
     * A TLS server on [bind] (an IP literal such as `127.0.0.1` or `::1`) that records each request's Host header.
     * `/api2/json/access/ticket` answers with a ticket; every other path answers the one-node resource list.
     * A path ending in `/vncwebsocket` answers 404, which ends a WebSocket handshake after the request was seen.
     */
    fun <T> serve(bind: String, block: (Served) -> T): T {
        val server = HttpsServer.create(InetSocketAddress(InetAddress.getByName(bind), 0), 0)
        server.httpsConfigurator = HttpsConfigurator(material.context)
        val served = Served(server.address.port, material.pin)
        server.createContext("/") { exchange ->
            served.hosts += exchange.requestHeaders.getFirst("Host").orEmpty()
            served.requests += "${exchange.requestMethod} ${exchange.requestURI}"
            exchange.requestBody.readAllBytes()
            val path = exchange.requestURI.path
            val (status, text) = when {
                path.endsWith("/vncwebsocket") -> 404 to "{}"
                path == "/api2/json/access/ticket" -> 200 to """{"data":{"ticket":"PVE:root@pam:00000000::fixture","CSRFPreventionToken":"fixture"}}"""
                else -> 200 to """{"data":[{"type":"node","node":"fixture"}]}"""
            }
            val bytes = text.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            return block(served)
        } finally {
            server.stop(0)
        }
    }

    fun literal(host: String): String = if (':' in host) "[$host]" else host
}

/** A plain TCP server that answers each connection with fixed bytes (or silence) so failure kinds can be provoked. */
internal class ScriptedServer(bind: String, private val reply: ByteArray?, private val hangMillis: Long = 0) : AutoCloseable {
    private val server = ServerSocket(0, 20, InetAddress.getByName(bind))
    val port: Int get() = server.localPort
    val firstLines = CopyOnWriteArrayList<String>()
    private val worker = thread(isDaemon = true, name = "audit-scripted-server") {
        while (!server.isClosed) {
            val client = try { server.accept() } catch (_: Exception) { break }
            thread(isDaemon = true) {
                client.use { socket ->
                    try {
                        socket.soTimeout = 300
                        val seen = try { socket.getInputStream().readNBytes(1) } catch (_: Exception) { ByteArray(0) }
                        if (seen.isNotEmpty()) firstLines += "bytes-received"
                        if (reply != null) { socket.getOutputStream().write(reply); socket.getOutputStream().flush() }
                        if (hangMillis > 0) Thread.sleep(hangMillis)
                    } catch (_: Exception) { }
                }
            }
        }
    }
    override fun close() { runCatching { server.close() } }
}

/** A plain HTTP server (no TLS) that records every request header line and answers a small page. */
internal class PlainHttpServer(bind: String) : AutoCloseable {
    private val server = ServerSocket(0, 20, InetAddress.getByName(bind))
    val port: Int get() = server.localPort
    val headerLines = CopyOnWriteArrayList<String>()
    init {
        thread(isDaemon = true, name = "audit-plain-http") {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) {
                    client.use { socket ->
                        try {
                            socket.soTimeout = 2000
                            val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                            while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; headerLines += line }
                            val body = "<title>Audit fixture</title>"
                            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
                            socket.getOutputStream().flush()
                        } catch (_: Exception) { }
                    }
                }
            }
        }
    }
    override fun close() { runCatching { server.close() } }
}

/** A tiny HTTP proxy that logs the first line of each request and answers 502, so a test sees who asked it. */
internal class FakeProxy : AutoCloseable {
    private val server = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    val firstLines = CopyOnWriteArrayList<String>()
    init {
        thread(isDaemon = true, name = "audit-fake-proxy") {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) {
                    client.use { socket ->
                        try {
                            socket.soTimeout = 2000
                            val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                            val first = reader.readLine().orEmpty()
                            firstLines += first
                            socket.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            socket.getOutputStream().flush()
                        } catch (_: Exception) { }
                    }
                }
            }
        }
    }
    override fun close() { runCatching { server.close() } }
}

/** Runs [AuditChild] in a fresh JVM so a test can set JVM flags or install a resolver without touching this JVM. */
internal object ChildJvm {
    class Result(val lines: List<String>, val exit: Int) {
        /** `AUDIT|key=value` lines as a map. A key that repeats keeps its last value. */
        val values: Map<String, String> = lines.filter { it.startsWith("AUDIT|") }.associate {
            val body = it.removePrefix("AUDIT|")
            body.substringBefore('=') to body.substringAfter('=', "")
        }
        override fun toString() = lines.joinToString("\n")
    }

    fun run(scenario: String, args: List<String> = emptyList(), jvmArgs: List<String> = emptyList(), withResolver: Boolean = false, timeoutSeconds: Long = 120): Result {
        val dir = Files.createTempDirectory("ringmaster-audit-child")
        try {
            val classPath = buildList {
                if (withResolver) {
                    val services = dir.resolve("META-INF").resolve("services")
                    Files.createDirectories(services)
                    Files.writeString(services.resolve("java.net.spi.InetAddressResolverProvider"), AuditResolverProvider::class.java.name + "\n")
                    add(dir.toString())
                }
                add(System.getProperty("java.class.path"))
            }.joinToString(File.pathSeparator)
            val executable = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
            val command = listOf(Path.of(System.getProperty("java.home"), "bin", executable).toString()) + jvmArgs +
                listOf("-cp", classPath, AuditChild::class.java.name, scenario) + args
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = CopyOnWriteArrayList<String>()
            val pump = thread(isDaemon = true) { process.inputStream.bufferedReader().forEachLine { output += it } }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { process.destroyForcibly(); pump.join(2000); error("child timed out\n$output") }
            pump.join(5000)
            return Result(output.toList(), process.exitValue())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}

/**
 * A name resolver installed only in the child JVM (java.net.spi, JDK 18+). Names ending in `.audit.test` are scripted;
 * every other name goes to the built-in resolver.
 */
class AuditResolverProvider : InetAddressResolverProvider() {
    override fun get(configuration: Configuration): InetAddressResolver {
        val builtin = configuration.builtinResolver()
        // The app's own hook (deadline, address order) runs on top of the scripted answers, as it runs on top of the JDK's in the app.
        return NameLookup.wrap(object : InetAddressResolver {
            override fun lookupByName(host: String, policy: InetAddressResolver.LookupPolicy): Stream<InetAddress> {
                fun v4(vararg b: Int) = InetAddress.getByAddress(host, ByteArray(4) { b[it].toByte() })
                return when {
                    host.startsWith("slow") && host.endsWith(".audit.test") -> { Thread.sleep(SLOW_MILLIS); Stream.of(v4(127, 0, 0, 1)) }
                    host == "two.audit.test" -> Stream.of(v4(127, 0, 0, 2), v4(127, 0, 0, 1))
                    host == "ttl.audit.test" -> { val n = calls.incrementAndGet(); Stream.of(v4(127, 0, 0, 10 + n)) }
                    else -> builtin.lookupByName(host, policy)
                }
            }
            override fun lookupByAddress(addr: ByteArray): String = builtin.lookupByAddress(addr)
        })
    }
    override fun name(): String = "ringmaster-audit-resolver"
    companion object {
        const val SLOW_MILLIS = 2500L
        val calls = AtomicInteger()
    }
}

/** Entry point of the child JVM. Prints `AUDIT|key=value` lines and exits. */
object AuditChild {
    private fun say(key: String, value: Any?) = println("AUDIT|$key=$value")
    private fun <T> timed(key: String, block: () -> T) {
        val started = System.nanoTime()
        val outcome = try { "ok(${block()})" } catch (e: Throwable) { "${e.javaClass.simpleName}(${e.message?.take(80)})" }
        say(key, "${(System.nanoTime() - started) / 1_000_000}ms $outcome")
    }

    @JvmStatic fun main(args: Array<String>) {
        when (args[0]) {
            "slow-dns" -> {
                // Three different slow names, so the JVM's own address cache cannot hide the second and third lookups.
                timed("probeTlsFingerprint, 300 ms timeout") { runBlocking { probeTlsFingerprint("https://slow1.audit.test:8006", 300) } }
                timed("connectPort, 300 ms timeout") { connectPort("slow2.audit.test", 80, 300) }
                timed("PveClient.overview, 300 ms connect and read timeouts") { PveClient(300, 300).overview("https://slow3.audit.test:8006", "fixture@pve!read", "x", "") }
            }
            "ipv4stack" -> {
                // What an IPv6 literal does when the environment (JAVA_TOOL_OPTIONS, _JAVA_OPTIONS) sets java.net.preferIPv4Stack.
                say("java.net.preferIPv4Stack", System.getProperty("java.net.preferIPv4Stack"))
                timed("Socket.connect([::1]:9)") { Socket().use { it.connect(InetSocketAddress(InetAddress.getByName("::1"), 9), 500); "connected" } }
                timed("ServerSocket on ::1") { ServerSocket(0, 1, InetAddress.getByName("::1")).use { "bound" } }
            }
            "ttl" -> {
                val first = InetAddress.getByName("ttl.audit.test").hostAddress
                Thread.sleep(1500)
                val second = InetAddress.getByName("ttl.audit.test").hostAddress
                say("first", first); say("second", second); say("resolverCalls", AuditResolverProvider.calls.get())
                say("networkaddress.cache.ttl", java.security.Security.getProperty("networkaddress.cache.ttl"))
                say("networkaddress.cache.negative.ttl", java.security.Security.getProperty("networkaddress.cache.negative.ttl"))
            }
            "ttl-expiry" -> {
                val first = InetAddress.getByName("ttl.audit.test").hostAddress
                var second = first
                var waited = 0
                while (second == first && waited < 70) { Thread.sleep(1000); waited++; second = InetAddress.getByName("ttl.audit.test").hostAddress }
                say("first", first); say("changedAfterSeconds", if (second == first) "never within 70" else waited); say("resolverCalls", AuditResolverProvider.calls.get())
            }
            "tls-names" -> {
                // Normal TLS (no pin): the JVM trust store was replaced with one that trusts the fixture certificate, so only
                // the host name check decides. The server listens on ::1 and is reached by the literal.
                val port = args[1]
                timed("api PveClient") { PveClient(3000, 3000).overview("https://[::1]:$port", "fixture@pve!read", "x", "").nodes }
                timed("storage socket (storageWire)") {
                    storageWire(serverOrigin("https://[::1]:$port"), "", "/api2/json/x", "GET", emptyMap(), null, null, null, 3000, 3000, UploadCancel(), {}, response = { code, _ -> code })
                }
                timed("console HttpClient") {
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build().use { client ->
                        client.send(HttpRequest.newBuilder(URI("https://[::1]:$port/api2/json/x")).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode()
                    }
                }
            }
            else -> error("unknown scenario ${args[0]}")
        }
    }
}
