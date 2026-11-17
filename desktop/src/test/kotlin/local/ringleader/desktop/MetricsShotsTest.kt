package local.ringleader.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import local.ringleader.core.*
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.TimeZone

/**
 * Opt-in pictures of every measurement visual: RM_METRICS_SHOTS=<folder>. Invented data only ([MetricsFixtures]); never
 * connects to a server; times in UTC. The same file names are written before and after a change, so pairs line up.
 *
 * For Dark, Light, Pitch Black and High Contrast at 1280x720 and 1920x1080: the fleet (cluster inspector, a guest, a
 * node), the guest summary over an hour, day, week and month, the chart lens with the pointer on it, the storage page,
 * and two component sheets (live: gauges, bars, numbers, sparklines with their awkward cases; charts: history and tool
 * charts). Extras in Dark: 150 % text, the narrowest window (960x640) and Showcase.
 */
class MetricsShotsTest {
    private val ms = 1_000_000L

    @Test fun shots() {
        val dir = File(System.getenv("RM_METRICS_SHOTS") ?: return).apply { mkdirs() }
        val only = System.getenv("RM_METRICS_ONLY")?.split(',')?.map { it.trim() }?.toSet()
        val zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val home = System.getProperty("user.home")
        val sandbox = Files.createTempDirectory("ringmaster-metrics-home")
        System.setProperty("user.home", sandbox.toString())
        try { runSceneTest { render(dir, only) } } finally {
            TimeZone.setDefault(zone); System.setProperty("user.home", home); sandbox.toFile().deleteRecursively()
            Appearance.preview(false)
        }
    }

    private fun setHistoryPreference(timeframe: String) {
        val cls = Class.forName("local.ringleader.desktop.HistoryPreference")
        cls.getDeclaredField("timeframe").apply { isAccessible = true }.set(null, timeframe)
        cls.getDeclaredField("cf").apply { isAccessible = true }.set(null, "AVERAGE")
    }

