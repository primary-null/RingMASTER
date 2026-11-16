package local.ringleader.core

import kotlin.test.*

/**
 * The scan plan: which networks are offered, which are ticked, and why. Private ranges (10.20.x.x and the like) appear only where
 * the rule under test is about private space; everything else a person could see is a documentation address.
 */
class ScanPlanTest {
    private fun card(name: String, ip: String, prefix: Int, index: Int? = null, device: String? = null, up: Boolean = true) = ScanInterface(name, ip, prefix, up, index, device)
    private fun adapter(index: Int?, name: String, virtual: Boolean?, hardware: Boolean?, category: String? = null, description: String? = null) = AdapterFact(index, name, description, virtual, hardware, category)
    private fun route(cidr: String, hop: String? = null, index: Int? = null, name: String? = null) = parseCidr(cidr)!!.let { (n, p) -> RouteEntry(n, p, hop, index, name) }
    private fun facts(adapters: List<AdapterFact>, vararg routes: RouteEntry) = NetworkFacts(adapters, routes.toList(), "test")
    private fun ScanPlanView.row(id: String) = rows.single { it.id == id }
    private val ethernet = card("ethernet_32772 Intel(R) Ethernet Controller I226-V", "10.20.30.7", 24, index = 7)
    private val ethernetFacts = adapter(7, "Ethernet", false, true)

    // ---- attached networks -------------------------------------------------------------------------------------------------

    @Test fun anAttachedPrivateNetworkIsOnByDefaultWithItsRealSize() {
        val view = ScanPlanner.plan(listOf(ethernet))
        val row = view.rows.single()
        assertEquals("10.20.30.0/24", row.id); assertEquals("10.20.30.0/24", row.sweep); assertEquals("10.20.30.0/24", row.real)
        assertEquals(254, row.count); assertEquals(254, row.realCount); assertEquals(24, row.realPrefix)
        assertTrue(row.on && row.defaultOn && row.swept && row.onLink); assertEquals(PlanSource.Attached, row.source); assertNull(row.note); assertNull(row.reason)
        assertEquals("Intel(R) Ethernet Controller I226-V", row.label, "the Java 21 name `ethernet_32772` is not shown")
        assertEquals("10.20.30.1", row.addresses.first()); assertEquals("10.20.30.254", row.addresses.last())
        assertEquals(254, view.addressCount); assertTrue(view.estimateSeconds > 0)
    }

    /** Audit F1, inverted: a /22 or /16 the computer sits in is no longer reported as if the /24 around it were the network. */
    @Test fun aNetworkWiderThanSlash24IsCutToTheSlash24AroundTheCardAndSaysSo() {
        val wide = ScanPlanner.plan(listOf(card("Ethernet", "10.20.5.7", 22))).rows
        assertEquals(listOf("10.20.5.0/24", "10.20.4.0/22"), wide.map { it.id })
        val main = wide[0]
        assertEquals("10.20.4.0/22", main.real); assertEquals(22, main.realPrefix); assertEquals(1022, main.realCount); assertEquals(254, main.count)
        assertEquals("the /24 around 10.20.5.7, of a /22 (1,022 addresses)", main.note); assertTrue(main.on)
        for (outside in listOf("10.20.4.1", "10.20.6.1", "10.20.7.254")) assertFalse(outside in main.addresses, outside)
        val sixteen = ScanPlanner.plan(listOf(card("Ethernet", "10.20.5.7", 16))).rows
        assertEquals("10.20.0.0/16", sixteen[0].real); assertEquals(65534, sixteen[0].realCount); assertEquals(254, sixteen[0].count)
        assertEquals("the /24 around 10.20.5.7, of a /16 (65,534 addresses)", sixteen[0].note)
        assertEquals("10.20.4.0/22", sixteen[1].id, "the wider choice is a /22 at most")
        val twentyThree = ScanPlanner.plan(listOf(card("Ethernet", "10.20.5.7", 23))).rows
        assertEquals(listOf("10.20.5.0/24", "10.20.4.0/23"), twentyThree.map { it.id }); assertEquals(510, twentyThree[1].count)
    }

