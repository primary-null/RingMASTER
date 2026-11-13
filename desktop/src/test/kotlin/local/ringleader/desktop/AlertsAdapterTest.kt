package local.ringleader.desktop

import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import local.ringleader.core.ConnectionProfile
import local.ringleader.core.DEMO_REMOTE_KEY
import local.ringleader.core.NotificationCategory
import local.ringleader.core.NotificationEvent
import local.ringleader.core.NotificationIdentity
import local.ringleader.core.NotificationSeverity
import local.ringleader.core.NotificationStore
import local.ringleader.core.NotificationTarget
import local.ringleader.core.PostResult
import local.ringleader.core.ResourceId
import local.ringleader.core.WatchResource
import local.ringleader.core.WatchScope
import local.ringleader.core.WatchStore
import local.ringleader.core.NotificationCenter as CoreCenter
import local.ringleader.core.Watcher as CoreWatcher
import local.ringleader.core.WatchKind as CoreKind

/**
 * The UI adapter against the real core NotificationCenter and Watcher, with stores in a temporary folder. The scope is
 * unconfined so a callback's store call has finished when the callback returns (the app passes a background scope).
 */
class AlertsAdapterTest {
    private val now = Instant.parse("2026-11-13T10:00:00Z")
    private val dir = Files.createTempDirectory("ringmaster-alerts")
    private val center = CoreCenter(NotificationStore(dir.resolve("notifications.json"))) { now }
    private val watcher = CoreWatcher(WatchStore(dir.resolve("watcher.json")), center)
    private val keep = mutableListOf<Boolean>()
    private val problems = mutableListOf<String>()
    private val source = AlertsSource(center, watcher, CoroutineScope(Dispatchers.Unconfined),
        NativeDeliveryView(available = true, enabled = true), keepWatchingInBackground = false, onKeepWatchingInBackground = { keep += it },
        onProblem = { problems += it }, clock = { now })
    private val demo = WatchScope.Demo
    private val names = ConnectionNames(listOf(ConnectionProfile("11111111-1111-1111-1111-111111111111", "Lab cluster", local.ringleader.core.DIRECT_PVE_KIND, "https://pve.example.test:8006", "u@pve!t", null)))

    private fun post(subject: String, title: String, target: NotificationTarget, severity: NotificationSeverity = NotificationSeverity.Warning,
                     category: NotificationCategory = NotificationCategory.WatchState): PostResult =
        center.post(NotificationEvent(NotificationIdentity("watch", demo.binding, subject, "1"), category, severity, title, "On demo-node-a", target))

    @Test fun notificationsMapWithGenericHeadlinesAndRouteOnlyInTheConnectionInView() {
        center.load()
        val guest = NotificationTarget.Resource(null, demo.binding, WatchResource(CoreKind.VM, vmid = 120))
        assertEquals(PostResult.Posted, post("VM/120", "debian-workbench (120) stopped", guest, NotificationSeverity.Error))
        val state = center.state.value
        val view = notificationCenterViewOf(state, names, demo, now)
        val n = view.items.single()
        assertEquals(NoticeSeverity.Critical, n.severity)
        assertEquals(NotificationCategory.WatchState.genericTitle, n.headline, "the bold line is the generic wording")
        assertTrue(n.detail!!.contains("debian-workbench"), "RingMASTER's own title is the identifying detail")
        assertEquals("Demonstration", n.sourceLabel)
        assertEquals(NoticeRoute.Guest(ResourceId(DEMO_REMOTE_KEY, "", "VM", 120)), n.target!!.route)
        assertTrue(noticeTargetOpenable(n.target, { true }, demo.binding))
        // Seen from another connection the same notice cannot route.
        val elsewhere = notificationCenterViewOf(state, names, null, now).items.single()
        assertEquals(NoticeRoute.None, elsewhere.target!!.route)
        assertFalse(noticeTargetOpenable(elsewhere.target, { true }, null))
        // Privacy comes from the engine's preference and drops the detail.
        center.setPreferences { it.copy(privacy = true) }
        val private = notificationCenterViewOf(center.state.value, names, demo, now)
        assertTrue(private.privacy)
        assertNull(noticeText(private.items.single(), private.privacy).detail)
    }

