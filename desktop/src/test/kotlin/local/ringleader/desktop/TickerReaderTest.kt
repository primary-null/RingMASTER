@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package local.ringleader.desktop

import java.nio.file.Files
import java.time.Instant
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import local.ringleader.core.*

/** What the window's own state becomes for the tile model: the connections, the running things, and nothing that was not asked for. */
class TickerReaderTest {
    private class Bed(scope: CoroutineScope, val lab: MatrixLab, val sessions: Sessions) {
        val scan = ScanSession(scope)
        val vpn = VpnSession(scope, null)
        val tools = ToolsSession(scope)
        val routes = RouteMemory()
        var route: RouteTagState = RouteTagState.Hidden
        var profiles: List<ConnectionProfile> = emptyList()
        var clock: Instant = lab.now
        val reader = TickerReader(sessions, { profiles }, { route }, scan, vpn, tools, routes, { clock })
        val workspace get() = sessions.state.value.active.workspace
        @Suppress("UNCHECKED_CAST")
        fun update(f: (WorkspaceState) -> WorkspaceState) { (workspace.javaClass.getDeclaredField("mutableState").apply { isAccessible = true }.get(workspace) as MutableStateFlow<WorkspaceState>).update(f) }
    }

    private fun bed(block: suspend (Bed) -> Unit) {
        val root = Files.createTempDirectory("ticker-reader")
        try {
            runBlocking {
                val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
                val lab = MatrixLab(root)
                val sessions = Sessions(scope) { connectedWorkspace(scope, lab) }
                try { block(Bed(scope, lab, sessions)) } finally { sessions.close(); scope.cancel() }
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun aConnectedSessionIsReadWithItsSignInTrustAndLiveState() = bed { b ->
        val seen = b.reader.read()
        val s = seen.sessions.single()
        assertTrue(s.inView); assertEquals(TickerSessionPhase.Connected, s.phase); assertEquals("API token", s.signIn)
        assertEquals(false, s.pinned, "no fingerprint: normal TLS"); assertEquals(b.lab.origin, s.address); assertEquals(DIRECT_PVE_KIND, s.kind)
        assertEquals(b.workspace.state.value.liveIntervalSeconds, s.liveSeconds)
        assertNotNull(s.updatedAt)
        assertEquals(s, seen.inView)
    }

    @Test fun anIdleSessionHoldsNothingAndADemoIsADemo() {
        val root = Files.createTempDirectory("ticker-reader-idle")
        try {
            runBlocking {
                val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
                val sessions = Sessions(scope) { Workspace(scope) }
                try {
                    val reader = TickerReader(sessions, { emptyList() }, { RouteTagState.Hidden }, ScanSession(scope), VpnSession(scope, null), ToolsSession(scope))
                    assertTrue(reader.read().sessions.isEmpty(), "the empty session on the Desk is not a connection")
                    assertNull(reader.read().inView)
                    sessions.state.value.active.workspace.exploreDemo()
                    val demo = reader.read().sessions.single()
                    assertEquals(TickerSessionPhase.Demo, demo.phase); assertNull(demo.pinned, "a demo has no certificate")
                } finally { sessions.close(); scope.cancel() }
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun aFailedConnectionIsReadWithItsReasonAndNoServerName() = bed { b ->
        b.update { WorkspaceState(error = CERTIFICATE_ERROR, failedAddress = "https://pve-b.example.test:8006", account = "root@pam", connected = true, address = "https://pve-b.example.test:8006", kind = DIRECT_PVE_KIND) }
        val s = b.reader.read().sessions.single()
        assertEquals(TickerSessionPhase.Failed, s.phase); assertEquals(TickerFailure.Certificate, s.failure)
        val model = HoodTicker.build(TickerInput(b.lab.now, TickerData.vitals(perMinute = 0), TickerData.metrics(p50 = null, p95 = null, sources = emptyList()), TraceLiveLoop("idle"), b.reader.read()))
        assertEquals("Failed", model.tiles.first().headline)
        assertFalse(model.tiles.any { tile -> listOfNotNull(tile.headline, tile.detail).any { "pve-b" in it || "root@pam" in it } })
    }

    @Test fun failuresAreClassifiedWithoutKeepingTheText() {
        assertEquals(TickerFailure.Certificate, classifyFailure(CERTIFICATE_ERROR)); assertEquals(TickerFailure.SignIn, classifyFailure(LOGIN_REFUSED))
        assertEquals(TickerFailure.SignIn, classifyFailure(SESSION_REFUSED)); assertEquals(TickerFailure.Unreachable, classifyFailure("The server could not be reached. Check the network and try again."))
        assertEquals(TickerFailure.Other, classifyFailure("Something else")); assertEquals(TickerFailure.Other, classifyFailure(null))
    }

    @Test fun runningTasksAndTheLatestFailureComeFromTheTaskList() = bed { b ->
        b.workspace.refreshTasks()
        val seen = b.reader.read()
        val running = seen.tasks.single()
        assertEquals("Backup", running.label); assertEquals("dns-service", running.subject, "the guest the task is about, for a page that may show it")
        assertEquals(b.lab.tasks[0].upid, running.upid); assertEquals(b.lab.tasks[0].startedAt, running.since)
        assertNull(seen.failedTask, "the failed task is hours old")
        val failed = b.lab.tasks[5]
        b.clock = failed.endedAt!!.plusSeconds(90)
        assertEquals(failed.upid, b.reader.read().failedTask?.upid)
        b.clock = failed.endedAt!!.plusSeconds(400)
        assertNull(b.reader.read().failedTask, "after five minutes it is no longer news")
    }

    @Test fun aRunningScanAnUploadAToolAndAVpnChangeAreReadAndNothingElse() = bed { b ->
        assertNull(b.reader.read().scan); assertFalse(b.reader.read().uploading); assertFalse(b.reader.read().toolRunning); assertFalse(b.reader.read().vpnBusy)
        val plan = NetworkPlan(listOf(ScanNetwork(ScanInterface("Ethernet", "192.0.2.2", 24), "192.0.2.0/24", null, (1..200).map { "192.0.2.$it" })))
        stateOf<NetworkProgress?>(b.scan, "progress").value = NetworkProgress(plan, 50, hits = listOf(ScanHit("192.0.2.1", 80, false, ScanKind.WEB), ScanHit("192.0.2.1", 443, true, ScanKind.WEB), ScanHit("192.0.2.9", 8006, true, ScanKind.PVE)))
        assertNull(b.reader.read().scan, "a finished or stopped scan is not running")
        stateOf<Boolean>(b.scan, "running").value = true
        assertEquals(TickerScan(25, 2), b.reader.read().scan, "a quarter of the addresses, two hosts (one answered on two ports)")
        stateOf<Boolean>(b.tools, "running").value = true; assertTrue(b.reader.read().toolRunning)
        stateOf<String?>(b.vpn, "busy").value = "tailscale"; assertTrue(b.reader.read().vpnBusy)
        val key = StorageKey(b.lab.origin, "pve-east", "local")
        b.update { it.copy(storageActions = StorageActionsState(submissions = listOf(StorageSubmission("local-1", key, StorageAction.Upload, PowerPhase.Accepted, "Sending storage action.", totalBytes = 1_000, bytesSent = 250)))) }
        assertTrue(b.reader.read().uploading); assertEquals(25, b.reader.read().uploadPercent)
        b.update { it.copy(storageActions = StorageActionsState(submissions = listOf(StorageSubmission("local-1", key, StorageAction.Upload, PowerPhase.Accepted, "Following storage task.", totalBytes = 1_000, bytesSent = 1_000)))) }
        assertFalse(b.reader.read().uploading, "sent in full: now a task the Activity page follows")
    }

    @Test fun theRouteObserversLastReadingIsKeptForEachConnectionAndDroppedOnAReconnect() = bed { b ->
        val s = b.sessions.state.value.active
        val epoch = s.workspace.state.value.connectionEpoch
        val target = routeTarget(b.lab.origin)!!
        fun observed(epoch: Long) = RouteTagState.Observed(RouteKey(s.id, epoch, target.origin), RouteObservation(target, b.lab.now, RouteKind.Vpn, vpnKind = VpnKind.Tailscale, medium = RouteMedium.Tunnel))
        assertNull(b.reader.read().sessions.single().route, "not observed yet")
        b.route = observed(epoch)
        assertEquals(TickerRoute(RouteKind.Vpn, VpnKind.Tailscale, RouteMedium.Tunnel), b.reader.read().sessions.single().route)
        b.route = RouteTagState.Hidden
        assertEquals(TickerRoute(RouteKind.Vpn, VpnKind.Tailscale, RouteMedium.Tunnel), b.reader.read().sessions.single().route, "kept when the page that reads it is not on screen")
        b.update { it.copy(connectionEpoch = epoch + 1) }
        assertNull(b.reader.read().sessions.single().route, "a new connection to the same server starts unread")
        b.route = observed(epoch + 5)
        assertNull(b.reader.read().sessions.single().route, "a reading for another epoch never shows")
    }

    @Test fun keptConsolesCarryTheGuestOnlyAsAnIdentity() = bed { b ->
        val guest = b.lab.guest(120)
        b.update { it.copy(keptConsoles = setOf(guest.resource)) }
        val kept = b.reader.read().kept.single()
        assertEquals(guest.resource, kept.guest); assertEquals(guest.name, kept.subject)
    }

    @Test fun theReaderReadsTheWindowAndNeverTheServer() = bed { b ->
        // Reading is property reads: it adds nothing to the trace and starts no refresh.
        val before = Trace.snapshot().size
        repeat(5) { b.reader.read() }
        assertEquals(before, Trace.snapshot().size)
    }
}
