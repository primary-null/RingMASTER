@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import local.ringleader.core.*
import java.io.File
import java.time.Instant
import kotlin.test.*

/**
 * The Radios screens drawn without a window: the Map in its states (demo, empty, refused with an action, PDM, one adapter,
 * a busy fleet of 40, a guess, loading) and at 960x600, 2560x1440 and 150 % text; the Wi-Fi view (names hidden and shown,
 * refused, a guest source and its confirmation); the Fleet strip and guest mark; the node section; the guest Radios tab for
 * a VM and a container. Every scene is audited for clipped text in dark, light, pitch black, high contrast and a system
 * contrast theme, under reduced motion, and checked in Showcase for leaked names. RINGMASTER_RADIOS_SHOTS=<folder> saves
 * the pictures. Invented data only.
 */
class RadiosScenesTest {
    @AfterTest fun reset() { Appearance.preview(false); WifiNames.chosen = false; RadiosPageMemory.view = RadiosView.Map }

    private class Look(val name: String, val apply: (Float) -> Unit)

    companion object {
        private val looks = listOf(
            Look("dark") { Appearance.preview(ThemeMode.Dark, textScale = it) },
            Look("light") { Appearance.preview(ThemeMode.Light, textScale = it) },
            Look("pitch") { Appearance.preview(ThemeMode.PitchBlack, textScale = it) },
            Look("contrast") { Appearance.preview(ThemeMode.HighContrast, textScale = it) },
            Look("contrast-desert") { Appearance.previewSystemContrast(SystemContrastColors(Color(0xFFFFFAEF), Color(0xFF3D3D3D), Color(0xFF903909), Color(0xFFFFF5E3), Color(0xFF1C5E75)), light = true) },
        )
        /** Names that may not reach the accessibility tree in Showcase (compared ignoring case). */
        val identities = listOf("debian-workbench", "web-services", "backup-lab", "demo-node-a", "demo-node-b", "Harbour Lab", "Orchard-6E", "Printer-Direct",
            "02:00:00:", "busy-node-", "busy-guest-")

        /** A busy fleet: 40 adapters on this computer and six nodes, most given to guests, some likely, some guesses. */
        fun busyBoard(): RadiosBoard {
            val nodes = (1..6).map { n -> RadioHost("busy-node-$n", "busy-node-$n", RadioHostKind.Node, online = n != 5,
                notes = if (n == 6) listOf(RadioNote(RADIOS_TOKEN_USB)) else emptyList(), session = "busy") }
            val radios = ArrayList<RadioCard>()
            radios += RadioCard("local/wifi", RadioKind.WiFi, "Intel Wi-Fi 7 BE200", THIS_COMPUTER_KEY, "Intel", "8086:272b", RadioBus.Pci, scan = RadioScanRoute.ThisComputer)
            radios += RadioCard("local/bt", RadioKind.Bluetooth, "Intel Bluetooth 5.4", THIS_COMPUTER_KEY, "Intel", "8087:0036", RadioBus.Usb, scan = RadioScanRoute.ThisComputer)
            // 38 on the nodes (7, 7, 6, 6, 6, 6): three in four given to a guest on the same node, two adapters to a guest.
            var i = 0
            nodes.forEachIndexed { n, node ->
                repeat(if (n < 2) 7 else 6) { j ->
                    val kind = if (i % 3 == 0) RadioKind.WiFi else RadioKind.Bluetooth
                    val vmid = 300 + n * 10 + j / 2
                    val holder = if (j % 4 != 3) RadioHolder(RadioGuestRef("busy/VM/$vmid", vmid, "busy-guest-$vmid", if (j / 2 == 2) "Container" else "VM",
                        node.name, j / 2 != 1, ResourceId("busy", "", "VM", vmid), "busy"), if (kind == RadioKind.WiFi && j % 2 == 0) RadioVia.Mapping else RadioVia.HostDevice, "usb${j % 4}") else null
                    radios += RadioCard("${node.key}/usb/$j", kind, if (kind == RadioKind.WiFi) "Wi-Fi dongle model ${i + 1}" else "Bluetooth dongle model ${i + 1}", node.key,
                        "Example Maker", "0f%02x:%04x".format(i, 1000 + i), RadioBus.Usb, "USB ${j % 4 + 1}-${j + 1}",
                        sure = when { i % 7 == 6 -> RadioSure.Guess; i % 5 == 4 -> RadioSure.Likely; else -> RadioSure.Certain }, holder = holder)
                    i++
                }
            }
            return RadiosBoard(listOf(RadioHost(THIS_COMPUTER_KEY, THIS_COMPUTER_LABEL, RadioHostKind.ThisComputer)) + nodes, radios, readAt = Instant.parse("2026-11-16T10:00:00Z"))
        }

        val demoGuest = sampleOverview().guests.first { it.id == 120 }
        val demoContainer = sampleOverview().guests.first { it.id == 121 }
        val demoHardware = parseHardware("""{"data":{"agent":"1","ostype":"l26","usb0":"host=2357:0604","usb1":"mapping=wifi-dongle","usb2":"spice","usb3":"host=046d:c52b"}}""")
    }

