package local.ringleader.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Audit finding 9: Kotlin's `toIntOrNull` reads a leading `+` or `-` and the digits of other scripts, so `+192.+0.+2.+10`, Arabic-Indic
 * digits and `/+24` were taken for addresses and prefixes. Addresses and prefixes the user types are ASCII digits only.
 * The first four tests are the audit's `defect...` tests, moved here with their demands unchanged.
 */
class AsciiDigitsTest {
    /** Digits of another script, written from code points so the source stays plain ASCII. */
    private fun script(vararg codePoints: Int) = String(codePoints, 0, codePoints.size)
    private val arabic192 = script(0x661, 0x669, 0x662)

    @Test fun ipv4ParserRefusesPlusSigns() {
        assertNull(ScanRules.ipv4("+192.+0.+2.+10"))
    }

    @Test fun ipv4ParserRefusesNonAsciiDigits() {
        assertNull(ScanRules.ipv4("$arabic192.${script(0x660)}.${script(0x662)}.${script(0x661, 0x660)}"), "Arabic-Indic digits for 192.0.2.10")
    }

    @Test fun subnetFieldRefusesAPlusSignedAddressAndPrefix() {
        assertNull(ScanRules.planSubnet("+10.200.0.0/+24"))
    }

    private val ctx = CreateContext(storages = listOf(StorageChoice("node-a", "local", "dir", setOf("images", "rootdir", "iso", "vztmpl"), false, 0, 100, "available")))
    private fun ct(nic: CtNic, nameservers: String = "") = defaultCtDraft("node-a", ctx).copy(vmid = "105", hostname = "web-1", template = "local:vztmpl/a.tar.zst", nic = nic, nameservers = nameservers)

    @Test fun plusSignedPrefixIsRefusedInAnAddress() {
        val errors = validateCt(ct(CtNic(bridge = "vmbr0", ipv4 = IpMode.Static("192.0.2.10/+24", ""))), ctx)
        assertTrue(CreateFields.IP4 in errors, "ip=192.0.2.10/+24 passes validation: $errors")
    }

    // ---------------------------------------------------------------- the rest of the same flaw, and what must keep working

    @Test fun everySignAndForeignDigitIsRefusedInTheSweepFields() {
        for (bad in listOf("-192.0.2.10", "192.0.2.+10", "192.0.2.-1", "192. 0.2.10", "0x7f.0.0.1", "192.0.2.${script(0x661, 0x660)}", "${script(0xff11)}92.168.77.3")) assertNull(ScanRules.ipv4(bad), bad)
        for (bad in listOf("10.200.0.0/-24", "10.200.0.0/${script(0x662, 0x664)}", "10.200.0.0/ 24", "10.200.0.0/+8", "10.200.0.0/", "10.200.0.0/24x", "${arabic192}.168.77.0/24")) assertNull(ScanRules.planSubnet(bad), bad)
        assertFalse(ScanRules.subnetNarrowed("10.0.0.0/+8"))
        assertFalse(ScanRules.subnetNarrowed("10.0.0.0/${script(0x668)}"))
    }

    @Test fun ordinaryAddressesAndPrefixesStillWork() {
        assertEquals(0xC0000201L + 9, ScanRules.ipv4("192.0.2.10"))
        assertEquals(0L, ScanRules.ipv4("0.0.0.0"))
        assertEquals(0xFFFFFFFFL, ScanRules.ipv4("255.255.255.255"))
        assertNull(ScanRules.ipv4("192.0.2"))
        assertNull(ScanRules.ipv4("192.0.2.256"))
        assertEquals("192.168.77.0/24", assertNotNull(ScanRules.planSubnet("192.168.77.9")).cidr, "no prefix means a /24")
        assertEquals("10.200.0.0/24", assertNotNull(ScanRules.planSubnet("10.200.0.0/24")).cidr)
        assertEquals("10.200.0.0/30", assertNotNull(ScanRules.planSubnet("10.200.0.0/30")).cidr)
        assertEquals("10.200.0.0/24", assertNotNull(ScanRules.planSubnet("10.200.0.0/8")).cidr, "wider than a /24 is cut down to the /24")
        assertTrue(ScanRules.subnetNarrowed("10.0.0.0/8"))
        assertFalse(ScanRules.subnetNarrowed("10.0.0.0/24"))
        // A public network is swept only because the person typed exactly it: at most a /24, and on the Proxmox ports only.
        assertEquals(PROXMOX_PORTS, assertNotNull(ScanRules.planSubnet("8.8.8.0/24")).ports)
    }

    @Test fun theCreateFormTakesPrefixesInAsciiDigitsOnly() {
        fun errors(nic: CtNic) = validateCt(ct(nic), ctx)
        for (ok in listOf("192.0.2.10/24", "192.0.2.10/0", "192.0.2.10/32")) assertFalse(CreateFields.IP4 in errors(CtNic(bridge = "vmbr0", ipv4 = IpMode.Static(ok, ""))), ok)
        for (bad in listOf("192.0.2.10/33", "192.0.2.10/+24", "192.0.2.10/-1", "192.0.2.10/${script(0x662, 0x664)}", "192.0.2.10/ 24", "192.0.2.10/")) {
            assertTrue(CreateFields.IP4 in errors(CtNic(bridge = "vmbr0", ipv4 = IpMode.Static(bad, ""))), bad)
        }
        for (ok in listOf("2001:db8::10/64", "2001:db8::10/0", "2001:db8::10/128")) assertFalse(CreateFields.IP6 in errors(CtNic(bridge = "vmbr0", ipv6 = Ip6Mode.Static(ok, ""))), ok)
        for (bad in listOf("2001:db8::10/129", "2001:db8::10/+64", "2001:db8::10/${script(0x666, 0x664)}")) {
            assertTrue(CreateFields.IP6 in errors(CtNic(bridge = "vmbr0", ipv6 = Ip6Mode.Static(bad, ""))), bad)
        }
    }
}