    private suspend fun render(dir: File, only: Set<String>?) {
        val context = currentCoroutineContext()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val workspace = sampleWorkspace(scope)
        workspace.setLive(false)
        workspace.select(workspace.state.value.overview!!.guests.first())
        workspace.inspectGuest("hour")
        delay(1200)
        val base = workspace.state.value
        val now = MetricsFixtures.now
        val overview = MetricsFixtures.overview(base.overview!!)
        val history = MetricsFixtures.history(overview)
        val fleet = base.copy(overview = overview, history = history, selected = null, selectedNode = null, details = null, updatedAt = now.minusSeconds(3),
            chartHistory = InspectionSection(), chartHistoryLoading = false)
        val hot = overview.guests[MetricsFixtures.overloaded]
        val fleetGuest = fleet.copy(selected = hot)
        val fleetNode = fleet.copy(selectedNode = "node-bravo", chartHistory = InspectionSection(MetricsFixtures.nodeHistory(60)))
        val db = overview.guests.first { it.name == "db-primary" }
        fun guestState(step: Long, restart: Int? = null, gap: IntRange? = null): WorkspaceState {
            val points = MetricsFixtures.guestHistory(step, restartAt = restart, gap = gap)
            return fleet.copy(selected = db, inspection = base.inspection!!.copy(resource = db.resource, node = db.node, history = InspectionSection(points)),
                chartHistory = InspectionSection(points))
        }
        val storageRow = overview.storage.first { it.node == "node-bravo" }
        val storageKey = StorageKey(storageRow.remote, storageRow.node, storageRow.id)
        val storageState = fleet.copy(storageSelection = storageKey,
            storageContent = ReadSection.loaded(StorageContentView(storageKey, null, sampleStorageContent(storageRow)), now),
            storageView = fleet.storageView.copy(history = ReadSection.loaded(StorageHistoryView(StorageHistoryRequest(storageKey, "hour", "AVERAGE"), MetricsFixtures.storageHistory(60)), now)))
        val pins = PinStore(sandboxPins())

        var clock = 0L
        suspend fun shot(name: String, w: Int, h: Int, theme: ThemeMode, fontScale: Float = 1f, showcase: Boolean = false, page: String = "Fleet", shown: WorkspaceState = fleet,
            timeframe: String = "hour", lensHost: Boolean = true, hover: (suspend (ImageComposeScene, List<Rect>) -> Unit)? = null, content: @Composable () -> Unit) {
            if (only != null && only.none { name.startsWith(it) }) return
            Appearance.preview(theme, showcase = showcase)
            setHistoryPreference(timeframe)
            val expands = mutableListOf<Rect>()
            val scene = ImageComposeScene(w, h, Density(1f, fontScale), coroutineContext = context) {
                MaterialTheme(colorScheme = ringColors) {
                    CompositionLocalProvider(LocalReduceMotion provides true, LocalNow provides now, LocalExpandBounds provides { r -> if (r !in expands) expands += r }) {
                        Surface(Modifier.fillMaxSize(), color = Tech.Canvas, contentColor = Tech.Ink) {
                            Row(Modifier.fillMaxSize()) {
                                NavigationRail(shown, page, "Windows 11", workspace, initialExpanded = false, pins = pins, connectionKey = "demo") {}
                                Box(Modifier.weight(1f).fillMaxHeight().padding(16.dp)) { if (lensHost) ChartLensHost(shown, workspace) { content() } else content() }
                            }
                        }
                    }
                }
            }
            try {
                repeat(14) { clock += 100 * ms; scene.render(clock).close(); yield(); delay(15) }
                if (hover != null) {
                    hover(scene, expands.toList())
                    repeat(6) { clock += 100 * ms; scene.render(clock).close(); yield(); delay(15) }
                }
                clock += 16 * ms
                scene.render(clock).use { File(dir, "$name.png").writeBytes(it.encodeToData()!!.bytes) }
            } finally { scene.close() }
        }
        suspend fun pointAt(scene: ImageComposeScene, x: Float, y: Float) {
            scene.sendPointerEvent(PointerEventType.Enter, Offset(x - 8, y))
            scene.sendPointerEvent(PointerEventType.Move, Offset(x - 4, y)); clock += 16 * ms; scene.render(clock).close(); yield()
            scene.sendPointerEvent(PointerEventType.Move, Offset(x, y))
        }
        // The first history chart's plot sits under its expand button: point a little to the left of it and below.
        val chartHover: suspend (ImageComposeScene, List<Rect>) -> Unit = { scene, expands ->
            if (System.getenv("RM_METRICS_DEBUG") != null) println("expand bounds: $expands")
            expands.filter { it.width > 0f && it.top > 0f }.minByOrNull { it.top * 10_000 + it.left }?.let { b -> pointAt(scene, b.left - 150f, b.bottom + 80f) }
        }
        val inventory: @Composable (WorkspaceState) -> Unit = { s -> InventoryScreen(s, workspace, null, "", {}, "All", {}, {}, {}, pins, "demo") }

        val themes = listOf(ThemeMode.Dark to "dark", ThemeMode.Light to "light", ThemeMode.PitchBlack to "pitch", ThemeMode.HighContrast to "hc")
        val sizes = listOf(1280 to 720, 1920 to 1080)
        try {
            for ((theme, t) in themes) for ((w, h) in sizes) {
                val sfx = "$t-${w}x$h"
                shot("fleet-$sfx", w, h, theme) { inventory(fleet) }
                shot("fleet-guest-$sfx", w, h, theme, shown = fleetGuest) { inventory(fleetGuest) }
                shot("fleet-node-$sfx", w, h, theme, shown = fleetNode, hover = chartHover) { inventory(fleetNode) }
                for ((range, s) in listOf("hour" to guestState(60), "day" to guestState(1200, gap = 30..34), "week" to guestState(8640, restart = 44), "month" to guestState(37000))) {
                    shot("guest-$range-$sfx", w, h, theme, shown = s, timeframe = range, hover = chartHover) {
                        GuestDetailPage(s, workspace, {}, { _, _ -> }, initialTab = "Summary", pins = pins, connectionKey = "demo")
                    }
                }
                shot("lens-$sfx", w, h, theme, shown = fleetGuest, lensHost = false, hover = { scene, _ -> pointAt(scene, w * .62f, h * .55f) }) {
                    ChartLensHost(fleetGuest, workspace) {
                        val lens = LocalChartLens.current
                        LaunchedEffect(Unit) { lens?.open(guestLens(hot, LiveMetric.Cpu)) }
                        inventory(fleetGuest)
                    }
                }
                shot("storage-$sfx", w, h, theme, page = "Storage", shown = storageState) { StoragePage(storageState, workspace) }
                shot("kit-live-$sfx", w, h, theme) { MetricSheetLive(fleet) }
                shot("kit-charts-$sfx", w, h, theme) { MetricSheetCharts() }
                // The pointer rests on the kit's hover sparkline, so its scale and readout are in the picture.
                shot("kit-components-$sfx", w, h, theme, hover = { scene, marks -> marks.firstOrNull { it.width > 0f }?.let { pointAt(scene, it.left + it.width * .4f, it.center.y) } }) { MetricSheetComponents(fleet) }
            }
            val staleFleet = fleet.copy(stale = true)
            shot("fleet-stale-dark-1280x720", 1280, 720, ThemeMode.Dark, shown = staleFleet) { inventory(staleFleet) }
            shot("fleet-text150-dark-1280x720", 1280, 720, ThemeMode.Dark, fontScale = 1.5f) { inventory(fleet) }
            shot("fleet-guest-text150-dark-1280x720", 1280, 720, ThemeMode.Dark, fontScale = 1.5f, shown = fleetGuest) { inventory(fleetGuest) }
            shot("guest-hour-text150-dark-1280x720", 1280, 720, ThemeMode.Dark, fontScale = 1.5f, shown = guestState(60), hover = chartHover) {
                GuestDetailPage(guestState(60), workspace, {}, { _, _ -> }, initialTab = "Summary", pins = pins, connectionKey = "demo")
            }
            shot("kit-live-text150-dark-1280x720", 1280, 720, ThemeMode.Dark, fontScale = 1.5f) { MetricSheetLive(fleet) }
            shot("fleet-narrow-dark-960x640", 960, 640, ThemeMode.Dark) { inventory(fleet) }
            shot("fleet-guest-narrow-dark-960x640", 960, 640, ThemeMode.Dark, shown = fleetGuest) { inventory(fleetGuest) }
            shot("guest-hour-narrow-dark-960x640", 960, 640, ThemeMode.Dark, shown = guestState(60), hover = chartHover) {
                GuestDetailPage(guestState(60), workspace, {}, { _, _ -> }, initialTab = "Summary", pins = pins, connectionKey = "demo")
            }
            shot("fleet-guest-showcase-dark-1920x1080", 1920, 1080, ThemeMode.Dark, showcase = true, shown = fleetGuest) { inventory(fleetGuest) }
        } finally { workspace.close(); scope.cancel() }
    }

