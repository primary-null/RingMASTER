package local.ringleader.desktop

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.coroutines.*
import local.ringleader.core.*

/** Refresh ordering and the minimised-window pause. Mutations stay on the VPN page tests. */
class VpnSessionTest {
    private fun snap(mark: String) = VpnSnapshot(java.time.Instant.EPOCH, emptyList(), false, null, false, emptyList(), listOf(mark))

    private fun backend(snapshot: () -> VpnSnapshot, routeTo: (VpnSnapshot?) -> Unit = {}): VpnBackend = object : VpnBackend {
        override val platform = "Test"
        override fun snapshot() = snapshot()
        override fun routeTo(host: String, snapshot: VpnSnapshot?): VpnRoute {
            routeTo(snapshot)
            return VpnRoute(host, host, null, null, null)
        }
        override fun setTunnel(tunnel: VpnTunnel, up: Boolean) {}
        override fun setExitNode(peer: TailscalePeer?, allowLanAccess: Boolean) {}
        override fun importWireGuard(config: java.nio.file.Path, name: String) = ""
        override fun removeWireGuard(tunnel: VpnTunnel) {}
    }

    private fun waitFor(timeoutMs: Long = 4_000, ready: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!ready()) {
            if (System.nanoTime() > deadline) fail("timed out")
            Thread.sleep(15)
        }
    }

    @Test fun anOlderSnapshotCannotOverwriteANewerRefresh() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val published = CountDownLatch(1)
        val calls = AtomicInteger(0)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val session = VpnSession(scope, backend(
                snapshot = {
                    if (calls.incrementAndGet() == 1) {
                        entered.countDown()
                        assertTrue(release.await(5, TimeUnit.SECONDS))
                        snap("old")
                    } else snap("new")
                },
                routeTo = { if (it?.notes?.firstOrNull() == "new") published.countDown() },
            ), hostActions = true)
            session.refresh("host.example")
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            session.refresh("host.example")
            assertTrue(published.await(5, TimeUnit.SECONDS))
            assertEquals("new", session.snapshot?.notes?.firstOrNull())
            release.countDown()
            Thread.sleep(250)
            assertEquals("new", session.snapshot?.notes?.firstOrNull())
        } finally {
            release.countDown()
            scope.cancel()
        }
    }

    @Test fun aMinimizedWindowSkipsBackgroundRefreshWithoutDroppingTheCurrentOne() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger(0)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val session = VpnSession(scope, backend(snapshot = {
                calls.incrementAndGet()
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                snap("kept")
            }), hostActions = true)
            session.refresh("host.example")
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(session.reading)
            session.minimized = true
            session.refresh("host.example")
            assertEquals(1, calls.get())
            release.countDown()
            waitFor { session.snapshot?.notes?.firstOrNull() == "kept" }
            assertFalse(session.reading)
        } finally {
            release.countDown()
            scope.cancel()
        }
    }

    @Test fun onlyAManualRefreshShowsReading() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val session = VpnSession(scope, backend(snapshot = {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                snap("manual")
            }), hostActions = true)
            session.minimized = true
            session.refresh("host.example", manual = true)
            assertTrue(session.reading)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(session.reading)
            release.countDown()
            waitFor { session.snapshot != null && !session.reading }
        } finally {
            release.countDown()
            scope.cancel()
        }
    }

    @Test fun aForcedRefreshWhileMinimizedRunsAndDoesNotShowReading() {
        val calls = AtomicInteger(0)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val session = VpnSession(scope, backend(snapshot = { calls.incrementAndGet(); snap("forced") }), hostActions = true)
            session.minimized = true
            session.refresh("host.example")
            Thread.sleep(200)
            assertEquals(0, calls.get())
            assertNull(session.snapshot)
            session.refresh("host.example", force = true)
            waitFor { session.snapshot?.notes?.firstOrNull() == "forced" }
            assertEquals(1, calls.get())
            assertFalse(session.reading)
            val tunnel = VpnTunnel("nm:u", VpnKind.WireGuard, "home", VpnState.Down, canToggle = true)
            session.act("nm:u", "host.example", "done") { it.setTunnel(tunnel, true) }
            waitFor { calls.get() >= 2 && session.message == "done" }
            assertFalse(session.reading)
            assertNull(session.busy)
        } finally { scope.cancel() }
    }
}
