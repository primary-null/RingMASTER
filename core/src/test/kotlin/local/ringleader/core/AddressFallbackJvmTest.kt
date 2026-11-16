package local.ringleader.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Audit finding 13, "only the first address of a name is tried", in child JVMs ([NetChild]) that load the app's name-lookup hook
 * the way the app does. The first three tests are the audit's `defect...` tests, moved here with the same demands; their scenarios
 * only had to learn that the JVM keeps the first answer it sees for a name for 30 s (see [characterizationAJvmCacheEntryKeepsTheOldOrder]).
 */
class AddressFallbackJvmTest {
    private val child = "local.ringleader.core.NetChild"

    /** two.test = [127.0.0.2, 127.0.0.1] and only the second answers. curl, browsers and Happy Eyeballs would connect. */
    @Test fun jdkSocketsReachTheSecondAddressOfANameOnTheSecondAttempt() {
        val services = NetChildJvm.servicesDir("java.net.spi.InetAddressResolverProvider", ScriptedRingProvider::class.java.name)
        try {
            val r = NetChildJvm.run(child, listOf("fallback"), classPathFirst = listOf(services.toAbsolutePath()))
            println("NET|fallback\n$r")
            assertTrue(r["Socket.connect(second address)"].orEmpty().contains("ok(connected)"), "sanity: the second address accepts: ${r.values}")
            assertTrue(r["connectPort"].orEmpty().startsWith("Open"), "connectPort(two.test) says ${r["connectPort"]}; ${r["resolved"]}")
            assertTrue(r["Socket.connect(name)"].orEmpty().contains("ok(connected)"), "Socket.connect(name): ${r["Socket.connect(name)"]}")
            assertEquals("127.0.0.1,127.0.0.2", r["resolved"], "the name now lists the address that worked first")
        } finally { services.toFile().deleteRecursively() }
    }

    /** Default flags: IPv4 first. With the service on ::1 only, the name fails by plain connect and the literal works. */
    @Test fun localhostIsReachedWhenTheServiceIsOnlyOnTheSecondFamily() {
        val r = NetChildJvm.run(child, listOf("localhost"))
        println("NET|localhost (default flags)\n$r")
        assumeTrue(r["resolved"]?.startsWith("127.0.0.1") == true && r["resolved"]?.contains(":") == true, "localhost is not dual stack here: ${r["resolved"]}")
        val v6Only = r["connectPort(localhost, listener only on ::1)"]
        assumeTrue(v6Only != null, "no IPv6 loopback listener here")
        assertTrue(r["connectPort([::1] literal)"].orEmpty().startsWith("Open"), "sanity: the literal reaches the ::1 listener")
        assertTrue(v6Only!!.startsWith("Open"), "listener only on ::1, name = localhost: $v6Only")
    }

    /** A user (or JAVA_TOOL_OPTIONS) sets the documented flag. AAAA now comes first and a server that listens on IPv4 only was not reached by name. */
    @Test fun preferIpv6FlagDoesNotMakeAnIpv4OnlyServerUnreachableByName() {
        val r = NetChildJvm.run(child, listOf("localhost"), jvmArgs = listOf("-Djava.net.preferIPv6Addresses=true"))
        println("NET|localhost (-Djava.net.preferIPv6Addresses=true)\n$r")
        assumeTrue(r["resolved"]?.startsWith("0:0:0:0:0:0:0:1") == true || r["resolved"]?.startsWith("::1") == true, "flag had no effect here: ${r["resolved"]}")
        assertTrue(r["connectPort(localhost, listener only on 127.0.0.1)"].orEmpty().startsWith("Open"), r.values.toString())
    }

    /**
     * The clients that cannot try the next address. `localhost` is [127.0.0.1, ::1] and the service is on ::1: a plain Socket and the API
     * client fail at 127.0.0.1. After one connectAny has found ::1, the next lookup of the name lists it first and both connect.
     */
    @Test fun aPlainSocketAndTheApiClientReachLocalhostAfterConnectAnyFoundTheWorkingAddress() {
        val r = NetChildJvm.run(child, listOf("second-attempt"))
        println("NET|second-attempt (real provider, real localhost)\n$r")
        assumeTrue(r["skip"] == null, r["skip"].orEmpty())
        assertEquals("0:0:0:0:0:0:0:1", r["connectAny remote"], "connectAny tried 127.0.0.1, was refused, and found ::1")
        assertTrue(r["Socket.connect(localhost)"].orEmpty().contains("ok(connected)"), "plain Socket by name: ${r["Socket.connect(localhost)"]}")
        assertTrue(r["order after"].orEmpty().startsWith("0:0:0:0:0:0:0:1"), "localhost now lists ::1 first: ${r["order after"]}")
        assertTrue(r["PveClient.overview(localhost)"].orEmpty().contains("ok(1)"), "the API client by name: ${r["PveClient.overview(localhost)"]}")
    }

    /**
     * What the hook cannot do: the JVM keeps the first answer it sees for a name for 30 s, whatever order the hook would give later.
     * A plain lookup before connectAny therefore keeps the old order for that long. Pinned so the report's statement stays true.
     */
    @Test fun characterizationAJvmCacheEntryKeepsTheOldOrder() {
        val r = NetChildJvm.run(child, listOf("primed"))
        println("NET|primed\n$r")
        assumeTrue(r["skip"] == null, r["skip"].orEmpty())
        assertEquals("0:0:0:0:0:0:0:1", r["connectAny remote"], "connectAny does not depend on the JVM's cache")
        assertTrue(!r["Socket.connect(localhost)"].orEmpty().contains("ok(connected)"), "the plain Socket still used the cached order: ${r["Socket.connect(localhost)"]}")
        assertTrue(r["order after"].orEmpty().startsWith("127.0.0.1"), "the cached order is unchanged: ${r["order after"]}")
    }
}
