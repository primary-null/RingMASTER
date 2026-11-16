package local.ringleader.core

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The proxy policy: which hosts are never sent to a proxy, what everything else is told, and that a broken setting cannot stop a LAN connection. */
class NetworkDefaultsTest {
    private val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.example.test", 3128))

    /** A system selector that records every URI it is asked about and answers [answer]. */
    private class Recording(val answer: (URI) -> List<Proxy>) : ProxySelector() {
        val asked = CopyOnWriteArrayList<URI>()
        val failed = CopyOnWriteArrayList<URI?>()
        override fun select(uri: URI?): List<Proxy> { asked += uri; return answer(uri!!) }
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) { failed += uri }
    }

    private fun lan(selector: LanProxySelector, vararg uris: String) = uris.forEach { assertEquals(listOf(Proxy.NO_PROXY), selector.select(URI(it)), it) }

    // ---------------------------------------------------------------- which hosts

    @Test fun localHostsAreNeverSentToAProxy() {
        val system = Recording { listOf(proxy) }
        val selector = LanProxySelector(system, kept = { false })
        lan(selector,
            "https://10.200.0.5:8006", "https://172.16.4.2", "https://172.31.255.254:8006", "https://192.168.77.3:8006", "https://127.0.0.1", "https://127.1.2.3",
            "https://localhost:8006", "https://[::1]:8006", "https://169.254.10.20", "https://[fe80::1]", "https://[fe80::1%12]:8006", "https://[fd7a:115c:a1e0::1]:8006", "https://[fc00::5]",
            "https://100.64.0.7:8006", "https://100.127.255.255", "https://nas.local:8006", "https://NAS.LOCAL.:8006", "https://pve1.example.ts.net:8006", "https://pvebox:8006", "https://PVEBOX.:8006",
            "https://files.home.arpa", "https://printer.lan", "https://pve.example.internal", "https://box.localdomain", "https://[::ffff:10.200.0.5]:8006", "https://[fec0::1]",
            "https://0.0.0.0:80", "https://[::]:80", "wss://nas.local:8006/api2/json/x?y=1", "http://192.168.77.3:3000/", "socket://nas.local:22", "socket://[fd7a:115c:a1e0::1]:22", "socket://fd00:db8::1:80",
        )
        assertTrue(system.asked.isEmpty(), "the system selector was never asked about a local host: ${system.asked}")
    }

    @Test fun everythingElseAsksTheSystemSelectorAndPassesItsAnswerOn() {
        val system = Recording { listOf(proxy) }
        val selector = LanProxySelector(system, kept = { false })
        val other = listOf("https://github.com/primary-null/RingMASTER/releases/latest/download/x.json", "https://objects.githubusercontent.com/x", "https://example.com", "https://8.8.8.8",
            "https://[2606:4700:4700::1111]", "https://pve.example.test:8006", "https://192.0.2.10", "https://100.63.255.255", "https://100.128.0.1", "https://172.15.0.1", "https://172.32.0.1",
            "https://11.0.0.1", "https://[2001:db8::1]:8006", "https://[2001:4860::8888]", "https://[::ffff:8.8.8.8]")
        for (uri in other) assertEquals(listOf(proxy), selector.select(URI(uri)), uri)
        assertEquals(other.map(::URI), system.asked.toList())
    }

    @Test fun anEmptyOrMissingAnswerFromTheSystemIsDirect() {
        assertEquals(listOf(Proxy.NO_PROXY), LanProxySelector(Recording { emptyList() }, kept = { false }).select(URI("https://github.com/")))
        assertEquals(listOf(Proxy.NO_PROXY), LanProxySelector(null, kept = { false }).select(URI("https://github.com/")))
    }

    // ---------------------------------------------------------------- a broken setting

    @Test fun aBrokenSystemSettingCannotReachALanConnection() {
        val never = CountDownLatch(1)
        val system = Recording { never.await(); listOf(proxy) }
        // A selector that asked would wait the whole 20 s bound; one that does not answers at once. The bar sits far from both, so load cannot trip it.
        val selector = LanProxySelector(system, delegateTimeoutMillis = 20_000, kept = { false })
        val started = System.nanoTime()
        lan(selector, "https://192.168.77.3:8006", "https://nas.local:8006", "https://[fd7a::1]:8006", "https://pvebox")
        val took = (System.nanoTime() - started) / 1_000_000
        never.countDown()
        assertTrue(system.asked.isEmpty(), "the broken system selector is never asked about a LAN host: ${system.asked}")
        assertTrue(took < 5_000, "four LAN selections took $took ms: the broken system selector is never waited for")
    }

    @Test fun aSystemSelectorThatThrowsIsDirect() {
        val selector = LanProxySelector(Recording { throw IllegalStateException("registry unreadable") }, kept = { false })
        assertEquals(listOf(Proxy.NO_PROXY), selector.select(URI("https://github.com/")))
    }

    @Test fun aSystemSelectorThatDoesNotAnswerIsDirectAfterTheBoundAndNotAskedAgainForAWhile() {
        val never = CountDownLatch(1)
        val offset = AtomicLong()
        val system = Recording { never.await(); listOf(proxy) }
        val selector = LanProxySelector(system, delegateTimeoutMillis = 150, kept = { false }, clock = { System.nanoTime() + offset.get() })
        try {
            val first = System.nanoTime()
            assertEquals(listOf(Proxy.NO_PROXY), selector.select(URI("https://github.com/")))
            val took = (System.nanoTime() - first) / 1_000_000
            assertTrue(took in 100..3_000, "took $took ms for a 150 ms bound")
            assertEquals(listOf(Proxy.NO_PROXY), selector.select(URI("https://objects.githubusercontent.com/x")))
            assertEquals(1, system.asked.size, "a system selector that was too slow is left alone for ten minutes")
            offset.addAndGet(TimeUnit.MINUTES.toNanos(11))
            val third = System.nanoTime()
            selector.select(URI("https://example.com/"))
            assertEquals(2, system.asked.size, "and asked again afterwards")
            assertTrue((System.nanoTime() - third) / 1_000_000 in 100..3_000)
        } finally { never.countDown() }
    }

    @Test fun connectFailedGoesToTheSystemSelector() {
        val system = Recording { listOf(proxy) }
        LanProxySelector(system).connectFailed(URI("https://github.com/"), proxy.address(), IOException("down"))
        assertEquals(listOf(URI("https://github.com/")), system.failed.toList())
        LanProxySelector(null).connectFailed(null, null, null)
    }

    @Test fun strangeInputNeverThrows() {
        val selector = LanProxySelector(Recording { listOf(proxy) }, kept = { false })
        assertEquals(listOf(Proxy.NO_PROXY), selector.select(null))
        for (uri in listOf("mailto:someone@example.test", "file:///tmp/x", "urn:isbn:1", "socket://:80", "https:///x")) {
            assertEquals(listOf(Proxy.NO_PROXY), selector.select(URI(uri)), uri)
        }
    }

    // ---------------------------------------------------------------- servers of this app

    @Test fun aServerRegisteredByItsNameIsReachedDirectlyWhateverItsNameLooksLike() {
        val host = "pve-${System.nanoTime()}.mydomain.example"
        val selector = LanProxySelector(Recording { listOf(proxy) })
        assertEquals(listOf(proxy), selector.select(URI("https://$host:8006")), "an ordinary public-looking name is proxied until it is registered")
        NetworkDefaults.keepDirect(host.uppercase() + ".")
        try {
            assertTrue(NetworkDefaults.isKeptDirect(host))
            assertEquals(listOf(Proxy.NO_PROXY), selector.select(URI("https://$host:8006")))
            assertEquals(listOf(proxy), selector.select(URI("https://another-$host:8006")), "only that name")
        } finally { NetworkDefaults.forget(host) }
        assertFalse(NetworkDefaults.isKeptDirect(host))
    }

    @Test fun theRegistryIsBounded() {
        val added = (1..300).map { "bounded-${System.nanoTime()}-$it.example.test" }
        try {
            added.forEach(NetworkDefaults::keepDirect)
            assertTrue(added.count { NetworkDefaults.isKeptDirect(it) } <= 256, "at most 256 hosts are kept")
        } finally { added.forEach(NetworkDefaults::forget) }
    }

    // ---------------------------------------------------------------- the route tag

    /** The connection tag asks this same selector (javaProxyFor), so it says "via proxy" only for a connection that really goes through one. */
    @Test fun theRouteTagStillTellsTheTruth() {
        val selector = LanProxySelector(Recording { listOf(proxy) }, kept = { false })
        assertNull(javaProxyFor(URI("https://192.168.77.3:8006"), selector), "a LAN server is reached directly by every client now")
        assertNull(javaProxyFor(URI("https://nas.local:8006"), selector))
        assertNull(javaProxyFor(URI("https://[fd7a:115c:a1e0::1]:8006"), selector))
        assertEquals("HTTP proxy proxy.example.test:3128", javaProxyFor(URI("https://github.com/"), selector))
    }

    // ---------------------------------------------------------------- what install must and must not contain

    @Test fun installLeavesAMarkedPlaceForTheRetryPostPropertyAndDoesNotSetItItself() {
        val source = java.io.File("src/main/kotlin/local/ringleader/core/NetworkDefaults.kt")
        assertTrue(source.isFile, "Missing ${source.path}")
        val lines = source.readLines(Charsets.UTF_8)
        assertTrue(lines.any { "RETRY-POST" in it }, "the marked place is gone")
        val code = lines.filter { !it.trimStart().startsWith("//") && !it.trimStart().startsWith("*") }.joinToString(" ")
        assertFalse(code.contains("sun.net.http.retryPost"), "another branch adds that line; this change must not touch the property in code")
    }

    @Test fun theProxyPolicyNeverLogsOrWritesAHost() {
        val forbidden = listOf("System.out", "System.err", "println(", "print(", "Logger", "java.util.logging", "printStackTrace", "Files.", "File(", "FileWriter", "FileOutputStream", "Trace.")
        val source = java.io.File("src/main/kotlin/local/ringleader/core/NetworkDefaults.kt")
        val offenders = source.readLines(Charsets.UTF_8).withIndex().filter { (_, line) -> !line.trimStart().startsWith("*") && !line.trimStart().startsWith("//") && forbidden.any { it in line } }
        assertTrue(offenders.isEmpty(), offenders.map { "line ${it.index + 1}: ${it.value.trim()}" }.toString())
    }

    @Test fun theSelectorIsInstalledOnlyOverTheJdksOwn() {
        // Not installed in this JVM by the tests: the JVM-wide install runs in child JVMs (NetworkDefaultsJvmTest).
        val before = ProxySelector.getDefault()
        assertSame(before, ProxySelector.getDefault())
        assertFalse(before is LanProxySelector, "no test may leave the policy installed in the shared test JVM")
    }
}
