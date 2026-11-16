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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import java.nio.file.Files
import kotlin.coroutines.CoroutineContext
import kotlin.test.*
import kotlinx.coroutines.*
import local.ringleader.core.*

private fun card(name: String, ip: String, prefix: Int, index: Int? = null) = ScanInterface(name, ip, prefix, true, index)
private fun waitFor(what: String, timeoutMs: Long = 8000, condition: () -> Boolean) {
    val end = System.nanoTime() + timeoutMs * 1_000_000
    while (!condition()) { check(System.nanoTime() < end) { "Timed out waiting for $what" }; Thread.sleep(5) }
}
private fun ScanSession.untilDone(what: String = "the scan") =
    waitFor(what) { !running && (progress?.finished == true || message.isNotEmpty()) }.also { check(message.isEmpty() || progress?.finished == true) { "The scan said: $message" } }

/** The session behind the Scan page: what it reads, what it plans, what it remembers, and what Start sweeps. No window, no network. */
class ScanSessionPlanTest {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    @AfterTest fun stop() { job.cancel() }
    private val lan = card("ethernet_32772 Intel(R) Ethernet Controller I226-V", "10.20.30.7", 24, 7)
    private val wide = card("wireless_32768 Wi-Fi", "10.20.5.7", 22, 9)

    @Test fun theCardsAndFactsAreReadOnceAndTheyDecideThePlan() {
        val env = FakeScanEnvironment(listOf(lan, card("lo", "127.0.0.1", 8)), NetworkFactsRead(NetworkFacts(emptyList(), listOf(RouteEntry(0, 0, "10.20.30.1", 7)), "test"), null))
        val session = quietSession(scope, env)
        assertTrue(session.plan.rows.isEmpty() && session.cards == null)
        session.refresh()
        waitFor("the read") { !session.reading && session.cards != null && session.facts != null }
        assertEquals(1, env.cardReads); assertEquals(1, env.factReads)
        assertEquals(listOf("10.20.30.0/24"), session.plan.swept.map { it.id }); assertEquals(1, session.plan.skipped.size)
        session.refresh(); session.refresh()
        assertEquals(1, env.cardReads, "the answer is kept while the page is open")
        session.refresh(force = true); waitFor("the second read") { env.cardReads == 2 && !session.reading }
    }

    @Test fun aFailedReadLeavesTheCardsOnThePlanAndSaysWhatFailed() {
        val env = FakeScanEnvironment(listOf(lan), NetworkFactsRead(null, "This computer's routes could not be read (It did not answer in time.)."))
        val session = quietSession(scope, env)
        session.refresh(); waitFor("the read") { session.facts != null && !session.reading }
        assertEquals(listOf("10.20.30.0/24"), session.plan.swept.map { it.id })
        assertEquals("This computer's routes could not be read (It did not answer in time.).", session.plan.problem)
        assertTrue(session.canStart)
    }

    @Test fun aReadThatThrowsIsAFailedReadNotACrash() {
        val broken = object : ScanEnvironment {
            override fun cards() = listOf(lan)
            override suspend fun facts(): NetworkFactsRead = error("no adapters")
            override fun scanner() = NetworkScanner { _, _ -> emptyList() }
        }
        val session = quietSession(scope, broken)
        session.refresh(); waitFor("the read") { session.facts != null && !session.reading }
        assertEquals(listOf("10.20.30.0/24"), session.plan.swept.map { it.id }); assertNotNull(session.plan.problem)
    }

