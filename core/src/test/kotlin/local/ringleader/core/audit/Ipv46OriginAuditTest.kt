package local.ringleader.core.audit

import java.net.Inet6Address
import java.net.InetAddress
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import local.ringleader.core.Connection
import local.ringleader.core.DIRECT_PVE_KIND
import local.ringleader.core.PDM_KIND
import local.ringleader.core.ProfileDraft
import local.ringleader.core.PveWebUi
import local.ringleader.core.certificateOrigin
import local.ringleader.core.completeServerAddress
import local.ringleader.core.profileFromDraft
import local.ringleader.core.routeTarget
import local.ringleader.core.serverOrigin
import local.ringleader.core.sessionIdentity

/**
 * IPv4/IPv6 audit, evidence 1: how a typed server address becomes an origin, a trust key and a session identity.
 *
 * Tests named `defect...` assert the CORRECT behaviour and FAIL today; the failure is the evidence.
 * Tests named `ok...` pin behaviour that was checked and is right; they pass.
 * Documentation addresses only (192.0.2.0/24, 2001:db8::/32, example.test).
 * Run: `./gradlew.bat :core:test --tests "local.ringleader.core.audit.Ipv46OriginAuditTest" --console=plain`
 */
class Ipv46OriginAuditTest {
    private fun identity(address: String, user: String = "root@pam!audit"): String =
        Connection(address, user, "x", "").use { sessionIdentity(DIRECT_PVE_KIND, it.address, user) }

    // ---------------------------------------------------------------- one server, one identity

    /** Host names are case-insensitive. `Connection.remoteKey`, `Guest.remote`, watches, schedules and pins are keyed by this string. */
    @Ignore("audit finding #3: addressing part 2")
    @Test fun defectHostCaseMakesASecondOrigin() {
        assertEquals(serverOrigin("https://pve.example.test:8006").toString(), serverOrigin("https://PVE.EXAMPLE.TEST:8006").toString())
    }

    /** `https://host` and `https://host:443` are the same endpoint, and the connection itself treats them so (routeTarget says 443 for both). */
    @Ignore("audit finding #3: addressing part 2")
    @Test fun defectExplicitDefaultPortMakesASecondOrigin() {
        assertEquals(serverOrigin("https://pve.example.test").toString(), serverOrigin("https://pve.example.test:443").toString())
    }

    /**
     * The certificate store already folds case and `:443` (certificateOrigin), the session list does not (sessionIdentity).
     * So one server is one trust entry but two open sessions, two inventories, two pollers.
     */
    @Ignore("audit finding #3: addressing part 2")
    @Test fun defectTrustKeyAndSessionIdentityDisagreeAboutTheSameServer() {
        val upper = "https://PVE.EXAMPLE.TEST:8006"
        val lower = "https://pve.example.test:8006"
        assertEquals(certificateOrigin(upper), certificateOrigin(lower), "the trust store treats these as one server")
        assertEquals(identity(upper), identity(lower), "the session list must agree with the trust store")
    }

    @Ignore("audit finding #3: addressing part 2")
    @Test fun defectExplicitPort443SplitsTrustFromSession() {
        val bare = "https://pve.example.test"
        val explicit = "https://pve.example.test:443"
        assertEquals(certificateOrigin(bare), certificateOrigin(explicit))
        assertEquals(identity(bare), identity(explicit))
    }

    @Ignore("audit finding #3: addressing part 2")
    @Test fun defectTrailingDotIsAnotherServerForCertificateTrust() {
        assertEquals(certificateOrigin("https://pve.example.test:8006"), certificateOrigin("https://pve.example.test.:8006"))
    }

    @Ignore("audit finding #3: addressing part 2")
    @Test fun defectIpv4LeadingZerosAreAnotherServerForCertificateTrust() {
        // The JDK reads 192.0.002.010 as 192.0.2.10 (checked in ok below), so the connection goes to the same host.
        assertEquals(certificateOrigin("https://192.0.2.10:8006"), certificateOrigin("https://192.0.002.010:8006"))
    }

    @Ignore("audit finding #3: addressing part 2")
    @Test fun defectIpv6TextFormsAreDifferentServersForCertificateTrust() {
        val short = certificateOrigin("https://[2001:db8::10]:8006")
        assertEquals(short, certificateOrigin("https://[2001:db8:0:0:0:0:0:10]:8006"))
        assertEquals(short, certificateOrigin("https://[2001:DB8::0010]:8006"))
    }