    /** The guest feed of the demo, with no waiting. */
    private class QuickGuestRadios : BoardGuestRadios() {
        var probes = 0
        override fun probeRefusal(guest: Guest): RadioNote? = if (guest.kind != "VM") RadioNote(RADIOS_CT_PROBE) else null
        override suspend fun probe(guest: Guest): GuestProbeView { probes++; return demoGuestProbe() }
        override suspend fun wifiScan(guest: Guest): WifiPanel = demoWifiPanel(demoWorkbench.key)
    }

    private class Taps {
        val guests = mutableListOf<Pair<RadioGuestRef, String>>()
        val nodes = mutableListOf<RadioHost>()
        val targets = mutableListOf<RadiosTarget>()
        val nav = RadiosNav({ g, t -> guests += g to t }, { nodes += it }, { targets += it })
    }

    private fun hub(board: RadiosBoard = demoRadiosBoard(), wifi: WifiPanel = demoWifiPanel(), taps: Taps = Taps(), guests: GuestRadiosFeed = QuickGuestRadios()) =
        RadiosHub(FixedRadiosFeed(board), FixedWifiFeed(wifi), guests, taps.nav)

    /**
     * Draws [content] at [w]x[h] (text at [scale]) in each look, audits it and saves it; [check] runs on the dark scene after
     * [prepare]. In dark, the same scene is redrawn in Showcase and must not expose an [identities] name.
     */
    private fun scenes(name: String, w: Int = 1280, h: Int = 800, scale: Float = 1f, only: List<String>? = null, showcase: Boolean = true,
        prepare: suspend CreateScene.() -> Unit = {}, content: @Composable (CoroutineScope) -> Unit, check: suspend CreateScene.() -> Unit = {}) = runSceneTest {
        for (look in looks.filter { only == null || it.name in only }) {
            if (scale != 1f && look.name == "contrast-desert") continue
            look.apply(scale)
            val job = SupervisorJob(coroutineContext[Job])
            val scope = CoroutineScope(coroutineContext + job)
            val scene = ImageComposeScene(w, h, Density(1f, scale), coroutineContext = coroutineContext) {
                MaterialTheme(colorScheme = ringColors) {
                    CompositionLocalProvider(LocalReduceMotion provides true) {
                        Surface(Modifier.fillMaxSize(), color = Tech.Canvas, contentColor = Tech.Ink) {
                            Box(Modifier.fillMaxSize().padding(16.dp)) { content(scope) }
                        }
                    }
                }
            }
            val ui = CreateScene(scene)
            try {
                ui.frames(); ui.prepare(); ui.frames()
                val audit = auditScene(scene).filter { it.kind != AuditKind.Ellipsis }
                assertTrue(audit.isEmpty(), "$name/${look.name}: clipped or collapsed text:\n${audit.describe()}")
                if (look.name == "dark") ui.check()
                shot(scene, "$name-${look.name}" + if (scale != 1f) "-text${(scale * 100).toInt()}" else "")
                if (look.name == "dark" && showcase) {
                    Appearance.preview(ThemeMode.Dark, showcase = true, textScale = scale); ui.frames(); delay(30); ui.frames()
                    val leaked = ui.texts().filter { t -> identities.any { t.contains(it, ignoreCase = true) } }
                    assertTrue(leaked.isEmpty(), "$name: Showcase leaked $leaked")
                    shot(scene, "$name-showcase")
                    look.apply(scale)
                }
            } finally { scene.close(); job.cancel() }
        }
    }