    @Test fun aChoiceIsRememberedForTheNetworkAcrossRestartsAndCanBeReset() {
        val dir = Files.createTempDirectory("scan-choices")
        val file = dir.resolve("scan.txt")
        val env = FakeScanEnvironment(listOf(lan, wide))
        val first = quietSession(scope, env, ScanChoices.stored(file))
        first.refresh(); waitFor("the read") { first.cards != null && !first.reading }
        assertEquals(setOf("10.20.30.0/24", "10.20.5.0/24"), first.plan.swept.map { it.id }.toSet())
        first.setChoice("10.20.30.0/24", false); first.setChoice("10.20.4.0/22", true)
        assertEquals(listOf("10.20.4.0/22"), first.plan.swept.map { it.id })
        // The next run of the app: a new session, a new store, the same file.
        val second = quietSession(scope, env, ScanChoices.stored(file))
        second.refresh(); waitFor("the read") { second.cards != null && !second.reading }
        assertEquals(listOf("10.20.4.0/22"), second.plan.swept.map { it.id }); assertEquals(false, second.plan.rows.first { it.id == "10.20.30.0/24" }.remembered)
        second.resetChoices()
        assertEquals(setOf("10.20.30.0/24", "10.20.5.0/24"), second.plan.swept.map { it.id }.toSet())
        assertEquals(setOf("10.20.30.0/24", "10.20.5.0/24"), quietSession(scope, env, ScanChoices.stored(file)).also { it.refresh(); waitFor("read") { it.cards != null } }.plan.swept.map { it.id }.toSet())
        dir.toFile().deleteRecursively()
    }

    @Test fun startSweepsExactlyWhatThePlanTicksAndTheTypedNetworkOnItsOwnPorts() {
        val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<String, Int>>()
        val env = FakeScanEnvironment(listOf(lan, wide), make = { NetworkScanner { ip, port -> seen += ip to port; emptyList() } })
        val session = quietSession(scope, env)
        session.refresh(); waitFor("the read") { session.cards != null && !session.reading }
        session.setChoice("10.20.5.0/24", false)
        session.extraSubnet = "203.0.113.7/32"
        session.start(); session.untilDone()
        val addresses = seen.map { it.first }.toSet()
        assertEquals(254 + 1, addresses.size)
        assertTrue(addresses.all { it.startsWith("10.20.30.") || it == "203.0.113.7" }, "the unticked /24 and the wider /22 are not touched")
        assertEquals(PROXMOX_PORTS.toSet(), seen.filter { it.first == "203.0.113.7" }.map { it.second }.toSet(), "outside private space only the Proxmox ports are asked")
        assertEquals(LAN_SCAN_PORTS.toSet(), seen.filter { it.first == "10.20.30.9" }.map { it.second }.toSet())
        assertEquals(1, env.scansStarted)
        assertEquals(255, session.progress!!.total)
    }

    @Test fun nothingStartsOnATypoAndNothingTickedIsSaidNotSwept() {
        val env = FakeScanEnvironment(listOf(lan))
        val session = quietSession(scope, env)
        session.refresh(); waitFor("the read") { session.cards != null && !session.reading }
        session.extraSubnet = "10.20.30"
        assertTrue(session.extraNote.startsWith("Not an IPv4 network")); assertFalse(session.canStart)
        session.start(); assertFalse(session.running); assertEquals(0, env.scansStarted); assertNull(session.progress)
        session.extraSubnet = ""
        assertEquals("", session.extraNote, "the note follows the field")
        session.setChoice("10.20.30.0/24", false)
        assertFalse(session.canStart, "nothing is ticked")
        session.start()
        // The page does not offer the click (Start is off), and a direct call is refused with the reason.
        assertFalse(session.running); assertEquals("Nothing is ticked. Tick a network below, or type one.", session.message); assertEquals(0, env.scansStarted)
    }

    @Test fun aStartBeforeTheReadEndsWaitsForItThenSweepsWhatItFound() {
        val hold = CompletableDeferred<Unit>()
        val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val env = FakeScanEnvironment(listOf(lan), hold = hold, make = { NetworkScanner { ip, _ -> seen += ip; emptyList() } })
        val session = quietSession(scope, env)
        assertTrue(session.canStart, "Start is allowed before the first read: it reads first")
        session.start()
        Thread.sleep(150)
        assertTrue(session.running); assertTrue(seen.isEmpty(), "nothing is swept while the networks are still being read")
        hold.complete(Unit)
        session.untilDone()
        assertEquals(254, seen.size)
    }

