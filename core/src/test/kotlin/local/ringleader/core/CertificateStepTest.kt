package local.ringleader.core

import java.io.IOException
import java.nio.file.Files
import java.time.Instant
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * Network audit W3, W5 and F7: a session refused on its certificate stops retrying until an explicit read or Connect, and a
 * saved profile with its own pin can review and replace that pin in its own panel (same key in one click).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CertificateStepTest {
    private val origin = "https://pve.example.test:8006"
    private val a = "ab".repeat(32)
    private val b = "cd".repeat(32)
    private val key = "11".repeat(32)
    private val otherKey = "22".repeat(32)
    private val overview = Overview(1, listOf(Guest(origin, 100, "alpha", "node-a", "VM", "running")))

    private class Counting(var answer: suspend () -> Overview) : InventoryGateway, TaskGateway {
        var reads = 0; var taskReads = 0
        var tasksAnswer: suspend () -> List<ClusterTask> = { emptyList() }
        override suspend fun overview(connection: Connection): Overview { reads++; return answer() }
        override suspend fun details(connection: Connection, guest: Guest) = sampleDetails(guest)
        override suspend fun tasks(connection: Connection): List<ClusterTask> { taskReads++; return tasksAnswer() }
    }

    private fun changed() = SSLHandshakeException("handshake").apply { initCause(PinMismatchException(b, otherKey)) }

    @Test fun aCertificateRefusalStopsTheLiveAndTaskLoopsUntilAnExplicitReadSucceeds() = runTest {
        val gateway = Counting({ throw changed() })
        val workspace = Workspace(this, gateway, taskGateway = gateway).apply { liveJitter = { 0.0 } }
        workspace.setLiveTasks(true)
        workspace.connect(Connection(origin, "root@pam!read", "secret", a))
        runCurrent()
        val held = workspace.state.value
        assertTrue(held.certificateHold); assertEquals(NetworkFailure.Changed(b, otherKey), held.failure)
        assertEquals(NetworkFailure.Changed().sentence, held.error); assertNull(held.liveBackoffSeconds)
        advanceTimeBy(10 * 60_000L); runCurrent()
        assertEquals(1, gateway.reads, "no TLS attempt every few seconds"); assertEquals(0, gateway.taskReads)
        // Window restored or live switched on again: still held.
        workspace.resumeLive(); workspace.setLive(true); workspace.setLiveTasks(true)
        advanceTimeBy(5 * 60_000L); runCurrent()
        assertEquals(1, gateway.reads)
        // Try again: an explicit read. While the certificate is still refused, it stays held.
        workspace.refresh(force = true); runCurrent()
        assertEquals(2, gateway.reads); assertTrue(workspace.state.value.certificateHold)
        // Once the server is trusted again (the user replaced trust and the server answers), the loops resume.
        gateway.answer = { overview }
        workspace.refresh(force = true); runCurrent()
        assertFalse(workspace.state.value.certificateHold); assertNull(workspace.state.value.failure)
        advanceTimeBy(60_000L); runCurrent()
        assertTrue(gateway.reads > 3, "live reads resumed: ${gateway.reads}"); assertTrue(gateway.taskReads > 0, "task reads resumed")
        workspace.close()
    }

    @Test fun aLaterCertificateChangeInAnOpenSessionAlsoStopsTheLoops() = runTest {
        val gateway = Counting({ overview })
        val workspace = Workspace(this, gateway, taskGateway = gateway).apply { liveJitter = { 0.0 } }
        workspace.connect(Connection(origin, "root@pam!read", "secret", a, knownSpki = otherKey))
        runCurrent()
        gateway.answer = { throw changed() }
        advanceTimeBy(30_000L); runCurrent()
        val reads = gateway.reads
        val state = workspace.state.value
        assertTrue(state.certificateHold); assertTrue(sessionOpen(state), "the inventory read before stays on screen, marked stale")
        // The recorded key matches the new leaf's key: a renewal.
        assertEquals(NetworkFailure.RenewedSameKey(b), state.failure)
        advanceTimeBy(10 * 60_000L); runCurrent()
        assertEquals(reads, gateway.reads)
        workspace.close()
    }

    @Test fun anUnreachableServerStillRetriesWithBackoff() = runTest {
        val gateway = Counting({ throw IOException("down") })
        val workspace = Workspace(this, gateway, taskGateway = gateway).apply { liveJitter = { 0.0 } }
        workspace.connect(Connection(origin, "root@pam!read", "secret", a))
        runCurrent()
        advanceTimeBy(5 * 60_000L); runCurrent()
        assertFalse(workspace.state.value.certificateHold)
        assertTrue(gateway.reads > 3, "an outage is retried: ${gateway.reads}")
        workspace.close()
    }

    // ---- the pinned profile's own certificate step ----

    private fun probed(pin: String, spki: String, to: String = "2028-11-01T00:00:00Z") =
        ProbedCertificate(pin, spki, "CN=pve.example.test", "CN=PVE Cluster Manager CA", Instant.parse("2026-11-01T00:00:00Z"), Instant.parse(to))

    private fun TestScope.desk(spki: String? = key): Triple<ProfileDesk, Workspace, java.nio.file.Path> {
        val root = Files.createTempDirectory("certificate-step")
        val workspace = Workspace(this, Counting({ overview }))
        val desk = ProfileDesk(workspace, ProfileStore(root.resolve("profiles.json")), clock = { Instant.parse("2026-11-16T09:12:00Z") })
        desk.beginCreate(); desk.updateDraft("Lab", origin, "root@pam", a); assertTrue(desk.saveDraft(passwordLogin = true))
        if (spki != null) {
            ProfileStore(root.resolve("profiles.json")).apply { load(); save(listOf(desk.state.value.profiles.single().copy(sha256Spki = spki))) }
            desk.reload()
        }
        desk.selectProfile(desk.state.value.profiles.single().id)
        return Triple(desk, workspace, root)
    }

    @Test fun aRenewalWithTheSameKeyReplacesThePinOnTheExplicitApprovalOnly() = runTest {
        val (desk, workspace, root) = desk()
        val routed = mutableListOf<Connection>()
        desk.router = { c, _ -> routed += c!! }
        try {
            val profile = desk.state.value.profiles.single()
            val trust = desk.profileTrust(profile, probed(b, key))
            assertTrue(trust.changed); assertTrue(trust.sameKey); assertTrue(trust.canApprove)
            assertEquals(listOf(a), trust.known, "only this profile's own pin is known here")
            // Without approval (Enter, Connect) the profile keeps its pin and TLS refuses the new leaf.
            desk.setProfileSecret("secret")
            assertTrue(desk.connectSelected())
            assertEquals(a, routed.last().fingerprint); assertEquals(key, routed.last().knownSpki, "the session can tell a renewal")
            // A stale approval (for another certificate) is refused.
            desk.setProfileSecret("secret")
            assertFalse(desk.connectSelected(approval = desk.profileTrust(profile, probed(b, otherKey)).copy(certificate = probed("ef".repeat(32), key))))
            assertEquals(a, ProfileStore(root.resolve("profiles.json")).load().single().sha256Pin)
            desk.setProfileSecret("secret")
            assertTrue(desk.connectSelected(approval = trust))
            assertEquals(b, routed.last().fingerprint)
            val saved = ProfileStore(root.resolve("profiles.json")).load().single()
            assertEquals(b, saved.sha256Pin); assertEquals(key, saved.sha256Spki)
            assertFalse(Files.exists(root.resolve("certificates.json")), "a pinned profile's approval goes to its own pin, not the trust file")
        } finally { desk.close(); workspace.close(); root.toFile().deleteRecursively() }
    }

    @Test fun aNewKeyIsAChangeAndAnUnknownKeyIsNeitherSameNorDifferent() = runTest {
        val (desk, workspace, root) = desk()
        try {
            val profile = desk.state.value.profiles.single()
            val replaced = desk.profileTrust(profile, probed(b, otherKey))
            assertTrue(replaced.changed); assertFalse(replaced.sameKey); assertTrue(replaced.keyChanged); assertTrue(replaced.canApprove)
            val same = desk.profileTrust(profile, probed(a, key))
            assertFalse(same.needsApproval, "the pinned certificate itself needs nothing")
        } finally { desk.close(); workspace.close(); root.toFile().deleteRecursively() }
        val (desk2, workspace2, root2) = desk(spki = null)
        try {
            val unknown = desk2.profileTrust(desk2.state.value.profiles.single(), probed(b, key))
            assertTrue(unknown.changed); assertFalse(unknown.sameKey); assertFalse(unknown.keyChanged)
        } finally { desk2.close(); workspace2.close(); root2.toFile().deleteRecursively() }
    }

    @Test fun anOutOfDateCertificateIsNeverApprovable() = runTest {
        val (desk, workspace, root) = desk()
        desk.router = { _, _ -> }
        try {
            val profile = desk.state.value.profiles.single()
            val expired = desk.profileTrust(profile, probed(b, key, to = "2026-11-10T00:00:00Z"))
            assertTrue(expired.observedOutOfDate); assertFalse(expired.canApprove); assertTrue(expired.sameKey)
            desk.setProfileSecret("secret")
            assertFalse(desk.connectSelected(approval = expired))
            assertEquals(a, ProfileStore(root.resolve("profiles.json")).load().single().sha256Pin)
            // The Connect form refuses an out-of-date leaf as well.
            val manual = desk.manualTrust("https://pve-b.example.test:8006", b, probed(b, key, to = "2026-11-10T00:00:00Z"))
            assertTrue(manual.observedOutOfDate); assertFalse(manual.canApprove)
            desk.setManualSecret("secret")
            assertFalse(desk.connectManual("https://pve-b.example.test:8006", "root@pam", "", passwordLogin = true, observed = b, approval = manual))
            assertFalse(Files.exists(root.resolve("certificates.json")))
        } finally { desk.close(); workspace.close(); root.toFile().deleteRecursively() }
    }

    @Test fun theConnectFormAcceptsARenewalOfARememberedApprovalWithTheSameKey() = runTest {
        val (desk, workspace, root) = desk(spki = null)
        desk.router = { _, _ -> }
        try {
            val other = "https://pve-b.example.test:8006"
            CertificateTrustStore(root.resolve("certificates.json")).save(listOf(ApprovedCertificate(other, a, key)))
            val renewed = desk.manualTrust(other, b, probed(b, key))
            assertTrue(renewed.sameKey); assertTrue(renewed.canApprove)
            desk.setManualSecret("secret")
            assertTrue(desk.connectManual(other, "root@pam", "", passwordLogin = true, observed = b, approval = renewed))
            assertEquals(ApprovedCertificate(other, b, key), CertificateTrustStore(root.resolve("certificates.json")).load().single())
        } finally { desk.close(); workspace.close(); root.toFile().deleteRecursively() }
    }
}
