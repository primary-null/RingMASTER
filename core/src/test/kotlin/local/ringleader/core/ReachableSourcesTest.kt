package local.ringleader.core

import kotlin.test.*

/** A source that says what it is told to, and counts how often it is asked. */
class FakeReachableSource(var answer: ReachableRead = ReachableRead()) : ReachableNetworksSource {
    var asked = 0; private set
    var lastContext: ReachableContext? = null; private set
    constructor(vararg networks: ReachableNetwork) : this(ReachableRead(networks.toList()))
    override fun networks(context: ReachableContext): ReachableRead { asked++; lastContext = context; return answer }
}

/** The pluggable sources of reachable networks: the interface, the two real implementations, the tailnet adapter and the way they are combined. */
class ReachableSourcesTest {
    private fun card(ip: String, prefix: Int, index: Int? = null) = ScanInterface("Ethernet", ip, prefix, true, index)
    private val ethernet = card("10.20.30.7", 24, 7)
    private fun route(cidr: String, hop: String? = null, index: Int? = null, name: String? = null) = parseCidr(cidr)!!.let { (n, p) -> RouteEntry(n, p, hop, index, name) }
    private fun facts(vararg routes: RouteEntry) = NetworkFacts(listOf(AdapterFact(7, "Ethernet", null, false, true), AdapterFact(15, "Tailscale", null, true, false)), routes.toList(), "test")
    private val withTailnetRoute = facts(route("0.0.0.0/0", "10.20.30.1", 7), route("10.31.0.0/24", "100.100.100.100", 15))
    private val withoutTailnetRoute = facts(route("0.0.0.0/0", "10.20.30.1", 7))

    // ---- the interface and a fake -------------------------------------------------------------------------------------------

    @Test fun aFakeSourceFeedsThePlanLikeAnyOther() {
        val fake = FakeReachableSource(ReachableNetwork("10.50.0.0/24", PlanSource.Tailnet, "Tailscale route via fake", defaultOn = true),
            ReachableNetwork("10.51.0.0/24", PlanSource.Route, "Route via fake", defaultOn = false, reason = "A route added by a VPN or by hand. Tick it to scan it."))
        val context = ReachableContext(listOf(ethernet), withoutTailnetRoute)
        val read = ReachableNetworks.collect(listOf(fake), context)
        assertEquals(1, fake.asked); assertSame(context, fake.lastContext)
        val view = ScanPlanner.plan(listOf(ethernet), withoutTailnetRoute, read.networks)
        assertEquals(listOf("10.20.30.0/24", "10.50.0.0/24"), view.swept.map { it.id })
        assertEquals("Tailscale route via fake", view.rows.first { it.id == "10.50.0.0/24" }.label)
        assertFalse(view.rows.first { it.id == "10.51.0.0/24" }.on)
    }

    @Test fun sourcesAreCombinedInTheOrderGivenAndAFailingOneDoesNotStopTheOthers() {
        val first = FakeReachableSource(ReachableRead(listOf(ReachableNetwork("10.50.0.0/24", PlanSource.Route, "first", defaultOn = false)), leftOut = 2, problem = "First said so."))
        val broken = ReachableNetworksSource { error("boom") }
        val last = FakeReachableSource(ReachableNetwork("10.51.0.0/24", PlanSource.Saved, "last", defaultOn = true))
        val read = ReachableNetworks.collect(listOf(first, broken, last), ReachableContext(emptyList(), null))
        assertEquals(listOf("10.50.0.0/24", "10.51.0.0/24"), read.networks.map { it.cidr }); assertEquals(2, read.leftOut)
        assertTrue(read.problem!!.startsWith("First said so.") && read.problem!!.contains("could not be read"), read.problem)
        assertEquals(1, last.asked)
        assertEquals(emptyList(), ReachableNetworks.collect(emptyList(), ReachableContext(emptyList(), null)).networks)
    }

