@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import local.ringleader.core.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * Open SSH drawn in the scene harness (1280x720, every look, Showcase): on a node beside Shell, on a guest beside Console,
 * on a VPN peer row, the dialog, and the muted line where there is no button. Invented data only. RINGMASTER_VPN_SHOTS=<folder>
 * saves the pictures as ssh-*.png.
 */
class OpenSshScenesTest {
    private val v = VpnScreenScenesTest
    @AfterTest fun reset() { Appearance.preview(false); TailnetSsh.reset() }

    /** pve-alpha (100.64.0.10) runs Tailscale SSH; web-01 (100.64.0.80) too; pve-beta does not; lab-gw routes 10.20.30.0/24. */
    private val extra = ""","w":{"ID":"w","HostName":"web-01","OS":"linux","TailscaleIPs":["100.64.0.80"],"Online":true,"Active":false,"Relay":"fra","UserID":7,
        "sshHostKeys":["ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExampleOnly","ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTY="]},
        "q":{"ID":"q","HostName":"lab-gw","OS":"linux","TailscaleIPs":["100.64.0.21"],"Online":true,"Active":true,"Relay":"fra","UserID":7,"AllowedIPs":["100.64.0.21/32","10.20.30.0/24"],"PrimaryRoutes":["10.20.30.0/24"]}"""
    private val json = v.tailnetJson(extra = extra)
    private fun fake(j: String = json) = VpnScreenScenesTest.FakeVpn(v.snapshot(j)).also { it.keysJson = j }
    /** The VPN page without the second router, so the peer rows sit in view. */
    private val peerJson = v.tailnetJson(extra = extra.substringBefore("\"q\":").trimEnd().removeSuffix(","))
    private val peerSession: (CoroutineScope) -> VpnSession = { VpnSession(it, fake(peerJson), hostActions = true, readLocalNetworks = { emptyList() }) }
    private val identities = v.identities + listOf("web-01", "app-guest", "lan-guest", "beta-guest")

    private fun guest(id: Int, name: String) = Guest("https://100.64.0.10:8006", id, name, "pve-alpha", "VM", "running")
    private fun inspected(g: Guest, vararg addresses: String) = GuestInspection(g.resource, g.node, "hour", InspectionSection(), InspectionSection(), InspectionSection(),
        InspectionSection(), InspectionSection(addresses.toList()))
    private val alpha = WorkspaceState(connected = true, address = "https://100.64.0.10:8006")
    private val beta = WorkspaceState(connected = true, address = "https://100.64.0.11:8006")

    private fun scenes(name: String, host: AppHost = AppHost(false), session: (CoroutineScope) -> VpnSession = { VpnSession(it, fake(), hostActions = true, readLocalNetworks = { emptyList() }) },
        prepare: suspend CreateScene.() -> Unit = {}, check: CreateScene.() -> Unit, content: @Composable (VpnSession) -> Unit) = runSceneTest {
        for ((look, apply) in VpnScreenScenesTest.looks) {
            apply()
            val job = SupervisorJob(coroutineContext[Job])
            val s = session(CoroutineScope(coroutineContext + job))
            TailnetSsh.reset()
            if (!host.web) TailnetSsh.session = s
            val scene = ImageComposeScene(1280, 720, coroutineContext = coroutineContext) {
                MaterialTheme(colorScheme = ringColors) {
                    CompositionLocalProvider(LocalReduceMotion provides true, LocalAppHost provides host) {
                        Surface(Modifier.fillMaxSize(), color = Tech.Canvas, contentColor = Tech.Ink) { Box(Modifier.fillMaxSize().padding(16.dp)) { content(s) } }
                    }
                }
            }
            val h = CreateScene(scene)
            try {
                var waited = 0
                while (TailnetSsh.status == null && !host.web && waited++ < 400) { h.frames(); delay(25) }
                h.frames(); h.prepare()
                val audit = auditScene(scene).filter { it.kind != AuditKind.Ellipsis }
                assertTrue(audit.isEmpty(), "$name/$look: clipped or collapsed text:\n${audit.describe()}")
                if (look == "dark") h.check()
                System.getenv("RINGMASTER_VPN_SHOTS")?.let { folder ->
                    File(folder).mkdirs()
                    scene.render(2_000_000_000).use { i -> i.encodeToData()!!.use { File(folder, "ssh-$name-$look.png").writeBytes(it.bytes) } }
                }
                if (look == "dark") {
                    Appearance.preview(ThemeMode.Dark, showcase = true); h.frames(); delay(30); h.frames()
                    val leaked = accessible(scene).filter { t -> identities.any { it in t } }
                    assertTrue(leaked.isEmpty(), "$name: Showcase leaked $leaked")
                    System.getenv("RINGMASTER_VPN_SHOTS")?.let { folder -> scene.render(3_000_000_000).use { i -> i.encodeToData()!!.use { File(folder, "ssh-$name-showcase.png").writeBytes(it.bytes) } } }
                    apply()
                }
            } finally { scene.close(); job.cancel() }
        }
    }

