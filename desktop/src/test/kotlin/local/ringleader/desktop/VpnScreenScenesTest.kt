@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import local.ringleader.core.*
import java.io.File
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

/**
 * The VPN page drawn in the scene harness at 1280x720 for the Tailscale/WireGuard fixes of 2026-11-16: a normal tailnet,
 * userspace mode, peers with every new tag, a sign-in link, a search that skipped peers and the WireGuard import
 * confirmation. Each scene is audited for clipped text, drawn in high contrast and under a system contrast theme, and
 * checked in Showcase. RINGMASTER_VPN_SHOTS=<folder> saves the pictures. Invented data only (example.ts.net,
 * 100.64.0.x, documentation ranges).
 */
class VpnScreenScenesTest {
    @AfterTest fun reset() = Appearance.preview(false)

    internal class FakeVpn(var snap: VpnSnapshot, override val platform: String = "Windows", val policy: WireGuardScriptPolicy = WireGuardScriptPolicy.Unknown,
        var accept: AcceptRoutesReading? = null) : VpnBackend {
        override fun wireGuardScriptPolicy() = policy
        /** accept-routes reads and changes, for the routes scenes. */
        var acceptReads = 0
        val acceptChanges = mutableListOf<Boolean>()
        override fun tailscaleStatus() = snap.tailscale
        override fun acceptRoutes(status: TailscaleStatus?, local: List<LocalNetwork>): AcceptRoutesReading { acceptReads++; return accept ?: AcceptRoutesReading.unknown("Scene.") }
        override fun setAcceptRoutes(accept: Boolean) { acceptChanges += accept; this.accept = this.accept?.copy(answer = if (accept) AcceptRoutes.Accepted else AcceptRoutes.NotAccepted) }
        /** The status JSON Open SSH reads its keys from; counted, since the read must happen at launch only. */
        var keysJson: String? = null
        var keyReads = 0
        override fun tailscaleSshHostKeys(peerId: String): SshPeerKeys? { keyReads++; return keysJson?.let { parsePeerSshHostKeys(it, peerId) } }
        override fun snapshot() = snap
        override fun routeTo(host: String, snapshot: VpnSnapshot?) = VpnRoute(host, host, "Ethernet 2", null, null, "192.0.2.20")
        override fun setTunnel(tunnel: VpnTunnel, up: Boolean) = error("scene")
        override fun setExitNode(peer: TailscalePeer?, allowLanAccess: Boolean) = error("scene")
        override fun importWireGuard(config: Path, name: String) = error("scene")
        override fun removeWireGuard(tunnel: VpnTunnel) = error("scene")
    }

