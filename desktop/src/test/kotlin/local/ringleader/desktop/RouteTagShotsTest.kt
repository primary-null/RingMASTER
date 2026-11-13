package local.ringleader.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import local.ringleader.core.*
import java.io.File
import java.nio.file.Files
import androidx.compose.ui.semantics.getOrNull
import kotlin.test.Test

/**
 * Opt-in pictures of the workspace heading (the row above every connected page), drawn with the production
 * [WorkspaceHeading] beside the real rail and Fleet page. Set RINGMASTER_ROUTE_TAG_SHOTS to an output folder and
 * RINGMASTER_ROUTE_TAG_PHASE to a file-name prefix ("before", "after"). Invented data only; nothing connects.
 *
 * Matrix: dark and light at 960x640 and 1600x900 (100 %), plus a stress pass at interface 200 % and text 150 %, each
 * with one connection and with three connections and long names. The "before" phase draws the heading without a route
 * tag (the heading as it was); the "after" phase adds the tag in each state, the tooltip opened by a real hover, and
 * Showcase. Static pictures: they show layout and text, not native window behaviour.
 */
class RouteTagShotsTest {
    private class FakeGateway : InventoryGateway {
        override suspend fun overview(connection: Connection) = Overview(1, listOf(
            Guest(connection.remoteKey, 100, "alpha", "node-a", "VM", "running"),
            Guest(connection.remoteKey, 101, "beta", "node-a", "CT", "stopped")))
        override suspend fun details(connection: Connection, guest: Guest) = demoDetails(guest)
    }

    internal data class Size(val width: Int, val height: Int, val density: Float, val font: Float) {
        val tag get() = "${width}x$height" + if (density != 1f || font != 1f) "-ui${(density * 100).toInt()}-text${(font * 100).toInt()}" else ""
    }

    internal companion object {
        val sizes = listOf(Size(960, 640, 1f, 1f), Size(1600, 900, 1f, 1f), Size(960, 640, 2f, 1.5f), Size(1600, 900, 2f, 1.5f))
    }

    @Test fun shots() {
        val dir = File(System.getenv("RINGMASTER_ROUTE_TAG_SHOTS") ?: return).apply { mkdirs() }
        val phase = System.getenv("RINGMASTER_ROUTE_TAG_PHASE")?.takeIf { it.isNotBlank() } ?: "after"
        val home = System.getProperty("user.home")
        val sandbox = Files.createTempDirectory("ringmaster-route-home")
        System.setProperty("user.home", sandbox.toString())
        // Times are shown in UTC, so a picture does not tell where it was made.
        val zone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
        try { runSceneTest { render(dir, phase, sandbox) } } finally {
            java.util.TimeZone.setDefault(zone)
            System.setProperty("user.home", home)
            sandbox.toFile().deleteRecursively()
            Appearance.preview(light = false)
        }
        val written = dir.listFiles { file -> file.extension == "png" && file.name.startsWith(phase) }.orEmpty()
        check(written.isNotEmpty())
        for (file in written) check(pngChunks(file).none { it in forbiddenPngChunks }) { "${file.name} carries metadata" }
    }

    private suspend fun render(dir: File, phase: String, sandbox: java.nio.file.Path) {
        val context = currentCoroutineContext()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val sessions = Sessions(scope) { Workspace(scope, FakeGateway()) }
        val desk = ProfileDesk(sessions.state.value.active.workspace, ProfileStore(sandbox.resolve("profiles.json"))).also(sessions::bind)
        try {
            desk.beginCreate(); desk.updateDraft("Datacenter west production cluster", "https://pve-west-cluster.example.test:8006", "tester@pve!read", ""); check(desk.saveDraft())
            desk.setProfileSecret("route-shot-secret"); check(desk.connectSelected())
            delay(200)
            val single = sessions.state.value
            val after = phase != "before"
            for (light in listOf(false, true)) for (size in sizes) {
                shot(dir, "$phase-single-${if (light) "light" else "dark"}-${size.tag}", context, sessions, desk, light, size, if (after) RouteTagSamples.direct else null)
            }
            if (after) {
                val wide = sizes[1]; val small = sizes[0]
                for ((name, state) in RouteTagSamples.all) shot(dir, "$phase-state-$name-dark-${wide.tag}", context, sessions, desk, false, wide, state)
                for (name in listOf("direct", "wireguard", "tailscale", "unknown", "failed", "proxy", "checking", "browser", "demo", "long-names")) {
                    val state = RouteTagSamples.all.first { it.first == name }.second
                    shot(dir, "$phase-tip-$name-dark-${wide.tag}", context, sessions, desk, false, wide, state, hover = true)
                }
                shot(dir, "$phase-tip-tailscale-light-${small.tag}", context, sessions, desk, true, small, RouteTagSamples.tailscale, hover = true)
                shot(dir, "$phase-tip-direct-light-${wide.tag}", context, sessions, desk, true, wide, RouteTagSamples.direct, hover = true)
                for (stress in sizes.drop(2)) shot(dir, "$phase-tip-wireguard-dark-${stress.tag}", context, sessions, desk, false, stress, RouteTagSamples.wireguard, hover = true)
                shot(dir, "$phase-privacy-tip-tailscale-dark-${wide.tag}", context, sessions, desk, false, wide, RouteTagSamples.tailscale, hover = true, showcase = true)
                shot(dir, "$phase-privacy-tip-failed-light-${small.tag}", context, sessions, desk, true, small, RouteTagSamples.failed, hover = true, showcase = true)
            }
            desk.setManualSecret("route-shot-secret"); check(desk.connectManual("https://192.0.2.15:8006", "root@pam", ""))
            desk.setManualSecret("route-shot-secret"); check(desk.connectManual("https://storage-node-with-a-rather-long-host-name.example.test:8006", "root@pam", ""))
            delay(200)
            check(sessions.state.value.open.size == 3) { "three sessions expected, found ${sessions.state.value.open.size}" }
            sessions.show(single.activeId)
            for (light in listOf(false, true)) for (size in sizes) {
                shot(dir, "$phase-multi-${if (light) "light" else "dark"}-${size.tag}", context, sessions, desk, light, size, if (after) RouteTagSamples.wireguard else null)
            }
            if (after) {
                shot(dir, "$phase-multi-long-names-dark-${sizes[0].tag}", context, sessions, desk, false, sizes[0], RouteTagSamples.longNames, hover = true)
                shot(dir, "$phase-privacy-multi-dark-${sizes[1].tag}", context, sessions, desk, false, sizes[1], RouteTagSamples.wireguard, hover = true, showcase = true)
            }
        } finally { desk.close(); sessions.close(); scope.cancel() }
    }

