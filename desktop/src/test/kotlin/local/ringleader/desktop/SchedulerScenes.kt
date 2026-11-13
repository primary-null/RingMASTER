package local.ringleader.desktop

import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import local.ringleader.core.*

/** Synthetic scheduler data for scene tests and screenshots. Invented names, documentation hosts, UTC instants. */
internal class SchedulerFixture(populated: Boolean = true, unreadable: Boolean = false) : AutoCloseable {
    val now: Instant = Instant.parse("2026-11-20T05:00:00Z")
    val dir: Path = Files.createTempDirectory("scheduler-scene")
    val file: Path = dir.resolve("schedules.json")
    val gateway = FakeScheduleGateway().apply {
        guests = mutableListOf(ScheduleFixtures.guest(), ScheduleFixtures.guest(vmid = 204, kind = "Container", status = "running").copy(name = "web-cache"),
            ScheduleFixtures.guest(vmid = 900, template = true).copy(name = "debian-template"))
    }
    val lab = ScheduleFixtures.profile()
    val office = ScheduleFixtures.profile(id = "22222222-2222-4333-8444-555555555555", name = "Office PVE", token = "ops@pve!sched")
    val home = ScheduleFixtures.profile(id = "33333333-2222-4333-8444-555555555555", name = "Home lab", token = "root@pam", pin = null)
    val secrets = object : ScheduleSecrets {
        override val vaultName = "Windows Credential Manager"
        override fun status() = VaultStatus.Available
        override fun remembered(profileId: String) = profileId != office.id
        override fun read(profileId: String) = ScheduleFixtures.SECRET.toCharArray()
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val plans: List<SchedulePlan>
    val engine: ScheduleEngine

    init {
        val berlin = ZoneId.of("Europe/Berlin")
        fun target(vmid: Int, name: String, kind: String = "VM") = ScheduleTarget(lab.id, profileBinding(lab), "node-a", kind, vmid, name, ScheduleFixtures.facts().best(false)!!)
        val created = Instant.parse("2026-11-02T09:00:00Z")
        val a = SchedulePlan(UUID.randomUUID().toString(), "Weekday lab start", 1, ScheduleAction.Start, ScheduleRepeat(RepeatKind.Weekdays), LocalTime.of(7, 30), berlin, target(104, "alpha"), true, created, created)
        val b = SchedulePlan(UUID.randomUUID().toString(), "Morning maintenance stop", 1, ScheduleAction.Shutdown, ScheduleRepeat(RepeatKind.Weekdays), LocalTime.of(7, 35), berlin, target(104, "alpha"), true, created, created)
        val c = SchedulePlan(UUID.randomUUID().toString(), "Weekend cache stop", 2, ScheduleAction.Shutdown, ScheduleRepeat(RepeatKind.Days, days = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)), LocalTime.of(23, 0), ZoneId.of("UTC"), target(204, "web-cache", "Container"), false, created, created)
        val d = SchedulePlan(UUID.randomUUID().toString(), "Router bench start", 3, ScheduleAction.Start, ScheduleRepeat(RepeatKind.Daily), LocalTime.of(6, 0), berlin, target(118, "router-bench"), true, created, created,
            invalidReason = "The guest's identity changed. Check that it is the right guest, then edit and authorize the schedule again.")
        // Watermarks as if RingMASTER had been running: no catch-up Missed rows are added when the scene starts.
        plans = if (populated) listOf(a, b, c, d).map { it.copy(lastHandledDue = now) } else emptyList()
        fun run(plan: SchedulePlan, daysAgo: Long, phase: RunPhase, detail: String, upid: Boolean = false, missed: Int = 0) = ScheduleRun(UUID.randomUUID().toString(), plan.id, plan.revision,
            Instant.parse("2026-11-19T06:30:00Z").minusSeconds(daysAgo * 86400), plan.action, plan.target.label, phase, detail, now.minusSeconds(daysAgo * 86400),
            if (upid) ScheduleFixtures.upid(type = if (plan.action == ScheduleAction.Start) "qmstart" else "qmshutdown") else null, if (upid) "node-a" else null, missed)
        val runs = if (!populated) emptyList() else listOf(
            run(a, 0, RunPhase.Succeeded, "Stopped with exit status OK.", upid = true),
            run(a, 1, RunPhase.Warnings, "Finished with WARNINGS: 1. Not counted as OK.", upid = true),
            run(a, 2, RunPhase.NoChange, "The guest was already running. Nothing was sent."),
            run(a, 3, RunPhase.Unknown, "The server did not confirm whether it accepted the action. It was not sent again. Check the task list."),
            run(a, 6, RunPhase.Missed, "Due Fri 13 Nov 2026 07:30 (+01:00). RingMASTER was not running, or the computer was asleep, until more than 15 minutes later. Nothing was sent.", missed = 3),
            run(b, 0, RunPhase.Submitted, "The task had not finished after 2 minutes. Open its log to see how it ended.", upid = true),
            run(b, 1, RunPhase.Rejected, "The server refused the action (HTTP 403, permission). It was not sent again."),
            run(d, 0, RunPhase.Skipped, "The guest's identity (SMBIOS UUID) changed. Nothing was sent; the schedule needs review."),
        )
        if (unreadable) Files.writeString(file, "{\"version\":1,\"plans\":[{\"id\":\"broken\"}],\"access\":[],\"runs\":[]}")
        else ScheduleStore(file).apply { load(); save(ScheduleDocument(plans, listOf(BackgroundAccess(lab.id, profileBinding(lab), created)), runs)) }
        engine = ScheduleEngine(ScheduleStore(file), { listOf(lab, office, home) }, secrets, gateway, MutationLeases(), Clock.fixed(now, ZoneOffset.UTC), scope)
        engine.start()
        val deadline = System.nanoTime() + 10_000_000_000
        while (!engine.state.value.loaded && System.nanoTime() < deadline) Thread.sleep(10)
        check(engine.state.value.loaded)
    }

