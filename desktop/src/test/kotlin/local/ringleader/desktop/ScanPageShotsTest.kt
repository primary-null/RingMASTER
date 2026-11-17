@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "DEPRECATION")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.*
import local.ringleader.core.*

/**
 * Pictures of the Scan page in every state it can be in, for a person to look at (`RM_FIELD_SHOTS=<folder>`; without it the test ends
 * at once). Every address is invented: documentation ranges for what a scan finds, and 10.20.x.x for the networks of the plan (the plan
 * only offers private space). The page sits in the app's window, so the widths are the ones a person gets. Nothing connects anywhere.
 */
class ScanPageShotsTest {
    private fun fp(seed: Int) = (0 until 32).joinToString(":") { "%02X".format((seed * 37 + it * 11) and 255) }
    private val future = 1_900_000_000_000L
    private fun pve(name: String, ip: String, seed: Int, port: Int = 8006, kind: ScanKind = ScanKind.PVE) = ScanHit(ip, port, true, kind, "$name - Proxmox", fp(seed),
        "CN=$name.example.test,O=Proxmox,OU=PVE Cluster Node", false, "CN=Proxmox,OU=${seed}0001,O=PVE Cluster Manager CA", future)
    private fun net(name: String, ip: String, prefix: Int, base: String, onLink: Boolean = true) = ScanNetwork(ScanInterface(name, ip, prefix), "$base.0/24", null, (1..254).map { "$base.$it" }, onLink = onLink)

    private val east = pve("pve-east", "192.0.2.21", 1); private val eastBackup = pve("pve-east", "192.0.2.21", 3, 8007, ScanKind.PBS)
    private val west = pve("pve-west", "192.0.2.22", 2); private val westTailnet = pve("pve-west", "203.0.113.22", 2)
    private val pdm = pve("pdm", "198.51.100.40", 4, 8443, ScanKind.PDM)
    private val services = listOf(ScanHit("192.0.2.1", 443, true, ScanKind.WEB, "RouterOS", fp(6), "CN=router.example.test"), ScanHit("192.0.2.53", 80, false, ScanKind.PIHOLE, "Pi-hole"),
        ScanHit("192.0.2.123", 8123, false, ScanKind.HOME_ASSISTANT, "Home Assistant"), ScanHit("198.51.100.9", 80, false, ScanKind.WEB, "Printer"),
        ScanHit("203.0.113.5", 443, true, ScanKind.WEB, "Grafana", fp(7), "CN=grafana.example.test"))

    @Test fun pictures() {
        val dir = File(System.getenv("RM_FIELD_SHOTS") ?: return).apply { mkdirs() }
        val home = System.getProperty("user.home")
        val sandbox = Files.createTempDirectory("ringmaster-scan-shots-home")
        System.setProperty("user.home", sandbox.toString())
        try { runSceneTest { renderAll(dir) } } finally { System.setProperty("user.home", home); sandbox.toFile().deleteRecursively(); Appearance.preview(false); ScanOfferClock.now = System::nanoTime }
    }

