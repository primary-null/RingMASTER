package local.ringleader.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import local.ringleader.core.BtDevice
import local.ringleader.core.BtScan


/** Categories, never bearings. Address spreads dots deterministically inside the category sector. */
internal enum class KindSector(val label: String) { Audio("AUDIO"), Input("INPUT"), Personal("PHONE / PC"), Other("OTHER") }
internal fun kindSector(category: String?) = when {
    category?.startsWith("audio") == true -> KindSector.Audio
    category?.startsWith("input") == true -> KindSector.Input
    category == "phone" || category?.startsWith("computer") == true -> KindSector.Personal
    else -> KindSector.Other
}
internal fun kindRingAngle(address: String, category: String?): Double {
    val fraction = ((address.hashCode().toLong() and 0xffffffffL) * .6180339887 % 1.0)
    return (-87.0 + kindSector(category).ordinal * 90 + fraction * 84) * PI / 180
}
internal fun kindRingRadius(signal: Float) = ((-signal - 30f) / 70f).coerceIn(.08f, 1f)
private const val Never = Long.MIN_VALUE
/** The field's devices, prepared once per scan update: quiet devices first so active ones draw on top. */
internal class FieldModel(devices: List<BtDevice>) {
    private val plotted = devices.filter { it.rssi != null && it.fieldRssi() != null }.sortedBy { it.lastSeenMs ?: 0L }
    val size = plotted.size
    val addresses = Array(size) { plotted[it].address }
    /** Unit offsets times the radius share: multiply by the field radius for pixels. */
    val ux = FloatArray(size); val uy = FloatArray(size)
    val first = LongArray(size) { plotted[it].firstSeenMs ?: Never }
    val last = LongArray(size) { plotted[it].lastSeenMs ?: Never }
    val tint = IntArray(size) { deviceTintClass(plotted[it]) }
    val big = BooleanArray(size) { plotted[it].connected == true || plotted[it].paired == true }
    init {
        for (i in 0 until size) {
            val a = kindRingAngle(addresses[i], plotted[i].category); val r = kindRingRadius(plotted[i].fieldRssi()!!)
            ux[i] = (cos(a) * r).toFloat(); uy[i] = (sin(a) * r).toFloat()
        }
    }
    fun indexOf(address: String?) = if (address == null) -1 else addresses.indexOf(address)
}

private val FieldRings = intArrayOf(-60, -78, -100)

private fun TextMeasurer.counted(text: String, style: TextStyle): TextLayoutResult = measure(text, style).also { BtScanStats.textMeasures++ }

/**
 * Kind rings, shared by local and guest scans. Sectors are reported device categories, never direction.
 * The centre represents the scan source's adapter. Each heard device is a dot whose distance follows its
 * smoothed signal (rings at -60, -78, -100 dBm); its angle is stable within a categorical sector. A ripple marks the moment a
 * device is first heard, a flash marks each new reading, dots fade when a device goes quiet, and the outer arc is the
 * real listening time. Under Reduce motion there are no ripples or flashes.
 *
 * Two layers: the rings, labels and axes are recorded once per size and theme; the dots, ripples and arc are a layer of
 * their own that re-records each frame from arrays prepared when the scan changed.
 */
