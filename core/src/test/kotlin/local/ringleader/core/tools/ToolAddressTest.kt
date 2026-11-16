package local.ringleader.core.tools

import java.net.Inet6Address
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * IPv6 in the tools (audit finding 5): the HTTP / TLS tool takes bracketed IPv6 URLs, every Host field takes every valid IPv6 text
 * form and a link-local address with its zone, an invalid host is refused with a sentence that says why, the IPv6 flags are right
 * per operating system, and no host can ever become an option of the program that is run.
 */
class ToolAddressTest {
    // ---------------------------------------------------------------- the audit's tests, moved with their demands

    /** `URI.getHost()` keeps the brackets of an IPv6 literal, `isHostToken` rejects `[` and `]`, so no IPv6 URL passed the HTTP / TLS tool. */
    @Test fun httpTlsToolAcceptsIpv6Urls() {
        for (url in listOf("https://[2001:db8::1]/", "http://[::1]:8006/", "https://[2001:db8::10]:8006/api2/json/version")) {
            assertNotNull(validateHttpUrl(url), url)
        }
    }

    /** Seven groups plus `::` has eight colons. `isIpv6` allowed two to seven, so valid addresses were refused by every Host field. */
    @Test fun validIpv6LiteralsWithEightColonsAreAccepted() {
        for (literal in listOf("1:2:3:4:5:6:7::", "::2:3:4:5:6:7:8")) {
            assertTrue(InetAddress.getByName(literal) is Inet6Address, "the JDK accepts $literal")
            assertTrue(isIpv6(literal), "isIpv6($literal)")
            assertTrue(isHostToken(literal), "isHostToken($literal)")
        }
    }

    /** A link-local address cannot be reached without its zone, and a zone is part of what ping and traceroute take. */
    @Test fun toolHostFieldsCanNameALinkLocalAddress() {
        assertTrue(isHostToken("fe80::1%12"))
    }

    // ---------------------------------------------------------------- hosts

    @Test fun everyValidHostIsAccepted() {
        val everywhere = listOf(
            "192.0.2.10", "0.0.0.0", "255.255.255.255", "pve.example.test", "localhost", "pve.example.ts.net", "xn--bcher-kva.example.test", "a", "pve-1.example.test.",
            "::", "::1", "2001:db8::1", "2001:DB8::10", "2001:0db8:0000:0000:0000:0000:0000:0010", "1:2:3:4:5:6:7:8", "1:2:3:4:5:6:7::", "::2:3:4:5:6:7:8", "1::8",
            "::ffff:192.0.2.10", "64:ff9b::192.0.2.33", "fe80::1%12", "fe80::1%1", "FE80::ABCD%4294967295",
        )
        for (host in everywhere) {
            assertNull(hostRefusal(host, "Host", namedZones = false), "Windows: $host")
            assertNull(hostRefusal(host, "Host", namedZones = true), "Linux: $host")
        }
        for (host in listOf("fe80::1%eth0", "fe80::1%wlp3s0", "fe80::1%br-lan", "fe80::1%veth_a.1")) {
            assertNull(hostRefusal(host, "Host", namedZones = true), "Linux: $host")
            assertTrue(hostRefusal(host, "Host", namedZones = false)!!.contains("interface number"), "Windows takes only the interface number: $host")
        }
    }

    @Test fun everyInvalidHostIsRefusedWithAReason() {
        val reasons = listOf(
            "" to "Enter host.",
            "[2001:db8::1]" to "without square brackets",
            "[2001:db8::1]:8006" to "must be a hostname or an IP address.",
            "-n" to "hyphen", "--help" to "hyphen", "-c" to "hyphen", "-oProxyCommand=x" to "hyphen",
            "192.0.02.10" to "leading zeros", "192.0.002.010" to "octal", "192.0.2" to "valid IPv4", "256.1.1.1" to "valid IPv4", "1.2.3.4.5" to "valid IPv4", "+1.2.3.4" to "must be a hostname",
            "1:2:3:4:5:6:7:8:9" to "valid IPv6", "2001:db8:::1" to "valid IPv6", "1::2::3" to "valid IPv6", ":1:2" to "valid IPv6", "12345::" to "valid IPv6", "1:2:3:4:5:6:7" to "valid IPv6",
            "2001:db8::1%12" to "link-local", "pve.example.test%12" to "can only follow an IPv6", "192.0.2.10%12" to "can only follow an IPv6", "fe80::1%" to "zone after % is empty", "fe80::1%-x" to "interface number or name",
            "fe80::1%eth0;x" to "interface number or name", "fe80::1%a b" to "interface number or name", "fe80::1%12345678901234567" to "interface number or name",
            "a b" to "must be a hostname", "pve;rm" to "must be a hostname", "$(x)" to "must be a hostname", "`x`" to "must be a hostname", "a|b" to "must be a hostname", "a&b" to "must be a hostname",
            "pve_1.example.test" to "must be a hostname", "pve..example.test" to "must be a hostname", "12.34" to "valid IPv4", "1234" to "must be a hostname",
            "a".repeat(254) to "too long",
        )
        for ((host, reason) in reasons) {
            val message = hostRefusal(host, "Host", namedZones = true)
            assertNotNull(message, "'$host' must be refused")
            assertTrue(reason.lowercase() in message.lowercase(), "'$host' was refused with '$message', expected it to mention '$reason'")
        }
        assertFailsWith<ToolInputException> { validateHost("pve;rm") }
        assertEquals("Host cannot start with a hyphen: a program would read it as an option.", assertFailsWith<ToolInputException> { validateHost("-n") }.message)
    }