    @Test fun okLeadingZerosReachTheSameIpv4HostAsTheCanonicalSpelling() {
        assertEquals(InetAddress.getByName("192.0.2.10"), InetAddress.getByName(routeTarget("https://192.0.002.010:8006")!!.host))
    }

    /**
     * The JDK reads 192.0.002.010 as decimal, 192.0.2.10 (the test above). Windows ping.exe and .NET IPAddress.Parse read it as
     * octal, 192.0.2.8 (checked on this computer), and so do URL parsers that follow the WHATWG rule browsers use. The app
     * connects to one host and hands the browser a link to another, where the browser then asks for a login.
     */
    @Ignore("audit finding #11: addressing part 2")
    @Test fun defectLeadingZeroAddressSendsTheBrowserToAnotherHost() {
        val link = PveWebUi.datacenter("https://192.0.002.010:8006")!!
        assertTrue("002" !in link && "010" !in link, "the web UI link keeps the zero-padded text: $link")
    }

    /** An IPv4-mapped literal connects over IPv4 (the JDK turns it into an Inet4Address) but is a different origin from the plain IPv4 text. */
    @Test fun mappedIpv4IsTheSameEndpointButADifferentOrigin() {
        val mapped = routeTarget("https://[::ffff:192.0.2.10]:8006")!!
        assertEquals("192.0.2.10", InetAddress.getByName(mapped.host).hostAddress)
        assertNotEquals(certificateOrigin("https://192.0.2.10:8006"), certificateOrigin("https://[::ffff:192.0.2.10]:8006"),
            "characterization: today these are two trust entries and two sessions for one TCP endpoint")
    }

    // ---------------------------------------------------------------- inputs the app must accept or refuse

    /** RFC 3986 section 3.1: schemes are case-insensitive. The browser-style `HTTPS://` is refused with a message that says to use HTTPS. */
    @Ignore("audit finding #6: addressing part 2")
    @Test fun defectUppercaseSchemeIsRefused() {
        assertNotNull(serverOrigin("HTTPS://pve.example.test:8006"))
    }

    /** `java.net.URI` gives a null host for an underscore name, so `serverOrigin` calls a name that resolves fine "not an HTTPS address". */
    @Ignore("audit finding #6: addressing part 2")
    @Test fun defectUnderscoreHostNameIsRefused() {
        assertNotNull(serverOrigin("https://pve_1.example.test:8006"))
    }

    /** An internationalised name is refused with "Use an HTTPS server address, including its port." although it has both. Punycode (IDN.toASCII) would work. */
    @Ignore("audit finding #6: addressing part 2")
    @Test fun defectInternationalNameIsRefusedWithAMisleadingMessage() {
        val origin = serverOrigin("https://b\u00fccher.example.test:8006")
        assertEquals("xn--bcher-kva.example.test", origin.host)
    }

    @Ignore("audit finding #4: addressing part 2")
    @Test fun defectEmptyPortAfterColonSilentlyMeansPort443() {
        // "192.0.2.10:" and "[2001:db8::10]:" are typed by people who stop before the number. The product port is 8006, the origin says 443.
        assertEquals(8006, serverOrigin(completeServerAddress("192.0.2.10:")).port)
        assertEquals(8006, serverOrigin(completeServerAddress("[2001:db8::10]:")).port)
    }

    /** The profile editor has no completion step; the Connect form does. The same text works in one place and not in the other. */
    @Ignore("audit finding #10: addressing part 2")
    @Test fun defectProfileEditorRefusesWhatTheConnectFormAccepts() {
        for (typed in listOf("192.0.2.10", "pve.example.test:8006", "[2001:db8::10]", "2001:db8::10")) {
            serverOrigin(completeServerAddress(typed))
            profileFromDraft(ProfileDraft(null, "lab", typed, "root@pam!audit", ""))
        }
    }

    /** A pasted RFC 6874 zone (`%25` for `%`) is kept verbatim in the host; the JDK then reads `25` as part of the scope. */
    @Ignore("audit finding #7: addressing part 2")
    @Test fun defectEncodedZoneIdIsNotDecodedBeforeResolution() {
        val host = routeTarget("https://[fe80::1%2512]:8006")!!.host
        val address = InetAddress.getByName(host) as Inet6Address
        assertEquals(12, address.scopeId, "RFC 6874 '%2512' is zone 12; the JDK read it as zone ${address.scopeId}")
    }

