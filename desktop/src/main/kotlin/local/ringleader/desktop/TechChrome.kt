package local.ringleader.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.interaction.*
import androidx.compose.animation.core.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The desktop component set: square-cut frames, mono labels, amber accents.
object Tech {
    val Space4 = 4.dp; val Space8 = 8.dp; val Space12 = 12.dp; val Space16 = 12.dp; val Space24 = 16.dp; val Space32 = 32.dp
    val Title = 18.sp; val Section = 16.sp; val Body = 13.sp; val Label = 11.sp; val Mono = FontFamily.Monospace
    // Colours come from the active palette (Appearance), so every screen follows the theme and background settings.
    val Canvas: Color get() = Appearance.palette.canvas
    val Black: Color get() = Appearance.palette.black
    val Panel: Color get() = Appearance.palette.panel
    val Raised: Color get() = Appearance.palette.raised
    val Edge: Color get() = Appearance.palette.edge
    val Ink: Color get() = Appearance.palette.ink
    val Muted: Color get() = Appearance.palette.muted
    val Amber: Color get() = Appearance.palette.amber
    val Green: Color get() = Appearance.palette.green
    val Red: Color get() = Appearance.palette.red
    val OnAccent: Color get() = Appearance.palette.onAccent
    val AmberWash: Color get() = Appearance.palette.amberWash
    val Light: Boolean get() = Appearance.palette.light
    val Plate = CutCornerShape(bottomEnd = 18.dp)
    val Control = CutCornerShape(bottomEnd = 8.dp)
}

/** Material colours from the active palette. Surfaces stay opaque even over a background image, so menus and dialogs read cleanly. */
val ringColors: ColorScheme get() = Appearance.palette.let { p ->
    val base = if (p.light) lightColorScheme() else darkColorScheme()
    base.copy(primary = p.amber, onPrimary = p.onAccent, primaryContainer = p.amberWash, onPrimaryContainer = p.amber,
        secondary = p.muted, secondaryContainer = p.materialRaised, onSecondaryContainer = p.ink,
        background = p.canvas, onBackground = p.ink, surface = p.materialPanel, onSurface = p.ink,
        surfaceVariant = p.materialRaised, onSurfaceVariant = p.muted, outline = p.edge, outlineVariant = p.edge.copy(alpha = .6f),
        surfaceContainer = p.materialPanel, surfaceContainerHigh = p.materialRaised, surfaceContainerHighest = p.materialRaised,
        surfaceContainerLow = p.materialPanel, surfaceContainerLowest = p.materialPanel, inverseSurface = p.ink, inverseOnSurface = p.materialPanel,
        error = p.red, onError = p.onAccent)
}

@Composable fun TechAction(label: String, modifier: Modifier = Modifier, active: Boolean = false,
    enabled: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val tint by animateColorAsState(if(active || hovered || pressed || focused) Tech.Amber else Tech.Edge, LocalMotion.current.fade(MotionDur.Quick))
    OutlinedButton(interactionSource = interaction, onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 32.dp),
        shape = Tech.Control, border = BorderStroke(1.dp, tint),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (active) Tech.AmberWash else Color.Transparent,
            contentColor = if (active) Tech.Amber else Tech.Ink), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
        Text(label.uppercase(), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.6.sp)
    }
}

@Composable fun SectionLabel(label: String, modifier: Modifier = Modifier, suffix: String? = null) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(14.dp, 3.dp).background(Tech.Amber))
        Text(label.uppercase(), fontFamily = FontFamily.Monospace, fontSize = 12.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold, color = Tech.Muted)
        if (suffix != null) Text(suffix, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Tech.Ink)
    }
}

