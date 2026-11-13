package local.ringleader.desktop

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import local.ringleader.desktop.AlertsFixtures.DETAIL_CANARY
import local.ringleader.desktop.AlertsFixtures.NAME_CANARY
import local.ringleader.desktop.AlertsFixtures.SOURCE_CANARY
import local.ringleader.desktop.AlertsFixtures.now

/** The notification and Watcher presentation rules, without Compose. */
class NotificationPresentationTest {
    private val items = AlertsFixtures.mixed()

    @Test fun badgeCountsEveryUnreadNoticeSnoozedOrNot() {
        // n1 n2 n3 n6 n7 n8 unread; n4 n5 read. Snoozing silences desktop delivery only (the engine's rule), so n7 counts.
        assertEquals(6, unreadNoticeCount(items, now))
        assertEquals(6, unreadNoticeCount(items, now.plusSeconds(3601)))
        assertNull(noticeBadgeText(0)); assertEquals("7", noticeBadgeText(7)); assertEquals("99+", noticeBadgeText(250))
    }

    @Test fun filtersBySeveritySourceAndUnreadAndSortsUnreadSeriousNewestFirst() {
        val all = filterNotices(items, NoticeFilter(), now)
        assertTrue(all.any { it.id == "n7" }, "a snoozed notice stays in the inbox")
        assertEquals(listOf("n7"), filterNotices(items, NoticeFilter(snoozedOnly = true), now).map { it.id }, "the Snoozed tab lists the silenced ones")
        assertTrue(filterNotices(items, NoticeFilter(snoozedOnly = true), now.plusSeconds(3601)).isEmpty(), "until the snooze runs out")
        assertEquals(listOf("n1", "n3"), all.take(2).map { it.id }, "unread critical first, newest first")
        assertTrue(all.indexOfFirst { it.read } > all.indexOfLast { !it.read }, "read after unread")
        assertEquals(listOf("n1", "n3"), filterNotices(items, NoticeFilter(severities = setOf(NoticeSeverity.Critical)), now).map { it.id })
        assertEquals(listOf("n5"), filterNotices(items, NoticeFilter(sourceId = "dc"), now).map { it.id })
        assertTrue(filterNotices(items, NoticeFilter(unreadOnly = true), now).none { it.read })
        assertEquals(listOf("lab" to SOURCE_CANARY, "dc" to "Datacenter"), noticeSources(items))
        assertTrue(filterNotices(emptyList(), NoticeFilter(), now).isEmpty())
    }

    @Test fun privacyKeepsOnlyGenericTextAndShowcaseKeepsIdentifyingTextOutOfTheSpokenLine() {
        val n = items[1]
        val plain = noticeText(n, privacy = false)
        assertTrue(plain.detail!!.contains(DETAIL_CANARY)); assertTrue(plain.target!!.contains(NAME_CANARY)); assertEquals(SOURCE_CANARY, plain.source)
        assertTrue(plain.spoken.contains(NAME_CANARY))
        val private = noticeText(n, privacy = true)
        assertNull(private.detail); assertNull(private.target); assertNull(private.source)
        assertEquals("Guest stopped unexpectedly", private.headline)
        listOf(NAME_CANARY, SOURCE_CANARY, DETAIL_CANARY).forEach { assertFalse(private.spoken.contains(it), "privacy spoken line leaks $it: ${private.spoken}") }
        val showcase = noticeText(n, privacy = false, showcase = true)
        assertTrue(showcase.detail != null, "Showcase keeps the layout (the window blurs the text)")
        listOf(NAME_CANARY, SOURCE_CANARY, DETAIL_CANARY).forEach { assertFalse(showcase.spoken.contains(it), "showcase spoken line leaks $it") }
        assertTrue(showcase.spoken.contains("details hidden"))
    }

    @Test fun readStateIncidentStateAndOsDismissalAreSeparate() {
        val recovered = items.first { it.id == "n4" }
        assertTrue(recovered.read && recovered.osDismissed)
        assertEquals("Recovered after 12 m", incidentLabel(recovered.incident))
        assertEquals("Ongoing", incidentLabel(NoticeIncident.Ongoing))
        assertNull(incidentLabel(NoticeIncident.Event))
        // An OS-dismissed but unread notice still counts; a recovered unread one too.
        val unreadDismissed = AlertsFixtures.notice("x", osDismissed = true, incident = NoticeIncident.Recovered(now))
        assertEquals(1, unreadNoticeCount(listOf(unreadDismissed), now))
        assertTrue(noticeText(unreadDismissed, false).spoken.contains("unread"))
    }

    @Test fun agesAndDurations() {
        assertEquals("now", noticeAge(now.minusSeconds(10), now))
        assertEquals("now", noticeAge(now.plusSeconds(30), now), "clock skew reads as now")
        assertEquals("5 m ago", noticeAge(now.minusSeconds(300), now))
        assertEquals("3 h ago", noticeAge(now.minusSeconds(3 * 3600 + 59), now))
        assertEquals("2 d", compactDuration(Duration.ofDays(2)))
    }

