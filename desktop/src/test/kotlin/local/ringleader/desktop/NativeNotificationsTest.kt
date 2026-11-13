package local.ringleader.desktop

import java.io.ByteArrayInputStream
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

class NativeNotificationsTest {
    private fun note(id: String = "incident:cpu", token: String? = "evt_42", severity: NativeSeverity = NativeSeverity.Warning) =
        NativeNotification(id, "CPU high", "Above 90% for 5 minutes", severity, token)

    // ---- payload and text ----

    @Test fun activationTokensRejectUrlsUpidsAndFreeText() {
        assertEquals("evt_42-a", NativeNotification("x", "t", "", activationToken = "evt_42-a").activationToken)
        for (bad in listOf("https://pve/x", "UPID:example-node:000A:qmstart:101:root@pam:", "web server", "", "a".repeat(65), "tok/../x")) {
            assertFailsWith<IllegalArgumentException>(bad) { NativeNotification("x", "t", "", activationToken = bad) }
        }
        assertFailsWith<IllegalArgumentException> { NativeNotification(" ", "t", "") }
        assertFailsWith<IllegalArgumentException> { NativeNotification("x", " ", "") }
        assertFalse(note().toString().contains("evt_42"))
    }

    @Test fun textDropsControlsBidiAndLoneSurrogatesButKeepsEmoji() {
        val raw = "a\u0007b\u202Ec\u2066d\r\ne\tf\uD800g\uFFFF \uD83D\uDE80"
        assertEquals("abcd\ne fg \uD83D\uDE80", NativeNotificationText.clean(raw))
        assertEquals("one two", NativeNotificationText.title("one\n\ntwo"))
    }

    @Test fun textIsBoundedByCodePointsAndLines() {
        val title = NativeNotificationText.title("x".repeat(500))
        assertEquals(NativeNotificationText.MAX_TITLE, title.codePointCount(0, title.length))
        assertTrue(title.endsWith("…"))
        val body = NativeNotificationText.body((1..20).joinToString("\n") { "line $it" })
        assertEquals(NativeNotificationText.MAX_BODY_LINES, body.lines().size)
        assertTrue(body.endsWith("…"))
        assertEquals("a\n\nb", NativeNotificationText.body("a\n\n\n\n\nb"))
        val emoji = NativeNotificationText.bound("\uD83D\uDE80".repeat(10), 4)
        assertEquals("\uD83D\uDE80\uD83D\uDE80\uD83D\uDE80…", emoji)
    }

    @Test fun markupEscapesTheCharactersThatStartMarkup() {
        assertEquals("&lt;b&gt;R&amp;D&lt;/b&gt; \"q\"", NativeNotificationText.escapeMarkup("<b>R&D</b> \"q\""))
    }

