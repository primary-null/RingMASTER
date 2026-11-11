package local.ringleader.core

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class NetworkScanTest {
    @Test fun privateCaps() {
        for (ip in listOf("10.0.0.8", "172.16.2.8", "192.168.1.8")) {
            val p = ScanRules.plan(listOf(ScanInterface("Ethernet", ip, 16)))
            assertEquals(254, p.addresses.size)
            assertTrue(p.networks.single().cidr!!.endsWith("/24"))
        }
    }
    @Test fun narrowAndDuplicate() {
        val i = ScanInterface("Ethernet", "10.0.0.9", 30)
        assertEquals(listOf("10.0.0.9", "10.0.0.10"), ScanRules.plan(listOf(i, i)).addresses)
    }
    @Test fun exclusions() {
        for ((name, ip, prefix) in listOf(Triple("mesh", "100.64.0.1", 24), Triple("mesh", "100.127.255.2", 24),
            Triple("vEthernet (WSL)", "172.20.0.1", 20), Triple("Ethernet", "127.0.0.1", 8),
            Triple("Ethernet", "169.254.1.2", 16), Triple("Ethernet", "8.8.8.8", 24), Triple("VPN", "10.1.0.1", 32))) {
            val p = ScanRules.plan(listOf(ScanInterface(name, ip, prefix)))
            assertTrue(p.addresses.isEmpty()); assertNotNull(p.networks.single().reason)
        }
    }
    @Test fun products() {
        for ((text, kind) in listOf("Proxmox Virtual Environment" to ScanKind.PVE, "Proxmox Backup Server" to ScanKind.PBS,
            "Proxmox Datacenter Manager" to ScanKind.PDM, "Pi-hole" to ScanKind.PIHOLE, "AdGuard Home" to ScanKind.ADGUARD,
            "Home Assistant" to ScanKind.HOME_ASSISTANT, "Welcome" to ScanKind.WEB)) {
            assertEquals(kind, ScanRules.classify("10.0.0.1", 443, true, ScanPeek(200, "<title>$text</title>"))!!.kind)
        }
        assertEquals(ScanKind.PVE, ScanRules.classify("10.0.0.1",8006,true,ScanPeek(401,"",subject="OU=PVE Cluster Node,CN=pve-b.local"))!!.kind)
        assertNull(ScanRules.classify("10.0.0.1",8006,true,ScanPeek(500,"Proxmox Virtual Environment")))
    }
    @Test fun redirects() {
        assertNull(ScanRules.classify("10.0.0.1",80,false,ScanPeek(302,"",location="https://10.0.0.1/")))
        assertNotNull(ScanRules.classify("10.0.0.1",80,false,ScanPeek(302,"",location="https://elsewhere/")))
    }
    @Test fun merge() {
        val web = ScanHit("10.0.0.1",80,false,ScanKind.WEB)
        val named = web.copy(kind=ScanKind.PIHOLE)
        assertEquals(listOf(named.copy(port=443,secure=true)), ScanRules.merge(listOf(web,named,named.copy(port=443,secure=true))))
        assertEquals(1, ScanRules.merge(listOf(web,web.copy(port=443,secure=true))).size)
    }
    @Test fun savedOrigins() {
        val h = ScanHit("10.0.0.1",443,true,ScanKind.PVE)
        assertTrue(ScanRules.saved(h,listOf("https://10.0.0.1")))
        assertFalse(ScanRules.saved(h,listOf("https://10.0.0.1:8006", "https://10.0.0.10", "http://10.0.0.1")))
    }
    @Test fun cancellation() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var stopped = false
        val scanner = NetworkScanner { _, _ -> started.complete(Unit); try { awaitCancellation() } finally { stopped = true } }
        val job = launch { scanner.scan(ScanRules.plan(listOf(ScanInterface("Ethernet","10.0.0.1",30))), concurrency=1).collect() }
        started.await(); withTimeout(1000) { job.cancelAndJoin() }
        assertTrue(stopped)
    }
    @Test fun flowProgressAndSaved() = runBlocking {
        val scanner = NetworkScanner { ip, port -> if(port==8006) listOf(ScanHit(ip,port,true,ScanKind.PVE)) else emptyList() }
        val values = scanner.scan(ScanRules.plan(listOf(ScanInterface("Ethernet","10.0.0.1",30))),listOf("https://10.0.0.1:8006"),1).toList()
        assertEquals(0,values.first().scanned); assertEquals(2,values.last().scanned)
        assertTrue(values.last().finished); assertTrue(values.last().hits.first().saved)
    }
}