    private fun shot(scene: ImageComposeScene, file: String) {
        val folder = System.getenv("RINGMASTER_RADIOS_SHOTS") ?: return
        File(folder).mkdirs()
        scene.render(4_000_000_000).use { image -> image.encodeToData()!!.use { File(folder, "$file.png").writeBytes(it.bytes) } }
    }

    private fun CreateScene.texts(): List<String> = nodes().flatMap { n ->
        n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }
    private fun CreateScene.has(text: String) = texts().any { it.equals(text, ignoreCase = true) }
    private fun CreateScene.hasPart(text: String) = texts().any { it.contains(text, ignoreCase = true) }
    private fun CreateScene.assertShows(vararg words: String) { for (w in words) assertTrue(has(w) || hasPart(w), "missing \"$w\" in ${texts()}") }

    // ---- map ----

    @Test fun demoMapDrawsAdaptersGuestsAndTheSameInWords() = scenes("map-demo", content = { RadiosScreen(hub(), null) }) {
        assertShows("Map", "Bluetooth", "Wi-Fi", "TP-Link UB500", "MediaTek MT7921AU", "Intel Wi-Fi 6E AX210", "debian-workbench", "backup-lab",
            "In words", "In guests", "Select an adapter", "Dashed line: the guest is stopped", "Dotted line: a SPICE channel from this computer")
        assertShows("Looks like a Bluetooth adapter: Realtek USB device · USB 1-6 · given to CT 121 web-services as a device node (dev0)")
        assertShows("VM 120 debian-workbench (running) has TP-Link UB500 (Bluetooth), MediaTek MT7921AU (Wi-Fi), a SPICE channel (usb2)")
        assertShows("demo-node-a: 5 adapters", "This computer: 2 adapters")
    }

    @Test fun mapHoldsAtTheSmallestWindow() = scenes("map-960x600", 960, 600, content = { RadiosScreen(hub(), null) }) {
        assertShows("TP-Link UB500", "In words")
    }

    @Test fun mapHoldsAtTheLargestWindow() = scenes("map-2560x1440", 2560, 1440, only = listOf("dark", "contrast"), content = { RadiosScreen(hub(), null) }) {
        assertShows("TP-Link UB500", "Selection")
    }

    @Test fun mapHoldsAtLargeText() = scenes("map-demo", 1280, 800, scale = 1.5f, only = listOf("dark", "light", "contrast"), content = { RadiosScreen(hub(), null) }) {
        assertShows("TP-Link UB500", "In words")
    }

    @Test fun aGuessArrivesSelectedWithItsWayOnward() {
        val taps = Taps()
        scenes("map-guess", content = { RadiosScreen(hub(taps = taps), null, RadiosRequest(RadiosTarget(RadiosView.Map, "radio:demo-node-a/usb/1-6"), 7L)) },
            check = {
                assertShows("Looks like a Bluetooth adapter", "Guess", "Open guest", "Open node", "A container cannot scan. Its adapter belongs to the node's kernel.",
                    "Given to CT 121 web-services as a device node (dev0).")
                assertFalse(has("Scan with it"), "a container's adapter has no scan")
                click("Open guest")
                assertEquals(demoWebServices to "Radios", taps.guests.last())
                click("Open node")
                assertEquals("demo-node-a", taps.nodes.last().name)
            })
    }

    @Test fun scanWithItOnThisComputerOpensTheBluetoothView() = scenes("map-local-bt", only = listOf("dark"),
        content = { RadiosScreen(hub(), null, RadiosRequest(RadiosTarget(RadiosView.Map, "radio:local/bt0"), 3L)) },
        check = {
            assertShows("Intel Wireless Bluetooth", "This computer's own adapter.", "Scan with it")
            click("Scan with it")
            assertShows("Bluetooth scanning is not available on this platform yet.")
        })

