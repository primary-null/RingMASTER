package local.ringleader.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import local.ringleader.core.audit.AuditTls

/**
 * [NetworkDefaults.install] in child JVMs ([NetChild]), with the JVM's proxy properties pointing at a [RecordingProxy]:
 * the same clients the app uses, from HttpsURLConnection, java.net.http.HttpClient and a raw Socket. Everything is loopback or
 * this computer's own addresses; nothing leaves the machine.
 */
class NetworkDefaultsJvmTest {
    private val child = "local.ringleader.core.NetChild"

    @Test fun installWiresTheSelectorAndTheResolverOnceAndKeepsAnExplicitSetting() {
        val r = NetChildJvm.run(child, listOf("defaults"))
        println("NET|defaults\n$r")
        assertEquals("null", r["useSystemProxies before"])
        assertEquals("LanProxySelector", r["selector"])
        assertEquals("true", r["idempotent"], "a second install changes nothing")
        assertEquals("true", r["installed"])
        assertEquals("true", r["useSystemProxies"], "the JDK is told to read the operating system's proxy settings")
        assertEquals("Active", r["resolver"], "the name-lookup hook is loaded by the install, not at some later first lookup")
        assertEquals("[DIRECT]", r["lan"])
        assertEquals("[DIRECT]", r["loopback"])

        val explicit = NetChildJvm.run(child, listOf("defaults"), jvmArgs = listOf("-Djava.net.useSystemProxies=false"))
        assertEquals("false", explicit["useSystemProxies"], "a setting somebody made on purpose stays")
        assertEquals("LanProxySelector", explicit["selector"])

        val plain = NetChildJvm.run(child, listOf("defaults"), env = mapOf("RINGMASTER_PLAIN_RESOLVER" to "1"))
        assertEquals("PlainBySwitch", plain["resolver"], "the support switch turns off the hook only")
        assertEquals("LanProxySelector", plain["selector"])
    }

    /** The self-check main that a packaged install runs with its own runtime and jars (see NetworkSelfCheck). */
    @Test fun theSelfCheckPassesWithBothHooksAndWithTheSwitch() {
        val r = NetChildJvm.run("local.ringleader.core.NetworkSelfCheck")
        println("SELFCHECK (default)\n$r")
        assertEquals(0, r.exit, r.toString())
        assertTrue(r.lines.contains("SELFCHECK|RESULT=ok"), r.toString())
        assertTrue(r.lines.contains("SELFCHECK|resolver=Active"), r.toString())
        assertTrue(r.lines.contains("SELFCHECK|proxySelector=local.ringleader.core.LanProxySelector"), r.toString())
        val plain = NetChildJvm.run("local.ringleader.core.NetworkSelfCheck", env = mapOf("RINGMASTER_PLAIN_RESOLVER" to "1"))
        assertEquals(0, plain.exit, plain.toString())
        assertTrue(plain.lines.contains("SELFCHECK|resolver=PlainBySwitch"), plain.toString())
    }