    private suspend fun shot(dir: File, name: String, context: kotlin.coroutines.CoroutineContext, sessions: Sessions, desk: ProfileDesk, light: Boolean, size: Size,
        route: RouteTagState? = null, hover: Boolean = false, showcase: Boolean = false) {
        val cases = System.getenv("RINGMASTER_ROUTE_TAG_CASES")?.split(';')?.filter { it.isNotBlank() }
        if (cases != null && cases.none { name.contains(it) }) return
        Appearance.preview(light = light, showcase = showcase)
        // Observed a few seconds before the picture, so the tooltip's age reads naturally.
        val route = (route as? RouteTagState.Observed)?.let { it.copy(observation = it.observation.copy(at = java.time.Instant.now().minusSeconds(12))) } ?: route
        val scene = ImageComposeScene(size.width, size.height, Density(size.density, size.font), coroutineContext = context) {
            MaterialTheme(colorScheme = ringColors) { CompositionLocalProvider(LocalReduceMotion provides true) {
                Surface(Modifier.fillMaxSize(), color = Tech.Canvas, contentColor = Tech.Ink) {
                    val all by sessions.state.collectAsState()
                    val deskState by desk.state.collectAsState()
                    val state by all.active.workspace.state.collectAsState()
                    val several = all.open.size > 1
                    Row(Modifier.fillMaxSize().background(Tech.Canvas)) {
                        NavigationRail(state, "Fleet", "Test", all.active.workspace, initialExpanded = size.width / size.density >= 900, sessions = all.open, activeSession = all.activeId, profiles = deskState.profiles, onSession = sessions::show) {}
                        Column(Modifier.weight(1f).fillMaxHeight().padding(16.dp), verticalArrangement = Arrangement.spacedBy(Tech.Space8)) {
                            WorkspaceHeading("Fleet", state, if (several) sessionLabel(state, deskState.profiles.find { it.id == all.active.profileId }) else null, route = route)
                            HorizontalDivider(color = Tech.Edge)
                            Box(Modifier.weight(1f).fillMaxWidth()) { InventoryScreen(state, all.active.workspace, null, "", {}, "All", {}, {}, {}) }
                        }
                    }
                }
            } }
        }
        try {
            var time = 0L
            repeat(4) { time += 16_000_000L; scene.render(time).close(); yield() }
            if (hover) {
                fun nodes(): List<androidx.compose.ui.semantics.SemanticsNode> = buildList {
                    fun visit(n: androidx.compose.ui.semantics.SemanticsNode) { add(n); n.children.forEach(::visit) }
                    scene.semanticsOwners.forEach { visit(it.rootSemanticsNode) }
                }
                val tag = nodes().first { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag) == ROUTE_TAG_TEST_TAG }
                scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move, tag.boundsInWindow.center)
                repeat(2) { time += 16_000_000L; scene.render(time).close(); yield() }
                delay(ROUTE_TIP_DELAY_MS + 150)
                repeat(3) { time += 16_000_000L; scene.render(time).close(); yield() }
                check(nodes().any { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag) == ROUTE_TIP_TEST_TAG }) { "$name: the tooltip did not open" }
            }
            time += 16_000_000L
            scene.render(time).use { File(dir, "$name.png").writeBytes(it.encodeToData()!!.bytes) }
            yield()
        } finally { scene.close() }
    }
}
