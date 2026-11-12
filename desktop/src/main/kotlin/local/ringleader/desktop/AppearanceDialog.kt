package local.ringleader.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Appearance section bodies used by the unified Settings window. */
@Composable internal fun AppearanceSettingsSection() {
    ThemeSection()
    HorizontalDivider(color = Tech.Edge)
    ShowcaseSection()
    HorizontalDivider(color = Tech.Edge)
    BackgroundSection()
}

@Composable private fun Caption(text: String) = Text(text, fontSize = 10.sp, lineHeight = 14.sp, fontFamily = Tech.Mono, color = Tech.Muted)

@Composable private fun ThemeSection() {
    SectionLabel("Theme")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ThemeMode.entries.forEach { mode ->
            val selected = Appearance.theme == mode
            val swatch = when (mode) {
                ThemeMode.Dark -> listOf(DarkPalette); ThemeMode.Light -> listOf(LightPalette); ThemeMode.PitchBlack -> listOf(PitchBlackPalette)
                ThemeMode.System -> listOf(DarkPalette, LightPalette)
            }
            Column(Modifier.width(150.dp).clip(Tech.Control).border(1.dp, if (selected) Tech.Amber else Tech.Edge, Tech.Control)
                .clickable { Appearance.chooseTheme(mode) }.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Canvas(Modifier.fillMaxWidth().height(54.dp)) {
                    val part = size.width / swatch.size
                    swatch.forEachIndexed { i, p ->
                        val x = i * part
                        drawRect(p.canvas, Offset(x, 0f), Size(part, size.height))
                        drawRect(p.panel, Offset(x + 6f, 6f), Size(part - 12f, size.height - 12f))
                        drawRect(p.edge, Offset(x + 6f, 6f), Size(part - 12f, size.height - 12f), style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
                        drawRect(p.amber, Offset(x + 12f, 14f), Size(18f, 4f))
                        drawRect(p.ink.copy(alpha = .8f), Offset(x + 12f, 24f), Size(part * .5f, 3f))
                        drawRect(p.muted, Offset(x + 12f, 32f), Size(part * .35f, 3f))
                        drawCircle(p.green, 3.5f, Offset(x + part - 18f, 18f))
                    }
                }
                Text(mode.label.uppercase(), fontSize = 10.sp, letterSpacing = 1.sp, fontFamily = Tech.Mono, fontWeight = FontWeight.Bold, color = if (selected) Tech.Amber else Tech.Ink)
            }
        }
    }
    Caption("Light uses soft greys with deeper accents for contrast. Pitch Black uses true black for OLED screens. System follows the operating system. Changes fade in.")
}

@Composable internal fun SizeSettingsSection() {
    SectionLabel("Interface size · this display")
    Caption("Interface size scales everything on this display.")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MiniAction("Auto", primary = Appearance.scaleIsAuto) { Appearance.chooseScale(null) }
        listOf(.9f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f).forEach { s ->
            MiniAction("${(s * 100).toInt()} %", primary = !Appearance.scaleIsAuto && kotlin.math.abs(Appearance.uiScale - s) < .01f) { Appearance.chooseScale(s) }
        }
    }
    BackdropSlider("Size", "${Math.round(Appearance.uiScale * 100)} %", Appearance.uiScale, .7f..2f, true, { Appearance.chooseScale(it) }, {})
    Caption("Auto chooses ${Math.round(Appearance.autoScaleValue * 100)} %. Ctrl + / Ctrl − / Ctrl 0 work anywhere.")
    DisplayFacts()
    HorizontalDivider(color = Tech.Edge)
    SectionLabel("Text size · all displays")
    Caption("Text size scales only text, on every display.")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(.9f, 1f, 1.15f, 1.3f, 1.5f).forEach { s ->
            MiniAction("${Math.round(s * 100)} %", primary = kotlin.math.abs(Appearance.textScale - s) < .01f) { Appearance.chooseTextScale(s) }
        }
    }
    BackdropSlider("Text", "${Math.round(Appearance.textScale * 100)} %", Appearance.textScale, .85f..1.5f, true, { Appearance.chooseTextScale(it) }, {})
    Text("The quick brown fox jumps over the lazy dog. 0123456789", fontSize = 14.sp, color = Tech.Ink)
}

