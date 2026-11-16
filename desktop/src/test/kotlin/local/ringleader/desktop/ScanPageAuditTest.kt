@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.*
import local.ringleader.core.*

/**
 * Scan page audit, 2026-11-16. Two parts.
 *
 * 1. `RM_SCAN_AUDIT_SHOTS=<folder>` (opt-in) writes pictures of the Scan page in every state, at 1280x720 and 1920x1080, in
 *    the dark theme, plus light, both high contrasts and Showcase for the main states. The page sits in the app's window
 *    (title bar, collapsed rail, page header) so the widths are the ones a user gets. All data is invented and in the
 *    documentation ranges; nothing connects anywhere and no scan is started.
 * 2. Characterization tests of the keyboard path and of what Showcase hides. They pass today and pin the audit's findings.
 */
class ScanPageAuditTest {
    // ---------------------------------------------------------------------------------------------------------------
    // Invented data (documentation ranges only)
    // ---------------------------------------------------------------------------------------------------------------

    private fun fp(seed: Int) = (0 until 32).joinToString(":") { "%02X".format((seed * 37 + it * 11) and 255) }
    private val day = 86_400_000L
    private val future = 1_900_000_000_000L
    private fun net(name: String, ip: String, prefix: Int, base: String) =
        ScanNetwork(ScanInterface(name, ip, prefix), "$base.0/24", null, (1..254).map { "$base.$it" })
    private fun skipped(name: String, ip: String, prefix: Int, reason: String) = ScanNetwork(ScanInterface(name, ip, prefix), null, reason)

    private val lan = net("ethernet_32772 Intel(R) Ethernet Connection", "192.0.2.5", 24, "192.0.2")
    private val second = net("ethernet_32780 Realtek USB GbE Family Controller", "198.51.100.8", 24, "198.51.100")
    private val third = net("wireless_32768 Intel(R) Wi-Fi 6E AX211 160MHz", "203.0.113.9", 24, "203.0.113")
    private val skippedNetworks = listOf(
        skipped("ethernet_32775 Hyper-V Virtual Ethernet Adapter", "172.17.80.1", 20, "Virtual-switch adapter"),
        skipped("iftype53_32768 Tailscale Tunnel", "100.101.102.103", 32, "Mesh / CGNAT space is not swept"),
        skipped("ethernet_32778 Npcap Loopback Adapter", "169.254.10.20", 16, "Link-local"))

    private fun pve(name: String, ip: String, seed: Int, saved: Boolean = false, expired: Boolean = false, selfSigned: Boolean = false) = ScanHit(ip, 8006, true, ScanKind.PVE,
        "$name - Proxmox Virtual Environment", fp(seed), "CN=$name.example.test,O=Proxmox Virtual Environment,OU=PVE Cluster Node", saved,
        if (selfSigned) "CN=$name.example.test,O=Proxmox Virtual Environment,OU=PVE Cluster Node" else "CN=Proxmox Virtual Environment,OU=${seed}0001,O=PVE Cluster Manager CA",
        if (expired) System.currentTimeMillis() - 3 * day else future)
    private val proxmox = listOf(
        pve("pve-east", "192.0.2.21", 1, saved = true), pve("pve-west", "192.0.2.22", 2), pve("pve-lab", "192.0.2.23", 3, expired = true),
        ScanHit("192.0.2.40", 8443, true, ScanKind.PDM, "Proxmox Datacenter Manager", fp(4), "CN=pdm.example.test,O=Proxmox Datacenter Manager", false,
            "CN=Proxmox Datacenter Manager", future),
        ScanHit("192.0.2.90", 8007, true, ScanKind.PBS, "Proxmox Backup Server", fp(5), "CN=backup.example.test,O=Proxmox Backup Server", false,
            "CN=backup.example.test,O=Proxmox Backup Server", future))
    private val services: List<ScanHit> = listOf(
        ScanHit("192.0.2.1", 443, true, ScanKind.WEB, "RouterOS", fp(6), "CN=router.example.test"),
        ScanHit("192.0.2.53", 80, false, ScanKind.PIHOLE, "Pi-hole"),
        ScanHit("192.0.2.54", 3000, false, ScanKind.ADGUARD, "AdGuard Home"),
        ScanHit("192.0.2.123", 8123, false, ScanKind.HOME_ASSISTANT, "Home Assistant"),
        ScanHit("192.0.2.201", 80, false, ScanKind.WEB, "Moved Temporarily"),
        ScanHit("192.0.2.240", 443, true, ScanKind.WEB, "Synology DiskStation", fp(7), "CN=nas.example.test"),
    ) + (1..28).map { n -> ScanHit("192.0.2.${100 + n}", if (n % 3 == 0) 443 else 80, n % 3 == 0, ScanKind.WEB, if (n % 4 == 0) null else listOf("Grafana", "Jellyfin", "Printer", "Welcome to nginx!", "Camera")[n % 5],
        if (n % 3 == 0) fp(10 + n) else null, if (n % 3 == 0) "CN=device$n.example.test" else null) }

