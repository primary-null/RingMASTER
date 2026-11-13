package local.ringleader.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Wall and monotonic time that move only when a test says so, and can move apart (a clock change). */
internal class ManualWatchClock(var wall: Instant, var mono: Long = 1_000_000L) : WatchClock {
    override fun now(): Instant = wall
    override fun monotonicMillis(): Long = mono
    fun advance(millis: Long) { wall = wall.plusMillis(millis); mono += millis }
}

internal class RecordingSink : NotificationSink {
    val events = mutableListOf<NotificationEvent>()
    override fun post(event: NotificationEvent): PostResult { events += event; return PostResult.Posted }
    fun titles() = events.map { it.title }
    fun take(): List<NotificationEvent> = events.toList().also { events.clear() }
}

class WatcherTest {
    private val origin = "https://pve.example.test:8006"
    private val t0 = Instant.parse("2026-11-13T10:00:00Z")
    private val profileId = ScheduleFixtures.PROFILE_ID
    private val scope = WatchScope.of(profileId, DIRECT_PVE_KIND, origin, "watch@pve!ro", ScheduleFixtures.PIN)
    private val vm = WatchResource(WatchKind.VM, vmid = 104)
    private val nodeA = WatchResource(WatchKind.Node, node = "node-a")
    private val gib = 1024L * 1024 * 1024

    private inner class Rig(val dir: Path = Files.createTempDirectory("watcher"), val clock: ManualWatchClock = ManualWatchClock(t0), rules: WatchRules = WatchRules()) {
        val file: Path = dir.resolve("watcher.json")
        var failMove = false
        val sink = RecordingSink()
        val watcher = Watcher(WatchStore(file) { temporary, target ->
            if (failMove) throw IOException("disk full")
            Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }, sink, clock, rules).also { it.load() }
        var session = WatchSession(scope, 1, 1)
        private var uptime = 100L
        fun open(next: WatchSession = session): Boolean { session = next; return watcher.open(next) }
        fun status(resource: WatchResource = vm, binding: String = scope.binding) = watcher.state.value.watched.single { it.entry.resource == resource && it.entry.binding == binding }

        /** A guest row whose uptime moves with every call, like a server that refreshed its numbers. */
        fun guest(cpu: Double? = 0.10, mem: Long? = 1 * gib, status: String = "running", id: Int = 104, name: String = "alpha", limit: Long? = 4 * gib, remote: String = origin) =
            Guest(remote, id, name, "node-a", "VM", status, metrics = GuestListMetrics(cpuFraction = cpu, memoryBytes = mem, memoryLimitBytes = limit, uptimeSeconds = uptime++))
        fun member(cpu: Double? = 0.10, mem: Long? = 8 * gib, status: String = "online", name: String = "node-a") =
            RecordedMember(origin, name, status, null, cpu, 8.0, mem, 32 * gib, 1, 10, uptime++)

        /** Advance both clocks by [afterMillis], then hand over a fresh reading taken now. */
        fun read(guests: List<Guest> = listOf(guest()), members: List<RecordedMember> = emptyList(), afterMillis: Long = 10_000,
            nodesCovered: Boolean = true, covered: Set<String>? = null, on: WatchSession = session): WatchObservation {
            clock.advance(afterMillis)
            return watcher.observeInventory(on, WatchInventoryReading.Fresh(clock.wall, guests, members, nodesCovered, covered))
        }
        fun hot(afterMillis: Long = 10_000) = read(listOf(guest(cpu = 0.92)), afterMillis = afterMillis)
        fun calm(afterMillis: Long = 10_000) = read(listOf(guest(cpu = 0.10)), afterMillis = afterMillis)
        fun fail() = watcher.observeInventory(session, WatchInventoryReading.Failed)
        fun watchVm() = assertEquals(WatchResult.Ok, watcher.watch(scope, vm, "alpha"))
        fun stress() { hot(); hot(); assertTrue(status().stressed) }
    }

    // ---- Thresholds and two consecutive readings ----

    @Test fun aSingleHotSampleRestartsTheRecoveryHold() {
        val rig = Rig(); rig.watchVm(); rig.open(); rig.stress()
        rig.sink.take()
        rig.calm(); rig.calm()
        rig.calm(afterMillis = 50_000)
        rig.hot(afterMillis = 2_000)
        rig.calm(afterMillis = 10_000)
        assertTrue(rig.sink.events.isEmpty(), "the old calm period was interrupted")
        rig.calm(afterMillis = 60_000)
        assertEquals(1, rig.sink.events.size)
        assertTrue(rig.sink.events.single().title.endsWith("back to normal"))
    }

    @Test fun configuredThresholdsAreStrict() {
        val rules = WatchRules()
        assertEquals(LoadReading.Calm, loadReading(75.0, 95.0, rules), "exactly at the limit is not hot")
        assertEquals(LoadReading.Hot, loadReading(75.01, 10.0, rules))
        assertEquals(LoadReading.Hot, loadReading(10.0, 95.01, rules))
        assertEquals(LoadReading.Hot, loadReading(null, 99.0, rules), "one known hot value is enough")
        assertEquals(LoadReading.Unknown, loadReading(10.0, null, rules), "a missing value cannot vouch for calm")
        assertEquals(LoadReading.Unknown, loadReading(null, null, rules))
        assertNull(cpuPercentOf(Double.NaN)); assertNull(cpuPercentOf(Double.POSITIVE_INFINITY)); assertNull(cpuPercentOf(-0.1)); assertNull(cpuPercentOf(null))
        assertEquals(50.0, cpuPercentOf(0.5))
        assertNull(usagePercent(5, 0)); assertNull(usagePercent(null, 10)); assertNull(usagePercent(5, null)); assertNull(usagePercent(-1, 10))
        assertEquals(96.0, usagePercent(96, 100))
        assertEquals(2, rules.readings); assertEquals(2_000, rules.minGap.toMillis())
    }

