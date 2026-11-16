package local.ringleader.core.audit

import java.net.Inet6Address
import java.net.InetAddress
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import local.ringleader.core.CreateContext
import local.ringleader.core.CreateFields
import local.ringleader.core.CtNic
import local.ringleader.core.Ip6Mode
import local.ringleader.core.IpMode
import local.ringleader.core.Redact
import local.ringleader.core.ScanHit
import local.ringleader.core.ScanInterface
import local.ringleader.core.ScanKind
import local.ringleader.core.ScanRules
import local.ringleader.core.StorageChoice
import local.ringleader.core.defaultCtDraft
import local.ringleader.core.encodeCtNic
import local.ringleader.core.preferredHosts
import local.ringleader.core.serverOrigin
import local.ringleader.core.spiceProxyHost
import local.ringleader.core.spiceRequestBody
import local.ringleader.core.validateCt
import local.ringleader.core.downloadUrlError
import local.ringleader.core.tools.isHostToken
import local.ringleader.core.tools.isIpv4
import local.ringleader.core.tools.isIpv6
import local.ringleader.core.tools.validateHttpUrl

/**
 * IPv4/IPv6 audit, evidence 4: the small parsers. Tool fields, redaction, scan hits, guest-create network fields, SPICE.
 * `defect...` tests assert the correct behaviour and fail today; `ok...` tests pin checked, correct behaviour.
 * Documentation addresses only.
 * Run: `./gradlew.bat :core:test --tests "local.ringleader.core.audit.Ipv46ParsersAuditTest" --console=plain`
 */
class Ipv46ParsersAuditTest {
    // ---------------------------------------------------------------- Tools screen

    @Test fun okHttpTlsToolAcceptsIpv4AndNames() {
        for (url in listOf("https://192.0.2.10:8006/", "http://pve.example.test/", "https://100.64.0.7:8006/")) assertNotNull(validateHttpUrl(url), url)
    }

    @Test fun okToolHostFieldsAcceptCommonLiterals() {
        for (host in listOf("192.0.2.10", "2001:db8::1", "::1", "2001:db8::10", "1:2:3:4:5:6:7:8", "pve.example.test", "pve.example.ts.net")) assertTrue(isHostToken(host), host)
        for (host in listOf("192.0.2.256", "192.0.2", "01.2.3.4", "2001:db8:::1", "1:2:3:4:5:6:7:8:9", "pve;rm", "a b")) assertFalse(isHostToken(host), host)
    }

    // ---------------------------------------------------------------- Redact

    @Test fun okRedactionKeepsIpv6AndOrdinaryHosts() {
        assertEquals("connect to [2001:db8::10]:8006 failed", Redact.text("connect to [2001:db8::10]:8006 failed"))
        assertEquals("connect to pve.example.test:8006 failed", Redact.text("connect to pve.example.test:8006 failed"))
        assertEquals("https://[2001:db8::10]:8006/api2/json/x?ticket=<redacted>", Redact.url("https://[2001:db8::10]:8006/api2/json/x?ticket=abc"))
        assertEquals("https://[2001:db8::10]:8006/api2/json/x", Redact.url("https://root:pw@[2001:db8::10]:8006/api2/json/x"))
    }

    // ---------------------------------------------------------------- scan hits (IPv4 only today; the type is a String)

    /** Nothing puts an IPv6 address into a [ScanHit] today (the sweep and the tailnet search are IPv4 only), but the field is a plain String. */
    @Ignore("audit finding #15: addressing part 2")
    @Test fun defectScanHitOriginIsNotAnOriginWhenTheAddressIsIpv6() {
        serverOrigin(ScanHit("2001:db8::5", 8006, true, ScanKind.PVE).origin)
    }

    @Ignore("audit finding #15: addressing part 2")
    @Test fun defectScanSavedMatchMissesAnIpv6Origin() {
        assertTrue(ScanRules.saved(ScanHit("2001:db8::5", 8006, true, ScanKind.PVE), listOf("https://[2001:db8::5]:8006")))
    }