@Composable fun TechPanel(modifier: Modifier = Modifier, rail: Color = Tech.Edge, selected: Boolean = false,
    onClick: (() -> Unit)? = null, background: Color = Tech.Panel, padding: androidx.compose.ui.unit.Dp = Tech.Space12, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = modifier.clip(Tech.Plate).then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
        color = if (selected) Tech.Raised else background, shape = Tech.Plate,
        border = BorderStroke(1.dp, if (selected) Tech.Amber else Tech.Edge)) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(rail))
            Column(Modifier.weight(1f).padding(padding), content = content)
        }
    }
}

@Composable fun StatusTag(label: String, healthy: Boolean = false) {
    val reduce = LocalReduceMotion.current
    val phase = LocalMotionPhase.current
    val pulsing = healthy && !reduce && phase.active.value
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Canvas(Modifier.size(5.dp)) {
            val alpha = if (!pulsing) 1f else 0.4f + 0.6f * ((phase.pulse.floatValue - 0.15f) / 0.45f).coerceIn(0f, 1f)
            drawRect(if (healthy) Tech.Green.copy(alpha = alpha) else Tech.Muted)
        }
        Text(label.uppercase(), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = if (healthy) Tech.Green else Tech.Muted)
    }
}

@Composable fun RingMark() {
    Canvas(Modifier.size(34.dp)) {
        drawCircle(Tech.Edge, radius = size.minDimension * 0.42f, style = Stroke(2.dp.toPx()))
        drawArc(Tech.Amber, -90f, 240f, false, style = Stroke(3.dp.toPx()))
        drawCircle(Tech.Ink, radius = 3.dp.toPx())
        drawCircle(Tech.Amber, radius = 3.dp.toPx(), center = Offset(size.width * 0.5f, size.height * 0.08f))
    }
}

@Composable fun Appear(content: @Composable () -> Unit) {
    val reduce=LocalReduceMotion.current
    var visible by remember { mutableStateOf(reduce) }
    LaunchedEffect(Unit) { visible=true }
    androidx.compose.animation.AnimatedVisibility(visible,enter=androidx.compose.animation.fadeIn(tween(if(reduce) 0 else 180)),exit=androidx.compose.animation.fadeOut(tween(0))) { content() }
}

/**
 * Hover-only tooltip near the pointer. Material's TooltipBox opens a popup that takes the next click
 * outside it as a dismiss, so a quick click on a neighbouring control only closed the tooltip.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable fun HelpTip(text: String, identity: Boolean = false, content: @Composable () -> Unit) {
    androidx.compose.foundation.TooltipArea(
        tooltip = {
            Surface(color = Tech.Raised, shape = Tech.Control, border = BorderStroke(1.dp, Tech.Edge), shadowElevation = 4.dp) {
                Text(text, Modifier.widthIn(max = 360.dp).padding(horizontal = 8.dp, vertical = 5.dp).then(if (identity) Modifier.identity() else Modifier), fontSize = 11.sp, lineHeight = 15.sp, color = Tech.Ink)
            }
        },
        delayMillis = 450,
        tooltipPlacement = androidx.compose.foundation.TooltipPlacement.CursorPoint(offset = androidx.compose.ui.unit.DpOffset(4.dp, 18.dp)),
    ) { content() }
}
/** Title-bar control marks. [Restore] is shown while the window is maximised. */
enum class WindowGlyph(val label: String) { Minimize("Minimise"), Maximize("Maximise"), Restore("Restore"), Close("Close") }

@Composable fun WindowControl(glyph: WindowGlyph, onClick: () -> Unit) {
    val close = glyph == WindowGlyph.Close
    val interaction=remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val ink = if (close && hovered) Tech.OnAccent else Tech.Ink
    TextButton(onClick=onClick,interactionSource=interaction,modifier=Modifier.width(40.dp).height(32.dp).semantics { contentDescription = glyph.label },shape=androidx.compose.ui.graphics.RectangleShape,contentPadding=PaddingValues(0.dp),colors=ButtonDefaults.textButtonColors(containerColor=if(hovered) { if(close) Tech.Red else Tech.Edge } else Color.Transparent,contentColor=ink)) {
        // Drawn, not font glyphs: a crisp 1dp mark at every scale, and no missing-glyph box on a Linux font set.
        Canvas(Modifier.size(10.dp)) { drawWindowGlyph(glyph, ink) }
    }
}

