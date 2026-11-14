package local.ringleader.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import local.ringleader.core.BtDevice
import local.ringleader.core.BtScan

/*
 * Drawing for the Bluetooth scan view (BtScanView in BluetoothScreen.kt): the scan clock, the signal field, the
 * heard timeline and the per-row trace and "heard ago" text.
 *
 * The rules that keep a live scan smooth (measured by BluetoothBenchTest, RINGMASTER_BT_BENCH=1):
 *  - everything that depends only on the scan (sorting, positions, paths, text layouts, colours) is prepared when the
 *    scan changes, in `remember` or a `drawWithCache` block; a draw block only reads the clock and issues draw calls;
 *  - what moves every frame lives in its own graphics layer, so a frame re-records that layer and nothing else;
 *  - rows read a coarse clock that changes four times a second, not the frame clock.
 */

/** Steps of [ScanClock.coarse]: "heard ago" text never needs more than this. */
internal const val BtCoarseTickMs = 250L

/**
 * Milliseconds into the scan. [ms] runs on with the frame clock between live updates (read it only in draw blocks);
 * [coarse] is [ms] rounded down to [BtCoarseTickMs], for text.
 */
@Stable internal class ScanClock(start: Long) {
    val ms = mutableLongStateOf(start)
    val coarse = mutableLongStateOf(start - start % BtCoarseTickMs)
    fun set(value: Long) {
        ms.longValue = value
        val c = value - value % BtCoarseTickMs
        if (coarse.longValue != c) coarse.longValue = c
    }
}

/**
 * While a live scan streams, the clock follows the frame clock from the last update ([receivedAt], System.nanoTime):
 * every display frame on a GPU, every [MotionDur.LiteTick] on software rendering. It holds still under Reduce motion,
 * while the window is unfocused or minimised, and once the scan has finished: then nothing asks for frames.
 */
@Composable internal fun rememberScanClock(scan: BtScan?, receivedAt: Long, live: Boolean): ScanClock {
    val window = scan?.windowMs ?: Long.MAX_VALUE
    val base = (scan?.elapsedMs ?: scan?.windowMs ?: 0L).coerceAtMost(window)
    val clock = remember { ScanClock(base) }
    val motion = LocalMotion.current
    val running = live && scan != null && !motion.reduced && LocalWindowActive.current
    val tick = if (motion.lite) MotionDur.LiteTick.toLong() else 0L
    LaunchedEffect(running, base, receivedAt, window, tick) {
        clock.set(base)
        if (!running) return@LaunchedEffect
        while (isActive && clock.ms.longValue < window) {
            if (tick > 0) delay(tick)
            withFrameNanos { frame -> clock.set((base + (frame - receivedAt).coerceAtLeast(0L) / 1_000_000L).coerceAtMost(window)) }
        }
    }
    return clock
}

/** Smoothed signal stored when the sample arrived, or the raw reading for a one-shot result. */
internal fun BtDevice.fieldRssi(): Float? = smoothedRssi ?: rssi?.takeIf { it < 0 }?.toFloat()

/** Radius share for a signal: about -35 dBm sits near the centre, -100 dBm on the edge. */
internal fun fieldRadius(rssi: Float) = ((-rssi - 33f) / 67f).coerceIn(.1f, 1f)

/** A stable bearing per address. Direction is not measured; the angle only keeps dots apart and in the same place. */
internal fun fieldAngle(address: String): Double = ((address.hashCode().toLong() and 0xffffffffL) * 0.6180339887 % 1.0) * 2 * PI

/** Tint classes, resolved against the palette at draw time: connected, paired, unnamed, named. */
internal fun deviceTintClass(d: BtDevice) = when {
    d.connected == true -> 0
    d.paired == true -> 1
    d.name.isNullOrBlank() -> 2
    else -> 3
}

internal fun tintPalette() = arrayOf(Tech.Green, Tech.Amber, Tech.Muted, Tech.Ink)

internal fun deviceTint(d: BtDevice) = tintPalette()[deviceTintClass(d)]

/** Sentinel for "not heard" in the prepared arrays. */
private const val Never = Long.MIN_VALUE

