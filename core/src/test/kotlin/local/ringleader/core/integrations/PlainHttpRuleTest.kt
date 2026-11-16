package local.ringleader.core.integrations

import java.net.InetAddress
import java.time.Instant
import kotlin.test.*

/** The plain-HTTP rule as a table, and the canonical origin it binds an acknowledgement to. */
class PlainHttpRuleTest {
    private val scopes = mapOf(
        "127.0.0.1" to AddressScope.Loopback, "127.255.0.9" to AddressScope.Loopback, "::1" to AddressScope.Loopback,
        "169.254.10.1" to AddressScope.LinkLocal, "fe80::1" to AddressScope.LinkLocal, "febf::1" to AddressScope.LinkLocal,
        "10.0.0.5" to AddressScope.Private, "172.16.0.1" to AddressScope.Private, "172.31.255.254" to AddressScope.Private,
        "192.168.1.1" to AddressScope.Private, "::ffff:10.1.2.3" to AddressScope.Private,
        "fd00::5" to AddressScope.UniqueLocal, "fc00::1" to AddressScope.UniqueLocal,
        "100.64.0.1" to AddressScope.Tailnet, "100.127.255.254" to AddressScope.Tailnet, "fd7a:115c:a1e0::1" to AddressScope.Tailnet,
        "fd7a:115c:a1e0:ab12:4843:cd96:625b:e016" to AddressScope.Tailnet,
        "172.15.0.1" to AddressScope.Public, "172.32.0.1" to AddressScope.Public, "100.63.255.255" to AddressScope.Public,
        "100.128.0.1" to AddressScope.Public, "8.8.8.8" to AddressScope.Public, "192.0.2.5" to AddressScope.Public,
        "2001:db8::1" to AddressScope.Public, "2606:4700::1111" to AddressScope.Public, "::ffff:8.8.8.8" to AddressScope.Public,
        "fd7a:115c:a1e1::1" to AddressScope.UniqueLocal, "fec0::1" to AddressScope.Public,
        "0.0.0.0" to AddressScope.Unusable, "0.1.2.3" to AddressScope.Unusable, "224.0.0.1" to AddressScope.Unusable,
        "239.255.255.250" to AddressScope.Unusable, "255.255.255.255" to AddressScope.Unusable, "240.0.0.1" to AddressScope.Unusable,
        "::" to AddressScope.Unusable, "ff02::1" to AddressScope.Unusable,
    )

    @Test fun everyAddressHasItsScope() {
        for ((text, scope) in scopes) assertEquals(scope, addressScope(InetAddress.getByName(text)), text)
    }

    private val origin = ServiceOrigin.parseOrThrow("http://192.0.2.5:8080")
    private val ack = PlainHttpAck.forOrigin(origin, Instant.EPOCH, "Pi-hole app password", "can change settings")
    private val otherAck = PlainHttpAck.forOrigin(ServiceOrigin.parseOrThrow("http://192.0.2.5:8081"), Instant.EPOCH, "x", "y")

    @Test fun theRuleTable() {
        for ((text, scope) in scopes) {
            val noCredential = PlainHttpRule.decide(scope, false, null, origin)
            val bare = PlainHttpRule.decide(scope, true, null, origin)
            val acked = PlainHttpRule.decide(scope, true, ack, origin)
            val wrongAck = PlainHttpRule.decide(scope, true, otherAck, origin)
            when (scope) {
                AddressScope.Unusable -> listOf(noCredential, bare, acked).forEach { assertIs<PlainHttpDecision.Refused>(it, text) }
                AddressScope.Public -> {
                    assertEquals(PlainHttpDecision.Allowed(UnencryptedMark.Unencrypted), noCredential, text)
                    assertEquals(PlainHttpDecision.Refused(PlainHttpRule.PUBLIC_REFUSED), bare, text)
                    assertEquals(PlainHttpDecision.Refused(PlainHttpRule.PUBLIC_REFUSED), acked, "an acknowledgement never opens a public address: $text")
                }
                else -> {
                    val mark = if (scope == AddressScope.Tailnet) UnencryptedMark.InsideTailnet else UnencryptedMark.Unencrypted
                    assertEquals(PlainHttpDecision.Allowed(mark), noCredential, text)
                    assertEquals(PlainHttpDecision.NeedsAcknowledgement, bare, text)
                    assertEquals(PlainHttpDecision.NeedsAcknowledgement, wrongAck, "an acknowledgement is bound to its endpoint: $text")
                    assertEquals(PlainHttpDecision.Allowed(mark), acked, text)
                }
            }
        }
    }

    @Test fun theAcknowledgementIgnoresOnlyTheBasePath() {
        assertTrue(ack.covers(ServiceOrigin.parseOrThrow("http://192.0.2.5:8080/admin")))
        assertFalse(ack.covers(ServiceOrigin.parseOrThrow("https://192.0.2.5:8080")))
        assertFalse(ack.covers(ServiceOrigin.parseOrThrow("http://192.0.2.6:8080")))
        assertTrue(PlainHttpAck.forOrigin(ServiceOrigin.parseOrThrow("http://h.example.test:80"), Instant.EPOCH, "", "").covers(ServiceOrigin.parseOrThrow("HTTP://H.Example.Test")))
    }

    @Test fun originsAreCanonical() {
        val cases = mapOf(
            "HTTP://Pi.Hole.LAN:80/admin/" to "http://pi.hole.lan/admin",
            "https://192.0.2.5:443" to "https://192.0.2.5",
            "https://192.0.2.5:8443/" to "https://192.0.2.5:8443",
            "http://[FD00:0:0:0::5]:8080/x//" to "http://[fd00::5]:8080/x",
            "http://[FD00:0:0:0::5]:8080/x/" to "http://[fd00::5]:8080/x",
            "http://[::ffff:10.0.0.1]" to "http://10.0.0.1",
            "http://[2001:db8:0:0:1:0:0:1]" to "http://[2001:db8::1:0:0:1]",
            "http://user:pw@192.0.2.5" to null, "http://192.0.2.5/?a=1" to null, "http://192.0.2.5/#x" to null,
            "ftp://192.0.2.5" to null, "http://127.1" to null, "http://999.0.0.1" to null, "http://[fe80::1%eth0]" to null,
            "http://192.0.2.5/a/../b" to null, "http://192.0.2.5/a%2fb" to null, "http:///x" to null, "http://bad_host" to null,
            "http://exa mple" to null, "http://192.0.2.5:0" to null, "http://192.0.2.5:70000" to null,
        )
        for ((input, expected) in cases) assertEquals(expected, ServiceOrigin.parse(input).getOrNull()?.canonical, input)
    }
}