/** The old text labels ("—", "□", "×"), still used by the screenshot scenes that rebuild the title bar. */
@Composable fun WindowControl(label: String, onClick: () -> Unit) =
    WindowControl(when (label) { "—", "-" -> WindowGlyph.Minimize; "×", "x" -> WindowGlyph.Close; else -> WindowGlyph.Maximize }, onClick)

internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWindowGlyph(glyph: WindowGlyph, color: Color) {
    val stroke = 1.dp.toPx().coerceAtLeast(1f)
    val half = stroke / 2
    val w = size.width; val h = size.height
    when (glyph) {
        // Centred on a pixel row, so the 1px bar stays solid instead of smearing over two rows.
        WindowGlyph.Minimize -> { val y = (h / 2).toInt() + half; drawLine(color, Offset(0f, y), Offset(w, y), stroke) }
        WindowGlyph.Maximize -> drawRect(color, Offset(half, half), androidx.compose.ui.geometry.Size(w - stroke, h - stroke), style = Stroke(stroke))
        WindowGlyph.Restore -> {
            // A front square low left, and the edge of the one behind it showing above and to the right.
            val back = w * .2f
            drawRect(color, Offset(half, back + half), androidx.compose.ui.geometry.Size(w - back - stroke, h - back - stroke), style = Stroke(stroke))
            val behind = androidx.compose.ui.graphics.Path().apply {
                moveTo(back + half, back); lineTo(back + half, half); lineTo(w - half, half); lineTo(w - half, h - back - half); lineTo(w - back, h - back - half)
            }
            drawPath(behind, color, style = Stroke(stroke))
        }
        WindowGlyph.Close -> {
            drawLine(color, Offset(0f, 0f), Offset(w, h), stroke)
            drawLine(color, Offset(w, 0f), Offset(0f, h), stroke)
        }
    }
}

/**
 * Keyboard focus ring: a 1dp amber outline in [shape], drawn [gap] outside the element so it still shows on an amber
 * control. Rail rows draw the same mark at their own edge.
 */
fun Modifier.focusRing(focused: Boolean, shape: androidx.compose.ui.graphics.Shape = androidx.compose.ui.graphics.RectangleShape, gap: androidx.compose.ui.unit.Dp = 0.dp): Modifier =
    if (!focused) this else this.drawWithContent {
        drawContent()
        val g = gap.toPx(); val stroke = 1.dp.toPx()
        val ring = androidx.compose.ui.geometry.Size(size.width + 2 * g - stroke, size.height + 2 * g - stroke)
        val outline = shape.createOutline(ring, layoutDirection, this)
        translate(-g + stroke / 2, -g + stroke / 2) { drawOutline(outline, Tech.Amber, style = Stroke(stroke)) }
    }

/**
 * Compact 28dp desktop action for toolbars and cards. [glow] (0 to 1) blends the plain style towards the amber one;
 * it is for an action that lights up for a moment and then settles (see [ArrivalAction]).
 */
@Composable fun MiniAction(label: String, primary: Boolean = false, enabled: Boolean = true, width: androidx.compose.ui.unit.Dp? = null, glow: Float = 0f, onClick: () -> Unit) =
    // Desktop pointer target: drop Material's 48dp touch minimum so the button stays 28dp tall.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides androidx.compose.ui.unit.Dp.Unspecified) {
    val lit = if (primary) 1f else glow.coerceIn(0f, 1f)
    TextButton(onClick=onClick, enabled=enabled, shape=Tech.Control, contentPadding=PaddingValues(horizontal=if (width != null) 0.dp else 10.dp, vertical=0.dp),
        modifier=Modifier.height(26.dp).then(if (width != null) Modifier.width(width) else Modifier).border(1.dp, if (enabled) androidx.compose.ui.graphics.lerp(Tech.Edge, Tech.Amber, lit) else Tech.Edge, Tech.Control),
        colors=ButtonDefaults.textButtonColors(contentColor=androidx.compose.ui.graphics.lerp(Tech.Ink, Tech.Amber, lit), disabledContentColor=Tech.Muted.copy(alpha=.5f))) {
        Text(label.uppercase(), fontFamily=FontFamily.Monospace, fontSize=11.sp, fontWeight=FontWeight.Bold, letterSpacing=.5.sp)
    }
}