    @Test fun tagsAreStableHexDigestsThatHideTheId() {
        val tag = NativeNotificationText.tagFor("guest adguard 101")
        assertEquals(32, tag.length)
        assertTrue(tag.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(tag, NativeNotificationText.tagFor("guest adguard 101"))
        assertNotEquals(tag, NativeNotificationText.tagFor("guest adguard 102"))
        assertFalse(tag.contains("adguard"))
    }

    @Test fun activationBookIsBounded() {
        val book = ActivationBook(capacity = 3)
        val handles = (1..5).map { book.register(note(id = "n$it")) }
        assertEquals(3, book.size())
        assertNull(book.lookup(handles[0]))
        assertEquals("n5", book.lookup(handles[4])?.notificationId)
    }

    // ---- queue ----

    private class FakeBackend : NativeNotificationBackend {
        override val platform = "Fake"
        var outcome: (PreparedNotification) -> NativeDeliveryOutcome = { NativeDeliveryOutcome.Sent() }
        val delivered = CopyOnWriteArrayList<PreparedNotification>()
        val withdrawn = CopyOnWriteArrayList<String>()
        var callback: (Long) -> Unit = {}
        var closed = false
        override fun availability() = NativeAvailability.Ready("ok")
        override fun deliver(notification: PreparedNotification): NativeDeliveryOutcome {
            delivered += notification
            return outcome(notification)
        }
        override fun withdraw(tag: String) { withdrawn += tag }
        override fun onActivated(callback: (Long) -> Unit) { this.callback = callback }
        override fun close() { closed = true }
    }

    @Test fun sendCompletesWithTheBackendOutcomeAndCleanText() {
        val backend = FakeBackend()
        QueuedNativeNotifier(backend, NativeNotifierConfig()).use { notifier ->
            val sent = notifier.send(NativeNotification("a", "Hi\u0000 there", "b\u0007ody")).get(5, TimeUnit.SECONDS)
            assertIs<NativeDeliveryOutcome.Sent>(sent)
            val p = backend.delivered.single()
            assertEquals("Hi there", p.title)
            assertEquals("body", p.body)
            assertEquals(NativeNotificationText.tagFor("a"), p.tag)
            assertIs<NativeAvailability.Ready>(notifier.prepare().get(5, TimeUnit.SECONDS))
        }
        assertTrue(backend.closed)
    }

    @Test fun backendExceptionsBecomeFailed() {
        val backend = FakeBackend().apply { outcome = { error("native crash") } }
        QueuedNativeNotifier(backend, NativeNotifierConfig()).use { notifier ->
            val result = notifier.send(note()).get(5, TimeUnit.SECONDS)
            assertEquals(NativeDeliveryOutcome.Failed("native crash"), result)
        }
    }

    @Test fun fullQueueFailsAtOnceAndStaleJobsAreDroppedNotShownLater() {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val backend = FakeBackend().apply {
            outcome = { started.countDown(); release.await(5, TimeUnit.SECONDS); NativeDeliveryOutcome.Sent() }
        }
        QueuedNativeNotifier(backend, NativeNotifierConfig(queueCapacity = 2, timeoutMillis = 300)).use { notifier ->
            val first = notifier.send(note(id = "1"))
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val queued = listOf(notifier.send(note(id = "2")), notifier.send(note(id = "3")))
            val overflow = notifier.send(note(id = "4")).get(1, TimeUnit.SECONDS)
            assertIs<NativeDeliveryOutcome.Failed>(overflow)
            assertTrue(overflow.detail.contains("waiting"))
            // The first job hangs past its deadline: its caller hears "did not answer" without waiting for the backend.
            assertIs<NativeDeliveryOutcome.Failed>(first.get(2, TimeUnit.SECONDS))
            queued.forEach { assertIs<NativeDeliveryOutcome.Failed>(it.get(2, TimeUnit.SECONDS)) }
            release.countDown()
            Thread.sleep(200)
            assertEquals(listOf("1"), backend.delivered.map { it.id }, "expired jobs must not reach the platform")
        }
    }

    @Test fun closedNotifierAnswersFailedWithoutTouchingTheBackend() {
        val backend = FakeBackend()
        val notifier = QueuedNativeNotifier(backend, NativeNotifierConfig())
        notifier.close()
        assertIs<NativeDeliveryOutcome.Failed>(notifier.send(note()).get(1, TimeUnit.SECONDS))
        assertTrue(backend.delivered.isEmpty())
    }

    @Test fun activationMapsTheHandleBackToIdAndTokenAndIgnoresUnknownHandles() {
        val backend = FakeBackend()
        val seen = CopyOnWriteArrayList<NativeActivation>()
        QueuedNativeNotifier(backend, NativeNotifierConfig()).use { notifier ->
            notifier.setActivationListener { seen += it }
            notifier.send(note(id = "incident:7", token = "evt_7")).get(5, TimeUnit.SECONDS)
            notifier.send(note(id = "plain", token = null)).get(5, TimeUnit.SECONDS)
            backend.callback(backend.delivered[0].handle)
            backend.callback(backend.delivered[1].handle)
            backend.callback(9_999)
            notifier.withdraw("incident:7")
            Thread.sleep(100)
            assertEquals(listOf(NativeActivation("incident:7", "evt_7"), NativeActivation("plain", null)), seen.toList())
            assertEquals(listOf(NativeNotificationText.tagFor("incident:7")), backend.withdrawn.toList())
        }
    }

    // ---- Windows helper protocol ----

    /** A scripted helper on pipes: answers each request with [reply] and records what it was sent. */
    private class FakeHelper(val reply: (JsonObject) -> JsonObject?, val sayHello: Boolean = true) {
        val requests = CopyOnWriteArrayList<JsonObject>()
        private val toApp = PipedOutputStream()
        private val appIn = PipedInputStream(toApp, 1 shl 16)
        private val fromApp = PipedOutputStream()
        private val helperIn = PipedInputStream(fromApp, 1 shl 16)
        val link = NotifyHelperLink(appIn, fromApp, kill = { runCatching { toApp.close() } })

        init {
            Thread {
                runCatching {
                    // A helper that exits at once closes its stdout before saying hello.
                    if (sayHello) emit(buildJsonObject { put("t", JsonPrimitive("hello")); put("v", JsonPrimitive("0.1.0")) }) else toApp.close()
                    helperIn.bufferedReader().forEachLine { line ->
                        val req = Json.parseToJsonElement(line).jsonObject
                        requests += req
                        reply(req)?.let { body ->
                            emit(JsonObject(body + mapOf("t" to JsonPrimitive("result"), "seq" to req["seq"]!!)))
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
        }

        fun emit(obj: JsonObject) = synchronized(toApp) { toApp.write((obj.toString() + "\n").toByteArray()); toApp.flush() }
    }

    private fun obj(vararg pairs: Pair<String, String>) = JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) })

    private val paths = WindowsPathsResult.Found(WindowsNotifyPaths(File("C:/x/ringmaster-notify.exe"), File("C:/x/ringmaster.exe")))

    private fun prepared(handle: Long = 42, id: String = "guest adguard", title: String = "T", body: String = "B") =
        PreparedNotification(handle, id, NativeNotificationText.tagFor(id), title, body, NativeSeverity.Warning)

    @Test fun windowsReportsUnsupportedWithoutHelperOrInstall() {
        val backend = WindowsToastBackend(NativeNotifierConfig(), locate = { WindowsPathsResult.Missing("no helper") }, start = { _, _ -> error("not started") })
        assertEquals(NativeDeliveryOutcome.Unsupported("no helper"), backend.deliver(prepared()))
        assertEquals(NativeAvailability.Unsupported("no helper"), backend.availability())
    }

    @Test fun windowsSendsOnlyTagHandleAndTextAndMapsResults() {
        var showOutcome = "sent"
        val helper = FakeHelper({ req ->
            when (req["op"]!!.jsonPrimitive.content) {
                "identity" -> obj("identity" to "present")
                "status" -> obj("setting" to "enabled")
                "show" -> if (showOutcome == "blocked") obj("outcome" to "blocked", "detail" to "disabled_app") else obj("outcome" to showOutcome, "detail" to "0x80070490")
                else -> obj("outcome" to "withdrawn")
            }
        })
        val appIds = CopyOnWriteArrayList<String>()
        val backend = WindowsToastBackend(NativeNotifierConfig(), locate = { paths }, start = { _, id -> appIds += id; helper.link })
        assertIs<NativeAvailability.Ready>(backend.availability())
        assertIs<NativeDeliveryOutcome.Sent>(backend.deliver(prepared()))
        showOutcome = "blocked"
        assertEquals(NativeDeliveryOutcome.Blocked("Notifications are turned off for RingMASTER in Windows Settings."), backend.deliver(prepared()))
        showOutcome = "unsupported"
        val unsupported = backend.deliver(prepared())
        assertIs<NativeDeliveryOutcome.Unsupported>(unsupported)
        assertTrue(unsupported.detail.contains("0x80070490"))
        showOutcome = "failed"
        assertIs<NativeDeliveryOutcome.Failed>(backend.deliver(prepared()))
        backend.withdraw(NativeNotificationText.tagFor("guest adguard"))

        assertEquals(listOf("RingMASTER.Desktop"), appIds.toList())
        val shows = helper.requests.filter { it["op"]!!.jsonPrimitive.content == "show" }
        assertEquals(setOf("op", "seq", "handle", "tag", "title", "body"), shows.first().keys)
        assertEquals("n42", shows.first()["handle"]!!.jsonPrimitive.content)
        assertFalse(helper.requests.joinToString().contains("adguard"), "the payload id must not reach the helper")
        // Identity is checked once while it holds, and again after Windows said it does not know the ID.
        assertEquals(2, helper.requests.count { it["op"]!!.jsonPrimitive.content == "identity" })
        assertEquals("true", helper.requests.first { it["op"]!!.jsonPrimitive.content == "identity" }["write"].toString())
        backend.close()
    }

    @Test fun windowsIdentityProblemsAreUnsupportedAndRegistrationCanBeOff() {
        for ((state, expect) in listOf("no_shortcut" to "was not found", "conflict" to "another app ID", "missing" to "adding one is turned off")) {
            val helper = FakeHelper({ obj("identity" to state) })
            val backend = WindowsToastBackend(NativeNotifierConfig(registerWindowsShortcutIdentity = false), locate = { paths }, start = { _, _ -> helper.link })
            val outcome = backend.deliver(prepared())
            assertIs<NativeDeliveryOutcome.Unsupported>(outcome, state)
            assertTrue(outcome.detail.contains(expect), outcome.detail)
            assertEquals("false", helper.requests.first()["write"].toString())
            assertTrue(helper.requests.none { it["op"]!!.jsonPrimitive.content == "show" })
            backend.close()
        }
    }

    @Test fun windowsActivationEventsMapStrictHandlesOnly() {
        val helper = FakeHelper({ obj("identity" to "present") })
        val backend = WindowsToastBackend(NativeNotifierConfig(), locate = { paths }, start = { _, _ -> helper.link })
        val got = CopyOnWriteArrayList<Long>()
        backend.onActivated { got += it }
        backend.availability()
        helper.emit(obj("t" to "activated", "handle" to "n7"))
        helper.emit(obj("t" to "activated", "handle" to "n7; rm -rf"))
        helper.emit(obj("t" to "activated", "handle" to "x9"))
        helper.emit(obj("t" to "dismissed", "handle" to "n8"))
        Thread.sleep(200)
        assertEquals(listOf(7L), got.toList())
        backend.close()
    }

    @Test fun windowsHelperThatNeverStartsIsBudgeted() {
        val starts = AtomicInteger()
        var now = 0L
        val backend = WindowsToastBackend(NativeNotifierConfig(), locate = { paths }, start = { _, _ -> starts.incrementAndGet(); FakeHelper({ null }, sayHello = false).link }, nowMillis = { now })
        repeat(3) { assertEquals("The notification helper did not start.", backend.deliver(prepared()).detail) }
        assertEquals("The notification helper keeps stopping.", backend.deliver(prepared()).detail)
        assertEquals(3, starts.get())
        now += 11 * 60_000L
        backend.deliver(prepared())
        assertEquals(4, starts.get())
    }

    @Test fun helperLinesAreBounded() {
        val data = "{\"t\":\"a\"}\n" + "x".repeat(NotifyHelperLink.MAX_LINE + 10) + "\n{\"t\":\"b\"}\r\n"
        val input = ByteArrayInputStream(data.toByteArray())
        assertEquals("{\"t\":\"a\"}", NotifyHelperLink.readBoundedLine(input))
        assertEquals("", NotifyHelperLink.readBoundedLine(input))
        assertEquals("{\"t\":\"b\"}", NotifyHelperLink.readBoundedLine(input))
        assertNull(NotifyHelperLink.readBoundedLine(input))
    }

    @Test fun deadHelperRequestsReturnNullPromptly() {
        val helper = FakeHelper({ null })
        assertNotNull(helper.link.awaitHello(2_000))
        helper.link.close()
        val started = System.nanoTime()
        assertNull(helper.link.request("status", timeoutMillis = 2_000))
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(1))
    }

    // ---- Linux freedesktop ----

    private class FakeBus(var caps: List<String> = listOf("body", "actions")) : NotificationBus {
        class Sent(val replaces: Long, val icon: String, val summary: String, val body: String, val actions: List<String>, val urgency: Int, val entry: String?)
        val sent = CopyOnWriteArrayList<Sent>()
        val closed = CopyOnWriteArrayList<Long>()
        var nextId = 100L
        var failure: NotificationBusException? = null
        var listens = true
        var onAction: (Long, String) -> Unit = { _, _ -> }
        var onClosed: (Long, Int) -> Unit = { _, _ -> }
        override fun capabilities(): List<String> { failure?.let { throw it }; return caps }
        override fun serverName() = "Xfce Notify Daemon"
        override fun notify(appName: String, replacesId: Long, appIcon: String, summary: String, body: String, actions: List<String>, urgency: Int, desktopEntry: String?): Long {
            failure?.let { throw it }
            sent += Sent(replacesId, appIcon, summary, body, actions, urgency, desktopEntry)
            return nextId++
        }
        override fun closeNotification(id: Long) { closed += id }
        override fun listen(onAction: (Long, String) -> Unit, onClosed: (Long, Int) -> Unit): Boolean {
            this.onAction = onAction; this.onClosed = onClosed; return listens
        }
    }

    private fun linux(bus: NotificationBus?, config: NativeNotifierConfig = NativeNotifierConfig()) =
        FreedesktopBackend(config, bus, desktopEntry = "ringmaster-ringmaster", appIcon = "file:///opt/ringmaster/lib/ringmaster.png")

    @Test fun linuxHonoursBodyAndMarkupCapabilities() {
        val bus = FakeBus(listOf("actions", "body", "body-markup"))
        val backend = linux(bus)
        assertIs<NativeDeliveryOutcome.Sent>(backend.deliver(prepared(body = "<i>R&D</i>")))
        assertEquals("&lt;i&gt;R&amp;D&lt;/i&gt;", bus.sent.last().body)
        bus.caps = listOf("body")
        val plain = linux(bus)
        plain.deliver(prepared(body = "<i>R&D</i>"))
        assertEquals("<i>R&D</i>", bus.sent.last().body)
        assertEquals(emptyList(), bus.sent.last().actions, "no actions without the capability")
        bus.caps = emptyList()
        linux(bus).deliver(prepared(body = "hidden"))
        assertEquals("", bus.sent.last().body)
        assertEquals("ringmaster-ringmaster", bus.sent.last().entry)
        assertEquals("file:///opt/ringmaster/lib/ringmaster.png", bus.sent.last().icon)
    }

    @Test fun linuxUrgencyNeverCriticalUnlessChosen() {
        val off = NativeNotifierConfig()
        assertEquals(listOf(0, 1, 1), NativeSeverity.entries.map { FreedesktopBackend.urgencyFor(it, off) })
        val on = NativeNotifierConfig(linuxCriticalUrgency = true)
        assertEquals(2, FreedesktopBackend.urgencyFor(NativeSeverity.Critical, on))
    }

    @Test fun linuxReplacesSameIdActivatesOnDefaultAndNeverOnClose() {
        val bus = FakeBus()
        val backend = linux(bus)
        val got = CopyOnWriteArrayList<Long>()
        backend.onActivated { got += it }
        backend.deliver(prepared(handle = 1, id = "a"))
        backend.deliver(prepared(handle = 2, id = "a"))
        backend.deliver(prepared(handle = 3, id = "b"))
        assertEquals(listOf(0L, 100L, 0L), bus.sent.map { it.replaces })
        assertEquals(listOf("default", "Open"), bus.sent.first().actions)
        bus.onAction(101, "default")
        bus.onAction(101, "other")
        bus.onClosed(102, 2)
        bus.onAction(102, "default")
        assertEquals(listOf(2L), got.toList())
        backend.withdraw(NativeNotificationText.tagFor("a"))
        backend.withdraw(NativeNotificationText.tagFor("b"))
        assertEquals(listOf(101L), bus.closed.toList(), "a notification the daemon already closed is not closed again")
    }

    @Test fun linuxWithoutSignalsOffersNoActions() {
        val bus = FakeBus().apply { listens = false }
        linux(bus).deliver(prepared())
        assertEquals(emptyList(), bus.sent.single().actions)
    }

    @Test fun linuxMapsMissingServiceAndBusErrors() {
        assertIs<NativeDeliveryOutcome.Unsupported>(linux(null).deliver(prepared()))
        assertIs<NativeAvailability.Unsupported>(linux(null).availability())
        val bus = FakeBus().apply { failure = GioNotificationBus.classify("org.freedesktop.DBus.Error.ServiceUnknown", "") }
        assertIs<NativeDeliveryOutcome.Unsupported>(linux(bus).deliver(prepared()))
        bus.failure = GioNotificationBus.classify("org.freedesktop.DBus.Error.NoReply", "")
        assertIs<NativeDeliveryOutcome.Failed>(linux(bus).deliver(prepared()))
        bus.failure = GioNotificationBus.classify("", "Timeout was reached")
        assertEquals("The notification service did not answer in time.", (linux(bus).deliver(prepared()) as NativeDeliveryOutcome.Failed).detail)
        bus.failure = null
        assertEquals(NativeAvailability.Ready("Notifications go to Xfce Notify Daemon."), linux(bus).availability())
    }

    @Test fun desktopEntryDetectionFollowsXdgDirs() {
        val files = setOf("/example-home/.local/share/applications/ringmaster.desktop", "/usr/share/applications/ringmaster-ringmaster.desktop")
        val env = mapOf("HOME" to "/example-home")
        assertEquals("ringmaster", FreedesktopBackend.detectDesktopEntry(env::get) { it.invariantSeparatorsPath in files })
        assertEquals("ringmaster-ringmaster", FreedesktopBackend.detectDesktopEntry(env::get) { it.invariantSeparatorsPath == "/usr/share/applications/ringmaster-ringmaster.desktop" })
        assertNull(FreedesktopBackend.detectDesktopEntry(mapOf("XDG_DATA_DIRS" to "relative:dirs")::get) { true })
        assertEquals("", FreedesktopBackend.detectAppIcon(null))
    }

    @Test fun unsupportedPlatformAnswersUnsupported() {
        QueuedNativeNotifier(UnsupportedBackend("none"), NativeNotifierConfig()).use {
            assertEquals(NativeDeliveryOutcome.Unsupported("none"), it.send(note()).get(5, TimeUnit.SECONDS))
        }
    }
}