    @Test fun twoConsecutiveHotReadingsStressAndTwoCalmOnesClear() {
        val rig = Rig(); rig.watchVm(); rig.open()
        assertEquals(WatchAvailability.Waiting, rig.status().availability)
        assertEquals(WatchObservation.Accepted, rig.hot())
        assertFalse(rig.status().stressed, "one reading is not enough")
        assertEquals(WatchAvailability.Live, rig.status().availability)
        rig.hot()
        assertTrue(rig.status().stressed && rig.status().stressedNow)
        assertEquals(92.0, rig.status().cpuPercent!!, 0.001)
        assertEquals(listOf("alpha (104) is under load"), rig.sink.titles())
        val event = rig.sink.events.single()
        assertEquals(NotificationCategory.WatchStress, event.category)
        assertEquals("CPU 92 %, memory 25 %", event.body)
        assertEquals(NotificationTarget.Resource(profileId, scope.binding, vm), event.target)
        rig.calm()
        assertTrue(rig.status().stressed, "one calm reading does not clear")
        rig.calm()
        assertFalse(rig.status().stressed)
    }

    @Test fun memoryAloneStressesAboveNinetyFivePercent() {
        val rig = Rig(); rig.watchVm(); rig.open()
        rig.read(listOf(rig.guest(cpu = 0.05, mem = (3.7 * gib).toLong()))); rig.read(listOf(rig.guest(cpu = 0.05, mem = (3.7 * gib).toLong())))
        assertFalse(rig.status().stressed, "92 % memory is page cache territory")
        rig.read(listOf(rig.guest(cpu = 0.05, mem = (3.9 * gib).toLong()))); rig.read(listOf(rig.guest(cpu = 0.05, mem = (3.9 * gib).toLong())))
        assertTrue(rig.status().stressed)
    }

    @Test fun aSingleSpikeOrASingleQuietReadingChangesNothing() {
        val rig = Rig(); rig.watchVm(); rig.open()
        rig.calm(); rig.hot(); rig.calm(); rig.hot(); rig.calm()
        assertFalse(rig.status().stressed)
        assertTrue(rig.sink.events.isEmpty())
        rig.hot(); rig.hot()
        assertTrue(rig.status().stressed)
        rig.calm(); rig.hot(); rig.calm(); rig.hot()
        assertTrue(rig.status().stressed, "single quiet readings do not clear")
        assertEquals(1, rig.sink.events.size)
    }

    // ---- sample freshness ----

    @Test fun aRepeatedSnapshotIsNotANewSample() {
        val rig = Rig(); rig.watchVm(); rig.open()
        rig.hot()
        // The same snapshot delivered again (a recomposition, an unrelated state change): same read time.
        val same = WatchInventoryReading.Fresh(rig.clock.wall, listOf(rig.guest(cpu = 0.92)), emptyList(), true)
        repeat(5) { rig.clock.advance(3_000); assertEquals(WatchObservation.Duplicate, rig.watcher.observeInventory(rig.session, same)) }
        assertFalse(rig.status().stressed, "one sample seen six times is still one reading")
        // An older snapshot arriving late is refused too.
        val older = WatchInventoryReading.Fresh(rig.clock.wall.minusSeconds(30), listOf(rig.guest(cpu = 0.92)), emptyList(), true)
        assertEquals(WatchObservation.Duplicate, rig.watcher.observeInventory(rig.session, older))
        assertFalse(rig.status().stressed)
    }

    @Test fun readingsCloserThanTwoSecondsCountOnce() {
        val rig = Rig(); rig.watchVm(); rig.open()
        rig.hot()
        assertEquals(WatchObservation.Accepted, rig.hot(afterMillis = 1_999))
        assertFalse(rig.status().stressed, "1.999 s apart is the same sample fetched twice")
        rig.hot(afterMillis = 1)
        assertTrue(rig.status().stressed, "2 s after the counted reading counts")
    }

    @Test fun theSameServerReadingPolledTwiceCountsOnce() {
        val rig = Rig(); rig.watchVm(); rig.open()
        val frozen = Guest(origin, 104, "alpha", "node-a", "VM", "running", metrics = GuestListMetrics(cpuFraction = 0.92, memoryBytes = gib, memoryLimitBytes = 4 * gib, uptimeSeconds = 500))
        rig.read(listOf(frozen), afterMillis = 5_000)
        rig.read(listOf(frozen), afterMillis = 5_000)
        assertFalse(rig.status().stressed, "identical numbers 5 s later are the server's same reading")
        // Once the server refreshed (uptime moved), the second reading counts.
        rig.read(listOf(frozen.copy(metrics = frozen.metrics!!.copy(uptimeSeconds = 510))), afterMillis = 5_000)
        assertTrue(rig.status().stressed)
        // Numbers that never move still count again after the hold window.
        val other = Rig(); other.watchVm(); other.open()
        other.read(listOf(frozen), afterMillis = 5_000); other.read(listOf(frozen), afterMillis = 12_000)
        assertTrue(other.status().stressed)
    }