    @Test fun theWiderChoiceIsOffByDefaultAndSweepsTheWholeNetworkWhenTicked() {
        val cards = listOf(card("Ethernet", "10.20.5.7", 22))
        val off = ScanPlanner.plan(cards)
        val wider = off.row("10.20.4.0/22")
        assertTrue(wider.wider && !wider.on && !wider.defaultOn && wider.choosable); assertEquals(1022, wider.count)
        assertEquals("the whole network (1,022 addresses)", wider.note)
        assertEquals(254, off.addressCount)
        val on = ScanPlanner.plan(cards, choices = mapOf("10.20.4.0/22" to true))
        assertTrue(on.row("10.20.4.0/22").swept)
        assertEquals("10.20.4.0/22", on.row("10.20.5.0/24").coveredBy, "the /24 is part of the ticked /22, so it is not swept twice")
        assertFalse(on.row("10.20.5.0/24").swept)
        assertEquals(1022, on.addressCount); assertEquals(1022, on.toNetworkPlan().addresses.size)
    }

    @Test fun aNetworkOnACardWindowsCallsPublicIsListedOffUntilTicked() {
        val facts = facts(listOf(adapter(7, "Ethernet", false, true, category = "Public")))
        val view = ScanPlanner.plan(listOf(ethernet), facts)
        val row = view.rows.single()
        assertFalse(row.on); assertFalse(row.defaultOn); assertTrue(row.reason!!.contains("Public")); assertEquals(0, view.addressCount)
        assertTrue(ScanPlanner.plan(listOf(ethernet), facts, choices = mapOf("10.20.30.0/24" to true)).rows.single().swept)
        val private = facts(listOf(adapter(7, "Ethernet", false, true, category = "Private")))
        assertTrue(ScanPlanner.plan(listOf(ethernet), private).rows.single().on)
    }

    @Test fun cardsAreSkippedWithAReasonAndAStoredTickCannotSweepThem() {
        fun reason(c: ScanInterface) = ScanPlanner.plan(listOf(c)).rows.single().reason
        assertEquals("Interface down", reason(card("Ethernet", "10.20.30.7", 24, up = false)))
        assertEquals("Invalid IPv4", reason(card("eth0", "fd00::5", 64)))
        assertEquals("Point-to-point or invalid prefix", reason(card("wg0", "10.8.0.2", 32)))
        assertEquals("Point-to-point or invalid prefix", reason(card("tun0", "10.8.0.2", 31)))
        assertEquals("Loopback", reason(card("lo", "127.0.0.1", 8)))
        assertEquals("Link-local", reason(card("Ethernet", "169.254.1.2", 16)))
        assertEquals("Not private space", reason(card("Ethernet", "203.0.113.9", 24)))
        assertEquals("Tailscale / CGNAT space is never swept", reason(card("Tailscale", "100.101.102.103", 32)))
        for (c in listOf(card("Ethernet", "10.20.30.7", 24, up = false), card("lo", "127.0.0.1", 8), card("Ethernet", "203.0.113.9", 24))) {
            val row = ScanPlanner.plan(listOf(c)).rows.single()
            assertFalse(row.sweepable); assertTrue(row.addresses.isEmpty()); assertFalse(row.swept); assertFalse(row.choosable)
            // Even a stored "on" cannot sweep what cannot be swept.
            assertFalse(ScanPlanner.plan(listOf(c), choices = mapOf(row.id to true)).rows.single().swept)
        }
    }

    // ---- virtual adapters: facts first, the name rule only as a fallback -----------------------------------------------------

    /** Audit F2, inverted: a Hyper-V external switch (or a Hyper-V guest) keeps the real LAN on a "virtual" adapter, and it owns the default route. */
    @Test fun aVirtualAdapterThatCarriesTheDefaultRouteIsTheRealLanAndIsSwept() {
        val external = card("ethernet_32775 Hyper-V Virtual Ethernet Adapter", "192.168.20.5", 24, index = 12)
        val facts = facts(listOf(adapter(12, "vEthernet (External)", true, false)), route("0.0.0.0/0", "192.168.20.1", 12), route("192.168.20.0/24", null, 12))
        val row = ScanPlanner.plan(listOf(external), facts).rows.single()
        assertTrue(row.swept, row.reason); assertEquals("vEthernet (External)", row.label)
        // The same is true of the only card of a Hyper-V or Azure guest.
        val guest = card("ethernet_32768 Microsoft Hyper-V Network Adapter", "192.168.20.5", 24, index = 5)
        assertTrue(ScanPlanner.plan(listOf(guest), facts(listOf(adapter(5, "Ethernet", true, false)), route("0.0.0.0/0", "192.168.20.1", 5))).rows.single().swept)
    }

