@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import local.ringleader.core.DEMO_REMOTE_KEY
import local.ringleader.core.Guest
import local.ringleader.core.GuestHints
import local.ringleader.core.ServiceProbe
import local.ringleader.core.WorkspaceState

/**
 * The Pin a service form in a scene: suggestions, a click that fills the fields, Test and Find services against servers
 * this test starts on 127.0.0.1, Escape, showcase mode, and Edit address on a stored pin.
 */
class PinServiceFormTest {
    private class Host(val scene: ImageComposeScene) {
        var now = 0L
        suspend fun step(frames: Int = 4) = repeat(frames) { now += 16; scene.render(now * 1_000_000L).close(); yield() }
        fun nodes(merged: Boolean = true): List<SemanticsNode> {
            val out = ArrayList<SemanticsNode>()
            fun visit(node: SemanticsNode) { out.add(node); node.children.forEach { visit(it) } }
            scene.semanticsOwners.forEach { visit(if (merged) it.rootSemanticsNode else it.unmergedRootSemanticsNode) }
            return out
        }
        fun texts(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        fun has(text: String) = nodes().any { text in texts(it) }
        fun hasPart(part: String) = nodes().any { n -> texts(n).any { part in it } }
        fun node(text: String) = nodes().firstOrNull { text in texts(it) } ?: error("no node $text; have ${nodes().flatMap { texts(it) }}")
        fun tagged(tag: String) = nodes().filter { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
        fun field(tag: String): SemanticsNode {
            val root = nodes(merged = false).first { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
            fun find(n: SemanticsNode): SemanticsNode? = if (n.config.contains(SemanticsProperties.EditableText)) n else n.children.firstNotNullOfOrNull { find(it) }
            return find(root) ?: error("no editable under $tag")
        }
        fun value(tag: String) = field(tag).config[SemanticsProperties.EditableText].text
        fun set(tag: String, value: String) = check(field(tag).config[SemanticsActions.SetText].action?.invoke(AnnotatedString(value)) != false)
        suspend fun click(node: SemanticsNode) {
            val at: Offset = node.boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Move, at)
            scene.sendPointerEvent(PointerEventType.Press, at)
            scene.sendPointerEvent(PointerEventType.Release, at)
            step()
        }
        suspend fun click(text: String) = click(node(text))
        suspend fun escape() { scene.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyDown)); scene.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyUp)); step() }
        suspend fun waitFor(what: String, check: () -> Boolean) {
            repeat(400) { step(1); if (check()) return; delay(25) }
            error("timed out waiting for $what; have ${nodes().flatMap { texts(it) }}")
        }
    }

    private fun scene(context: CoroutineContext, content: @Composable () -> Unit) = ImageComposeScene(900, 1000, Density(1f), coroutineContext = context) {
        MaterialTheme(colorScheme = ringColors) {
            CompositionLocalProvider(LocalReduceMotion provides true, LocalModalStack provides ModalStack()) { content() }
        }
    }

    private fun withForm(content: @Composable () -> Unit, body: suspend Host.() -> Unit) = runSceneTest {
        val scene = scene(coroutineContext, content)
        try { Host(scene).apply { step(6) }.body() } finally { scene.close() }
    }

    /** A local web page titled [title], answering every request. */
    private fun webPage(title: String): ServerSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).also { server ->
        Thread {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                Thread {
                    client.use { c ->
                        c.soTimeout = 5000
                        val reader = c.getInputStream().bufferedReader()
                        while (true) { val line = runCatching { reader.readLine() }.getOrNull() ?: break; if (line.isEmpty()) break }
                        runCatching { c.getOutputStream().write("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n<title>$title</title>".toByteArray()); c.getOutputStream().flush() }
                    }
                }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    @Test fun theBestSuggestionFillsTheFormAndSaysWhereItCameFrom() = withForm({
        ServicePinForm(GuestHints("homeassistant", listOf("community-script", "smarthome")), listOf("192.0.2.10"), canProbe = false, onDismiss = {}, onSave = { _, _ -> null })
    }) {
        assertEquals("http://192.0.2.10:8123", value("service-address"))
        assertEquals("Home Assistant", value("service-name"))
        assertTrue(has("SUGGESTIONS"))
        assertTrue(hasPart("http://192.0.2.10:8123"))
        assertTrue(has("From the guest name. Check it opens before relying on it."))
        assertTrue(has("Find services and Test need the desktop app and a live connection."))
        assertTrue(!has("TEST") && !has("FIND SERVICES"), "no probing in browser mode or the demo")
    }

    @Test fun aBareHostSaysThePortIsAGuessAndOffersFindServices() = withForm({
        ServicePinForm(GuestHints("box"), listOf("192.0.2.10"), canProbe = true, onDismiss = {}, onSave = { _, _ -> null })
    }) {
        assertEquals("http://192.0.2.10", value("service-address"))
        assertTrue(has("The port is a guess: most services do not listen on port 80. Pick a suggestion or use Find services."))
        assertTrue(has("FIND SERVICES"))
        assertTrue(hasPart("sends one plain GET to each open port (User-Agent RingMASTER, no sign-in, no cookies)"))
    }

    @Test fun clickingASuggestionFillsNameAndAddressButNotATypedName() = withForm({
        ServicePinForm(GuestHints("dns1", listOf("community-script", "adblock")), listOf("192.0.2.10"), canProbe = false, onDismiss = {}, onSave = { _, _ -> null })
    }) {
        assertEquals("http://192.0.2.10", value("service-address"), "category tags alone do not fill the form")
        val rows = tagged("service-suggestion")
        assertEquals(2, rows.size)
        click(rows.first { "Pi-hole" in texts(it) })
        assertEquals("http://192.0.2.10/admin", value("service-address"))
        assertEquals("Pi-hole", value("service-name"))
        click(tagged("service-suggestion").first { "AdGuard Home" in texts(it) })
        assertEquals("http://192.0.2.10:3000", value("service-address"))
        assertEquals("AdGuard Home", value("service-name"), "a name the form filled follows the next pick")
        set("service-name", "Home DNS"); step()
        click(tagged("service-suggestion").first { "Pi-hole" in texts(it) })
        assertEquals("http://192.0.2.10/admin", value("service-address"))
        assertEquals("Home DNS", value("service-name"), "a typed name wins")
    }

    @Test fun testReportsWhatTheAddressAnswers() {
        val page = webPage("Grafana")
        val closed = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        try {
            withForm({
                ServicePinForm(GuestHints("box"), listOf("127.0.0.1"), canProbe = true, onDismiss = {}, onSave = { _, _ -> null },
                    probe = ServiceProbe(connectTimeoutMs = 500, readTimeoutMs = 1500))
            }) {
                set("service-address", "http://127.0.0.1:${page.localPort}"); step()
                click("TEST")
                waitFor("the test result") { has("Answers: Grafana.") }
                set("service-address", "http://127.0.0.1:$closed"); step()
                assertTrue(tagged("service-test").isEmpty(), "a result belongs to the address it tested")
                click("TEST")
                waitFor("the closed port") { has("Port $closed is closed.") }
            }
        } finally { page.close() }
    }

    @Test fun findServicesListsWhatAnsweredFirst() {
        val page = webPage("Grafana")
        try {
            withForm({
                ServicePinForm(GuestHints("box"), listOf("127.0.0.1"), canProbe = true, onDismiss = {}, onSave = { _, _ -> null },
                    probe = ServiceProbe(connectTimeoutMs = 500, readTimeoutMs = 1500), findPorts = listOf(page.localPort))
            }) {
                click("FIND SERVICES")
                waitFor("find to finish") { has("1 web page answered.") }
                val row = tagged("service-suggestion").first()
                assertTrue("Grafana" in texts(row) && "http://127.0.0.1:${page.localPort}" in texts(row) && "ANSWERED" in texts(row), texts(row).toString())
                click(row)
                assertEquals("http://127.0.0.1:${page.localPort}", value("service-address"))
                assertEquals("Grafana", value("service-name"))
            }
        } finally { page.close() }
    }

    @Test fun escapeCancelsAndAnOutsideClickKeepsTheForm() {
        var dismissed = 0
        withForm({ ServicePinForm(GuestHints("box"), listOf("192.0.2.10"), canProbe = false, onDismiss = { dismissed++ }, onSave = { _, _ -> null }) }) {
            scene.sendPointerEvent(PointerEventType.Press, Offset(3f, 3f)); scene.sendPointerEvent(PointerEventType.Release, Offset(3f, 3f)); step()
            assertEquals(0, dismissed)
            escape()
            assertEquals(1, dismissed)
        }
    }

    @Test fun showcaseConcealsEveryAddress() {
        Appearance.preview(false, showcase = false)
        try {
            withForm({ ServicePinForm(GuestHints("grafana"), listOf("192.0.2.10", "198.51.100.4"), canProbe = true, onDismiss = {}, onSave = { _, _ -> null }) }) {
                step(4)
                val all = nodes(merged = false)
                val showing = all.filter { n ->
                    val words = n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                        listOfNotNull(n.config.getOrNull(SemanticsProperties.EditableText)?.text)
                    words.any { "192.0.2.10" in it || "198.51.100.4" in it }
                }
                assertTrue(showing.size >= 4, "the field, a suggestion and two reported addresses: ${showing.size}")
                Appearance.preview(false, showcase = true); step(4)
                val exposed = nodes(merged = true).flatMap { n ->
                    n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                        listOfNotNull(n.config.getOrNull(SemanticsProperties.EditableText)?.text) +
                        n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                }
                assertFalse(exposed.any { "192.0.2.10" in it || "198.51.100.4" in it }, exposed.toString())
                assertTrue("Hidden" in exposed)
            }
        } finally { Appearance.preview(false, showcase = false) }
    }

    @Test fun editAddressSavesTheCorrectedAddress() {
        val file = Files.createTempDirectory("ringmaster-pins").resolve("pins.json")
        val store = PinStore(file)
        val guest = Guest(DEMO_REMOTE_KEY, 121, "adguard", "demo-node-a", "Container", "running")
        assertNull(store.addService("profile", guest, "AdGuard", "http://192.0.2.8"))
        val pin = store.list("profile").single()
        var closed = 0
        withForm({ ServicePinEditor(WorkspaceState(), store, "profile", pin) { closed++ } }) {
            assertTrue(has("Edit pinned service"))
            assertEquals("AdGuard", value("service-name"))
            assertEquals("http://192.0.2.8", value("service-address"))
            assertTrue(has("The port is a guess: most services do not listen on port 80. Pick a suggestion or use Find services."))
            // The pin's host stands in for the reported addresses, so the catalogue guess for the guest's name is offered.
            click(tagged("service-suggestion").first { "AdGuard Home" in texts(it) })
            assertEquals("http://192.0.2.8:3000", value("service-address"))
            click("SAVE")
            assertEquals(1, closed)
        }
        val saved = PinStore(file).list("profile").single()
        assertEquals(pin.id, saved.id)
        assertEquals("http://192.0.2.8:3000", saved.serviceUrl)
        assertEquals("AdGuard", saved.serviceName, "a name the user had stays")
        assertNotNull(saved.guestName)
    }

    @Test fun theRailMenuOffersEditAddressForAServicePin() {
        val file = Files.createTempDirectory("ringmaster-pins").resolve("pins.json")
        val store = PinStore(file)
        val guest = Guest(DEMO_REMOTE_KEY, 121, "grafana", "demo-node-a", "Container", "running")
        assertNull(store.addService("profile", guest, "Grafana", "http://192.0.2.8"))
        store.toggleGuest("profile", guest)
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined + kotlinx.coroutines.SupervisorJob())
        val workspace = local.ringleader.core.Workspace(scope)
        try {
            suspend fun Host.menuOn(label: String) {
                val at = node(label).boundsInRoot.center
                scene.sendPointerEvent(PointerEventType.Move, at)
                scene.sendPointerEvent(PointerEventType.Press, at, buttons = androidx.compose.ui.input.pointer.PointerButtons(isSecondaryPressed = true),
                    button = androidx.compose.ui.input.pointer.PointerButton.Secondary)
                scene.sendPointerEvent(PointerEventType.Release, at, button = androidx.compose.ui.input.pointer.PointerButton.Secondary)
                step(6)
            }
            val rail: @Composable () -> Unit = { NavigationRail(WorkspaceState(), "Fleet", "Test", workspace, initialExpanded = true, pins = store, connectionKey = "profile") {} }
            withForm(rail) {
                menuOn("grafana")
                assertTrue(has("Unpin") && !has("Edit address…"), "a guest pin has no address to edit")
            }
            withForm(rail) {
                menuOn("grafana · Grafana")
                click("Edit address…")
                step(4)
                assertTrue(has("Edit pinned service"))
                assertEquals("http://192.0.2.8", value("service-address"))
            }
        } finally { workspace.close(); scope.cancel() }
    }

    @Test fun notesThatArriveLaterReplaceAnUntouchedGuessButNotATypedAddress() = runSceneTest {
        var hints by androidx.compose.runtime.mutableStateOf(GuestHints("box"))
        val scene = scene(coroutineContext) { ServicePinForm(hints, listOf("192.0.2.10"), canProbe = false, onDismiss = {}, onSave = { _, _ -> null }) }
        try {
            val host = Host(scene).apply { step(6) }
            assertEquals("http://192.0.2.10", host.value("service-address"))
            hints = GuestHints("box", description = "<a href='https://community-scripts.org/scripts/uptimekuma'>x</a>"); host.step(6)
            assertEquals("http://192.0.2.10:3001", host.value("service-address"))
            assertEquals("Uptime Kuma", host.value("service-name"))
            host.set("service-address", "http://192.0.2.10:9999"); host.step()
            hints = GuestHints("grafana"); host.step(6)
            assertEquals("http://192.0.2.10:9999", host.value("service-address"), "the typed address always wins")
        } finally { scene.close() }
    }
}
