package local.ringleader.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Network scan audit, 2026-11-16 ("why does the application not scan the other networks?"). Report:
 * `.local/network-audit-20261116/scan-networks-and-page.md` in the main checkout.
 *
 * These are characterization tests: each one pins what the code does TODAY, including behaviour the audit calls `wrong`
 * or a gap. They pass now. When a finding is fixed, the matching assertion should be inverted, not deleted, so the
 * decision stays written down. Nothing here leaves this computer: `audit12` talks to a loopback server it starts itself,
 * and the opt-in live measurement at the bottom (RM_SCAN_AUDIT_LIVE=1) sweeps one attached /24 and prints counts and
 * seconds only. No address in this file belongs to a real network.
 */
class NetworkScanAuditTest {
    private fun network(name: String, ip: String, prefix: Int) = ScanRules.plan(listOf(ScanInterface(name, ip, prefix))).networks.single()

    /**
     * F1, inverted. A /22 or /16 the computer sits in is still swept as the /24 around it by default, but nothing calls that /24 "the
     * network" any more: the sweep carries the real network, the plan row says what it is a part of, and the whole /22 is a separate,
     * unticked choice. (The page's "Complete" no longer stands for the whole: see ScanPageStatesTest.)
     */
    @Test fun audit01_attachedNetworkWiderThanSlash24IsCutToOneSlash24AndThePlanSaysSo() {
        val wide = network("Ethernet", "10.20.5.7", 22)             // really 10.20.4.0/22: 1,022 hosts
        assertEquals("10.20.5.0/24", wide.cidr)                      // what is swept
        assertEquals(254, wide.addresses.size)
        assertEquals(22, wide.source.prefix)
        assertEquals("10.20.4.0/22", wide.realCidr)                  // and what the network really is, carried to the scanner and the page
        for (outside in listOf("10.20.4.1", "10.20.6.1", "10.20.7.254")) assertFalse(outside in wide.addresses, outside)
        val sixteen = network("Ethernet", "10.20.5.7", 16)           // 65,534 hosts, 254 swept
        assertEquals("10.20.5.0/24", sixteen.cidr); assertEquals("10.20.0.0/16", sixteen.realCidr)
        assertEquals(254, sixteen.addresses.size)
        val rows = ScanPlanner.plan(listOf(ScanInterface("Ethernet", "10.20.5.7", 22))).rows
        assertEquals("the /24 around 10.20.5.7, of a /22 (1,022 addresses)", rows[0].note)
        assertFalse(rows[1].on); assertEquals(1022, rows[1].count)   // the whole /22 is offered, not taken
    }

    /**
     * F2, inverted. The virtual-adapter rule no longer reads the display name when the system's own facts are there. A Hyper-V external
     * switch ("allow the management operating system to share this network adapter") moves the computer's own LAN address onto
     * "Hyper-V Virtual Ethernet Adapter", and the only NIC of a Hyper-V or Azure guest is "Microsoft Hyper-V Network Adapter":
     * both are virtual and both carry the default route, so both are the real LAN and are swept. A NAT switch (WSL, Default Switch)
     * is virtual and carries no default route, and stays out. The name rule remains only as the fallback.
     */
    @Test fun audit02_realNicsWhoseDescriptionMatchesTheVirtualAdapterRuleAreSweptWhenTheyCarryTheDefaultRoute() {
        fun facts(index: Int, defaultRoute: Boolean) = NetworkFacts(listOf(AdapterFact(index, "Ethernet $index", null, true, false)),
            listOfNotNull(if (defaultRoute) RouteEntry(0, 0, "192.168.20.1", index) else null), "test")
        for (name in listOf("ethernet_32775 Hyper-V Virtual Ethernet Adapter", "ethernet_32777 Hyper-V Virtual Ethernet Adapter #2",
            "ethernet_32768 Microsoft Hyper-V Network Adapter", "Ethernet 2 Microsoft Hyper-V Network Adapter #3")) {
            val card = ScanInterface(name, "192.168.20.5", 24, true, 12)
            val real = ScanPlanner.plan(listOf(card), facts(12, true)).rows.single()
            assertTrue(real.swept, name); assertEquals("192.168.20.0/24", real.sweep, name)
            val nat = ScanPlanner.plan(listOf(card), facts(12, false)).rows.single()
            assertFalse(nat.swept, name); assertNull(nat.sweep, name)
            // Without the facts the old name rule decides, as it did.
            val fallback = network(name, "192.168.20.5", 24)
            assertNull(fallback.cidr, name); assertEquals("Virtual-switch adapter", fallback.reason, name); assertTrue(fallback.addresses.isEmpty(), name)
        }
    }