    @Test fun aVirtualAdapterWithoutTheDefaultRouteIsSkippedAsAnotherMachinesNetwork() {
        val nat = card("ethernet_32775 Hyper-V Virtual Ethernet Adapter", "172.17.80.1", 20, index = 23)
        val real = card("Ethernet", "10.20.30.7", 24, index = 7)
        val facts = facts(listOf(adapter(23, "vEthernet (Default Switch)", true, false), adapter(7, "Ethernet", false, true)),
            route("0.0.0.0/0", "10.20.30.1", 7), route("172.17.80.0/20", null, 23))
        val view = ScanPlanner.plan(listOf(nat, real), facts)
        val skipped = view.rows.single { !it.sweepable }
        assertEquals("Virtual adapter that does not carry the default route", skipped.reason); assertEquals("vEthernet (Default Switch)", skipped.label)
        assertEquals(listOf("10.20.30.0/24"), view.swept.map { it.id })
    }

    @Test fun linuxBridgesAndContainerNetworksAreSkippedUnlessTheyCarryTheDefaultRoute() {
        val devices = listOf(adapter(null, "eth0", false, true), adapter(null, "docker0", true, false), adapter(null, "virbr0", true, false), adapter(null, "vmbr0", true, false), adapter(null, "wg0", true, false))
        val facts = facts(devices, route("0.0.0.0/0", "10.20.30.1", name = "vmbr0"))
        fun swept(dev: String, ip: String) = ScanPlanner.plan(listOf(card("$dev $dev", ip, 24, device = dev)), facts).rows.single().swept
        assertTrue(swept("vmbr0", "10.20.30.7"), "a Proxmox host's bridge carries the default route")
        assertTrue(swept("eth0", "10.20.31.7"), "real hardware is swept even without the default route")
        for (dev in listOf("docker0", "virbr0", "wg0")) assertFalse(swept(dev, "172.18.0.1"), dev)
    }

    @Test fun withoutTheSystemsFactsTheNameRuleIsTheFallback() {
        for (name in listOf("ethernet_32775 Hyper-V Virtual Ethernet Adapter", "ethernet_32777 Hyper-V Virtual Ethernet Adapter #2", "ethernet_32768 Microsoft Hyper-V Network Adapter", "docker0 docker0", "virbr0 virbr0")) {
            val row = ScanPlanner.plan(listOf(card(name, "192.168.20.5", 24, index = 12))).rows.single()
            assertEquals("Virtual-switch adapter", row.reason, name); assertFalse(row.swept)
        }
        for (name in listOf("eth0 eth0", "br0 br0", "vmbr0 vmbr0", "enp3s0 enp3s0", "Wi-Fi Intel(R) Wi-Fi 6E AX211 160MHz")) assertTrue(ScanPlanner.plan(listOf(card(name, "192.168.20.5", 24))).rows.single().swept, name)
        // Facts that do not list the card, or do not say whether it is virtual, leave the name rule in charge for that card.
        val unlisted = facts(listOf(adapter(99, "Other", false, true)))
        assertEquals("Virtual-switch adapter", ScanPlanner.plan(listOf(card("ethernet_1 Hyper-V Virtual Ethernet Adapter", "192.168.20.5", 24, index = 12)), unlisted).rows.single().reason)
        val unknown = facts(listOf(adapter(12, "vEthernet", null, null)))
        assertEquals("Virtual-switch adapter", ScanPlanner.plan(listOf(card("ethernet_1 Hyper-V Virtual Ethernet Adapter", "192.168.20.5", 24, index = 12)), unknown).rows.single().reason)
    }

    @Test fun cardLabelsAreWhatAPersonRecognises() {
        fun label(name: String, fact: AdapterFact? = null) = ScanPlanner.cardLabel(card(name, "10.20.30.7", 24), fact)
        assertEquals("Intel(R) Ethernet Controller I226-V", label("ethernet_32772 Intel(R) Ethernet Controller I226-V"))
        assertEquals("MediaTek Wi-Fi 6E MT7922 160MHz Wireless LAN", label("wireless_32768 MediaTek Wi-Fi 6E MT7922 160MHz Wireless LAN "))
        assertEquals("Tailscale Tunnel", label("iftype53_32768 Tailscale Tunnel"))
        assertEquals("eth0", label("eth0 eth0")); assertEquals("eth0", label("eth0"))
        assertEquals("br0 Bridge", label("br0 Bridge"))
        assertEquals("Wi-Fi 2", label("wireless_1 Intel(R) Wi-Fi", adapter(3, "Wi-Fi 2", false, true)))
        assertEquals("10.20.30.7", label(""))
    }

