package local.ringleader.core

import kotlin.test.*

/** One card per machine: what joins hits into a device, which address is preferred, and when a device counts as saved. Documentation addresses only. */
class ScanDevicesTest {
    private fun fp(seed: Int) = (0 until 32).joinToString(":") { "%02X".format((seed * 37 + it * 11) and 255) }
    private fun hit(ip: String, port: Int = 8006, kind: ScanKind = ScanKind.PVE, fingerprint: String? = fp(1), name: String = "pve", secure: Boolean = true) =
        ScanHit(ip, port, secure, kind, fingerprint = fingerprint, subject = "CN=$name.example.test,OU=PVE Cluster Node")

    /** Audit F4, inverted: one server reached on two addresses is one device, and another server no longer sits between its copies. */
    @Test fun theSameServerOnTwoAddressesIsOneDeviceAndAnotherServerDoesNotSitBetween() {
        val a1 = hit("192.0.2.10", name = "pve-a"); val b = hit("198.51.100.5", fingerprint = fp(2), name = "pve-b"); val a2 = hit("203.0.113.10", name = "pve-a")
        val devices = ScanDevices.group(ScanRules.merge(listOf(a1, b, a2)))
        assertEquals(2, devices.size)
        assertEquals(listOf("192.0.2.10", "203.0.113.10"), devices[0].addresses); assertEquals("198.51.100.5", devices[1].primary.ip)
        assertEquals(listOf("203.0.113.10"), devices[0].alsoAt.map { it.ip })
        assertEquals(listOf(a1, a2), devices[0].choices); assertTrue(devices[0].others.isEmpty())
    }

    @Test fun aHostWithSeveralPortsIsOneDeviceAndItsMainServiceComesFirst() {
        val backup = hit("192.0.2.5", port = 8007, kind = ScanKind.PBS, fingerprint = fp(3), name = "backup")
        val pve = hit("192.0.2.5", port = 8006, fingerprint = fp(4), name = "pve")
        val pdm = hit("192.0.2.5", port = 8443, kind = ScanKind.PDM, fingerprint = fp(5), name = "pdm")
        val device = ScanDevices.group(listOf(backup, pdm, pve)).single()
        assertEquals(pve, device.primary); assertEquals(listOf(pdm, backup), device.others, "Datacenter Manager before Backup Server")
        assertEquals(setOf(pve, pdm, backup), device.hits.toSet()); assertEquals(listOf("192.0.2.5"), device.addresses)
        assertEquals(ScanKind.PDM, ScanDevices.group(listOf(backup, pdm)).single().primary.kind)
    }

    @Test fun namesNeverJoinAnything() {
        val one = hit("192.0.2.10", fingerprint = fp(1), name = "pve"); val two = hit("198.51.100.10", fingerprint = fp(2), name = "pve")
        assertEquals(2, ScanDevices.group(listOf(one, two)).size, "the same common name on two certificates is two devices")
        // A hit with no certificate joins only by address.
        val bare1 = hit("192.0.2.20", fingerprint = null); val bare2 = hit("198.51.100.20", fingerprint = null)
        assertEquals(2, ScanDevices.group(listOf(bare1, bare2)).size)
        assertEquals(1, ScanDevices.group(listOf(bare1, bare1.copy(port = 8443, kind = ScanKind.PDM))).size)
    }

    @Test fun onlyProxmoxServicesAreDevicesAndAWebPageWithTheSameCertificateIsNotJoined() {
        val web = ScanHit("198.51.100.30", 443, true, ScanKind.WEB, fingerprint = fp(1))
        val pve = hit("192.0.2.10")
        assertEquals(listOf(pve), ScanDevices.group(listOf(pve, web)).map { it.primary }); assertTrue(ScanDevices.group(listOf(web)).isEmpty()); assertTrue(ScanDevices.group(emptyList()).isEmpty())
    }

    @Test fun theAddressOnANetworkThisComputerIsAttachedToIsPreferred() {
        val far = hit("198.51.100.21"); val near = hit("192.0.2.21")
        val none = ScanDevices.group(listOf(far, near)).single()
        assertEquals("198.51.100.21", none.primary.ip, "without a preference the order found stands")
        val attached = ScanDevices.group(listOf(far, near), attached = listOf("192.0.2.0/24")).single()
        assertEquals("192.0.2.21", attached.primary.ip); assertEquals(listOf("192.0.2.21", "198.51.100.21"), attached.choices.map { it.ip })
        assertEquals(listOf("198.51.100.21"), attached.alsoAt.map { it.ip })
        // Hostile or odd network text is ignored, not trusted.
        assertEquals("198.51.100.21", ScanDevices.group(listOf(far, near), attached = listOf("+192.0.2.0/24", "junk", "", "192.0.2.0/99")).single().primary.ip)
    }