    @Test fun aSourceIsAskedEveryTimeSoItFollowsWhatItReadsFrom() {
        var known = listOf(KnownServer("10.20.40.5", "pve-a", PlanSource.Saved))
        val source = KnownServersSource { known }
        val context = ReachableContext(emptyList(), null)
        assertEquals(listOf("10.20.40.0/24"), source.networks(context).networks.map { it.cidr })
        known = known + KnownServer("10.20.41.5", "pve-b", PlanSource.Session)
        assertEquals(listOf("10.20.40.0/24", "10.20.41.0/24"), source.networks(context).networks.map { it.cidr })
    }

    @Test fun theRoutingTableSourceNeedsTheSystemsFactsAndReadsNothingElse() {
        assertEquals(emptyList(), OsRoutesSource().networks(ReachableContext(listOf(ethernet), null)).networks)
        val read = OsRoutesSource().networks(ReachableContext(listOf(ethernet), withTailnetRoute))
        assertEquals(listOf("10.31.0.0/24"), read.networks.map { it.cidr }); assertEquals(PlanSource.Tailnet, read.networks.single().source)
    }

    // ---- the tailnet adapter (a stub, connected to nothing) -----------------------------------------------------------------

    private fun tailnet(facts: NetworkFacts?, vararg advertised: Pair<String, String>) = TailnetSubnetRoutes { advertised.toList() }.networks(ReachableContext(listOf(ethernet), facts)).networks

    @Test fun anAdvertisedRouteThatThisComputerAcceptedIsOnWithTheRoutersName() {
        val row = tailnet(withTailnetRoute, "router-1" to "10.31.0.0/24").single()
        assertEquals(PlanSource.Tailnet, row.source); assertEquals("Tailscale route via router-1", row.label); assertTrue(row.defaultOn); assertNull(row.reason)
        val view = ScanPlanner.plan(listOf(ethernet), withTailnetRoute, listOf(row))
        assertEquals(listOf("10.20.30.0/24", "10.31.0.0/24"), view.swept.map { it.id }, "a tailnet's subnets are swept without a click")
        // A wider route in the routing table covers a narrower one the router advertises.
        assertTrue(tailnet(facts(route("10.31.0.0/16", null, 15)), "router-1" to "10.31.5.0/24").single().defaultOn)
    }

    @Test fun anAdvertisedRouteThisComputerDoesNotHaveIsListedOffAndSaysWhy() {
        val row = tailnet(withoutTailnetRoute, "router-1" to "10.31.0.0/24").single()
        assertFalse(row.defaultOn); assertTrue(row.reason!!.contains("router-1 advertises it, but this computer has no route") && row.reason!!.contains("--accept-routes"), row.reason)
        val view = ScanPlanner.plan(listOf(ethernet), withoutTailnetRoute, listOf(row))
        assertEquals(listOf("10.20.30.0/24"), view.swept.map { it.id }); assertFalse(view.rows.first { it.id == "10.31.0.0/24" }.on)
        // A route through some other interface does not count.
        assertFalse(tailnet(facts(route("10.31.0.0/24", "10.20.30.254", 7)), "router-1" to "10.31.0.0/24").single().defaultOn)
        // Nor does a route for a different network.
        assertFalse(tailnet(facts(route("10.32.0.0/24", null, 15)), "router-1" to "10.31.0.0/24").single().defaultOn)
    }

    @Test fun whenTheRoutesCouldNotBeReadNothingIsAssumedAndTheListSaysSo() {
        val row = tailnet(null, "router-1" to "10.31.0.0/24").single()
        assertFalse(row.defaultOn); assertTrue(row.reason!!.contains("could not be read"), row.reason)
    }