    // ---- routes and tailnet ------------------------------------------------------------------------------------------------

    private val tailnetFacts = facts(listOf(adapter(7, "Ethernet", false, true), adapter(15, "Tailscale", true, false, description = "Tailscale Tunnel")),
        route("0.0.0.0/0", "10.20.30.1", 7),
        route("10.20.30.0/24", null, 7),                       // this computer's own network
        route("10.20.31.0/24", "10.20.30.254", 7),             // a static route
        route("10.30.0.0/16", "100.100.100.100", 15),          // a tailnet route that is too wide
        route("10.31.0.0/24", "100.100.100.100", 15),          // a tailnet route
        route("10.32.0.0/22", "100.100.100.100", 15),          // a tailnet /22
        route("203.0.113.0/24", "10.20.30.1", 7),              // public destination
        route("100.64.0.0/10", null, 15), route("100.64.0.2/32", null, 15), route("224.0.0.0/4", null, 7), route("127.0.0.0/8", null, 7),
        route("169.254.0.0/16", null, 7), route("10.20.30.9/32", "10.20.30.1", 7), route("10.33.0.0/31", "10.20.30.1", 7), route("255.255.255.255/32", null, 7))

    @Test fun routedPrivateNetworksAreOfferedAndTheTailnetOnesAreOn() {
        val found = routeNetworks(tailnetFacts, listOf(ethernet))
        assertEquals(listOf("10.31.0.0/24", "10.32.0.0/22", "10.30.0.0/16", "10.20.31.0/24"), found.networks.map { it.cidr }, "tailnet first, then the most specific")
        assertEquals(0, found.leftOut)
        assertTrue(found.networks.take(3).all { it.source == PlanSource.Tailnet && it.defaultOn }); assertEquals(PlanSource.Route, found.networks.last().source)
        val view = ScanPlanner.plan(listOf(ethernet), tailnetFacts, found.networks)
        assertEquals(listOf("10.20.30.0/24", "10.31.0.0/24", "10.32.0.0/22"), view.swept.map { it.id }, "the tailnet's subnets are swept without a click")
        val static = view.row("10.20.31.0/24")
        assertFalse(static.on); assertEquals(PlanSource.Route, static.source); assertEquals("Route via 10.20.30.254 on Ethernet", static.label); assertTrue(static.reason!!.contains("VPN"))
        val tooWide = view.rows.single { !it.sweepable && it.real == "10.30.0.0/16" }
        assertTrue(tooWide.reason!!.startsWith("Too wide to sweep as a whole (65,534 addresses)"), tooWide.reason)
        assertEquals(1022, view.row("10.32.0.0/22").count)
        // The person can tick the static route; it is then swept like the others and remembered by its network.
        assertTrue(ScanPlanner.plan(listOf(ethernet), tailnetFacts, found.networks, choices = mapOf("10.20.31.0/24" to true)).row("10.20.31.0/24").swept)
    }

    @Test fun routesThatAreNotNetworksToSweepAreNotListed() {
        val cidrs = routeNetworks(tailnetFacts, listOf(ethernet)).networks.map { it.cidr }
        for (never in listOf("0.0.0.0/0", "10.20.30.0/24", "203.0.113.0/24", "100.64.0.0/10", "100.64.0.2/32", "224.0.0.0/4", "127.0.0.0/8", "169.254.0.0/16", "10.20.30.9/32", "10.33.0.0/31", "255.255.255.255/32"))
            assertFalse(never in cidrs, never)
    }

    @Test fun onlyTwelveRoutesAreListedAndTheRestAreCounted() {
        val routes = (1..15).map { route("10.${40 + it}.0.0/24", "10.20.30.254", 7) }
        val found = routeNetworks(facts(listOf(ethernetFacts), *routes.toTypedArray()), listOf(ethernet))
        assertEquals(12, found.networks.size); assertEquals(3, found.leftOut)
        assertEquals(3, ScanPlanner.plan(listOf(ethernet), null, found.networks, routesLeftOut = found.leftOut).routesLeftOut)
        // Tailnet routes come first even when there are more routes than room.
        val mixed = facts(listOf(ethernetFacts, adapter(15, "Tailscale", true, false)), *(routes + route("10.99.0.0/24", "100.100.100.100", 15)).toTypedArray())
        assertEquals("10.99.0.0/24", routeNetworks(mixed, listOf(ethernet)).networks.first().cidr)
    }