    /**
     * F3, inverted. A typed network can be public or link-local space (Proxmox ports only), but never CGNAT or tailnet space; a /32 is
     * that one host (it used to be widened to a /30 that left the host out) and the page can say what a typed network was cut to.
     */
    @Test fun audit03_aTypedNetworkCanBePublicOrLinkLocalButNeverCgnatAndASingleHostIsThatHost() {
        for (text in listOf("100.64.0.0/24", "100.100.100.0/24", "fd00::/64")) assertNull(ScanRules.planSubnet(text), text)
        for (text in listOf("198.51.100.0/24", "169.254.10.0/24", "8.8.8.0/24")) assertEquals(PROXMOX_PORTS, ScanRules.planSubnet(text)!!.ports, text)
        val host = ScanRules.planSubnet("192.168.20.7/32")!!
        assertEquals("192.168.20.7/32", host.cidr)
        assertEquals(listOf("192.168.20.7"), host.addresses)
        assertFalse(ScanRules.subnetNarrowed("192.168.20.7/32"))
        // The same for /31: both addresses of the pair, the typed host among them.
        assertEquals(listOf("192.168.20.6", "192.168.20.7"), ScanRules.planSubnet("192.168.20.7/31")!!.addresses)
        assertEquals("one address", (ScanTyped.parse("192.168.20.7/32") as TypedNetwork.Rows).rows.single().note)
    }

    /**
     * F4, inverted. One server reached on two addresses is still two hits (the scan finds addresses), and the sort still puts another
     * host between them, but [ScanDevices] puts them together: one device, with the other address named. See ScanDevicesTest.
     */
    @Test fun audit04_theSameServerOnTwoAddressesIsTwoHitsAndOneDevice() {
        val fingerprint = "AB:" + "CD:".repeat(30) + "EF"
        fun pve(ip: String, fp: String, cn: String) = ScanHit(ip, 8006, true, ScanKind.PVE, fingerprint = fp, subject = "CN=$cn,OU=PVE Cluster Node")
        val merged = ScanRules.merge(listOf(pve("192.168.20.10", fingerprint, "pve-a.example.test"), pve("172.16.0.5", "11:22", "pve-b.example.test"),
            pve("10.20.0.10", fingerprint, "pve-a.example.test")))
        assertEquals(listOf("10.20.0.10", "172.16.0.5", "192.168.20.10"), merged.map { it.ip })
        assertEquals(2, merged.count { it.fingerprint == fingerprint })
        assertEquals("pve-b.example.test", subjectCommonNameForAudit(merged[1]))      // the other server sits between the two copies in the sorted list
        // The hit's own Saved flag is still per address and port, but the device is saved when any of its addresses is.
        val flagged = merged.map { it.copy(saved = ScanRules.saved(it, listOf("https://10.20.0.10:8006"))) }
        assertEquals(listOf(true, false, false), flagged.map { it.saved })
        val devices = ScanDevices.group(merged, KnownSaved(listOf("https://10.20.0.10:8006")))
        assertEquals(2, devices.size); assertEquals(listOf("10.20.0.10", "192.168.20.10"), devices[0].addresses); assertEquals(SavedBy.Address, devices[0].saved.by)
        assertEquals("172.16.0.5", devices[1].primary.ip); assertFalse(devices[1].saved.saved)
    }