    @Test fun theSentenceNamesTheFieldItIsAbout() {
        assertEquals("Name must be a hostname or an IP address.", assertFailsWith<ToolInputException> { validateHost("a b", "Name") }.message)
        assertEquals("URL host must be a hostname or an IP address.", hostRefusal("pve_1.example.test", "URL host"))
    }

    @Test fun windowsOnlyTakesTheInterfaceNumberAsAZone() {
        val windows = hostRefusal("fe80::1%Ethernet", "Host", namedZones = false)
        assertEquals("On Windows the zone is the interface number, like fe80::1%12.", windows)
    }

    // ---------------------------------------------------------------- URLs

    @Test fun httpUrlsTakeBracketedIpv6AndKeepTheirBrackets() {
        for (url in listOf("https://[2001:db8::1]/", "http://[::1]:8006/", "https://[::ffff:192.0.2.10]:8443/", "https://[1:2:3:4:5:6:7::]/x?y=1", "https://[2001:DB8::10]:8006/api2/json/version", "https://192.0.2.10:8006/", "http://pve.example.test/")) {
            val uri = validateHttpUrl(url)
            assertEquals(url, uri.toString())
        }
        assertEquals("[2001:db8::1]", validateHttpUrl("https://[2001:db8::1]/").host, "the URI still carries the brackets HttpClient needs")
        assertTrue(bypassProxy(validateHttpUrl("https://[2001:db8::1]/").host), "an IPv6 literal is reached directly, like an IPv4 one")
    }

    @Test fun httpUrlsRefuseWhatIsNotAnAddressWithAReason() {
        assertTrue(assertFailsWith<ToolInputException> { validateHttpUrl("https://[fe80::1%2512]/") }.message!!.isNotBlank())
        for (url in listOf("https://[1:2:3]/", "https://[2001:db8::1/", "https://user@[2001:db8::1]/", "ftp://[::1]/", "https://[::1]\n/", "https://[fe80::1%12]/")) {
            assertFailsWith<ToolInputException>(url) { validateHttpUrl(url) }
        }
        assertEquals("Enter an http or https URL without a username.", assertFailsWith<ToolInputException> { validateHttpUrl("https://user@[2001:db8::1]/") }.message)
    }

    // ---------------------------------------------------------------- argument vectors per operating system

    private val windows = "Windows 11"
    private val linux = "Linux"

    @Test fun pingGetsTheFamilyFlagAndTheEndOfOptionsWhereTheToolHasThem() {
        assertEquals(listOf("ping.exe", "-n", "4", "-w", "1000", "192.0.2.10"), pingCommand(windows, "ping.exe", "192.0.2.10", 4, 1000))
        assertEquals(listOf("ping.exe", "-n", "4", "-w", "1000", "pve.example.test"), pingCommand(windows, "ping.exe", "pve.example.test", 4, 1000), "a name: the system decides the family")
        assertEquals(listOf("ping.exe", "-n", "4", "-w", "1000", "-6", "2001:db8::1"), pingCommand(windows, "ping.exe", "2001:db8::1", 4, 1000))
        assertEquals(listOf("ping.exe", "-n", "4", "-w", "1000", "-6", "fe80::1%12"), pingCommand(windows, "ping.exe", "fe80::1%12", 4, 1000))
        assertEquals(listOf("/bin/ping", "-c", "4", "-W", "2", "--", "192.0.2.10"), pingCommand(linux, "/bin/ping", "192.0.2.10", 4, 1500))
        assertEquals(listOf("/bin/ping", "-c", "4", "-W", "1", "--", "pve.example.test"), pingCommand(linux, "/bin/ping", "pve.example.test", 4, 1000))
        assertEquals(listOf("/bin/ping", "-c", "4", "-W", "2", "-6", "--", "2001:db8::1"), pingCommand(linux, "/bin/ping", "2001:db8::1", 4, 1500))
        assertEquals(listOf("/bin/ping", "-c", "4", "-W", "2", "-6", "--", "fe80::1%eth0"), pingCommand(linux, "/bin/ping", "fe80::1%eth0", 4, 1500))
    }