    @Test fun readingsFarApartAreNotConsecutive() {
        val rig = Rig(); rig.watchVm(); rig.open()
        rig.hot()
        // Live refresh was paused for six minutes: stay inside the stale limit by re-reading, but the gap breaks the streak.
        rig.hot(afterMillis = 6 * 60_000)
        assertFalse(rig.status().stressed)
        rig.hot()
        assertTrue(rig.status().stressed)
    }

    @Test fun aStaleOrFutureSampleIsRefused() {
        val rig = Rig(); rig.watchVm(); rig.open()
        rig.hot()
        val old = WatchInventoryReading.Fresh(rig.clock.wall.plusSeconds(1), listOf(rig.guest(cpu = 0.92)), emptyList(), true)
        rig.clock.advance(120_000)
        assertEquals(WatchObservation.Stale, rig.watcher.observeInventory(rig.session, old), "read two minutes ago")
        val future = WatchInventoryReading.Fresh(rig.clock.wall.plusSeconds(600), listOf(rig.guest(cpu = 0.92)), emptyList(), true)
        assertEquals(WatchObservation.Stale, rig.watcher.observeInventory(rig.session, future))
        assertFalse(rig.status().stressed)
        // A refused sample does not poison the next good one.
        assertEquals(WatchObservation.Accepted, rig.hot())
    }

    @Test fun gapsUseTheMonotonicClockNotTheWallClock() {
        val rig = Rig(); rig.watchVm(); rig.open()
        rig.hot()
        // The wall clock jumps forward an hour (NTP, DST by hand); only 500 ms really passed.
        rig.clock.wall = rig.clock.wall.plus(Duration.ofHours(1)); rig.clock.mono += 500
        rig.watcher.observeInventory(rig.session, WatchInventoryReading.Fresh(rig.clock.wall, listOf(rig.guest(cpu = 0.92)), emptyList(), true))
        assertFalse(rig.status().stressed, "500 ms of real time is one sample, whatever the wall clock says")
        // The wall clock is set back: readings stamped earlier than the last accepted one are duplicates, never a streak.
        rig.clock.wall = rig.clock.wall.minus(Duration.ofHours(2)); rig.clock.mono += 10_000
        assertEquals(WatchObservation.Duplicate, rig.watcher.observeInventory(rig.session, WatchInventoryReading.Fresh(rig.clock.wall, listOf(rig.guest(cpu = 0.92)), emptyList(), true)))
        assertFalse(rig.status().stressed)
    }

    // ---- unknown stays unknown ----

    @Test fun missingOrUnusableMetricsNeverClearStress() {
        val rig = Rig(); rig.watchVm(); rig.open(); rig.stress()
        rig.sink.take()
        val unusable = listOf(
            rig.guest(cpu = null, mem = null), rig.guest(cpu = Double.NaN, mem = gib), rig.guest(cpu = 0.05, mem = null),
            rig.guest(cpu = 0.05, mem = gib, limit = 0), rig.guest(cpu = Double.NEGATIVE_INFINITY, mem = null), rig.guest(cpu = -1.0, mem = gib, limit = null),
        )
        repeat(3) { unusable.forEach { rig.read(listOf(it)) } }
        assertTrue(rig.status().stressed, "eighteen unusable readings clear nothing")
        assertEquals(LoadReading.Unknown, rig.status().lastReading)
        assertEquals(WatchAvailability.Live, rig.status().availability)
        assertTrue(rig.sink.events.isEmpty(), "and announce nothing")
        // An unusable reading between two calm ones breaks the pair.
        rig.calm(); rig.read(listOf(rig.guest(cpu = null, mem = null))); rig.calm()
        assertTrue(rig.status().stressed)
        rig.calm()
        assertFalse(rig.status().stressed)
    }

    @Test fun unusableMetricsNeverStartStressEither() {
        val rig = Rig(); rig.watchVm(); rig.open()
        rig.hot(); rig.read(listOf(rig.guest(cpu = null, mem = null))); rig.hot()
        assertFalse(rig.status().stressed, "hot, unknown, hot is not two consecutive readings")
    }

    @Test fun failedReadsNeverClearStressOrAnnounceAnything() {
        val rig = Rig(); rig.watchVm(); rig.open(); rig.stress()
        rig.sink.take()
        repeat(10) { rig.clock.advance(10_000); assertEquals(WatchObservation.Accepted, rig.fail()) }
        assertTrue(rig.status().stressed)
        assertFalse(rig.status().stressedNow, "the last confirmed state is shown as not live")
        assertEquals(WatchAvailability.Stale, rig.status().availability)
        assertEquals("running", rig.status().status, "the last known state is kept")
        assertTrue(rig.sink.events.isEmpty())
        // A calm reading before the failure and one after it are not consecutive.
        val other = Rig(); other.watchVm(); other.open(); other.stress()
        other.calm(); other.clock.advance(5_000); other.fail(); other.calm()
        assertTrue(other.status().stressed)
        other.calm()
        assertFalse(other.status().stressed)
    }

    @Test fun aSessionThatStopsDeliveringIsShownAsNotUpdating() {
        val rig = Rig(); rig.watchVm(); rig.open(); rig.stress()
        rig.clock.advance(4 * 60_000); rig.watcher.tick()
        assertEquals(WatchAvailability.Live, rig.status().availability)
        rig.clock.advance(2 * 60_000); rig.watcher.tick()
        assertEquals(WatchAvailability.Stale, rig.status().availability)
        assertTrue(rig.status().stressed && !rig.status().stressedNow)
        rig.hot()
        assertEquals(WatchAvailability.Live, rig.status().availability)
    }