    @Test fun knownServersAndEarlierFindingsBecomeNetworksToOffer() {
        val env = FakeScanEnvironment(listOf(lan), make = { NetworkScanner { ip, port -> if (ip == "10.20.30.9" && port == 8006) listOf(ScanHit(ip, port, true, ScanKind.PVE, subject = "CN=pve-lab.example.test")) else emptyList() } })
        val session = quietSession(scope, env)
        session.known = ScanKnown(listOf(KnownServer("10.20.40.5", "pve-west", PlanSource.Saved), KnownServer("10.20.50.5", "pve-lab", PlanSource.Session)), listOf("https://10.20.40.5:8006"))
        session.refresh(); waitFor("the read") { session.cards != null && !session.reading }
        assertEquals(setOf("10.20.30.0/24", "10.20.40.0/24", "10.20.50.0/24"), session.plan.swept.map { it.id }.toSet())
        assertEquals("Saved connection pve-west", session.plan.rows.first { it.id == "10.20.40.0/24" }.label)
        session.setChoice("10.20.40.0/24", false); session.setChoice("10.20.50.0/24", false)
        session.start(); session.untilDone()
        // The server the scan found is offered with its network next time, under its own name.
        assertEquals(listOf("pve-lab.example.test"), session.progress!!.hits.map { scanHostname(it) })
        assertEquals(listOf("10.20.30.0/24"), session.plan.rows.filter { it.swept }.map { it.id })
    }

    @Test fun noProfilesAndNoOpenConnectionsKnowNothing() {
        val known = scanKnownOf(emptyList())
        assertEquals(ScanKnown.NONE, known); assertEquals(emptyList(), known.origins)
    }

    @Test fun aNetworkChangedUnderThePageIsReadAgainWhenTheReadIsOld() {
        val env = FakeScanEnvironment(listOf(lan))
        val session = quietSession(scope, env)
        session.refresh(); waitFor("the read") { session.cards != null && !session.reading }
        assertEquals(1, env.cardReads)
        // A scan started long after the read reads again first (the clock is the system's; the test moves the read's age instead).
        session.javaClass.getDeclaredField("readAt").apply { isAccessible = true }.setLong(session, System.nanoTime() - 120_000_000_000L)
        session.start(); session.untilDone()
        assertEquals(2, env.cardReads)
    }
}

/** The choices the page remembers: what is kept, how much, and what is never trusted from a file. */
class ScanChoicesTest {
    private val dir = Files.createTempDirectory("scan-choices-test")
    private val file = dir.resolve("scan.txt")
    @AfterTest fun clean() { dir.toFile().deleteRecursively(); SaveStatus.dismiss() }

    @Test fun aChoiceIsKeptPerNetworkAndSurvivesARestart() {
        val a = ScanChoices.stored(file)
        assertFalse(a.any); a.put("10.20.30.0/24", false); a.put("10.20.31.0/24", true)
        val b = ScanChoices.stored(file)
        assertEquals(mapOf("10.20.30.0/24" to false, "10.20.31.0/24" to true), b.all()); assertTrue(b.any)
        b.put("10.20.30.0/24", true)
        assertEquals(true, ScanChoices.stored(file).all()["10.20.30.0/24"])
        b.reset()
        assertEquals(emptyMap(), ScanChoices.stored(file).all()); assertFalse(ScanChoices.stored(file).any)
    }

    @Test fun theFileHoldsNothingButNetworksAndAYesOrNo() {
        ScanChoices.stored(file).apply { put("10.20.30.0/24", false); put("192.168.20.0/24", true) }
        val lines = Files.readString(file).lines().filter { it.isNotBlank() && !it.startsWith("#") }.sorted()
        assertEquals(listOf("net.10.20.30.0/24=off\\:1", "net.192.168.20.0/24=on\\:2", "schema=1"), lines)
        if ("posix" in file.fileSystem.supportedFileAttributeViews()) assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        assertTrue(Files.list(dir).use { it.toList() }.all { it.fileName.toString() == "scan.txt" }, "no temporary file is left")
    }