    /**
     * The sweep reads only Inet4Address entries, so an interface that has IPv6 but no IPv4 (an IPv6-only LAN) does not appear in
     * the plan at all, and the scan page cannot say why it found nothing. Every non-loopback IPv6 address of an up interface
     * on this computer should at least be listed as "not swept".
     */
    @Ignore("audit finding #17: addressing part 2")
    @Test fun defectIpv6OnlyInterfacesAreMissingFromTheSweepPlan() {
        val v6 = java.net.NetworkInterface.networkInterfaces().toList().filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { nic -> nic.inetAddresses().toList().filterIsInstance<Inet6Address>().map { nic.name to it.hostAddress.substringBefore('%') } }
        org.junit.jupiter.api.Assumptions.assumeTrue(v6.isNotEmpty(), "this computer has no IPv6 address on an up, non-loopback interface")
        val listed = local.ringleader.core.NetworkScanner.interfaces().networks.map { it.source.ip }
        println("SCAN|IPv6 addresses on this computer: ${v6.size}; sweep plan lists ${listed.size} networks, none of them IPv6")
        assertTrue(v6.any { (_, ip) -> ip in listed }, "no IPv6 interface is named in the plan, so an IPv6-only LAN gets 'No services found' with no reason")
    }

    /** The reason text if an IPv6 interface ever reaches [ScanRules.plan]. It reads "Invalid IPv4". */
    @Ignore("audit finding #17: addressing part 2")
    @Test fun defectIpv6InterfaceReasonSaysInvalidIpv4() {
        val network = ScanRules.plan(listOf(ScanInterface("eth0", "2001:db8::5", 64))).networks.single()
        assertNull(network.cidr)
        assertTrue("IPv6" in network.reason.orEmpty(), "the reason says '${network.reason}'")
    }

    // ---------------------------------------------------------------- digits

    @Test fun okIpv4ParserAcceptsOrdinaryAddresses() {
        assertEquals(0xC0000201L + 9, ScanRules.ipv4("192.0.2.10"))
        assertNull(ScanRules.ipv4("192.0.2"))
        assertNull(ScanRules.ipv4("192.0.2.256"))
        assertNull(ScanRules.ipv4(" 192.0.2.10"))
    }

    @Test fun okPreferredHostsPutsIpv6LastAndKeepsNames() {
        assertEquals(listOf("192.168.1.5", "10.200.0.5", "100.64.0.7", "8.8.8.8", "pve.example.test", "2001:db8::10"),
            preferredHosts(listOf("2001:db8::10", "pve.example.test", "8.8.8.8", "100.64.0.7", "10.200.0.5", "192.168.1.5")))
    }

    // ---------------------------------------------------------------- create-guest network fields

    private val ctx = CreateContext(storages = listOf(StorageChoice("node-a", "local", "dir", setOf("images", "rootdir", "iso", "vztmpl"), false, 0, 100, "available")))
    private fun ct(nic: CtNic, nameservers: String = "") = defaultCtDraft("node-a", ctx).copy(vmid = "105", hostname = "web-1", template = "local:vztmpl/a.tar.zst", nic = nic, nameservers = nameservers)

    /**
     * UNVERIFIED, so this test pins what happens and does not call it a defect. The address check strips `%...` before it
     * validates, and the original text is what is sent. pve-container allows an interface suffix on a link-local DNS address
     * (pve-devel patch "fix #3140", 2020), so for `nameserver` that is deliberate. Whether `gw6` (format `ipv6`) takes a
     * suffix could not be checked without a node; if it does not, the form lets a value through that the node refuses.
     */
    @Test fun unverifiedZoneIdIsAcceptedInGatewayAndNameserverAndSentAsTyped() {
        val nic = CtNic(bridge = "vmbr0", ipv6 = Ip6Mode.Static("2001:db8::10/64", "fe80::1%eth0"))
        assertTrue(validateCt(ct(nic), ctx).isEmpty())
        assertEquals("name=eth0,bridge=vmbr0,firewall=1,ip6=2001:db8::10/64,gw6=fe80::1%eth0", encodeCtNic(nic))
        assertTrue(validateCt(ct(CtNic(bridge = "vmbr0"), nameservers = "fe80::53%eth0"), ctx).isEmpty())
    }