    @Test fun whatIsNotASubnetRouteIsLeftOut() {
        val junk = listOf("a" to "fd7a:115c:a1e0::/48", "b" to "abc", "c" to "10.31.0.7/32", "d" to "0.0.0.0/0", "e" to "::/0", "f" to "", "g" to "+10.31.0.0/24", "h" to "10.31.0.0/+24", "i" to "10.31.0.0/33", "j" to "10.31.0.0/31")
        assertEquals(emptyList(), tailnet(withTailnetRoute, *junk.toTypedArray()))
        // Public and tailnet-space routes pass the adapter and are named, never swept, by the plan.
        val rows = tailnet(withTailnetRoute, "router-1" to "203.0.113.0/24", "router-1" to "100.64.0.0/10", "router-1" to "10.30.0.0/16")
        val view = ScanPlanner.plan(listOf(ethernet), withTailnetRoute, rows)
        assertEquals(setOf("Not private space", "Tailscale / CGNAT space is never swept. Search your tailnet from the VPN page."), view.rows.filter { !it.sweepable && it.source == PlanSource.Tailnet }.take(2).map { it.reason }.toSet())
        assertTrue(view.rows.any { it.reason?.startsWith("Too wide to sweep as a whole") == true })
        assertEquals(listOf("10.20.30.0/24"), view.swept.map { it.id })
    }

    @Test fun theSameNetworkFromTwoRoutersIsOneRowUnderTheFirstRoutersName() {
        val rows = tailnet(withTailnetRoute, "router-1" to "10.31.0.0/24", "router-2" to "10.31.0.0/24")
        assertEquals(1, rows.size); assertEquals("Tailscale route via router-1", rows.single().label); assertTrue(rows.single().defaultOn)
    }

    @Test fun aRouterNameIsCleanedAndAnEmptyOneIsNamed() {
        assertEquals("Tailscale route via a router", tailnet(withTailnetRoute, "" to "10.31.0.0/24").single().label)
        assertEquals("Tailscale route via a router", tailnet(withTailnetRoute, "\u0007\n" to "10.31.0.0/24").single().label)
        val label = tailnet(withTailnetRoute, ("r\u0000" + "x".repeat(300)) to "10.31.0.0/24").single().label
        assertTrue(label.length <= "Tailscale route via ".length + 80 && label.none { it.isISOControl() }, label)
    }

    @Test fun onlyTwoHundredAndFiftySixAdvertisedRoutesAreRead() {
        val many = (0 until 400).map { "r" to "10.${60 + it / 256}.${it % 256}.0/24" }
        assertEquals(256, tailnet(withTailnetRoute, *many.toTypedArray()).size)
    }

    @Test fun theSameRouteInTheRoutingTableAndFromTheRouterIsOneRowNamedAfterTheRouter() {
        val stub = TailnetSubnetRoutes { listOf("router-1" to "10.31.0.0/24") }
        val context = ReachableContext(listOf(ethernet), withTailnetRoute)
        // The extra source comes first, as the page puts it, so its wording stays.
        val read = ReachableNetworks.collect(listOf(stub, OsRoutesSource()), context)
        assertEquals(2, read.networks.size)
        val view = ScanPlanner.plan(listOf(ethernet), withTailnetRoute, read.networks)
        val row = view.rows.single { it.id == "10.31.0.0/24" }
        assertEquals("Tailscale route via router-1", row.label); assertTrue(row.alsoFrom.isEmpty(), row.alsoFrom.toString()); assertTrue(row.swept)
    }

    @Test fun aRememberedUntickWinsOverATailnetDefault() {
        val rows = tailnet(withTailnetRoute, "router-1" to "10.31.0.0/24")
        assertTrue(ScanPlanner.plan(listOf(ethernet), withTailnetRoute, rows).rows.first { it.id == "10.31.0.0/24" }.on)
        assertFalse(ScanPlanner.plan(listOf(ethernet), withTailnetRoute, rows, choices = mapOf("10.31.0.0/24" to false)).rows.first { it.id == "10.31.0.0/24" }.on)
    }

    @Test fun aHugeTypedPrefixNeverShowsANegativeCount() {
        assertEquals(Int.MAX_VALUE, hostCount(0)); assertEquals(2_147_483_646, hostCount(1)); assertEquals(1022, hostCount(22)); assertEquals(1, hostCount(32)); assertEquals(2, hostCount(31))
        val note = (ScanTyped.parse("10.0.0.0/0") as TypedNetwork.Rows).rows.first().note!!
        assertFalse("-" in note, note)
    }
}