    @Test fun aTailscaleRouteOnLinuxIsRecognisedByItsInterface() {
        val facts = facts(listOf(adapter(null, "eth0", false, true), adapter(null, "tailscale0", true, false)), route("0.0.0.0/0", "10.20.30.1", name = "eth0"), route("10.31.0.0/24", null, name = "tailscale0"))
        val net = routeNetworks(facts, listOf(card("eth0 eth0", "10.20.30.7", 24, device = "eth0"))).networks.single()
        assertEquals(PlanSource.Tailnet, net.source); assertTrue(net.defaultOn)
        // Even when the device list could not be read, the interface name tells.
        val bare = NetworkFacts(emptyList(), facts.routes, "Linux")
        assertEquals(PlanSource.Tailnet, routeNetworks(bare, emptyList()).networks.single().source)
    }

    // ---- known servers -----------------------------------------------------------------------------------------------------

    @Test fun theNetworkAroundEachKnownServerIsOnWhenPrivate() {
        val known = listOf(KnownServer("10.20.30.44", "pve-east", PlanSource.Saved), KnownServer("10.20.40.5", "pve-west", PlanSource.Session), KnownServer("10.20.50.9", "pve-lab", PlanSource.Earlier),
            KnownServer("203.0.113.9", "hosted", PlanSource.Saved), KnownServer("100.64.0.9", "tail", PlanSource.Saved), KnownServer("127.0.0.1", "local", PlanSource.Earlier), KnownServer("pve.example.test", "by name", PlanSource.Saved))
        val view = ScanPlanner.plan(listOf(ethernet), null, knownServerNetworks(known))
        assertEquals(listOf("10.20.30.0/24", "10.20.40.0/24", "10.20.50.0/24"), view.swept.map { it.id })
        // The server's /24 is the card's own /24: one row, with both sources named.
        val own = view.row("10.20.30.0/24")
        assertEquals(PlanSource.Attached, own.source); assertEquals(listOf("Saved connection pve-east"), own.alsoFrom)
        assertEquals("Open connection pve-west", view.row("10.20.40.0/24").label); assertEquals("the /24 around 10.20.40.5", view.row("10.20.40.0/24").note)
        assertEquals("Earlier result pve-lab", view.row("10.20.50.0/24").label)
        // A hosted server is named and not swept; the tailnet's own space never is; loopback and names have no network to take.
        val skipped = view.rows.filter { !it.sweepable }
        assertEquals(setOf("Not private space", "Tailscale / CGNAT space is never swept. Search your tailnet from the VPN page."), skipped.map { it.reason }.toSet())
        assertEquals(5, view.rows.size, "the card's own /24 with the server merged in, two more on, two named and not swept")
    }

    @Test fun aServerInsideATickedWiderNetworkIsCoveredByIt() {
        val known = knownServerNetworks(listOf(KnownServer("10.20.6.9", "pve-east", PlanSource.Saved)))
        val cards = listOf(card("Ethernet", "10.20.5.7", 22))
        assertEquals(setOf("10.20.5.0/24", "10.20.6.0/24"), ScanPlanner.plan(cards, null, known).swept.map { it.id }.toSet())
        val whole = ScanPlanner.plan(cards, null, known, choices = mapOf("10.20.4.0/22" to true))
        assertEquals("10.20.4.0/22", whole.row("10.20.6.0/24").coveredBy); assertEquals(listOf("10.20.4.0/22"), whole.swept.map { it.id })
    }

    @Test fun originsGiveTheirAddressOnlyWhenTheyAreOne() {
        assertEquals("10.20.30.44", ipv4HostOf("https://10.20.30.44:8006")); assertEquals("10.20.30.44", ipv4HostOf(" https://10.20.30.44 "))
        for (origin in listOf("https://pve.example.test:8006", "https://[fd00::10]:8006", "https://+10.20.30.44:8006", "https://10.20.30:8006", "", "not an origin")) assertNull(ipv4HostOf(origin), origin)
    }

    // ---- the typed network -------------------------------------------------------------------------------------------------

    private fun typedRows(text: String) = ScanPlanner.plan(emptyList(), typed = text).rows.also { assertTrue(it.isNotEmpty(), text) }