    private fun subjectCommonNameForAudit(hit: ScanHit) = hit.subject?.substringAfter("CN=")?.substringBefore(',')

    /**
     * F5, inverted. "Saved" on a hit is still the exact address and port, and a profile saved by name still never matches (node names are
     * not unique). A device is also Saved when the certificate it presents is pinned by a saved connection or approved: a server saved
     * at its other address, or by name with a pinned certificate, is found. See ScanDevicesTest.
     */
    @Test fun audit05_aProfileSavedByNameIsNeverMarkedSavedButItsPinnedCertificateIs() {
        val fingerprint = "AB:" + "CD:".repeat(30) + "EF"
        val hit = ScanHit("192.168.20.10", 8006, true, ScanKind.PVE, fingerprint = fingerprint)
        assertTrue(ScanRules.saved(hit, listOf("https://192.168.20.10:8006")))
        assertFalse(ScanRules.saved(hit, listOf("https://pve-a.example.test:8006")))
        assertFalse(ScanRules.saved(hit, listOf("https://[fd00::10]:8006")))
        val byName = KnownSaved(listOf("https://pve-a.example.test:8006"), mapOf(pinKey(fingerprint)!! to "saved connection pve-a"))
        assertEquals(SavedMark(SavedBy.Pin, "saved connection pve-a"), ScanDevices.group(listOf(hit), byName).single().saved)
    }

    /** F6. Outside the plan: IPv6 interface addresses, and /31 or /32 point-to-point links (typical for a WireGuard or OpenVPN client). */
    @Test fun audit06_ipv6AndPointToPointLinksAreOutsideThePlan() {
        assertEquals("Invalid IPv4", network("eth0", "fd00::5", 64).reason)
        assertEquals("Point-to-point or invalid prefix", network("wg0", "10.8.0.2", 32).reason)
        assertEquals("Point-to-point or invalid prefix", network("tun0", "10.8.0.2", 31).reason)
        // A VPN that hands out a /24 is swept like a LAN, without a question.
        assertEquals("10.8.0.0/24", network("tun0", "10.8.0.6", 24).cidr)
        assertEquals("10.8.0.0/24", network("wg0", "10.8.0.6", 24).cidr)
    }

    /**
     * F7, inverted. The ports of one address used to be probed one after another (seven waits for a silent address). Now the Proxmox
     * ports of an address are asked side by side, and so are the rest for an address that answered or is listed by the neighbour table;
     * different addresses run side by side too, and no more than the connects allowed are open at once.
     */
    @Test fun audit07_portsOfOneAddressAreProbedSideBySide() = runBlocking {
        val inFlight = ConcurrentHashMap<String, AtomicInteger>()
        var maxPerAddress = 0
        var maxTotal = 0
        val total = AtomicInteger()
        val lock = Any()
        val scanner = NetworkScanner { ip, _ ->
            val mine = inFlight.getOrPut(ip) { AtomicInteger() }.incrementAndGet()
            val all = total.incrementAndGet()
            synchronized(lock) { maxPerAddress = maxOf(maxPerAddress, mine); maxTotal = maxOf(maxTotal, all) }
            delay(15)
            inFlight.getValue(ip).decrementAndGet(); total.decrementAndGet()
            emptyList()
        }
        scanner.scan(ScanRules.plan(listOf(ScanInterface("Ethernet", "10.20.0.1", 27))), concurrency = 24).toList()
        assertTrue(maxPerAddress in 3..LAN_SCAN_PORTS.size, "the ports of one address run together: $maxPerAddress")
        assertTrue(maxTotal > maxPerAddress)    // and many addresses at once
        assertTrue(maxTotal <= MAX_QUICK_IN_FLIGHT)
        assertEquals(7, LAN_SCAN_PORTS.size)
    }