    @Test fun theChoiceOnOneAddressIsTheSecureServiceOnTheLowestPort() {
        val plain = hit("192.0.2.10", port = 8006, secure = false); val secure = hit("192.0.2.10", port = 8006, secure = true).copy(title = "secure")
        assertEquals("secure", ScanDevices.group(listOf(plain, secure)).single().primary.title)
    }

    @Test fun deviceOrderIsTheOrderOfTheirFirstHit() {
        val hits = listOf(hit("192.0.2.30", fingerprint = fp(3)), hit("192.0.2.10", fingerprint = fp(1)), hit("198.51.100.10", fingerprint = fp(1)), hit("192.0.2.20", fingerprint = fp(2)))
        assertEquals(listOf("192.0.2.30", "192.0.2.10", "192.0.2.20"), ScanDevices.group(hits).map { it.primary.ip })
    }

    // ---- saved --------------------------------------------------------------------------------------------------------------

    private val pin1 = pinKey(fp(1))!!

    /** Audit F5, inverted. A profile saved by name is still never matched by name, but one saved at the device's other address, or whose certificate is pinned, is. */
    @Test fun aServerIsSavedByAnAddressItAnswersOnOrByAPinnedCertificateNeverByName() {
        val a1 = hit("192.0.2.10", name = "pve-a"); val a2 = hit("198.51.100.10", name = "pve-a")
        fun saved(known: KnownSaved, vararg hits: ScanHit) = ScanDevices.group(hits.toList(), known).single().saved
        assertEquals(SavedMark.NO, saved(KnownSaved(listOf("https://pve-a.example.test:8006")), a1), "a name is not an address")
        assertEquals(SavedMark.NO, saved(KnownSaved(listOf("https://pve-a.example.test:8006"), emptyMap()), a1, a2))
        assertEquals(SavedMark(SavedBy.Address), saved(KnownSaved(listOf("https://192.0.2.10:8006")), a1))
        assertEquals(SavedMark(SavedBy.Address), saved(KnownSaved(listOf("https://198.51.100.10:8006")), a1, a2), "the other address of the same machine")
        assertEquals(SavedMark.NO, saved(KnownSaved(listOf("https://192.0.2.10:8443", "http://192.0.2.10:8006")), a1), "the same address on another port or scheme is another service")
        assertEquals(SavedMark(SavedBy.Pin, "saved connection pve-east"), saved(KnownSaved(emptyList(), mapOf(pin1 to "saved connection pve-east")), a1))
        assertEquals(SavedMark(SavedBy.Pin, "approved certificate for pve.example.test"), saved(KnownSaved(emptyList(), mapOf(pin1 to "approved certificate for pve.example.test")), a2))
        assertEquals(SavedMark(SavedBy.Address), saved(KnownSaved(listOf("https://192.0.2.10:8006"), mapOf(pin1 to "x")), a1), "the address is the stronger tie")
        assertEquals(SavedMark.NO, saved(KnownSaved(emptyList(), mapOf(pinKey(fp(9))!! to "other")), a1), "another certificate")
        assertFalse(saved(KnownSaved.NONE, a1).saved)
    }

    @Test fun aPinIsComparedInItsCanonicalFormOnly() {
        assertEquals(pin1, pinKey(fp(1))); assertEquals(pin1, pinKey(fp(1).lowercase().replace(":", ""))); assertEquals(pin1, pinKey("  " + fp(1) + " "))
        for (bad in listOf(null, "", "AB:CD", "zz".repeat(32), "ab".repeat(31), "ab".repeat(33), "+b".repeat(32))) assertNull(pinKey(bad), bad)
        // A pin that is not 64 hex digits never matches, whatever it is made of.
        assertFalse(ScanDevices.group(listOf(hit("192.0.2.10", fingerprint = "AB:CD")), KnownSaved(emptyList(), mapOf("abcd" to "x"))).single().saved.saved)
    }

    @Test fun ownsNamesTheServicesOfTheDevice() {
        val d = ScanDevices.group(listOf(hit("192.0.2.10"), hit("198.51.100.10"), hit("192.0.2.10", port = 8007, kind = ScanKind.PBS, fingerprint = fp(3)))).single()
        assertTrue(d.owns("https://192.0.2.10:8006") && d.owns("https://198.51.100.10:8006") && d.owns("https://192.0.2.10:8007")); assertFalse(d.owns("https://192.0.2.99:8006"))
    }
}