    companion object {
        /** Every identity the scenes carry; none may reach the accessibility tree in Showcase. */
        val identities = listOf("pve-alpha", "pve-beta", "subnet-gw", "laptop-07", "friend-nas", "old-pbs", "se-sto-wg-001", "exit-gw", "desk-01",
            "example.ts.net", "100.64.0.", "203.0.113.9", "lab-gw", "10.20.30.", "192.168.77.", "Scene LAN", "198.51.100.7", "OFFICE", "10.8.0.2", "login.example.com", "1a2b3c4d5e", "Ethernet 2", "scene-user")

        fun at(days: Long) = Instant.now().plusSeconds(days * 86_400 + 3_600).toString()

        fun tailnetJson(top: String = """"TUN":true,""", backend: String = "Running", extra: String = "") = """
            {"Version":"1.104.6","BackendState":"$backend",$top
             "CurrentTailnet":{"Name":"example.ts.net","MagicDNSSuffix":"example.ts.net","MagicDNSEnabled":true},
             "Self":{"ID":"self","HostName":"desk-01","DNSName":"desk-01.example.ts.net.","TailscaleIPs":["100.64.0.1"],"Online":true,"UserID":7},
             "Peer":{
              "a":{"ID":"a","HostName":"pve-alpha","OS":"linux","TailscaleIPs":["100.64.0.10"],"Online":true,"Active":true,"CurAddr":"203.0.113.9:41641","Relay":"fra","UserID":7,"KeyExpiry":"${at(200)}","sshHostKeys":["ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExampleOnly"]},
              "b":{"ID":"b","HostName":"pve-beta","OS":"linux","TailscaleIPs":["100.64.0.11"],"Online":true,"Active":true,"CurAddr":"","Relay":"fra","PeerRelay":"198.51.100.7:40000:12","UserID":7,"KeyExpiry":"${at(5)}"},
              "r":{"ID":"r","HostName":"subnet-gw","OS":"linux","TailscaleIPs":["100.64.0.20"],"Online":true,"Active":true,"Relay":"fra","UserID":7,"AllowedIPs":["100.64.0.20/32","192.0.2.0/24"],"PrimaryRoutes":["192.0.2.0/24"]},
              "l":{"ID":"l","HostName":"laptop-07","OS":"windows","TailscaleIPs":["100.64.0.30"],"Online":true,"Active":false,"Relay":"fra","UserID":7},
              "s":{"ID":"s","HostName":"friend-nas","OS":"linux","TailscaleIPs":["100.64.0.40"],"Online":true,"Active":false,"Relay":"ams","ShareeNode":true,"UserID":99},
              "o":{"ID":"o","HostName":"old-pbs","OS":"linux","TailscaleIPs":["100.64.0.50"],"Online":false,"Expired":true,"LastSeen":"${at(-12)}","UserID":7},
              "m":{"ID":"m","HostName":"se-sto-wg-001","DNSName":"se-sto-wg-001.example.ts.net.","OS":"linux","TailscaleIPs":["100.64.0.60"],"Online":true,"ExitNodeOption":true,"Location":{"Country":"Sweden","CountryCode":"SE","City":"Stockholm"}},
              "x":{"ID":"x","HostName":"exit-gw","OS":"linux","TailscaleIPs":["100.64.0.70"],"Online":true,"Active":true,"CurAddr":"203.0.113.9:41642","ExitNodeOption":true,"UserID":7}
              $extra
             },"Health":[]}
        """.trimIndent()

        fun snapshot(json: String = tailnetJson(), state: VpnState = VpnState.Up): VpnSnapshot {
            val ts = parseTailscaleStatus(json)
            return VpnSnapshot(Instant.now(), listOf(
                VpnTunnel("tailscale", VpnKind.Tailscale, "Tailscale", state, "Tailscale", listOf("100.64.0.1"), "6 of 8 peers online", canToggle = state != VpnState.NeedsLogin),
                VpnTunnel("wg:office", VpnKind.WireGuard, "office", VpnState.Down, "office", listOf("10.8.0.2/24"), canToggle = true, toggleNeedsAdmin = true, canRemove = true),
            ), true, ts, true, emptyList(), emptyList(), tailscaleCli = "D:\\People\\scene-user\\bin\\tailscale.exe")
        }

        val looks = listOf<Pair<String, () -> Unit>>(
            "dark" to { Appearance.preview(ThemeMode.Dark) },
            "light" to { Appearance.preview(ThemeMode.Light) },
            "contrast" to { Appearance.preview(ThemeMode.HighContrast) },
            "contrast-desert" to { Appearance.previewSystemContrast(SystemContrastColors(Color(0xFFFFFAEF), Color(0xFF3D3D3D), Color(0xFF903909), Color(0xFFFFF5E3), Color(0xFF1C5E75)), light = true) },
        )
    }

    /** A second router with a private network (the route list is private ranges only). */
    private val labRouter = ""","q":{"ID":"q","HostName":"lab-gw","OS":"linux","TailscaleIPs":["100.64.0.21"],"Online":true,"Active":true,"Relay":"fra","UserID":7,"AllowedIPs":["100.64.0.21/32","10.20.30.0/24"],"PrimaryRoutes":["10.20.30.0/24"]},
        "q2":{"ID":"q2","HostName":"lab-gw-2","OS":"linux","TailscaleIPs":["100.64.0.22"],"Online":false,"UserID":7,"AllowedIPs":["100.64.0.22/32","192.168.77.0/24"],"PrimaryRoutes":["192.168.77.0/24"]}"""
    private fun routed() = snapshot(tailnetJson(extra = labRouter))
    private val noLocal: () -> List<LocalNetwork> = { emptyList() }