    /**
     * F8, inverted. The old sweep cost every port times the connect wait for each silent address, 24 at a time: about 30 s for a /24,
     * two minutes for a /22 (measured: 31.9 s for a /24). The estimate on the plan now follows the new engine's constants.
     */
    @Test fun audit08_theEstimateFollowsTheNewEnginesCheapPassNotEveryPortOfEveryAddress() {
        val oldSeconds = { addresses: Int -> addresses * LAN_SCAN_PORTS.size * LAN_CONNECT_TIMEOUT_MS / 1000.0 / 24 }
        assertEquals(29.6, oldSeconds(254), 0.1); assertEquals(119.2, oldSeconds(1022), 0.1)
        assertEquals(6, ScanEstimate.seconds(254, true, LAN_SCAN_PORTS.size))              // a /24 on this computer's own network
        assertEquals(12, ScanEstimate.seconds(1022, true, LAN_SCAN_PORTS.size))            // a /22
        assertEquals(7, ScanEstimate.seconds(254, false, PROXMOX_PORTS.size))              // a routed /24, paced
        assertTrue(ScanEstimate.seconds(254, true, 7) < oldSeconds(254) / 4 && ScanEstimate.seconds(1022, true, 7) < oldSeconds(1022) / 8)
        assertTrue(ScanEstimate.seconds(4094, true, 7) < 40)                                // a /20 is no longer minutes
    }

    /** F9. The tailnet search takes online peers' IPv4 addresses and /32 routes only. Offline and IPv6-only peers are not probed. */
    @Test fun audit09_theTailnetSearchSkipsOfflineAndIpv6OnlyPeers() = runBlocking {
        fun peer(id: String, online: Boolean, vararg addresses: String) =
            TailscalePeer(id, "peer-$id", null, "linux", online, addresses.toList(), false, false, "dfw", null, null, null, null)
        val status = TailscaleStatus("Running", null, null, null, null,
            listOf(peer("1", true, "100.64.0.2"), peer("2", false, "100.64.0.3"), peer("3", true, "fd7a:115c:a1e0::9")), null, emptyList())
        val seen = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        findTailnetProxmox(status, NetworkScanner(TAILNET_CONNECT_TIMEOUT_MS) { ip, _ -> seen += ip; emptyList() }).toList()
        assertEquals(setOf("100.64.0.2"), seen)
    }

    /**
     * F10, fixed. The tailnet status model keeps which routes a router is in charge of, shared-in nodes and userspace mode,
     * and a node someone else shared in is not searched unless the person asks for it.
     */
    @Test fun audit10_tailscaleStatusKeepsRoutesSharedNodesAndUserspaceAndSharedNodesAreNotSearched() {
        val json = """{"BackendState":"Running","TUN":false,"Self":{"ID":"s","HostName":"me","TailscaleIPs":["100.64.0.1"]},
          "Peer":{"nodekey:aa":{"ID":"p1","HostName":"router","DNSName":"router.example.ts.net.","Online":true,"TailscaleIPs":["100.64.0.2"],
          "AllowedIPs":["100.64.0.2/32","192.168.20.0/24"],"PrimaryRoutes":["192.168.20.0/24"]},
          "nodekey:bb":{"ID":"p2","HostName":"friend","Online":true,"ShareeNode":true,"TailscaleIPs":["100.64.0.3"],"AllowedIPs":["100.64.0.3/32"]}}}"""
        val status = parseTailscaleStatus(json)
        val router = status.peers.single { it.id == "p1" }
        assertEquals(listOf("100.64.0.2/32", "192.168.20.0/24"), router.allowedIps)
        assertEquals(listOf("192.168.20.0/24"), router.primaryRoutes)
        assertTrue(status.peers.single { it.id == "p2" }.sharee)
        assertTrue(status.userspace)
        val seen = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        runBlocking { findTailnetProxmox(status, NetworkScanner(TAILNET_CONNECT_TIMEOUT_MS) { ip, _ -> seen += ip; emptyList() }).toList() }
        assertFalse("100.64.0.3" in seen, "a shared-in node is not searched by default: $seen")
    }

