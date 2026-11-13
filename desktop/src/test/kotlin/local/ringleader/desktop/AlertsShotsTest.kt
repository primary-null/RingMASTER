package local.ringleader.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.*
import local.ringleader.core.*
import org.junit.jupiter.api.Test

/**
 * Opt-in pictures (RINGMASTER_SCREENSHOTS=1, phase from RINGMASTER_UI_PHASE) of the Watcher page, the notification center,
 * its preferences, the toasts and the Watch toggles, in the same shell as UiScreenshotsTest. Synthetic data only; never
 * connects to a server. Written to artifacts/ui/<phase>/alerts/.
 */
class AlertsShotsTest {
    @Test fun alertsScreens() {
        if (System.getenv("RINGMASTER_SCREENSHOTS") != "1") return
        val phase = System.getenv("RINGMASTER_UI_PHASE") ?: "after"
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val workspace = Workspace(scope)
        workspace.exploreDemo()
        runBlocking { delay(2500) }
        workspace.select(workspace.state.value.overview!!.guests.first())
        runBlocking { delay(500) }
        val state = workspace.state.value
        val first = state.overview!!.guests.first()
        val targets = AlertsFixtures.targets().mapIndexed { i, t -> if (i == 0) t.copy(resource = first.resource, vmid = first.id, name = first.name) else t }
        val notices = AlertsFixtures.mixed()
        val alerts = AlertsUi(notices = AlertsFixtures.center(notices), noticeActions = NotificationActions(onMarkRead = {}, onMarkAllRead = {}, onDismiss = {}, onClearRead = {}, onSnooze = { _, _ -> }, onUnsnooze = {}),
            prefs = AlertsFixtures.prefs(native = false), onPrefChange = {}, watcher = AlertsFixtures.watcher(targets),
            watcherActions = WatcherActions(onUnwatch = {}, onWatchGuest = { _, _, _ -> }))
        val privateAlerts = AlertsUi(notices = AlertsFixtures.center(notices, privacy = true), noticeActions = alerts.noticeActions, prefs = alerts.prefs, onPrefChange = {}, watcher = alerts.watcher, watcherActions = alerts.watcherActions)
        val canOpen: (NoticeView) -> Boolean = { noticeTargetOpenable(it.target, { r -> state.overview?.guests?.any { g -> g.resource == r } == true }, null) }
        val toastQueue = { ToastQueue().apply { offer(emptyList(), AlertsFixtures.now); offer(AlertsFixtures.burst(5) + listOf(AlertsFixtures.longText), AlertsFixtures.now) } }
        val toastItems = AlertsFixtures.burst(5) + listOf(AlertsFixtures.longText)

        class Shot(val page: String, val alerts: AlertsUi, val content: @Composable () -> Unit)
        val shots = linkedMapOf(
            "watcher" to Shot(WATCHER_PAGE, alerts) { WatcherScreen(alerts.watcher!!, alerts.watcherActions, { r -> state.overview?.guests?.any { it.resource == r } == true }, {}, unreadNotices = 5, onNotifications = {}) },
            "watcher-empty" to Shot(WATCHER_PAGE, alerts) { WatcherScreen(AlertsFixtures.watcher(emptyList()), alerts.watcherActions, { true }, {}) },
            "notices-center" to Shot("Fleet", alerts) {
                InventoryScreen(state, workspace, null, "", {}, "All", {}, {}, {})
                NotificationCenterOverlay(alerts, canOpen, {}) {}
            },
            "notices-private" to Shot("Fleet", privateAlerts) {
                InventoryScreen(state, workspace, null, "", {}, "All", {}, {}, {})
                NotificationCenterOverlay(privateAlerts, canOpen, {}) {}
            },
            "notices-prefs" to Shot("Fleet", alerts) {
                InventoryScreen(state, workspace, null, "", {}, "All", {}, {}, {})
                NotificationCenterOverlay(alerts, canOpen, {}, initialTab = NoticeTab.Preferences) {}
            },
            "notices-empty" to Shot("Desk", alerts) { NotificationCenterOverlay(AlertsUi(notices = AlertsFixtures.center(emptyList()), noticeActions = alerts.noticeActions), canOpen, {}) {} },
            "toasts" to Shot("Fleet", alerts) {
                Box(Modifier.fillMaxSize()) {
                    InventoryScreen(state, workspace, null, "", {}, "All", {}, {}, {})
                    NotificationToasts(AlertsFixtures.center(toastItems), privacy = false, suppressed = false, canOpen = { true }, onOpen = {}, onOpenCenter = {},
                        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp), clock = { AlertsFixtures.now }, queue = toastQueue())
                }
            },
            "toasts-private" to Shot("Fleet", alerts) {
                Box(Modifier.fillMaxSize()) {
                    InventoryScreen(state, workspace, null, "", {}, "All", {}, {}, {})
                    NotificationToasts(AlertsFixtures.center(toastItems), privacy = true, suppressed = false, canOpen = { true }, onOpen = {}, onOpenCenter = {},
                        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp), clock = { AlertsFixtures.now }, queue = toastQueue())
                }
            },
            "fleet-watch" to Shot("Fleet", alerts) { InventoryScreen(state, workspace, null, "", {}, "All", {}, {}, {}) },
            "guest-watch" to Shot("Fleet", alerts) { GuestDetailPage(state, workspace, {}, { _, _ -> }) },
        )
        // Dark at both review sizes with the rail collapsed and expanded; light, Showcase and 150 % text at 1600x900.
        data class Look(val prefix: String, val light: Boolean, val showcase: Boolean, val text: Float, val sizes: List<Pair<Int, Int>>, val expanded: List<Boolean>)
        val looks = listOf(Look("", false, false, 1f, listOf(1600 to 900, 960 to 640), listOf(false, true)),
            Look("light-", true, false, 1f, listOf(1600 to 900), listOf(false)), Look("showcase-", false, true, 1f, listOf(1600 to 900), listOf(false)),
            Look("text150-", false, false, 1.5f, listOf(1600 to 900, 960 to 640), listOf(false)))
        val out = File("../artifacts/ui/$phase/alerts").apply { mkdirs() }
        try {
            for (look in looks) for ((w, h) in look.sizes) for ((name, shot) in shots) for (expanded in look.expanded) {
                if (look.prefix.isNotEmpty() && name in setOf("watcher-empty", "notices-empty", "fleet-watch")) continue
                Appearance.preview(look.light, look.showcase, look.text)
                val scene = ImageComposeScene(w, h, Density(1f, look.text)) {
                    MaterialTheme(colorScheme = ringColors) { CompositionLocalProvider(LocalReduceMotion provides true, LocalAlerts provides shot.alerts) {
                        Surface(Modifier.fillMaxSize()) { Column {
                            Row(Modifier.fillMaxWidth().height(32.dp)) { Text("◈ RingMASTER", Modifier.weight(1f), color = Tech.Amber); WindowControl("—") {}; WindowControl("□") {}; WindowControl("×") {} }
                            Row(Modifier.weight(1f)) {
                                NavigationRail(state, shot.page, "Screenshot / Windows", workspace, initialExpanded = expanded, onNotifications = {}) {}
                                Column(Modifier.weight(1f).padding(16.dp)) {
                                    Row(Modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text("WORKSPACE / ${shot.page.uppercase()}", fontSize = Tech.Label, color = Tech.Muted); Text("RingMASTER / $name", fontSize = Tech.Title)
                                    }
                                    HorizontalDivider(color = Tech.Edge, modifier = Modifier.padding(vertical = Tech.Space12))
                                    Box(Modifier.weight(1f)) { shot.content() }
                                }
                            }
                        } }
                    } }
                }
                try {
                    var image = scene.render(0L)
                    for (i in 1..6) { image.close(); image = scene.render(i * 16_000_000L) }
                    File(out, "${look.prefix}$name-${if (expanded) "expanded" else "collapsed"}-${w}x$h.png").writeBytes(image.encodeToData()!!.bytes)
                    image.close()
                } finally { scene.close() }
            }
        } finally { Appearance.preview(false); workspace.close(); scope.cancel() }
    }
}
