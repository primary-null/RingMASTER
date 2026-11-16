package local.ringleader.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Audit finding 8: the ticket pattern was case-insensitive and unanchored, so `pve:` and `pdm:` (the products' own default host names)
 * lost their name in diagnostic text. The first test is the audit's `defect...` test, moved here with its demand unchanged.
 * Nothing in this checkout masks an IPv4 address; the parity tests pin that an IPv6 literal is treated exactly like an IPv4 one wherever
 * addresses pass through [Redact].
 */
class RedactAddressTest {
    @Test fun aHostNamedPveOrPdmKeepsItsNameInADiagnosticLine() {
        assertEquals("connect to pve:8006 failed", Redact.text("connect to pve:8006 failed"))
        assertEquals("connect to pdm:8443 failed", Redact.text("connect to pdm:8443 failed"))
    }

    @Test fun everySpellingOfThoseHostNamesIsKeptAsWell() {
        for (line in listOf("connect to PVE:8006 failed", "connect to Pdm:8443 failed", "pve:8006,pdm:8443", "host=pve:8006", "(pve:8006)", "[pve]:8006 pve:8006/api2/json/version", "pve:8006 -> pdm:8443")) {
            assertEquals(line, Redact.text(line), line)
        }
        assertEquals("https://pve:8006/api2/json/version", Redact.url("https://pve:8006/api2/json/version"))
        assertEquals("https://pdm:8443/api2/json/ping", Redact.url("https://pdm:8443/api2/json/ping"))
        assertEquals("pve1.example:8006", Redact.text("pve1.example:8006"))
    }

    @Test fun ticketsAreStillMasked() {
        val tickets = listOf(
            "PVE:root@pam:5F3A1B2C::c2lnbmF0dXJl", "pve:root@pam:5F3A1B2C::c2lnbmF0dXJl", "PDM:admin@pdm:5F3A1B2C::c2lnbmF0dXJl", "PVE:svc!x@pve:5F3A1B2C::c2lnbmF0dXJl", "PVE:demo@pam:synthetic-token",
            "PVEVNC:5F3A1B2C::c2lnbmF0dXJl", "PVEVNC:demo@pve:synthetic-value", "PVESPICEPROXY:demo@pve:synthetic-value", "PVNC:demo@pve:synthetic-value", "PTERM:demo@pve:synthetic-value",
        )
        for (ticket in tickets) {
            assertEquals("login failed for ${Redact.MASK}", Redact.text("login failed for $ticket"), ticket)
            assertEquals("Cookie: ${Redact.MASK}", Redact.text("Cookie: PVEAuthCookie=$ticket"), "inside a cookie: $ticket")
            assertFalse("synthetic" in Redact.text("GET /x?port=$ticket"), ticket)
        }
        assertEquals("a ${Redact.MASK} b", Redact.text("a PVE:root@pam:5F3A1B2C::sig b"))
        assertEquals("connect to pve:8006 failed, then ${Redact.MASK}", Redact.text("connect to pve:8006 failed, then PVE:root@pam:5F3A1B2C::sig"), "a host and a ticket in one line")
        assertEquals("xPVE:8006", Redact.text("xPVE:8006"), "not inside a longer word")
    }

    // ---------------------------------------------------------------- IPv6 literals are treated like IPv4 ones

    private val hosts = listOf("192.0.2.10", "[2001:db8::10]", "[::1]", "[::ffff:192.0.2.10]", "[2001:db8:0:0:0:0:0:10]", "pve.example.test")

    @Test fun anAddressIsKeptWhateverItsFamilyInTextAndUrls() {
        for (host in hosts) {
            assertEquals("connect to $host:8006 failed", Redact.text("connect to $host:8006 failed"), host)
            assertEquals("https://$host:8006/api2/json/x?ticket=${Redact.MASK}", Redact.url("https://$host:8006/api2/json/x?ticket=abc"), host)
            assertEquals("https://$host:8006/api2/json/x", Redact.url("https://root:pw@$host:8006/api2/json/x"), "user-info goes, the host stays: $host")
            assertEquals("https://$host:8006/a?port=5900&vncticket=${Redact.MASK}", Redact.url("https://$host:8006/a?port=5900&vncticket=PVEVNC:5F3A1B2C::sig"), host)
        }
    }

    @Test fun anAddressIsKeptWhateverItsFamilyInDetailsAndCommands() {
        for (host in hosts) {
            val bare = host.removePrefix("[").removeSuffix("]")
            assertEquals("https://$host:8006/x", Redact.details(mapOf("url" to "https://$host:8006/x"))["url"], host)
            assertTrue(bare in Redact.commandDetails(listOf("ping", "-c", "4", "--", bare)).getValue("arguments"), "ping $bare")
            assertTrue(bare in Redact.commandDetails(listOf("iperf3", "-c", bare, "-p", "5201")).getValue("arguments"), "iperf3 $bare")
            assertEquals("--- $bare ---", Redact.text("--- $bare ---"), bare)
        }
    }
}
