@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import local.ringleader.core.ResourceId
import local.ringleader.core.Workspace
import local.ringleader.core.WorkspaceState
import local.ringleader.desktop.AlertsFixtures.DETAIL_CANARY
import local.ringleader.desktop.AlertsFixtures.NAME_CANARY
import local.ringleader.desktop.AlertsFixtures.SOURCE_CANARY

/** The notification center, toasts, preferences, Watcher page and rail hooks in offscreen scenes with synthetic data. */
class AlertsUiTest {
    private class Calls {
        val log = mutableListOf<String>()
        val actions = NotificationActions(
            onMarkRead = { log += "read:${it.sorted().joinToString()}" }, onMarkUnread = { log += "unread:$it" }, onMarkAllRead = { log += "all-read" },
            onDismiss = { log += "dismiss:$it" }, onClearRead = { log += "clear-read" }, onClearAll = { log += "clear-all" },
            onSnoozeAll = { log += "pause:${it?.toHours()}" }, onSnooze = { id, d -> log += "snooze:$id:${d.toHours()}h" },
            onUnsnooze = { log += "unsnooze:$it" }, onOpenTarget = { log += "opened:${it.id}" })
    }

    private val present = setOf(AlertsFixtures.resource(120))
    private val canOpen: (NoticeView) -> Boolean = { noticeTargetOpenable(it.target, { r -> r in present }, null) }

    private fun scene(width: Int = 700, height: Int = 900, fontScale: Float = 1f, reduce: Boolean = true, body: suspend StorageSceneDriver.() -> Unit, content: @Composable () -> Unit) = runSceneTest {
        val scene = ImageComposeScene(width, height, Density(1f, fontScale), coroutineContext = coroutineContext) {
            MaterialTheme(colorScheme = ringColors) { CompositionLocalProvider(LocalReduceMotion provides reduce) { Surface(Modifier.fillMaxSize()) { content() } } }
        }
        try { StorageSceneDriver(scene, storageLooks().first()).apply { settle() }.body() } finally { scene.close(); Appearance.preview(false) }
    }

    private fun StorageSceneDriver.texts(): List<String> = nodes().flatMap { n ->
        listOfNotNull(semanticsText(n).takeIf { it.isNotBlank() }) + n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }

