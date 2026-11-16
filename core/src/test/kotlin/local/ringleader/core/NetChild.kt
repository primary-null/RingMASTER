package local.ringleader.core

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.spi.InetAddressResolver
import java.net.spi.InetAddressResolverProvider
import java.util.stream.Stream
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.thread
import local.ringleader.core.audit.AuditTls
import local.ringleader.core.tools.connectPort

/**
 * A name resolver for child JVMs: the real [RingResolver] built around scripted answers, installed through the real
 * `java.net.spi` service lookup (the child's class path puts a services file naming this class first). Names:
 * `slow*.test` answer after 3 s, `unknown*.test` do not exist, `two*.test` have a dead first address and a live second one
 * (127.0.0.2, 127.0.0.1), everything else answers 127.0.0.1 at once.
 * The hook's deadline comes from `-Dringmaster.test.deadline` (milliseconds, default 700).
 */
class ScriptedRingProvider : InetAddressResolverProvider() {
    override fun get(configuration: Configuration): InetAddressResolver {
        val scripted = object : InetAddressResolver {
            override fun lookupByName(host: String, policy: InetAddressResolver.LookupPolicy): Stream<InetAddress> {
                val lower = host.lowercase()
                if (lower.startsWith("slow")) Thread.sleep(3_000)
                if (lower.startsWith("unknown")) throw UnknownHostException("$host: Name or service not known")
                if (lower.startsWith("two")) return Stream.of(InetAddress.getByAddress(host, byteArrayOf(127, 0, 0, 2)), InetAddress.getByAddress(host, byteArrayOf(127, 0, 0, 1)))
                return Stream.of(InetAddress.getByAddress(host, byteArrayOf(127, 0, 0, 1)))
            }
            override fun lookupByAddress(addr: ByteArray): String = throw UnknownHostException()
        }
        return NameLookup.wrap(scripted, System.getProperty("ringmaster.test.deadline")?.toLongOrNull() ?: 700)
    }
    override fun name(): String = "ringmaster-test-scripted"
}

/** Main class of the child JVMs of [NameResolverJvmTest] and [AddressFallbackJvmTest]. Prints `NET|key=value`. */
object NetChild {
    private fun say(key: String, value: Any?) = println("NET|$key=$value")

    private fun <T> timed(key: String, block: () -> T) {
        val started = System.nanoTime()
        val outcome = try { "ok(${block()})" } catch (e: Throwable) { "${e.javaClass.simpleName}" }
        say(key, "${(System.nanoTime() - started) / 1_000_000}ms $outcome")
    }

    private fun accepting(listener: ServerSocket) = thread(isDaemon = true) { while (true) { try { listener.accept().close() } catch (_: Exception) { break } } }

