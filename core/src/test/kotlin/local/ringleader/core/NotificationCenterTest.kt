package local.ringleader.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationCenterTest {
    private val t0 = Instant.parse("2026-11-13T10:00:00Z")
    private val binding = "ab".repeat(32)

    private class Rig(val dir: Path = Files.createTempDirectory("notify"), var at: Instant) {
        val file: Path = dir.resolve("notifications.json")
        var failMove = false
        fun store() = NotificationStore(file) { temporary, target ->
            if (failMove) throw IOException("disk full")
            Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        fun center() = NotificationCenter(store()) { at }.also { it.load() }
        fun text(): String = Files.readString(file)
    }

    private fun event(n: Int = 1, category: NotificationCategory = NotificationCategory.TaskFailure, subject: String = "thing-$n", occurrence: String = "failed",
        title: String = "Task failed: vzdump", body: String = "101 on node pve-a.", demo: Boolean = false, native: Boolean = true) =
        NotificationEvent(NotificationIdentity("task", binding, subject, occurrence), category, NotificationSeverity.Error, title, body, demo = demo, native = native)

    @Test fun queuedDeliveryRechecksPrivacySnoozeAndDismissal() = runTest {
        val rig = Rig(at = t0)
        val center = rig.center()
        val requests = mutableListOf<NativeNotificationRequest>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { center.native.toList(requests) }
        center.post(event())
        val queued = requests.single()
        center.setPreferences { it.copy(privacy = true) }
        val private = assertNotNull(center.prepareNative(queued))
        assertTrue(private.private)
        assertEquals(NotificationCategory.TaskFailure.genericTitle, private.title)
        assertFalse("pve-a" in private.body)
        center.setPreferences { it.copy(privacy = false) }
        assertTrue(assertNotNull(center.prepareNative(queued, showcase = true)).private)
        center.snoozeAll(t0.plusSeconds(60))
        assertNull(center.prepareNative(queued))
        center.snoozeAll(null)
        center.setPreferences { it.copy(native = false) }
        assertNull(center.prepareNative(queued))
        center.setPreferences { it.copy(native = true) }
        center.dismiss(queued.id)
        assertNull(center.prepareNative(queued))
    }

    @Test fun identityIsDeterministicAndTellsOccurrencesApart() {
        val a = NotificationIdentity("schedule", "local", "plan-1", "r1|1000|Failed")
        assertEquals(a.id, NotificationIdentity("schedule", "local", "plan-1", "r1|1000|Failed").id)
        assertTrue(a.id.matches(Regex("[0-9a-f]{32}")))
        val others = listOf(
            NotificationIdentity("schedule", "local", "plan-1", "r2|1000|Failed"),
            NotificationIdentity("schedule", "local", "plan-1", "r1|2000|Failed"),
            NotificationIdentity("schedule", "local", "plan-1", "r1|1000|Missed"),
            NotificationIdentity("schedule", "local", "plan-2", "r1|1000|Failed"),
            NotificationIdentity("task", "local", "plan-1", "r1|1000|Failed"),
        )
        assertEquals(others.size + 1, (others.map { it.id } + a.id).distinct().size)
        // The subject identity ignores the occurrence, so a snooze covers every later event about the same thing.
        assertEquals(a.subjectId, others[0].subjectId)
        assertFalse(a.subjectId == others[3].subjectId)
        // Field boundaries cannot be forged with a line break.
        assertFailsWith<IllegalArgumentException> { NotificationIdentity("task", "local", "a\nb", "c") }
    }

    @Test fun aRepeatedEventPostsOnceAlsoAfterDismissClearAndRestart() = runTest {
        val rig = Rig(at = t0)
        val center = rig.center()
        assertEquals(PostResult.Posted, center.post(event(1)))
        assertEquals(PostResult.Duplicate, center.post(event(1)))
        assertEquals(1, center.state.value.items.size)
        center.dismiss(center.state.value.items.single().id)
        assertEquals(PostResult.Duplicate, center.post(event(1)), "a dismissed event must not come back")
        assertEquals(PostResult.Posted, center.post(event(2)))
        center.clear()
        assertTrue(center.state.value.items.isEmpty())
        assertEquals(PostResult.Duplicate, center.post(event(2)))
        val again = rig.center()
        assertEquals(PostResult.Duplicate, again.post(event(1)), "identities survive a restart")
        assertEquals(PostResult.Duplicate, again.post(event(2)))
        assertEquals(PostResult.Posted, again.post(event(1, occurrence = "warnings")), "another occurrence of the same subject is a new event")
    }

    @Test fun nothingIsRecordedBeforeLoad() {
        val rig = Rig(at = t0)
        val center = NotificationCenter(rig.store()) { rig.at }
        assertEquals(PostResult.NotLoaded, center.post(event(1)))
        center.load()
        assertEquals(PostResult.Posted, center.post(event(1)), "the refused event was not remembered as posted")
    }

    @Test fun historyAndIdentitiesAreBounded() {
        val rig = Rig(at = t0)
        val center = rig.center()
        repeat(MAX_NOTIFICATIONS + 30) { n -> rig.at = t0.plusSeconds(n.toLong()); assertEquals(PostResult.Posted, center.post(event(n))) }
        val items = center.state.value.items
        assertEquals(MAX_NOTIFICATIONS, items.size)
        assertEquals(t0.plusSeconds(MAX_NOTIFICATIONS + 29L), items.first().createdAt, "newest first")
        assertEquals(t0.plusSeconds(30), items.last().createdAt, "the oldest were dropped")
        assertEquals(PostResult.Duplicate, center.post(event(0)), "a trimmed notification's identity is still known")
        val reloaded = rig.center().state.value
        assertEquals(MAX_NOTIFICATIONS, reloaded.items.size)
        assertEquals(items, reloaded.items)
        assertTrue(Files.size(rig.file) < MAX_NOTIFICATION_BYTES)
        // The identity memory is bounded too: after more than MAX_NOTIFICATION_SEEN newer events the oldest is forgotten.
        repeat(MAX_NOTIFICATION_SEEN) { n -> center.post(event(10_000 + n)) }
        assertEquals(PostResult.Posted, center.post(event(0)))
        assertTrue(decodeNotifications(rig.text()).seen.size <= MAX_NOTIFICATION_SEEN)
    }

    @Test fun readDismissAndClearArePersisted() {
        val rig = Rig(at = t0)
        val center = rig.center()
        center.post(event(1)); rig.at = t0.plusSeconds(1); center.post(event(2)); rig.at = t0.plusSeconds(2); center.post(event(3))
        assertEquals(3, center.state.value.unread)
        val (third, second, first) = center.state.value.items
        center.markRead(second.id)
        assertEquals(2, center.state.value.unread)
        center.dismiss(first.id)
        assertEquals(listOf(third.id, second.id), rig.center().state.value.items.map { it.id })
        assertEquals(listOf(false, true), rig.center().state.value.items.map { it.read })
        center.markAllRead()
        assertEquals(0, rig.center().state.value.unread)
        center.clear()
        assertTrue(rig.center().state.value.items.isEmpty())
    }

    @Test fun preferencesGateRecordingAndNativeDelivery() = runTest {
        val rig = Rig(at = t0)
        val center = rig.center()
        val native = mutableListOf<NativeNotificationRequest>()
        val posted = mutableListOf<AppNotification>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { center.native.toList(native) }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { center.posted.toList(posted) }

        assertEquals(PostResult.Posted, center.post(event(1)))
        assertEquals("Task failed: vzdump", native.single().title)
        assertFalse(native.single().private)

        // A category that is off by default records nothing.
        assertEquals(PostResult.Disabled, center.post(event(2, category = NotificationCategory.TaskWarnings)))
        center.setPreferences { it.with(NotificationCategory.TaskWarnings, true) }
        assertEquals(PostResult.Posted, center.post(event(2, category = NotificationCategory.TaskWarnings)), "a disabled event was not remembered as posted")
        assertEquals(2, native.size)

        // Native off: recorded, not delivered.
        center.setPreferences { it.copy(native = false) }
        assertEquals(PostResult.Posted, center.post(event(3)))
        assertEquals(2, native.size); assertEquals(3, posted.size)

        // An in-window-only event never reaches the OS.
        center.setPreferences { it.copy(native = true) }
        assertEquals(PostResult.Posted, center.post(event(4, native = false)))
        assertEquals(2, native.size)

        // Master off: nothing at all.
        center.setPreferences { it.copy(master = false) }
        assertEquals(PostResult.Disabled, center.post(event(5)))
        assertEquals(4, center.state.value.items.size); assertEquals(4, posted.size)

        val reloaded = rig.center().state.value.preferences
        assertFalse(reloaded.master); assertTrue(reloaded.native)
        assertTrue(reloaded.enabled(NotificationCategory.TaskWarnings)); assertTrue(reloaded.enabled(NotificationCategory.TaskFailure))
        assertFalse(reloaded.enabled(NotificationCategory.ExternalPower))
    }

    @Test fun privacyGivesOnlyGenericTextToTheOs() = runTest {
        val rig = Rig(at = t0)
        val center = rig.center()
        val native = mutableListOf<NativeNotificationRequest>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { center.native.toList(native) }
        center.setPreferences { it.copy(privacy = true) }
        center.post(event(1, title = "Task failed: vzdump", body = "101 on node pve-secretlab."))
        val request = native.single()
        assertTrue(request.private)
        assertEquals(NotificationCategory.TaskFailure.genericTitle, request.title)
        assertEquals(NOTIFICATION_GENERIC_BODY, request.body)
        assertFalse(listOf(request.title, request.body).any { "101" in it || "pve-secretlab" in it || "vzdump" in it })
        // The window keeps the detail and can ask for either presentation.
        val stored = center.state.value.items.single()
        assertEquals("101 on node pve-secretlab.", presentNotification(stored, private = false).body)
        assertEquals(NOTIFICATION_GENERIC_BODY, presentNotification(stored, private = true).body)
        // Turning privacy off applies to the next event, not retroactively.
        center.setPreferences { it.copy(privacy = false) }
        center.post(event(2, body = "102 on node pve-b."))
        assertEquals("102 on node pve-b.", native.last().body)
        // No generic title names a server, guest or task.
        NotificationCategory.entries.forEach { assertFalse(it.genericTitle.any(Char::isDigit), it.name) }
    }

    @Test fun snoozeRecordsButDoesNotDeliver() = runTest {
        val rig = Rig(at = t0)
        val center = rig.center()
        val native = mutableListOf<NativeNotificationRequest>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { center.native.toList(native) }

        center.post(event(1, subject = "vm-101", occurrence = "a"))
        assertEquals(1, native.size)
        // Snooze the subject of that notification for an hour.
        center.snooze(center.state.value.items.single().id, t0.plus(Duration.ofHours(1)))
        rig.at = t0.plusSeconds(60)
        assertEquals(PostResult.Posted, center.post(event(1, subject = "vm-101", occurrence = "b")))
        assertEquals(1, native.size, "same subject: recorded only")
        assertEquals(PostResult.Posted, center.post(event(2, subject = "vm-102", occurrence = "a")))
        assertEquals(2, native.size, "another subject is not snoozed")
        // The snooze survives a restart and ends by the clock.
        val restarted = rig.center()
        assertEquals(1, restarted.state.value.snoozes.size)
        rig.at = t0.plus(Duration.ofHours(1)).plusSeconds(1)
        assertEquals(PostResult.Posted, center.post(event(1, subject = "vm-101", occurrence = "c")))
        assertEquals(3, native.size)

        // Snooze everything.
        center.snoozeAll(rig.at.plusSeconds(600))
        center.post(event(3)); center.post(event(4))
        assertEquals(3, native.size); assertEquals(6, center.state.value.items.size)
        center.snoozeAll(null)
        center.post(event(5))
        assertEquals(4, native.size)
        // A snooze time in the past is no snooze.
        center.snoozeAll(rig.at.minusSeconds(1))
        assertNull(center.state.value.snoozeAllUntil)
    }

    @Test fun snoozesAreBounded() {
        val rig = Rig(at = t0)
        val center = rig.center()
        center.setPreferences { it.copy(native = false) }
        repeat(MAX_NOTIFICATION_SNOOZES + 10) { n ->
            center.post(event(n, subject = "s$n"))
            center.snooze(center.state.value.items.first().id, t0.plusSeconds(1000L + n))
        }
        assertEquals(MAX_NOTIFICATION_SNOOZES, center.state.value.snoozes.size)
        assertEquals(MAX_NOTIFICATION_SNOOZES, rig.center().state.value.snoozes.size)
    }

    @Test fun textIsBoundedFlattenedAndRedacted() {
        val rig = Rig(at = t0)
        val center = rig.center()
        center.post(event(1, title = "Line one\nline two\u0007 " + "x".repeat(300), body = "token=abc123secret password: hunter2 " + "y".repeat(400)))
        val stored = center.state.value.items.single()
        assertTrue(stored.title.length <= NOTIFICATION_TITLE_MAX && stored.body.length <= NOTIFICATION_BODY_MAX)
        assertTrue(stored.title.startsWith("Line one line two x"))
        assertTrue(stored.title.endsWith("…") && stored.body.endsWith("…"))
        assertFalse("abc123secret" in stored.body || "hunter2" in stored.body)
        assertFalse((stored.title + stored.body).any { it.isISOControl() })
        val onDisk = rig.text()
        assertFalse("abc123secret" in onDisk || "hunter2" in onDisk)
        // A cut never splits a surrogate pair.
        val cut = notificationText("a".repeat(9) + "😀" + "b".repeat(20), 11)
        assertEquals("a".repeat(9) + "…", cut)
        assertEquals("", notificationText(" \n\t ", 20))
    }

    @Test fun storedFileHoldsNoAddressAndRoundTripsTargets() {
        val rig = Rig(at = t0)
        val center = rig.center()
        val scope = WatchScope.of(ScheduleFixtures.PROFILE_ID, DIRECT_PVE_KIND, "https://pve.example.test:8006", "watch@pve!ro", ScheduleFixtures.PIN)
        val resource = NotificationTarget.Resource(scope.profileId, scope.binding, WatchResource(WatchKind.VM, vmid = 104))
        val task = NotificationTarget.Task(null, scope.binding, ScheduleFixtures.upid())
        val run = NotificationTarget.ScheduleRun("11111111-2222-4333-8444-555555555555", "99999999-2222-4333-8444-555555555555")
        center.post(event(1).copy(target = resource)); rig.at = t0.plusSeconds(1)
        center.post(event(2).copy(target = task)); rig.at = t0.plusSeconds(2)
        center.post(event(3).copy(target = run)); rig.at = t0.plusSeconds(3)
        center.post(event(4))
        assertEquals(listOf(NotificationTarget.None, run, task, resource), rig.center().state.value.items.map { it.target })
        val text = rig.text()
        assertFalse("pve.example.test" in text, "the server address is not stored")
        assertFalse("watch@pve" in text, "the account is not stored")
        assertFalse(ScheduleFixtures.PIN in text, "the pin is not stored")
        // A target is identity only: nothing URL- or command-shaped can be built.
        assertFailsWith<IllegalArgumentException> { NotificationTarget.Task(null, scope.binding, "https://evil.example/run?cmd=1") }
        assertFailsWith<IllegalArgumentException> { NotificationTarget.Resource("not-a-uuid", scope.binding, WatchResource(WatchKind.VM, vmid = 104)) }
        assertFailsWith<IllegalArgumentException> { NotificationTarget.ScheduleRun("../../x", "y") }
    }

    @Test fun aCorruptFileIsPreservedReportedAndOnlySetAsideOnRequest() {
        val rig = Rig(at = t0)
        rig.center().post(event(1))
        val damaged = rig.text().dropLast(20)
        Files.writeString(rig.file, damaged)
        val center = rig.center()
        val state = center.state.value
        assertTrue(state.loaded && state.unreadable && state.unsaved)
        assertNotNull(state.problem)
        // The center still works in memory, and says that it is unsaved; the damaged file is never overwritten.
        assertEquals(PostResult.Posted, center.post(event(2)))
        center.markAllRead(); center.setPreferences { it.copy(privacy = true) }
        assertEquals(1, center.state.value.items.size)
        assertTrue(center.state.value.unsaved)
        assertEquals(damaged, rig.text(), "an unreadable file must not be overwritten")
        assertFalse(center.retrySave())
        assertEquals(damaged, rig.text())
        // Explicit action: the old content is kept under another name and memory becomes the new file.
        rig.at = Instant.parse("2026-11-13T10:05:00Z")
        val aside = center.setAsideAndStartEmpty()
        assertEquals("notifications.unreadable-20261113T100500Z.json", aside.fileName.toString())
        assertEquals(damaged, Files.readString(aside))
        assertNull(center.state.value.problem); assertFalse(center.state.value.unsaved)
        val reloaded = rig.center().state.value
        assertEquals(1, reloaded.items.size); assertTrue(reloaded.preferences.privacy)
    }

    @Test fun otherUnusableFilesAreNeverOverwritten() {
        // Not JSON, wrong types, unknown and duplicate keys, an unknown category, a bad id: all unreadable.
        val good = Rig(at = t0).also { it.center().post(event(1)) }.text()
        val bad = listOf(
            "not json", "[]", "{}",
            good.replace("\"version\":1", "\"version\":\"1\""),
            good.replace("\"version\":1", "\"version\":0"),
            good.replace("\"seen\":", "\"extra\":1,\"seen\":"),
            good.replace("\"master\":true", "\"master\":true,\"master\":false"),
            good.replace("\"TaskFailure\"", "\"NoSuchCategory\""),
            good.replace(Regex("\"id\":\"[0-9a-f]{32}\""), "\"id\":\"XYZ\""),
            good.replace("\"read\":false", "\"read\":\"no\""),
        )
        bad.forEachIndexed { index, text ->
            val rig = Rig(at = t0)
            Files.writeString(rig.file, text)
            val center = rig.center()
            assertTrue(center.state.value.unreadable, "case $index")
            center.post(event(9))
            assertEquals(text, rig.text(), "case $index")
        }
        // Invalid UTF-8.
        val rig = Rig(at = t0)
        Files.write(rig.file, byteArrayOf(0x7b, 0xC3.toByte(), 0x28, 0x7d))
        assertTrue(rig.center().state.value.unreadable)
        // A newer version is read-only: it cannot even be set aside.
        val newer = Rig(at = t0)
        Files.writeString(newer.file, good.replace("\"version\":1", "\"version\":2"))
        val center = newer.center()
        assertTrue(center.state.value.readOnly); assertFalse(center.state.value.unreadable)
        center.post(event(9))
        assertFailsWith<GuardedStoreException> { center.setAsideAndStartEmpty() }
        assertTrue("\"version\":2" in newer.text())
    }

    @Test fun aFailedSaveIsSurfacedKeepsMemoryAndCanBeRetried() {
        val rig = Rig(at = t0)
        val center = rig.center()
        center.post(event(1))
        val before = rig.text()
        rig.failMove = true
        assertEquals(PostResult.Posted, center.post(event(2)))
        val failed = center.state.value
        assertEquals(2, failed.items.size, "the notification is not lost from the window")
        assertTrue(failed.unsaved); assertNotNull(failed.problem)
        assertFalse(failed.unreadable || failed.readOnly)
        assertFalse("disk full" in failed.problem!!, "an exception message is not copied into the state")
        assertEquals(before, rig.text(), "the previous file is unchanged")
        assertTrue(Files.list(rig.dir).use { it.toList() }.none { it.fileName.toString().endsWith(".tmp") }, "no temporary file is left behind")
        assertFalse(center.retrySave())
        rig.failMove = false
        // The next change (or an explicit retry) writes everything that was held in memory.
        assertTrue(center.retrySave())
        assertFalse(center.state.value.unsaved); assertNull(center.state.value.problem)
        assertEquals(2, rig.center().state.value.items.size)
    }

    @Test fun aFileChangedOutsideIsLeftAlone() {
        val rig = Rig(at = t0)
        val center = rig.center()
        center.post(event(1))
        val foreign = rig.text().replace("\"privacy\":false", "\"privacy\":true")
        Files.writeString(rig.file, foreign)
        center.post(event(2))
        assertTrue(center.state.value.readOnly && center.state.value.unsaved)
        assertEquals(2, center.state.value.items.size)
        assertEquals(foreign, rig.text())
        center.post(event(3))
        assertEquals(foreign, rig.text(), "it stays blocked until restart")
    }

    @Test fun demoEventsStayInMemoryAndNeverReachTheOs() = runTest {
        val rig = Rig(at = t0)
        val center = rig.center()
        val native = mutableListOf<NativeNotificationRequest>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { center.native.toList(native) }
        assertEquals(PostResult.Posted, center.post(event(1, demo = true, title = "Demo guest is under load")))
        assertEquals(PostResult.Duplicate, center.post(event(1, demo = true)))
        assertTrue(center.state.value.items.single().demo)
        assertTrue(native.isEmpty())
        assertFalse(Files.exists(rig.file), "a demo event alone writes nothing")
        center.post(event(2))
        assertFalse("Demo guest" in rig.text())
        assertEquals(1, decodeNotifications(rig.text()).seen.size, "demo identities are not stored")
        center.dropDemo()
        assertContentEquals(listOf(false), center.state.value.items.map { it.demo })
        assertEquals(1, rig.center().state.value.items.size)
    }
}