@Composable internal fun KindRings(devices: List<BtDevice>, scan: BtScan?, clock: ScanClock, live: Boolean, focus: String?, selected: String?,
    onFocus: (String?) -> Unit, onSelect: (String) -> Unit, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    var fieldWidth by remember { mutableIntStateOf(0) }
    val labelMaxPx = (fieldWidth - 8).coerceIn(1, 220)
    val model = remember(devices) { FieldModel(devices) }
    val label = remember { TextStyle(fontFamily = Tech.Mono, fontSize = 9.sp) }
    val strong = remember { TextStyle(fontFamily = Tech.Mono, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
    val showcase = Appearance.showcase
    // Ripples and reading flashes are motion: under Reduce motion they would freeze and blink with each update instead.
    val pulses = live && !LocalMotion.current.reduced
    val window = scan?.windowMs?.takeIf { it > 0 }
    val centre = if (model.size > 0) null else if (live) "LISTENING" else if (scan == null) "READY" else "NOTHING HEARD"
    val centreText = remember(measurer, centre) { centre?.let { measurer.counted(it, label) } }
    val ringTexts = remember(measurer) { FieldRings.map { measurer.counted("$it", label) } }
    val sectorTexts = remember(measurer) { KindSector.entries.map { measurer.counted(it.label, label) } }
    fun markLabel(address: String?): String? {
        val d = address?.let { a -> devices.firstOrNull { it.address == a } } ?: return null
        return kindRingLabel(d, showcase)
    }
    // The focused and the selected device are ringed and labelled; their labels are laid out when their text changes.
    val focusMark = model.indexOf(focus).takeIf { it >= 0 }
    val focusLabel = markLabel(focus.takeIf { focusMark != null })
    val focusText = remember(measurer, focusLabel, labelMaxPx) { focusLabel?.let { measurer.measure(it, strong, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, constraints = androidx.compose.ui.unit.Constraints(maxWidth = labelMaxPx)).also { BtScanStats.textMeasures++ } } }
    val selectedMark = model.indexOf(selected).takeIf { it >= 0 && selected != focus }
    val selectedLabel = markLabel(selected.takeIf { selectedMark != null })
    val selectedText = remember(measurer, selectedLabel, labelMaxPx) { selectedLabel?.let { measurer.measure(it, strong, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, constraints = androidx.compose.ui.unit.Constraints(maxWidth = labelMaxPx)).also { BtScanStats.textMeasures++ } } }
    // Read at draw time, so a hover redraws the dots layer without rebuilding its cache (the positions).
    val marks = rememberUpdatedState(listOfNotNull(focusMark?.let { m -> focusText?.let { m to it } }, selectedMark?.let { m -> selectedText?.let { m to it } }))
    val hits = remember { FieldHits() }
    val onFocusNow by rememberUpdatedState(onFocus)
    val onSelectNow by rememberUpdatedState(onSelect)
    Box(modifier.onSizeChanged { fieldWidth = it.width }.drawWithCache {
        BtScanStats.staticFieldCaches++
        val c = size.center
        val radius = (size.minDimension / 2f - 32.dp.toPx()).coerceAtLeast(1f)
        val edge = Tech.Edge; val muted = Tech.Muted
        val solid = Stroke(1.dp.toPx())
        val dashed = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 5f)))
        val outer = radius + 4.dp.toPx()
        val glow = androidx.compose.ui.graphics.Brush.radialGradient(listOf(Tech.Green.copy(alpha=.14f), Tech.Green.copy(alpha=.02f)), c, kindRingRadius(-60f)*radius)
        onDrawBehind {
            drawCircle(glow, kindRingRadius(-60f)*radius, c)
            KindSector.entries.forEachIndexed { i, _ ->
                drawArc(edge.copy(alpha = if (i % 2 == 0) .16f else .08f), -87f + i * 90f, 84f, true,
                    Offset(c.x-radius,c.y-radius), Size(radius*2,radius*2))
                val t = sectorTexts[i]
                val a = (-45.0 + i * 90) * PI / 180
                val r = radius + 19.dp.toPx()
                drawText(t, color = muted, topLeft = Offset(
                    (c.x + cos(a).toFloat()*r-t.size.width/2f).coerceIn(0f,(size.width-t.size.width).coerceAtLeast(0f)),
                    (c.y + sin(a).toFloat()*r-t.size.height/2f).coerceIn(0f,(size.height-t.size.height).coerceAtLeast(0f))))
            }
            FieldRings.forEachIndexed { i, dbm ->
                val r = kindRingRadius(dbm.toFloat()) * radius
                drawCircle(edge.copy(alpha = if (dbm == -100) .9f else .55f), r, c, style = if (dbm == -100) solid else dashed)
                val t = ringTexts[i]
                drawText(t, color = muted, topLeft = Offset(c.x + 4.dp.toPx(), c.y - r + 2.dp.toPx()))
            }
            drawLine(edge.copy(alpha = .3f), Offset(c.x - radius, c.y), Offset(c.x + radius, c.y), 1f)
            drawLine(edge.copy(alpha = .3f), Offset(c.x, c.y - radius), Offset(c.x, c.y + radius), 1f)
            if (window != null) drawCircle(edge.copy(alpha = .5f), outer, c, style = solid)
            centreText?.let { drawText(it, color = muted, topLeft = Offset(c.x - it.size.width / 2f, c.y + 12.dp.toPx())) }
        }
    }) {
        Spacer(Modifier.matchParentSize().graphicsLayer().drawWithCache {
            val c = size.center
            val radius = (size.minDimension / 2f - 32.dp.toPx()).coerceAtLeast(1f)
            val n = model.size
            val xs = FloatArray(n) { c.x + model.ux[it] * radius }
            val ys = FloatArray(n) { c.y + model.uy[it] * radius }
            hits.update(model.addresses, xs, ys); BtScanStats.hitPositions += n
            val tints = tintPalette()
            val amber = Tech.Amber; val panel = Tech.Panel.copy(alpha = .92f); val ink = Tech.Ink
            val ripple = Stroke(1.5.dp.toPx()); val ring = Stroke(1.5.dp.toPx())
            val arc = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
            val outer = radius + 4.dp.toPx()
            val arcTopLeft = Offset(c.x - outer, c.y - outer); val arcSize = Size(outer * 2, outer * 2)
            val small = 3.dp.toPx(); val large = 4.dp.toPx(); val flash = 9.dp.toPx(); val dp4 = 4.dp.toPx(); val dp22 = 22.dp.toPx()
            val head = 3.dp.toPx(); val halo = 7.dp.toPx()
            onDrawBehind {
                BtScanStats.movingFieldDraws++
                val now = clock.ms.longValue
                if (window != null) {
                    val frac = (now.toFloat() / window).coerceIn(0f, 1f)
                    drawArc(if (live) amber else amber.copy(alpha = .45f), -90f, 360f * frac, false, arcTopLeft, arcSize, style = arc)
                    if (live) {
                        val a = (-90f + 360f * frac) * PI / 180.0
                        drawCircle(amber, head, Offset(c.x + (cos(a) * outer).toFloat(), c.y + (sin(a) * outer).toFloat()))
                    }
                }
                for (i in 0 until n) {
                    val p = Offset(xs[i], ys[i])
                    val tint = tints[model.tint[i]]
                    val last = model.last[i]
                    val alpha = if (last == Never) .8f else (1f - ((now - last).coerceAtLeast(0L) - 2500f).coerceAtLeast(0f) / 9000f).coerceIn(.28f, 1f)
                    val first = model.first[i]
                    if (pulses && first != Never) {
                        val t = now - first
                        if (t in 0..1400) { val f = t / 1400f; drawCircle(tint.copy(alpha = (1f - f) * .8f), dp4 + f * dp22, p, style = ripple) }
                    }
                    if (pulses && last != Never) {
                        val t = now - last
                        if (t in 0..450) drawCircle(tint.copy(alpha = (1f - t / 450f) * .35f), flash, p)
                    }
                    val r = if (model.big[i]) large else small
                    if (last != Never && now - last > 5000) drawCircle(tint.copy(alpha = .6f), r, p, style = ring)
                    else drawCircle(tint.copy(alpha = alpha), r, p)
                    if (first != Never && now - first in 0 until 3000) drawCircle(amber, r + dp4, p, style = ring)
                }
                for ((mark, text) in marks.value) {
                    val p = Offset(xs[mark], ys[mark])
                    drawCircle(amber, (if (model.big[mark]) large else small) + dp4, p, style = ring)
                    if (marks.value.size > 1 && mark != marks.value.first().first) continue
                    val x = (p.x + 10f).coerceAtMost(size.width - text.size.width - 4f).coerceAtLeast(4f)
                    val y = (p.y - text.size.height - 6f).coerceAtLeast(2f)
                    drawRoundRect(panel, Offset(x - 4f, y - 2f), Size(text.size.width + 8f, text.size.height + 4f), CornerRadius(3f))
                    drawText(text, color = ink, topLeft = Offset(x, y))
                }
                drawCircle(amber.copy(alpha = .25f), halo, c)
                drawCircle(amber, head, c)
            }
        }.pointerInput(hits) {
            val within = 12.dp.toPx()
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent()
                    val p = e.changes.firstOrNull()?.position ?: continue
                    val hit = hits.nearest(p.x, p.y, within)
                    when (e.type) {
                        PointerEventType.Exit -> onFocusNow(null)
                        PointerEventType.Press -> { onFocusNow(hit); hit?.let(onSelectNow) }
                        else -> onFocusNow(hit)
                    }
                }
            }
        })
    }
}


/** Canvas labels conceal identity as well as ordinary Text nodes in Showcase. */
internal fun kindRingLabel(d: BtDevice, concealed: Boolean) = listOfNotNull(
    if (concealed) null else d.name?.takeIf { it.isNotBlank() },
    d.fieldRssi()?.let { "${it.toInt()} dBm" }
).joinToString("  ")