    @Test fun emptyServerSaysWhatToDoAndOffersTheNoteAction() {
        var asked = 0
        val board = RadiosBoard(listOf(RadioHost(THIS_COMPUTER_KEY, THIS_COMPUTER_LABEL, RadioHostKind.ThisComputer),
            RadioHost("demo-node-a", "demo-node-a", RadioHostKind.Node, notes = listOf(RadioNote(RADIOS_TOKEN_USB, RadioAction("Check again") { asked++ })))))
        scenes("map-empty", content = { RadiosScreen(hub(board), null) }, check = {
            assertShows(RADIOS_EMPTY, RADIOS_TOKEN_USB, "Check again", "demo-node-a")
            assertFalse(has("Selection") || has("In words"), "an empty board shows only what to do")
            click("Check again")
            assertEquals(1, asked)
        })
    }

    @Test fun refusalsLayOutWithoutTheirAction() {
        val board = RadiosBoard(listOf(RadioHost("demo-node-a", "demo-node-a", RadioHostKind.Node, notes = listOf(RadioNote(RADIOS_TOKEN_USB)))))
        scenes("map-empty-no-action", only = listOf("dark", "contrast"), content = { RadiosScreen(hub(board), null) }) { assertShows(RADIOS_EMPTY, RADIOS_TOKEN_USB) }
    }

    @Test fun pdmSessionSaysItCannotBeRead() = scenes("map-pdm", content = { RadiosScreen(hub(unreadRadiosBoard(WorkspaceState(kind = PDM_KIND))), null) }) {
        assertShows(RADIOS_PDM, RADIOS_EMPTY)
        assertFalse(hasPart("not read in this build"))
    }

    @Test fun oneAdapter() = scenes("map-one", content = {
        RadiosScreen(hub(RadiosBoard(listOf(RadioHost("demo-node-b", "demo-node-b", RadioHostKind.Node)),
            listOf(RadioCard("b/usb/1", RadioKind.Bluetooth, "ASUS USB-BT500", "demo-node-b", "ASUS", "0b05:190e", RadioBus.Usb, "USB 3-2")))), null)
    }) {
        assertShows("ASUS USB-BT500", "demo-node-b: 1 adapter", "Bluetooth adapter: ASUS USB-BT500 · USB 3-2 · free", "FREE")
    }

    @Test fun aBusyFleetOfFortyAdapters() = scenes("map-busy", 1600, 1000, content = { RadiosScreen(hub(busyBoard()), null) }) {
        val board = busyBoard()
        assertEquals(40, board.radios.size)
        assertShows("${board.count(RadioKind.Bluetooth)}", "${board.count(RadioKind.WiFi)}", "Guesses", RADIOS_TOKEN_USB, "busy-node-5: 6 adapters (offline)")
    }

    @Test fun loading() = scenes("map-loading", only = listOf("dark", "light"), content = { RadiosScreen(hub(RadiosBoard(loading = true)), null) }) { assertShows(RADIOS_LOADING) }

    @Test fun tickerBluetoothTargetAndRequestsOpenTheirView() = scenes("bluetooth-view", only = listOf("dark", "light"),
        content = { RadiosScreen(hub(), null, RadiosRequest(RadiosTarget(RadiosView.Bluetooth), 1L)) }) {
        assertShows("Bluetooth scanning is not available on this platform yet.")
        assertEquals(RadiosView.Bluetooth, RadiosPageMemory.view)
    }

    // ---- Wi-Fi ----

    @Test fun wifiHidesNamesUntilAsked() = scenes("wifi", content = { RadiosScreen(hub(), null, RadiosRequest(RadiosTarget(RadiosView.WiFi), 1L)) }) {
        assertShows("Current link", "Channels in use", "2.4 GHz", "5 GHz", "6 GHz", "Networks in range", "Network A", "WPA3-Personal", "Show names", ADDRESS_HIDDEN,
            "Wi-Fi 6", "Open", "Signal over the last 40 readings")
        assertFalse(hasPart("Harbour Lab") || hasPart("02:00:00:"), "names and addresses hidden by default")
    }