    @Test fun onlyCanonicalNetworksAreKeptAndOnlyAsOnOrOff() {
        val c = ScanChoices.stored(file)
        for (bad in listOf("10.20.30.7/24", "10.20.30.0", "+10.20.30.0/24", "10.20.30.0/33", "abc", "", "10.20.30.0/24 ", "١٠.20.30.0/24")) c.put(bad, true)
        assertEquals(emptyMap(), c.all()); assertFalse(Files.exists(file))
        Files.writeString(file, "schema=1\nnet.10.20.30.0/24=maybe\\:1\nnet.10.20.31.0/24=on\\:4\nnet.10.20.32.7/24=on\\:5\nnet.junk=on\\:6\nother=on\n")
        assertEquals(mapOf("10.20.31.0/24" to true), ScanChoices.stored(file).all())
    }

    @Test fun aCorruptOrOversizedFileIsAnEmptyOneAndIsRewrittenOnTheNextChoice() {
        Files.writeString(file, "\u0000\u0001 not a properties file \\u12")
        assertEquals(emptyMap(), ScanChoices.stored(file).all())
        Files.write(file, ByteArray(200_000) { 'a'.code.toByte() })
        assertEquals(emptyMap(), ScanChoices.stored(file).all())
        val c = ScanChoices.stored(file); c.put("10.20.30.0/24", true)
        assertEquals(mapOf("10.20.30.0/24" to true), ScanChoices.stored(file).all())
    }

    @Test fun aFileFromANewerVersionIsLeftAlone() {
        val text = "schema=2\nnet.10.20.30.0/24=on\\:1\n"
        Files.writeString(file, text)
        val c = ScanChoices.stored(file)
        assertEquals(emptyMap(), c.all()); c.put("10.20.31.0/24", true)
        assertEquals(text, Files.readString(file), "a newer RingMASTER's file is not overwritten")
    }

    @Test fun onlyTwoHundredAndFiftySixChoicesAreKeptAndTheOldestGoFirst() {
        val c = ScanChoices.stored(file)
        for (i in 0 until 280) c.put("10.${100 + i / 256}.${i % 256}.0/24", i % 2 == 0)
        val kept = ScanChoices.stored(file).all()
        assertEquals(256, kept.size)
        assertFalse("10.100.0.0/24" in kept); assertTrue("10.101.23.0/24" in kept, "the newest are kept")
        assertEquals(false, kept["10.101.23.0/24"]); assertEquals(true, kept["10.101.22.0/24"])
    }

    @Test fun aLinkInPlaceOfTheFileIsNeitherReadNorWritten() {
        val target = dir.resolve("elsewhere.txt"); Files.writeString(target, "schema=1\nnet.10.20.30.0/24=on\\:1\n")
        val link = dir.resolve("link.txt")
        try { Files.createSymbolicLink(link, target) } catch (e: Exception) { return }       // no symbolic links here (Windows without the right)
        val c = ScanChoices.stored(link)
        assertEquals(emptyMap(), c.all()); c.put("10.20.31.0/24", true)
        assertEquals("schema=1\nnet.10.20.30.0/24=on\\:1\n", Files.readString(target))
    }

    @Test fun inMemoryChoicesWriteNothingAnywhere() {
        val c = ScanChoices.inMemory(); c.put("10.20.30.0/24", false)
        assertEquals(mapOf("10.20.30.0/24" to false), c.all()); c.reset(); assertFalse(c.any)
    }
}