    /** F11. Only `&amp;` is decoded in a page title, so a name with an apostrophe or a dash entity is shown raw on its card. */
    @Test fun audit11_pageTitlesKeepTheirHtmlEntities() {
        assertEquals("Bob&#39;s NAS", ScanRules.title("<html><title>Bob&#39;s NAS</title>"))
        assertEquals("Printer &#8211; setup", ScanRules.title("<title>Printer &#8211; setup</title>"))
        assertEquals("Tom &quot;home&quot;", ScanRules.title("<title>Tom &quot;home&quot;</title>"))
        assertEquals("Q & A", ScanRules.title("<title>Q &amp; A</title>"))
    }

    /**
     * F13, inverted. The list used to be re-sorted by (kind, address) after every address, so a hit that arrived late was inserted among
     * the rows already shown. While a scan runs the hits now stay in the order they were found ([ScanRules.mergeArrival]); the sorted
     * merge is made once, when the scan ends (the engine's own tests follow a whole scan).
     */
    @Test fun audit13_aLateHitIsAddedBelowTheRowsAlreadyShownAndTheSortComesAtTheEnd() {
        fun pve(last: Int) = ScanHit("192.168.20.$last", 8006, true, ScanKind.PVE)
        val first = ScanRules.mergeArrival(emptyList(), listOf(pve(30), pve(22)))
        assertEquals(listOf("192.168.20.30", "192.168.20.22"), first.map { it.ip })                    // as found
        val arrival = ScanRules.mergeArrival(first, listOf(pve(21)))
        assertEquals(listOf("192.168.20.30", "192.168.20.22", "192.168.20.21"), arrival.map { it.ip })  // nothing already shown moved
        val later = ScanRules.merge(arrival)
        assertEquals(listOf("192.168.20.21", "192.168.20.22", "192.168.20.30"), later.map { it.ip })   // sorted once, at the end
        // A web page of the same host never shows beside its Proxmox card; the page lists one row per host.
        val withWeb = ScanRules.merge(later + ScanHit("192.168.20.22", 443, true, ScanKind.WEB))
        assertEquals(3, withWeb.size)
    }

    /**
     * F12, inverted. A web server that sends its whole answer and then keeps the connection open is found: the probe keeps what it
     * has read when its 1.5 s socket timeout ends the wait ([ServiceProbe] always did). Uses 127.0.0.1 and port 8123 (plain HTTP in
     * the scan); skipped when that port is taken.
     */
    @Test fun audit12_aServerThatAnswersButKeepsTheConnectionOpenIsFoundToo() = runBlocking {
        val port = 8123
        val body = "<html><head><title>Home Assistant</title></head></html>"
        val answer = ("HTTP/1.0 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${body.length}\r\n\r\n$body").toByteArray()
        suspend fun hitsWith(closeAfterAnswer: Boolean): List<ScanHit>? {
            val server = try { java.net.ServerSocket(port, 5, java.net.InetAddress.getByName("127.0.0.1")) } catch (e: java.io.IOException) { return null }
            val held = mutableListOf<java.net.Socket>()
            val acceptor = Thread {
                try { while (true) { val s = server.accept(); s.getInputStream().read(ByteArray(512)); s.getOutputStream().apply { write(answer); flush() }
                    if (closeAfterAnswer) s.close() else synchronized(held) { held += s } } } catch (_: java.io.IOException) {}
            }.apply { isDaemon = true; start() }
            try {
                val plan = NetworkPlan(listOf(ScanNetwork(ScanInterface("lo", "127.0.0.1", 32), null, null, listOf("127.0.0.1"))))
                return NetworkScanner().scan(plan, ports = listOf(port)).toList().last().hits
            } finally { server.close(); synchronized(held) { held.forEach { runCatching { it.close() } } }; acceptor.join(2000) }
        }
        val closing = hitsWith(closeAfterAnswer = true) ?: return@runBlocking          // port taken: nothing to prove here
        assertEquals(listOf(ScanKind.HOME_ASSISTANT), closing.map { it.kind })
        val keeping = hitsWith(closeAfterAnswer = false) ?: return@runBlocking
        assertEquals(listOf(ScanKind.HOME_ASSISTANT), keeping.map { it.kind })           // same answer, connection kept open: the same hit
    }