/** Pixel positions of the field's dots, rebuilt with the draw cache (scan update or resize), read by the hit test. */
internal class FieldHits {
    var addresses: Array<String> = emptyArray(); private set
    var xs = FloatArray(0); private set
    var ys = FloatArray(0); private set
    fun update(addresses: Array<String>, xs: FloatArray, ys: FloatArray) { this.addresses = addresses; this.xs = xs; this.ys = ys }
    fun nearest(x: Float, y: Float, within: Float): String? {
        var best = -1; var bestD = within * within
        for (i in xs.indices) {
            val dx = xs[i] - x; val dy = ys[i] - y; val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = i }
        }
        return if (best < 0) null else addresses[best]
    }
}

/** The timeline's data, prepared once per scan update. */
internal class TimelineModel(devices: List<BtDevice>) {
    val firsts: LongArray = devices.mapNotNull { it.firstSeenMs }.sorted().toLongArray()
    /** Reading times per tint class (see [deviceTintClass]). */
    val readings: Array<LongArray> = Array(4) { cls -> devices.filter { deviceTintClass(it) == cls }.flatMap { d -> d.samples.map { it.ms } }.toLongArray() }
    val readingCount = readings.sumOf { it.size }
    val total = maxOf(devices.size, firsts.size, 1)
}

/**
 * Discovery over the listening window: the stepped line is how many devices had been heard by each moment, the ticks
 * along the bottom are individual readings, and the focused device's signal is traced in green. Everything but the
 * last step and the "now" line is recorded once per update; those two are a small layer of their own.
 */
@Composable internal fun HeardTimeline(devices: List<BtDevice>, scan: BtScan?, clock: ScanClock, focus: String?, modifier: Modifier) {
    val model = remember(devices) { TimelineModel(devices) }
    val focused = focus?.let { a -> devices.firstOrNull { it.address == a } }
    val window = scan?.windowMs
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("HEARD ${model.firsts.size}", fontFamily = Tech.Mono, fontSize = 10.sp, letterSpacing = 1.sp, color = Tech.Amber, maxLines = 1)
            Text(if (focused != null && focused.samples.isNotEmpty()) "${focused.samples.size} READINGS · THIS DEVICE" else "${model.readingCount} READINGS",
                fontFamily = Tech.Mono, fontSize = 10.sp, letterSpacing = 1.sp, color = if (focused != null) Tech.Green else Tech.Muted, maxLines = 1)
        }
        val samples = focused?.samples
        Box(Modifier.weight(1f).fillMaxWidth().drawWithCache {
            val w = size.width; val h = size.height
            val band = 8.dp.toPx()
            val plotH = h - band - 3.dp.toPx()
            val edge = Tech.Edge; val amber = Tech.Amber; val green = Tech.Green
            val tints = tintPalette()
            if (window == null || window <= 0) return@drawWithCache onDrawBehind { drawLine(edge, Offset(0f, plotH), Offset(w, plotH), 1f) }
            fun x(ms: Long) = (ms.toFloat() / window).coerceIn(0f, 1f) * w
            fun level(count: Int) = plotH - plotH * count / model.total
            val step = Path().apply {
                moveTo(0f, plotH)
                model.firsts.forEachIndexed { i, ms -> lineTo(x(ms), level(i)); lineTo(x(ms), level(i + 1)) }
            }
            val lastX = model.firsts.lastOrNull()?.let(::x) ?: 0f
            val area = Path().apply { addPath(step); lineTo(lastX, plotH); close() }
            val ticks = model.readings.map { r -> FloatArray(r.size * 4).also { a -> r.forEachIndexed { i, ms -> val xi = x(ms); a[i * 4] = xi; a[i * 4 + 1] = h - band; a[i * 4 + 2] = xi; a[i * 4 + 3] = h } } }
            val tickPaints = tints.map { t -> Paint().apply { color = t.copy(alpha = .55f); strokeWidth = 1f } }
            fun y(rssi: Int) = plotH - plotH * ((rssi + 100).coerceIn(0, 70) / 70f)
            val trace = samples?.takeIf { it.isNotEmpty() }?.let { s -> Path().apply { s.forEachIndexed { i, p -> if (i == 0) moveTo(x(p.ms), y(p.rssi)) else lineTo(x(p.ms), y(p.rssi)) } } }
            val dots = samples?.map { Offset(x(it.ms), y(it.rssi)) }.orEmpty()
            val line = Stroke(1.5.dp.toPx())
            onDrawBehind {
                drawLine(edge, Offset(0f, plotH), Offset(w, plotH), 1f)
                drawPath(area, amber.copy(alpha = .12f))
                drawPath(step, amber, style = line)
                drawIntoCanvas { cv -> ticks.forEachIndexed { i, a -> if (a.isNotEmpty()) cv.drawRawPoints(PointMode.Lines, a, tickPaints[i]) } }
                trace?.let { drawPath(it, green, style = line) }
                if (dots.isNotEmpty()) drawPoints(dots, PointMode.Points, green, strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
            }
        }) {
            if (window != null && window > 0) Spacer(Modifier.matchParentSize().graphicsLayer().drawWithCache {
                val w = size.width; val h = size.height
                val plotH = h - 8.dp.toPx() - 3.dp.toPx()
                val amber = Tech.Amber
                val lastX = model.firsts.lastOrNull()?.let { (it.toFloat() / window).coerceIn(0f, 1f) * w } ?: 0f
                val y = plotH - plotH * model.firsts.size / model.total
                val width = 1.5.dp.toPx()
                onDrawBehind {
                    val now = clock.ms.longValue.coerceAtMost(window)
                    val nx = (now.toFloat() / window).coerceIn(0f, 1f) * w
                    if (nx > lastX) {
                        drawRect(amber.copy(alpha = .12f), Offset(lastX, y), Size(nx - lastX, plotH - y))
                        drawLine(amber, Offset(lastX, y), Offset(nx, y), width)
                    }
                    if (now < window) drawLine(amber.copy(alpha = .6f), Offset(nx, 0f), Offset(nx, h), 1f)
                }
            })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0 s", fontFamily = Tech.Mono, fontSize = 9.sp, color = Tech.Muted)
            Text(window?.let { "${it / 1000} s" } ?: "—", fontFamily = Tech.Mono, fontSize = 9.sp, color = Tech.Muted)
        }
    }
}

