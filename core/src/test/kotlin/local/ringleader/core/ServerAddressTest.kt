package local.ringleader.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ServerAddress.parse], from the IPv4/IPv6 audit's per-input matrix (section 4.1: the 34 typed inputs) and pair table (section 4.2).
 * Nothing adopts the type yet, so these tests are its whole specification.
 */
class ServerAddressTest {
    private val bucher = "b${0xfc.toChar()}cher.example.test"
    private val pve = 8006

    private class Row(val typed: String, val canonical: String? = null, val problem: ServerAddressProblem? = null, val zone: String? = null, val family: AddressFamily? = null)

    /** Section 4.1, in the report's order. Columns: typed text, what it must become (or why it is refused). Run as on Linux; Windows differs only in the zone rows below. */
    private val matrix = listOf(
        Row("192.0.2.10", "https://192.0.2.10:8006", family = AddressFamily.Ipv4),
        Row("192.0.2.10:8006", "https://192.0.2.10:8006"),
        Row("https://192.0.2.10", "https://192.0.2.10"),
        Row("pve.example.test", "https://pve.example.test:8006", family = AddressFamily.Name),
        Row("PVE.EXAMPLE.TEST:8006", "https://pve.example.test:8006"),
        Row("https://pve.example.test:8006/", "https://pve.example.test:8006"),
        Row("https://pve.example.test:8006/some/path", problem = ServerAddressProblem.UserInfoOrPath),
        Row("[2001:db8::10]", "https://[2001:db8::10]:8006", family = AddressFamily.Ipv6),
        Row("2001:db8::10", "https://[2001:db8::10]:8006"),
        Row("[2001:db8::10]:8006", "https://[2001:db8::10]:8006"),
        Row("https://[2001:db8::10]:8006", "https://[2001:db8::10]:8006"),
        Row("fe80::1%eth0", "https://[fe80::1]:8006", zone = "eth0"),
        Row("fe80::1%12", "https://[fe80::1]:8006", zone = "12"),
        Row("[fe80::1%25eth0]:8006", "https://[fe80::1]:8006", zone = "eth0"),
        Row("::1", "https://[::1]:8006"),
        Row("[::ffff:192.0.2.10]:8006", "https://[::ffff:192.0.2.10]:8006"),
        Row(bucher, "https://xn--bcher-kva.example.test:8006"),
        Row("pve.example.test.", "https://pve.example.test:8006"),
        Row("HTTPS://pve.example.test:8006", "https://pve.example.test:8006"),
        Row("http://pve.example.test:8006", problem = ServerAddressProblem.NotHttps),
        Row("https://root@pve.example.test:8006", problem = ServerAddressProblem.UserInfoOrPath),
        Row("pve.example.test:0", problem = ServerAddressProblem.Port),
        Row("pve.example.test:65536", problem = ServerAddressProblem.Port),
        Row("pve.example.test:abc", problem = ServerAddressProblem.Port),
        Row("  pve.example.test:8006  ", "https://pve.example.test:8006"),
        Row("pve.example.ts.net", "https://pve.example.ts.net:8006"),
        Row("100.64.0.7", "https://100.64.0.7:8006"),
        Row("pve_1.example.test", "https://pve_1.example.test:8006"),
        Row("192.0.2.10:", "https://192.0.2.10:8006"),
        Row("https://pve.example.test:443", "https://pve.example.test"),
        Row("https://pve.example.test", "https://pve.example.test"),
        Row("192.0.002.010", problem = ServerAddressProblem.Ipv4LeadingZero),
        Row("https://[2001:DB8::10]:8006", "https://[2001:db8::10]:8006"),
        Row("https://[2001:db8:0:0:0:0:0:10]:8006", "https://[2001:db8::10]:8006"),
    )

    @Test fun theMatrixHasTheReportsThirtyFourInputs() {
        assertEquals(34, matrix.size)
        assertEquals(34, matrix.map { it.typed }.toSet().size)
    }