    /** Audit F3, inverted: a /32 is that one host, a /31 its pair, and the page can say so. */
    @Test fun aTypedSingleHostIsThatHostAndNeighboursAreNotPulledIn() {
        val host = typedRows("10.20.30.7/32").single()
        assertEquals(listOf("10.20.30.7"), host.addresses); assertEquals("10.20.30.7/32", host.sweep); assertEquals("one address", host.note); assertTrue(host.on)
        assertEquals(listOf("10.20.30.6", "10.20.30.7"), typedRows("10.20.30.7/31").single().addresses)
        assertEquals(listOf("10.20.30.5", "10.20.30.6"), typedRows("10.20.30.7/30").single().addresses, "a /30 is its two hosts")
        assertEquals(254, typedRows("10.20.30.7/24").single().count); assertEquals("10.20.30.0/24", typedRows("10.20.30.7/24").single().sweep)
        assertEquals("10.50.1.0/24", typedRows("10.50.1.5").single().sweep, "no mask means /24")
        assertEquals(510, typedRows("10.20.5.7/23").single().count); assertEquals(1022, typedRows("10.20.5.7/22").single().count)
        assertEquals("10.20.4.0/22", typedRows("10.20.5.7/22").single().sweep); assertNull(typedRows("10.20.5.7/22").single().note)
    }

    @Test fun aTypedNetworkWiderThanSlash22IsCutAndTheSlash22IsOnlyOffered() {
        val rows = typedRows("10.20.5.7/16")
        assertEquals(listOf("10.20.5.0/24", "10.20.4.0/22"), rows.map { it.id })
        assertEquals("the /24 around 10.20.5.7, of the /16 you typed (65,534 addresses)", rows[0].note); assertEquals("10.20.0.0/16", rows[0].real)
        assertTrue(rows[0].on && !rows[1].defaultOn && rows[1].wider)
        val view = ScanPlanner.plan(emptyList(), typed = "10.20.5.7/16")
        assertEquals(254, view.addressCount)
        assertEquals(1022, ScanPlanner.plan(emptyList(), typed = "10.20.5.7/16", choices = mapOf("10.20.4.0/22" to true)).addressCount)
    }

    @Test fun aTypedNetworkThatIsNotPrivateIsSweptOnTheProxmoxPortsAndAtMostASlash24() {
        val row = typedRows("203.0.113.0/24").single()
        assertEquals(PROXMOX_PORTS, row.ports); assertTrue(row.on); assertEquals(254, row.count); assertTrue(row.reason!!.contains("Not private space"))
        val wide = typedRows("203.0.113.9/16")
        assertEquals(1, wide.size, "there is no wider choice outside private space"); assertEquals("203.0.113.0/24", wide.single().sweep)
        assertEquals(listOf("203.0.113.7"), typedRows("203.0.113.7/32").single().addresses)
        assertEquals(PROXMOX_PORTS, typedRows("169.254.10.0/24").single().ports, "link-local space is typed the same way")
        assertEquals(LAN_SCAN_PORTS, typedRows("10.20.30.0/24").single().ports)
        val plan = ScanPlanner.plan(emptyList(), typed = "203.0.113.0/24").toNetworkPlan()
        assertEquals(PROXMOX_PORTS, plan.networks.single().ports)
    }

    @Test fun whatCannotBeATypedNetworkIsRefusedWithAReason() {
        for (text in listOf("100.64.0.0/24", "100.100.100.1/32", "100.127.255.1", "127.0.0.1", "0.0.0.0/0", "224.0.0.1", "239.1.1.1/32", "255.255.255.255/32", "240.0.0.1"))
            assertTrue(ScanTyped.parse(text) is TypedNetwork.Rejected, text)
        assertTrue((ScanTyped.parse("100.64.0.0/24") as TypedNetwork.Rejected).reason.contains("never swept"))
        assertTrue((ScanTyped.parse("fd00::/64") as TypedNetwork.Rejected).reason.contains("IPv6"))
        for (text in listOf("abc", "10.20.30", "10.20.30.0/abc", "10.20.30.0/33", "10.20.30.0/", "+10.20.30.0/24", "10.20.30.0/+24", "10.20.30.0 /24", "٣.0.0.1/8", "10.20.30.0/٢٤", "10.20.30.256", "10.20.30.0/24/8"))
            assertTrue(ScanTyped.parse(text) is TypedNetwork.Rejected, text)
        assertTrue(ScanTyped.parse("   ") === TypedNetwork.None && ScanTyped.parse("") === TypedNetwork.None)
        assertEquals("", ScanPlanner.plan(emptyList(), typed = "10.20.30.0/24").typedProblem)
        assertTrue(ScanPlanner.plan(emptyList(), typed = "abc").typedProblem.startsWith("Not an IPv4 network"))
        assertEquals(0, ScanPlanner.plan(emptyList(), typed = "abc").rows.size)
    }