    @Test fun anUnknownStateWordIsNotAStateChangeAndGivesNoLoadVerdict() {
        val rig = Rig(); rig.watcher.watch(scope, nodeA, "node-a"); rig.watchVm(); rig.open()
        rig.read(members = listOf(rig.member()))
        rig.read(listOf(rig.guest(status = "")), members = listOf(rig.member(status = "unknown")))
        rig.read(listOf(rig.guest(status = "io-error")), members = listOf(rig.member(status = "unknown")))
        assertEquals("online", rig.status(nodeA).status); assertEquals("running", rig.status(vm).status)
        rig.read(members = listOf(rig.member()))
        assertTrue(rig.sink.events.isEmpty(), "unknown and back is not offline and back")
    }

    // ---- missing resources ----

    @Test fun aWatchedResourceThatIsNotListedStaysWatchedAsUnavailable() {
        val rig = Rig(); rig.watchVm(); rig.open(); rig.stress()
        rig.sink.take()
        rig.read(guests = emptyList())
        assertEquals(WatchAvailability.Live, rig.status().availability, "one read without it is not enough")
        rig.read(guests = emptyList())
        val missing = rig.status()
        assertEquals(WatchAvailability.Missing, missing.availability)
        assertTrue(missing.stressed, "absence does not clear the last confirmed stress")
        assertFalse(missing.stressedNow)
        assertEquals("running", missing.status)
        val event = rig.sink.take().single()
        assertEquals("alpha (104) is no longer listed", event.title)
        assertEquals(NotificationCategory.WatchState, event.category)
        assertFalse(listOf("stopped", "down", "offline", "normal").any { it in (event.title + event.body).lowercase() }, "absence is not reported as down or recovered")
        repeat(5) { rig.read(guests = emptyList()) }
        assertTrue(rig.sink.events.isEmpty(), "said once")
        assertTrue(rig.watcher.state.value.watching(scope.binding, vm))
        // It comes back hot: listed again, and still stressed without a second "under load".
        rig.hot()
        assertEquals(listOf("alpha (104) is listed again"), rig.sink.titles())
        assertEquals(WatchAvailability.Live, rig.status().availability)
        assertTrue(rig.status().stressedNow)
    }

    @Test fun aResourceNeverSeenIsMissingWithoutAnAlert() {
        val rig = Rig(); rig.watchVm(); rig.open()
        repeat(4) { rig.read(guests = listOf(rig.guest(id = 200))) }
        assertEquals(WatchAvailability.Missing, rig.status().availability)
        assertTrue(rig.sink.events.isEmpty(), "nothing was ever known about it, so nothing changed")
    }

    @Test fun rowsNotCoveredByAReadAreUnknownNotMissing() {
        val rig = Rig(); rig.watcher.watch(scope, nodeA, "node-a"); rig.open()
        rig.read(members = listOf(rig.member(cpu = 0.9))); rig.read(members = listOf(rig.member(cpu = 0.9)))
        assertTrue(rig.status(nodeA).stressed)
        rig.sink.take()
        // The node index of these reads failed: the member list is empty or kept from before, either way not current.
        repeat(4) { rig.read(members = emptyList(), nodesCovered = false) }
        repeat(4) { rig.read(members = listOf(rig.member(cpu = 0.01, status = "offline")), nodesCovered = false) }
        assertEquals(WatchAvailability.Stale, rig.status(nodeA).availability)
        assertTrue(rig.status(nodeA).stressed); assertEquals("online", rig.status(nodeA).status)
        assertTrue(rig.sink.events.isEmpty())

        // PDM: a remote that failed keeps its previous guest rows; they are not facts of this read.
        val pdm = WatchScope.of(null, PDM_KIND, origin, "watch@pdm!ro", null)
        val fleet = WatchResource(WatchKind.VM, remoteScope = "site-a", vmid = 300)
        rig.watcher.watch(pdm, fleet, "edge")
        val session = WatchSession(pdm, 2, 1)
        rig.watcher.open(session)
        fun row(status: String) = Guest(origin, 300, "edge", "n1", "VM", status, scope = "site-a", metrics = GuestListMetrics(cpuFraction = 0.1, memoryBytes = gib, memoryLimitBytes = 4 * gib, uptimeSeconds = rig.clock.mono))
        rig.read(listOf(row("running")), covered = setOf("site-a"), on = session)
        repeat(3) { rig.read(listOf(row("stopped")), covered = emptySet(), on = session) }
        repeat(3) { rig.read(emptyList(), covered = setOf("site-b"), on = session) }
        assertEquals("running", rig.status(fleet, pdm.binding).status)
        assertEquals(WatchAvailability.Stale, rig.status(fleet, pdm.binding).availability)
        assertTrue(rig.sink.events.isEmpty())
        rig.read(listOf(row("stopped")), covered = setOf("site-a"), on = session)
        assertEquals(listOf("edge (300) is now stopped"), rig.sink.titles())
    }

    @Test fun aResourceListedTwiceIsAmbiguousAndIgnored() {
        val rig = Rig(); rig.watchVm(); rig.open()
        rig.calm()
        repeat(3) { rig.read(listOf(rig.guest(cpu = 0.95), rig.guest(cpu = 0.95, status = "stopped"))) }
        assertFalse(rig.status().stressed); assertEquals("running", rig.status().status)
        assertTrue(rig.sink.events.isEmpty())
    }

    // ---- state changes ----

