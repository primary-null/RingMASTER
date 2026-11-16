@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.TimeZone
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlinx.coroutines.*
import local.ringleader.core.*

/**
 * Opt-in pictures of the live ticker (RM_FIELD_SHOTS=<absolute folder>): every page with the tray collapsed and expanded, its states,
 * and the Under the Hood page lit area by area. Invented data only, UTC times, written without metadata by the PNG encoder.
 * Filters (comma lists, all optional): RM_TICKER_PAGES, RM_TICKER_SIZES (1280x720,...), RM_TICKER_SET (pages, states, looks, hood, narrow).
 */
class LiveTickerShotsTest {
    @Test fun shots() {
        val out = System.getenv("RM_FIELD_SHOTS")?.takeIf { it.isNotBlank() } ?: return
        check(System.getenv("XDG_CONFIG_HOME").isNullOrBlank()) { "Unset XDG_CONFIG_HOME for the ticker pictures." }
        val dir = File(out).apply { mkdirs() }
        val home = System.getProperty("user.home")
        val sandbox = Files.createTempDirectory("ringmaster-ticker-home")
        val zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        System.setProperty("user.home", sandbox.toString())
        try { runSceneTest { TickerShotRun(dir, sandbox, coroutineContext).run() } } finally {
            TimeZone.setDefault(zone); System.setProperty("user.home", home)
            LiveTickerPreference.preview(TickerMode.Automatic); Appearance.preview(false)
            sandbox.toFile().deleteRecursively()
        }
    }
}

internal data class ShotLook(val name: String, val theme: ThemeMode, val text: Float = 1f, val ui: Float = 1f)

private class TickerShotRun(private val dir: File, private val sandbox: java.nio.file.Path, private val context: CoroutineContext) {
    private val sizes = (System.getenv("RM_TICKER_SIZES") ?: "1280x720,1920x1080").split(',').map { it.trim().split('x').let { (w, h) -> w.toInt() to h.toInt() } }
    private val only = System.getenv("RM_TICKER_PAGES")?.split(',')?.map { it.trim() }?.toSet()
    private val sets = (System.getenv("RM_TICKER_SET") ?: "pages,states,looks,hood,narrow").split(',').toSet()
    private val dark = ShotLook("dark", ThemeMode.Dark)

    private fun save(scene: ImageComposeScene, name: String, time: Long) {
        val image = scene.render(time)
        try { File(dir, "$name.png").also { it.parentFile.mkdirs() }.writeBytes(image.encodeToData()!!.bytes) } finally { image.close() }
    }

    /** One picture: [page] in the app's window of [w]x[h], the tray with [input], collapsed or open. */
    private suspend fun shot(name: String, page: String, w: Int, h: Int, pages: TickerPages, look: ShotLook = dark, input: TickerInput = TickerData.calm, open: Boolean = true,
        rail: Boolean = false, off: Boolean = false, settle: Int = 36, showcase: Boolean = false, variant: String? = null, configure: suspend (TickerBench) -> Unit = {}, after: suspend (ImageComposeScene, TickerBench) -> Unit = { _, _ -> }) {
        Appearance.preview(look.theme, showcase = showcase, textScale = look.text)
        LiveTickerPreference.preview(if (off) TickerMode.Off else if (open) TickerMode.Always else TickerMode.Automatic)
        val bench = TickerBench()
        bench.sample(input)
        configure(bench)
        val scene = ImageComposeScene(w, h, Density(look.ui, look.text), coroutineContext = context) {
            MaterialTheme(colorScheme = ringColors) {
                CompositionLocalProvider(LocalReduceMotion provides false, LocalMotion provides RmMotion(MotionTier.Full), LocalPageFocus provides bench.focus,
                    LocalTickerActivity provides { input.activity }, LocalHoodProbe provides true) {
                    Surface(Modifier.fillMaxSize(), color = Tech.Canvas, contentColor = Tech.Ink) {
                        TickerShell(page, pages.state(page), pages.idle, pages.lab, rail, bench, blocked = page == "Under the Hood") { pages.Content(page, variant) }
                    }
                }
            }
        }
        try {
            var clock = 0L
            repeat(settle) { clock += 33_000_000L; scene.render(clock).close(); yield(); delay(8) }
            after(scene, bench)
            repeat(if (settle < 36) 0 else 12) { clock += 33_000_000L; scene.render(clock).close(); yield(); delay(8) }
            save(scene, name, clock + 16_000_000L)
        } finally { scene.close() }
    }

