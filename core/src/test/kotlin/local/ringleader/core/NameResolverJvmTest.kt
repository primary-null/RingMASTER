package local.ringleader.core

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The name-lookup hook as the JDK really loads it: through `META-INF/services/java.net.spi.InetAddressResolverProvider`.
 * The first test runs in this JVM (the Gradle test JVM has the module's own resources on its class path, as the app has the
 * core jar); the rest start child JVMs ([NetChild]) so that flags, the environment and a scripted resolver stay out of this one.
 */
class NameResolverJvmTest {
    private val child = "local.ringleader.core.NetChild"

    @Test fun theProviderIsLoadedHereFromTheServicesFile() {
        assumeTrue(!NameLookup.plainRequested(), "RINGMASTER_PLAIN_RESOLVER is set for this test run")
        InetAddress.getByName("localhost")
        assertEquals(ResolverStatus.Active, NameLookup.status, "failure: ${NameLookup.failure}")
        assertNotNull(NameLookup.stats())
    }

    @Test fun theServicesFileNamesTheProviderAndNothingElse() {
        val file = java.io.File("src/main/resources/META-INF/services/java.net.spi.InetAddressResolverProvider")
        assertTrue(file.isFile, "Missing ${file.path}")
        assertEquals(listOf(RingResolverProvider::class.java.name), file.readLines(Charsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") })
    }

    @Test fun anInstalledHookLeavesLiteralsAndTheJvmCacheAlone() {
        val r = NetChildJvm.run(child, listOf("status"))
        println("NET|status\n$r")
        assertEquals("NotLoaded", r["before"], "the JDK loads its resolver lazily, at the first name lookup")
        assertEquals("Active", r["after"], "failure: ${r["failure"]}")
        assertEquals("1", r["lookupsAfterThree"], "three lookups of one name reached the hook once: the JVM's address cache still sits above it")
        assertEquals("1", r["lookupsAfterLiterals"], "IP literals never reach a resolver")
        assertTrue(r["localhost"].orEmpty().isNotEmpty())
    }

    @Test fun theSwitchTurnsTheHookOffAndNamesStillResolve() {
        val r = NetChildJvm.run(child, listOf("status"), env = mapOf("RINGMASTER_PLAIN_RESOLVER" to "1"))
        println("NET|status (RINGMASTER_PLAIN_RESOLVER=1)\n$r")
        assertEquals("PlainBySwitch", r["after"])
        assertTrue(r["localhost"].orEmpty().isNotEmpty(), "the JDK's own resolver answers")
        assertEquals("null", r["lookupsAfterThree"], "no hook, no statistics")
    }

    @Test fun theSystemPropertyIsTheSameSwitch() {
        val r = NetChildJvm.run(child, listOf("status"), jvmArgs = listOf("-Dringmaster.plainResolver=true"))
        assertEquals("PlainBySwitch", r["after"], r.toString())
    }

    /**
     * Audit finding 12 for the Tools port check: the caller's 400 ms bound covers the name lookup too (a lookup takes 3 s here, and
     * the hook's own deadline is 5 s), because connectPort passes its timeout on. The audit's `defectNameResolutionIsNotCoveredByAnyTimeout`
     * asked this for three callers; the other two (the certificate probe and the API client) wait for part 2 and keep their ignored tests.
     */
    @Test fun aCallBoundedToItsOwnTimeoutDoesNotWaitForASlowLookup() {
        val services = NetChildJvm.servicesDir("java.net.spi.InetAddressResolverProvider", ScriptedRingProvider::class.java.name)
        try {
            val r = NetChildJvm.run(child, listOf("scripted-bound"), jvmArgs = listOf("-Dringmaster.test.deadline=5000"), classPathFirst = listOf(services.toAbsolutePath()))
            println("NET|scripted-bound (hook deadline 5 s, caller bound 400 ms, the lookup takes 3 s)\n$r")
            val key = "connectPort(slow-port.test, 400 ms)"
            val millis = r[key].orEmpty().substringBefore("ms").toLongOrNull() ?: Long.MAX_VALUE
            assertTrue(millis < 1500, "$key returned after ${r[key]} although the caller bounded it to 400 ms (the resolver needs 3000 ms)")
            assertTrue(!r[key].orEmpty().contains("Open"), r[key])
        } finally { services.toFile().deleteRecursively() }
    }

    @Test fun aSlowScriptedResolverBehindTheHookEndsAtTheDeadlineThroughEveryClient() {
        val services = NetChildJvm.servicesDir("java.net.spi.InetAddressResolverProvider", ScriptedRingProvider::class.java.name)
        try {
            val r = NetChildJvm.run(child, listOf("scripted"), jvmArgs = listOf("-Dringmaster.test.deadline=700"), classPathFirst = listOf(services.toAbsolutePath()))
            println("NET|scripted (deadline 700 ms, lookups take 3 s)\n$r")
            assertEquals("127.0.0.1", r["fast"])
            val first = r["getByName(slow-a.test)"].orEmpty()
            val firstMillis = first.substringBefore("ms").toLong()
            assertTrue(firstMillis in 600..2_500, "getByName returned after $first, not after the resolver's 3 s")
            assertTrue(first.endsWith("NameLookupTimedOutException"), "the first caller gets the dedicated exception: $first")
            assertEquals("700", r["slow-a timedOutRecently"])
            assertEquals("UnknownHostException", r["repeat class"], "within 10 s the JDK answers from its own failure cache with a plain exception")
            assertEquals("true", r["repeat isLookupTimeout"], "and the side channel still knows the name timed out")
            assertEquals("false", r["repeat reached the hook"])
            for (label in listOf("Socket", "HttpsURLConnection", "HttpClient")) {
                val ms = r["$label ms"]!!.toLong()
                assertTrue(ms in 600..2_500, "$label returned after $ms ms")
                assertEquals("true", r["$label isLookupTimeout"], "$label: ${r["$label error"]}")
            }
            assertEquals("UnknownHostException", r["Socket error"], "the dedicated subclass does not survive InetSocketAddress")
            assertEquals("UnknownHostException", r["HttpsURLConnection error"])
            assertEquals("ConnectException", r["HttpClient error"], "HttpClient reports an unresolved address as a ConnectException")
            assertTrue(r["getByName(unknown-a.test)"].orEmpty().endsWith("UnknownHostException"), "a name that does not exist is not a timeout: ${r["getByName(unknown-a.test)"]}")
            assertNull(r["unknown-a timedOutRecently"]?.takeIf { it != "null" })
            assertEquals("unknown-b.test: Name or service not known", r["unknown message"], "the inner resolver's own message is the caller's")
            assertEquals("198.51.100.7", r["literal"])
        } finally { services.toFile().deleteRecursively() }
    }
}