/** The device's readings across the window as a small line, -100 dBm at the bottom. Built once per new reading. */
@Composable internal fun SignalTrace(device: BtDevice, window: Long?, modifier: Modifier) {
    val s = device.samples
    val cls = deviceTintClass(device)
    Spacer(modifier.drawWithCache {
        val edge = Tech.Edge.copy(alpha = .6f)
        val tint = tintPalette()[cls]
        val base = size.height - .5f
        if (s.isEmpty() || window == null || window <= 0) return@drawWithCache onDrawBehind { drawLine(edge, Offset(0f, base), Offset(size.width, base), 1f) }
        fun x(ms: Long) = (ms.toFloat() / window).coerceIn(0f, 1f) * size.width
        fun y(rssi: Int) = size.height - size.height * ((rssi + 100).coerceIn(0, 70) / 70f)
        val dot = if (s.size == 1) Offset(x(s[0].ms), y(s[0].rssi)) else null
        val path = if (s.size > 1) Path().apply { s.forEachIndexed { i, v -> if (i == 0) moveTo(x(v.ms), y(v.rssi)) else lineTo(x(v.ms), y(v.rssi)) } } else null
        val stroke = Stroke(1.dp.toPx()); val r = 1.5.dp.toPx()
        onDrawBehind {
            drawLine(edge, Offset(0f, base), Offset(size.width, base), 1f)
            dot?.let { drawCircle(tint, r, it) }
            path?.let { drawPath(it, tint, style = stroke) }
        }
    })
}

/** How long ago the device was last heard: "now" within a second; a dash when it was never heard. */
@Composable internal fun HeardAgo(device: BtDevice, clock: ScanClock, modifier: Modifier) {
    val last = device.lastSeenMs
    if (last == null) { Text(if (device.rssi != null) "·" else "—", modifier, color = Tech.Muted, fontFamily = Tech.Mono, fontSize = 10.sp, maxLines = 1); return }
    // The coarse clock changes four times a second, and this recomposes only when the whole second changes.
    val ago by remember(last, clock) { derivedStateOf { ((clock.coarse.longValue - last).coerceAtLeast(0L) / 1000L).toInt() } }
    Text(if (ago < 1) "now" else "${ago}s", modifier, color = if (ago < 3) Tech.Green else Tech.Muted, fontFamily = Tech.Mono, fontSize = 10.sp, maxLines = 1)
}