/** The plan as the page draws it, before Start. */
class ScanPlanSceneTest {
    private class Bench(context: CoroutineContext, width: Int, height: Int, val env: FakeScanEnvironment, choices: ScanChoices = ScanChoices.inMemory(), val web: Boolean = false) : AutoCloseable {
        private val job = SupervisorJob()
        val session = quietSession(CoroutineScope(Dispatchers.Unconfined + job), env, choices)
        val used = mutableListOf<ScanHit>()
        val scene = ImageComposeScene(width, height, coroutineContext = context) {
            MaterialTheme(colorScheme = ringColors) { CompositionLocalProvider(LocalReduceMotion provides true, LocalAppHost provides AppHost(web)) {
                Surface(Modifier.fillMaxSize(), color = Tech.Canvas, contentColor = Tech.Ink) { Box(Modifier.fillMaxSize().padding(16.dp)) { ScanScreen(session, emptyList()) { used += it } } }
            } }
        }
        val ui = CreateScene(scene)
        suspend fun settle() { ui.frames(); delay(30); ui.frames() }
        fun texts(): List<String> = ui.nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text } }
        fun has(text: String) = ui.nodes().any { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text.equals(text, ignoreCase = true) } == true }
        fun focusedText(): String = ui.nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true }?.let { n ->
            buildList { fun walk(x: androidx.compose.ui.semantics.SemanticsNode) { addAll(x.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }); x.children.forEach(::walk) }; walk(n) }.joinToString(" | ") } ?: "(nothing)"
        override fun close() { scene.close(); job.cancel() }
    }

    private val cards = listOf(card("ethernet_32772 Intel(R) Ethernet Controller I226-V", "10.20.30.7", 24, 7), card("wireless_32768 Wi-Fi 6E", "10.20.5.7", 22, 9),
        card("ethernet_32775 Hyper-V Virtual Ethernet Adapter", "172.17.80.1", 20, 23), card("iftype53_32768 Tailscale Tunnel", "100.101.102.103", 32, 15))

    @Test fun theWholePlanIsOnThePageBeforeStartWithSourceSizeAndReason() = runSceneTest {
        val b = Bench(coroutineContext, 1280, 1000, FakeScanEnvironment(cards))
        try {
            b.settle()
            assertTrue(b.has("Networks to scan"))
            assertTrue(b.has("A scan sweeps 508 addresses on 2 networks, about 8 s."), b.texts().toString())
            for (needed in listOf("Intel(R) Ethernet Controller I226-V", "10.20.30.0/24", "THIS COMPUTER", "254 addresses", "about 6 s", "Whole network", "OFF BY DEFAULT", "1,022 addresses",
                "The /24 around 10.20.5.7, of a /22 (1,022 addresses)", "10.20.5.0/24 · of 10.20.4.0/22", "10.20.4.0/22", "Show 2 not scanned")) assertTrue(b.has(needed), "$needed in ${b.texts()}")
            b.ui.click("Show 2 not scanned"); b.settle()
            assertTrue(b.has("Virtual-switch adapter") && b.has("Tailscale / CGNAT space is never swept"), b.texts().toString())
            assertTrue(b.has("Hide 2 not scanned"))
            // The page names no Java 21 card name.
            assertTrue(b.texts().none { "ethernet_32772" in it || "iftype53" in it })
        } finally { b.close() }
    }

    @Test fun clickingARowTicksItRemembersItAndChangesWhatStartDoes() = runSceneTest {
        val choices = ScanChoices.inMemory()
        val b = Bench(coroutineContext, 1280, 1000, FakeScanEnvironment(cards), choices)
        try {
            b.settle()
            b.ui.click("Whole network"); b.settle()
            assertEquals(mapOf("10.20.4.0/22" to true), choices.all())
            assertTrue(b.has("A scan sweeps 1,276 addresses on 2 networks, about 13 s."), b.texts().toString())
            assertTrue(b.has("Included in 10.20.4.0/22"), "the /24 inside the ticked /22 is not swept twice")
            b.ui.click("Intel(R) Ethernet Controller I226-V"); b.settle()
            assertEquals(false, choices.all()["10.20.30.0/24"])
            assertTrue(b.has("Reset choices"))
            b.ui.click("Reset choices"); b.settle()
            assertEquals(emptyMap(), choices.all()); assertTrue(b.has("A scan sweeps 508 addresses on 2 networks, about 8 s."))
        } finally { b.close() }
    }

    @Test fun theKeyboardReachesEveryTickAndSpaceChangesIt() = runSceneTest {
        val choices = ScanChoices.inMemory()
        val b = Bench(coroutineContext, 1280, 1000, FakeScanEnvironment(cards), choices)
        try {
            b.settle()
            var reached = ""
            repeat(30) { if (!reached.contains("Whole network")) { b.ui.key(Key.Tab); reached = b.focusedText() } }
            assertTrue(reached.contains("Whole network"), reached)
            b.ui.key(Key.Spacebar)
            assertEquals(mapOf("10.20.4.0/22" to true), choices.all())
            b.ui.key(Key.Spacebar)
            assertEquals(mapOf("10.20.4.0/22" to false), choices.all())
        } finally { b.close() }
    }

    @Test fun startIsOffWhenNothingIsTickedAndItsReasonIsOnThePage() = runSceneTest {
        val choices = ScanChoices.inMemory().apply { put("10.20.30.0/24", false); put("10.20.5.0/24", false) }
        val b = Bench(coroutineContext, 1280, 1000, FakeScanEnvironment(cards), choices)
        try {
            b.settle()
            assertTrue(b.has("Nothing is ticked, so a scan has nothing to sweep. Tick a network below, or type one."))
            val start = b.ui.text("Start scan")
            assertNotNull(start.config.getOrNull(SemanticsProperties.Disabled), "Start has nothing to do")
        } finally { b.close() }
    }

    @Test fun whileTheNetworksAreBeingReadThePageSaysSoAndThenShowsThem() = runSceneTest {
        val hold = CompletableDeferred<Unit>()
        val b = Bench(coroutineContext, 1280, 800, FakeScanEnvironment(cards, hold = hold))
        try {
            b.settle()
            assertTrue(b.has("Reading…"), b.texts().toString())
            assertTrue(b.has("Reading adapter details and routes… this list can still change."), "the list is shown at once and said to be provisional: " + b.texts().toString())
            hold.complete(Unit); b.settle(); b.settle()
            assertTrue(b.has("A scan sweeps 508 addresses on 2 networks, about 8 s."), b.texts().toString())
            assertFalse(b.has("Reading adapter details and routes… this list can still change."))
        } finally { b.close() }
    }

    @Test fun aFailedReadIsSaidAndTheCardsAreStillOffered() = runSceneTest {
        val b = Bench(coroutineContext, 1280, 800, FakeScanEnvironment(cards, NetworkFactsRead(null, "This computer's routes could not be read (It did not answer in time.).")))
        try {
            b.settle(); b.settle()
            assertTrue(b.has("This computer's routes could not be read (It did not answer in time.)."), b.texts().toString())
            assertTrue(b.has("A scan sweeps 508 addresses on 2 networks, about 8 s."))
        } finally { b.close() }
    }

    @Test fun theTypedNetworkIsARowWithItsOwnNoteAndAProblemIsSaidUnderTheField() = runSceneTest {
        val b = Bench(coroutineContext, 1280, 1000, FakeScanEnvironment(cards))
        try {
            b.settle()
            b.ui.type("10.20.30.7/32"); b.settle()
            assertTrue(b.has("TYPED") && b.has("Included in 10.20.30.0/24"), "a host inside a ticked network is swept with it: " + b.texts().toString())
            b.ui.type("10.60.0.7/32"); b.settle()
            assertTrue(b.has("TYPED") && b.has("One address") && b.has("1 address"), b.texts().toString())
            assertTrue(b.has("A scan sweeps 509 addresses on 3 networks, about 8 s."), b.texts().toString())
            b.ui.type("10.20.30"); b.settle()
            assertTrue(b.texts().any { it.startsWith("Not an IPv4 network") }, b.texts().toString())
            assertNotNull(b.ui.text("Start scan").config.getOrNull(SemanticsProperties.Disabled))
            b.ui.type("100.64.0.0/24"); b.settle()
            assertTrue(b.texts().any { it.contains("never swept") }, b.texts().toString())
            b.ui.type(""); b.settle()
            assertTrue(b.texts().none { it.startsWith("Not an IPv4 network") || it.contains("never swept; search") }, "the note goes when the field is cleared")
        } finally { b.close() }
    }

    @Test fun aFinishedScanKeepsItsPageAndTheNetworksAreOneClickAway() = runSceneTest {
        val b = Bench(coroutineContext, 1280, 1000, FakeScanEnvironment(cards))
        try {
            b.session.refresh(); b.settle()
            b.session.start(); b.session.untilDone()
            b.settle()
            assertFalse(b.has("Networks to scan"), "the results come first")
            assertTrue(b.has("Change networks"))
            b.ui.click("Change networks"); b.settle()
            assertTrue(b.has("Networks to scan") && b.has("Hide networks"))
        } finally { b.close() }
    }
}