    @Test fun targetsOpenOnlyWhenTheWindowCanReachThem() {
        val present = setOf(AlertsFixtures.resource(120))
        val guest = items[1].target
        assertTrue(noticeTargetOpenable(guest, { it in present }, "demo"))
        assertFalse(noticeTargetOpenable(items.first { it.id == "n6" }.target, { it in present }, "demo"), "a guest no longer in the inventory")
        assertFalse(noticeTargetOpenable(null, { true }, "demo"), "no target")
        assertFalse(noticeTargetOpenable(NoticeTarget(NoticeTargetKind.Connection, "x"), { true }, "demo"), "a target without a route")
        assertFalse(noticeTargetOpenable(guest!!.copy(sessionKey = "other-profile"), { true }, "demo"), "another connection's guest")
        assertTrue(noticeTargetOpenable(guest.copy(sessionKey = "demo"), { it in present }, "demo"))
        assertTrue(noticeTargetOpenable(NoticeTarget(NoticeTargetKind.Task, "t", NoticeRoute.Activity), { false }, null))
    }

    @Test fun toastsAreOnlyForNewArrivalsAndStayBounded() {
        val q = ToastQueue()
        q.offer(items, now)
        assertEquals(0, q.showing, "what exists at start-up is the center's business, not a burst of toasts")
        val burst = items + AlertsFixtures.burst(12)
        q.offer(burst, now)
        assertEquals(12, q.showing)
        assertEquals(TOAST_VISIBLE_MAX, q.visible(burst).size)
        assertEquals(12 - TOAST_VISIBLE_MAX, q.overflow())
        assertEquals("b12", q.visible(burst).first().id, "newest on top")
    }

    @Test fun toastLifetimesHoldsAndClosing() {
        val q = ToastQueue()
        q.offer(emptyList(), now)
        val info = AlertsFixtures.notice("i", NoticeSeverity.Info); val warn = AlertsFixtures.notice("w", NoticeSeverity.Warning); val crit = AlertsFixtures.notice("c", NoticeSeverity.Critical)
        val list = listOf(info, warn, crit)
        q.offer(list, now)
        assertEquals(now.plusSeconds(6), q.nextExpiry(list))
        q.expire(list, now.plusSeconds(7))
        assertEquals(setOf("w", "c"), q.visible(list).map { it.id }.toSet(), "info left after 6 s")
        q.pause("w", true)
        q.expire(list, now.plusSeconds(30))
        assertTrue(q.visible(list).any { it.id == "w" }, "a held toast stays")
        q.pause("w", false)
        q.expire(list, now.plusSeconds(39))
        assertTrue(q.visible(list).any { it.id == "w" }, "released: a full lifetime again from the last hold")
        q.expire(list, now.plusSeconds(41))
        assertEquals(listOf("c"), q.visible(list).map { it.id }, "critical stays until closed")
        assertNull(q.nextExpiry(list))
        q.close("c")
        assertEquals(0, q.showing)
        // Closing a toast is not reading: the notice is untouched (the queue never changes a notice).
        assertFalse(crit.read)
    }

    @Test fun readSnoozedOrSuppressedNoticesDoNotToast() {
        val q = ToastQueue()
        q.offer(emptyList(), now)
        val a = AlertsFixtures.notice("a"); val b = AlertsFixtures.notice("b", read = true); val c = AlertsFixtures.notice("c", snoozedUntil = now.plusSeconds(60))
        q.offer(listOf(a, b, c), now)
        assertEquals(listOf("a"), q.visible(listOf(a, b, c)).map { it.id })
        q.offer(listOf(a.copy(read = true), b, c), now)
        assertEquals(0, q.showing, "read elsewhere (the center): its toast goes")
        val d = AlertsFixtures.notice("d")
        q.offer(listOf(d), now, enabled = false)
        assertEquals(0, q.showing, "while suppressed nothing queues, and it does not come back later")
        q.offer(listOf(d), now)
        assertEquals(0, q.showing)
    }

    @Test fun watcherOrderCountsAndLines() {
        val t = AlertsFixtures.targets()
        val ordered = orderWatchTargets(t)
        assertEquals("w2", ordered.first().key, "an open incident first")
        assertEquals(setOf("w3", "w5"), ordered.drop(1).take(2).map { it.key }.toSet(), "then the unavailable ones")
        assertEquals(WatchCounts(6, 1, 2), watchCounts(t))
        assertEquals("Not in the inventory · last seen 2 d ago", watchStatusLine(t[2], now))
        assertEquals("Waiting for the first read", watchStatusLine(t[5], now))
        assertEquals("Stopped unexpectedly 6 m ago", watchStatusLine(t[1], now))
        assertEquals("watched since 1 d", watchStatusLine(t[3], now))
        assertEquals("Watching every 15 s", watcherActivityLabel(WatcherActivity.Watching(15)))
        assertEquals("Watching", watcherActivityLabel(WatcherActivity.Watching()))
        assertFalse(watchTargetOpenable(t[3].copy(inView = false)) { true }, "another connection's node never routes here")
        assertEquals("Paused · Window hidden", watcherActivityLabel(WatcherActivity.Paused("Window hidden")))
        val present = setOf(AlertsFixtures.resource(120))
        assertTrue(watchTargetOpenable(t[0]) { it in present })
        assertFalse(watchTargetOpenable(t[2]) { true }, "unavailable never opens")
        assertFalse(watchTargetOpenable(t[5]) { true }, "pending never opens")
        assertTrue(watchTargetOpenable(t[3]) { false }, "a live node opens (the fleet selects it)")
    }
}