internal const val ARRIVAL_HOLD_MS = 900
internal const val ARRIVAL_FADE_MS = 700

/**
 * 1 when [arrival] changes while [lit], held for a moment, then faded to 0. Amber that announces something (a guest
 * was selected, the console took the keyboard) and then gets out of the way instead of staying lit. With reduced
 * motion it is on for the hold and then off, with no fade.
 */
@Composable fun rememberArrivalGlow(arrival: Any?, lit: Boolean = true): State<Float> {
    val glow = remember { androidx.compose.animation.core.Animatable(0f) }
    val reduced = LocalReduceMotion.current
    LaunchedEffect(arrival, lit) {
        if (!lit) { glow.snapTo(0f); return@LaunchedEffect }
        glow.snapTo(1f)
        // One millisecond, not zero, under reduced motion: a zero-length tween ignores its delay and ends at once.
        glow.animateTo(0f, tween(if (reduced) 1 else ARRIVAL_FADE_MS, delayMillis = ARRIVAL_HOLD_MS, easing = LinearOutSlowInEasing))
    }
    return glow.asState()
}

/** An action that lights up amber when [arrival] changes, then settles to the plain style. Only this button redraws while it fades. */
@Composable fun ArrivalAction(label: String, arrival: Any?, enabled: Boolean = true, onClick: () -> Unit) {
    val glow by rememberArrivalGlow(arrival)
    MiniAction(label, enabled = enabled, glow = glow, onClick = onClick)
}

@Composable fun IdentityTile(label: String, template: Boolean = false) {
    Box(Modifier.height(22.dp).widthIn(min = 26.dp).background(Tech.Raised, Tech.Control).border(1.dp, Tech.Edge, Tech.Control).padding(start = 3.dp, end = 5.dp), contentAlignment = Alignment.Center) {
        Text(if(template) "$label*" else label, fontFamily=Tech.Mono, fontSize=9.sp, lineHeight=11.sp, color=Tech.Muted, maxLines=1, softWrap=false)
    }
}

/**
 * On/off control in the Tech language: a cut-corner track with a square thumb that slides to the amber side.
 * Pass a null [onChange] when a parent row already handles the click (menus), so the switch only shows state.
 */