    private suspend fun renderAll(dir: File) {
        val context = currentCoroutineContext()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val workspace = sampleWorkspace(scope)
        delay(300)
        val state = workspace.state.value

        fun window(w: Int, h: Int, scale: Float, title: String, page: @Composable () -> Unit) = ImageComposeScene(w, h, Density(1f, scale), coroutineContext = context) {
            MaterialTheme(colorScheme = ringColors) { CompositionLocalProvider(LocalReduceMotion provides true) {
                Surface(Modifier.fillMaxSize(), color = Tech.Canvas, contentColor = Tech.Ink) { Column {
                    Row(Modifier.fillMaxWidth().background(Tech.Raised).height(32.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("RingMASTER", Modifier.weight(1f), color = Tech.Amber)
                        WindowControl(WindowGlyph.Minimize) {}; WindowControl(WindowGlyph.Maximize) {}; WindowControl(WindowGlyph.Close) {}
                    }
                    Row(Modifier.weight(1f)) {
                        NavigationRail(state, if (title == "Network scan") "Scan" else "Desk", "Windows 11", workspace, initialExpanded = false) {}
                        Column(Modifier.weight(1f).fillMaxHeight().padding(16.dp), verticalArrangement = Arrangement.spacedBy(Tech.Space8)) {
                            Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("WORKSPACE / ${title.uppercase()}", fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 1.5.sp, color = Tech.Muted)
                                Text(title, Modifier.weight(1f), fontSize = Tech.Title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            HorizontalDivider(color = Tech.Edge)
                            Box(Modifier.weight(1f).fillMaxWidth()) { page() }
                        }
                    }
                } }
            } }
        }
        suspend fun write(scene: ImageComposeScene, name: String, frames: suspend () -> Unit = {}) {
            try {
                var t = 0L
                repeat(14) { t += 50_000_000L; scene.render(t).close(); yield() }
                frames()
                scene.render(t + 16_000_000L).use { image -> File(dir, "$name.png").writeBytes(image.encodeToData()!!.bytes) }
            } finally { scene.close() }
        }

        // ---- the Scan page ------------------------------------------------------------------------------------------------------------
        /** A session that shows [apply]'s state; [plan] gives the Ready state its networks through the real planner. */
        suspend fun scanSession(plan: Boolean, apply: (ScanSession) -> Unit): ScanSession {
            val cards = listOf(ScanInterface("ethernet_32772 Intel(R) Ethernet Controller I226-V", "10.20.30.7", 24, true, 7), ScanInterface("wireless_32768 MediaTek Wi-Fi 6E", "10.20.5.7", 22, true, 9),
                ScanInterface("ethernet_32780 Realtek USB GbE Family Controller", "10.20.40.8", 24, true, 11), ScanInterface("ethernet_32775 Hyper-V Virtual Ethernet Adapter", "172.17.80.1", 20, true, 23),
                ScanInterface("iftype53_32768 Tailscale Tunnel", "100.101.102.103", 32, true, 15))
            val facts = NetworkFactsRead(NetworkFacts(listOf(AdapterFact(7, "Ethernet", null, false, true), AdapterFact(9, "Wi-Fi", null, false, true), AdapterFact(11, "Ethernet 2", null, false, true, "Public"),
                AdapterFact(23, "vEthernet (Default Switch)", null, true, false), AdapterFact(15, "Tailscale", null, true, false)),
                listOf(RouteEntry(0, 0, "10.20.30.1", 7), parseCidr("10.31.0.0/24")!!.let { (n, p) -> RouteEntry(n, p, "100.100.100.100", 15) }, parseCidr("10.30.0.0/16")!!.let { (n, p) -> RouteEntry(n, p, "100.100.100.100", 15) },
                    parseCidr("10.60.0.0/24")!!.let { (n, p) -> RouteEntry(n, p, "10.20.30.254", 7) }), "picture"), null)
            val session = quietSession(scope, FakeScanEnvironment(if (plan) cards else emptyList(), if (plan) facts else NetworkFactsRead(null, null)), ScanChoices.inMemory())
            if (plan) session.extraSubnet = "10.20.88.0/24"
            session.refresh(); withTimeout(5000) { while (session.cards == null || session.reading) delay(10) }
            apply(session); return session
        }
        val nets3 = NetworkPlan(listOf(net("Ethernet", "192.0.2.8", 24, "192.0.2"), net("Wi-Fi", "198.51.100.8", 24, "198.51.100"), net("Tailscale route", "203.0.113.8", 24, "203.0.113", onLink = false),
            ScanNetwork(ScanInterface("vEthernet (Default Switch)", "172.17.80.1", 20), null, "Virtual adapter that does not carry the default route")))
        // Every host that answered a network is a finding, so the counts of the page (hosts answering, answered per network) agree with each other.
        fun answered(network: String, base: String, services: List<Int>, others: Int) = (services + (30 until 30 + others)).mapIndexed { i, n ->
            HostFinding("$base.$n", network, when (i % 3) { 0 -> FoundBy.Known; 1 -> FoundBy.Neighbour; else -> FoundBy.Sweep }, 400L + i * 37L) }
        val east24 = answered("192.0.2.0/24", "192.0.2", listOf(1, 21, 22, 53, 123), 9); val wifi24 = answered("198.51.100.0/24", "198.51.100", listOf(9, 40), 4); val route24 = answered("203.0.113.0/24", "203.0.113", listOf(5, 22), 0)
        val partEast = answered("192.0.2.0/24", "192.0.2", listOf(1, 21, 22), 11); val partWifi = answered("198.51.100.0/24", "198.51.100", emptyList(), 3)
        val tallies = listOf(NetworkTally("192.0.2.0/24", 254, 254, answered = 14, deep = 14, deepDone = 14, done = true), NetworkTally("198.51.100.0/24", 130, 254, answered = 3), NetworkTally("203.0.113.0/24", 20, 254))
        val finishedTallies = listOf(NetworkTally("192.0.2.0/24", 254, 254, answered = 14, done = true), NetworkTally("198.51.100.0/24", 254, 254, answered = 6, done = true), NetworkTally("203.0.113.0/24", 254, 254, answered = 2, done = true))
        val oneNet = NetworkPlan(listOf(net("Ethernet", "192.0.2.8", 24, "192.0.2")))
        val knownPlan = ScanKnown(listOf(KnownServer("10.20.77.20", "pve-east", PlanSource.Saved), KnownServer("10.20.78.5", "pdm", PlanSource.Session)), listOf("https://10.20.77.20:8006"))
        val knownSaved = ScanKnown(emptyList(), listOf("https://192.0.2.21:8006"), mapOf(pinKey(fp(4))!! to "saved connection pdm"))
        class Shot(val name: String, val plan: Boolean, val known: ScanKnown = ScanKnown.NONE, val apply: (ScanSession) -> Unit)
        val cases = listOf(
            Shot("01-plan-before-start", true, knownPlan) { _ -> },
            Shot("02-scanning", false) { s -> s.show(NetworkProgress(nets3, 404, "198.51.100.130", listOf(east, west, services[0]), false, ScanPhase.Sweeping, 17, tallies, findings = partEast + partWifi), running = true, seconds = 7) },
            Shot("03-finished-grouped", false, knownSaved) { s -> s.show(NetworkProgress(nets3, 762, hits = listOf(east, eastBackup, west, westTailnet, pdm) + services, finished = true, alive = 22, tallies = finishedTallies, findings = east24 + wifi24 + route24), seconds = 9) },
            Shot("04-empty", false) { s -> s.show(NetworkProgress(oneNet, 254, finished = true, alive = 12, findings = (1..12).map { HostFinding("192.0.2.$it", "192.0.2.0/24", FoundBy.Neighbour, 900L) },
                tallies = listOf(NetworkTally("192.0.2.0/24", 254, 254, answered = 12, done = true))), seconds = 6) },
            Shot("05-failed", true) { s -> s.show(null, message = scanFailureMessage(java.net.SocketException("down")), seconds = 2) },
            Shot("06-cancelled", false) { s -> s.show(NetworkProgress(nets3, 404, hits = listOf(east, west), tallies = tallies, findings = partEast + partWifi), message = "Cancelled", seconds = 7) },
            Shot("08-nothing-ticked", true) { s -> s.plan.rows.filter { it.choosable }.forEach { r -> s.setChoice(r.id, false) }; s.extraSubnet = ""; s.start() },
            Shot("09-no-network", false) { s -> s.start() },
        )
        val sizes = listOf(1280 to 720, 1920 to 1080)
        Appearance.preview(false)
        for (c in cases) for ((w, h) in sizes) {
            val s = scanSession(c.plan, c.apply)
            write(window(w, h, 1f, "Network scan") { ScanScreen(s, emptyList(), known = c.known) {} }, "dark-${c.name}-${w}x$h")
        }
        // The check's answer on a finished page: the address is inside a route nobody ticked, so the answer names it and offers to tick it.
        val lan10 = NetworkPlan(listOf(net("Ethernet", "10.20.30.7", 24, "10.20.30")))
        for ((w, h) in sizes) {
            val s = scanSession(true) { x -> x.show(NetworkProgress(lan10, 254, hits = listOf(pve("pve-east", "10.20.30.21", 1), pve("pve-west", "10.20.30.22", 2)), finished = true, alive = 9,
                tallies = listOf(NetworkTally("10.20.30.0/24", 254, 254, answered = 9, done = true))), seconds = 6) }
            val scene = window(w, h, 1f, "Network scan") { ScanScreen(s, emptyList()) {} }
            val ui = CreateScene(scene)
            try {
                ui.frames(); ui.click("Why isn't it here?"); ui.frames()
                val field = ui.nodes().filter { it.config.getOrNull(SemanticsActions.SetText) != null }[1]
                field.config[SemanticsActions.SetText].action!!(AnnotatedString("10.60.0.9")); ui.frames()
                ui.click("Check"); ui.frames()
            } finally { write(scene, "dark-07-why-answer-${w}x$h") }
        }
        // The same states in the other looks, and with larger text, at the window size most people have.
        val looks = listOf<Triple<String, () -> Unit, Float>>(
            Triple("light", { Appearance.preview(true) }, 1f), Triple("hc-black", { Appearance.preview(ThemeMode.HighContrast) }, 1f),
            Triple("hc-white", { Appearance.previewSystemContrast(null, light = true) }, 1f), Triple("dark-text150", { Appearance.preview(false) }, 1.5f))
        for ((look, apply, scale) in looks) {
            apply()
            for (c in cases.filter { it.name in setOf("01-plan-before-start", "02-scanning", "03-finished-grouped", "04-empty", "05-failed") }) {
                val s = scanSession(c.plan, c.apply)
                write(window(1280, if (scale > 1f) 1500 else 720, scale, "Network scan") { ScanScreen(s, emptyList(), known = c.known) {} }, "$look-${c.name}-1280x${if (scale > 1f) 1500 else 720}")
            }
        }
        Appearance.preview(false)

        // ---- the Connect form with the scanned certificate's offer -----------------------------------------------------------------------
        var nanos = 5_000_000_000_000L
        ScanOfferClock.now = { nanos }; ScanOfferClock.tickMillis = 20
        val root = Files.createTempDirectory("scan-offer-shots")
        val desk = ProfileDesk(workspace, ProfileStore(root.resolve("profiles.json")))
        val motion = MotionPreferences(root.resolve("motion"))
        val pick = ScanHit("192.0.2.21", 8006, true, ScanKind.PVE, fingerprint = fp(1), subject = "CN=pve-east.example.test")
        try {
            for ((w, h) in sizes) for ((name, seconds) in listOf("09-connect-offer-countdown" to 125L, "10-connect-offer-lapsed" to 301L)) {
                nanos = 5_000_000_000_000L
                var pickedAt by mutableStateOf<Long?>(nanos)
                var scanned by mutableStateOf<ScanHit?>(pick)
                applyScanPick(desk, pick)
                val scene = window(w, h, 1f, "Connection") {
                    val deskState by desk.state.collectAsState()
                    DeskScreen(workspace.state.value, deskState, desk, scanned = scanned, scannedAt = pickedAt, onScannedEnded = { scanned = null; pickedAt = null }, motion = motion) {}
                }
                val ui = CreateScene(scene)
                try {
                    ui.frames(); nanos += seconds * 1_000_000_000L
                    repeat(40) { ui.frames(); delay(25) }
                } finally { write(scene, "dark-$name-${w}x$h") }
            }
        } finally { desk.close(); root.toFile().deleteRecursively(); ScanOfferClock.now = System::nanoTime; ScanOfferClock.tickMillis = 1000 }
        Appearance.preview(false)
        workspace.close(); scope.cancel()
    }
}