    private fun accessible(scene: ImageComposeScene): List<String> = buildList {
        fun visit(n: SemanticsNode) {
            addAll(n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text })
            addAll(n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty())
            n.config.getOrNull(SemanticsProperties.EditableText)?.text?.let(::add)
            n.children.forEach(::visit)
        }
        scene.semanticsOwners.forEach { visit(it.rootSemanticsNode) }
    }
    private fun CreateScene.texts() = nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text } }

    /** The node inspector's heading and action row, as InventoryScreen draws it, with the two hookup lines. */
    @Composable private fun InspectorMock(state: WorkspaceState, node: String) {
        TechPanel(Modifier.width(560.dp), padding = 12.dp) {
            Text(node, Modifier.identity(), fontSize = 16.sp)
            Spacer(Modifier.height(6.dp))
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MiniAction("Refresh", primary = true) {}
                Spacer(Modifier.weight(1f))
                MiniAction("Shell") {}
                NodeSshButton(state, node)
                MiniAction("Close") {}
            }
            NodeSshNote(state, node)
        }
    }

    /** The guest page's heading row, as GuestDetailPage draws it, with the two hookup lines. */
    @Composable private fun GuestHeaderMock(state: WorkspaceState, g: Guest) {
        Column(Modifier.width(900.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(g.name, Modifier.identity(), fontSize = 18.sp)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MiniAction("\u2039 Fleet") {}
                MiniAction("Console") {}
                GuestSshButton(state, g)
            }
            GuestSshNote(state, g)
        }
    }

    @Test fun nodeShowsOpenSshBesideShellOrSaysWhyNot() = scenes("node", check = {
        val t = texts()
        assertEquals(1, t.count { it.equals("Open SSH", true) }, "only pve-alpha runs Tailscale SSH: $t")
        assertTrue(t.any { it.startsWith("No Open SSH: pve-beta does not run Tailscale SSH") }, "$t")
        assertTrue(t.any { it.equals("Shell", true) }, "the Proxmox shell stays")
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { InspectorMock(alpha, "pve-alpha"); InspectorMock(beta, "pve-beta") }
    }

    @Test fun theNodeDialogDefaultsToRootAndExplainsTheCheck() = scenes("node-dialog", prepare = { click("Open SSH") }, check = {
        val t = texts()
        assertTrue("Open SSH" in t && SSH_ACTION_HELP in t, "$t")
        assertTrue(nodes().any { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "root" }, "root for a Proxmox node")
        assertTrue(t.any { it.contains("checked against the 1 key your tailnet publishes for it (ssh-ed25519)") }, "$t")
        assertTrue(SSH_CHECK_MODE_NOTE in t)
        assertTrue(t.any { it.equals("Open terminal", true) })
    }) { InspectorMock(alpha, "pve-alpha") }

    @Test fun guestsShowOpenSshOrTheReason() {
        val app = guest(101, "app-guest"); val lan = guest(102, "lan-guest"); val betaGuest = guest(103, "beta-guest")
        scenes("guest", check = {
            val t = texts()
            assertEquals(1, t.count { it.equals("Open SSH", true) }, "$t")
            assertTrue(t.any { it.startsWith("No Open SSH: this server is reached through the subnet router lab-gw") }, "$t")
            assertTrue(t.any { it.startsWith("No Open SSH: pve-beta does not run Tailscale SSH") }, "$t")
        }) {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                GuestHeaderMock(alpha.copy(inspection = inspected(app, "192.168.1.30/24", "100.64.0.80")), app)
                GuestHeaderMock(alpha.copy(inspection = inspected(lan, "10.20.30.15")), lan)
                GuestHeaderMock(alpha.copy(inspection = inspected(betaGuest, "100.64.0.11 (Tailscale)")), betaGuest)
            }
        }
    }

    @Test fun theGuestDialogStartsEmptyAndRemembersTheUserInMemory() = scenes("guest-dialog", prepare = { click("Open SSH") }, check = {
        assertTrue(nodes().none { it.config.getOrNull(SemanticsProperties.EditableText)?.text?.isNotEmpty() == true }, "empty until typed")
        assertTrue(texts().any { it.contains("2 keys your tailnet publishes for it (ssh-ed25519, ecdsa-sha2-nistp256)") }, "${texts()}")
    }) {
        val app = guest(101, "app-guest")
        GuestHeaderMock(alpha.copy(inspection = inspected(app, "100.64.0.80")), app)
    }

    @Test fun peerRowsOfferOpenSshOnlyForPeersWithKeys() = scenes("peer-row", session = peerSession, check = {
        val t = texts()
        assertEquals(2, t.count { it.equals("Open SSH", true) }, "pve-alpha and web-01: $t")
        assertTrue(t.any { it.startsWith("Open SSH ($SSH_ACTION_HELP) is offered for online peers that run Tailscale SSH") }, "$t")
    }) { s -> VpnScreen(s, "https://100.64.0.10:8006", emptyList()) {} }

    @Test fun thePeerDialogNeedsAUserBeforeOpening() = scenes("peer-dialog", session = peerSession, prepare = { click("Open SSH") }, check = {
        val t = texts()
        assertTrue(t.any { it.equals("Open terminal", true) })
        assertTrue("Type the user to sign in as." in t, "$t")
    }) { s -> VpnScreen(s, "https://100.64.0.10:8006", emptyList()) {} }

    @Test fun browserModeOffersNoTerminalOnTheHost() = scenes("browser", host = AppHost(web = true, hostActions = true), session = peerSession, check = {
        val t = texts()
        assertFalse(t.any { it.equals("Open SSH", true) }, "$t")
        assertTrue(t.any { it.startsWith("Open SSH starts a terminal on the computer RingMASTER runs on") }, "$t")
    }) { s -> VpnScreen(s, "https://100.64.0.10:8006", emptyList()) {} }

    @Test fun openingReadsKeysOnceWritesTheFileAndStartsTheTerminalOnce() = runSceneTest {
        val dir = Files.createTempDirectory("ssh-scene")
        val started = mutableListOf<List<String>>()
        val wt = "D:\\People\\alex\\AppData\\Local\\Microsoft\\WindowsApps\\wt.exe"
        val ssh = "C:\\Windows\\System32\\OpenSSH\\ssh.exe"
        val env = mapOf("SystemRoot" to "C:\\Windows", "LOCALAPPDATA" to "D:\\People\\alex\\AppData\\Local")
        TailnetSsh.environment = { SshLaunchEnvironment(true, env::get, { it == wt || it == ssh }, { null }, dir, SshKnownHostsStore(dir.resolve("kh")), { started += it; null }) }
        val fake = fake()
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        TailnetSsh.session = VpnSession(scope, fake, hostActions = true, readLocalNetworks = { emptyList() })
        val scene = ImageComposeScene(1280, 720, coroutineContext = coroutineContext) {
            MaterialTheme(colorScheme = ringColors) { CompositionLocalProvider(LocalReduceMotion provides true) { Surface(Modifier.fillMaxSize()) { InspectorMock(alpha, "pve-alpha") } } }
        }
        val h = CreateScene(scene)
        try {
            var waited = 0
            while (TailnetSsh.status == null && waited++ < 400) { h.frames(); delay(25) }
            h.frames()
            h.click("Open SSH")
            assertEquals(0, fake.keyReads, "no key read before Open terminal")
            h.click("Open terminal")
            waited = 0
            while (started.isEmpty() && waited++ < 400) { h.frames(); delay(25) }
            h.frames()
            assertEquals(1, fake.keyReads); assertEquals(1, started.size)
            val command = started.single()
            assertEquals(listOf(wt, "new-tab", "--title", "SSH", ssh, "-l", "root"), command.take(7))
            assertEquals("100.64.0.10", command.last())
            assertTrue("HostKeyAlias=100.64.0.10" in command, "the fixture peer has no DNS name: the address is the alias")
            val file = Files.list(dir.resolve("kh")).use { it.toList() }.single()
            assertEquals("100.64.0.10 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExampleOnly\n", Files.readString(file))
            assertTrue(h.texts().any { it == "Opened in Windows Terminal." }, "${h.texts()}")
            assertEquals("root", TailnetSsh.users["node:https://100.64.0.10:8006:pve-alpha"], "remembered in memory for this node")
        } finally { scene.close(); scope.cancel(); dir.toFile().deleteRecursively() }
    }
}