    @Test fun okCreateAcceptsPlainStaticDhcpAndSlaacConfigurations() {
        val good = listOf(
            CtNic(bridge = "vmbr0", ipv4 = IpMode.Static("192.0.2.10/24", "192.0.2.1"), ipv6 = Ip6Mode.Static("2001:db8::10/64", "2001:db8::1")),
            CtNic(bridge = "vmbr0", ipv4 = IpMode.Dhcp, ipv6 = Ip6Mode.Slaac),
            CtNic(bridge = "vmbr0", ipv4 = IpMode.Static("192.0.2.10/24", "192.0.2.1"), ipv6 = Ip6Mode.Static("2001:db8::10/64", "fe80::1")),
            CtNic(bridge = "vmbr0", ipv4 = IpMode.Dhcp, ipv6 = Ip6Mode.Dhcp),
        )
        good.forEach { assertTrue(validateCt(ct(it), ctx).isEmpty(), "${encodeCtNic(it)} -> ${validateCt(ct(it), ctx)}") }
        assertEquals("name=eth0,bridge=vmbr0,firewall=1,ip=192.0.2.10/24,gw=192.0.2.1,ip6=2001:db8::10/64,gw6=2001:db8::1", encodeCtNic(good[0]))
        val bad = listOf(
            CtNic(bridge = "vmbr0", ipv4 = IpMode.Static("192.0.2.10", "")), CtNic(bridge = "vmbr0", ipv4 = IpMode.Static("192.0.2.10/33", "")),
            CtNic(bridge = "vmbr0", ipv4 = IpMode.Static("192.0.2.256/24", "")), CtNic(bridge = "vmbr0", ipv4 = IpMode.Static("192.0.2.10/24", "2001:db8::1")),
            CtNic(bridge = "vmbr0", ipv6 = Ip6Mode.Static("2001:db8::10", "")), CtNic(bridge = "vmbr0", ipv6 = Ip6Mode.Static("2001:db8::10/129", "")),
            CtNic(bridge = "vmbr0", ipv6 = Ip6Mode.Static("2001:db8::10/64", "192.0.2.1")), CtNic(bridge = "vmbr0", ipv6 = Ip6Mode.Static("2001:db8:::10/64", "")),
        )
        bad.forEach { assertFalse(validateCt(ct(it), ctx).isEmpty(), encodeCtNic(it)) }
        assertTrue(validateCt(ct(CtNic(bridge = "vmbr0"), nameservers = "192.0.2.53 2001:db8::53"), ctx).isEmpty())
    }

    // ---------------------------------------------------------------- SPICE, storage download URL

    /**
     * What the app tells PVE's `spiceproxy` for an IPv6 server: the bare literal, no brackets. Upstream moved that
     * parameter from `dns-name` (no ':' possible) to the `address` format in 2015 (pve-devel "spice-proxy format now uses
     * new address format"), which takes IPv6. So this is believed correct; it has not been run against a node reachable
     * over IPv6.
     */
    @Test fun okSpiceProxyParameterForAnIpv6ServerIsABareLiteral() {
        assertEquals("2001:db8::10", spiceProxyHost("https://[2001:db8::10]:8006"))
        assertEquals("""{"proxy":"2001:db8::10"}""", spiceRequestBody("https://[2001:db8::10]:8006").decodeToString())
        assertEquals("""{"proxy":"pve.example.test"}""", spiceRequestBody("https://pve.example.test:8006").decodeToString())
        assertEquals("""{"proxy":"192.0.2.10"}""", spiceRequestBody("https://192.0.2.10:8006").decodeToString())
    }

    /**
     * `java.net.URI` gives a null host for an international or underscore name, so the field answers "Use an HTTP or HTTPS
     * address without a login or fragment." for a URL that has neither. Whether the node can fetch such a URL was not checked;
     * the message is wrong either way.
     */
    @Ignore("audit finding #6: addressing part 2")
    @Test fun defectDownloadUrlRefusalBlamesALoginOrFragmentThatIsNotThere() {
        for (url in listOf("https://b\u00fccher.example.test/a.iso", "https://iso_mirror.example.test/a.iso")) {
            val message = downloadUrlError(url)
            assertTrue(message == null || "login" !in message, "$url -> $message")
        }
    }

    @Test fun okDownloadUrlFieldAcceptsIpv6AndIpv4() {
        assertNull(downloadUrlError("http://[2001:db8::1]:8080/a.iso"))
        assertNull(downloadUrlError("https://192.0.2.10/a.iso"))
        assertNotNull(downloadUrlError("https://user:pw@192.0.2.10/a.iso"))
    }

    @Test fun okIsIpv4StrictAboutLeadingZeros() {
        assertFalse(isIpv4("192.0.02.10"))
        assertTrue(isIpv4("192.0.2.10"))
    }
}
