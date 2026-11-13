package local.ringleader.core

import java.net.InetAddress
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

/**
 * The tag's observer on virtual time: switching connections, failed reads, coalescing, pause and resume, network
 * changes, VPN invalidation, cancellation and time limits. All reads are fakes; nothing touches the network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionRouteObserverTest {
    private val ethernet = RouteInterface("eth0", 2, null, RouteMedium.Ethernet)
    private val tunnel = RouteInterface("wg0", 7, null, RouteMedium.Tunnel, VpnKind.WireGuard)

    private class Harness(val test: TestScope, io: kotlinx.coroutines.CoroutineDispatcher? = null) {
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        val scope = CoroutineScope(dispatcher + Job())
        val clock = { Instant.EPOCH.plusMillis(test.testScheduler.currentTime) }
        var routeFor: (String) -> OsRoute = { OsRoute(it, RouteInterface("eth0", 2, null, RouteMedium.Ethernet), "192.168.1.20", "192.168.1.1") }
        var addressesFor: (String) -> List<InetAddress> = { host -> listOf(InetAddress.getByName(if (host.startsWith("a.")) "192.0.2.1" else "192.0.2.2")) }
        var print = "net-1"
        var proxy: String? = null
        var proxyFails = false
        val probed = mutableListOf<String>()
        var snapshots = 0
        val inventory = VpnInventorySource({ null }, { snapshots++; VpnSnapshot(clock(), emptyList(), false, null, false, emptyList(), emptyList()) }, clock)
        val observer = ConnectionRouteObserver(scope, object : RouteProbe {
            override fun route(address: String): OsRoute { probed += address; return routeFor(address) }
        }, inventory, resolve = { addressesFor(it) }, proxyFor = { if (proxyFails) error("Cannot inspect proxy") else proxy }, fingerprint = { print }, clock = clock, io = io ?: dispatcher,
            refreshMillis = 90_000, fingerprintMillis = 15_000, stepTimeoutMillis = 20_000)
        fun run() = test.testScheduler.runCurrent()
        fun advance(ms: Long) = test.testScheduler.advanceTimeBy(ms).also { run() }
        val state get() = observer.state.value
        fun close() { observer.close(); scope.cancel() }
    }

    private val a = RouteContext.Server(1, 1, "https://a.example.test:8006")
    private val b = RouteContext.Server(2, 1, "https://b.example.test:8006")
    private fun RouteTagState.kind() = (this as? RouteTagState.Observed)?.observation?.kind

    @Test fun aSessionSwitchCannotRenderThePreviousServersObservation() = runTest {
        val h = Harness(this)
        h.observer.update(a, true); h.run()
        val observed = assertIs<RouteTagState.Observed>(h.state)
        assertSame(observed, observed.forContext(a))
        assertEquals(b.session, assertIs<RouteTagState.Checking>(observed.forContext(b)).key.session)
        assertEquals(RouteTagState.Hidden, observed.forContext(RouteContext.None))
        assertEquals(RouteTagState.Demo, observed.forContext(RouteContext.Demo))
        assertEquals(RouteTagState.Browser, observed.forContext(RouteContext.Browser))
        h.close()
    }

    @Test fun aProbeIgnoringInterruptionCannotOverlapTheNextRead() = runTest {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val h = Harness(this, io = Dispatchers.IO)
        h.routeFor = { address ->
            entered.countDown()
            try {
                while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
                OsRoute(address, ethernet, null, null)
            } finally { exited.countDown() }
        }
        try {
            h.observer.update(a, true)
            val deadline = System.nanoTime() + 10_000_000_000L
            while (entered.count > 0 && System.nanoTime() < deadline) { h.run(); Thread.sleep(5) }
            assertEquals(0, entered.count)
            h.advance(20_001)
            assertEquals(RouteKind.Unknown, h.state.kind())
            h.observer.update(b, true); h.run()
            // The new connection cannot get past the gate while the old OS call is still executing.
            h.advance(20_001)
            assertEquals(RouteKind.Unknown, h.state.kind())
            assertEquals(1, h.probed.size)
        } finally {
            release.countDown()
            assertTrue(exited.await(5, TimeUnit.SECONDS))
            h.close()
        }
    }
    private fun RouteTagState.origin() = when (this) { is RouteTagState.Observed -> key.origin; is RouteTagState.Checking -> key.origin; else -> null }

    @Test fun aResultForConnectionAIsNeverShownForB() = runTest {
        val h = Harness(this)
        // The switch to B happens while A's route read is running.
        h.routeFor = { address -> if (address == "192.0.2.1") h.observer.update(b, true); OsRoute(address, ethernet, null, null) }
        h.observer.update(a, true)
        assertIs<RouteTagState.Checking>(h.state)
        h.run()
        val shown = h.state
        assertEquals("https://b.example.test:8006", shown.origin())
        assertIs<RouteTagState.Observed>(shown)
        assertEquals("192.0.2.2", shown.observation.address)
        h.close()
    }

    @Test fun switchingConnectionsDropsToCheckingAtOnce() = runTest {
        val h = Harness(this)
        h.observer.update(a, true); h.run()
        assertEquals(RouteKind.Direct, h.state.kind())
        h.observer.update(b, true)
        assertIs<RouteTagState.Checking>(h.state); assertEquals("https://b.example.test:8006", h.state.origin())
        h.run(); assertEquals(RouteKind.Direct, h.state.kind())
        // A reconnect to the same origin is a new epoch, so a new key.
        h.observer.update(b.copy(epoch = 2), true)
        assertIs<RouteTagState.Checking>(h.state)
        h.close()
    }

    @Test fun aFailedRefreshReplacesAFreshDirect() = runTest {
        val h = Harness(this)
        h.observer.update(a, true); h.run()
        assertEquals(RouteKind.Direct, h.state.kind())
        h.routeFor = { error("Find-NetRoute failed") }
        h.advance(90_000)
        val shown = assertIs<RouteTagState.Observed>(h.state)
        assertEquals(RouteKind.Unknown, shown.observation.kind)
        assertTrue(shown.observation.reason!!.startsWith("The route check failed"))
        h.close()
    }

    @Test fun refreshesSlowlyAndCoalescesInvalidations() = runTest {
        val h = Harness(this)
        h.observer.update(a, true); h.run()
        assertEquals(1, h.observer.reads)
        h.advance(60_000); assertEquals(1, h.observer.reads, "no read before the interval")
        h.advance(30_000); assertEquals(2, h.observer.reads)
        // Three invalidations during one read: that read is discarded and exactly one more follows.
        var calls = 0
        h.routeFor = { address -> calls++; if (calls == 1) repeat(3) { h.observer.invalidate() }; OsRoute(address, ethernet, null, null) }
        h.advance(90_000)
        assertEquals(4, h.observer.reads)
        assertEquals(RouteKind.Direct, h.state.kind())
        h.close()
    }

    @Test fun vpnInvalidationDropsTheShownResultAndTheVpnCache() = runTest {
        val h = Harness(this)
        h.observer.update(a, true); h.run()
        assertEquals(1, h.snapshots)
        h.advance(90_000); assertEquals(1, h.snapshots, "the VPN snapshot is cached between route reads")
        h.routeFor = { OsRoute(it, tunnel, "10.8.0.2", null) }
        h.observer.invalidate()
        assertIs<RouteTagState.Checking>(h.state, "a VPN change drops the shown answer at once")
        h.run()
        assertEquals(RouteKind.Vpn, h.state.kind()); assertEquals(2, h.snapshots)
        h.close()
    }

    @Test fun pausedObserversReadNothingAndResumeHonestly() = runTest {
        val h = Harness(this)
        h.observer.update(a, true); h.run()
        val reads = h.observer.reads
        h.observer.update(a, false)
        h.advance(900_000)
        assertEquals(reads, h.observer.reads, "nothing runs while hidden")
        assertTrue(h.probed.size == 1)
        // After a long pause the old answer is not shown again: Checking, then a new read.
        h.observer.update(a, true)
        assertIs<RouteTagState.Checking>(h.state)
        h.run(); assertEquals(reads + 1, h.observer.reads); assertEquals(RouteKind.Direct, h.state.kind())
        // A brief page switch keeps a young answer.
        h.observer.update(a, false); h.advance(5_000); h.observer.update(a, true)
        assertIs<RouteTagState.Observed>(h.state)
        h.close()
    }

    @Test fun switchingBackReusesOnlyAYoungResult() = runTest {
        val h = Harness(this)
        h.observer.update(a, true); h.run()
        h.observer.update(b, true); h.run()
        h.observer.update(a, true)
        assertIs<RouteTagState.Observed>(h.state, "A's answer is a few moments old")
        h.observer.update(b, true); h.run()
        h.advance(200_000)
        h.observer.update(a, true)
        assertIs<RouteTagState.Checking>(h.state, "an old cached answer is not reused")
        h.close()
    }

    @Test fun aNetworkChangeTriggersANewRead() = runTest {
        val h = Harness(this)
        h.observer.update(a, true); h.run()
        val reads = h.observer.reads
        h.advance(15_000); assertEquals(reads, h.observer.reads, "same network, no read")
        h.print = "net-2"
        h.routeFor = { OsRoute(it, tunnel, "10.8.0.2", null) }
        h.advance(15_000)
        assertEquals(reads + 1, h.observer.reads)
        assertEquals(RouteKind.Vpn, h.state.kind())
        h.close()
    }

    @Test fun demoBrowserAndNoConnectionNeverReadTheHost() = runTest {
        val h = Harness(this)
        h.observer.update(RouteContext.Demo, true); h.advance(200_000)
        assertEquals(RouteTagState.Demo, h.state)
        h.observer.update(RouteContext.Browser, true); h.advance(200_000)
        assertEquals(RouteTagState.Browser, h.state)
        h.observer.update(RouteContext.None, true); h.advance(200_000)
        assertEquals(RouteTagState.Hidden, h.state)
        h.observer.update(RouteContext.Server(3, 1, "not a url"), true); h.advance(200_000)
        assertEquals(RouteTagState.Hidden, h.state)
        assertEquals(0, h.observer.reads); assertTrue(h.probed.isEmpty()); assertEquals(0, h.snapshots)
        // Leaving a connection for the demo hides the connection's answer at once.
        h.observer.update(a, true); h.run(); assertEquals(RouteKind.Direct, h.state.kind())
        h.observer.update(RouteContext.Demo, true)
        assertEquals(RouteTagState.Demo, h.state)
        h.close()
    }

    @Test fun aProxySkipsTheRouteRead() = runTest {
        val h = Harness(this)
        h.proxy = "HTTP proxy proxy.example.test:3128"
        h.observer.update(a, true); h.run()
        assertEquals(RouteKind.Proxy, h.state.kind())
        assertTrue(h.probed.isEmpty()); assertEquals(0, h.snapshots)
        h.close()
    }

    @Test fun failedProxyInspectionStopsBeforeRouteClassification() = runTest {
        val h = Harness(this)
        h.proxyFails = true
        h.observer.update(a, true); h.run()
        assertEquals(RouteKind.Unknown, h.state.kind())
        assertTrue(h.probed.isEmpty()); assertEquals(0, h.snapshots)
        h.close()
    }

    @Test fun closingStopsEverything() = runTest {
        val h = Harness(this)
        h.observer.update(a, true); h.run()
        val reads = h.observer.reads
        h.observer.close()
        h.advance(900_000)
        assertEquals(reads, h.observer.reads)
        h.scope.cancel()
    }

    @Test fun aSlowRouteReadIsAbandonedAfterItsLimit() = runTest {
        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val h = Harness(this, io = Dispatchers.IO)
        h.routeFor = { address ->
            entered.countDown()
            try { Thread.sleep(30_000) } catch (e: InterruptedException) { interrupted.countDown(); throw e }
            OsRoute(address, ethernet, null, null)
        }
        h.observer.update(a, true)
        // The earlier steps run on real threads; let them finish until the probe is entered.
        val deadline = System.nanoTime() + 10_000_000_000L
        while (entered.count > 0 && System.nanoTime() < deadline) { h.run(); Thread.sleep(5) }
        assertEquals(0, entered.count, "the probe was not reached")
        h.advance(20_001)
        val shown = assertIs<RouteTagState.Observed>(h.state)
        assertEquals(RouteKind.Unknown, shown.observation.kind)
        assertEquals("The route check failed: The check took too long.", shown.observation.reason)
        assertTrue(interrupted.await(5, TimeUnit.SECONDS), "the abandoned read is interrupted")
        h.close()
    }

    @Test fun inventoryPrefersTheVpnPagesFreshSnapshot() {
        var now = Instant.parse("2026-11-12T10:00:00Z")
        var shared: VpnSnapshot? = null
        var takes = 0
        var failing = false
        fun snapshot(at: Instant) = VpnSnapshot(at, emptyList(), false, null, false, emptyList(), emptyList())
        val source = VpnInventorySource({ shared }, { takes++; if (failing) error("powershell: denied") else snapshot(now) }, { now }, maxAgeMillis = 300_000, sharedMaxAgeMillis = 60_000)
        shared = snapshot(now.minusSeconds(10))
        assertSame(shared, source.current().snapshot); assertEquals(0, takes)
        shared = snapshot(now.minusSeconds(120))
        source.current(); assertEquals(1, takes, "a stale page snapshot is not used")
        now = now.plusSeconds(100); source.current(); assertEquals(1, takes, "our own snapshot is kept for five minutes")
        now = now.plusSeconds(300); source.current(); assertEquals(2, takes)
        source.invalidate()
        shared = snapshot(now.minusSeconds(5))
        source.current(); assertEquals(3, takes, "a page snapshot from before a VPN change is not used")
        failing = true; source.invalidate()
        val failed = source.current()
        assertNull(failed.snapshot); assertFalse(failed.complete); assertTrue(failed.problem!!.startsWith("The VPN check failed."))
        assertEquals(VpnInventory.Unavailable, VpnInventorySource({ null }, null).current())
    }
}