    @Test fun okZoneIdInOriginKeepsTheInterfaceFor_getByName() {
        assertEquals(12, (InetAddress.getByName(routeTarget("https://[fe80::1%12]:8006")!!.host) as Inet6Address).scopeId)
    }

    /** A zone id names an interface of THIS computer. It is stored in profiles.json and certificates.json as part of the origin. */
    @Ignore("audit finding #7: addressing part 2")
    @Test fun defectLinkLocalZoneIsPersistedAsPartOfAnOrigin() {
        val profile = profileFromDraft(ProfileDraft(null, "cabled", "https://[fe80::1%12]:8006", "root@pam!audit", ""))
        assertTrue('%' !in profile.origin, "interface index 12 is meaningless after a reboot or on another computer: ${profile.origin}")
    }

    // ---------------------------------------------------------------- checked and correct

    @Test fun okRefusalsAreRefusals() {
        for (bad in listOf("http://pve.example.test:8006", "https://root@pve.example.test:8006", "https://pve.example.test:8006/some/path",
            "https://pve.example.test:8006?x=1", "https://pve.example.test:8006#f", "https://pve.example.test:0", "https://pve.example.test:65536",
            "https://pve.example.test:abc", "https://pve.example.test:99999999999", "https://192.0.2.256:8006", "https://192.0.2:8006",
            "https://-pve.example.test:8006", "https://pve..example.test:8006", "https://2001:db8::10:8006", "ssh://pve.example.test", "", "   ", "https://")) {
            assertFailsWith<RuntimeException>(bad) { serverOrigin(bad) }
        }
    }

    @Test fun okAcceptedSpellingsCanonicalize() {
        assertEquals("https://pve.example.test:8006", serverOrigin("  https://pve.example.test:8006/  ").toString())
        assertEquals("https://pve.example.test:8006", serverOrigin("https://pve.example.test:08006").toString())
        assertEquals("https://[2001:db8::10]:8006", serverOrigin("https://[2001:db8::10]:8006").toString())
        assertEquals("https://pve.example.ts.net:8006", serverOrigin("https://pve.example.ts.net:8006").toString())
        assertEquals("https://100.64.0.7:8006", serverOrigin("https://100.64.0.7:8006").toString())
    }

    @Test fun okCompletionHandlesBareHostsAndIpv6() {
        assertEquals("https://[2001:db8::10]:8006", completeServerAddress("2001:db8::10"))
        assertEquals("https://[2001:db8::10]:8006", completeServerAddress("[2001:db8::10]"))
        assertEquals("https://[::1]:8006", completeServerAddress("::1"))
        assertEquals("https://[::ffff:192.0.2.10]:8006", completeServerAddress("[::ffff:192.0.2.10]:8006"))
        assertEquals("https://pve.example.test:8443", completeServerAddress("pve.example.test", PDM_KIND))
        assertEquals("https://PVE.EXAMPLE.TEST:8006", completeServerAddress("PVE.EXAMPLE.TEST:8006"), "case is kept, see defectHostCaseMakesASecondOrigin")
    }

    @Test fun okRouteTargetStripsBracketsForSocketsAndAddsThemForDisplay() {
        val target = routeTarget("https://[2001:db8::10]:8006")!!
        assertEquals("2001:db8::10", target.host)
        assertEquals("[2001:db8::10]:8006", target.hostPort)
        assertNull(routeTarget("http://[2001:db8::10]:8006"))
    }

    @Test fun okWebSocketAndRestUrlsKeepTheBracketsOfAnIpv6Origin() {
        val origin = serverOrigin("https://[2001:db8::10]:8006")
        assertEquals("[2001:db8::10]:8006", origin.rawAuthority)
        // The expression VncSession, TerminalSession and ConsoleStill use to build the console URL.
        val socket = java.net.URI("wss://${origin.rawAuthority}/api2/json/nodes/n1/qemu/100/vncwebsocket?port=5900&vncticket=t")
        assertEquals("[2001:db8::10]", socket.host)
        assertEquals(8006, socket.port)
        assertEquals("https://[2001:db8::10]:8006/api2/json/version", origin.resolve("/api2/json/version").toString())
    }

    @Test fun okMatrixDocumentedInTheReport() {
        // Guards the claims in the report's per-input table: the long Ipv46MatrixAuditTest prints the whole table.
        assertEquals("https://192.0.2.10:8006", serverOrigin(completeServerAddress("192.0.2.10")).toString())
        assertEquals("https://192.0.2.10", serverOrigin("https://192.0.2.10").toString())
    }
}
