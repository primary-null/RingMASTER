package local.ringleader.desktop

import java.time.Duration
import java.time.Instant
import local.ringleader.core.DEMO_REMOTE_KEY
import local.ringleader.core.ResourceId

/**
 * Synthetic notices and watched targets for the notification and Watcher tests and pictures (test source set only).
 * Names are invented; the "canary" strings let tests prove that identifying text never reaches the screen or a screen
 * reader in privacy mode and Showcase.
 */
internal object AlertsFixtures {
    val now: Instant = Instant.parse("2026-11-13T10:00:00Z")
    const val NAME_CANARY = "canary-guest-name"
    const val SOURCE_CANARY = "canary-lab-server"
    const val DETAIL_CANARY = "canary-detail-text"

    fun resource(vmid: Int) = ResourceId(DEMO_REMOTE_KEY, "", "VM", vmid)

    fun notice(id: String, severity: NoticeSeverity = NoticeSeverity.Warning, minutesAgo: Long = 5, read: Boolean = false, source: String = "lab",
               sourceLabel: String = SOURCE_CANARY, target: NoticeTarget? = NoticeTarget(NoticeTargetKind.Guest, NAME_CANARY, NoticeRoute.Guest(resource(120))),
               headline: String = "Guest stopped unexpectedly", detail: String? = "$DETAIL_CANARY: stopped outside RingMASTER", category: String = "Guests",
               incident: NoticeIncident = NoticeIncident.Ongoing, snoozedUntil: Instant? = null, osDismissed: Boolean = false, repeats: Int = 1) =
        NoticeView(id, severity, category, headline, detail, source, sourceLabel, target, now.minusSeconds(minutesAgo * 60), read, incident, snoozedUntil, osDismissed, repeats)

    /** A realistic mix: every severity, two servers, an unknown target, a recovery, a snoozed one, an OS-dismissed one. */
    fun mixed(): List<NoticeView> = listOf(
        notice("n1", NoticeSeverity.Critical, 2, headline = "Node offline", category = "Nodes", detail = "Not answering for 3 minutes",
            target = NoticeTarget(NoticeTargetKind.Node, "pve-node-b", NoticeRoute.Node("demo-node-b"))),
        notice("n2", NoticeSeverity.Warning, 6),
        notice("n3", NoticeSeverity.Critical, 14, headline = "Backup failed", category = "Backups", detail = "vzdump ended with: storage unavailable",
            target = NoticeTarget(NoticeTargetKind.Task, "vzdump 124", NoticeRoute.Activity), incident = NoticeIncident.Event),
        notice("n4", NoticeSeverity.Success, 25, read = true, headline = "Node back online", category = "Nodes", detail = null,
            incident = NoticeIncident.Recovered(now.minusSeconds(25 * 60), Duration.ofMinutes(12)), osDismissed = true,
            target = NoticeTarget(NoticeTargetKind.Node, "pve-node-c", NoticeRoute.Node("demo-node-c"))),
        notice("n5", NoticeSeverity.Info, 40, source = "dc", sourceLabel = "Datacenter", headline = "Schedule ran", category = "Schedules", detail = "Start · completed OK",
            target = NoticeTarget(NoticeTargetKind.Schedule, "Nightly start", NoticeRoute.Schedules), incident = NoticeIncident.Event, read = true),
        notice("n6", NoticeSeverity.Warning, 55, headline = "Guest gone", detail = "No longer in the inventory",
            target = NoticeTarget(NoticeTargetKind.Guest, "retired-guest", NoticeRoute.Guest(resource(404)))),
        notice("n7", NoticeSeverity.Warning, 70, snoozedUntil = now.plusSeconds(3600), headline = "Memory above 90 % for 5 minutes", category = "Thresholds"),
        notice("n8", NoticeSeverity.Info, 90, target = null, headline = "Watcher started", category = "RingMASTER", detail = null, incident = NoticeIncident.Event, repeats = 3),
    )

