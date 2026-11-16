package local.ringleader.core

import kotlin.test.*

/**
 * "Why isn't it here?": for an address, which planned network would have contained it, or that none did and why. Every answer is
 * read from the plan and the last scan; nothing is sent. Addresses are private examples (the rules are about private space) and
 * documentation ranges where a public address is needed.
 */
class ScanExplainTest {
    private fun card(name: String, ip: String, prefix: Int, index: Int? = null) = ScanInterface(name, ip, prefix, true, index)
    private val lan = card("Ethernet", "10.20.30.7", 24)
    private val wide = card("Wi-Fi", "10.20.5.7", 22)
    private val campus = card("Campus", "10.40.9.7", 16)
    private val docker = card("docker0", "172.17.0.1", 16)
    private val tailscale = card("Tailscale", "100.101.102.103", 32)
    private val saved = ReachableNetwork("10.20.40.0/24", PlanSource.Saved, "Saved connection pve-west", anchor = "10.20.40.5", defaultOn = true)
    private val route = ReachableNetwork("10.60.0.0/24", PlanSource.Route, "Route via 10.20.30.1 on Ethernet", defaultOn = false, reason = "A route added by a VPN or by hand. Tick it to scan it.")
    private val farRoute = ReachableNetwork("10.128.0.0/9", PlanSource.Route, "Route via 10.20.30.254", defaultOn = false)

    private fun plan(typed: String = "", choices: Map<String, Boolean> = emptyMap(), problem: String? = null) =
        ScanPlanner.plan(listOf(lan, wide, campus, docker, tailscale), null, listOf(saved, route, farRoute), typed, choices, problem)
    private fun why(text: String, plan: ScanPlanView = plan(), scan: NetworkProgress? = null) = ScanExplain.explain(text, plan, scan)

    @Test fun whatIsTypedIsReadAsAnAddressOrRefusedInWords() {
        assertEquals("10.20.30.5", ScanExplain.addressIn(" 10.20.30.5 ")); assertEquals("10.20.30.5", ScanExplain.addressIn("10.20.30.5:8006"))
        assertEquals("10.20.30.5", ScanExplain.addressIn("https://10.20.30.5:8006/#v1")); assertNull(ScanExplain.addressIn("pve.example.test"))
        assertEquals("10.20.30.5", ScanExplain.addressIn("10.20.30.5/24"), "an address written with its prefix is still that address")
        for (bad in listOf("", "   ")) assertEquals(WhyKind.Invalid, why(bad).kind)
        assertTrue(why("pve.example.test").text.contains("host name is not looked up"))
        assertTrue(why("https://pve.example.test:8006").text.contains("not an IPv4 address"))
        for (v6 in listOf("2001:db8::5", "[2001:db8::5]:8006", "https://[2001:db8::5]:8006")) { assertEquals(WhyKind.Invalid, why(v6).kind, v6); assertTrue(why(v6).text.startsWith("IPv6 addresses are not scanned"), v6) }
        // Hostile and odd text is an answer in words, never a crash and never an address.
        for (odd in listOf("10.20.30", "10.20.30.256", "+10.20.30.5", "١٠.20.30.5", "10.20.30.5x/24", "a".repeat(5000), "\u0000", "://", "999999999999999999999")) assertEquals(WhyKind.Invalid, why(odd).kind, odd.take(30))
    }

    @Test fun anAddressInATickedNetworkIsSweptNextAndTheNetworkIsNamedWithItsSource() {
        val a = why("10.20.30.5")
        assertEquals(WhyKind.Swept, a.kind); assertEquals("It is inside 10.20.30.0/24 (Ethernet), which is ticked: the next scan asks it.", a.text)
        assertEquals("10.20.30.5", a.address); assertNull(a.tick); assertNull(a.typed)
        // The /24 around a saved server is a network of the plan like any other.
        assertEquals("It is inside 10.20.40.0/24 (Saved connection pve-west), which is ticked: the next scan asks it.", why("https://10.20.40.9:8006").text)
        // The network's own first and last address are not devices.
        assertEquals(WhyKind.Invalid, why("10.20.30.255").kind); assertTrue(why("10.20.30.0").text.contains("network or broadcast address of 10.20.30.0/24"))
    }