    @Test fun aTypedNetworkIsAlwaysOnAndCannotBeUntickedOrRemembered() {
        val rows = ScanPlanner.plan(listOf(ethernet), typed = "10.20.30.0/24", choices = mapOf("10.20.30.0/24" to false)).rows
        val row = rows.single()
        assertEquals(PlanSource.Typed, row.source); assertTrue(row.on); assertFalse(row.choosable); assertNull(row.remembered)
        assertEquals(listOf("Intel(R) Ethernet Controller I226-V"), row.alsoFrom, "the card that is on the same network is named")
        assertEquals(1, rows.size)
    }

    @Test fun theLegacyTypedSubnetFunctionFollowsTheSameRules() {
        val host = ScanRules.planSubnet("192.168.20.7/32")!!
        assertEquals("192.168.20.7/32", host.cidr); assertEquals(listOf("192.168.20.7"), host.addresses)
        assertEquals(listOf("192.168.20.6", "192.168.20.7"), ScanRules.planSubnet("192.168.20.7/31")!!.addresses)
        assertEquals(PROXMOX_PORTS, ScanRules.planSubnet("198.51.100.0/24")!!.ports); assertEquals(PROXMOX_PORTS, ScanRules.planSubnet("8.8.8.0/24")!!.ports)
        assertNotNull(ScanRules.planSubnet("169.254.10.0/24"))
        for (text in listOf("100.64.0.0/24", "100.100.100.0/24", "fd00::/64", "invalid", "192.168.20.0/abc", "192.168.20.0/33", "192.168.20.0/")) assertNull(ScanRules.planSubnet(text), text)
        assertFalse(ScanRules.subnetNarrowed("192.168.20.7/32")); assertFalse(ScanRules.subnetNarrowed("192.168.20.0/22")); assertTrue(ScanRules.subnetNarrowed("172.16.0.0/16"))
        assertTrue(ScanRules.subnetNarrowed("203.0.113.0/16")); assertFalse(ScanRules.subnetNarrowed("203.0.113.0/24"))
    }

    // ---- limits and remembered choices -------------------------------------------------------------------------------------

    private fun routedSlash22(n: Int) = ReachableNetwork("10.40.${4 * n}.0/22", PlanSource.Tailnet, "Tailscale route $n", defaultOn = true)

    @Test fun noScanSweepsMoreThan4096Addresses() {
        val view = ScanPlanner.plan(emptyList(), reachable = (0..4).map(::routedSlash22))
        assertEquals(4 * 1022, view.addressCount)
        val over = view.rows.last()
        assertTrue(over.on && over.limited && !over.swept); assertTrue(over.reason!!.contains("4,096"), over.reason)
        assertEquals(4, view.swept.size); assertTrue(view.addressCount <= MAX_SCAN_ADDRESSES)
        assertEquals(4 * 1022, view.toNetworkPlan().addresses.size)
        // Unticking one frees room, and the left-out network is swept; nothing the person ticked is dropped silently.
        val freed = ScanPlanner.plan(emptyList(), reachable = (0..4).map(::routedSlash22), choices = mapOf("10.40.0.0/22" to false))
        assertEquals(setOf("10.40.4.0/22", "10.40.8.0/22", "10.40.12.0/22", "10.40.16.0/22"), freed.swept.map { it.id }.toSet())
        val small = ScanPlanner.plan(emptyList(), reachable = (0 until 17).map { ReachableNetwork("10.50.$it.0/24", PlanSource.Saved, "s$it", defaultOn = true) })
        assertEquals(16, small.swept.size); assertEquals(16 * 254, small.addressCount); assertTrue(small.rows.last().limited)
    }

    @Test fun aRememberedChoiceBeatsTheDefaultInBothDirections() {
        val reachable = listOf(ReachableNetwork("10.40.0.0/24", PlanSource.Tailnet, "Tailscale route", defaultOn = true), ReachableNetwork("10.41.0.0/24", PlanSource.Route, "Route", defaultOn = false, reason = "A route added by a VPN or by hand. Tick it to scan it."))
        val none = ScanPlanner.plan(emptyList(), reachable = reachable)
        assertEquals(listOf("10.40.0.0/24"), none.swept.map { it.id }); assertNull(none.row("10.40.0.0/24").remembered)
        val chosen = ScanPlanner.plan(emptyList(), reachable = reachable, choices = mapOf("10.40.0.0/24" to false, "10.41.0.0/24" to true))
        assertEquals(listOf("10.41.0.0/24"), chosen.swept.map { it.id })
        assertEquals(false, chosen.row("10.40.0.0/24").remembered); assertEquals(true, chosen.row("10.41.0.0/24").remembered)
        // A choice for a network that is not on the plan any more is ignored, and does not make one appear.
        assertEquals(none.rows, ScanPlanner.plan(emptyList(), reachable = reachable, choices = mapOf("10.99.0.0/24" to true)).rows)
    }