    /** Many notices at once (a flapping node, a failing backup job): the center and the toast stack must stay bounded. */
    fun burst(count: Int): List<NoticeView> = (1..count).map { i ->
        notice("b$i", if (i % 7 == 0) NoticeSeverity.Critical else NoticeSeverity.Warning, minutesAgo = (count - i).toLong(), headline = "Task failed",
            category = "Tasks", detail = "qmstart $i ended with an error", target = NoticeTarget(NoticeTargetKind.Task, "qmstart $i", NoticeRoute.Activity), incident = NoticeIncident.Event)
    }

    val longText = notice("long", NoticeSeverity.Critical, 1,
        headline = "Storage pool degraded on several nodes and the replication jobs that depend on it are overdue by more than one day",
        detail = "zpool status reports DEGRADED: one of two mirrored devices is unavailable; resilvering has not started; " +
            "the following datasets are affected: rpool/data/vm-120-disk-0, rpool/data/vm-121-disk-0, rpool/data/vm-122-disk-0 — « ünïcödé » · …",
        target = NoticeTarget(NoticeTargetKind.Storage, "local-zfs-with-a-very-long-storage-identifier-name", NoticeRoute.Storage), category = "Storage")

    fun center(items: List<NoticeView>, privacy: Boolean = false) = NotificationCenterView(items, privacy, now)

    fun prefs(native: Boolean = true) = NotificationPrefsView(enabled = true, inAppToasts = true,
        native = if (native) NativeDeliveryView(available = true, enabled = true) else NativeDeliveryView(false, false, "The desktop notification helper is not installed."),
        privacy = false, categories = listOf(
            NoticeCategoryPreference("guest-stop", "Guest stopped unexpectedly", "Stopped outside RingMASTER and outside a schedule.", true),
            NoticeCategoryPreference("backup", "Backup failure", null, true),
            NoticeCategoryPreference("node", "Node offline or back", null, true),
            NoticeCategoryPreference("task", "Failed tasks", "Any task, from any user.", false),
            NoticeCategoryPreference("schedule", "Schedule results", null, true),
        ), keepWatchingInBackground = false)

    fun targets(): List<WatchTargetView> = listOf(
        WatchTargetView("w1", WatchTargetKind.Guest, NAME_CANARY, "lab", SOURCE_CANARY, "VM", 120, node = "demo-node-a", resource = resource(120),
            availability = WatchTargetState.Live, status = "running", healthy = true, lastChange = "Started", lastChangeAt = now.minusSeconds(3 * 3600)),
        WatchTargetView("w2", WatchTargetKind.Guest, "dns-service", "lab", SOURCE_CANARY, "CT", 121, node = "demo-node-b", resource = ResourceId(DEMO_REMOTE_KEY, "", "Container", 121),
            availability = WatchTargetState.Live, status = "stopped", healthy = false, unread = 2, incident = true, lastChange = "Stopped unexpectedly", lastChangeAt = now.minusSeconds(6 * 60)),
        WatchTargetView("w3", WatchTargetKind.Guest, "retired-guest", "lab", SOURCE_CANARY, "VM", 404, node = "demo-node-c", resource = resource(404),
            availability = WatchTargetState.Unavailable("Not in the inventory", now.minusSeconds(2 * 86_400)), status = "stopped", unread = 1),
        WatchTargetView("w4", WatchTargetKind.Node, "demo-node-a", "lab", SOURCE_CANARY, availability = WatchTargetState.Live, status = "online", healthy = true,
            since = now.minusSeconds(86_400)),
        WatchTargetView("w5", WatchTargetKind.Node, "dc-node-1", "dc", "Datacenter", availability = WatchTargetState.Unavailable("Server not reachable", now.minusSeconds(20 * 60)), status = "unknown"),
        WatchTargetView("w6", WatchTargetKind.Guest, "build-runner", "dc", "Datacenter", "VM", 210, resource = resource(210), availability = WatchTargetState.Pending),
    )

    fun watcher(targets: List<WatchTargetView> = targets()) = WatcherView(targets, WatcherActivity.Watching(15), now, watchedNodes = setOf("demo-node-a"))
}