@Composable fun TechSwitch(checked: Boolean, onChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val motion = LocalMotion.current
    // The thumb slide is movement (it snaps under Reduce motion); the colours are tints and keep their short fade.
    val travel by animateFloatAsState(if (checked) 1f else 0f, if (motion.reduced) snap() else tween(MotionDur.Short, easing = MotionEase.Standard))
    val edge by animateColorAsState(when {
        !enabled -> Tech.Edge.copy(alpha = .5f)
        checked -> Tech.Amber
        hovered || focused -> Tech.Amber.copy(alpha = .6f)
        else -> Tech.Edge
    }, motion.fade(MotionDur.Quick))
    val fill by animateColorAsState(if (checked && enabled) Tech.AmberWash else Color.Transparent, motion.fade(MotionDur.Quick))
    val thumb by animateColorAsState(when { !enabled -> Tech.Muted.copy(alpha = .4f); checked -> Tech.Amber; else -> Tech.Muted }, motion.fade(MotionDur.Quick))
    val shape = CutCornerShape(bottomEnd = 6.dp)
    // The drawn track stays 36 by 18; an interactive switch takes a 24dp tall pointer target around it (WCAG 2.5.8).
    val target = if (onChange != null) Modifier.height(SWITCH_TARGET.dp).toggleable(checked, interactionSource = interaction, indication = null, enabled = enabled, role = Role.Switch) { onChange(it) }
        .hoverable(interaction).pointerHoverIcon(androidx.compose.ui.input.pointer.PointerIcon.Hand) else Modifier
    Box(modifier.width(36.dp).then(target), contentAlignment = Alignment.Center) {
    Box(Modifier.size(36.dp, 18.dp).focusRing(focused && onChange != null, shape, gap = 3.dp).clip(shape).background(fill).border(1.dp, edge, shape)
        .padding(3.dp)) {
        // The thumb is a 12dp square; the off side shows a faint tick so both states read without colour.
        Canvas(Modifier.fillMaxSize()) {
            val side = size.height
            val x = (size.width - side) * travel
            if (!checked) drawRect(Tech.Edge, Offset(size.width - side / 2 - 1.dp.toPx(), side * .25f), androidx.compose.ui.geometry.Size(2.dp.toPx(), side * .5f))
            drawRect(thumb, Offset(x, 0f), androidx.compose.ui.geometry.Size(side, side))
        }
    }
    }
}

/** Pointer target height of an interactive [TechSwitch], in dp. */
internal const val SWITCH_TARGET = 24

/** Check box in the Tech language: a cut-corner square that fills amber with a dark tick. The whole [label] row toggles it. */
@Composable fun TechCheck(checked: Boolean, onChange: (Boolean) -> Unit, label: String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val motion = LocalMotion.current
    val fill by animateColorAsState(if (checked && enabled) Tech.Amber else Color.Transparent, motion.fade(MotionDur.Quick))
    val edge by animateColorAsState(when { !enabled -> Tech.Edge.copy(alpha = .5f); checked || hovered -> Tech.Amber; else -> Tech.Edge }, motion.fade(MotionDur.Quick))
    val shape = CutCornerShape(bottomEnd = 4.dp)
    Row(modifier.heightIn(min = 28.dp).toggleable(checked, interactionSource = interaction, indication = null, enabled = enabled, role = Role.Checkbox) { onChange(it) }
        .hoverable(interaction).pointerHoverIcon(androidx.compose.ui.input.pointer.PointerIcon.Hand),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(16.dp).focusRing(focused, shape, gap = 3.dp).clip(shape).background(fill).border(1.dp, edge, shape)) {
            if (checked) Canvas(Modifier.fillMaxSize().padding(3.dp)) {
                val tick = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width * .08f, size.height * .52f); lineTo(size.width * .4f, size.height * .82f); lineTo(size.width * .94f, size.height * .18f)
                }
                drawPath(tick, Tech.OnAccent, style = Stroke(2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Square))
            }
        }
        Text(label, fontSize = 13.sp, color = if (enabled) Tech.Ink else Tech.Muted)
    }
}

/** Slider in the Tech language: a 2dp track, amber up to the value, and an upright cut-corner thumb. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun TechSlider(value: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    range: ClosedFloatingPointRange<Float> = 0f..1f, onDone: (() -> Unit)? = null) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val dragged by interaction.collectIsDraggedAsState()
    val accent = if (enabled) Tech.Amber else Tech.Muted.copy(alpha = .5f)
    Slider(value, onChange, modifier.hoverable(interaction), enabled = enabled, valueRange = range, onValueChangeFinished = onDone, interactionSource = interaction,
        thumb = {
            val shape = CutCornerShape(bottomEnd = 4.dp)
            Box(Modifier.size(10.dp, if (hovered || dragged) 20.dp else 16.dp).clip(shape).background(if (dragged) Tech.Amber else Tech.Raised).border(1.dp, accent, shape))
        },
        track = { state ->
            val span = state.valueRange.endInclusive - state.valueRange.start
            val fraction = if (span == 0f) 0f else ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f)
            Canvas(Modifier.fillMaxWidth().height(2.dp)) {
                drawRect(Tech.Edge)
                drawRect(accent, size = androidx.compose.ui.geometry.Size(size.width * fraction, size.height))
            }
        })
}

/**
 * Modal dialog in the Tech language, drawn inside the RingMASTER window (never an OS window with a light title bar).
 * Escape and a click outside call [onDismiss]; [actions] sit bottom right, primary action first.
 */