    private fun sandboxPins() = Files.createTempDirectory("metrics-pins").resolve("pins.json")
}

/** Gauges, bars, numbers and sparklines, each with the cases that are hard to draw. */
@Composable internal fun MetricSheetLive(state: WorkspaceState) {
    val guests = state.overview!!.guests
    fun series(name: String) = state.history.guests[guests.first { it.name == name }.resource] ?: Series()
    val hot = series("ci-runner-1"); val quiet = series("dns-2"); val gappy = series("flaky-sensor"); val fresh = series("fresh-start"); val reset = series("nat-box")
    val constant = Series(hot.samples.map { it.copy(cpu = .25, held = false) })
    val hover = remember { SparkHover().apply { x = 150f } }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("Gauges")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ArcGauge(null, "CPU", "no sample", series = Series())
                ArcGauge(.03, "CPU", "idle", series = quiet)
                ArcGauge(.42, "CPU", "4 vCPU", series = hot)
                ArcGauge(.72, "Memory", "11.5 / 16 GiB", series = hot, metric = { memoryFraction(it.mem, it.memMax) }, thresholds = MetricThresholds.Capacity)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ArcGauge(.88, "Memory", "14.1 / 16 GiB", series = hot, metric = { memoryFraction(it.mem, it.memMax) }, thresholds = MetricThresholds.Capacity)
                ArcGauge(.97, "CPU", "8 vCPU", series = hot)
                ArcGauge(1.0, "CPU", "pinned", series = hot)
            }
            SectionLabel("Bars")
            for (f in listOf(null, .12, .55, .82, .96)) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(percent(f), Modifier.width(44.dp), fontSize = 10.sp, fontFamily = Tech.Mono, color = Tech.Muted); UsageBar(f, Modifier.weight(1f))
            }
            SplitBar(listOf(31, 7, 2, 0), Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { AnimatedCount(40); AnimatedNumber(.071); AnimatedNumber(.588); AnimatedNumber(.971) }
            RateLine("IN", 1_234_567.0, Tech.Green); RateLine("OUT", 987.0, Tech.Amber)
        }
        Column(Modifier.weight(1.3f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionLabel("Sparklines")
            @Composable fun case(label: String, body: @Composable () -> Unit) = Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, fontSize = 9.sp, fontFamily = Tech.Mono, color = Tech.Muted); body()
            }
            case("CPU · overloaded guest, hover") { Sparkline(hot, Modifier.fillMaxWidth().height(44.dp).background(Tech.Black.copy(alpha = .35f)), hover = hover) }
            case("CPU · quiet guest (0.3 %)") { Sparkline(quiet, Modifier.fillMaxWidth().height(32.dp).background(Tech.Black.copy(alpha = .35f))) }
            case("CPU · missing samples") { Sparkline(gappy, Modifier.fillMaxWidth().height(32.dp).background(Tech.Black.copy(alpha = .35f))) }
            case("CPU · just started (2 samples)") { Sparkline(fresh, Modifier.fillMaxWidth().height(32.dp).background(Tech.Black.copy(alpha = .35f))) }
            case("CPU · constant 25 %") { Sparkline(constant, Modifier.fillMaxWidth().height(32.dp).background(Tech.Black.copy(alpha = .35f))) }
            case("CPU · no samples") { Sparkline(Series(), Modifier.fillMaxWidth().height(24.dp).background(Tech.Black.copy(alpha = .35f))) }
            case("Memory") { Sparkline(hot, Modifier.fillMaxWidth().height(32.dp).background(Tech.Black.copy(alpha = .35f)), floor = .1, pulse = false, readout = SparkReadout.Memory) { memoryFraction(it.mem, it.memMax) } }
            case("Network · counter reset") { NetSparkline(reset, Modifier.fillMaxWidth().height(32.dp).background(Tech.Black.copy(alpha = .35f)), pulse = false) }
            case("Network · cluster") { NetSparkline(state.history.cluster, Modifier.fillMaxWidth().height(32.dp).background(Tech.Black.copy(alpha = .35f)), pulse = false) }
            case("Table cells") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    for (s in listOf(hot, quiet, gappy, fresh)) Sparkline(s, Modifier.size(96.dp, 18.dp), pulse = false)
                }
            }
        }
    }
}