    @Test fun wifiShowsNamesWhenAskedButNotInShowcase() {
        WifiNames.chosen = true
        scenes("wifi-names", content = { RadiosScreen(hub(), null, RadiosRequest(RadiosTarget(RadiosView.WiFi), 1L)) }) {
            assertShows("Harbour Lab", "Hidden network", "02:00:00:5a:01:11")
        }
    }

    @Test fun wifiRefusalCarriesItsAction() {
        var opened = 0
        val panel = WifiPanel(listOf(WifiSourceView(THIS_COMPUTER_KEY, THIS_COMPUTER_LABEL)), THIS_COMPUTER_KEY,
            refusal = RadioNote("Windows lists Wi-Fi networks only when Location is on for apps.", RadioAction("Open Location settings") { opened++ }))
        scenes("wifi-refused", content = { RadiosScreen(hub(wifi = panel), null, RadiosRequest(RadiosTarget(RadiosView.WiFi), 1L)) }) {
            assertShows("Windows lists Wi-Fi networks only when Location is on for apps.", "Open Location settings")
            click("Open Location settings"); assertEquals(1, opened)
        }
    }

    @Test fun wifiInsideAGuestIsConfirmedFirst() {
        val feed = FixedWifiFeed(demoWifiPanel(demoWorkbench.key))
        scenes("wifi-guest", content = { RadiosScreen(RadiosHub(FixedRadiosFeed(demoRadiosBoard()), feed, QuickGuestRadios()), null, RadiosRequest(RadiosTarget(RadiosView.WiFi), 1L)) },
            check = {
                assertShows("Not connected to a network.", "MediaTek MT7921AU")
                click("Scan")
                assertShows(GUEST_WIFI_CONFIRM)
                assertEquals(0, feed.scans, "nothing runs in the guest before the confirmation")
            })
    }

    @Test fun wifiStoppedGuestSourceIsRefused() = scenes("wifi-guest-stopped", only = listOf("dark", "contrast"),
        content = { RadiosScreen(hub(wifi = demoWifiPanel(demoBackupLab.key)), null, RadiosRequest(RadiosTarget(RadiosView.WiFi), 1L)) }) {
        assertShows("Start the VM to scan inside it.")
    }

    // ---- Fleet, node, guest ----

    @Test fun fleetStripCountsAndOpensTheMap() {
        val taps = Taps()
        scenes("strip", 1400, 160, content = { RadiosStripRow(demoRadiosBoard(), taps.nav) }, check = {
            assertShows("Radios", "5 Bluetooth", "3 Wi-Fi", "4 in guests", "Map ›", "→ VM 120")
            click("TP-Link UB500")
            assertEquals(RadiosTarget(RadiosView.Map, "radio:demo-node-a/usb/1-4"), taps.targets.last())
        })
    }

    @Test fun guestMarkNamesItsRadios() {
        val taps = Taps()
        scenes("guest-mark", 400, 120, only = listOf("dark", "contrast"), content = {
            CompositionLocalProvider(LocalRadios provides hub(taps = taps)) { GuestRadioMark(demoGuest) }
        }, check = {
            assertShows("Radios: TP-Link UB500 (Bluetooth, usb0), MediaTek MT7921AU (Wi-Fi, usb1), SPICE channel (usb2)")
        })
    }

    @Test fun nodeSectionListsAdaptersAndTheirGuests() {
        val taps = Taps()
        scenes("node", 380, 640, content = { Column { NodeRadiosSection(demoRadiosBoard(), "demo-node-a", taps.nav) } }, check = {
            assertShows("Radios on this node", "Intel Wi-Fi 6E AX210", "Wi-Fi · PCI 0000:03:00.0 · free", "VM 120 debian-workbench", "CT 121 web-services",
                "Own Wi-Fi: Connected · -61 dBm (good) · 5 GHz · channel 44 · Wi-Fi 6E")
            click("VM 120 debian-workbench")
            assertEquals(demoWorkbench to "Radios", taps.guests.last())
        })
    }

    @Test fun nodeSectionForANodeNotRead() = scenes("node-unread", 380, 300, only = listOf("dark", "light"), content = { Column { NodeRadiosSection(RadiosBoard(), "demo-node-a", RadiosNav()) } }) {
        assertShows("Radios on this node were not read.")
    }