@Composable fun TechDialog(title: String, onDismiss: () -> Unit, width: androidx.compose.ui.unit.Dp = 520.dp, subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.width(width).heightIn(max = 760.dp), color = ringColors.surface, shape = Tech.Plate, border = BorderStroke(1.dp, Tech.Edge), shadowElevation = 12.dp) {
            Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(Tech.Amber))
                Column(Modifier.weight(1f).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(title, fontSize = Tech.Title, fontWeight = FontWeight.SemiBold, color = Tech.Ink)
                        if (subtitle != null) Text(subtitle, fontSize = Tech.Label, color = Tech.Muted)
                    }
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Tech.Space8, Alignment.End), verticalAlignment = Alignment.CenterVertically, content = actions)
                }
            }
        }
    }
}

/** A tab strip in the Tech language: mono capitals, an amber underline under the chosen tab, a hairline under the rest. */
@Composable fun TechTabs(tabs: List<String>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val motion = LocalMotion.current
    Row(modifier.fillMaxWidth().drawBehind { drawLine(Tech.Edge, Offset(0f, size.height - .5f), Offset(size.width, size.height - .5f), 1f) },
        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        tabs.forEach { title ->
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            val focused by interaction.collectIsFocusedAsState()
            val active = title == selected
            val ink by animateColorAsState(when { active -> Tech.Amber; hovered || focused -> Tech.Ink; else -> Tech.Muted }, motion.fade(MotionDur.Quick))
            val bar by animateColorAsState(when { active -> Tech.Amber; hovered -> Tech.Edge; else -> Color.Transparent }, motion.fade(MotionDur.Quick))
            Box(Modifier.height(32.dp).focusRing(focused).hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null, role = Role.Tab) { onSelect(title) }
                .pointerHoverIcon(androidx.compose.ui.input.pointer.PointerIcon.Hand)
                .drawBehind { drawRect(bar, Offset(0f, size.height - 2.dp.toPx()), androidx.compose.ui.geometry.Size(size.width, 2.dp.toPx())) }
                .padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                Text(title.uppercase(), fontFamily = Tech.Mono, fontSize = 11.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, letterSpacing = .8.sp, color = ink, maxLines = 1)
            }
        }
    }
}

/** Text field in the Tech language: mono label above, a cut-corner box that turns amber when focused and red with an [error]. */
@Composable fun TechField(value: String, onValue: (String) -> Unit, label: String, modifier: Modifier = Modifier, placeholder: String? = null,
    error: String? = null, hint: String? = null, singleLine: Boolean = true, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val edge = when { error != null -> Tech.Red; focused -> Tech.Amber; else -> Tech.Edge }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label.uppercase(), fontFamily = Tech.Mono, fontSize = 10.sp, letterSpacing = .8.sp, color = Tech.Muted, maxLines = 1)
        Box(Modifier.fillMaxWidth().then(if (singleLine) Modifier.height(32.dp) else Modifier.heightIn(min = 64.dp, max = 140.dp))
            .border(1.dp, edge, Tech.Control).padding(horizontal = 8.dp, vertical = if (singleLine) 0.dp else 7.dp),
            contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart) {
            if (value.isEmpty() && placeholder != null) Text(placeholder, fontFamily = Tech.Mono, fontSize = 12.sp, color = Tech.Muted.copy(alpha = .55f), maxLines = 1)
            androidx.compose.foundation.text.BasicTextField(value, onValue, Modifier.fillMaxWidth(), enabled = enabled, singleLine = singleLine,
                textStyle = androidx.compose.ui.text.TextStyle(color = if (enabled) Tech.Ink else Tech.Muted, fontSize = 12.sp, fontFamily = Tech.Mono),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Tech.Amber), interactionSource = interaction)
        }
        (error ?: hint)?.let { Text(it, fontSize = 11.sp, color = if (error != null) Tech.Red else Tech.Muted) }
    }
}