    @Test fun theScannerGetsExactlyWhatIsTickedAndWhatCannotBeSwept() {
        val view = ScanPlanner.plan(listOf(ethernet, card("lo", "127.0.0.1", 8)), reachable = listOf(ReachableNetwork("10.41.0.0/24", PlanSource.Route, "Route", defaultOn = false)), typed = "203.0.113.7/32")
        val plan = view.toNetworkPlan()
        val swept = plan.networks.filter { it.cidr != null }
        assertEquals(listOf("203.0.113.7/32", "10.20.30.0/24"), swept.map { it.cidr })
        assertEquals(PROXMOX_PORTS, swept[0].ports); assertEquals(LAN_SCAN_PORTS, swept[1].ports); assertEquals(PlanSource.Typed, swept[0].origin); assertTrue(swept[1].onLink); assertFalse(swept[0].onLink)
        assertEquals("10.20.30.0/24", swept[1].realCidr)
        assertEquals(listOf("Loopback"), plan.networks.filter { it.cidr == null }.map { it.reason })
        assertEquals(255, plan.addresses.size)
    }

    @Test fun rowsComeInTheOrderAPersonReadsThem() {
        val view = ScanPlanner.plan(listOf(card("lo", "127.0.0.1", 8), card("Ethernet", "10.20.5.7", 22, index = 7)), facts(listOf(ethernetFacts)),
            reachable = listOf(ReachableNetwork("10.41.0.0/24", PlanSource.Route, "Route", defaultOn = false), ReachableNetwork("10.40.0.0/24", PlanSource.Tailnet, "Tailscale route", defaultOn = true),
                ReachableNetwork("10.42.0.0/24", PlanSource.Earlier, "Earlier result", defaultOn = true), ReachableNetwork("10.43.0.0/24", PlanSource.Saved, "Saved connection", defaultOn = true),
                ReachableNetwork("203.0.113.0/24", PlanSource.Saved, "Saved connection hosted", defaultOn = true)),
            typed = "10.60.0.0/24")
        assertEquals(listOf(PlanSource.Typed, PlanSource.Attached, PlanSource.Attached, PlanSource.Tailnet, PlanSource.Saved, PlanSource.Earlier, PlanSource.Route, PlanSource.Attached, PlanSource.Saved),
            view.rows.map { it.source })
        assertTrue(view.rows.takeWhile { it.sweepable }.size == 7 && view.rows.drop(7).none { it.sweepable })
        assertEquals(2, view.skipped.size)
    }

    @Test fun theEstimateFollowsTheSizeAndSaysItInWords() {
        assertEquals(6, ScanEstimate.seconds(254, true, 7)); assertEquals(0, ScanEstimate.seconds(0, true, 7))
        assertEquals(12, ScanEstimate.seconds(1022, true, 7)); assertEquals(7, ScanEstimate.seconds(254, false, 3))
        assertEquals(8, ScanEstimate.total(ScanPlanner.plan(listOf(card("Ethernet", "10.20.30.7", 24), card("Wi-Fi", "10.20.31.7", 24))).rows), "two networks share the connects, and the settle is paid once")
        assertEquals("about 30 s", ScanEstimate.text(30)); assertEquals("about 2 min", ScanEstimate.text(120)); assertEquals("no time", ScanEstimate.text(0))
        assertEquals("1,022", 1022.thousands()); assertEquals("65,534", 65534.thousands())
    }

    @Test fun theLegacyPlanKeepsOneNetworkPerCardAndTheOldReasons() {
        val plan = ScanRules.plan(listOf(card("Ethernet", "10.0.0.8", 16), card("lo", "127.0.0.1", 8), card("Ethernet", "10.0.0.9", 16)))
        assertEquals(254, plan.addresses.size)
        assertEquals(listOf("10.0.0.0/24", null), plan.networks.map { it.cidr }); assertEquals(16, plan.networks[0].source.prefix)
        assertEquals("10.0.0.0/16", plan.networks[0].realCidr)
    }
}