@Composable private fun BackgroundSection() {
    val host = LocalAppHost.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val name = Appearance.background
    SectionLabel("Background")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val preview by produceState<ImageBitmap?>(null, name) { value = name?.let { withContext(Dispatchers.IO) { Backgrounds.render(it, 0f) } } }
        Box(Modifier.size(176.dp, 99.dp).clip(Tech.Control).background(Tech.Black).border(1.dp, Tech.Edge, Tech.Control), contentAlignment = Alignment.Center) {
            preview?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } ?: Text("NONE", fontSize = 10.sp, fontFamily = Tech.Mono, color = Tech.Muted)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MiniAction(if (busy) "Importing…" else "Choose image", primary = true, enabled = !busy && host.filePicker) {
                    val picked = host.pickFile("Choose a background image", setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")) ?: return@MiniAction
                    busy = true; error = null
                    scope.launch {
                        try {
                            val stored = withContext(Dispatchers.IO) { Backgrounds.import(picked) }
                            Appearance.chooseBackground(stored)
                            withContext(Dispatchers.IO) { Backgrounds.prune(stored) }
                        } catch (e: Exception) { error = e.message ?: "The image could not be used." }
                        finally { busy = false }
                    }
                }
                MiniAction("Remove", enabled = name != null && !busy) { Appearance.chooseBackground(null); scope.launch(Dispatchers.IO) { Backgrounds.prune(null) } }
            }
            Caption("PNG, JPEG, WebP, GIF or BMP up to 64 MB. A copy is kept in RingMASTER's settings folder.")
            error?.let { Text(it, Modifier.identity(), fontSize = 11.sp, color = Tech.Amber) }
        }
    }
    var blur by remember { mutableStateOf(Appearance.blur) }
    BackdropSlider("Blur", "${blur.toInt()} px", blur, 0f..48f, name != null, onChange = { blur = it }) { Appearance.setBackdrop(blur = blur) }
    BackdropSlider("Dim", "${(Appearance.dim * 100).toInt()} %", Appearance.dim, 0f..0.8f, name != null, onChange = { Appearance.setBackdrop(dim = it, persist = false) }) { Appearance.setBackdrop() }
    BackdropSlider("Vignette", "${(Appearance.vignette * 100).toInt()} %", Appearance.vignette, 0f..0.8f, name != null, onChange = { Appearance.setBackdrop(vignette = it, persist = false) }) { Appearance.setBackdrop() }
}

@Composable private fun BackdropSlider(label: String, value: String, current: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean, onChange: (Float) -> Unit, onDone: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label.uppercase(), Modifier.width(108.dp), fontSize = 10.sp, letterSpacing = 1.sp, fontFamily = Tech.Mono, color = Tech.Muted)
        TechSlider(current, onChange, Modifier.weight(1f).height(28.dp), enabled = enabled, range = range, onDone = onDone)
        Text(value, Modifier.width(52.dp), fontSize = 11.sp, fontFamily = Tech.Mono, color = Tech.Ink)
    }
}

@Composable private fun ShowcaseSection() {
    SectionLabel("Showcase blur")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TechSwitch(Appearance.showcase, { Appearance.chooseShowcase(it) })
        Column(Modifier.weight(1f)) {
            Text("Blur names, IDs and addresses", fontSize = 13.sp)
            Caption("For demos, recordings and screenshots. Hover a value to peek. Ctrl+Shift+B toggles it anywhere.")
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("STRENGTH", Modifier.width(76.dp), fontSize = 10.sp, letterSpacing = 1.sp, fontFamily = Tech.Mono, color = Tech.Muted)
        ShowcaseStrength.entries.forEach { s -> MiniAction(s.label, primary = Appearance.strength == s) { Appearance.chooseStrength(s) } }
    }
    FlowRow(Modifier.fillMaxWidth().border(1.dp, Tech.Edge, Tech.Control).background(Tech.Black.copy(alpha = .35f)).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("SAMPLE", fontSize = 9.sp, letterSpacing = 1.sp, fontFamily = Tech.Mono, color = Tech.Muted)
        Text("debian-workbench", Modifier.identity(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text("VM 120 · node-a", Modifier.identity(), fontSize = 11.sp, fontFamily = Tech.Mono, color = Tech.Muted)
        Text("pve.example.test:8006", Modifier.identity(), fontSize = 11.sp, fontFamily = Tech.Mono)
    }
}

/**
 * What the window's display is and how RingMASTER draws on it. Static facts only: an earlier frame counter here asked
 * for a frame every refresh while the section was open, which pegged a core on software rendering.
 */
@Composable private fun DisplayFacts() {
    val display = Appearance.display
    val api = LocalRenderApi.current
    val tier = LocalMotion.current.tier
    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("THIS DISPLAY", fontSize = 10.sp, letterSpacing = 1.sp, fontFamily = Tech.Mono, color = Tech.Muted)
        Text(display?.summary ?: "Browser viewport", Modifier.identity(), fontSize = 11.sp, fontFamily = Tech.Mono, color = Tech.Ink)
        if (display != null) Text(displayDrawingLine(api, SoftwareGl.detected, tier), fontSize = 11.sp, fontFamily = Tech.Mono, color = Tech.Ink)
    }
}

/** "Drawing with Direct3D · full motion", the render line under the display facts. */
internal fun displayDrawingLine(api: org.jetbrains.skiko.GraphicsApi?, softwareGl: Boolean, tier: MotionTier): String =
    "Drawing with ${renderApiLabel(api, softwareGl)} · " + when (tier) {
        MotionTier.Full -> "full motion"
        MotionTier.Lite -> "light motion for software drawing"
        MotionTier.Reduced -> "reduced motion"
    }