    @Suppress("UNCHECKED_CAST")
    private fun <T> stateOf(owner: Any, name: String) = owner.javaClass.getDeclaredField("$name\$delegate").apply { isAccessible = true }.get(owner) as MutableState<T>

    private fun session(scope: CoroutineScope, progress: NetworkProgress? = null, running: Boolean = false, message: String = "", seconds: Long = 0, extraSubnet: String = ""): ScanSession =
        quietSession(scope).also {
            stateOf<NetworkProgress?>(it, "progress").value = progress
            stateOf<Boolean>(it, "running").value = running
            stateOf<String>(it, "message").value = message
            stateOf<Long>(it, "durationSeconds").value = seconds
            it.extraSubnet = extraSubnet
        }

    private class Case(val name: String, val session: ScanSession)

    private fun cases(scope: CoroutineScope): List<Case> {
        val one = NetworkPlan(listOf(lan))
        val three = NetworkPlan(listOf(lan, second, third) + skippedNetworks)
        val all = proxmox + services
        val dup = pve("pve-east", "198.51.100.21", 1)
        return listOf(
            Case("01-ready", session(scope)),
            Case("02-scanning", session(scope, NetworkProgress(one, 100, "192.0.2.100", all.filter { it.ip.substringAfterLast('.').toInt() <= 100 }.take(5)), running = true, seconds = 14)),
            Case("03-done-many", session(scope, NetworkProgress(one, 254, hits = all, finished = true), seconds = 31)),
            Case("04-done-none", session(scope, NetworkProgress(one, 254, hits = emptyList(), finished = true), seconds = 31)),
            Case("05-cancelled", session(scope, NetworkProgress(one, 120, hits = all.take(3), finished = false), message = "Cancelled", seconds = 17)),
            Case("06-three-networks", session(scope, NetworkProgress(three, 762, hits = all + dup.copy(subject = "CN=pve-east.example.test,O=Proxmox Virtual Environment,OU=PVE Cluster Node"), finished = true), seconds = 92,
                extraSubnet = "192.168.20.0/22")),
            Case("07-failed", session(scope, null, message = "Scan failed: SocketException")),
            Case("08-same-server-twice", session(scope, NetworkProgress(NetworkPlan(listOf(lan, second)), 508, hits = listOf(proxmox[0], dup, proxmox[1]) + services.take(3), finished = true), seconds = 58)),
        )
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 1. Pictures
    // ---------------------------------------------------------------------------------------------------------------

    @Test fun pictures() {
        val dir = File(System.getenv("RM_SCAN_AUDIT_SHOTS") ?: return).apply { mkdirs() }
        val home = System.getProperty("user.home")
        val sandbox = Files.createTempDirectory("ringmaster-scan-audit-home")
        System.setProperty("user.home", sandbox.toString())
        try { runSceneTest { renderAll(dir) } } finally {
            System.setProperty("user.home", home)
            sandbox.toFile().deleteRecursively()
            Appearance.preview(false)
        }
    }

    private suspend fun renderAll(dir: File) {
        val context = currentCoroutineContext()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val workspace = Workspace(scope).also { it.exploreDemo() }
        delay(300)
        val state = workspace.state.value
        val all = cases(scope)
        fun window(w: Int, h: Int, page: @Composable () -> Unit) = ImageComposeScene(w, h, coroutineContext = context) {
            MaterialTheme(colorScheme = ringColors) { CompositionLocalProvider(LocalReduceMotion provides true) {
                Surface(Modifier.fillMaxSize(), color = Tech.Canvas, contentColor = Tech.Ink) { Column {
                    Row(Modifier.fillMaxWidth().background(Tech.Raised).height(32.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("RingMASTER", Modifier.weight(1f), color = Tech.Amber)
                        WindowControl(WindowGlyph.Minimize) {}; WindowControl(WindowGlyph.Maximize) {}; WindowControl(WindowGlyph.Close) {}
                    }
                    Row(Modifier.weight(1f)) {
                        NavigationRail(state, "Scan", "Windows 11", workspace, initialExpanded = false) {}
                        Column(Modifier.weight(1f).fillMaxHeight().padding(16.dp), verticalArrangement = Arrangement.spacedBy(Tech.Space8)) {
                            Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("WORKSPACE / SCAN", fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 1.5.sp, color = Tech.Muted)
                                Text("Network scan", Modifier.weight(1f), fontSize = Tech.Title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            HorizontalDivider(color = Tech.Edge)
                            Box(Modifier.weight(1f).fillMaxWidth()) { page() }
                        }
                    }
                } }
            } }
        }
        suspend fun shot(name: String, w: Int, h: Int, c: Case, scroll: Int = 0) {
            val scene = window(w, h) { ScanScreen(c.session, emptyList(), initialScroll = scroll) {} }
            try {
                var t = 0L
                repeat(14) { t += 50_000_000L; scene.render(t).close(); yield() }
                scene.render(t + 16_000_000L).use { image -> File(dir, "$name.png").writeBytes(image.encodeToData()!!.bytes) }
            } finally { scene.close() }
        }
        val sizes = listOf(1280 to 720, 1920 to 1080)
        Appearance.preview(false)
        for (c in all) for ((w, h) in sizes) shot("dark-${c.name}-${w}x$h", w, h, c)
        // The same pages, scrolled to where the results are, and the whole page on one tall picture.
        for (c in all.filter { it.name in setOf("03-done-many", "06-three-networks") }) {
            shot("dark-${c.name}-1280x720-scrolled", 1280, 720, c, scroll = 700)
            shot("dark-${c.name}-1280-full", 1280, 2600, c)
            shot("dark-${c.name}-1920-full", 1920, 2300, c)
        }
        shot("dark-03-done-many-960x640", 960, 640, all.first { it.name == "03-done-many" })
        shot("dark-06-three-networks-960x640", 960, 640, all.first { it.name == "06-three-networks" })
        val looks = listOf<Pair<String, () -> Unit>>(
            "light" to { Appearance.preview(false.not()) },
            "hc-black" to { Appearance.preview(ThemeMode.HighContrast) },
            "hc-white" to { Appearance.previewSystemContrast(null, light = true) },
            "showcase" to { Appearance.preview(false, showcase = true) },
        )
        for ((look, apply) in looks) {
            apply()
            for (c in all.filter { it.name in setOf("02-scanning", "03-done-many", "06-three-networks", "05-cancelled") }) shot("$look-${c.name}-1280x720", 1280, 720, c)
            shot("$look-03-done-many-1280-full", 1280, 2600, all.first { it.name == "03-done-many" })
        }
        Appearance.preview(false)
        workspace.close(); scope.cancel()
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 2. Keyboard path
    // ---------------------------------------------------------------------------------------------------------------

    private fun describe(node: androidx.compose.ui.semantics.SemanticsNode?): String {
        node ?: return "(nothing focused)"
        val texts = mutableListOf<String>()
        fun walk(n: androidx.compose.ui.semantics.SemanticsNode) {
            n.config.getOrNull(SemanticsProperties.Text)?.forEach { texts += it.text }
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.forEach { texts += it }
            n.children.forEach(::walk)
        }
        walk(node)
        return texts.joinToString(" | ").ifBlank { "(text field or unlabelled control)" }
    }

    /**
     * F-P1, inverted. The keyboard path of the finished page, in Tab order: the info toggle, Start scan, the subnet field (Cancel is off
     * while nothing runs), "Why isn't it here?", Change networks, the three filters, then each card's fingerprint, Use, Web UI, Copy
     * address and Copy fingerprint (the service rows' own links are walked in ScanLinksSceneTest). The page takes the keyboard when it
     * opens, so F5 and Enter reach it without a click (ScanPageStatesTest).
     */
    @Test fun audit_p1_theKeyboardReachesTheToolbarTheFiltersAndEveryCardAction() = runSceneTest {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val c = cases(scope).first { it.name == "03-done-many" }
        val scene = ImageComposeScene(1280, 720, coroutineContext = coroutineContext) {
            MaterialTheme(colorScheme = ringColors) { CompositionLocalProvider(LocalReduceMotion provides true) {
                Surface(Modifier.fillMaxSize(), color = Tech.Canvas, contentColor = Tech.Ink) { Box(Modifier.padding(16.dp)) { ScanScreen(c.session, emptyList()) {} } }
            } }
        }
        val ui = CreateScene(scene)
        try {
            ui.frames()
            val order = mutableListOf<String>()
            repeat(14) {
                ui.key(Key.Tab)
                order += describe(ui.nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true })
            }
            val first = order.map { it.uppercase() }
            assertEquals(listOf("SCAN & TRUST INFO", "START SCAN", "(TEXT FIELD OR UNLABELLED CONTROL)", "WHY ISN'T IT HERE?", "CHANGE NETWORKS"), first.take(5), order.toString())
            assertTrue(first.drop(5).take(3).all { it.startsWith("ALL") || it.startsWith("PROXMOX") || it.startsWith("SERVICES") }, order.toString())
            assertTrue(first.any { it.startsWith("SHA-256") } && first.any { it == "COPY ADDRESS" } && first.any { it == "COPY FINGERPRINT" } && first.any { it == "WEB UI" } && first.any { it == "USE" }, order.toString())
            assertTrue(first.none { "CANCEL" in it || "WEB PAGE" in it || "192.0.2.1" in it }, order.toString())
            System.getenv("RM_SCAN_AUDIT_SHOTS")?.let { File(it).apply { mkdirs() }.resolve("tab-order.txt").writeText(order.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")) }
        } finally { scene.close(); scope.cancel() }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 3. Showcase (the mode for screenshots and recordings: identifying text is blurred)
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * F-S1, inverted. In Showcase the hosts' names and addresses were blurred but the NETWORKS chips (this computer's networks and the
     * adapters' descriptions), the typed subnet, its note and the certificate fingerprint on each card were drawn as they were, though
     * the Desk treats a fingerprint as identity. Now the page hides all of them: not one network, address, adapter or fingerprint is
     * left in what is read from the screen.
     */
    @Test fun audit_s1_showcaseHidesNetworkChipsTheSubnetAddressesAndFingerprints() = runSceneTest {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val c = cases(scope).first { it.name == "06-three-networks" }
        Appearance.preview(false, showcase = true)
        val scene = ImageComposeScene(1920, 1080, coroutineContext = coroutineContext) {
            MaterialTheme(colorScheme = ringColors) { CompositionLocalProvider(LocalReduceMotion provides true) {
                Surface(Modifier.fillMaxSize(), color = Tech.Canvas, contentColor = Tech.Ink) { Box(Modifier.padding(16.dp)) { ScanScreen(c.session, emptyList()) {} } }
            } }
        }
        val ui = CreateScene(scene)
        try {
            ui.frames()
            fun textOf(n: androidx.compose.ui.semantics.SemanticsNode) = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
            val nodes = ui.nodes()
            // Hidden: a hidden node loses its text and is announced as "Hidden", so none of it is in the tree.
            val hidden = nodes.count { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Hidden") == true }
            assertTrue(hidden >= 20, "chips, rows, cards, fingerprints and the field are hidden: $hidden")
            val shown = nodes.flatMap { n -> listOf(textOf(n)) + n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() + listOfNotNull(n.config.getOrNull(SemanticsProperties.EditableText)?.text) }
            for (secret in listOf("192.0.2.", "198.51.100.", "203.0.113.", "192.168.20", "Intel(R) Ethernet Connection", "Realtek", "Wi-Fi 6E", "SHA-256", "Hyper-V", "Tailscale Tunnel", "Npcap", "pve-east"))
                assertTrue(shown.none { secret in it }, "$secret is readable in Showcase: " + shown.firstOrNull { secret in it })
            // What is not an identity stays: the counts and the words of the page.
            assertTrue(shown.any { it.startsWith("NETWORKS") } && shown.any { "addresses" in it && "Proxmox" in it }, shown.toString())
        } finally { scene.close(); scope.cancel(); Appearance.preview(false) }
    }
}