    @Test fun everyInputOfTheMatrixBecomesWhatTheReportSaysItShould() {
        for (row in matrix) {
            if (row.problem != null) {
                val e = assertFailsWith<ServerAddressException>("'${row.typed}' must be refused") { ServerAddress.parse(row.typed, pve, windows = false) }
                assertEquals(row.problem, e.problem, "'${row.typed}': ${e.message}")
                assertTrue(e.message!!.isNotBlank() && e.message!!.endsWith("."), "'${row.typed}' needs a sentence: ${e.message}")
                assertNull(ServerAddress.parseOrNull(row.typed, pve, windows = false))
            } else {
                val a = ServerAddress.parse(row.typed, pve, windows = false)
                assertEquals(row.canonical, a.canonical, "'${row.typed}'")
                assertEquals(row.zone, a.zone, "zone of '${row.typed}'")
                row.family?.let { assertEquals(it, a.family, "family of '${row.typed}'") }
                // What is stored is what is shown to nobody twice: parsing the canonical text gives the same address.
                assertEquals(a, ServerAddress.parse(a.canonical, pve, windows = false), "round trip of '${row.typed}'")
                assertEquals(a.canonical, ServerAddress.parse(a.canonical, 1, windows = false).canonical, "the canonical text carries its own port: '${row.typed}'")
            }
        }
    }

    // ---------------------------------------------------------------- section 4.2: the same server under two spellings

    @Test fun twoSpellingsOfOneServerAreOneAddress() {
        val pairs = listOf(
            "PVE.EXAMPLE.TEST:8006" to "pve.example.test:8006",
            "https://pve.example.test" to "https://pve.example.test:443",
            "pve.example.test" to "pve.example.test.",
            "[2001:DB8::10]" to "[2001:db8::10]",
            "[2001:db8:0:0:0:0:0:10]" to "[2001:db8::10]",
            "2001:db8::10" to "[2001:0DB8:0000::0010]:8006",
            "HTTPS://pve.example.test:8006" to "https://pve.example.test:8006/",
        )
        for ((a, b) in pairs) {
            val x = ServerAddress.parse(a, pve)
            val y = ServerAddress.parse(b, pve)
            assertEquals(x, y, "$a / $b")
            assertEquals(x.canonical, y.canonical, "$a / $b")
            assertEquals(x.hashCode(), y.hashCode())
        }
    }

    @Test fun zeroPaddedIpv4IsRefusedNotMergedWithItsDecimalReading() {
        // The JDK reads 192.0.002.010 as 192.0.2.10, Windows ping and the browsers as octal 192.0.2.8: so it is not an address at all.
        assertEquals("https://192.0.2.10:8006", ServerAddress.parse("192.0.2.10", pve).canonical)
        val e = assertFailsWith<ServerAddressException> { ServerAddress.parse("192.0.002.010", pve) }
        assertEquals(ServerAddressProblem.Ipv4LeadingZero, e.problem)
        assertTrue("octal" in e.message!!, "the sentence explains the ambiguity: ${e.message}")
        assertTrue("leading zero" in e.message!!)
        assertEquals(ServerAddressProblem.Ipv4LeadingZero, assertFailsWith<ServerAddressException> { ServerAddress.parse("https://192.0.2.010:8006", pve) }.problem)
        assertEquals(ServerAddressProblem.Ipv4LeadingZero, assertFailsWith<ServerAddressException> { ServerAddress.parse("010.1.1.1", pve) }.problem)
    }

    /** Decided and documented: an IPv4-mapped IPv6 literal is its own identity. The JDK connects over IPv4 for both, but a pin or a trust entry for one is not silently the other. */
    @Test fun anIpv4MappedIpv6AddressStaysSeparateFromTheIpv4Address() {
        val plain = ServerAddress.parse("192.0.2.10", pve)
        val mapped = ServerAddress.parse("[::ffff:192.0.2.10]", pve)
        assertNotEquals(plain, mapped)
        assertEquals("https://[::ffff:192.0.2.10]:8006", mapped.canonical)
        assertEquals(mapped, ServerAddress.parse("[::FFFF:C000:20A]", pve), "one canonical text for the mapped address, however it is typed")
        assertEquals(AddressFamily.Ipv6, mapped.family)
    }

    // ---------------------------------------------------------------- the port

    @Test fun theProductPortAppliesToABareHostAndFourFortyThreeToAnHttpsOneWithoutPort() {
        assertEquals(8006, ServerAddress.parse("pve.example.test", 8006).port)
        assertEquals(8443, ServerAddress.parse("pve.example.test", 8443).port, "Datacenter Manager")
        assertEquals(443, ServerAddress.parse("https://pve.example.test", 8443).port, "an explicit https:// without a port means 443, as the connection itself reads it")
        assertEquals(443, ServerAddress.parse("pve.example.test:443", 8006).port)
        assertEquals("https://pve.example.test", ServerAddress.parse("pve.example.test:443", 8006).canonical, ":443 and no port are the same server")
        assertEquals("https://pve.example.test:8443", ServerAddress.parse("https://pve.example.test:8443", 8006).canonical)
    }