    @JvmStatic fun main(args: Array<String>) {
        when (args[0]) {
            "status" -> {
                say("before", NameLookup.status)
                say("localhost", InetAddress.getByName("localhost").hostAddress)
                say("after", NameLookup.status)
                // Two more lookups of the same name come from the JVM's own cache and never reach the hook.
                InetAddress.getByName("localhost"); InetAddress.getByName("localhost")
                say("lookupsAfterThree", NameLookup.stats()?.lookups)
                // Literals are parsed by the JDK and never reach any resolver.
                InetAddress.getByName("192.0.2.10"); InetAddress.getByName("2001:db8::1"); InetAddress.getByName("[::1]"); InetAddress.getByName("fe80::1%12")
                say("lookupsAfterLiterals", NameLookup.stats()?.lookups)
                say("failure", NameLookup.failure)
            }
            "scripted" -> {
                say("fast", InetAddress.getByName("fast.test").hostAddress)
                timed("getByName(slow-a.test)") { InetAddress.getByName("slow-a.test") }
                say("slow-a timedOutRecently", NameLookup.timedOutRecently("slow-a.test"))
                // The JDK keeps a failure for 10 s: the repeat never reaches the hook and is a plain UnknownHostException.
                val before = NameLookup.stats()?.lookups
                try { InetAddress.getByName("slow-a.test") } catch (e: UnknownHostException) {
                    say("repeat class", e.javaClass.simpleName); say("repeat isLookupTimeout", NameLookup.isLookupTimeout(e, "slow-a.test"))
                }
                say("repeat reached the hook", NameLookup.stats()?.lookups != before)
                // Every client the app uses: what each one reports, and whether the side channel still says "timed out".
                for (label in listOf("Socket", "HttpsURLConnection", "HttpClient")) {
                    val host = "slow-${label.lowercase()}.test"
                    val started = System.nanoTime()
                    val error: Throwable? = try {
                        when (label) {
                            "Socket" -> Socket().use { it.connect(InetSocketAddress(host, 9), 300) }
                            "HttpsURLConnection" -> (URI.create("https://$host:9/").toURL().openConnection() as HttpsURLConnection).apply { connectTimeout = 300; readTimeout = 300 }.responseCode
                            else -> HttpClient.newHttpClient().use { it.send(HttpRequest.newBuilder(URI.create("https://$host:9/")).build(), HttpResponse.BodyHandlers.discarding()) }
                        }
                        null
                    } catch (e: Throwable) { e }
                    say("$label ms", (System.nanoTime() - started) / 1_000_000)
                    say("$label error", error?.javaClass?.simpleName)
                    say("$label isLookupTimeout", error?.let { NameLookup.isLookupTimeout(it, host) })
                }
                timed("getByName(unknown-a.test)") { InetAddress.getByName("unknown-a.test") }
                say("unknown-a timedOutRecently", NameLookup.timedOutRecently("unknown-a.test"))
                try { InetAddress.getByName("unknown-b.test") } catch (e: UnknownHostException) { say("unknown message", e.message) }
                say("literal", InetAddress.getByName("198.51.100.7").hostAddress)
                say("stats", NameLookup.stats())
            }
            "scripted-bound" -> {
                // The Tools port check with a lookup (3 s) that is slower than the caller's own bound (400 ms).
                // One call first, so that class loading in this fresh JVM is not part of what is timed.
                connectPort("warm.test", 9, 1000)
                timed("connectPort(slow-port.test, 400 ms)") { connectPort("slow-port.test", 80, 400) }
            }
            "scripted-port" -> {
                // The Tools port check and "Pin a service > Test" with a lookup that is slower than the caller's own bound, and with a name that does not exist.
                // One call first, so that class loading in this fresh JVM is not part of what is timed.
                connectPort("warm.test", 9, 1000)
                timed("connectPort(slow-port.test, 400 ms)") { connectPort("slow-port.test", 80, 400) }
                say("connectPort result", connectPort("slow-port2.test", 80, 400))
                say("connectPort unknown", connectPort("unknown-port.test", 80, 1000))
                val verdict = kotlinx.coroutines.runBlocking { ServiceProbe(connectTimeoutMs = 400).test("http://slow-probe.test/") }
                say("probe message", verdict.message)
                say("probe unknown", kotlinx.coroutines.runBlocking { ServiceProbe(connectTimeoutMs = 400).test("http://unknown-probe.test/") }.message)
            }
            "fallback" -> {
                // two.test = [127.0.0.2 (nothing there), 127.0.0.1 (the listener)]. The first plain lookup of the name comes AFTER connectPort:
                // the JVM keeps the answer it first sees for 30 s, so a plain lookup before it would keep the old order (see "primed").
                val listener = ServerSocket(0, 5, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
                accepting(listener)
                say("connectPort", connectPort("two.test", listener.localPort, 1000))
                timed("Socket.connect(name)") { Socket().use { it.connect(InetSocketAddress("two.test", listener.localPort), 1000); "connected" } }
                timed("Socket.connect(second address)") { Socket().use { it.connect(InetSocketAddress("127.0.0.1", listener.localPort), 1000); "connected" } }
                say("resolved", InetAddress.getAllByName("two.test").joinToString(",") { it.hostAddress })
            }
            "localhost" -> {
                // Which family comes first for `localhost`, and does a listener on the other family still get reached?
                val all = InetAddress.getAllByName("localhost")
                say("resolved", all.joinToString(",") { it.hostAddress })
                val onlyV6 = runCatching { ServerSocket(0, 5, InetAddress.getByName("::1")) }.getOrNull()
                val onlyV4 = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
                for (s in listOfNotNull(onlyV6, onlyV4)) accepting(s)
                if (onlyV6 != null) say("connectPort(localhost, listener only on ::1)", connectPort("localhost", onlyV6.localPort, 1000))
                say("connectPort(localhost, listener only on 127.0.0.1)", connectPort("localhost", onlyV4.localPort, 1000))
                if (onlyV6 != null) say("connectPort([::1] literal)", connectPort("::1", onlyV6.localPort, 1000))
            }
            "second-attempt", "primed" -> {
                // The service listens on ::1 only. `localhost` is [127.0.0.1, ::1], so a plain client stops at the first address and fails.
                // connectAny finds ::1 and remembers it; the next plain lookup of the name then lists ::1 first. "primed" does a plain
                // lookup of the name first: the JVM keeps that answer for 30 s, so the order cannot change within it.
                val v6 = runCatching { ServerSocket(0, 5, InetAddress.getByName("::1")) }.getOrNull()
                if (v6 == null) { say("skip", "no IPv6 loopback"); return }
                accepting(v6)
                if (args[0] == "primed") say("primed order", InetAddress.getAllByName("localhost").joinToString(",") { it.hostAddress })
                val families = NameLookup.resolveAll("localhost", 2000)
                say("resolved", families.joinToString(",") { it.hostAddress })
                if (families.size < 2 || families.first() !is java.net.Inet4Address || families.none { it is java.net.Inet6Address }) { say("skip", "localhost is not [IPv4, IPv6] here"); return }
                connectAny("localhost", v6.localPort, 2000).use { say("connectAny remote", it.inetAddress.hostAddress) }
                timed("Socket.connect(localhost)") { Socket().use { it.connect(InetSocketAddress("localhost", v6.localPort), 1000); "connected" } }
                say("order after", InetAddress.getAllByName("localhost").joinToString(",") { it.hostAddress })
                AuditTls.serve("::1") { served ->
                    timed("PveClient.overview(localhost)") { PveClient(3000, 3000).overview("https://localhost:${served.port}", "fixture@pve!read", "x", served.pin).nodes }
                }
            }
            "defaults" -> {
                say("useSystemProxies before", System.getProperty("java.net.useSystemProxies"))
                NetworkDefaults.install()
                val first = java.net.ProxySelector.getDefault()
                NetworkDefaults.install()
                say("selector", first.javaClass.simpleName)
                say("idempotent", first === java.net.ProxySelector.getDefault())
                say("installed", NetworkDefaults.isInstalled)
                say("useSystemProxies", System.getProperty("java.net.useSystemProxies"))
                say("resolver", NameLookup.status)
                say("lan", first.select(URI("https://nas.local:8006")))
                say("loopback", first.select(URI("https://[::1]:8006")))
            }
            "proxy-policy" -> {
                // The JVM's proxy properties name a recording proxy for every host (http.nonProxyHosts is empty, so even loopback would go
                // there under the JDK's own selector). The names are mapped to loopback by a hosts file. With the policy installed only
                // a name that does not look local may reach the proxy.
                NetworkDefaults.install()
                fun outcome(block: () -> Any?): String = try { "ok(${block()})" } catch (e: Throwable) { e.javaClass.simpleName }
                fun probe(label: String, host: String, authority: String, port: Int, pin: String) {
                    say("$label|HttpsURLConnection", outcome {
                        val c = URI.create("https://$authority:$port/api2/json/x").toURL().openConnection() as HttpsURLConnection
                        c.sslSocketFactory = pinnedSslContext(pin)!!.socketFactory
                        c.setHostnameVerifier { _, _ -> true }
                        c.connectTimeout = 3000; c.readTimeout = 3000
                        c.responseCode
                    })
                    say("$label|HttpClient", outcome {
                        HttpClient.newBuilder().sslContext(pinnedSslContext(pin)!!).connectTimeout(java.time.Duration.ofSeconds(3)).build().use { client ->
                            client.send(HttpRequest.newBuilder(URI.create("https://$authority:$port/api2/json/x")).timeout(java.time.Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode()
                        }
                    })
                    say("$label|Socket", outcome { Socket().use { it.connect(InetSocketAddress(host, port), 3000); "connected" } })
                    say("$label|routeTag", javaProxyFor(URI("https://$authority:$port")))
                }
                val names = listOf("127.0.0.1", "localhost", "pvebox", "nas.local", "pve1.example.ts.net", "files.home.arpa", "pve.example.test")
                AuditTls.serve("127.0.0.1") { served -> for (name in names) probe(name, name, name, served.port, served.pin) }
                // This computer's own addresses in the private, CGNAT (tailnet) and unique-local ranges, if it has any: a real connection
                // to such an address never leaves the computer. Only the kind is printed, never the address.
                val own = linkedMapOf<String, InetAddress>()
                for (nic in java.net.NetworkInterface.networkInterfaces().toList()) {
                    if (!runCatching { nic.isUp && !nic.isLoopback }.getOrDefault(false)) continue
                    for (a in nic.inetAddresses().toList()) {
                        val b = a.address
                        val kind = when {
                            a is java.net.Inet4Address && a.isSiteLocalAddress -> "private"
                            a is java.net.Inet4Address && (b[0].toInt() and 255) == 100 && (b[1].toInt() and 192) == 64 -> "tailnet"
                            a is java.net.Inet6Address && (b[0].toInt() and 254) == 252 -> "unique-local"
                            else -> null
                        } ?: continue
                        own.putIfAbsent(kind, a)
                    }
                }
                say("own kinds", own.keys.sorted().joinToString(","))
                for ((kind, address) in own) {
                    val ip = address.hostAddress.substringBefore('%')
                    AuditTls.serve(ip) { served -> probe("own-$kind", ip, if (':' in ip) "[$ip]" else ip, served.port, served.pin) }
                }
            }
            "proxy-lan-clients" -> {
                // The audit's scenario for finding 14, as the app starts: the policy is installed first. The JVM proxy properties name a
                // fake proxy; which of the app's LAN clients went through it?
                NetworkDefaults.install()
                val port = args[1]
                val pin = args[2]
                val address = "https://127.0.0.1:$port"
                say("javaProxyFor(routeTag)", javaProxyFor(URI(address)))
                timed("api PveClient.overview") { PveClient(2000, 2000).overview(address, "fixture@pve!read", "x", pin).nodes }
                timed("console HttpClient (as Vnc/Terminal/ConsoleStill build it)") {
                    HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(java.time.Duration.ofSeconds(2))
                        .apply { pinnedSslContext(pin)?.let { sslContext(it) } }.build().use { client ->
                            client.send(HttpRequest.newBuilder(URI("$address/api2/json/x")).timeout(java.time.Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode()
                        }
                }
                timed("storage upload socket (storageWire)") {
                    storageWire(serverOrigin(address), pin, "/api2/json/x", "GET", emptyMap(), null, null, null, 2000, 2000, UploadCancel(), {}, response = { code, _ -> code })
                }
                timed("probeTlsFingerprint") { kotlinx.coroutines.runBlocking { probeTlsFingerprint(address, 2000)?.take(11) } }
                timed("probeDiagnosticReachability") { kotlinx.coroutines.runBlocking { probeDiagnosticReachability(address, 2000).let { "tcp=${it.tcpSuccess} tls=${it.tlsHandshakeSuccess} http=${it.httpStatusCode}" } } }
                timed("update check transport") { HttpsUpdateTransport().get("$address/x", 1000, "audit").status }
            }
            else -> error("unknown scenario ${args[0]}")
        }
    }
}