    @Test fun knownStateChangesAreAnnouncedOnceWithACooldown() {
        val rig = Rig(); rig.watchVm(); rig.watcher.watch(scope, nodeA, "node-a"); rig.open()
        val online = { rig.member() }
        rig.read(members = listOf(online()))
        assertTrue(rig.sink.events.isEmpty(), "the first known state is the baseline, not a change")
        rig.read(listOf(rig.guest(status = "stopped")), listOf(online()))
        val stopped = rig.sink.take().single()
        assertEquals("alpha (104) is now stopped", stopped.title); assertEquals("It was running before.", stopped.body)
        assertEquals(NotificationSeverity.Warning, stopped.severity)
        repeat(3) { rig.read(listOf(rig.guest(status = "stopped")), listOf(online())) }
        assertTrue(rig.sink.events.isEmpty(), "an unchanged state is not news")
        // Flapping inside the cooldown: start, stop, start within a minute gives no burst...
        rig.read(listOf(rig.guest(status = "running")), listOf(online()), afterMillis = 5_000)
        rig.read(listOf(rig.guest(status = "stopped")), listOf(online()), afterMillis = 5_000)
        rig.read(listOf(rig.guest(status = "running")), listOf(online()), afterMillis = 5_000)
        assertTrue(rig.sink.events.isEmpty())
        // ...and the state after the cooldown is reported once.
        rig.read(listOf(rig.guest(status = "running")), listOf(online()), afterMillis = 60_000)
        assertEquals(listOf("alpha (104) is now running"), rig.sink.take().map { it.title })
        // Node offline is an error; node back is its own event.
        rig.read(members = listOf(rig.member(status = "offline")), afterMillis = 61_000)
        val offline = rig.sink.take().single()
        assertEquals("Node node-a is now offline", offline.title); assertEquals(NotificationSeverity.Error, offline.severity)
        rig.read(members = listOf(online()), afterMillis = 61_000)
        assertEquals(listOf("Node node-a is now online"), rig.sink.take().map { it.title })
        assertEquals(4, listOf(stopped, offline).map { it.identity.id }.plus(listOf("x", "y")).distinct().size)
    }

    @Test fun aStoppedGuestClearsStressSilently() {
        val rig = Rig(); rig.watchVm(); rig.open(); rig.stress()
        rig.sink.take()
        rig.read(listOf(rig.guest(cpu = 0.0, mem = 0, status = "stopped")))
        assertFalse(rig.status().stressed)
        assertNull(rig.status().cpuPercent)
        assertEquals(listOf("alpha (104) is now stopped"), rig.sink.take().map { it.title }, "the state change says it; there is no 'back to normal'")
        repeat(20) { rig.read(listOf(rig.guest(cpu = 0.0, mem = 0, status = "stopped"))) }
        assertTrue(rig.sink.events.isEmpty())
    }

    // ---- cooldown and flapping ----

    @Test fun metricsUpdatesWhileStressedAreNotNewAlerts() {
        val rig = Rig(); rig.watchVm(); rig.open(); rig.stress()
        repeat(200) { rig.read(listOf(rig.guest(cpu = 0.80 + (it % 15) / 100.0))) }
        assertEquals(1, rig.sink.events.size, "33 minutes of hot readings is one alert")
    }

    @Test fun recoveryIsAnnouncedOnlyAfterItHeld() {
        val rig = Rig(); rig.watchVm(); rig.open(); rig.stress()
        rig.sink.take()
        rig.calm(); rig.calm()
        assertFalse(rig.status().stressed)
        assertTrue(rig.sink.events.isEmpty(), "calm just now: not announced yet")
        repeat(5) { rig.calm() }
        assertTrue(rig.sink.events.isEmpty(), "50 s of calm")
        rig.calm()
        val event = rig.sink.take().single()
        assertEquals("alpha (104) is back to normal", event.title)
        assertEquals(NotificationSeverity.Success, event.severity)
        assertTrue(event.body.startsWith("CPU 10 %, memory 25 %"))
        repeat(10) { rig.calm() }
        assertTrue(rig.sink.events.isEmpty())
    }

    @Test fun flappingAroundTheLimitGivesOneAlertNotAStorm() {
        val rig = Rig(); rig.watchVm(); rig.open(); rig.stress()
        assertEquals(1, rig.sink.events.size)
        // Calm for 20 s, hot for 20 s, over and over for ten minutes: the recovery never holds, so nothing is posted.
        repeat(15) { rig.calm(); rig.calm(); rig.hot(); rig.hot() }
        assertEquals(1, rig.sink.events.size)
        // A real recovery, then a relapse inside the cooldown of the first alert: recovery yes, second alert not yet.
        val quick = Rig(); quick.watchVm(); quick.open(); quick.stress()
        repeat(8) { quick.calm() }
        assertEquals(listOf("alpha (104) is under load", "alpha (104) is back to normal"), quick.sink.titles())
        quick.hot(); quick.hot()
        assertTrue(quick.status().stressed, "the state itself is always current")
        assertEquals(2, quick.sink.events.size, "a second alert inside ten minutes is held back")
        // Still hot when the cooldown ends: announced then, once.
        repeat(60) { quick.hot() }
        assertEquals(3, quick.sink.events.size)
        assertEquals("alpha (104) is under load", quick.sink.events.last().title)
        assertFalse(quick.sink.events[0].identity.id == quick.sink.events[2].identity.id, "a new onset is a new occurrence")
    }

    // ---- sessions, scope and epochs ----