    /** Audit finding 4: a colon with nothing after it is "no port", not port 443. */
    @Test fun aTrailingColonIsNoPort() {
        for (typed in listOf("192.0.2.10:", "pve.example.test:", "[2001:db8::10]:", "https://pve.example.test:", "https://[2001:db8::10]:")) {
            assertEquals(pve, ServerAddress.parse(typed, pve).port, typed)
        }
        assertEquals("https://pve.example.test:8443", ServerAddress.parse("pve.example.test:", 8443).canonical)
    }

    @Test fun portsAreWholeNumbersFromOneToSixtyFiveThousandFiveHundredThirtyFive() {
        for (bad in listOf("0", "65536", "99999", "100000", "-1", "+8006", "8006x", "99999999999", "\u0668\u0660\u0660\u0666")) {
            val e = assertFailsWith<ServerAddressException>(bad) { ServerAddress.parse("pve.example.test:$bad", pve) }
            assertEquals(ServerAddressProblem.Port, e.problem, bad)
            assertEquals("The server port must be a whole number from 1 to 65535.", e.message)
        }
        assertEquals(8006, ServerAddress.parse("pve.example.test:08006", pve).port, "leading zeros of a port are harmless")
        assertEquals(1, ServerAddress.parse("pve.example.test:1", pve).port)
        assertEquals(65535, ServerAddress.parse("[::1]:65535", pve).port)
    }

    // ---------------------------------------------------------------- the scheme and what may not be there

    @Test fun onlyHttpsInAnyLetterCase() {
        for (ok in listOf("https://pve.example.test", "HTTPS://pve.example.test", "Https://pve.example.test")) assertEquals(443, ServerAddress.parse(ok, pve).port, ok)
        for (bad in listOf("http://pve.example.test", "ssh://pve.example.test", "ftp://pve.example.test", "wss://pve.example.test", "HTTP://pve.example.test")) {
            val e = assertFailsWith<ServerAddressException>(bad) { ServerAddress.parse(bad, pve) }
            assertEquals(ServerAddressProblem.NotHttps, e.problem, bad)
            assertTrue("https" in e.message!!)
        }
    }

    @Test fun noLoginPathQueryOrFragment() {
        for (bad in listOf("https://root@pve.example.test:8006", "https://root:pw@pve.example.test", "root@pve.example.test", "https://pve.example.test:8006/some/path", "https://pve.example.test:8006?x=1",
            "https://pve.example.test:8006#f", "pve.example.test/x", "https://pve.example.test//", "https://[2001:db8::10]:8006/api2/json")) {
            val e = assertFailsWith<ServerAddressException>(bad) { ServerAddress.parse(bad, pve) }
            assertEquals(ServerAddressProblem.UserInfoOrPath, e.problem, bad)
            assertEquals("Enter only the server address, without a login, page path, or query.", e.message)
        }
        assertEquals("https://pve.example.test:8006", ServerAddress.parse("https://pve.example.test:8006/", pve).canonical, "one trailing slash is dropped")
    }

    @Test fun nothingAtAllIsRefusedAsEmpty() {
        for (bad in listOf("", "   ", "https://", "https:///", "HTTPS://  ")) assertEquals(ServerAddressProblem.Empty, assertFailsWith<ServerAddressException>("'$bad'") { ServerAddress.parse(bad, pve) }.problem, "'$bad'")
    }

    // ---------------------------------------------------------------- hosts

    @Test fun namesAreLowerCaseAsciiWithoutATrailingDot() {
        assertEquals("pve.example.test", ServerAddress.parse("PvE.ExAmPlE.TeSt.", pve).host)
        assertEquals("pve_1.example.test", ServerAddress.parse("PVE_1.Example.Test", pve).host, "an underscore is allowed")
        assertEquals("pve", ServerAddress.parse("PVE", pve).host, "a single label")
        assertEquals("xn--bcher-kva.example.test", ServerAddress.parse("B${0xdc.toChar()}CHER.example.test", pve).host, "an international name becomes ASCII, upper case folded")
        assertEquals("xn--bcher-kva.example.test", ServerAddress.parse(bucher, pve).host)
        assertEquals(AddressFamily.Name, ServerAddress.parse("pve.example.test", pve).family)
    }

