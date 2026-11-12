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
    @Test fun virtualBridgesAreNotSwept() {
        for (name in listOf("virbr0 virbr0", "lxcbr0 lxcbr0", "lxdbr0 lxdbr0", "cni0 cni0", "podman0 podman0", "br-1a2b3c4d5e6f br-1a2b3c4d5e6f", "docker0 docker0")) {
            val p = ScanRules.plan(listOf(ScanInterface(name, "172.18.0.1", 16)))
            assertTrue(p.addresses.isEmpty(), name); assertEquals("Virtual-switch adapter", p.networks.single().reason, name)
        }
        for (name in listOf("eth0 eth0", "br0 br0", "vmbr0 vmbr0", "enp3s0 enp3s0", "Wi-Fi Intel(R) Wi-Fi 6E AX211 160MHz")) {
            assertEquals(254, ScanRules.plan(listOf(ScanInterface(name, "192.168.1.8", 24))).addresses.size, name)
        }
    }
    @Test fun products() {
        // Proxmox product names count on the products' own ports.
        for ((text, kind) in listOf("Proxmox Virtual Environment" to ScanKind.PVE, "Proxmox Backup Server" to ScanKind.PBS,
            "Proxmox Datacenter Manager" to ScanKind.PDM)) {
            for (port in PROXMOX_PORTS) assertEquals(kind, ScanRules.classify("10.0.0.1", port, true, ScanPeek(200, "<title>$text</title>"))!!.kind)
        }
        for ((text, kind) in listOf("Pi-hole" to ScanKind.PIHOLE, "AdGuard Home" to ScanKind.ADGUARD,
            "Home Assistant" to ScanKind.HOME_ASSISTANT, "Welcome" to ScanKind.WEB)) {
            assertEquals(kind, ScanRules.classify("10.0.0.1", 443, true, ScanPeek(200, "<title>$text</title>"))!!.kind)
        }
        assertEquals(ScanKind.HOME_ASSISTANT, ScanRules.classify("10.0.0.1", 8123, false, ScanPeek(200, "<title>Home Assistant</title>"))!!.kind)
        assertEquals(ScanKind.ADGUARD, ScanRules.classify("10.0.0.1", 3000, false, ScanPeek(200, "<title>Setup AdGuard Home</title>"))!!.kind)
        assertEquals(ScanKind.WEB, ScanRules.classify("10.0.0.1", 3000, false, ScanPeek(200, "<title>Grafana</title>"))!!.kind)
        assertEquals(ScanKind.PVE, ScanRules.classify("10.0.0.1",8006,true,ScanPeek(401,"",subject="OU=PVE Cluster Node,CN=pve-b.local"))!!.kind)
        assertNull(ScanRules.classify("10.0.0.1",8006,true,ScanPeek(500,"Proxmox Virtual Environment")))
    }
    @Test fun productNamesOnOtherPortsStayWebPages() {
        // A wiki, a docs container or a proxy error page that names a product is not that product.
        for (port in listOf(80, 443, 3000, 8123)) for (text in listOf("Proxmox Virtual Environment", "Proxmox Backup Server", "Proxmox Datacenter Manager")) {
            val hit = ScanRules.classify("10.0.0.1", port, port == 443, ScanPeek(200, "<title>Docs</title> $text"))!!
            assertEquals(ScanKind.WEB, hit.kind, "$text on $port"); assertFalse(hit.proxmox)
        }
        assertEquals(ScanKind.WEB, ScanRules.classify("10.0.0.1",443,true,ScanPeek(401,"",subject="OU=PVE Cluster Node,CN=pve-b.local"))!!.kind)
        // The product's own Server header is identity on any port, for example behind a port forward.
        assertEquals(ScanKind.PVE, ScanRules.classify("10.0.0.1",443,true,ScanPeek(401,"",server="pve-api-daemon/3.0"))!!.kind)
        assertEquals(ScanKind.PBS, ScanRules.classify("10.0.0.1",443,true,ScanPeek(200,"",server="pbs-api"))!!.kind)
        assertEquals(ScanKind.PDM, ScanRules.classify("10.0.0.1",443,true,ScanPeek(200,"",server="pdm-api"))!!.kind)
        // A body that only quotes the header name is not the header.
        assertEquals(ScanKind.WEB, ScanRules.classify("10.0.0.1",443,true,ScanPeek(200,"Server: pve-api-daemon"))!!.kind)
    }
    @Test fun certificateDetailsReachTheHit() {
        val hit = ScanRules.classify("10.0.0.1",8006,true,ScanPeek(200,"Proxmox Virtual Environment", fingerprint="AB:CD", subject="CN=pve",
            issuer="CN=Proxmox Virtual Environment,OU=PVE Cluster Manager CA", notAfter=1_900_000_000_000L))!!
        assertEquals("CN=Proxmox Virtual Environment,OU=PVE Cluster Manager CA", hit.issuer); assertEquals(1_900_000_000_000L, hit.notAfter)
        assertEquals("AB:CD", hit.fingerprint)
    }
    @Test fun lanSweepProbesWebPortsAndTheTailnetOnlyProxmox() = runBlocking {
        assertEquals(listOf(8006, 8007, 8443, 80, 443, 3000, 8123), LAN_SCAN_PORTS)
        val lan = mutableListOf<Int>()
        NetworkScanner { _, port -> synchronized(lan) { lan += port }; emptyList() }.scan(ScanRules.plan(listOf(ScanInterface("Ethernet","10.0.0.9",30))), concurrency=1).toList()
        assertEquals(LAN_SCAN_PORTS + LAN_SCAN_PORTS, lan)
        assertEquals(LAN_CONNECT_TIMEOUT_MS, NetworkScanner().connectTimeoutMs)
        assertTrue(tailnetScanner().connectTimeoutMs > LAN_CONNECT_TIMEOUT_MS)
        assertEquals(TAILNET_CONNECT_TIMEOUT_MS, tailnetScanner().connectTimeoutMs)
        val peer = TailscalePeer("1", "pve", null, "linux", true, listOf("100.64.0.2", "fd7a:115c:a1e0::2"), false, false, "dfw", null, null, null, null)
        val status = TailscaleStatus("Running", null, null, null, null, listOf(peer, peer.copy(id="2", online=false, addresses=listOf("100.64.0.3"))), null, emptyList())
        val seen = mutableListOf<Pair<String, Int>>()
        val scanner = NetworkScanner(TAILNET_CONNECT_TIMEOUT_MS) { ip, port -> synchronized(seen) { seen += ip to port }; if (port == 8006) listOf(ScanHit(ip, port, true, ScanKind.PVE)) else emptyList() }
        val found = findTailnetProxmox(status, scanner).toList().last()
        assertEquals(listOf("100.64.0.2" to 8006, "100.64.0.2" to 8007, "100.64.0.2" to 8443), seen)
        assertEquals("pve", found.single().peer.hostName)
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