    @Test fun tracerouteGetsTheFamilyFlagAndTheEndOfOptionsWhereTheToolHasThem() {
        val tracert = { _: String -> "tracert.exe" }
        assertEquals(listOf("tracert.exe", "-d", "-w", "500", "-h", "5", "192.0.2.10"), traceCommand(windows, tracert, "192.0.2.10", 500, 5))
        assertEquals(listOf("tracert.exe", "-d", "-w", "500", "-h", "5", "-6", "2001:db8::1"), traceCommand(windows, tracert, "2001:db8::1", 500, 5))
        assertEquals(listOf("tracert.exe", "-d", "-w", "500", "-h", "5", "-6", "fe80::1%12"), traceCommand(windows, tracert, "fe80::1%12", 500, 5))
        val traceroute = { name: String -> if (name == "traceroute") "/usr/bin/traceroute" else null }
        assertEquals(listOf("/usr/bin/traceroute", "-n", "-w", "1", "-m", "8", "192.0.2.10"), traceCommand(linux, traceroute, "192.0.2.10", 1000, 8))
        assertEquals(listOf("/usr/bin/traceroute", "-n", "-w", "1", "-m", "8", "-6", "2001:db8::1"), traceCommand(linux, traceroute, "2001:db8::1", 1000, 8))
        assertEquals(listOf("/usr/bin/traceroute", "-n", "-w", "1", "-m", "8", "-6", "fe80::1%eth0"), traceCommand(linux, traceroute, "fe80::1%eth0", 1000, 8))
        val tracepath = { name: String -> if (name == "tracepath") "/usr/bin/tracepath" else null }
        assertEquals(listOf("/usr/bin/tracepath", "-n", "-m", "8", "--", "192.0.2.10"), traceCommand(linux, tracepath, "192.0.2.10", 1000, 8))
        assertEquals(listOf("/usr/bin/tracepath", "-n", "-m", "8", "-6", "--", "2001:db8::1"), traceCommand(linux, tracepath, "2001:db8::1", 1000, 8))
        assertNull(traceCommand(linux, { null }, "2001:db8::1", 1000, 8))
    }

    @Test fun iperfGetsTheFamilyFlagForAnIpv6Server() {
        assertEquals(listOf("iperf3", "-c", "192.0.2.10", "-p", "5201", "-t", "10", "-P", "1", "--forceflush"), iperfCommand("iperf3", false, "192.0.2.10", 5201, 10, 1, false, false))
        assertEquals(listOf("iperf3", "-c", "2001:db8::1", "-p", "5201", "-t", "10", "-P", "1", "--forceflush", "-6"), iperfCommand("iperf3", false, "2001:db8::1", 5201, 10, 1, false, false))
        assertEquals(listOf("iperf3", "-c", "fe80::1%eth0", "-p", "5201", "-t", "5", "-P", "2", "--forceflush", "-6", "-R", "-u"), iperfCommand("iperf3", false, "fe80::1%eth0", 5201, 5, 2, true, true))
        assertEquals(listOf("iperf3", "-s", "-1", "-p", "5201", "--forceflush"), iperfCommand("iperf3", true, "", 5201, 10, 1, false, false), "server mode listens on both families by itself")
    }

    // ---------------------------------------------------------------- a host is never an option

    @Test fun noHostCanBecomeAnOptionOfTheProgramThatIsRun() {
        val hostile = listOf("-n", "--help", "-c", "-oProxyCommand=x", "-V", "-1", "-s", "-f")
        for (host in hostile) {
            assertTrue(hostRefusal(host) != null, "validation refuses $host")
            // The argument builders refuse it as well, whatever called them.
            assertFailsWith<IllegalArgumentException>("ping $host") { pingCommand(linux, "/bin/ping", host, 4, 1000) }
            assertFailsWith<IllegalArgumentException>("tracert $host") { traceCommand(windows, { "tracert.exe" }, host, 500, 5) }
            assertFailsWith<IllegalArgumentException>("traceroute $host") { traceCommand(linux, { "/usr/bin/traceroute" }, host, 500, 5) }
            assertFailsWith<IllegalArgumentException>("iperf3 $host") { iperfCommand("iperf3", false, host, 5201, 10, 1, false, false) }
        }
    }

    @Test fun thePluginsStartNoProcessForAHostThatLooksLikeAnOption() = runBlocking {
        val before = RunningTools.lastCommand
        val ping = PingPlugin().run(mapOf("host" to "-n", "count" to "1", "timeout" to "1000")) {}
        val trace = TraceroutePlugin().run(mapOf("host" to "--help", "timeout" to "1000", "hops" to "3")) {}
        val iperf = Iperf3Plugin().run(mapOf("server" to "false", "host" to "-c", "port" to "5201", "duration" to "1", "parallel" to "1", "reverse" to "false", "udp" to "false", "executable" to "")) {}
        for (result in listOf(ping, trace, iperf)) assertFalse(result.ok, result.summary)
        assertTrue(ping.summary.contains("hyphen"), ping.summary)
        assertTrue(iperf.summary.contains("hyphen"), iperf.summary)
        assertEquals(before, RunningTools.lastCommand, "nothing was started")
    }

    @Test fun anIpv6TargetIsPassedThroughTheFieldValidationUnchanged() {
        val field = ToolInput("host", "Host", ToolInputKind.Host)
        for (host in listOf("2001:db8::1", "fe80::1%12", "::ffff:192.0.2.10")) assertEquals(host, validateToolValue(field, "  $host  "))
    }
}