    @Test fun badNamesAreRefusedWithAReason() {
        val cases = listOf(
            "-pve.example.test" to "hyphen", "pve-.example.test" to "hyphen", "pve..example.test" to "empty part", ".pve.example.test" to "empty part", "pve example" to "no spaces",
            "pve.example.test:8006:9" to "IPv6", "pve!.example.test" to "valid host name", "a".repeat(64) + ".test" to "63",
            "pve.1" to "number", "1234" to "number", "pve." + "a".repeat(250) to "253",
        )
        for ((typed, reason) in cases) {
            val e = assertFailsWith<ServerAddressException>("'$typed'") { ServerAddress.parse(typed, pve) }
            assertTrue(reason.lowercase() in e.message!!.lowercase(), "'$typed' was refused with '${e.message}', expected it to mention '$reason'")
        }
    }

    @Test fun almostAnIpv4AddressIsRefusedNotTreatedAsAName() {
        for (typed in listOf("1.2.3", "256.1.1.1", "1.2.3.4.5", "192.0.2.", ".1.2.3", "1..2.3", "999.1.1.1")) {
            val e = assertFailsWith<ServerAddressException>(typed) { ServerAddress.parse(typed, pve) }
            assertEquals(ServerAddressProblem.Ipv4Malformed, e.problem, typed)
        }
    }

    // ---------------------------------------------------------------- IPv6

    @Test fun ipv6IsWrittenAsRfc5952InEveryForm() {
        val same = listOf("2001:db8::10", "2001:DB8::10", "2001:0db8:0:0:0:0:0:0010", "[2001:db8:0::10]", "2001:db8::0:10", "[2001:db8:0:0:0:0:0:10]:8006")
        for (typed in same) assertEquals("https://[2001:db8::10]:8006", ServerAddress.parse(typed, pve).canonical, typed)
        assertEquals("https://[::]:8006", ServerAddress.parse("::", pve).canonical)
        assertEquals("https://[1:2:3:4:5:6:7:0]:8006", ServerAddress.parse("1:2:3:4:5:6:7::", pve).canonical, "one zero group is not compressed")
        assertEquals("https://[1:0:0:2::3]:8006", ServerAddress.parse("[1:0:0:2:0:0:0:3]", pve).canonical, "the longest run is compressed")
        assertEquals("2001:db8::10", ServerAddress.parse("[2001:db8::10]", pve).host, "host has no brackets")
        assertEquals("[2001:db8::10]", ServerAddress.parse("[2001:db8::10]", pve).urlHost)
        assertEquals("[2001:db8::10]:8006", ServerAddress.parse("[2001:db8::10]", pve).display)
        assertEquals("pve.example.test:8006", ServerAddress.parse("pve.example.test", pve).display, "the port is always shown")
    }

    @Test fun badIpv6IsRefusedWithAReason() {
        val cases = listOf(
            "[2001:db8::10" to "closing ]", "[2001:db8:::10]" to "valid IPv6", "[1::2::3]" to "valid IPv6", "[1:2:3:4:5:6:7:8:9]" to "valid IPv6", "[2001:db8::10]x" to "closing ]", "[2001:db8::10]:8006:1" to "port",
            "[]" to "valid IPv6", "[::1]/x" to "login, page path", "https://2001:db8::10:8006" to "square brackets", "https://2001:db8::10" to "square brackets", "[2001:db8::10]:abc" to "port",
            "[::ffff:256.0.0.1]" to "valid IPv6", "[g::1]" to "valid IPv6",
        )
        for ((typed, reason) in cases) {
            val e = assertFailsWith<ServerAddressException>("'$typed'") { ServerAddress.parse(typed, pve) }
            assertTrue(reason.lowercase() in e.message!!.lowercase(), "'$typed' was refused with '${e.message}', expected it to mention '$reason'")
        }
    }

    // ---------------------------------------------------------------- zones