    @Test fun guestTabOfAVmShowsItsRadiosAndLooksInsideOnlyAfterConfirmation() {
        val guests = QuickGuestRadios()
        val taps = Taps()
        scenes("guest-vm", 1280, 900, content = { scope ->
            CompositionLocalProvider(LocalRadios provides hub(taps = taps, guests = guests)) {
                Column(Modifier.verticalScroll(rememberScrollState())) { GuestRadiosTab(WorkspaceState(), Workspace(scope), demoGuest, demoHardware) }
            }
        }, check = {
            assertShows("Radios given to this VM", "TP-Link UB500", "MediaTek MT7921AU", "SPICE USB channel", "Show on map", "Attached to this VM", "usb3",
                "Look inside the VM", "Scan inside this VM", "Wi-Fi inside this VM")
            click("Look inside the VM")
            assertShows(GUEST_PROBE_CONFIRM)
            assertEquals(0, guests.probes)
            click("Look inside")
            frames()
            assertEquals(1, guests.probes)
            assertShows("What the VM sees", "hci0 · driver btusb", "wlan0 · driver mt7921u", "On, not connected to a network.")
            click("Show on map")
            assertEquals(RadiosView.Map, taps.targets.last().view)
        })
    }

    @Test fun guestTabOfAContainerSaysWhyItCannotLookInside() = scenes("guest-ct", 1280, 700, content = { scope ->
        CompositionLocalProvider(LocalRadios provides hub()) {
            Column(Modifier.verticalScroll(rememberScrollState())) { GuestRadiosTab(WorkspaceState(), Workspace(scope), demoContainer, null) }
        }
    }) {
        assertShows("Radios given to this container", "Realtek USB device", "Guess", RADIOS_CT_PROBE)
        assertFalse(has("Look inside the VM") || hasPart("Scan inside this VM"), "a container has neither")
    }

    @Test fun guestTabRefusalWithAndWithoutAction() {
        var used = 0
        val withAction = object : BoardGuestRadios() { override fun probeRefusal(guest: Guest) = RadioNote(GUEST_BT_NO_PERMISSION, RadioAction("Show how") { used++ }) }
        scenes("guest-refused", 960, 600, only = listOf("dark", "contrast"), content = { scope ->
            CompositionLocalProvider(LocalRadios provides hub(guests = withAction)) {
                Column(Modifier.verticalScroll(rememberScrollState())) { GuestRadiosTab(WorkspaceState(), Workspace(scope), demoGuest, demoHardware) }
            }
        }, check = {
            assertShows(GUEST_BT_NO_PERMISSION, "Show how")
            click("Show how"); assertEquals(1, used)
        })
    }

    // ---- on the Fleet page ----

    private fun demoState(node: String? = null) = WorkspaceState(connected = true, address = SAMPLE_ADDRESS, overview = sampleOverview(), selectedNode = node)

    @Test fun fleetPageShowsTheStripAndGuestMarks() = scenes("fleet", 1440, 900, content = { scope ->
        val workspace = androidx.compose.runtime.remember { Workspace(scope) }
        CompositionLocalProvider(LocalRadios provides hub()) {
            ChartLensHost(demoState(), workspace) { InventoryScreen(demoState(), workspace, "Demo", "", {}, "All", {}, {}, {}) }
        }
    }) {
        assertShows("Radios", "5 Bluetooth", "4 in guests", "Radios: TP-Link UB500 (Bluetooth, usb0), MediaTek MT7921AU (Wi-Fi, usb1), SPICE channel (usb2)",
            "Radios: ASUS USB-BT500 (Bluetooth, usb0)")
    }

    @Test fun nodeInspectorHasItsRadiosSection() = scenes("fleet-node", 1440, 1000, only = listOf("dark", "light", "contrast"), content = { scope ->
        val workspace = androidx.compose.runtime.remember { Workspace(scope) }
        CompositionLocalProvider(LocalRadios provides hub()) {
            ChartLensHost(demoState("demo-node-a"), workspace) { InventoryScreen(demoState("demo-node-a"), workspace, "Demo", "", {}, "All", {}, {}, {}) }
        }
    }) {
        // Under the gauges, so it is in view without scrolling the inspector.
        assertShows("Radios on this node", "Intel Wi-Fi 6E AX210")
        assertTrue(text("Radios on this node").boundsInWindow.bottom in 1f..1000f, "the section is in view")
    }