    override fun close() { scope.cancel() }
}

internal fun CreateScene.texts(): List<String> = nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text } }
internal fun CreateScene.has(text: String) = texts().any { it.equals(text, ignoreCase = true) }
internal fun CreateScene.hasPrefix(prefix: String) = texts().any { it.startsWith(prefix) }

internal suspend fun CreateScene.until(what: String, condition: () -> Boolean) {
    repeat(300) { if (condition()) return; frames(); delay(20) }
    runCatching { java.io.File("build/scene-timeout-${what.replace(' ', '-')}.png").writeBytes(scene.render(1_000_000_000_000).encodeToData()!!.bytes) }
    error("Timed out waiting for $what. Visible: ${texts().joinToString(" | ").takeLast(2500)}")
}

/** Clicks the [index]-th (from the end when negative) clickable node whose text matches. */
internal suspend fun CreateScene.clickText(match: (String) -> Boolean, last: Boolean = false) {
    val hits = nodes().filter { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { match(it.text) } == true }
    var node = (if (last) hits.lastOrNull() else hits.firstOrNull()) ?: error("No text to click. Visible: ${texts().joinToString(" | ").take(1500)}")
    fun target(n: SemanticsNode): SemanticsNode = generateSequence(n) { it.parent }.firstOrNull { it.config.contains(SemanticsActions.OnClick) } ?: n
    // Bring it into view through each scrolling ancestor (page, then dialog) before pressing.
    repeat(8) {
        val t = target(node)
        val scroller = generateSequence(t.parent) { it.parent }.firstOrNull { it.config.contains(SemanticsActions.ScrollBy) } ?: return@repeat
        // positionInRoot is not clipped, unlike boundsInWindow, so an off-screen target is seen as off-screen.
        val boxTop = scroller.positionInRoot.y; val boxBottom = boxTop + scroller.size.height
        val top = t.positionInRoot.y; val bottom = top + t.size.height
        if (top >= boxTop && bottom <= boxBottom) return@repeat
        scroller.config[SemanticsActions.ScrollBy].action!!(0f, (top + bottom) / 2f - (boxTop + boxBottom) / 2f)
        frames()
        val again = nodes().filter { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { match(it.text) } == true }
        node = (if (last) again.lastOrNull() else again.firstOrNull()) ?: node
    }
    val at = target(node).boundsInWindow.center
    scene.sendPointerEvent(PointerEventType.Move, at)
    scene.sendPointerEvent(PointerEventType.Press, at)
    scene.sendPointerEvent(PointerEventType.Release, at)
    frames()
}

/** Pins the JVM default zone (the editor's suggested zone) so scenes do not depend on this computer's zone. */
internal fun withBerlinZone(block: () -> Unit) {
    val before = java.util.TimeZone.getDefault()
    java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/Berlin"))
    try { block() } finally { java.util.TimeZone.setDefault(before) }
}