    @Test fun aZoneIsDecodedKeptApartAndNeverInTheCanonicalText() {
        for (typed in listOf("fe80::1%12", "[fe80::1%12]:8006", "[fe80::1%2512]:8006", "https://[fe80::1%2512]:8006", "FE80::1%12")) {
            val a = ServerAddress.parse(typed, pve, windows = true)
            assertEquals("12", a.zone, typed)
            assertEquals("https://[fe80::1]:8006", a.canonical, typed)
            assertEquals("fe80::1", a.host)
            assertEquals("fe80::1%12", a.socketHost, "the socket gets the zone, the stored text never")
            assertTrue(a.linkLocal)
        }
        val named = ServerAddress.parse("[fe80::1%25eth0]:8006", pve, windows = false)
        assertEquals("eth0", named.zone)
        assertEquals("fe80::1%eth0", named.socketHost)
        assertNull(ServerAddress.parse("[2001:db8::10]", pve).zone)
        assertEquals("2001:db8::10", ServerAddress.parse("[2001:db8::10]", pve).socketHost)
        assertFalse(ServerAddress.parse("[2001:db8::10]", pve).linkLocal)
    }

    @Test fun onWindowsOnlyTheInterfaceNumberIsAZone() {
        val e = assertFailsWith<ServerAddressException> { ServerAddress.parse("[fe80::1%25Ethernet]:8006", pve, windows = true) }
        assertEquals(ServerAddressProblem.Zone, e.problem)
        assertEquals("On Windows the zone is the interface number, like fe80::1%12.", e.message)
        assertEquals("Ethernet", ServerAddress.parse("[fe80::1%25Ethernet]:8006", pve, windows = false).zone)
    }

    @Test fun aZoneBelongsToALinkLocalAddressAndMustBeUsable() {
        for ((typed, reason) in listOf("[2001:db8::10%12]" to "link-local", "[::1%1]" to "link-local", "[fe80::1%]" to "zone after % is empty",
            "[fe80::1%-x]" to "interface number or name", "[fe80::1%a%20b]" to "interface number or name", "[fe80::1%12345678901234567]" to "interface number or name")) {
            val e = assertFailsWith<ServerAddressException>(typed) { ServerAddress.parse(typed, pve, windows = false) }
            assertEquals(ServerAddressProblem.Zone, e.problem, typed)
            assertTrue(reason in e.message!!, "'$typed': ${e.message}")
        }
    }

    // ---------------------------------------------------------------- properties

    /** Whatever is typed, nothing but a ServerAddressException comes out, and whatever is accepted is stable: its canonical text parses to itself. */
    @Test fun noInputEscapesAsAnotherExceptionAndEveryAcceptedAddressIsStable() {
        val alphabet = "0123456789abcdefABCDEFxX:.[]%/@?#- _ghpsHTP".toList() + listOf(0xfc.toChar(), 0x661.toChar(), 0xff11.toChar(), 0.toChar(), 10.toChar())
        val random = Random(20261116)
        var accepted = 0
        repeat(40_000) {
            val text = buildString { repeat(random.nextInt(1, 24)) { append(alphabet[random.nextInt(alphabet.size)]) } }
            val result = try { ServerAddress.parse(text, pve, windows = random.nextBoolean()) } catch (_: ServerAddressException) { null }
            if (result != null) {
                accepted++
                val again = ServerAddress.parse(result.canonical, pve, windows = false)
                assertEquals(result, again, "'$text' -> ${result.canonical}")
                assertEquals(result.canonical, again.canonical)
                assertTrue(result.canonical.startsWith("https://"))
                assertTrue('%' !in result.canonical, "a zone never reaches the canonical text: '$text'")
                assertTrue(result.canonical.none { it.isWhitespace() || it.isISOControl() || it.code > 127 }, "'$text' -> ${result.canonical}")
            }
        }
        assertTrue(accepted > 100, "the fuzz must reach accepted inputs too: $accepted")
    }

    @Test fun theCanonicalFormNeverContainsWhatTheOldOriginsDid() {
        for (typed in listOf("PVE.EXAMPLE.TEST", "pve.example.test.", "https://pve.example.test:443", "[2001:DB8:0:0:0:0:0:10]", "fe80::1%12", bucher)) {
            val canonical = ServerAddress.parse(typed, pve, windows = true).canonical
            assertEquals(canonical.lowercase(), canonical, "no upper case: $typed")
            assertFalse(canonical.substringAfter("https://").substringBefore("/").trimEnd('.') != canonical.substringAfter("https://").substringBefore("/"), "no trailing dot: $typed")
            assertFalse(canonical.endsWith(":443"), "no :443: $typed")
        }
    }
}