/** History and tool charts: ranges, a gap, a restart, an idle window, an empty one, a crosshair. */
@Composable internal fun MetricSheetCharts() {
    val hour = MetricsFixtures.guestHistory(60)
    val day = MetricsFixtures.guestHistory(1200, gap = 30..34)
    val week = MetricsFixtures.guestHistory(8640, restartAt = 44)
    val idle = hour.map { HistoryPoint(it.time, mapOf("cpu" to 0.0)) }
    val cross = remember { HistoryInteraction().apply { instant = hour[48].time } }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HistoryChartPanel("CPU / 8 cores", hour, listOf("cpu"), "No samples", 110.dp, cross)
            HistoryChartPanel("Memory · day, gap", day, listOf("mem", "maxmem"), "No samples", 110.dp, remember { HistoryInteraction() })
            HistoryChartPanel("Network · week, restart", week, listOf("netin", "netout"), "No samples", 110.dp, remember { HistoryInteraction() })
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HistoryChartPanel("CPU · idle window", idle, listOf("cpu"), "No samples", 70.dp, remember { HistoryInteraction() })
            HistoryChartPanel("Disk IO · empty", emptyList(), listOf("diskread", "diskwrite"), "No samples in this window", 70.dp, remember { HistoryInteraction() })
            SectionLabel("Tools")
            ToolChart(MetricsFixtures.pingSamples(), Modifier.fillMaxWidth().height(120.dp))
            ToolChart(MetricsFixtures.iperfSamples(), Modifier.fillMaxWidth().height(120.dp))
            ToolChart(emptyList(), Modifier.fillMaxWidth().height(80.dp))
        }
    }
}