    // ------------------------------------------------------------------------------------------------------------
    // Opt-in live measurement: RM_SCAN_AUDIT_LIVE=1. Reads this computer's interfaces and sweeps the FIRST swept
    // network once with the production scanner, then once with a prototype two-step pass. It prints classes, counts and
    // seconds only, never an address, a host name or a fingerprint.
    // ------------------------------------------------------------------------------------------------------------

    @Test fun auditLive_measureOneSweepAndTheNeighbourTrick() {
        if (System.getenv("RM_SCAN_AUDIT_LIVE") != "1") return
        val windows = System.getProperty("os.name").startsWith("Windows", true)
        fun cls(ip: String): String { val n = ScanRules.ipv4(ip) ?: return "?"; return when {
            n ushr 24 == 10L -> "10/8"; n ushr 20 == 0xac1L -> "172.16/12"; n ushr 16 == 0xc0a8L -> "192.168/16"
            n ushr 22 == 0x191L -> "cgnat"; n ushr 24 == 127L -> "loopback"; n ushr 16 == 0xa9feL -> "link-local"; else -> "public" } }
        val started = System.nanoTime()
        val plan = NetworkScanner.interfaces()
        println("AUDIT-LIVE interfaces() took ${"%.1f".format((System.nanoTime() - started) / 1e6)} ms on the calling thread, ${plan.networks.size} IPv4 interface addresses")
        plan.networks.forEach { println("AUDIT-LIVE   ${cls(it.source.ip)} /${it.source.prefix} -> ${it.cidr?.let { "swept ${it.substringAfter('/')}" } ?: "skipped: ${it.reason}"}  [${it.source.name.take(60)}]") }
        val first = plan.networks.firstOrNull { it.cidr != null } ?: return println("AUDIT-LIVE nothing to sweep")
        val one = NetworkPlan(listOf(first))

        // 1. The production scanner, defaults, one network. RM_SCAN_AUDIT_SKIP_PRODUCTION=1 leaves it out of a repeat run.
        if (System.getenv("RM_SCAN_AUDIT_SKIP_PRODUCTION") != "1") {
            val t0 = System.nanoTime()
            val last = runBlocking { NetworkScanner().scan(one).toList().last() }
            val seconds = (System.nanoTime() - t0) / 1e9
            println("AUDIT-LIVE production sweep: ${last.scanned}/${last.total} addresses in ${"%.1f".format(seconds)} s, hosts answering ${last.hits.map { it.ip }.distinct().size}, by kind ${last.hits.groupBy { it.kind }.mapValues { it.value.size }}")
        }

        // 2. Prototype two-step pass: one quick connect per address on 8006 with many workers (this also makes the operating
        //    system resolve every on-link address), read the neighbour table, then run the production probe on the live ones only.
        val table = { neighbourStates(windows, first.addresses) }
        val before = table()
        val total0 = System.nanoTime()
        val t1 = System.nanoTime()
        val outcome = quickConnectSweep(first.addresses, port = 8006, timeoutMs = 300, workers = 128)
        val quickSeconds = (System.nanoTime() - t1) / 1e9
        println("AUDIT-LIVE quick pass on 8006: ${first.addresses.size} addresses in ${"%.2f".format(quickSeconds)} s with 128 workers and 300 ms: $outcome")
        Thread.sleep(1500)                                    // let the operating system finish its address resolution
        val t2 = System.nanoTime()
        val after = table()
        println("AUDIT-LIVE neighbour table inside the network: before=${before.mapValues { it.value.size }} after=${after.mapValues { it.value.size }} (read took ${"%.0f".format((System.nanoTime() - t2) / 1e6)} ms)")
        val live = after.filterKeys { it in setOf("reachable", "stale", "delay", "probe") }.values.flatten().toSet()
        val livePlan = NetworkPlan(listOf(ScanNetwork(first.source, first.cidr, null, first.addresses.filter { it in live })))
        val t3 = System.nanoTime()
        val second = runBlocking { NetworkScanner().scan(livePlan).toList().last() }
        val probeSeconds = (System.nanoTime() - t3) / 1e9
        println("AUDIT-LIVE full-port probe of the ${live.size} live addresses only: ${"%.1f".format(probeSeconds)} s, hosts answering ${second.hits.map { it.ip }.distinct().size}, by kind ${second.hits.groupBy { it.kind }.mapValues { it.value.size }}")
        println("AUDIT-LIVE two-step total (quick pass + settle + table + probe): ${"%.1f".format((System.nanoTime() - total0) / 1e9)} s")

        // 3. How do live hosts answer a port nobody listens on? RST ("refused") is what makes a closed port cheap; a drop costs the whole wait.
        val answered = second.hits.map { it.ip }.toSet()
        val silent = live.filter { it !in answered }
        println("AUDIT-LIVE connect to 8006 (1500 ms) on ${silent.size} live addresses that showed no service: ${quickConnectSweep(silent, port = 8006, timeoutMs = 1500, workers = 16)}")
        println("AUDIT-LIVE connect to 8006 (1500 ms) on ${answered.size} addresses that answered some port: ${quickConnectSweep(answered.toList(), port = 8006, timeoutMs = 1500, workers = 16)}")
    }