    @Test fun actionsReachTheEngineAndSnoozeOnlySilences() {
        center.load()
        post("VM/120", "a stopped", NotificationTarget.None)
        post("VM/121", "b stopped", NotificationTarget.None)
        val actions = notificationActionsOf(source)
        val ids = center.state.value.items.map { it.id }
        actions.onMarkRead!!(setOf(ids[0]))
        assertTrue(center.state.value.items.first { it.id == ids[0] }.read)
        actions.onSnooze!!(ids[1], Duration.ofHours(1))
        val view = notificationCenterViewOf(center.state.value, names, demo, now)
        assertEquals(now.plusSeconds(3600), view.items.first { it.id == ids[1] }.snoozedUntil)
        assertEquals(1, unreadNoticeCount(view.items, now), "the snoozed one is still unread and listed")
        actions.onUnsnooze!!(ids[1])
        assertNull(notificationCenterViewOf(center.state.value, names, demo, now).items.first { it.id == ids[1] }.snoozedUntil)
        actions.onSnoozeAll!!(Duration.ofHours(4))
        assertEquals(now.plusSeconds(4 * 3600), notificationCenterViewOf(center.state.value, names, demo, now).snoozeAllUntil)
        actions.onSnoozeAll!!(null)
        assertNull(center.state.value.snoozeAllUntil)
        actions.onDismiss!!(ids[0])
        assertEquals(listOf(ids[1]), center.state.value.items.map { it.id })
        actions.onClearAll!!()
        assertTrue(center.state.value.items.isEmpty())
        assertNull(actions.onClearRead, "the engine has no clear-read: no control")
        assertNull(actions.onMarkUnread, "nor mark-unread")
    }

    @Test fun preferencesMapBothWaysAndKeepWatchingGoesToTheParentOnly() {
        center.load()
        val prefs = notificationPrefsViewOf(center.state.value.preferences, source.native, source.keepWatchingInBackground)
        assertEquals(false, prefs.keepWatchingInBackground)
        assertNull(prefs.inAppToasts)
        assertEquals(NotificationCategory.entries.size, prefs.categories.size)
        applyPrefChange(source, NotificationPrefChange.Category(NotificationCategory.TaskWarnings.name, true))
        applyPrefChange(source, NotificationPrefChange.Native(false))
        applyPrefChange(source, NotificationPrefChange.Enabled(false))
        val after = center.state.value.preferences
        assertTrue(after.enabled(NotificationCategory.TaskWarnings)); assertFalse(after.native); assertFalse(after.master)
        assertFalse(notificationPrefsViewOf(after, source.native, false).native.enabled)
        applyPrefChange(source, NotificationPrefChange.KeepWatchingInBackground(true))
        assertEquals(listOf(true), keep)
        assertEquals(after, center.state.value.preferences, "keep-watching is not an engine preference")
        assertNull(preferencesAfter(after, NotificationPrefChange.Category("NoSuchCategory", true)))
    }

    @Test fun watchToggleAndWatcherPageMapTheRealWatcher() {
        center.load(); watcher.load()
        val actions = watcherActionsOf(source) { demo }
        actions.onWatchGuest!!(ResourceId(DEMO_REMOTE_KEY, "", "VM", 120), "debian-workbench", true)
        actions.onWatchGuest!!(ResourceId("https://other.example.test:8006", "", "VM", 121), "elsewhere", true)
        actions.onWatchNode!!("demo-node-a", true)
        val view = watcherViewOf(watcher.state.value, center.state.value, names, demo, now)
        assertEquals(2, view.targets.size, "a guest of another connection is never watched from this one")
        assertEquals(setOf(ResourceId(DEMO_REMOTE_KEY, "", "VM", 120)), view.watchedGuests)
        assertEquals(setOf("demo-node-a"), view.watchedNodes)
        val guest = view.targets.first { it.kind == WatchTargetKind.Guest }
        assertEquals("debian-workbench", guest.name); assertEquals("VM", guest.guestKind); assertEquals(120, guest.vmid)
        // No session is open in this test: the engine says not connected, and the page shows it as unavailable.
        assertEquals(WatchTargetState.Unavailable("Not connected"), guest.availability)
        assertFalse(watchTargetOpenable(guest) { true })
        assertTrue(view.activity is WatcherActivity.Paused)
        // Seen from no connection at all: nothing is in view.
        assertFalse(watcherViewOf(watcher.state.value, null, names, null, now).targets.any { it.inView })
        actions.onUnwatch!!(guest.key)
        actions.onWatchNode!!("demo-node-a", false)
        assertTrue(watcher.state.value.watched.isEmpty())
        assertEquals(WatcherActivity.Off, watcherViewOf(watcher.state.value, null, names, demo, now).activity)
        assertTrue(problems.isEmpty(), "no refusals: $problems")
    }

    @Test fun namesNeverUseTheAddress() {
        assertEquals("Lab cluster", names.label("11111111-1111-1111-1111-111111111111", "b".repeat(64)))
        assertEquals("Removed profile", names.label("22222222-2222-2222-2222-222222222222", "b".repeat(64)))
        assertEquals("Manual connection", names.label(null, "b".repeat(64)))
        assertEquals("Demonstration", names.label(null, demo.binding))
        assertEquals("RingMASTER", names.label(null, null))
        assertEquals(WatchResource(CoreKind.Container, vmid = 7), watchResourceOfId(ResourceId(DEMO_REMOTE_KEY, "", "Container", 7)))
        assertNull(watchResourceOfId(ResourceId(DEMO_REMOTE_KEY, "", "Other", 7)))
    }
}