    @Test fun nothingIsAcceptedWithoutAnOpenSession() {
        val rig = Rig(); rig.watchVm()
        assertEquals(WatchAvailability.NotConnected, rig.status().availability)
        assertEquals(WatchObservation.NoSession, rig.hot())
        assertEquals(WatchObservation.NoSession, rig.watcher.observeTasks(rig.session, WatchTaskReading.Fresh(rig.clock.wall, emptyList())))
        assertNull(rig.status().lastSampleAt)
    }

    @Test fun aDisconnectedSessionCannotEmitAndReconnectStartsCounting() {
        val rig = Rig(); rig.watchVm(); rig.open(); rig.stress()
        rig.sink.take()
        val old = rig.session
        rig.watcher.close(old)
        assertEquals(WatchAvailability.NotConnected, rig.status().availability)
        assertTrue(rig.status().stressed && !rig.status().stressedNow, "last confirmed, not live")
        // Late data from the closed session, including a state change and calm readings: all refused.
        repeat(5) { assertEquals(WatchObservation.NoSession, rig.read(listOf(rig.guest(cpu = 0.01, status = "stopped")), on = old)) }
        assertFalse(rig.watcher.open(old), "a closed session cannot be opened again")
        assertTrue(rig.sink.events.isEmpty())
        assertEquals("running", rig.status().status)
        // Reconnect: a new epoch. One calm reading before the disconnect and one after are not a pair.
        assertTrue(rig.open(WatchSession(scope, 1, 2)))
        assertEquals(WatchAvailability.Waiting, rig.status().availability)
        rig.calm()
        assertTrue(rig.status().stressed)
        rig.calm()
        assertFalse(rig.status().stressed)
        assertTrue(rig.sink.events.isEmpty(), "recovery still has to hold")
        repeat(6) { rig.calm() }
        assertEquals(listOf("alpha (104) is back to normal"), rig.sink.titles())
    }

    @Test fun reconnectWhileStillHotDoesNotAlertTwice() {
        val rig = Rig(); rig.watchVm(); rig.open(); rig.stress()
        rig.watcher.close(rig.session)
        rig.open(WatchSession(scope, 1, 2))
        repeat(10) { rig.hot() }
        assertEquals(1, rig.sink.events.size)
    }

    @Test fun anOlderEpochAndAReplacedSessionAreRefused() {
        val rig = Rig(); rig.watchVm()
        val first = WatchSession(scope, 1, 5)
        assertTrue(rig.open(first))
        assertFalse(rig.watcher.open(WatchSession(scope, 1, 4)), "an older epoch of the same session")
        assertEquals(WatchObservation.NoSession, rig.read(on = WatchSession(scope, 1, 4)))
        // A newer epoch replaces it; the old one is refused from then on.
        val second = WatchSession(scope, 1, 6)
        assertTrue(rig.open(second))
        assertEquals(WatchObservation.NoSession, rig.read(listOf(rig.guest(cpu = 0.95)), on = first))
        assertFalse(rig.watcher.open(first))
        // Another window tab to the same connection takes over; the previous tab's data is refused.
        val tab = WatchSession(scope, 2, 1)
        assertTrue(rig.open(tab))
        assertEquals(WatchObservation.NoSession, rig.read(on = second))
        assertEquals(WatchObservation.Accepted, rig.read(on = tab))
        // Closing a session that is not the current one changes nothing for the current one.
        rig.watcher.close(second)
        assertEquals(WatchObservation.Accepted, rig.read(on = tab))
    }

    @Test fun aChangedEndpointAccountOrPinStopsTheWatch() {
        val rig = Rig(); rig.watchVm(); rig.open(); rig.stress()
        rig.sink.take()
        rig.watcher.close(rig.session)
        val changed = listOf(
            WatchScope.of(profileId, DIRECT_PVE_KIND, "https://other.example.test:8006", "watch@pve!ro", ScheduleFixtures.PIN),
            WatchScope.of(profileId, DIRECT_PVE_KIND, origin, "admin@pve!ro", ScheduleFixtures.PIN),
            WatchScope.of(profileId, DIRECT_PVE_KIND, origin, "watch@pve!ro", "ef".repeat(32)),
            WatchScope.of(profileId, DIRECT_PVE_KIND, origin, "watch@pve!ro", null),
            WatchScope.of(profileId, PDM_KIND, origin, "watch@pve!ro", ScheduleFixtures.PIN),
        )
        assertEquals(changed.size + 1, (changed.map { it.binding } + scope.binding).distinct().size)
        changed.forEachIndexed { index, other ->
            val session = WatchSession(other, 10L + index, 1)
            assertTrue(rig.watcher.open(session))
            // Same VMID, calm and stopped, on the other connection: not this watch's data.
            repeat(4) {
                rig.clock.advance(10_000)
                val row = Guest(other.remote, 104, "alpha", "node-a", "VM", "stopped", metrics = GuestListMetrics(cpuFraction = 0.0, memoryBytes = 0, memoryLimitBytes = gib, uptimeSeconds = 0))
                assertEquals(WatchObservation.Accepted, rig.watcher.observeInventory(session, WatchInventoryReading.Fresh(rig.clock.wall, listOf(row), emptyList(), true)))
            }
            val status = rig.status()
            assertEquals(WatchAvailability.BindingChanged, status.availability, "case $index")
            assertTrue(status.stressed); assertEquals("running", status.status)
            assertFalse(rig.watcher.state.value.watching(other.binding, vm), "the watch does not follow the profile to another connection")
            rig.watcher.close(session)
        }
        assertTrue(rig.sink.events.isEmpty())
        // Back on the original connection it resumes.
        rig.open(WatchSession(scope, 99, 1))
        rig.hot()
        assertEquals(WatchAvailability.Live, rig.status().availability)
    }

