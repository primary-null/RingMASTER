package local.ringleader.desktop

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import local.ringleader.core.*

class ScanGridLayoutTest {
    private val twoNetworks = ScanRules.plan(listOf(ScanInterface("Ethernet", "192.168.1.5", 24), ScanInterface("Wi-Fi", "172.16.5.5", 24)))

    @Test fun twoSweptNetworksGetTheirOwnStrips() {
        val layout = ScanGridLayout(twoNetworks)
        assertEquals(2, layout.strips); assertEquals(8, layout.rows)
        assertEquals(20 to 0, layout.cell("192.168.1.20"))
        assertEquals(20 to 4, layout.cell("172.16.5.20"))
        assertEquals(63 to 7, layout.cell("172.16.5.255"))
        // The same last octet on two networks is two different dots.
        assertNotEquals(layout.cell("192.168.1.77"), layout.cell("172.16.5.77"))
    }

    @Test fun hoverNamesTheHostOnTheNetworkUnderThePointer() {
        val layout = ScanGridLayout(twoNetworks)
        val a = ScanHit("192.168.1.20", 8006, true, ScanKind.PVE)
        val b = ScanHit("172.16.5.20", 8006, true, ScanKind.PVE)
        val width = 1280f; val height = 128f
        val onB = Offset((20 + .5f) * width / 64, (4 + .5f) * height / 8)
        val onA = Offset((20 + .5f) * width / 64, .5f * height / 8)
        assertEquals(b, gridHitAt(listOf(a, b), onB, width, height, 12f, layout))
        assertEquals(a, gridHitAt(listOf(a, b), onA, width, height, 12f, layout))
    }

    @Test fun sweepOrderFollowsThePlan() {
        val layout = ScanGridLayout(twoNetworks)
        assertEquals(254 * 2, layout.order.size)
        assertEquals(1, layout.order.first())                    // 192.168.1.1: column 1, row 0
        assertEquals(4 * 64 + 1, layout.order[254])              // 172.16.5.1: first dot of the second strip
        assertEquals(layout.order.size, layout.order.toSet().size)
    }

    @Test fun oneNetworkKeepsTheOldGrid() {
        val layout = ScanGridLayout(ScanRules.plan(listOf(ScanInterface("Ethernet", "192.0.2.2", 24))))
        assertEquals(1, layout.strips); assertEquals(4, layout.rows)
        assertEquals(11 to 0, layout.cell("192.0.2.11")); assertEquals(0 to 2, layout.cell("192.0.2.128"))
        // No swept network: the last octet, as before.
        assertEquals(5 to 3, ScanGridLayout(NetworkPlan(emptyList())).cell("198.51.100.197"))
    }

    @Test fun certificateLineShowsExpiryAndIssuer() {
        val day = 86_400_000L
        val now = 1_800_000_000_000L
        val base = ScanHit("192.0.2.10", 8006, true, ScanKind.PVE, fingerprint = "AB:CD", subject = "CN=pve1.example.test,OU=PVE Cluster Node")
        assertEquals("Plain or unreadable TLS", certificateNote(base.copy(fingerprint = null), now))
        assertEquals("Unverified certificate", certificateNote(base, now))
        val issued = base.copy(issuer = "CN=Proxmox Virtual Environment,OU=12345,O=PVE Cluster Manager CA", notAfter = now + 30 * day)
        assertEquals("Unverified certificate · expires 2027-02-14 · issuer Proxmox Virtual Environment", certificateNote(issued, now))
        assertTrue(certificateNote(issued.copy(notAfter = now - day), now).contains("expired 2027-01-14"))
        assertTrue(certificateNote(base.copy(issuer = base.subject), now).endsWith("self-signed"))
    }

    @Test fun helpTextNamesEveryProbedPort() {
        for (port in LAN_SCAN_PORTS) assertTrue(port.toString() in SCAN_HELP, "$port")
    }
}