    @Test fun anUntickedNetworkIsNamedWithItsReasonAndOfferedForATick() {
        val off = why("10.60.0.9")
        assertEquals(WhyKind.Off, off.kind); assertEquals("10.60.0.0/24", off.tick)
        assertEquals("It is inside 10.60.0.0/24 (Route via 10.20.30.1 on Ethernet), which is not ticked: A route added by a VPN or by hand. Tick it to scan it. Tick it to include it in the next scan.", off.text)
        // A network the person switched off themselves has no reason of its own.
        val mine = why("10.20.30.5", plan(choices = mapOf("10.20.30.0/24" to false)))
        assertEquals(WhyKind.Off, mine.kind); assertEquals("10.20.30.0/24", mine.tick)
        assertEquals("It is inside 10.20.30.0/24 (Ethernet), which is not ticked. Tick it to include it in the next scan.", mine.text)
        // Ticking it is the whole fix.
        assertEquals(WhyKind.Swept, why("10.60.0.9", plan(choices = mapOf("10.60.0.0/24" to true))).kind)
    }

    @Test fun anAddressInTheRestOfAWiderNetworkPointsAtTheWiderChoice() {
        // The card is 10.20.5.7/22: the /24 around it is swept, 10.20.6.x is the same network and is not.
        val cut = why("10.20.6.9")
        assertEquals(WhyKind.Cut, cut.kind); assertEquals("10.20.4.0/22", cut.tick)
        assertEquals("It is inside 10.20.4.0/22 (Wi-Fi). Only 10.20.5.0/24 is swept of it, and this address is outside that part. The wider choice, 10.20.4.0/22 with 1,022 addresses, includes it: tick \"Whole network\".", cut.text)
        assertEquals(WhyKind.Swept, why("10.20.6.9", plan(choices = mapOf("10.20.4.0/22" to true))).kind)
        // With the /24 switched off too, the /24 speaks for its own addresses and the wider choice for the rest.
        val bothOff = plan(choices = mapOf("10.20.5.0/24" to false))
        assertEquals(WhyKind.Off, why("10.20.5.9", bothOff).kind); assertEquals("10.20.5.0/24", why("10.20.5.9", bothOff).tick)
        assertTrue(why("10.20.6.9", bothOff).text.contains("It is not ticked."))
        // A /16: the widest sweep is the /22 around the card, and an address beyond it can only be typed.
        val beyond = why("10.40.200.9")
        assertEquals(WhyKind.Cut, beyond.kind); assertNull(beyond.tick); assertEquals("10.40.200.0/24", beyond.typed)
        assertTrue(beyond.text.startsWith("It is inside 10.40.0.0/16 (Campus), but a scan sweeps at most a /22 of a network"), beyond.text)
        assertEquals("10.40.8.0/22", why("10.40.10.9").tick, "inside the /22 around the card the wider choice is the answer")
    }

    @Test fun aNetworkOverTheLimitOfOneScanSaysSo() {
        // Four /22s are 4,088 addresses; the next network does not fit in 4,096.
        val cards = (0 until 4).map { card("Card $it", "10.50.${it * 4}.7", 22) } + lan
        val plan = ScanPlanner.plan(cards, choices = (0 until 4).associate { "10.50.${it * 4}.0/22" to true })
        val row = plan.rows.first { it.limited }
        val a = ScanExplain.explain(row.addresses.first(), plan)
        assertEquals(WhyKind.Limited, a.kind); assertNull(a.tick)
        assertTrue(a.text.contains("ticked but left out: one scan sweeps at most 4,096 addresses"), a.text)
    }

    @Test fun aNetworkThatCannotBeSweptSaysWhyAndOffersTheTypedWayWhereThereIsOne() {
        val virtual = why("172.17.0.9")
        assertEquals(WhyKind.Skipped, virtual.kind); assertEquals("172.17.0.0/24", virtual.typed)
        assertEquals("It is inside 172.17.0.0/16 (docker0), which is not swept: Virtual-switch adapter. If the server is yours, type 172.17.0.0/24 to scan the /24 around it.", virtual.text)
        val tooWide = why("10.200.1.9")
        assertEquals(WhyKind.Skipped, tooWide.kind); assertEquals("10.200.1.0/24", tooWide.typed)
        assertTrue(tooWide.text.startsWith("It is inside 10.128.0.0/9 (Route via 10.20.30.254), which is not swept: Too wide to sweep as a whole"), tooWide.text)
        // Tailscale's own space: named, never offered.
        val mesh = why("100.101.102.103")
        assertEquals(WhyKind.Skipped, mesh.kind); assertNull(mesh.typed); assertTrue(mesh.text.contains("never swept"), mesh.text)
    }

