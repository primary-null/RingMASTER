package local.ringleader.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IpLiteralTest {
    @Test fun strictDottedQuadsOnly() {
        assertContentEquals(byteArrayOf(192.toByte(), 0, 2, 10), IpLiteral.parseV4("192.0.2.10"))
        assertContentEquals(byteArrayOf(0, 0, 0, 0), IpLiteral.parseV4("0.0.0.0"))
        assertContentEquals(byteArrayOf(255.toByte(), 255.toByte(), 255.toByte(), 255.toByte()), IpLiteral.parseV4("255.255.255.255"))
        for (bad in listOf("", "1.2.3", "1.2.3.4.5", "256.1.1.1", "192.0.02.10", "192.0.002.010", "00.1.1.1", "+1.2.3.4", "1.2.3.-4", " 1.2.3.4", "1.2.3.4 ",
            "1..3.4", "1.2.3.", ".1.2.3", "\u0661\u0669\u0662.0.2.10", "1.2.3.0x4", "1e1.2.3.4", "0x7f.0.0.1", "192.0.2.1000")) {
            assertNull(IpLiteral.parseV4(bad), "'$bad'")
        }
    }

    @Test fun brokenLookingDottedQuadsAreRecognisedAsSuch() {
        for (text in listOf("1.2.3", "192.0.002.010", "999.1.1.1", "1.2.3.4.5")) assertTrue(IpLiteral.looksLikeBrokenV4(text), text)
        for (text in listOf("192.0.2.10", "pve.example.test", "1.2.3.example", "deadbeef", "")) assertFalse(IpLiteral.looksLikeBrokenV4(text), text)
    }

    @Test fun everyValidIpv6TextFormIsAccepted() {
        val valid = listOf(
            "::", "::1", "1::", "fe80::1", "2001:db8::10", "2001:DB8::10", "2001:0db8:0000:0000:0000:0000:0000:0010", "1:2:3:4:5:6:7:8",
            "1:2:3:4:5:6:7::", "::2:3:4:5:6:7:8", "1::8", "1:2:3:4:5:6::8", "::ffff:192.0.2.10", "::192.0.2.10", "64:ff9b::192.0.2.33",
            "1:2:3:4:5:6:1.2.3.4", "ffff:ffff:ffff:ffff:ffff:ffff:255.255.255.255", "0:0:0:0:0:0:0:0",
        )
        for (text in valid) assertNotNull(IpLiteral.parseV6(text), text)
        assertContentEquals(ByteArray(16).also { it[15] = 1 }, IpLiteral.parseV6("::1"))
        assertContentEquals(byteArrayOf(0x20, 0x01, 0x0d, 0xb8.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x10), IpLiteral.parseV6("2001:db8::10"))
        assertContentEquals(IpLiteral.parseV6("::ffff:c000:20a"), IpLiteral.parseV6("::ffff:192.0.2.10"), "an embedded IPv4 tail is two groups")
    }

    @Test fun everyMalformedIpv6TextIsRefused() {
        val invalid = listOf(
            "", ":", ":::", "1:::2", "1::2::3", ":1::", "1::2:", ":1:2:3:4:5:6:7", "1:2:3:4:5:6:7:", "1:2:3:4:5:6:7", "1:2:3:4:5:6:7:8:9", "1:2:3:4:5:6:7:8::",
            "12345::", "g::1", "::1%12", "[::1]", "1.2.3.4", "::1.2.3", "::01.2.3.4", "1.2.3.4::", "::1.2.3.4:5", "1:2:3:4:5:6:7:1.2.3.4", "::ffff:256.0.0.1",
            "2001:db8:::1", "2001:db8:0:0:0:0:0:0:1", " ::1", "::1 ", "\u0661::1", "::\uff11", "+1::", "1::-1",
        )
        for (text in invalid) assertNull(IpLiteral.parseV6(text), "'$text'")
    }

    @Test fun rfc5952TextIsLowerCaseWithTheLongestZeroRunCompressed() {
        val cases = mapOf(
            "2001:0db8:0000:0000:0000:0000:0000:0001" to "2001:db8::1",
            "2001:DB8:0:0:1:0:0:1" to "2001:db8::1:0:0:1",
            "2001:db8:0:1:1:1:1:1" to "2001:db8:0:1:1:1:1:1",
            "2001:db8::0010" to "2001:db8::10",
            "1:0:0:2:0:0:0:3" to "1:0:0:2::3",
            "0:0:0:0:0:0:0:0" to "::", "0:0:0:0:0:0:0:1" to "::1", "1:0:0:0:0:0:0:0" to "1::", "FE80:0:0:0:0:0:0:1" to "fe80::1",
            "1:0:2:0:3:0:4:0" to "1:0:2:0:3:0:4:0", "1:2:3:4:5:6:7::" to "1:2:3:4:5:6:7:0", "::2:3:4:5:6:7:8" to "0:2:3:4:5:6:7:8",
            "::ffff:192.0.2.10" to "::ffff:192.0.2.10", "0:0:0:0:0:ffff:c000:20a" to "::ffff:192.0.2.10", "64:ff9b::192.0.2.33" to "64:ff9b::c000:221",
        )
        for ((typed, expected) in cases) assertEquals(expected, IpLiteral.formatV6(IpLiteral.parseV6(typed)!!), typed)
    }

    @Test fun formattingAndParsingAgree() {
        for (text in listOf("::1", "2001:db8::10", "fe80::1", "1:2:3:4:5:6:7:8", "::ffff:192.0.2.10")) {
            val bytes = IpLiteral.parseV6(text)!!
            assertContentEquals(bytes, IpLiteral.parseV6(IpLiteral.formatV6(bytes)), text)
        }
        assertEquals("192.0.2.10", IpLiteral.formatV4(IpLiteral.parseV4("192.0.2.10")!!))
    }

    @Test fun bracketsComeOffOnce() {
        assertEquals("2001:db8::1", IpLiteral.unbracket("[2001:db8::1]"))
        assertEquals("[2001:db8::1", IpLiteral.unbracket("[2001:db8::1"))
        assertEquals("pve.example.test", IpLiteral.unbracket("pve.example.test"))
        assertEquals("[x]", IpLiteral.unbracket("[[x]]"))
        assertTrue(IpLiteral.isLiteral("192.0.2.10") && IpLiteral.isLiteral("::1") && !IpLiteral.isLiteral("pve.example.test"))
    }
}