    @Test fun rowsOfAnotherConnectionAreRefused() {
        val rig = Rig(); rig.watchVm(); rig.open(); rig.stress()
        rig.sink.take()
        val foreign = rig.guest(cpu = 0.01, status = "stopped", remote = "https://other.example.test:8006")
        repeat(3) { assertEquals(WatchObservation.WrongRemote, rig.read(listOf(foreign))) }
        assertTrue(rig.status().stressed); assertTrue(rig.sink.events.isEmpty())
        assertEquals(WatchObservation.WrongRemote, rig.read(members = listOf(RecordedMember("https://other.example.test:8006", "node-a", "online", null, 0.1, 4.0, 1, 2, 1, 2, 3))))
    }

    @Test fun demoAndRealWatchesAreIsolated() {
        val rig = Rig(); rig.watchVm(); rig.open()
        val demoVm = WatchResource(WatchKind.VM, vmid = 104)
        assertEquals(WatchResult.Ok, rig.watcher.watch(WatchScope.Demo, demoVm, "demo-alpha"))
        val demo = WatchSession(WatchScope.Demo, 7, 1)
        assertTrue(rig.watcher.open(demo))
        fun demoRow(cpu: Double) = Guest(DEMO_REMOTE_KEY, 104, "demo-alpha", "demo-node-a", "VM", "running", metrics = GuestListMetrics(cpuFraction = cpu, memoryBytes = gib, memoryLimitBytes = 4 * gib, uptimeSeconds = rig.clock.mono))
        repeat(2) { rig.read(listOf(demoRow(0.99)), on = demo) }
        assertTrue(rig.status(demoVm, DEMO_WATCH_BINDING).stressed)
        assertFalse(rig.status(vm, scope.binding).stressed, "the real VM 104 heard nothing of the demo's VM 104")
        val event = rig.sink.take().single()
        assertTrue(event.demo); assertEquals("demo-alpha (104) is under load", event.title)
        // Demo rows offered to the real session, and real rows to the demo session, are refused.
        assertEquals(WatchObservation.WrongRemote, rig.read(listOf(demoRow(0.99))))
        assertEquals(WatchObservation.WrongRemote, rig.read(listOf(rig.guest(cpu = 0.99)), on = demo))
        // Demo watches are never written and end with the demo.
        assertFalse("demo" in Files.readString(rig.file).lowercase())
        rig.watcher.close(demo)
        assertEquals(listOf(scope.binding), rig.watcher.state.value.watched.map { it.entry.binding })
        // Real events are not marked demo.
        rig.stress()
        assertFalse(rig.sink.events.single().demo)
    }

    // ---- watch list and storage ----

    @Test fun watchesOfASavedProfilePersistWithoutAddressAccountOrMetrics() {
        val rig = Rig(); rig.watchVm(); rig.watcher.watch(scope, nodeA, "node-a"); rig.open(); rig.stress()
        assertEquals(WatchResult.Ok, rig.watcher.watch(scope, vm, "alpha"), "watching twice is one watch")
        val text = Files.readString(rig.file)
        assertFalse(listOf("pve.example.test", "watch@pve", ScheduleFixtures.PIN, "running", "cpu", "stress").any { it in text }, text)
        assertTrue(scope.binding in text && profileId in text)
        val again = Rig(rig.dir)
        assertEquals(listOf(vm, nodeA), again.watcher.state.value.watched.map { it.entry.resource })
        val restored = again.status()
        assertEquals("alpha", restored.entry.name)
        assertFalse(restored.stressed, "state is not persisted: after a restart nothing is known")
        assertEquals(WatchAvailability.NotConnected, restored.availability); assertNull(restored.status)
        assertEquals(WatchResult.Ok, again.watcher.unwatch(scope.binding, vm))
        assertEquals(listOf(nodeA), Rig(rig.dir).watcher.state.value.watched.map { it.entry.resource })
        // A deleted profile takes its watches along.
        assertEquals(WatchResult.Ok, again.watcher.forgetProfile(profileId))
        assertTrue(Rig(rig.dir).watcher.state.value.watched.isEmpty())
    }

    @Test fun anUnsavedConnectionIsWatchedInMemoryOnly() {
        val rig = Rig()
        val unsaved = WatchScope.of(null, DIRECT_PVE_KIND, origin, "watch@pve!ro", null)
        assertEquals(WatchResult.Ok, rig.watcher.watch(unsaved, vm, "alpha"))
        assertFalse(Files.exists(rig.file))
        rig.watchVm()
        assertFalse(unsaved.binding in Files.readString(rig.file))
        assertEquals(1, Rig(rig.dir).watcher.state.value.watched.size)
    }