    @Test fun anAddressNoNetworkContainsSaysWhichKindOfAddressItIs() {
        val private = why("192.168.77.9")
        assertEquals(WhyKind.Outside, private.kind); assertEquals("192.168.77.0/24", private.typed)
        assertEquals("None of this computer's networks, routes or known servers include it, so no scan asked it. If this computer can reach it (through a router or a VPN), type its network: 192.168.77.0/24.", private.text)
        val public = why("203.0.113.9")
        assertEquals("203.0.113.0/24", public.typed); assertTrue(public.text.contains("not in private space") && public.text.contains("only the Proxmox ports"))
        assertNull(why("100.64.3.9").typed); assertTrue(why("100.64.3.9").text.contains("Tailscale, Mullvad and carrier-grade NAT"))
        assertNull(why("127.0.0.1").typed); assertTrue(why("127.0.0.1").text.contains("this computer itself"))
        for (reserved in listOf("0.1.2.3", "224.0.0.251", "255.255.255.255")) { assertNull(why(reserved).typed, reserved); assertTrue(why(reserved).text.contains("reserved or a multicast"), reserved) }
        assertEquals("169.254.9.0/24", why("169.254.9.9").typed)
        // What the answer suggests to type is a network the field accepts.
        for (a in listOf(private, public, why("169.254.9.9"), why("172.17.0.9"), why("10.40.200.9"))) assertTrue(ScanTyped.parse(a.typed!!) is TypedNetwork.Rows, a.typed)
        // Typing it makes it a swept network.
        assertEquals(WhyKind.Swept, why("192.168.77.9", plan(typed = "192.168.77.0/24")).kind)
    }

    @Test fun aFailedReadOfTheRoutesIsSaidWithEveryAnswerThatCouldBeMissingANetwork() {
        val problem = "This computer's routes could not be read (It did not answer in time.)."
        val a = why("192.168.77.9", plan(problem = problem))
        assertTrue(a.text.endsWith("$problem The plan may be missing a routed network."), a.text)
        assertTrue(why("10.20.30.5", plan(problem = problem)).text.endsWith("The plan may be missing a routed network."))
    }

    // ---- with a scan behind it ---------------------------------------------------------------------------------------------------

    private fun scanned(finished: Boolean = true, hits: List<ScanHit> = emptyList(), findings: List<HostFinding> = emptyList(), view: ScanPlanView = plan()) =
        NetworkProgress(view.toNetworkPlan(), view.addressCount, hits = hits, finished = finished, findings = findings)

    @Test fun afterAScanTheAnswerIsWhatThatScanDid() {
        val pve = ScanHit("10.20.30.9", 8006, true, ScanKind.PVE)
        val found = why("10.20.30.9:8006", scan = scanned(hits = listOf(pve)))
        assertEquals(WhyKind.Found, found.kind); assertSame(pve, found.found); assertEquals("It was found: Proxmox VE at 10.20.30.9:8006. Its card or row is on this page.", found.text)
        val swept = why("10.20.30.5", scan = scanned())
        assertEquals(WhyKind.Swept, swept.kind)
        assertEquals("The last scan swept 10.20.30.0/24 (Ethernet) and nothing answered at this address on the ports a scan asks (8006, 8007 and 8443 first, then 80, 443, 3000 and 8123 on a device that answered). " +
            "The server may be off, on another address, or behind a firewall that drops these ports.", swept.text)
        // The device is there (the neighbour table or a reset said so) and offers nothing a scan knows.
        val silent = why("10.20.30.5", scan = scanned(findings = listOf(HostFinding("10.20.30.5", "10.20.30.0/24", FoundBy.Neighbour, 900))))
        assertEquals(WhyKind.Silent, silent.kind); assertTrue(silent.text.startsWith("It is there: it answered the network inside 10.20.30.0/24 (Ethernet). But nothing answered on the ports"), silent.text)
        // A scan that was stopped does not claim it asked.
        assertTrue(why("10.20.30.5", scan = scanned(finished = false)).text.contains("did not finish, so it may not have been asked"))
    }

    @Test fun whatTheLastScanSweptCountsNotWhatIsTickedNow() {
        // The scan ran with the route unticked; it is ticked afterwards. The address was not asked, and the next scan will ask it.
        val before = scanned()
        val now = plan(choices = mapOf("10.60.0.0/24" to true))
        assertEquals("It is inside 10.60.0.0/24 (Route via 10.20.30.1 on Ethernet), which is ticked: the next scan asks it.", why("10.60.0.9", now, before).text)
        // And the other way round: swept by the last scan, unticked since. The scan's answer stands.
        assertEquals(WhyKind.Swept, why("10.20.30.5", plan(choices = mapOf("10.20.30.0/24" to false)), before).kind)
        // A typed public network is asked on the Proxmox ports only, and the answer says so.
        val typed = plan(typed = "203.0.113.0/24")
        assertTrue(why("203.0.113.9", typed, scanned(view = typed)).text.contains("8006, 8007 and 8443, the only ports asked outside private space"))
    }
}