/** Extra sources of reachable networks, connected to the page's session. */
class ScanSessionSourcesTest {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    @AfterTest fun stop() { job.cancel() }
    private val lan = card("ethernet_32772 Intel(R) Ethernet Controller I226-V", "10.20.30.7", 24, 7)
    private val withTailnetRoute = NetworkFactsRead(NetworkFacts(listOf(AdapterFact(7, "Ethernet", null, false, true), AdapterFact(15, "Tailscale", null, true, false)),
        listOf(RouteEntry(0, 0, "10.20.30.1", 7), parseCidr("10.31.0.0/24")!!.let { (n, p) -> RouteEntry(n, p, "100.100.100.100", 15) }), "test"), null)

    @Test fun aConnectedSourceJoinsThePlanAndFollowsWhatItReadsFrom() {
        val advertised = androidx.compose.runtime.mutableStateOf(listOf("router-1" to "10.31.0.0/24"))
        val session = quietSession(scope, FakeScanEnvironment(listOf(lan), withTailnetRoute))
        session.refresh(); waitFor("the read") { session.facts != null && !session.reading }
        assertEquals(listOf("Tailscale route"), session.plan.rows.filter { it.source == PlanSource.Tailnet }.map { it.label }, "the routing table alone already shows the route")
        session.connect(TailnetSubnetRoutes { advertised.value })
        assertEquals(listOf("Tailscale route via router-1"), session.plan.rows.filter { it.source == PlanSource.Tailnet }.map { it.label })
        assertEquals(setOf("10.20.30.0/24", "10.31.0.0/24"), session.plan.swept.map { it.id }.toSet())
        // The source reads Compose state: a change of what it says is a change of the plan.
        advertised.value = listOf("router-2" to "10.31.0.0/24", "router-2" to "10.32.0.0/24")
        assertEquals(setOf("Tailscale route via router-2"), session.plan.rows.filter { it.source == PlanSource.Tailnet }.map { it.label }.toSet())
        assertFalse(session.plan.rows.first { it.id == "10.32.0.0/24" }.on, "a route this computer does not have is listed off")
    }

    @Test fun aSourceThatFailsIsSaidAndTheRestOfThePlanStands() {
        val session = quietSession(scope, FakeScanEnvironment(listOf(lan)))
        session.connect { error("boom") }
        session.refresh(); waitFor("the read") { session.cards != null && !session.reading }
        assertEquals(listOf("10.20.30.0/24"), session.plan.swept.map { it.id })
        assertTrue(session.plan.problem!!.contains("could not be read"), session.plan.problem)
    }
}