    suspend fun run() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val lab = MatrixLab(sandbox)
        val pages = TickerPages(scope, lab, sandbox)
        try {
            val shown = pages.names.filter { it != "Under the Hood" && (only == null || it in only) }
            if ("bare" in sets) for ((w, h) in sizes) for (page in shown) shot("bare/${page.lowercase()}-${w}x$h", page, w, h, pages, off = true)
            if ("pages" in sets) for ((w, h) in sizes) for (page in shown) {
                shot("pages/${page.lowercase()}-${w}x$h-open", page, w, h, pages)
                shot("pages/${page.lowercase()}-${w}x$h-pill", page, w, h, pages, open = false)
            }
            if ("states" in sets) for (page in listOf("Fleet", "Scan").filter { only == null || it in only }) {
                val (w, h) = sizes.first()
                for ((state, input) in listOf("calm" to TickerData.calm, "busy" to TickerData.busy, "error" to TickerData.error, "running" to TickerData.longRunning,
                    "vpn" to TickerData.vpn, "failed-connection" to TickerData.failedConnection, "backoff" to TickerData.backoff)) {
                    shot("states/${page.lowercase()}-$state-open", page, w, h, pages, input = input)
                    shot("states/${page.lowercase()}-$state-pill", page, w, h, pages, input = input, open = false)
                }
            }
            if ("looks" in sets) for (look in listOf(ShotLook("light", ThemeMode.Light), ShotLook("pitch", ThemeMode.PitchBlack), ShotLook("high-contrast", ThemeMode.HighContrast), ShotLook("text150", ThemeMode.Dark, 1.5f)))
                for (page in listOf("Fleet", "Activity", "Scan", "Storage", "Tools").filter { only == null || it in only }) {
                    val (w, h) = sizes.first()
                    shot("looks/${page.lowercase()}-${look.name}-error", page, w, h, pages, look, TickerData.error)
                    shot("looks/${page.lowercase()}-${look.name}-calm", page, w, h, pages, look)
                }
            if ("hood" in sets) {
                val (w, h) = sizes.first()
                // Real rows in the real trace, so the page has something to show and the Operation focus has an operation to find.
                Trace.clear()
                val calls = listOf("GET /cluster/resources", "GET /cluster/tasks", "GET /nodes/pve-east/status", "GET /nodes/pve-west/qemu/125/status/current")
                repeat(40) { i ->
                    val span = Trace.begin(if (i % 9 == 4) TraceSource.PdmApi else TraceSource.PveApi, calls[i % calls.size], mapOf("url" to "https://pve-east.example.test:8006/api2/json/${calls[i % calls.size].substringAfter(' ')}"))
                    Thread.sleep(listOf(2L, 5L, 3L, 9L)[i % 4]); span.end(status = if (i % 13 == 0) 503 else 200, bytesIn = 18_432L)
                }
                val open = listOf(Trace.begin(TraceSource.Console, "VNC session", session = true), Trace.begin(TraceSource.PveApi, "GET /nodes/pve-east/syslog", mapOf("url" to "https://pve-east.example.test:8006/api2/json/nodes/pve-east/syslog")))
                try {
                    val failed = Trace.snapshot().first { it.outcome == TraceOutcome.Error }.id
                    val three = TickerActivity(listOf(
                        TickerData.session(1, true, route = TickerRoute(RouteKind.Vpn, VpnKind.Tailscale), label = "Lab cluster"),
                        TickerData.session(2, false, route = TickerRoute(RouteKind.Direct, medium = RouteMedium.WiFi), pinned = false, label = "Office PVE", address = "https://pve-office.example.test:8006"),
                        TickerData.session(3, false, route = TickerRoute(RouteKind.Proxy), label = "Datacenter Manager", address = "https://pdm.example.test:8443")))
                    for ((name, focus) in listOf("stream-source" to HoodFocus.Stream(TraceSource.PveApi), "operation" to HoodFocus.Operation(failed), "engine" to HoodFocus.Engine, "sessions" to HoodFocus.Sessions,
                        "network" to HoodFocus.Network, "latency" to HoodFocus.Latency, "requests" to HoodFocus.Requests, "slowest" to HoodFocus.Slowest, "errors" to HoodFocus.Errors))
                        shot("hood/$name", "Under the Hood", w, h, pages, input = TickerData.input(activity = three), settle = 14, configure = { it.focus.request(TickerTarget.Hood(focus)) })
                    shot("hood/plain", "Under the Hood", w, h, pages, input = TickerData.input(activity = three), settle = 14)
                    // Showcase: the connection names and addresses are blurred; the facts under them are not.
                    shot("hood/network-showcase", "Under the Hood", w, h, pages, input = TickerData.input(activity = three), settle = 14, showcase = true, configure = { it.focus.request(TickerTarget.Hood(HoodFocus.Network)) })
                } finally { open.forEach { it.end(status = 200) } }
            }
            // The guest's own page: a tab at a time. The Console tab is the one the tray steps aside for, so its pictures show no tray at all.
            if ("guest" in sets) {
                val (w, h) = sizes.first()
                for (tab in listOf("Summary", "Hardware", "Snapshots", "Backups", "Actions", "Console")) {
                    shot("fleet-guest/${tab.lowercase()}-${w}x$h-open", "Fleet", w, h, pages, input = TickerData.error, variant = tab, settle = 44)
                    shot("fleet-guest/${tab.lowercase()}-${w}x$h-pill", "Fleet", w, h, pages, open = false, variant = tab, settle = 44)
                }
            }
            // The narrowest window the app allows: 960 by 640 pixels at the largest interface size (200 %) is 480 by 320 units.
            if ("narrowest" in sets) for (page in listOf("Desk", "Fleet", "Activity", "Scan", "Tools").filter { only == null || it in only }) {
                val tiny = ShotLook("ui200", ThemeMode.Dark, 1f, 2f)
                shot("narrowest/${page.lowercase()}-480x320-open", page, 960, 640, pages, tiny, TickerData.error)
                shot("narrowest/${page.lowercase()}-480x320-calm", page, 960, 640, pages, tiny)
                shot("narrowest/${page.lowercase()}-480x320-pill", page, 960, 640, pages, tiny, open = false)
            }
            if ("narrow" in sets) for (page in shown) {
                shot("narrow/${page.lowercase()}-960x640-open", page, 960, 640, pages, input = TickerData.error)
                shot("narrow/${page.lowercase()}-960x640-calm", page, 960, 640, pages)
            }
        } finally { pages.close(); scope.cancel() }
    }
}