    // ---- sizes, text and motion ----

    @Test fun smallestWindowAtLargeText() = scenes("map-960x600", 960, 600, scale = 1.5f, only = listOf("dark", "contrast"), content = { RadiosScreen(hub(), null) }) {
        assertShows("TP-Link UB500", "In words")
    }

    @Test fun wifiAtSmallestWindowAndLargeText() = scenes("wifi-960x600", 960, 600, scale = 1.5f, only = listOf("dark", "light"),
        content = { RadiosScreen(hub(), null, RadiosRequest(RadiosTarget(RadiosView.WiFi), 1L)) }) { assertShows("Current link", "Scan") }

    @Test fun wifiAtTheLargestWindow() = scenes("wifi-2560x1440", 2560, 1440, only = listOf("dark", "pitch"),
        content = { RadiosScreen(hub(), null, RadiosRequest(RadiosTarget(RadiosView.WiFi), 1L)) }) { assertShows("Channels in use", "Network H") }

    @Test fun tooNarrowForTheMapSaysSoAndKeepsTheWords() = scenes("map-narrow", 560, 700, only = listOf("dark", "contrast"),
        content = { RadiosMapView(demoRadiosBoard(), null, {}, null, RadiosNav(), {}) }) {
        assertShows("The window is too narrow for the map. The list below says the same.", "In words", "TP-Link UB500")
    }

    /** With motion on, packets travel along the lines of running guests: two frames 600 ms apart differ. Nothing is audited here. */
    @Test fun mapPacketsMoveWithFullMotion() = runSceneTest {
        Appearance.preview(ThemeMode.Dark)
        val scene = ImageComposeScene(1000, 760, coroutineContext = coroutineContext) {
            MaterialTheme(colorScheme = ringColors) {
                CompositionLocalProvider(LocalReduceMotion provides false) {
                    Surface(Modifier.fillMaxSize(), color = Tech.Canvas) { Box(Modifier.padding(16.dp)) { RadiosMap(demoRadiosBoard(), null, {}, null) } }
                }
            }
        }
        try {
            fun frame(t: Long) = scene.render(t).use { it.encodeToData()!!.bytes }
            frame(0); frame(16_000_000)
            val a = frame(100_000_000)
            val b = frame(700_000_000)
            assertFalse(a.contentEquals(b), "the packets moved")
            shot(scene, "map-motion")
        } finally { scene.close() }
    }

    @Test fun theMapShowsEachAdaptersStateInWordsAndWhy() = scenes("map-states",
        content = { RadiosScreen(hub(), null, RadiosRequest(RadiosTarget(RadiosView.Map, "radio:demo-node-a/usb/1-10"), 7L)) },
        check = {
            assertShows("ACTIVE", "READY", "INACTIVE", "DISABLED")
            assertShows("Blocked by a radio switch on the node.")
        })

    @Test fun theSameSentenceFollowsAGuestThatStops() {
        val states = demoRadiosBoard().radios.associate { it.id to it.state }
        assertEquals(RadioState.Active, states["demo-node-a/usb/1-4"], "given to a running VM")
        assertEquals(RadioState.Inactive, states["demo-node-b/usb/3-2"], "given to a stopped VM")
        assertEquals(RadioState.Neutral, states["local/bt0"], "on and free")
        assertEquals(RadioState.Disabled, states["demo-node-a/usb/1-10"], "blocked")
        val links = radiosMapLayout(demoRadiosBoard(), 1100f, 1f).links
        assertTrue(links.first { it.from == "radio:demo-node-a/usb/1-4" }.live, "a running guest's line is live")
        assertFalse(links.first { it.from == "radio:demo-node-b/usb/3-2" }.live, "a stopped guest's line is dormant")
        assertTrue(links.none { it.down })
    }
}