/** The shared kit ([MetricKit]): hero figure, stat tiles, ring and bar gauges, stacked bar, status grid, top list, legend. */
@Composable internal fun MetricSheetComponents(state: WorkspaceState) {
    val guests = state.overview!!.guests
    val cluster = state.history.cluster.samples.map { it.cpu }
    val hot = state.history.guests[guests.first { it.name == "ci-runner-1" }.resource]?.samples?.map { it.cpu }.orEmpty()
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel("Hero figure \u00b7 stat tiles")
            HeroFigure("Requests per second", "1284/s", caption = "last 15 minutes", trend = cluster, trendScale = 1.0)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("CPU", "61%", Modifier.weight(1f), tone = MetricThresholds.Load.tone(.61), trend = cluster, range = rangeText(cluster, ::wholePercent)?.let { "$it%" })
                StatTile("Build queue", "97%", Modifier.weight(1f), tone = MetricThresholds.Load.tone(.97), trend = hot, range = rangeText(hot, ::wholePercent)?.let { "$it%" })
                StatTile("Uptime", "23d 4h", Modifier.weight(1f), detail = "since the last kernel update")
            }
            SectionLabel("Sparkline \u00b7 axes on hover")
            val mark = LocalExpandBounds.current
            val times = state.history.cluster.samples.map { it.at }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricSparkline(cluster, Modifier.weight(1f).height(40.dp).onGloballyPositioned { mark?.invoke(it.boundsInRoot()) }, kind = AxisKind.Fraction, times = times, thresholds = MetricThresholds.Load)
                MetricSparkline(state.history.cluster.samples.map { it.netInRate }, Modifier.weight(1f).height(40.dp), kind = AxisKind.Rate, color = MetricColors.secondary, times = times)
            }
            SectionLabel("Ring and bar gauges")
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                RingGauge(null); RingGauge(.34); RingGauge(.86, peak = .9); RingGauge(.97); RingGauge(.71, size = 84.dp, thresholds = MetricThresholds.Load)
            }
            for ((f, t) in listOf(.34 to MetricThresholds.Capacity, .88 to MetricThresholds.Capacity, .97 to MetricThresholds.Capacity, .62 to MetricThresholds.Load)) {
                BarGauge(f, Modifier.fillMaxWidth(), thresholds = t, height = 6.dp, peak = (f + .05).coerceAtMost(1.0))
            }
            BarGauge(null, Modifier.fillMaxWidth(), height = 6.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel("Stacked breakdown")
            StackedBar(listOf(BarSegment("Disks", 176.0, MetricColors.category(0)), BarSegment("Containers", 8.0, MetricColors.category(2)), BarSegment("Backups", 96.0, MetricColors.category(1)),
                BarSegment("Not listed", 120.0, Tech.Muted, hatched = true)), Modifier.fillMaxWidth(), total = 500.0, marker = 450.0, legend = { "${it.toInt()} GiB" })
            SectionLabel("Status grid")
            StatusGrid(List(48) { i -> StatusCell("check-$i", when { i % 17 == 5 -> MetricTone.Critical; i % 9 == 3 -> MetricTone.Attention; i % 13 == 7 -> MetricTone.Neutral; else -> MetricTone.Good }) })
            SectionLabel("Top list")
            TopList(listOf(TopItem("ci-runner-1", .97, "97%", MetricTone.Critical), TopItem("k8s-w-3", .88, "88%", MetricTone.Critical), TopItem("db-primary", .62, "62%", MetricTone.Attention),
                TopItem("loki", .38, "38%", MetricTone.Good)), thresholds = MetricThresholds.Load)
            TopList(listOf(TopItem("vm-120-disk-1", 1.0, "128 GiB"), TopItem("vm-120-disk-0", .25, "32 GiB"), TopItem("vm-130-disk-0", .125, "16 GiB")))
            ChartLegend(listOf(LegendEntry("In", MetricColors.secondary), LegendEntry("Out", MetricColors.primary), LegendEntry("Total", MetricColors.capacity, dashed = true)))
        }
    }
}