    private fun quickConnectSweep(addresses: List<String>, port: Int, timeoutMs: Int, workers: Int): Map<String, Int> {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(workers)
        val counts = ConcurrentHashMap<String, AtomicInteger>()
        try {
            addresses.map { ip -> pool.submit {
                val result = try {
                    java.net.Socket().use { it.connect(java.net.InetSocketAddress(ip, port), timeoutMs) }; "open"
                } catch (e: java.net.ConnectException) { "refused (RST)" }
                catch (e: java.net.SocketTimeoutException) { "timeout" }
                catch (e: java.io.IOException) { "other:" + e.javaClass.simpleName }
                counts.getOrPut(result) { AtomicInteger() }.incrementAndGet()
            } }.forEach { it.get() }
        } finally { pool.shutdownNow() }
        return counts.mapValues { it.value.get() }.toSortedMap()
    }

    /**
     * Neighbour-table entries (ARP) for [addresses], grouped by lower-case state: reachable, stale, delay, probe, incomplete,
     * unreachable (Windows) or REACHABLE, STALE, DELAY, PROBE, INCOMPLETE, FAILED (Linux). Read without administrator rights.
     * The Windows script goes through -EncodedCommand, as the app's own helper does: a double quote in a ProcessBuilder
     * argument does not survive the Windows command line (the first version of this helper lost its quotes and read nothing).
     */
    private fun neighbourStates(windows: Boolean, addresses: List<String>): Map<String, List<String>> {
        val wanted = addresses.toSet()
        return try {
            val command = if (windows) {
                val script = "Get-NetNeighbor -AddressFamily IPv4 | ForEach-Object { \$_.IPAddress + ' ' + \$_.State }"
                listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand",
                    java.util.Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE)))
            } else listOf("ip", "-4", "neigh", "show")
            val p = ProcessBuilder(command).redirectErrorStream(true).start()
            val lines = p.inputStream.bufferedReader().readLines(); p.waitFor()
            val inside = lines.mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                val ip = parts.firstOrNull() ?: return@mapNotNull null
                if (ip !in wanted) return@mapNotNull null
                (if (windows) parts.getOrNull(1) ?: "?" else parts.lastOrNull() ?: "?").lowercase() to ip
            }
            inside.groupBy({ it.first }, { it.second }).toSortedMap()
        } catch (e: Exception) { mapOf("unavailable:${e.javaClass.simpleName}" to emptyList()) }
    }
}