    @Test fun noLanClientTouchesTheProxyAndAPublicNameDoes() {
        RecordingProxy().use { proxy ->
            val hosts = Files.createTempFile("ringmaster-hosts", ".txt")
            try {
                Files.writeString(hosts, listOf("pvebox", "nas.local", "pve1.example.ts.net", "files.home.arpa", "pve.example.test").joinToString("\n") { "127.0.0.1 $it" } + "\n")
                val p = proxy.port
                val r = NetChildJvm.run(child, listOf("proxy-policy"), jvmArgs = listOf(
                    "-Dhttp.proxyHost=127.0.0.1", "-Dhttp.proxyPort=$p", "-Dhttps.proxyHost=127.0.0.1", "-Dhttps.proxyPort=$p", "-DsocksProxyHost=127.0.0.1", "-DsocksProxyPort=$p",
                    "-Dhttp.nonProxyHosts=", "-Djava.net.useSystemProxies=false", "-Djdk.net.hosts.file=${hosts.toAbsolutePath()}"))
                println("NET|proxy-policy (every JVM proxy property names the recording proxy)\n$r")
                println("PROXYLOG|${proxy.seen}")
                val clients = listOf("HttpsURLConnection", "HttpClient", "Socket")
                val local = listOf("127.0.0.1", "localhost", "pvebox", "nas.local", "pve1.example.ts.net", "files.home.arpa")
                for (name in local) {
                    for (client in clients) assertTrue(r["$name|$client"].orEmpty().startsWith("ok("), "$name by $client: ${r["$name|$client"]}")
                    assertEquals("null", r["$name|routeTag"], "$name: the route tag must not say 'via proxy' for a connection that goes direct")
                }
                // This computer's own private, tailnet and unique-local addresses, whichever it has.
                val kinds = r["own kinds"].orEmpty().split(',').filter { it.isNotEmpty() }
                for (kind in kinds) {
                    for (client in clients) assertTrue(r["own-$kind|$client"].orEmpty().startsWith("ok("), "own $kind address by $client: ${r["own-$kind|$client"]}")
                    assertEquals("null", r["own-$kind|routeTag"])
                }
                println("NET|own address kinds present on this computer: ${kinds.ifEmpty { listOf("none") }}")
                // A public-looking name is sent to the proxy by all three clients, and the tag says so.
                for (client in clients) assertTrue(!r["pve.example.test|$client"].orEmpty().startsWith("ok("), "pve.example.test by $client reached the server: ${r["pve.example.test|$client"]}")
                assertEquals("HTTP proxy 127.0.0.1:$p", r["pve.example.test|routeTag"])
                // The proxy saw the public name and nothing else.
                assertTrue(proxy.seen.any { it.startsWith("CONNECT pve.example.test:") }, "CONNECT for the public name: ${proxy.seen}")
                assertTrue(proxy.seen.any { it == "SOCKS5" }, "a SOCKS handshake from the raw socket: ${proxy.seen}")
                assertTrue(proxy.seen.all { it.startsWith("CONNECT pve.example.test:") || it.startsWith("SOCKS") }, "nothing else touched the proxy: ${proxy.seen}")
            } finally { Files.deleteIfExists(hosts) }
        }
    }

    /** The audit's `defectJvmProxySettingsApplyToSomeLanClientsAndNotOthers`, moved here: the scenario installs the policy first, as the app does. */
    @Test fun everyLanClientAgreesAboutTheProxy() = AuditTls.serve("127.0.0.1") { served ->
        RecordingProxy().use { proxy ->
            val r = NetChildJvm.run(child, listOf("proxy-lan-clients", served.port.toString(), served.pin),
                jvmArgs = listOf("-Dhttps.proxyHost=127.0.0.1", "-Dhttps.proxyPort=${proxy.port}", "-Dhttp.nonProxyHosts="))
            println("NET|proxy-lan-clients\n$r")
            println("PROXYLOG|${proxy.seen}")
            val lan = listOf("api PveClient.overview", "console HttpClient (as Vnc/Terminal/ConsoleStill build it)", "storage upload socket (storageWire)", "probeTlsFingerprint", "probeDiagnosticReachability")
            val direct = lan.filter { r[it]?.contains("ok(") == true }
            val viaProxy = lan.filter { r[it]?.contains("ok(") != true }
            println("PROXYSPLIT|direct=$direct|viaProxy=$viaProxy")
            assertTrue(direct.isEmpty() || viaProxy.isEmpty(), "direct=$direct viaProxy=$viaProxy; the proxy saw ${proxy.seen}")
            assertEquals(lan, direct, "all of them go direct to a LAN server, whatever the environment says")
            assertTrue(proxy.seen.isEmpty(), "the proxy was not touched: ${proxy.seen}")
            assertEquals("null", r["javaProxyFor(routeTag)"])
        }
    }
}