/**
 * Thin progress bar in the Tech language. With a [fraction] it fills amber to that point; without one an amber
 * segment sweeps along the hairline (a still half bar under Reduce motion).
 */
@Composable fun TechProgress(modifier: Modifier = Modifier, fraction: Float? = null) {
    // Still (not sweeping) under Reduce motion and while the window is in the background: an endless animation would
    // otherwise keep repainting an unfocused window.
    val reduce = LocalReduceMotion.current || !LocalWindowActive.current
    val sweep = if (fraction == null && !reduce) rememberInfiniteTransition(label = "progress").animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "sweep").value else 0f
    Canvas(modifier.fillMaxWidth().height(2.dp)) {
        drawRect(Tech.Edge)
        when {
            fraction != null -> drawRect(Tech.Amber, size = androidx.compose.ui.geometry.Size(size.width * fraction.coerceIn(0f, 1f), size.height))
            reduce -> drawRect(Tech.Amber.copy(alpha = .7f), size = androidx.compose.ui.geometry.Size(size.width * .5f, size.height))
            else -> {
                val segment = size.width * .28f
                val start = (-segment + (size.width + segment) * sweep).coerceAtLeast(0f)
                val end = (-segment + (size.width + segment) * sweep + segment).coerceAtMost(size.width)
                if (end > start) drawRect(Tech.Amber, Offset(start, 0f), androidx.compose.ui.geometry.Size(end - start, size.height))
            }
        }
    }
}

/**
 * "Web UI" toolbar action: opens [url] (a Proxmox web page) in the system browser. Hidden when there is no link
 * (demo, PDM), in browser mode, or when the desktop has no browser; a failed launch says so in the tooltip.
 */
@Composable fun WebUiAction(url: String?) {
    if (url == null || LocalAppHost.current.web) return
    // null: idle. Failed copies the link. Pending means xdg-open is still running and was left alone.
    var outcome by remember(url) { mutableStateOf<BrowserLaunch?>(null) }
    var opening by remember(url) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    HelpTip(when (outcome) {
        BrowserLaunch.Failed -> "No browser opened on this computer, so the link was copied. Paste it into a browser."
        BrowserLaunch.Pending -> "Browser launch still running."
        else -> "Open this page in the Proxmox web interface (it asks for its own login)"
    }) {
        MiniAction(when { opening -> "Opening…"; outcome == BrowserLaunch.Failed -> "Link copied"; else -> "Web UI ↗" }, enabled = !opening) {
            opening = true
            scope.launch {
                val launch = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { openInBrowser(url) }
                if (launch == BrowserLaunch.Failed) runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(url), null) }
                outcome = launch; opening = false
            }
        }
    }
}

/** Small busy mark: three amber squares that light in turn (all lit under Reduce motion). */
@Composable fun TechBusy(modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current || !LocalWindowActive.current
    val step = if (reduce) -1f else rememberInfiniteTransition(label = "busy").animateFloat(0f, 3f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "step").value
    Canvas(modifier.size(18.dp, 6.dp)) {
        val side = size.height
        val gap = (size.width - 3 * side) / 2
        repeat(3) { i -> drawRect(Tech.Amber.copy(alpha = if (step < 0 || step.toInt() == i) 1f else .25f), Offset(i * (side + gap), 0f), androidx.compose.ui.geometry.Size(side, side)) }
    }
}