    private fun StorageSceneDriver.clickDescribed(label: String) {
        val n = nodes().last { label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() && it.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick) != null }
        check(n.config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!.invoke())
    }

    private fun StorageSceneDriver.assertNoCanary(where: String) {
        val all = texts()
        listOf(NAME_CANARY, SOURCE_CANARY, DETAIL_CANARY).forEach { c -> assertFalse(all.any { c in it }, "$where leaked $c: $all") }
    }

    @Test fun anEmptyCenterSaysSoAndOffersNothingToDo() {
        val calls = Calls()
        scene(body = {
            assertTrue(matches("No notifications").isNotEmpty())
            assertTrue(clickable("MARK ALL READ").config.getOrNull(SemanticsProperties.Disabled) != null, "nothing to mark")
            assertTrue(clickable("CLEAR READ").config.getOrNull(SemanticsProperties.Disabled) != null, "nothing to clear")
        }) { NotificationCenterPanel(AlertsFixtures.center(emptyList()), calls.actions, canOpen, {}, {}, Modifier.padding(16.dp)) }
    }

    @Test fun filtersReadDismissClearAndSnoozeGoThroughTheCallbacks() {
        val calls = Calls()
        scene(body = {
            assertTrue(matches("6 unread", exact = false).isNotEmpty(), "the snoozed one still counts")
            click("CRITICAL 2"); settle()
            val headlines = listOf("Node offline", "Backup failed", "Guest stopped unexpectedly", "Schedule ran")
            assertEquals(listOf("Node offline", "Backup failed"), headlines.filter { matches(it).isNotEmpty() }, "only critical rows")
            click("MARK READ", last = false); click("DISMISS", last = false); click("MARK ALL READ"); click("CLEAR READ")
            click("SNOOZE ▾", last = false); settle()
            click("Desktop alerts for 4 hours"); settle()
            click("CLEAR ALL"); settle()
            assertTrue(matches("Clear all?").isNotEmpty(), "clearing unread notices asks once")
            click("KEEP"); settle()
            click("CLEAR ALL"); settle(); click("CLEAR"); settle()
            click("PAUSE DESKTOP NOTIFICATIONS \u25be"); settle(); click("For 1 hour"); settle()
            assertEquals(listOf("read:n1", "dismiss:n1", "all-read", "clear-read", "snooze:n1:4h", "clear-all", "pause:1"), calls.log)
            click("CRITICAL 2"); settle()
            assertTrue(matches("Guest stopped unexpectedly").isNotEmpty(), "filter off again: the warnings are back")
            click("SNOOZED 1"); settle()
            assertTrue(matches("Memory above 90 % for 5 minutes").isNotEmpty(), "the snoozed one is on its own tab")
            click("UNSNOOZE"); assertEquals("unsnooze:n7", calls.log.last())
        }) { NotificationCenterPanel(AlertsFixtures.center(AlertsFixtures.mixed()), calls.actions, canOpen, {}, {}, Modifier.padding(16.dp)) }
    }

    @Test fun anUnknownOrMissingTargetCannotBeOpened() {
        val calls = Calls(); val opened = mutableListOf<String>()
        scene(height = 1800, body = {
            assertTrue(matches("Not in the connection in view").size >= 1, "the retired guest's notice says why it cannot open")
            assertEquals(2, matches("OPEN GUEST").size, "n2 and the snoozed n7 (it stays in the inbox) point at the guest that is present")
            click("OPEN GUEST", last = false)
            assertEquals(listOf("n2"), opened, "the guest that is present opens")
            // Watcher started has no target at all: no Open control, no explanation either.
            assertTrue(matches("Watcher started").isNotEmpty())
        }) { NotificationCenterPanel(AlertsFixtures.center(AlertsFixtures.mixed()), calls.actions, canOpen, { opened += it.id }, {}, Modifier.padding(16.dp)) }
    }

    @Test fun privacyModeNeverComposesIdentifyingText() {
        val calls = Calls()
        scene(body = {
            assertTrue(matches("PRIVACY MODE", exact = false).isNotEmpty())
            assertTrue(matches("Guest stopped unexpectedly").isNotEmpty(), "the generic headline stays")
            assertNoCanary("privacy center")
            click("Every server ▾"); settle()
            assertTrue(matches("Server 1").isNotEmpty() && matches("Server 2").isNotEmpty(), "sources are numbered, not named")
            assertNoCanary("privacy source menu")
        }) { NotificationCenterPanel(AlertsFixtures.center(AlertsFixtures.mixed(), privacy = true), calls.actions, canOpen, {}, {}, Modifier.padding(16.dp)) }
    }

    @Test fun showcaseHidesIdentifyingTextFromScreenReaders() {
        val calls = Calls()
        Appearance.preview(false, showcase = true)
        scene(body = {
            assertNoCanary("showcase center")
            assertTrue(texts().contains("Hidden"))
            assertTrue(matches("Guest stopped unexpectedly").isNotEmpty())
        }) { NotificationCenterPanel(AlertsFixtures.center(AlertsFixtures.mixed()), calls.actions, canOpen, {}, {}, Modifier.padding(16.dp)) }
    }

    @Test fun longTextLargeTextAndANarrowPanelStayInside() {
        val calls = Calls()
        scene(width = 380, height = 900, fontScale = 1.5f, body = {
            val width = scene.semanticsOwners.first().rootSemanticsNode.boundsInRoot.width
            val overflow = nodes().filter { semanticsText(it).isNotBlank() && it.boundsInRoot.right > width + 1f }.map { semanticsText(it) to it.boundsInRoot.right }
            assertTrue(overflow.isEmpty(), "text past the right edge at 380 px and 150 % text: $overflow")
            assertTrue(texts().any { "« ünïcödé » · …" in it }, "Unicode punctuation survives")
        }) { NotificationCenterPanel(AlertsFixtures.center(listOf(AlertsFixtures.longText) + AlertsFixtures.mixed()), calls.actions, canOpen, {}, {}, Modifier.padding(12.dp)) }
    }

    @Test fun aBurstStaysALazyBoundedList() {
        val calls = Calls()
        scene(body = {
            assertTrue(matches("60 unread", exact = false).isNotEmpty())
            val rows = matches("Task failed").size
            assertTrue(rows in 1..20, "only the rows in view are composed ($rows)")
        }) { NotificationCenterPanel(AlertsFixtures.center(AlertsFixtures.burst(60)), calls.actions, canOpen, {}, {}, Modifier.padding(16.dp)) }
    }

    @Test fun toastsShowNewArrivalsBoundedAndCloseWithoutReading() {
        val items = mutableStateOf(AlertsFixtures.mixed())
        val opened = mutableListOf<String>(); var center = 0
        val suppressed = mutableStateOf(false)
        scene(width = 900, height = 700, body = {
            assertTrue(nodes().none { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { d -> "unread" in d } }, "the start-up inbox does not toast")
            items.value = AlertsFixtures.burst(5) + AlertsFixtures.mixed(); settle()
            val toasts = nodes().filter { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { d -> "Task failed" in d } }
            assertEquals(TOAST_VISIBLE_MAX, toasts.size, "three cards")
            assertTrue(matches("+2 more · open notifications").isNotEmpty())
            click("+2 more · open notifications"); assertEquals(1, center)
            clickDescribed("Close"); settle()
            assertEquals(TOAST_VISIBLE_MAX, nodes().count { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { d -> "Task failed" in d } }, "the next one moves up")
            assertTrue(matches("+1 more · open notifications").isNotEmpty())
            click("OPEN TASK", last = false); settle()
            assertEquals(1, opened.size)
            suppressed.value = true; settle()
            assertTrue(matches("OPEN TASK").isEmpty(), "suppressed (center open, full-screen console): the stack empties")
        }) {
            Box(Modifier.fillMaxSize()) {
                NotificationToasts(AlertsFixtures.center(items.value), privacy = false, suppressed = suppressed.value, canOpen = { true }, onOpen = { opened += it.id },
                    onOpenCenter = { center++ }, modifier = Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(12.dp), clock = { AlertsFixtures.now })
            }
        }
    }

    @Test fun privateToastsSpeakOnlyGenericText() {
        val items = mutableStateOf(emptyList<NoticeView>())
        scene(width = 900, height = 700, body = {
            items.value = listOf(AlertsFixtures.notice("p1", NoticeSeverity.Critical)); settle()
            val spoken = texts().filter { "Guest stopped unexpectedly" in it }
            assertTrue(spoken.isNotEmpty(), "the toast is announced")
            assertNoCanary("private toast")
        }) {
            Box(Modifier.fillMaxSize()) {
                NotificationToasts(AlertsFixtures.center(items.value), privacy = true, suppressed = false, canOpen = { true }, onOpen = {}, onOpenCenter = {},
                    modifier = Modifier.align(androidx.compose.ui.Alignment.BottomEnd), clock = { AlertsFixtures.now })
            }
        }
    }

    @Test fun keepWatchingInTheBackgroundIsAnExplicitOffByDefaultChoice() {
        val changes = mutableListOf<NotificationPrefChange>()
        scene(body = {
            val row = clickable("Keep watching connected sessions while minimized or in the tray")
            assertEquals(androidx.compose.ui.state.ToggleableState.Off, row.config.getOrNull(SemanticsProperties.ToggleableState))
            assertTrue(matches("It never signs in and never uses saved secrets.", exact = false).isNotEmpty())
            assertTrue(matches("In-app toasts").isEmpty(), "the engine has no toast setting: no row")
            click("Keep watching connected sessions while minimized or in the tray")
            assertEquals(listOf<NotificationPrefChange>(NotificationPrefChange.KeepWatchingInBackground(true)), changes)
        }) { NotificationPreferencesPanel(AlertsFixtures.prefs().copy(inAppToasts = null, keepWatchingInBackground = false), { changes += it }, Modifier.padding(16.dp)) }
    }

    @Test fun preferencesGoOutAsChangesAndAnUnavailableHelperSaysWhy() {
        val changes = mutableListOf<NotificationPrefChange>()
        scene(body = {
            assertTrue(matches("The desktop notification helper is not installed.").isNotEmpty())
            assertTrue(clickable("Desktop notifications").config.getOrNull(SemanticsProperties.Disabled) == null, "availability must not prevent changing the saved preference or retrying preparation")
            click("Desktop notifications")
            click("Failed tasks"); click("Privacy mode"); click("Notifications")
            assertEquals(listOf(NotificationPrefChange.Native(true), NotificationPrefChange.Category("task", true), NotificationPrefChange.Privacy(true), NotificationPrefChange.Enabled(false)), changes)
        }) { NotificationPreferencesPanel(AlertsFixtures.prefs(native = false), { changes += it }, Modifier.padding(16.dp)) }
    }

    @Test fun watcherListsRetainedTargetsWithWorkingUnwatchAndHonestOpen() {
        val unwatched = mutableListOf<String>(); val opened = mutableListOf<String>()
        scene(width = 1200, height = 800, body = {
            assertTrue(matches("Not in the inventory · last seen 2 d ago").isNotEmpty(), "a vanished guest stays listed with why")
            assertTrue(matches("Server not reachable · last seen 20 m ago").isNotEmpty())
            assertTrue(matches("Datacenter").isNotEmpty(), "two servers: a heading for each")
            val opens = matches("OPEN").mapNotNull { n -> var p: androidx.compose.ui.semantics.SemanticsNode? = n; while (p != null && p.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick) == null) p = p.parent; p }
            assertEquals(6, opens.size)
            assertEquals(3, opens.count { it.config.getOrNull(SemanticsProperties.Disabled) == null }, "only the two live guests in view and the live node open")
            click("OPEN", last = false)
            assertEquals(listOf("w2"), opened, "the incident row is first, and it is in view")
            click("UNWATCH", last = true)
            assertEquals(1, unwatched.size)
        }) {
            WatcherScreen(AlertsFixtures.watcher(), WatcherActions(onUnwatch = { unwatched += it }), { r -> r in present || r == ResourceId(local.ringleader.core.DEMO_REMOTE_KEY, "", "Container", 121) },
                { opened += it.key }, Modifier.padding(16.dp))
        }
    }

    @Test fun watcherNamesAreHiddenInShowcaseAndTheEmptyStateExplainsHowToWatch() {
        Appearance.preview(false, showcase = true)
        scene(width = 1200, height = 600, body = {
            assertNoCanary("showcase watcher")
            assertTrue(texts().contains("Hidden"))
        }) { WatcherScreen(AlertsFixtures.watcher(), WatcherActions(onUnwatch = {}), { true }, {}) }
        scene(width = 960, height = 600, body = {
            assertTrue(matches("Nothing is watched yet").isNotEmpty())
            assertTrue(matches("Choose Watch", exact = false).isNotEmpty())
        }) { WatcherScreen(AlertsFixtures.watcher(emptyList()), WatcherActions(onWatchGuest = { _, _, _ -> }), { true }, {}) }
    }

    @Test fun theWatchToggleAppearsOnlyWhenTheParentWiredIt() {
        val toggles = mutableListOf<String>()
        val wired = AlertsUi(watcher = AlertsFixtures.watcher(), watcherActions = WatcherActions(onWatchGuest = { r, name, on -> toggles += "${r.vmid}:$name:$on" }))
        scene(body = {
            assertTrue(matches("WATCHING").isNotEmpty(), "guest 120 is watched")
            click("WATCH")
            assertEquals(listOf("999:new-guest:true"), toggles)
        }) {
            CompositionLocalProvider(LocalAlerts provides wired) {
                androidx.compose.foundation.layout.Column {
                    WatchGuestButton(AlertsFixtures.resource(120), "x")
                    WatchGuestButton(AlertsFixtures.resource(999), "new-guest")
                }
            }
        }
        scene(body = { assertTrue(matches("WATCH").isEmpty() && matches("WATCHING").isEmpty(), "no Watcher wired: no control") }) {
            CompositionLocalProvider(LocalAlerts provides AlertsUi(watcher = AlertsFixtures.watcher())) { WatchGuestButton(AlertsFixtures.resource(120), "x") }
            WatchGuestButton(AlertsFixtures.resource(120), "x")
        }
    }

    @Test fun theRailShowsTheBellAndWatcherOnlyWhenProvided() {
        val workspace = Workspace(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        var bell = 0; var page = ""
        val alerts = AlertsUi(notices = AlertsFixtures.center(AlertsFixtures.mixed()), watcher = AlertsFixtures.watcher())
        try {
            scene(width = 300, height = 900, body = {
                assertTrue(matches("Watcher").isNotEmpty(), "the Watcher page is in the rail")
                assertTrue(matches("6").isNotEmpty(), "the bell carries the unread count")
                click("Notifications"); assertEquals(1, bell)
                click("Watcher"); assertEquals(WATCHER_PAGE, page)
            }) {
                CompositionLocalProvider(LocalAlerts provides alerts) {
                    NavigationRail(WorkspaceState(), "Desk", "Test", workspace, initialExpanded = true, onNotifications = { bell++ }) { page = it }
                }
            }
            scene(width = 300, height = 900, body = {
                assertTrue(matches("Watcher").isEmpty() && matches("Notifications").isEmpty(), "nothing provided: neither appears")
            }) { NavigationRail(WorkspaceState(), "Desk", "Test", workspace, initialExpanded = true, onNotifications = { bell++ }) { page = it } }
        } finally { workspace.close() }
        assertNull(noticeBadgeText(0))
        assertEquals(Duration.ofHours(1), DefaultSnoozeChoices.first().duration)
    }
}
