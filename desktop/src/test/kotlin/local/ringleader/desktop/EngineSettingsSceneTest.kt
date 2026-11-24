@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.yield
import local.ringleader.core.*
import local.ringleader.core.guesthelper.*
import local.ringleader.core.integrations.ServiceWire
import kotlin.test.*

class EngineSettingsSceneTest {
    @Test fun actualConfigControlsRemovalAndEnabledGuestOpensOneConfirmation() = runSceneTest {
        val workspace = sampleWorkspace(this)
        try {
            workspace.state.first { it.overview != null }
            val guest = workspace.state.value.overview!!.guests.first { it.kind == "VM" && it.status == "running" }
            val wire = ServiceWire("RingMASTER/test")
            val hub = EngineHub(GuestHelperPairings(GuestHelperStore(null), NoVault("Test"), wire), wire, { emptyList() })
            val link = GuestHelperLink("fixture", guestHelperPlace(guest), "192.0.2.7", 8696,
                "a".repeat(64), "0.1.0", "fixture", Instant.EPOCH, null)
            for (mode in listOf("enabled", "disabled", "unidentified", "unknown")) {
                val config = when (mode) {
                    "enabled" -> parseHardware("""{"data":{"agent":"1","ostype":"l26","meta":"ctime=1"}}""")
                    "unidentified" -> parseHardware("""{"data":{"agent":"1","ostype":"l26"}}""")
                    "disabled" -> parseHardware("""{"data":{"agent":"0","ostype":"l26"}}""")
                    else -> null
                }
                for ((width, height) in listOf(1600 to 900, 960 to 640)) {
                    var loaded = config
                    val scene = ImageComposeScene(width, height, coroutineContext = coroutineContext) {
                        MaterialTheme(colorScheme = ringColors) {
                            CompositionLocalProvider(LocalReduceMotion provides true) {
                                PairingRow(hub, workspace, workspace.state.value, link, loadHardware = { _, _ -> loaded })
                            }
                        }
                    }
                    try {
                        suspend fun frame() { repeat(4) { scene.render((it + 1) * 16_000_000L).close(); yield() } }
                        fun texts() = allNodes(scene).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
                        frame()
                        if (mode == "enabled") {
                            loaded = parseHardware("""{"data":{"agent":"0"}}""")
                            workspace.guestAgentAvailability.invalidate()
                            frame()
                            assertTrue(texts().any { it.equals("How to remove it", ignoreCase = true) })
                            loaded = config
                            workspace.guestAgentAvailability.invalidate()
                            frame()
                            val remove = allNodes(scene).first {
                                it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text.startsWith("REMOVE") } == true
                            }
                            assertTrue(remove.config[SemanticsActions.OnClick].action!!.invoke())
                            frame()
                            assertEquals(1, texts().count { it == "Remove the RingMASTER Engine?" })
                        } else {
                            assertTrue(texts().any { it.equals("How to remove it", ignoreCase = true) })
                            assertFalse(texts().any { it.startsWith("REMOVE") })
                        }
                        val folder = java.io.File("../artifacts/engine-fallback/settings").apply { mkdirs() }
                        java.io.File(folder, "$mode-${width}x$height.png").writeBytes(scene.render().encodeToData()!!.bytes)
                    } finally { scene.close() }
                }
            }
        } finally { workspace.close() }
    }
    @Test fun failedActualUninstallRetainsPairingAndOffersManualCommand() = runSceneTest {
        val workspace = sampleWorkspace(this)
        try {
            workspace.state.first { it.overview != null }
            val config = parseHardware("""{"data":{"agent":"1","ostype":"l26","meta":"ctime=1"}}""")
            val access = GuestAgentAvailability({ workspace.state.value }, { 0L }, { _, _ -> config }, { _, g -> g })
            val guest = workspace.state.value.overview!!.guests.first { it.kind == "VM" && it.status == "running" }
            var calls = 0
            val channel = object : GuestAgentChannel {
                override fun writeFile(connection: Connection, guest: Guest, path: String, base64: String) = error("No upload")
                override fun run(connection: Connection, guest: Guest, command: List<String>, firstPollSeconds: Int,
                    budgetSeconds: Int): AgentExecStatus {
                    calls++
                    throw GuestAgentFailure(GuestRefusal.NoPermission)
                }
            }
            val wire = ServiceWire("RingMASTER/test")
            val hub = EngineHub(GuestHelperPairings(GuestHelperStore(null), NoVault("Test"), wire), wire,
                { emptyList() }, GuestAgentInstaller(channel))
            val link = GuestHelperLink("fixture", guestHelperPlace(guest), "192.0.2.7", 8696,
                "a".repeat(64), "0.1.0", "fixture", Instant.EPOCH, null)
            hub.pairings.save(link, "AAAAA-BBBBB-CCCCC-DDDDD-EEEEE".toCharArray()).getOrThrow()
            val scene = ImageComposeScene(960, 640, coroutineContext = coroutineContext) {
                MaterialTheme(colorScheme = ringColors) {
                    CompositionLocalProvider(LocalReduceMotion provides true) {
                        PairingRow(hub, workspace, workspace.state.value, link, agentAccess = access)
                    }
                }
            }
            try {
                var tick = 0L
                suspend fun frame() { repeat(5) { scene.render(++tick * 16_000_000L).close(); yield() } }
                fun texts() = allNodes(scene).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
                fun click(label: String, prefix: Boolean = false) {
                    val node = allNodes(scene).last { it.config.getOrNull(SemanticsProperties.Text)?.any { t ->
                        if (prefix) t.text.startsWith(label) else t.text == label } == true }
                    assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
                }
                kotlinx.coroutines.withTimeout(5000) {
                    while (texts().none { it.startsWith("REMOVE") }) { frame(); kotlinx.coroutines.delay(10) }
                }
                click("REMOVE", prefix = true); frame(); click("REMOVE")
                kotlinx.coroutines.withTimeout(5000) {
                    while (texts().none { it == GUEST_HELPER_UNINSTALL }) { frame(); kotlinx.coroutines.delay(10) }
                }
                assertEquals(1, calls)
                assertNotNull(hub.pairings.link(link.id))
                val token = assertNotNull(hub.pairings.token(link.id))
                try { assertEquals("AAAAA-BBBBB-CCCCC-DDDDD-EEEEE", token.concatToString()) }
                finally { token.fill('\u0000') }
                assertTrue(texts().any { it == GUEST_HELPER_UNINSTALL })
                assertTrue(texts().any { it.contains(GuestRefusal.NoPermission.sentence) })
                assertFalse(texts().any { it.startsWith("Removed from") })
            } finally { scene.close() }
        } finally { workspace.close() }
    }

}