    @Test fun theWatchListIsBoundedAndResourcesAreValidated() {
        val rig = Rig()
        val unsaved = WatchScope.of(null, DIRECT_PVE_KIND, origin, "watch@pve!ro", null)
        repeat(MAX_WATCHED) { assertEquals(WatchResult.Ok, rig.watcher.watch(unsaved, WatchResource(WatchKind.VM, vmid = 100 + it), "g$it")) }
        assertTrue(rig.watcher.watch(unsaved, WatchResource(WatchKind.VM, vmid = 999), "x") is WatchResult.Error)
        assertFailsWith<IllegalArgumentException> { WatchResource(WatchKind.Node, node = "bad node; rm -rf") }
        assertFailsWith<IllegalArgumentException> { WatchResource(WatchKind.VM, node = "node-a", vmid = 1) }
        assertFailsWith<IllegalArgumentException> { WatchResource(WatchKind.VM, vmid = 0) }
        assertFailsWith<IllegalArgumentException> { WatchResource(WatchKind.Node, node = "node-a", vmid = 5) }
        assertFailsWith<IllegalArgumentException> { WatchScope.of(null, DIRECT_PVE_KIND, "http://plain.example.test", "a@pve", null) }
        assertFailsWith<IllegalArgumentException> { WatchScope.of(null, "other", origin, "a@pve", null) }
        assertFailsWith<IllegalArgumentException> { WatchScope.of(null, DIRECT_PVE_KIND, origin, "a@pve", "1234") }
        // The pin is compared in canonical form, like a Connection's.
        assertEquals(WatchScope.of(null, DIRECT_PVE_KIND, origin, "a@pve", "AB:".repeat(31) + "AB").binding, WatchScope.of(null, DIRECT_PVE_KIND, origin, "a@pve", "ab".repeat(32)).binding)
    }

    @Test fun aCorruptWatchListIsPreservedAndRefusesNewSavedWatches() {
        val seed = Rig(); seed.watchVm()
        val damaged = Files.readString(seed.file).replace("\"VM\"", "\"Toaster\"")
        Files.writeString(seed.file, damaged)
        val rig = Rig(seed.dir)
        val state = rig.watcher.state.value
        assertTrue(state.loaded && state.unreadable); assertNotNull(state.problem); assertTrue(state.watched.isEmpty())
        val refused = rig.watcher.watch(scope, nodeA, "node-a")
        assertTrue(refused is WatchResult.Error, "a watch that cannot be saved is not silently kept")
        assertTrue(rig.watcher.state.value.watched.isEmpty())
        assertEquals(damaged, Files.readString(rig.file))
        // Demo and unsaved watches need no file and still work.
        assertEquals(WatchResult.Ok, rig.watcher.watch(WatchScope.Demo, vm, "demo"))
        // Explicit set-aside keeps the damaged content and starts a new file.
        rig.clock.wall = Instant.parse("2026-11-13T11:00:00Z")
        val aside = rig.watcher.setAsideAndStartEmpty()
        assertEquals("watcher.unreadable-20261113T110000Z.json", aside.fileName.toString())
        assertEquals(damaged, Files.readString(aside))
        assertNull(rig.watcher.state.value.problem)
        assertEquals(WatchResult.Ok, rig.watcher.watch(scope, nodeA, "node-a"))
        assertEquals(listOf(nodeA), Rig(seed.dir).watcher.state.value.watched.map { it.entry.resource })
        // A newer file is read-only and cannot be set aside.
        val newer = Rig()
        Files.writeString(newer.file, "{\"version\":2,\"watched\":[]}\n")
        val blocked = Rig(newer.dir)
        assertTrue(blocked.watcher.state.value.readOnly)
        assertFailsWith<GuardedStoreException> { blocked.watcher.setAsideAndStartEmpty() }
        assertTrue(blocked.watcher.watch(scope, vm, "alpha") is WatchResult.Error)
        assertEquals("{\"version\":2,\"watched\":[]}\n", Files.readString(newer.file))
    }

    @Test fun aFailedSaveIsReportedAndLeavesListAndFileConsistent() {
        val rig = Rig(); rig.watchVm()
        val before = Files.readString(rig.file)
        rig.failMove = true
        val result = rig.watcher.watch(scope, nodeA, "node-a")
        assertFalse("disk full" in (result as WatchResult.Error).message)
        assertEquals(listOf(vm), rig.watcher.state.value.watched.map { it.entry.resource }, "not added")
        assertNotNull(rig.watcher.state.value.problem)
        assertTrue(rig.watcher.unwatch(scope.binding, vm) is WatchResult.Error)
        assertEquals(listOf(vm), rig.watcher.state.value.watched.map { it.entry.resource }, "not removed")
        assertEquals(before, Files.readString(rig.file))
        rig.failMove = false
        assertEquals(WatchResult.Ok, rig.watcher.watch(scope, nodeA, "node-a"))
        assertNull(rig.watcher.state.value.problem)
        assertEquals(2, Rig(rig.dir).watcher.state.value.watched.size)
    }

    @Test fun profileChangesMarkWatchesBeforeAnySessionOpens() {
        val rig = Rig(); rig.watchVm()
        val same = ConnectionProfile(profileId, "Lab", DIRECT_PVE_KIND, origin, "watch@pve!ro", ScheduleFixtures.PIN)
        rig.watcher.profilesChanged(listOf(same))
        assertEquals(WatchAvailability.NotConnected, rig.status().availability)
        rig.watcher.profilesChanged(listOf(same.copy(sha256Pin = null)))
        assertEquals(WatchAvailability.BindingChanged, rig.status().availability)
        assertEquals(WatchScope.of(same).binding, scope.binding)
    }

    @Test fun namesInAlertsAreBoundedAndFlattened() {
        val rig = Rig(); rig.open()
        rig.watcher.watch(scope, vm, "evil\nname " + "x".repeat(300))
        val named = { cpu: Double -> rig.guest(cpu = cpu, name = "password=hunter2 " + "n".repeat(200)) }
        rig.read(listOf(named(0.9))); rig.read(listOf(named(0.9)))
        val event = rig.sink.events.single()
        assertTrue(event.title.length <= NOTIFICATION_TITLE_MAX)
        assertFalse("hunter2" in event.title)
        assertFalse(event.title.any { it.isISOControl() })
    }
}
