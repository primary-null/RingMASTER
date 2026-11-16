package local.ringleader.core

import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * Network audit W2: a background run connects with the same certificate the Desk would, without anything observed or newly
 * approved: the profile's own pin, else the single approved certificate for its server, refusing on a conflict. A changed
 * certificate is a skipped run that says so and where to review it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleTrustTest {
    private val origin = ScheduleFixtures.ORIGIN
    private val approvedPin = "ab".repeat(32)
    private val otherPin = "ef".repeat(32)
    private val base = Instant.parse("2026-11-20T05:55:00Z")
    private val due = Instant.parse("2026-11-20T06:00:00Z")

    @Test fun theResolutionIsTheProfilePinElseTheSingleApprovedCertificate() {
        val own = ScheduleFixtures.profile()
        val pinless = ScheduleFixtures.profile(pin = null)
        val approved = listOf(ApprovedCertificate(origin, approvedPin, "11".repeat(32)))
        assertEquals(ProfilePin.Pinned(ScheduleFixtures.PIN, null), resolveProfilePin(own, approved, listOf(own)))
        assertEquals(ProfilePin.Pinned(approvedPin, "11".repeat(32)), resolveProfilePin(pinless, approved, listOf(pinless)))
        assertEquals(ProfilePin.NormalTls, resolveProfilePin(pinless, emptyList(), listOf(pinless)))
        assertEquals(ProfilePin.NormalTls, resolveProfilePin(pinless, listOf(ApprovedCertificate("https://pve.example.test:8443", approvedPin)), listOf(pinless)), "another port is another server")
        // Another saved profile's pin to the same server takes part, as on the Desk; two different ones conflict.
        val sibling = ScheduleFixtures.profile(pin = approvedPin, id = "22222222-2222-4333-8444-555555555555", name = "Other")
        assertEquals(ProfilePin.Pinned(approvedPin, null), resolveProfilePin(pinless, emptyList(), listOf(pinless, sibling)))
        assertEquals(ProfilePin.Conflict, resolveProfilePin(pinless, listOf(ApprovedCertificate(origin, otherPin)), listOf(pinless, sibling)))
        // Host case and an explicit 443 are the same origin.
        val upper = pinless.copy(origin = "https://PVE.example.test:8006")
        assertEquals(ProfilePin.Pinned(approvedPin, "11".repeat(32)), resolveProfilePin(upper, approved, listOf(upper)))
    }

    /** Records the pin of every background connection, then answers as [FakeScheduleGateway] does (or refuses as [refuse] says). */
    private class Recording(val inner: FakeScheduleGateway = FakeScheduleGateway()) : ScheduleGateway by inner {
        val pins = mutableListOf<String>()
        var refuse: Exception? = null
        override suspend fun guests(connection: Connection): List<Guest> { pins += connection.fingerprint; refuse?.let { throw it }; return inner.guests(connection) }
    }

    private fun TestScope.engine(profile: ConnectionProfile, gateway: Recording, approved: () -> List<ApprovedCertificate>, profiles: List<ConnectionProfile> = listOf(profile)): Pair<ScheduleEngine, java.nio.file.Path> {
        val dir = Files.createTempDirectory("schedule-trust")
        val clock = VirtualClock(testScheduler, base.minusMillis(testScheduler.currentTime))
        val plan = ScheduleFixtures.plan(notBefore = base).let { it.copy(target = ScheduleFixtures.target(profile = profile)) }
        ScheduleStore(dir.resolve("schedules.json")).apply { load(); save(ScheduleDocument(listOf(plan), listOf(BackgroundAccess(profile.id, profileBinding(profile), base)))) }
        val engine = ScheduleEngine(ScheduleStore(dir.resolve("schedules.json")), { profiles }, FakeScheduleSecrets(), gateway, MutationLeases(), clock,
            backgroundScope, StandardTestDispatcher(testScheduler), approvedCertificates = approved)
        return engine to dir
    }

    private fun TestScope.runDue(engine: ScheduleEngine): ScheduleRun {
        engine.start(); runCurrent()
        advanceTimeBy(Duration.between(base, due).toMillis() + 10_000); runCurrent()
        return engine.state.value.document.runs.single()
    }

    @Test fun aPinlessProfileRunsWithTheApprovedCertificateNotNormalTls() = runTest {
        val gateway = Recording()
        val (engine, dir) = engine(ScheduleFixtures.profile(pin = null), gateway, { listOf(ApprovedCertificate(origin, approvedPin)) })
        try {
            val run = runDue(engine)
            assertEquals(RunPhase.Succeeded, run.phase, run.detail)
            assertEquals(listOf(approvedPin), gateway.pins.distinct())
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun aConflictOrAnUnreadableTrustFileSkipsWithoutTouchingTheServer() = runTest {
        val pinless = ScheduleFixtures.profile(pin = null)
        val sibling = ScheduleFixtures.profile(pin = otherPin, id = "22222222-2222-4333-8444-555555555555", name = "Other")
        val conflicting = Recording()
        val (engine, dir) = engine(pinless, conflicting, { listOf(ApprovedCertificate(origin, approvedPin)) }, listOf(pinless, sibling))
        try {
            val run = runDue(engine)
            assertEquals(RunPhase.Skipped, run.phase); assertEquals(SCHEDULE_PIN_CONFLICT, run.detail)
            assertTrue(conflicting.pins.isEmpty()); assertEquals(0, conflicting.inner.dispatches)
        } finally { dir.toFile().deleteRecursively() }
        val unreadable = Recording()
        val (engine2, dir2) = engine(pinless, unreadable, { throw CertificateTrustStoreException("bad", true) })
        try {
            val run = runDue(engine2)
            assertEquals(RunPhase.Skipped, run.phase); assertEquals(SCHEDULE_TRUST_UNREADABLE, run.detail)
            assertTrue(unreadable.pins.isEmpty())
        } finally { dir2.toFile().deleteRecursively() }
    }

    @Test fun aChangedCertificateIsASkippedRunThatSaysToReviewItOnTheDesk() = runTest {
        val gateway = Recording().apply {
            refuse = ScheduleNotSentException(scheduleNotSentReason(javax.net.ssl.SSLHandshakeException("x").apply { initCause(PinMismatchException(otherPin, null)) }))
        }
        val (engine, dir) = engine(ScheduleFixtures.profile(pin = null), gateway, { listOf(ApprovedCertificate(origin, approvedPin)) })
        try {
            val run = runDue(engine)
            assertEquals(RunPhase.Skipped, run.phase)
            assertEquals("The server's certificate changed since it was trusted; review it on the Desk. Nothing was sent.", run.detail)
            assertEquals(0, gateway.inner.dispatches)
        } finally { dir.toFile().deleteRecursively() }
    }

    /** The real gateway against a loopback server whose certificate is no longer the approved one. */
    @Test fun theLiveGatewayReportsAChangedCertificate() = TrustFixtures.selfSigned("pve", "-ext", "SAN=ip:127.0.0.1").use { keys ->
        val oldPin = keys.pin()
        keys.reissue("-ext", "SAN=ip:127.0.0.1")
        FixtureHttps(keys.serverContext()).use { server ->
            Connection(server.address, "sched@pve!runner", "token-secret", oldPin).use { connection ->
                val refused = assertFailsWith<ScheduleNotSentException> { runBlocking { LiveScheduleGateway(2000, 2000).guests(connection) } }
                assertEquals("The server's certificate changed since it was trusted; review it on the Desk. Nothing was sent.", refused.message)
            }
            assertEquals(0, server.requests.get())
        }
    }
}