    /** Draws [content] at 1280x720 in each look, audits it and saves it; [check] runs on the first (dark) scene. */
    private fun scenes(name: String, session: (CoroutineScope) -> VpnSession, host: AppHost = AppHost(false), prepare: suspend CreateScene.(VpnSession) -> Unit = {},
        check: CreateScene.(VpnSession) -> Unit = {}) = runSceneTest {
        for ((look, apply) in looks) {
            apply()
            val job = SupervisorJob(coroutineContext[Job])
            val scope = CoroutineScope(coroutineContext + job)
            val s = session(scope)
            val scene = ImageComposeScene(1280, 720, coroutineContext = coroutineContext) {
                MaterialTheme(colorScheme = ringColors) {
                    CompositionLocalProvider(LocalReduceMotion provides true, LocalAppHost provides host) {
                        Surface(Modifier.fillMaxSize(), color = Tech.Canvas, contentColor = Tech.Ink) {
                            Box(Modifier.fillMaxSize().padding(16.dp)) { VpnScreen(s, "https://100.64.0.10:8006", emptyList()) {} }
                        }
                    }
                }
            }
            val h = CreateScene(scene)
            try {
                var waited = 0
                while ((s.snapshot == null || s.route == null) && waited++ < 80) { h.frames(); delay(25) }
                assertNotNull(s.snapshot, "$name: the page read its snapshot")
                h.frames()
                h.prepare(s)
                val audit = auditScene(scene).filter { it.kind != AuditKind.Ellipsis }
                assertTrue(audit.isEmpty(), "$name/$look: clipped or collapsed text:\n${audit.describe()}")
                if (look == "dark") h.check(s)
                System.getenv("RINGMASTER_VPN_SHOTS")?.let { folder ->
                    File(folder).mkdirs()
                    scene.render(2_000_000_000).use { image -> image.encodeToData()!!.use { File(folder, "vpn-$name-$look.png").writeBytes(it.bytes) } }
                }
                if (look == "dark") {
                    // Showcase: the same scene leaks no peer name, address, endpoint or relay address.
                    Appearance.preview(ThemeMode.Dark, showcase = true); h.frames(); delay(30); h.frames()
                    val leaked = accessible(scene).filter { t -> identities.any { it in t } }
                    assertTrue(leaked.isEmpty(), "$name: Showcase leaked $leaked")
                    System.getenv("RINGMASTER_VPN_SHOTS")?.let { folder ->
                        scene.render(3_000_000_000).use { image -> image.encodeToData()!!.use { File(folder, "vpn-$name-showcase.png").writeBytes(it.bytes) } }
                    }
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

    @Test fun normalTailnetWithEveryPeerTag() = scenes("tailnet", { VpnSession(it, FakeVpn(snapshot()), hostActions = true) }) {
        val t = texts()
        for (label in listOf("direct", "peer relay", "DERP fra", "idle", "offline", "Key expired", "Expires in 5 d", "SSH", "Shared", "Subnet router"))
            assertTrue(t.any { it.equals(label, ignoreCase = true) }, "missing $label in $t")
        assertFalse(t.any { it.startsWith("relay ") }, "the old table label is gone")
        assertFalse(TAILSCALE_USERSPACE_NOTE in t)
        assertTrue("Tailscale CLI:" in t && "D:\\People\\scene-user\\bin\\tailscale.exe" in t, "the page says which binary was used")
    }

    @Test fun userspaceModeIsSaidBeforeASearch() = scenes("userspace", { VpnSession(it, FakeVpn(snapshot(tailnetJson(top = """"TUN":false,"""))), hostActions = true) }) {
        val t = texts()
        assertTrue(TAILSCALE_USERSPACE_NOTE in t, "$t")
        assertTrue("Not known: Tailscale runs in userspace mode" in t, "the route banner withholds 'Ordinary network'")
        assertFalse(t.any { it.startsWith("Ordinary network") })
    }

    private val signIn = "https://login.example.com/a/1a2b3c4d5e"
    private fun needsLogin() = snapshot(tailnetJson(backend = "NeedsLogin", top = """"TUN":true,"AuthURL":"$signIn","""), VpnState.NeedsLogin)

    @Test fun needsLoginOffersTheSignInLinkForTheUserToOpen() = scenes("needs-login", { VpnSession(it, FakeVpn(needsLogin()), hostActions = true) },
        prepare = { s ->
            // A Bring up that ended in a sign-in wait: the full sentence is shown, not only the one-line toolbar.
            s.act("tailscale", "100.64.0.10", "done") { error(tailscaleChangeFailure("VPN command timed out.", true, TAILSCALE_UP_SECONDS, it.snapshot().tailscale, true)) }
            var waited = 0
            while (s.busy != null && waited++ < 80) { frames(); delay(25) }
            frames()
        }) { s ->
        val t = texts()
        assertTrue(t.any { it.contains("Open the sign-in link below in your browser") }, "$t")
        assertTrue(t.any { it.equals("Open sign-in link", ignoreCase = true) })
        assertTrue(t.any { it.equals("Copy sign-in link", ignoreCase = true) })
        assertTrue(signIn in t, "the link itself is shown on the desktop")
        assertEquals("Tailscale needs a sign-in before it can connect. Open the sign-in link on this page.", s.message)
        assertEquals(1, t.count { it == s.message }, "the full sentence once, under the toolbar")
        assertTrue("The change did not complete." in t)
        assertFalse(t.any { it.contains("Open the Tailscale app") }, "the old advice is gone")
    }

    @Test fun browserModeOffersOnlyACopyOfTheSignInLink() = scenes("needs-login-browser", { VpnSession(it, FakeVpn(needsLogin(), platform = "Linux"), hostActions = true) },
        host = AppHost(web = true, hostActions = true)) {
        val t = texts()
        assertTrue(t.any { it.equals("Copy sign-in link", ignoreCase = true) })
        assertFalse(t.any { it.equals("Open sign-in link", ignoreCase = true) }, "opening would happen on the RingMASTER host")
        assertFalse(t.any { signIn in it }, "browser mode does not print the link")
    }

    @Test fun browserModeWithoutHostActionsOffersNoSignInLink() = scenes("needs-login-browser-read-only", { VpnSession(it, FakeVpn(needsLogin(), platform = "Linux"), hostActions = false) },
        host = AppHost(web = true, hostActions = false)) {
        val t = texts()
        assertFalse(t.any { it.contains("sign-in link", ignoreCase = true) && it.length < 30 }, "no open or copy action: $t")
        assertTrue(SIGN_IN_HOST_ACTIONS_REASON in t)
        assertTrue(t.any { it.contains("sudo tailscale login") }, "the advice for no link: $t")
    }

    /** Answers PVE on 8006 for one peer; records every address probed. */
    private class Probe { val seen = mutableSetOf<String>()
        val scanner = NetworkScanner { ip, port -> synchronized(seen) { seen += ip }; if (ip == "100.64.0.10" && port == 8006) listOf(ScanHit(ip, port, true, ScanKind.PVE)) else emptyList() } }

    private suspend fun CreateScene.search(s: VpnSession) {
        click("Find Proxmox")
        var waited = 0
        while ((s.finding || !s.searched) && waited++ < 120) { frames(); delay(25) }
        frames()
    }

    @Test fun aSearchSaysWhichPeersItSkippedAndWhy() {
        val probes = mutableListOf<Probe>()
        scenes("search-skipped", { Probe().also(probes::add).let { p -> VpnSession(it, FakeVpn(snapshot()), hostActions = true, scanner = p.scanner) } }, prepare = { s -> search(s) }) { s ->
            val t = texts()
            assertTrue(t.any { it.equals("Include machines shared by others", ignoreCase = true) }, "$t")
            assertFalse(s.includeShared, "off by default")
            val note = "Not searched: 1 location-based exit node (a VPN provider's servers, never searched); 1 machine shared by others or owned by another user (tick the box to include them)."
            assertTrue(note in t, "$t")
            assertTrue(t.any { it == "PROXMOX ON THE TAILNET \u00b7 1" })
        }
        for (p in probes) {
            assertFalse("100.64.0.60" in p.seen, "the Mullvad node is never probed")
            assertFalse("100.64.0.40" in p.seen, "the shared node is not probed by default")
            assertTrue("100.64.0.10" in p.seen)
        }
    }

    @Test fun includingSharedMachinesProbesThemButNeverMullvad() {
        val probes = mutableListOf<Probe>()
        scenes("search-included", { Probe().also(probes::add).let { p -> VpnSession(it, FakeVpn(snapshot()), hostActions = true, scanner = p.scanner) } },
            prepare = { s -> click("Include machines shared by others"); assertTrue(s.includeShared); search(s) }) { s ->
            assertEquals(0, s.searchPlan?.skippedShared)
            assertTrue(texts().any { it == "Not searched: 1 location-based exit node (a VPN provider's servers, never searched)." })
        }
        for (p in probes) { assertTrue("100.64.0.40" in p.seen); assertFalse("100.64.0.60" in p.seen) }
        // A new session (a restart) starts with the box off again.
        assertFalse(VpnSession(CoroutineScope(Dispatchers.Unconfined), FakeVpn(snapshot())).includeShared)
    }

    private fun scriptFile(): Path = java.nio.file.Files.createTempFile("office", ".conf").also {
        java.nio.file.Files.writeString(it, "[Interface]\nPrivateKey = ${"A".repeat(43)}=\npostup = iptables -t nat -A POSTROUTING -j MASQUERADE\n" +
            "PreDown = /opt/secret-script.sh\nTable = off\n[Peer]\nPublicKey = ${"A".repeat(43)}=\nAllowedIPs = 0.0.0.0/0, ::/0\nEndpoint = vpn.example.invalid:51820\n")
    }

    private suspend fun CreateScene.openImport() {
        click("Import WireGuard\u2026")
        var waited = 0
        while (nodes().none { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "THIS FILE ALSO" } == true } && waited++ < 120) { frames(); delay(25) }
        frames()
    }

    private fun CreateScene.importEnabled(): Boolean {
        var n: SemanticsNode? = nodes().first { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text.equals("Import", ignoreCase = true) } == true }
        while (n != null && !n.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick)) n = n.parent
        return n != null && !n.config.contains(SemanticsProperties.Disabled)
    }

    @Test fun importConfirmationListsScriptsAndAsksForLocalSystemConsent() {
        val file = scriptFile()
        try {
            scenes("import-scripts", { VpnSession(it, FakeVpn(snapshot(), policy = WireGuardScriptPolicy.RunAsSystem), hostActions = true) },
                host = object : AppHost(false) { override fun pickFile(title: String, extensions: Set<String>) = file },
                prepare = { openImport() }) {
                val t = texts()
                for (line in listOf("PostUp: a script", "PreDown: a script", "will run as Local System", "Table = off", "AllowedIPs 0.0.0.0/0", "AllowedIPs ::/0"))
                    assertTrue(t.any { it.contains(line) }, "missing $line in $t")
                for (secret in listOf("A".repeat(20), "iptables", "secret-script", "vpn.example.invalid")) assertFalse(t.any { secret in it }, "$secret shown")
                assertFalse(importEnabled(), "Import waits for the Local System box")
            }
        } finally { java.nio.file.Files.deleteIfExists(file) }
    }

    @Test fun importConsentEnablesImport() = runSceneTest {
        val file = scriptFile()
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val s = VpnSession(scope, FakeVpn(snapshot(), policy = WireGuardScriptPolicy.RunAsSystem), hostActions = true)
        val scene = ImageComposeScene(1280, 720, coroutineContext = coroutineContext) {
            MaterialTheme(colorScheme = ringColors) { CompositionLocalProvider(LocalReduceMotion provides true, LocalAppHost provides object : AppHost(false) { override fun pickFile(title: String, extensions: Set<String>) = file }) {
                Surface(Modifier.fillMaxSize()) { VpnScreen(s, "https://100.64.0.10:8006", emptyList()) {} }
            } }
        }
        val h = CreateScene(scene)
        try {
            var waited = 0
            while (s.snapshot == null && waited++ < 80) { h.frames(); delay(25) }
            h.openImport()
            assertFalse(h.importEnabled())
            h.click("I understand these scripts will run as Local System")
            assertTrue(h.importEnabled())
        } finally { scene.close(); scope.cancel(); java.nio.file.Files.deleteIfExists(file) }
    }

    @Test fun linuxImportSaysNetworkManagerDropsScripts() {
        val file = scriptFile()
        try {
            scenes("import-scripts-linux", { VpnSession(it, FakeVpn(snapshot(), platform = "Linux", policy = WireGuardScriptPolicy.DroppedOnImport), hostActions = true) },
                host = object : AppHost(false) { override fun pickFile(title: String, extensions: Set<String>) = file },
                prepare = { openImport() }) {
                assertTrue(texts().any { it.contains("NetworkManager does not run these scripts") })
                assertTrue(importEnabled(), "no consent needed where scripts never run")
            }
        } finally { java.nio.file.Files.deleteIfExists(file) }
    }

    @Test fun anUnreadablePeerCostsOneRow() = scenes("unreadable", { VpnSession(it, FakeVpn(snapshot(tailnetJson(extra = ""","bad":null"""))), hostActions = true) }) {
        assertTrue("1 peer entry was not readable and is not listed." in texts())
    }
    // ------------------------------------------------------------------ subnet routes (2026-11-16)

    private val notAccepted = AcceptRoutesReading(AcceptRoutes.NotAccepted, AcceptRoutesSource.Preference)
    private val accepted = AcceptRoutesReading(AcceptRoutes.Accepted, AcceptRoutesSource.Preference)

    private suspend fun CreateScene.settle(s: VpnSession) { var waited = 0; while (s.acceptRoutes == null && waited++ < 400) { frames(); delay(25) }; frames() }

    @Test fun routesNotAcceptedShowTheCardAndOneAction() = scenes("routes-not-accepted",
        { VpnSession(it, FakeVpn(routed(), accept = notAccepted), hostActions = true, readLocalNetworks = noLocal) }, prepare = { settle(it) }) { s ->
        val t = texts()
        assertTrue("TAILNET NETWORKS NOT IN USE" in t, "$t")
        assertTrue(t.any { it.startsWith("Your tailnet offers these networks through lab-gw, lab-gw-2; this computer is not using them, so servers there cannot be reached from here.") }, "$t")
        assertTrue("10.20.30.0/24" in t && "192.168.77.0/24" in t, "the networks")
        assertTrue(t.any { it.equals("Router offline", true) }, "an offline router is said")
        assertEquals(1, t.count { it.equals("Use these networks on this computer", true) }, "one action")
        assertFalse(t.any { it.contains("192.0.2.0/24") }, "a documentation range is not a private network the card offers")
    }

    @Test fun theConfirmationNamesTheExactChange() = scenes("routes-confirm",
        { VpnSession(it, FakeVpn(routed(), accept = notAccepted), hostActions = true, readLocalNetworks = { listOf(LocalNetwork("Scene LAN", "192.168.77.40/24")) }) },
        prepare = { s -> settle(s); click("Use these networks on this computer") }) { s ->
        val t = texts()
        assertTrue("Use the tailnet's networks on this computer?" in t, "$t")
        assertTrue(t.any { it.contains("This runs: tailscale set --accept-routes=true") && it.contains("whole computer") }, "$t")
        assertTrue(t.any { it.contains("is also this computer's local network. Afterwards the operating system may send it through") }, "the overlap warning")
        assertTrue(t.any { it.equals("Use these networks", true) } && t.any { it.equals("Cancel", true) })
        assertEquals(emptyList(), (s.backend as FakeVpn).acceptChanges, "nothing changes before the confirmation")
    }

    @Test fun confirmingReReadsActsOnceAndReadsBack() = runSceneTest {
        val fake = FakeVpn(routed(), accept = notAccepted)
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val s = VpnSession(scope, fake, hostActions = true, readLocalNetworks = noLocal)
        val scene = ImageComposeScene(1280, 720, coroutineContext = coroutineContext) {
            MaterialTheme(colorScheme = ringColors) { CompositionLocalProvider(LocalReduceMotion provides true) {
                Surface(Modifier.fillMaxSize()) { VpnScreen(s, "https://100.64.0.10:8006", emptyList()) {} }
            } }
        }
        val h = CreateScene(scene)
        try {
            var waited = 0
            while ((s.snapshot == null || s.acceptRoutes == null) && waited++ < 400) { h.frames(); delay(25) }
            h.click("Use these networks on this computer")
            val before = fake.acceptReads
            h.click("Use these networks")
            waited = 0
            while ((s.busy != null || s.message == null) && waited++ < 120) { h.frames(); delay(25) }
            assertEquals(listOf(true), fake.acceptChanges, "one change, never retried")
            assertTrue(fake.acceptReads >= before + 2, "read again right before acting and once after")
            assertEquals("This computer now uses the tailnet's networks.", s.message); assertFalse(s.failed)
        } finally { scene.close(); scope.cancel() }
    }

    @Test fun aRefusedChangeIsReportedOnceWithoutARetry() = runSceneTest {
        val fake = object : VpnBackend by FakeVpn(routed(), accept = notAccepted) {
            var calls = 0
            override fun setAcceptRoutes(accept: Boolean) { calls++; error(tailscaleChangeFailure("Access denied: prefs write access denied", false, 30, snapshot().tailscale, false, ACCEPT_ROUTES_SUDO_COMMAND)) }
        }
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val s = VpnSession(scope, fake, hostActions = true, readLocalNetworks = noLocal)
        val routes = tailnetSubnetRoutes(s.backend!!.snapshot().tailscale)
        s.actReporting("routes", null) { b -> b.setAcceptRoutes(true); "done" }
        var waited = 0
        while (s.busy != null && waited++ < 80) { delay(25) }
        assertEquals(1, fake.calls); assertTrue(s.failed)
        assertTrue(s.message!!.contains("Run in a terminal: sudo tailscale set --accept-routes=true"), s.message)
        assertTrue(routes.isNotEmpty())
        scope.cancel()
    }

    @Test fun linuxWithoutOperatorRightsShowsTheCommandInsteadOfAButton() = scenes("routes-linux-sudo",
        { VpnSession(it, FakeVpn(routed(), platform = "Linux", accept = notAccepted.copy(operator = "", operatorKnown = true)), hostActions = true, readLocalNetworks = noLocal) },
        prepare = { settle(it) }) {
        val t = texts()
        assertTrue("sudo tailscale set --accept-routes=true" in t, "$t")
        assertTrue(t.any { it.contains("only root or Tailscale's operator may change this") })
        assertFalse(t.any { it.equals("Use these networks on this computer", true) }, "no button that would be refused")
    }

    @Test fun routesAcceptedSearchThroughTheRouter() {
        val probes = mutableListOf<Set<String>>()
        scenes("routes-accepted-search", { scope ->
            val seen = mutableSetOf<String>()
            probes += seen
            val scanner = NetworkScanner { ip, port -> synchronized(seen) { seen += ip }
                when { ip == "10.20.30.15" && port == 8006 -> listOf(ScanHit(ip, port, true, ScanKind.PVE)); ip == "100.64.0.10" && port == 8006 -> listOf(ScanHit(ip, port, true, ScanKind.PVE)); else -> emptyList() } }
            VpnSession(scope, FakeVpn(routed(), accept = accepted), hostActions = true, scanner = scanner, readLocalNetworks = noLocal)
        }, prepare = { s -> settle(s); search(s) }) { s ->
            val t = texts()
            assertTrue("USING TAILNET NETWORKS" in t, "the quiet line: $t")
            assertFalse("TAILNET NETWORKS NOT IN USE" in t)
            assertTrue(t.any { it == "PROXMOX ON THE TAILNET \u00b7 2" }, "$t")
            assertTrue("through" in t && "10.20.30.15:8006" in t, "the routed hit says through which router")
            assertTrue("Also searched through routers:" in t)
            assertEquals("10.20.30.0/24", s.services.single { it.through != null }.through!!.cidr)
        }
        for (seen in probes) { assertTrue("10.20.30.200" in seen, "the router's network is swept"); assertFalse(seen.any { it.startsWith("192.168.77.") }, "not behind an offline router") }
    }

    @Test fun anOverlapSaysWhichNetworkWins() = scenes("routes-overlap",
        { VpnSession(it, FakeVpn(routed(), accept = accepted.copy(overlaps = mapOf("10.20.30.0/24" to OverlapWinner.Local))), hostActions = true,
            readLocalNetworks = { listOf(LocalNetwork("Scene LAN", "10.20.30.40/24")) }) }, prepare = { settle(it) }) {
        val t = texts()
        assertTrue(t.any { it.startsWith("10.20.30.0/24 is also this computer's local network (Scene LAN, 10.20.30.40/24): the local network wins") }, "$t")
        assertTrue(t.any { it.equals("Local network too", true) } || "USING TAILNET NETWORKS" in t)
    }

    @Test fun notAcceptedRoutesTurnTheRouterSearchOffWithTheReason() = scenes("routes-search-off",
        { VpnSession(it, FakeVpn(routed(), accept = notAccepted), hostActions = true, scanner = Probe().scanner, readLocalNetworks = noLocal) },
        prepare = { s -> settle(s); search(s) }) { s ->
        assertTrue(texts().any { it.startsWith("Networks behind subnet routers are not searched: this computer does not use the tailnet's subnet routes") })
        assertTrue(s.searchPlan!!.subnets.isEmpty())
    }

    @Test fun theRouteBannerNamesTheSubnetRouter() = scenes("routes-banner", { scope ->
        val fake = object : VpnBackend by FakeVpn(routed(), accept = accepted) {
            override fun routeTo(host: String, snapshot: VpnSnapshot?) = VpnRoute(host, "10.20.30.15", "Tailscale", "tailscale", "Tailscale", "100.64.0.1")
        }
        VpnSession(scope, fake, hostActions = true, readLocalNetworks = noLocal)
    }) {
        assertTrue(texts().any { it == "Through Tailscale \u00b7 subnet router lab-gw" }, "${texts()}")
    }
}
