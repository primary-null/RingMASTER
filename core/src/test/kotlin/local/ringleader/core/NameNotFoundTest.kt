package local.ringleader.core

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import local.ringleader.core.tools.PortCheckPlugin
import local.ringleader.core.tools.PortOutcome
import local.ringleader.core.tools.ToolEvent
import local.ringleader.core.tools.connectPort
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Audit finding 2 for the Tools port check and "Pin a service > Test": a name that does not exist (or whose lookup ran out of time)
 * is neither "filtered" nor a firewall. The first two tests are the audit's `defect...` tests, moved here with the same demands.
 */
class NameNotFoundTest {
    private fun unknownNamesFail() = runCatching { InetAddress.getByName("no-such-host.invalid"); false }.getOrDefault(true)

    @Test fun portCheckDoesNotCallAFailedNameLookupFiltered() {
        assumeTrue(unknownNamesFail(), "this resolver answers for .invalid names")
        val outcome = connectPort("no-such-host.invalid", 80, 1000)
        assertTrue(outcome !is PortOutcome.Filtered && outcome !is PortOutcome.Closed, "an unknown name is neither 'filtered' nor 'closed': $outcome")
        assertEquals(PortOutcome.NameNotFound(timedOut = false), outcome)
    }

    @Test fun serviceTestDoesNotBlameAFirewallForAnUnknownName() {
        assumeTrue(unknownNamesFail(), "this resolver answers for .invalid names")
        val verdict = runBlocking { ServiceProbe().test("http://no-such-host.invalid/") }
        assertFalse("firewall" in verdict.message, verdict.message)
        assertEquals(TestLevel.Bad, verdict.level)
        assertFalse("no-such-host" in verdict.message, "the verdict never names the host, so it can stay visible in showcase mode")
    }

    @Test fun theVerdictsForANameThatNeverBecameAnAddressAreTheirOwn() {
        val url = "http://pve.example.test:3000"
        assertEquals("That name is not known. Check the spelling, or use the guest's IP address.", describeTest(url, ServiceAnswer("pve.example.test", 3000, PortState.NameNotFound)).message)
        assertEquals("Looking up that name took too long. Check the DNS server, or use the guest's IP address.", describeTest(url, ServiceAnswer("pve.example.test", 3000, PortState.NameTimedOut)).message)
        // The older verdicts are untouched.
        assertEquals("Port 3000 is closed.", describeTest(url, ServiceAnswer("192.0.2.10", 3000, PortState.Closed)).message)
        assertEquals("No answer on port 3000. The guest may be off, or a firewall drops it.", describeTest(url, ServiceAnswer("192.0.2.10", 3000, PortState.NoAnswer)).message)
    }

    @Test fun portCheckSaysOnceThatTheNameHasNoAddressAndStops() = runBlocking {
        assumeTrue(unknownNamesFail(), "this resolver answers for .invalid names")
        val lines = mutableListOf<String>()
        val result = PortCheckPlugin().run(mapOf("host" to "no-such-host.invalid", "ports" to "22,80,443", "timeout" to "1000")) { if (it is ToolEvent.Line) lines += it.text }
        assertFalse(result.ok)
        assertEquals("No address for no-such-host.invalid.", result.summary)
        assertEquals(listOf("No address for no-such-host.invalid."), lines, "one line, not one 'filtered' per port")
    }

    /** A lookup slower than the caller's own bound, through the real hook (child JVM, scripted resolver). */
    @Test fun aCallBoundedToItsOwnTimeoutDoesNotWaitForASlowLookup() {
        val services = NetChildJvm.servicesDir("java.net.spi.InetAddressResolverProvider", ScriptedRingProvider::class.java.name)
        try {
            val r = NetChildJvm.run("local.ringleader.core.NetChild", listOf("scripted-port"), jvmArgs = listOf("-Dringmaster.test.deadline=5000"), classPathFirst = listOf(services.toAbsolutePath()))
            println("NET|scripted-port (hook deadline 5 s, callers bound to 400 ms, lookups take 3 s)\n$r")
            val key = "connectPort(slow-port.test, 400 ms)"
            val millis = r[key].orEmpty().substringBefore("ms").toLongOrNull() ?: Long.MAX_VALUE
            assertTrue(millis < 1500, "$key returned after ${r[key]} although the caller bounded it to 400 ms (the resolver needs 3000 ms)")
            assertEquals("NameNotFound(timedOut=true)", r["connectPort result"], "a lookup that ran out of time is not 'no such name'")
            assertEquals("NameNotFound(timedOut=false)", r["connectPort unknown"])
            assertEquals("Looking up that name took too long. Check the DNS server, or use the guest's IP address.", r["probe message"])
            assertEquals("That name is not known. Check the spelling, or use the guest's IP address.", r["probe unknown"])
        } finally { services.toFile().deleteRecursively() }
    }
}
