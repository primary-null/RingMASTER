@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "DEPRECATION")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.coroutines.CoroutineContext
import kotlin.test.*
import kotlinx.coroutines.*
import local.ringleader.core.*

/**
 * The Scan page's states: what it says when a scan was cancelled, stopped or found nothing, where the results sit against the grid,
 * which keys start a scan, the "Why isn't it here?" check, and what Showcase and high contrast do to the page. Documentation and
 * invented addresses only; no network is touched (the session's environment is a fake).
 */
class ScanPageStatesTest {
    private class Bench(context: CoroutineContext, width: Int, height: Int, val env: FakeScanEnvironment = FakeScanEnvironment(CARDS), choices: ScanChoices = ScanChoices.inMemory(), fontScale: Float = 1f) : AutoCloseable {
        private val job = SupervisorJob()
        val session = quietSession(CoroutineScope(Dispatchers.Unconfined + job), env, choices)
        val used = mutableListOf<ScanHit>()
        val scene = ImageComposeScene(width, height, Density(1f, fontScale), coroutineContext = context) {
            MaterialTheme(colorScheme = ringColors) { CompositionLocalProvider(LocalReduceMotion provides true) {
                Surface(Modifier.fillMaxSize(), color = Tech.Canvas, contentColor = Tech.Ink) { Box(Modifier.fillMaxSize().padding(16.dp)) { ScanScreen(session, emptyList()) { used += it } } }
            } }
        }
        val ui = CreateScene(scene)
        suspend fun settle() { ui.frames(); delay(20); ui.frames() }
        /** The cards are read and the plan is on the page. */
        suspend fun ready() { session.refresh(); waitFor("the read") { session.cards != null && !session.reading }; settle() }
        fun texts(): List<String> = ui.nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text } }
        fun has(text: String) = ui.nodes().any { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text.equals(text, ignoreCase = true) } == true }
        fun node(text: String): SemanticsNode = ui.nodes().first { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text.equals(text, ignoreCase = true) } == true }
        fun fields(): List<SemanticsNode> = ui.nodes().filter { it.config.getOrNull(SemanticsActions.SetText) != null }
        suspend fun focusField(index: Int) {
            val at = fields()[index].boundsInWindow.center
            scene.sendPointerEvent(PointerEventType.Move, at); scene.sendPointerEvent(PointerEventType.Press, at); scene.sendPointerEvent(PointerEventType.Release, at); settle()
        }
        suspend fun typeInto(index: Int, value: String) { fields()[index].config[SemanticsActions.SetText].action!!(androidx.compose.ui.text.AnnotatedString(value)); settle() }
        override fun close() { scene.close(); job.cancel() }
    }

    private val hit = ScanHit("192.0.2.21", 8006, true, ScanKind.PVE, "pve-east - Proxmox", FINGERPRINT_A, "CN=pve-east.example.test,OU=PVE Cluster Node", false, "CN=Proxmox,OU=PVE Cluster Manager CA", 1_900_000_000_000L)
    private fun plan(vararg cidr: String) = NetworkPlan(cidr.map { c -> val base = c.substringBefore(".0/"); ScanNetwork(ScanInterface("Ethernet", "$base.8", 24), c, null, (1..254).map { "$base.$it" }, onLink = true) })

    // ---- what a scan that ended otherwise says --------------------------------------------------------------------------------

    @Test fun anErrorIsSaidWithItsCauseAndNeverWithAClaimOfResults() = runSceneTest {
        val b = Bench(coroutineContext, 1280, 900)
        try {
            b.session.show(null, message = scanFailureMessage(java.net.SocketException("Network is down")))
            b.settle()
            assertTrue(b.has("The scan stopped: the network stopped answering while it ran (SocketException)."), b.texts().toString())
            assertTrue(b.has("Start scan tries again. If it keeps stopping, check that this computer is on a network; Under the Hood has the details."))
            assertTrue(b.texts().none { "Results remain available" in it || "What was found" in it }, "nothing was found, and nothing is claimed: " + b.texts())
            // With something found before it stopped, the page says it stays; the old text is never there.
            b.session.show(NetworkProgress(plan("192.0.2.0/24"), 120, hits = listOf(hit)), message = scanFailureMessage(java.net.SocketException("x")))
            b.settle()
            assertTrue(b.has("What was found before it stopped stays below.")); assertTrue(b.texts().none { "Results remain available" in it })
            // The old wording of a failure (a class name) is still read as a failure.
            b.session.show(null, message = "Scan failed: SocketException"); b.settle()
            assertTrue(b.has("Scan failed: SocketException") && b.has("Start scan tries again. If it keeps stopping, check that this computer is on a network; Under the Hood has the details."))
        } finally { b.close() }
    }

    @Test fun theCauseOfAFailureIsASentenceAndTheKindOfAMessageIsNamed() {
        assertEquals("The scan stopped: this computer would not let RingMASTER open the connections a scan needs.", scanFailureMessage(SecurityException("denied")))
        assertEquals("The scan stopped: the network stopped answering while it ran (SocketException).", scanFailureMessage(java.net.SocketException("down")))
        assertEquals("The scan stopped: the network stopped answering while it ran (IOException).", scanFailureMessage(java.io.IOException("down")))
        assertEquals("The scan stopped: A scan sweeps at most 4096 addresses.", scanFailureMessage(IllegalArgumentException("A scan sweeps at most 4096 addresses.")))
        assertEquals("The scan stopped: a scan was asked for something it does not do.", scanFailureMessage(IllegalArgumentException("")))
        assertEquals("The scan stopped: a scan was asked for something it does not do.", scanFailureMessage(IllegalArgumentException("x".repeat(500))))
        assertEquals("The scan stopped: an unexpected error ended it (IllegalStateException).", scanFailureMessage(IllegalStateException("secret detail")))
        assertEquals(ScanNotice.None, scanNoticeOf("")); assertEquals(ScanNotice.Cancelled, scanNoticeOf("Cancelled"))
        assertEquals(ScanNotice.NothingTicked, scanNoticeOf("Nothing is ticked. Tick a network below, or type one."))
        for (m in listOf(scanFailureMessage(IllegalStateException("x")), "Scan failed: SocketException", "anything else")) assertEquals(ScanNotice.Failed, scanNoticeOf(m), m)
    }

    @Test fun aCancelledScanSaysHowFarItGotAndWhatToDoNext() = runSceneTest {
        val b = Bench(coroutineContext, 1280, 900)
        try {
            b.session.show(NetworkProgress(plan("192.0.2.0/24"), 120, hits = listOf(hit)), message = "Cancelled", seconds = 17); b.settle()
            assertTrue(b.has("Cancelled after 17s: 120 of 254 addresses were checked. What was found stays below."), b.texts().toString())
            assertTrue(b.has("Start scan runs it again.")); assertTrue(b.texts().none { "Results remain available" in it })
            b.session.show(NetworkProgress(plan("192.0.2.0/24"), 0), message = "Cancelled", seconds = 1); b.settle()
            assertTrue(b.has("Cancelled before anything was checked."))
            b.session.show(NetworkProgress(plan("192.0.2.0/24"), 30), message = "Cancelled", seconds = 3); b.settle()
            assertTrue(b.has("Cancelled after 3s: 30 of 254 addresses were checked."), "no results, none claimed: " + b.texts())
        } finally { b.close() }
    }

    @Test fun startingWithNothingTickedIsRefusedInWords() = runSceneTest {
        val b = Bench(coroutineContext, 1280, 900, choices = ScanChoices.inMemory().apply { CARDS.forEach { c -> put(ScanPlanner.plan(listOf(c)).rows.first().id, false) } })
        try {
            b.ready()
            assertTrue(b.has("Nothing is ticked, so a scan has nothing to sweep. Tick a network below, or type one."))
            assertNotNull(b.node("Start scan").config.getOrNull(SemanticsProperties.Disabled))
            b.session.start(); b.settle()
            assertEquals("Nothing is ticked. Tick a network below, or type one.", b.session.message); assertEquals(ScanNotice.NothingTicked, b.session.notice); assertEquals(0, b.env.scansStarted)
            assertEquals(1, b.texts().count { it.startsWith("Nothing is ticked") }, "the plan says it, and the refusal does not say it a second time: " + b.texts())
        } finally { b.close() }
    }

    @Test fun aComputerWithNoNetworkIsToldSoAndNeverToTickOne() = runSceneTest {
        val b = Bench(coroutineContext, 1280, 900, FakeScanEnvironment(emptyList()))
        try {
            b.ready()
            assertTrue(b.has(NO_NETWORK_TO_SCAN), b.texts().toString())
            assertTrue(b.texts().none { it.startsWith("Nothing is ticked") }, "there is nothing to tick: " + b.texts())
            assertNotNull(b.node("Start scan").config.getOrNull(SemanticsProperties.Disabled))
            b.session.start(); b.settle()
            assertEquals(NO_NETWORK_TO_SCAN, b.session.message); assertEquals(ScanNotice.NothingTicked, b.session.notice); assertEquals(0, b.env.scansStarted)
            assertEquals(1, b.texts().count { it == NO_NETWORK_TO_SCAN }, "the plan says it, and the refusal does not say it a second time: " + b.texts())
            // A typed network is something to sweep: the refusal is no longer true, so it goes, and Start works again.
            b.session.extraSubnet = "10.20.31.0/24"; b.settle()
            assertEquals(ScanNotice.None, b.session.notice); assertTrue(b.session.canStart)
            assertTrue(b.texts().none { it == NO_NETWORK_TO_SCAN }, "a refusal that is no longer true is not shown: " + b.texts())
        } finally { b.close() }
    }

    @Test fun theRefusalOfANothingTickedStartGoesWhenSomethingIsTicked() = runSceneTest {
        val b = Bench(coroutineContext, 1280, 900, choices = ScanChoices.inMemory().apply { CARDS.forEach { c -> put(ScanPlanner.plan(listOf(c)).rows.first().id, false) } })
        try {
            b.ready(); b.session.start(); b.settle()
            assertEquals("Nothing is ticked. Tick a network below, or type one.", b.session.message); assertEquals(ScanNotice.NothingTicked, b.session.notice)
            b.session.setChoice(b.session.plan.rows.first { it.choosable }.id, true); b.settle()
            assertEquals(ScanNotice.None, b.session.notice, "ticking something makes the refusal untrue")
            assertTrue(b.texts().none { it.startsWith("Nothing is ticked") }, b.texts().toString())
            assertEquals("Nothing is ticked. Tick a network below, or type one.", b.session.message, "the session keeps what it said; only the page stops showing it")
        } finally { b.close() }
    }

    // ---- empty --------------------------------------------------------------------------------------------------------------

    @Test fun aScanThatFoundNothingSaysWhatWasCheckedAndWhatToTry() = runSceneTest {
        val b = Bench(coroutineContext, 1280, 1000)
        try {
            val p = NetworkProgress(plan("192.0.2.0/24"), 254, hits = emptyList(), finished = true, alive = 12,
                findings = (1..12).map { HostFinding("192.0.2.$it", "192.0.2.0/24", FoundBy.Neighbour, 900L) })
            b.session.show(p, seconds = 31); b.settle()
            assertTrue(b.has("Nothing to show") && b.has("254 addresses on 1 network were checked in 31s. 12 devices answered the network, and none offers a service RingMASTER knows."), b.texts().toString())
            for (step in listOf("What to check", "Is this computer on the same network as the servers?", "Is the server on another network?", "Does it listen on another port?"))
                assertTrue(b.texts().any { step in it }, step)
            assertTrue(b.has("Why isn't it here?") && b.has("Change networks"))
            assertTrue(b.texts().none { "No services found" in it || "Only answering services" in it }, "the old empty panel is gone")
            assertFalse(b.has("Sort: Name"), "nothing to sort")
            // No device at all, and one device.
            b.session.show(NetworkProgress(plan("192.0.2.0/24"), 254, finished = true), seconds = 5); b.settle()
            assertTrue(b.has("254 addresses on 1 network were checked in 5s. No device answered."))
            b.session.show(NetworkProgress(plan("192.0.2.0/24", "198.51.100.0/24"), 508, finished = true, alive = 1), seconds = 9); b.settle()
            assertTrue(b.has("508 addresses on 2 networks were checked in 9s. 1 device answered the network, and none offers a service RingMASTER knows."), b.texts().toString())
            // While it runs the page only says nothing has answered yet; a stopped scan says it was stopped.
            b.session.show(NetworkProgress(plan("192.0.2.0/24"), 40), running = true); b.settle()
            assertTrue(b.has("Waiting for devices") && b.has("No device has answered yet."))
            b.session.show(NetworkProgress(plan("192.0.2.0/24"), 40), message = "Cancelled", seconds = 2); b.settle()
            assertTrue(b.has("The scan was stopped before a service answered"))
        } finally { b.close() }
    }

    @Test fun theEmptyPanelsButtonsOpenTheCheckAndThePlan() = runSceneTest {
        val b = Bench(coroutineContext, 1280, 1000)
        try {
            b.session.show(NetworkProgress(plan("192.0.2.0/24"), 254, finished = true), seconds = 5); b.settle()
            assertFalse(b.has("Address"), "the check is closed")
            b.ui.click("Why isn't it here?"); b.settle()
            assertTrue(b.has("Address") && b.has("Check"), b.texts().toString())
            b.ui.click("Change networks"); b.settle()
            assertTrue(b.has("Networks to scan") && b.has("Hide networks"), b.texts().toString())
        } finally { b.close() }
    }

    // ---- where the results sit ----------------------------------------------------------------------------------------------

    @Test fun withSeveralNetworksTheResultsComeBeforeTheGridAndWithOneTheGridStaysOnTop() = runSceneTest {
        val devices = listOf(hit, hit.copy(ip = "198.51.100.22", fingerprint = FINGERPRINT_B, subject = "CN=pve-west.example.test,OU=PVE Cluster Node"))
        val several = Bench(coroutineContext, 1280, 1400)
        try {
            several.session.show(NetworkProgress(plan("192.0.2.0/24", "198.51.100.0/24"), 508, hits = devices, finished = true), seconds = 9); several.settle()
            val card = several.node("Use").boundsInWindow; val grid = several.node("Discovery grid").boundsInWindow
            assertTrue(card.bottom < grid.top, "the cards ($card) are above the grid ($grid)")
        } finally { several.close() }
        val one = Bench(coroutineContext, 1280, 1400)
        try {
            one.session.show(NetworkProgress(plan("192.0.2.0/24"), 254, hits = devices.take(1), finished = true), seconds = 9); one.settle()
            assertTrue(one.node("Discovery grid").boundsInWindow.bottom < one.node("Use").boundsInWindow.top, "one network: the grid is on top as before")
        } finally { one.close() }
    }

    @Test fun eachNetworkChipShowsItsOwnProgressAndWhatItWasCutFrom() = runSceneTest {
        val b = Bench(coroutineContext, 1280, 1000)
        try {
            val cut = ScanNetwork(ScanInterface("Wi-Fi", "10.20.5.7", 22), "10.20.5.0/24", null, (1..254).map { "10.20.5.$it" }, realCidr = "10.20.4.0/22", onLink = true)
            val view = NetworkPlan(plan("192.0.2.0/24").networks + cut + ScanNetwork(ScanInterface("docker0", "172.17.0.1", 16), null, "Virtual-switch adapter"))
            b.session.show(NetworkProgress(view, 289, hits = listOf(hit), tallies = listOf(NetworkTally("192.0.2.0/24", 254, 254, answered = 4, done = true), NetworkTally("10.20.5.0/24", 35, 254))), running = true, seconds = 3); b.settle()
            assertTrue(b.has("254 addresses · 4 answered") && b.has("35/254"), b.texts().toString())
            assertTrue(b.has("10.20.5.0/24 of 10.20.4.0/22"), "a network that was only part swept says what it was part of")
            assertTrue(b.has("virtual-switch adapter"), "a network that was not swept says why")
        } finally { b.close() }
    }

    // ---- keys ------------------------------------------------------------------------------------------------------------------

    @Test fun f5AndEnterStartAScanWhenThePageHasTheKeyboardAndOtherKeysDoNot() = runSceneTest {
        val b = Bench(coroutineContext, 1280, 1000)
        try {
            b.ready()
            // The page took the keyboard when it opened: no click is needed.
            b.ui.key(Key.F5); waitFor("F5 to start a scan") { b.env.scansStarted == 1 }
            waitFor("the scan to end") { !b.session.running }; b.settle()
            b.ui.key(Key.Enter); waitFor("Enter to start a scan") { b.env.scansStarted == 2 }
            waitFor("the scan to end") { !b.session.running }; b.settle()
            // A chord is not a start, and neither is a key that means something else.
            val before = b.env.scansStarted
            b.ui.key(Key.F5, ctrl = true); b.ui.key(Key.F5, shift = true); b.ui.key(Key.Enter, alt = true); b.ui.key(Key.Spacebar); b.ui.key(Key.F4)
            delay(200); assertEquals(before, b.env.scansStarted)
        } finally { b.close() }
    }

    @Test fun enterInTheSubnetFieldStartsAScanAndInTheCheckFieldItChecks() = runSceneTest {
        val b = Bench(coroutineContext, 1280, 1000)
        try {
            b.ready()
            b.focusField(0); b.ui.key(Key.Enter)
            waitFor("Enter in the subnet field to start a scan") { b.env.scansStarted == 1 }
            waitFor("the scan to end") { !b.session.running }; b.settle()
            // The check's own field uses Enter to check, and never starts a scan.
            b.ui.click("Why isn't it here?"); b.settle()
            b.typeInto(1, "192.168.77.9"); b.focusField(1); b.ui.key(Key.Enter)
            assertTrue(b.has("None of this computer's networks, routes or known servers include it, so no scan asked it. If this computer can reach it (through a router or a VPN), type its network: 192.168.77.0/24."), b.texts().toString())
            delay(150); assertEquals(1, b.env.scansStarted, "Enter in the check field did not start a scan")
        } finally { b.close() }
    }

    @Test fun noKeyStartsAScanWhileOneRunsOrWhenStartIsOff() = runSceneTest {
        val running = CompletableDeferred<Unit>()
        val env = FakeScanEnvironment(CARDS, make = { NetworkScanner { _, _ -> running.complete(Unit); awaitCancellation() } })
        val b = Bench(coroutineContext, 1280, 1000, env)
        try {
            b.ready()
            b.ui.key(Key.F5); waitFor("the scan to run") { b.session.running && running.isCompleted }
            b.ui.key(Key.F5); b.ui.key(Key.Enter); delay(150)
            assertEquals(1, env.scansStarted, "a scan that runs is not started again")
            b.session.cancel(); waitFor("the scan to stop") { !b.session.running }; b.settle()
            assertTrue(b.texts().any { it.startsWith("Cancelled") }, b.texts().toString().take(300))
            // A typo in the subnet field turns Start off, and so the keys.
            b.typeInto(0, "10.20.30"); b.ui.key(Key.F5); delay(150)
            assertEquals(1, env.scansStarted)
        } finally { b.close() }
    }

    @Test fun theNetworkFieldGrowsWithTheTextSoItsExampleStillFits() = runSceneTest {
        val widths = mutableMapOf<Float, Float>()
        for (scale in listOf(1f, 1.5f, 3f)) {
            val b = Bench(coroutineContext, 1280, 900, fontScale = scale)
            try { b.ready(); widths[scale] = b.fields()[0].boundsInWindow.width } finally { b.close() }
        }
        assertTrue(widths.getValue(1.5f) / widths.getValue(1f) in 1.4f..1.6f, "150 % text: $widths")
        assertTrue(widths.getValue(3f) / widths.getValue(1f) in 1.9f..2.15f, "text larger than 200 % stops growing the field: $widths")
    }

    // ---- why isn't it here? ------------------------------------------------------------------------------------------------------

    private val withRoute = NetworkFactsRead(NetworkFacts(listOf(AdapterFact(7, "Ethernet", null, false, true)),
        listOf(RouteEntry(0, 0, "10.20.30.1", 7), parseCidr("10.60.0.0/24")!!.let { (n, p) -> RouteEntry(n, p, "10.20.30.254", 7) }), "test"), null)

    @Test fun theCheckNamesTheNetworkAndItsButtonsDoWhatTheyName() = runSceneTest {
        val b = Bench(coroutineContext, 1280, 1400, FakeScanEnvironment(listOf(ScanInterface("Ethernet", "10.20.30.7", 24, true, 7)), withRoute))
        try {
            b.ready(); b.ui.click("Why isn't it here?"); b.settle()
            // A route nobody ticked: the answer names it, and one button ticks it.
            b.typeInto(1, "10.60.0.9"); b.ui.click("Check")
            assertTrue(b.has("It is inside 10.60.0.0/24 (Route via 10.20.30.254 on Ethernet), which is not ticked: A route added by a VPN or by hand. Tick it to scan it. Tick it to include it in the next scan."), b.texts().toString())
            b.ui.click("Tick 10.60.0.0/24"); b.settle()
            assertEquals(true, b.session.choices.all()["10.60.0.0/24"])
            assertTrue(b.has("It is inside 10.60.0.0/24 (Route via 10.20.30.254 on Ethernet), which is ticked: the next scan asks it."), "the answer follows the plan: " + b.texts())
            // An address no network contains: the answer says so, and its button types the network.
            b.typeInto(1, "192.168.77.9"); b.ui.click("Check")
            assertTrue(b.has("Type 192.168.77.0/24")); b.ui.click("Type 192.168.77.0/24"); b.settle()
            assertEquals("192.168.77.0/24", b.session.extraSubnet)
            assertTrue(b.has("It is inside 192.168.77.0/24 (Typed network), which is ticked: the next scan asks it."), b.texts().toString())
            // What cannot be an address is answered in words.
            b.typeInto(1, "pve.example.test"); b.ui.click("Check")
            assertTrue(b.has("That is not an IPv4 address. A host name is not looked up here: type the address, for example 10.20.30.5."))
            assertEquals(0, b.env.scansStarted, "asking sends nothing")
        } finally { b.close() }
    }

    @Test fun afterAScanTheCheckSaysWhatThatScanDid() = runSceneTest {
        val b = Bench(coroutineContext, 1280, 1400)
        try {
            b.ready(); b.session.show(NetworkProgress(b.session.plan.toNetworkPlan(), b.session.plan.addressCount, hits = listOf(hit.copy(ip = "10.20.30.9")), finished = true), seconds = 6); b.settle()
            b.ui.click("Why isn't it here?"); b.settle()
            b.typeInto(1, "10.20.30.9"); b.ui.click("Check")
            assertTrue(b.has("It was found: Proxmox VE at 10.20.30.9:8006. Its card or row is on this page."), b.texts().toString())
            b.typeInto(1, "10.20.30.50"); b.ui.click("Check")
            assertTrue(b.texts().any { it.startsWith("The last scan swept 10.20.30.0/24") && "nothing answered at this address" in it }, b.texts().toString())
        } finally { b.close() }
    }

    // ---- showcase and high contrast --------------------------------------------------------------------------------------------------

    @Test fun showcaseHidesThePlanTheSubnetFieldAndTheCheck() = runSceneTest {
        val b = Bench(coroutineContext, 1280, 1400, FakeScanEnvironment(listOf(ScanInterface("ethernet_1 Intel(R) Ethernet Controller I226-V", "10.20.30.7", 24, true, 7)), withRoute))
        try {
            b.ready(); b.session.extraSubnet = "10.20.31.0/24"; b.ui.click("Why isn't it here?"); b.settle()
            b.typeInto(1, "10.60.0.9"); b.ui.click("Check")
            fun shown() = b.ui.nodes().flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() + listOfNotNull(n.config.getOrNull(SemanticsProperties.EditableText)?.text) }
            val secrets = listOf("10.20.30", "10.20.31", "10.60.0", "Ethernet", "Route via")
            assertTrue(secrets.all { secret -> shown().any { secret in it } }, "the page shows them before Showcase: " + shown())
            // Showcase on, live: the plan, the typed network, the check's field, its answer and its buttons are hidden; the words stay.
            Appearance.preview(false, showcase = true); b.settle()
            for (secret in secrets) assertTrue(shown().none { secret in it }, "$secret is readable in Showcase: " + shown().firstOrNull { secret in it })
            assertTrue(b.has("Networks to scan") && b.has("Check"), "the words of the page stay")
        } finally { Appearance.preview(false); b.close() }
    }

    @Test fun highContrastDrawsChipBordersInTheTextColour() {
        assertEquals(Tech.Ink, scanChipEdge(swept = true, highContrast = true)); assertEquals(Tech.Ink, scanChipEdge(swept = false, highContrast = true))
        assertEquals(Tech.Amber.copy(alpha = .5f), scanChipEdge(swept = true, highContrast = false)); assertEquals(Tech.Edge, scanChipEdge(swept = false, highContrast = false))
        Appearance.preview(ThemeMode.HighContrast)
        try { assertTrue(Appearance.palette.highContrast); assertEquals(Tech.Ink, scanChipEdge(true, Appearance.palette.highContrast)) } finally { Appearance.preview(false) }
    }

    // ---- nothing is cut off --------------------------------------------------------------------------------------------------------------

    @Test fun noStateClipsOrCollapsesAnythingAtTheCommonWidthsAndAtLargeText() = runSceneTest {
        val states: List<Pair<String, (ScanSession) -> Unit>> = listOf(
            "failed" to { s -> s.show(null, message = scanFailureMessage(java.net.SocketException("x"))) },
            "cancelled" to { s -> s.show(NetworkProgress(plan("192.0.2.0/24"), 120, hits = listOf(hit)), message = "Cancelled", seconds = 17) },
            "empty" to { s -> s.show(NetworkProgress(plan("192.0.2.0/24"), 254, finished = true, alive = 12), seconds = 31) },
            "several" to { s -> s.show(NetworkProgress(plan("192.0.2.0/24", "198.51.100.0/24", "203.0.113.0/24"), 762, hits = listOf(hit), finished = true), seconds = 9) },
        )
        for ((w, h, scale) in listOf(Triple(1280, 1400, 1f), Triple(960, 1800, 1f), Triple(1280, 2400, 1.5f))) for ((name, apply) in states) {
            val b = Bench(coroutineContext, w, h, fontScale = scale)
            try {
                apply(b.session); b.settle(); b.ui.click("Why isn't it here?"); b.settle()
                val bad = auditScene(b.scene).filter { it.kind != AuditKind.Ellipsis }
                assertEquals(emptyList(), bad, "$name at ${w}x$h text $scale:\n" + bad.describe())
            } finally { b.close() }
        }
    }

    private companion object {
        val FINGERPRINT_A = (0 until 32).joinToString(":") { "%02X".format((37 + it * 11) and 255) }
        val FINGERPRINT_B = (0 until 32).joinToString(":") { "%02X".format((74 + it * 11) and 255) }
        val CARDS = listOf(ScanInterface("ethernet_1 Intel(R) Ethernet Controller", "10.20.30.7", 24, true, 7), ScanInterface("wireless_1 Wi-Fi 6E", "10.20.5.7", 22, true, 9))
        fun waitFor(what: String, timeoutMs: Long = 8000, condition: () -> Boolean) {
            val end = System.nanoTime() + timeoutMs * 1_000_000
            while (!condition()) { check(System.nanoTime() < end) { "Timed out waiting for $what" }; Thread.sleep(5) }
        }
    }
}
