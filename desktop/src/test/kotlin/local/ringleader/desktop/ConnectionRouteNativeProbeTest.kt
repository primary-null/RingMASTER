@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package local.ringleader.desktop

import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.awt.ComposeWindow
import local.ringleader.core.RouteTagState
import java.awt.MouseInfo
import java.awt.Rectangle
import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Real-window hover, focus, scroll and Escape acceptance for the route tag. Synthetic data only, explicit opt-in:
 * RINGMASTER_ROUTE_NATIVE_PROBE=<output folder>. Optional RINGMASTER_ROUTE_NATIVE_RENDER=<Skiko GraphicsApi name>
 * (for example SOFTWARE_COMPAT) selects the renderer for the whole run. No owner settings, network, vault or other window.
 *
 * Assertions read the test window's own composed surface (PrintWindow with PW_RENDERFULLCONTENT), which another
 * window drawn over it cannot reach; RINGMASTER_ROUTE_NATIVE_CAPTURE=screen asserts on the Robot screen grab instead.
 * Each frame also records where the other source disagrees, so screen and surface are compared on every frame.
 * Only frames verified free of foreign paint are written.
 *
 * RINGMASTER_ROUTE_NATIVE_INPUT=synthetic is the pointer-free variant for a desktop whose pointer and foreground belong
 * to someone else: the same real native window (renderer, DWM surface, popup layer) is opened off-screen, not
 * focusable and not on top, and hover, Tab, End, Home and Escape are AWT events dispatched to that window's own Compose
 * component. Nothing moves the OS pointer or takes the foreground. It is rendering evidence for the window surface; it
 * is not physical hover or OS keyboard-focus validation, which needs the default (physical) input mode.
 *
 * Capture protocol. Compose lays out and reports bounds in device pixels (density = OS scale x interface scale);
 * AWT window geometry and Robot coordinates are logical (user-space) units. Robot.createScreenCapture returns a
 * user-space image that the JDK bilinearly shrinks from the device grab, so at any OS scale other than 100 % its
 * pixels cannot be compared with Compose bounds, and 1 px strokes are blended away. This probe therefore takes the
 * device-resolution variant of createMultiResolutionScreenCapture and converts every coordinate explicitly. The
 * previous probe cropped Compose device-pixel bounds out of the shrunken image; at 125 % that "tag" crop reached
 * into the popup's top edge and reported a vanished tag that the pictures did not show.
 *
 * Every retained frame is checked in full, not by a difference count: the tag's own pixels equal the baseline in
 * every state, everything outside the expected popup/tooltip/focus-ring areas equals the baseline, hover and focus
 * draw the same popup pixel for pixel, a frame is accepted only after repeated identical grabs, and leaving the tag
 * or pressing Escape restores the baseline. A plain Compose TooltipArea control runs through the same protocol.
 */
class ConnectionRouteNativeProbeTest {
    private data class Case(
        val name: String, val width: Int, val height: Int, val light: Boolean, val ui: Float, val text: Float,
        val state: RouteTagState, val mode: RouteTagMode, val scroll: Boolean = false, val control: Boolean = false,
    )

    private val cases = listOf(
        Case("wide-dark", 900, 680, light = false, ui = 1f, text = 1f, state = RouteTagSamples.direct, mode = RouteTagMode.Full, control = true),
        Case("narrow-light", 460, 560, light = true, ui = 1f, text = 1f, state = RouteTagSamples.tailscale, mode = RouteTagMode.Short),
        Case("wide-dark-ui200-text150", 900, 680, light = false, ui = 2f, text = 1.5f, state = RouteTagSamples.wireguard, mode = RouteTagMode.Glyph, scroll = true),
        Case("narrow-light-ui200-text150", 560, 600, light = true, ui = 2f, text = 1.5f, state = RouteTagSamples.longNames, mode = RouteTagMode.Glyph, scroll = true),
    )

    /** Integer device-pixel rectangle, inclusive left/top, exclusive right/bottom. */
    private data class Box(val l: Int, val t: Int, val r: Int, val b: Int) {
        val w get() = r - l
        val h get() = b - t
        fun inflate(d: Int) = Box(l - d, t - d, r + d, b + d)
        fun contains(x: Int, y: Int) = x in l until r && y in t until b
        fun intersects(o: Box) = l < o.r && o.l < r && t < o.b && o.t < b
        fun clip(w: Int, h: Int) = Box(l.coerceIn(0, w), t.coerceIn(0, h), r.coerceIn(0, w), b.coerceIn(0, h))
        override fun toString() = "[$l,$t ${w}x$h]"
    }

    private class Layout {
        val tag = AtomicReference<Rect>()
        val sentinel = AtomicReference<Rect>()
        val control = AtomicReference<Rect>()
        val focused = AtomicBoolean(false)
        val baseDensity = AtomicReference<Float>()
    }

    @Test fun nativeHoverFocusScrollAndEscape() {
        val output = System.getenv("RINGMASTER_ROUTE_NATIVE_PROBE")?.let(::File) ?: return
        output.mkdirs()
        System.getenv("RINGMASTER_ROUTE_NATIVE_RENDER")?.takeIf { it.isNotBlank() }?.let { System.setProperty("skiko.renderApi", it) }
        val manifest = StringBuilder()
        manifest.appendLine("RingMASTER synthetic route-tag native acceptance")
        manifest.appendLine("java=${System.getProperty("java.version")} os=${System.getProperty("os.name")} ${System.getProperty("os.version")}")
        manifest.appendLine("skiko.renderApi property=${System.getProperty("skiko.renderApi") ?: "(default)"}")
        manifest.appendLine("input=${inputMode()}" + if (inputMode() == "synthetic") " (AWT events dispatched to the test window's Compose component; no OS pointer or foreground; not physical hover/OS focus validation)" else " (Robot pointer and keys on the test window)")
        if (inputMode() == "synthetic") manifest.appendLine("placement=" + if (behind()) "on-screen behind other windows (toBack), not focusable" else "off-screen, not focusable")
        manifest.appendLine("assertion capture=${captureMode()} (screen: Robot device-resolution screen grab; window: PrintWindow PW_RENDERFULLCONTENT of the test window only); the other source is recorded as a cross-check")
        for (source in listOf("src/main/kotlin/local/ringleader/desktop/ConnectionRouteTag.kt", "src/test/kotlin/local/ringleader/desktop/ConnectionRouteNativeProbeTest.kt"))
            File(source).takeIf { it.isFile }?.let { manifest.appendLine("source $source sha256=${sha256(it.readBytes())}") }
        val synthetic = inputMode() == "synthetic"
        val pointer = if (synthetic) null else MouseInfo.getPointerInfo().location
        val zone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
        val robot = if (synthetic) null else Robot().apply { autoDelay = 40 }
        val failures = mutableListOf<String>()
        try {
            for (case in cases) {
                val folder = File(output, case.name).apply { deleteRecursively(); mkdirs() }
                try { runCase(case, robot, folder, manifest) }
                catch (error: AssertionError) { failures += "${case.name}: ${error.message}"; manifest.appendLine("FAIL ${case.name}: ${error.message}") }
            }
        } finally {
            java.util.TimeZone.setDefault(zone)
            SwingUtilities.invokeAndWait { Appearance.preview(light = false) }
            if (robot != null && pointer != null) robot.mouseMove(pointer.x, pointer.y)
            File(output, "manifest.txt").writeText(manifest.toString())
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    private fun runCase(case: Case, robot: Robot?, folder: File, manifest: StringBuilder) {
        val synthetic = robot == null
        val layout = Layout()
        lateinit var window: ComposeWindow
        var created = false
        val frames = linkedMapOf<String, BufferedImage>()
        val safe = linkedSetOf<String>()
        val crossCheck = linkedMapOf<String, String>()
        fun edt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)
        try {
            edt {
                Appearance.preview(light = case.light)
                window = ComposeWindow().apply {
                    created = true
                    title = "RingMASTER synthetic route acceptance"
                    isUndecorated = true
                    if (synthetic) {
                        // Never shown over anyone's work and never activated: off every screen, not focusable.
                        focusableWindowState = false
                        isAutoRequestFocus = false
                        type = java.awt.Window.Type.UTILITY
                    } else isAlwaysOnTop = true
                    // Multiples of 4 so 125 % and 150 % land on whole device pixels.
                    setBounds(80, if (synthetic && !behind()) 8000 else 80, case.width, case.height)
                    setContent { Scene(case, layout) }
                    isVisible = true
                    if (!synthetic) toFront() else if (behind()) toBack()
                }
            }
            val deadline = System.nanoTime() + 10_000_000_000L
            while ((layout.tag.get() == null || layout.sentinel.get() == null || (case.control && layout.control.get() == null)) && System.nanoTime() < deadline) Thread.sleep(50)
            assertTrue(layout.tag.get() != null, "native tag was not laid out")

            var origin = java.awt.Point(); var content = java.awt.Point(); var size = java.awt.Dimension(); var scaleX = 1.0; var scaleY = 1.0
            edt {
                origin = window.locationOnScreen; size = window.size
                val pane = window.contentPane.locationOnScreen
                content = java.awt.Point(pane.x - origin.x, pane.y - origin.y)
                val tx = window.graphicsConfiguration.defaultTransform
                scaleX = tx.scaleX; scaleY = tx.scaleY
            }
            val deviceW = (size.width * scaleX).roundToInt(); val deviceH = (size.height * scaleY).roundToInt()
            val composeDensity = layout.baseDensity.get()
            assertEquals(scaleX.toFloat(), composeDensity, 0.001f, "Compose window density must equal the OS scale")
            // Compose bounds are relative to the content root; the capture starts at the window origin.
            fun dev(r: Rect) = Box(floor(r.left + content.x * scaleX).toInt(), floor(r.top + content.y * scaleY).toInt(),
                ceil(r.right + content.x * scaleX).toInt(), ceil(r.bottom + content.y * scaleY).toInt())
            fun screen(xDev: Double, yDev: Double) = java.awt.Point(origin.x + (xDev / scaleX).roundToInt(), origin.y + (yDev / scaleY).roundToInt())
            val px = composeDensity * case.ui
            val tag = dev(layout.tag.get())
            val sentinel = dev(layout.sentinel.get())
            val control = layout.control.get()?.let(::dev)
            // The focus ring is drawn 2 dp outside the tag with a 1 dp stroke; allow anti-aliasing.
            val ring = tag.inflate(ceil(3 * px).toInt() + 2)
            manifest.appendLine("case ${case.name}: window logical ${size.width}x${size.height} at ${origin.x},${origin.y}, OS scale $scaleX x $scaleY, device ${deviceW}x$deviceH, " +
                "compose density $composeDensity, interface ${case.ui}, text ${case.text}, theme ${if (case.light) "light" else "dark"}, mode ${case.mode}")
            manifest.appendLine("  tag $tag sentinel $sentinel" + (control?.let { " control $it" } ?: ""))

            fun grabScreen(): BufferedImage {
                val variants = robot!!.createMultiResolutionScreenCapture(Rectangle(origin.x, origin.y, size.width, size.height)).resolutionVariants
                val native = variants.maxBy { it.getWidth(null) }
                val image = BufferedImage(native.getWidth(null), native.getHeight(null), BufferedImage.TYPE_INT_RGB)
                image.createGraphics().apply { drawImage(native, 0, 0, null); dispose() }
                return image
            }
            var hwnd: Long = 0
            edt { hwnd = window.windowHandle }
            // The desktop may be shared with other automation. Every pointer move is remembered and checked at each
            // capture, so input taken by another program is reported as such instead of as a rendering fault.
            var expected: java.awt.Point? = null
            // Synthetic input goes to the Compose component under the point, in its own coordinates.
            fun target(windowX: Int, windowY: Int): java.awt.Component {
                var c: java.awt.Component? = null
                edt { c = window.findComponentAt(windowX, windowY) }
                return c ?: error("no component at $windowX,$windowY")
            }
            var entered = false
            fun moveTo(p: java.awt.Point) {
                if (robot != null) { robot.mouseMove(p.x, p.y); expected = p; return }
                val wx = p.x - origin.x; val wy = p.y - origin.y
                val c = target(wx, wy)
                edt {
                    val at = SwingUtilities.convertPoint(window, wx, wy, c)
                    fun send(id: Int) = c.dispatchEvent(java.awt.event.MouseEvent(c, id, System.currentTimeMillis(), 0, at.x, at.y, p.x, p.y, 0, false, java.awt.event.MouseEvent.NOBUTTON))
                    if (!entered) { send(java.awt.event.MouseEvent.MOUSE_ENTERED); entered = true }
                    send(java.awt.event.MouseEvent.MOUSE_MOVED)
                }
            }
            fun assertPointer(name: String) {
                val want = expected ?: return
                val now = MouseInfo.getPointerInfo().location
                assertTrue(kotlin.math.abs(now.x - want.x) <= 1 && kotlin.math.abs(now.y - want.y) <= 1,
                    "$name: the pointer was moved by another program (expected ${want.x},${want.y}, found ${now.x},${now.y}); input contention, not rendering")
            }
            val windowFirst = synthetic || captureMode() == "window"
            fun grab(): BufferedImage = if (windowFirst) printWindow(hwnd) else grabScreen()
            /** A frame is accepted when three grabs 150 ms apart are identical (after [settle] ms); a partial frame cannot pass. */
            fun capture(name: String, settle: Long = 700): BufferedImage {
                Thread.sleep(settle)
                assertPointer(name)
                var last = grab(); var stable = 0; var tries = 0
                while (stable < 2 && tries++ < 20) { Thread.sleep(150); val next = grab(); if (same(last, next)) stable++ else { stable = 0; last = next } }
                assertTrue(stable >= 2, "$name: the window never produced a stable frame")
                assertEquals(deviceW to deviceH, last.width to last.height, "$name: capture is not at device resolution")
                // Cross-check against the other capture source; only the count and place of disagreement are recorded.
                val other = if (synthetic) null else runCatching { if (windowFirst) grabScreen() else printWindow(hwnd) }.getOrNull()
                val all = Box(0, 0, deviceW, deviceH)
                assertPointer(name)
                crossCheck[name] = if (synthetic) "not applicable (off-screen synthetic-input window)" else if (other == null || other.width != last.width || other.height != last.height) "unavailable"
                    else changesIn(last, other, all)?.let { "${diffIn(last, other, all)} px differ within $it" } ?: "identical"
                frames[name] = last
                return last
            }
            val away = screen(deviceW - 6.0, 6.0)  // top-right corner: never under the tag, popup or control
            fun moveAway() = moveTo(away)
            fun key(code: Int) {
                if (robot == null) {
                    val c = target(size.width / 2, size.height / 2)
                    val char = if (code == KeyEvent.VK_TAB) '\t' else if (code == KeyEvent.VK_ESCAPE) 27.toChar() else KeyEvent.CHAR_UNDEFINED
                    edt {
                        val now = System.currentTimeMillis()
                        c.dispatchEvent(KeyEvent(c, KeyEvent.KEY_PRESSED, now, 0, code, char, KeyEvent.KEY_LOCATION_STANDARD))
                        c.dispatchEvent(KeyEvent(c, KeyEvent.KEY_RELEASED, now + 1, 0, code, char, KeyEvent.KEY_LOCATION_STANDARD))
                    }
                    return
                }
                var stillActive = false
                edt { stillActive = window.isActive }
                assertTrue(stillActive, "another program took the foreground before a key press; input contention, not rendering")
                robot.keyPress(code); robot.keyRelease(code)
            }

            // Activate only this window (Windows may refuse a programmatic foreground request) on its empty corner.
            moveAway()
            var active = synthetic
            if (!synthetic) for (attempt in 1..3) {
                assertPointer("activation")
                robot!!.mousePress(InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                Thread.sleep(250)
                edt { active = window.isActive }
                if (active) break
                edt { window.toFront() }
            }
            assertTrue(active, "the synthetic window must own focus before keyboard input (another program kept the foreground)")

            val baseline = capture("baseline")
            val bg = baseline.getRGB(deviceW - 3, deviceH / 2)
            val tagInk = ink(baseline, tag, bg)
            assertTrue(tagInk > tag.w * tag.h / 20, "baseline: the tag is not drawn (ink $tagInk in $tag)")
            assertTrue(ink(baseline, sentinel, bg) > sentinel.w * sentinel.h / 30, "baseline: the sentinel text is not drawn")
            // The tag's computed bounds line up with its drawn border (the probe's coordinate conversion is right).
            assertTrue(ink(baseline, Box(tag.l, tag.t - 3, tag.r, tag.t), bg) == 0 && ink(baseline, Box(tag.l, tag.t, tag.r, tag.t + 2), bg) > tag.w / 2,
                "baseline: tag bounds do not match the drawn border; coordinate conversion is wrong")
            // Nothing else is on screen inside the window (no foreign overlay, no stray paint).
            val known = listOfNotNull(tag, sentinel, control).map { it.inflate(1) }
            var strayBox: Box? = null
            val stray = countWhere(baseline) { x, y ->
                (known.none { it.contains(x, y) } && baseline.getRGB(x, y) != bg).also { hit ->
                    if (hit) strayBox = strayBox?.let { Box(minOf(it.l, x), minOf(it.t, y), maxOf(it.r, x + 1), maxOf(it.b, y + 1)) } ?: Box(x, y, x + 1, y + 1)
                }
            }
            assertEquals(0, stray, "baseline: unexpected paint outside the tag, sentinel and control, within $strayBox (another window over the test window?)")
            safe += "baseline"
            manifest.appendLine("  baseline tag ink $tagInk px, background #${Integer.toHexString(bg and 0xffffff)}")

            // Region the route popup may occupy: below the tag (with its shadow) across the window; nothing above the tag's bottom.
            val shadow = ceil(8 * px).toInt()
            val popupArea = Box(0, tag.b, deviceW, deviceH)

            /** Differences against the baseline, as a bounding box, and how many fall outside [allowed]. */
            var lastOutside: Box? = null
            fun changes(frame: BufferedImage, allowed: List<Box>): Pair<Box?, Int> {
                var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = -1; var b = -1; var outside = 0
                var ol = Int.MAX_VALUE; var ot = Int.MAX_VALUE; var or = -1; var ob = -1
                for (y in 0 until deviceH) for (x in 0 until deviceW) if (frame.getRGB(x, y) != baseline.getRGB(x, y)) {
                    if (x < l) l = x; if (y < t) t = y; if (x >= r) r = x + 1; if (y >= b) b = y + 1
                    if (allowed.none { it.contains(x, y) }) {
                        outside++
                        if (x < ol) ol = x; if (y < ot) ot = y; if (x >= or) or = x + 1; if (y >= ob) ob = y + 1
                    }
                }
                lastOutside = if (or < 0) null else Box(ol, ot, or, ob)
                return (if (r < 0) null else Box(l, t, r, b)) to outside
            }
            // The tag is a cut-corner shape (Tech.Control, bottom-end 8 dp). Its focus ring follows a larger cut-corner
            // outline whose diagonal crosses the empty triangle of the tag's bounding box, so the tag is compared by shape.
            val cut = 8 * px
            fun inTagShape(x: Int, y: Int) = tag.contains(x, y) && x + y < tag.r + tag.b - cut - 1
            val corner = countWhere(baseline) { x, y -> tag.contains(x, y) && !inTagShape(x, y) && distance(baseline.getRGB(x, y), bg) > 40 }
            assertTrue(corner <= 2 * cut, "baseline: tag content found in the cut corner ($corner px); the shape comparison would hide it")
            fun assertTagIntact(name: String, frame: BufferedImage) {
                val d = countWhere(frame) { x, y -> inTagShape(x, y) && frame.getRGB(x, y) != baseline.getRGB(x, y) }
                assertEquals(0, d, "$name: the tag's own pixels changed ($d px in $tag)")
            }
            fun assertPopup(name: String, frame: BufferedImage, ringAllowed: Boolean): Box {
                val (_, outside) = changes(frame, listOfNotNull(popupArea, ring.takeIf { ringAllowed }))
                assertEquals(0, outside, "$name: $outside px changed outside the popup area${if (ringAllowed) " and focus ring" else ""}, within $lastOutside")
                safe += name
                assertTagIntact(name, frame)
                val popup = changesIn(baseline, frame, popupArea, exclude = ring)
                assertTrue(popup != null, "$name: no popup was drawn")
                popup!!
                assertTrue(popup.t >= tag.b, "$name: the popup $popup covers the tag $tag")
                val maxWidth = minOf(deviceW - 16 * px, 400 * px) + 2 * shadow
                assertTrue(popup.w <= maxWidth + 2, "$name: changed area $popup is wider than the popup can be (${maxWidth.roundToInt()} px)")
                // Text rows actually drawn inside the popup: count separated ink bands down its height.
                val bands = inkBands(frame, popup)
                assertTrue(bands >= 5, "$name: the popup $popup holds only $bands text bands; its content is not fully drawn")
                if (!sentinel.intersects(popup.inflate(shadow))) assertEquals(0, diffIn(baseline, frame, sentinel), "$name: the uncovered sentinel text changed")
                manifest.appendLine("  $name popup $popup, $bands text bands, tag unchanged, outside-area changes 0" + if (sentinel.intersects(popup.inflate(shadow))) ", sentinel covered by design" else ", sentinel unchanged")
                return popup
            }
            fun assertBaseline(name: String, frame: BufferedImage, ringExpected: Boolean) {
                val (box, outside) = changes(frame, listOfNotNull(ring.takeIf { ringExpected }))
                assertEquals(0, outside, "$name: the frame did not return to the baseline (changes within $lastOutside)")
                safe += name
                assertTagIntact(name, frame)
                if (ringExpected) assertTrue(box != null && box.intersects(ring), "$name: the focus ring is not drawn")
                manifest.appendLine("  $name equals baseline" + if (ringExpected) " except the focus ring $box" else "")
            }

            // Hover: the details after the hover delay, tag and everything else untouched.
            val center = screen((tag.l + tag.r) / 2.0, (tag.t + tag.b) / 2.0)
            moveTo(center)
            val hover = capture("hover", settle = ROUTE_TIP_DELAY_MS + 700)
            val hoverPopup = assertPopup("hover", hover, ringAllowed = false)
            moveAway()
            assertBaseline("unhovered", capture("unhovered"), ringExpected = false)

            // Keyboard: Tab focuses the tag (the first focusable) and opens the same details at once.
            key(KeyEvent.VK_TAB)
            val focused = capture("focused")
            assertTrue(layout.focused.get(), "Tab did not focus the tag")
            val focusPopup = assertPopup("focused", focused, ringAllowed = true)
            assertEquals(hoverPopup, focusPopup, "hover and focus popups differ in place")
            // The light theme's popup shadow reaches above the popup, where the focus ring's lower edge also lands.
            assertEquals(0, diffIn(hover, focused, hoverPopup, exclude = ring), "hover and focus popups are not drawn identically")
            if (case.scroll) {
                key(KeyEvent.VK_END)
                val end = capture("focused-end")
                val endPopup = assertPopup("focused-end", end, ringAllowed = true)
                assertTrue(diffIn(focused, end, endPopup, exclude = ring) > 0, "End did not scroll the details")
                key(KeyEvent.VK_HOME)
                val home = capture("focused-home")
                assertEquals(0, diffIn(focused, home, Box(0, 0, deviceW, deviceH)), "Home did not return to the identical focused frame")
                assertPopup("focused-home", home, ringAllowed = true)
            }
            key(KeyEvent.VK_ESCAPE)
            assertBaseline("escaped", capture("escaped"), ringExpected = true)

            if (control != null) {
                // Control: a plain Text in Compose's own TooltipArea, through the same protocol.
                val target = screen((control.l + control.r) / 2.0, (control.t + control.b) / 2.0)
                moveTo(target)
                val tip = capture("control-hover")
                val controlArea = Box(0, control.b, deviceW, deviceH)
                val (box, outside) = changes(tip, listOf(controlArea, ring))
                assertEquals(0, outside, "control-hover: changes outside the control tooltip area, within $lastOutside")
                safe += "control-hover"
                assertTagIntact("control-hover", tip)
                assertTrue(box != null && diffIn(baseline, tip, controlArea, exclude = ring) > 0, "control-hover: the plain tooltip was not drawn")
                assertEquals(0, diffIn(baseline, tip, control), "control-hover: the control text changed")
                manifest.appendLine("  control-hover tooltip ${changesIn(baseline, tip, controlArea, exclude = ring)}, tag and control text unchanged")
                moveAway()
                Thread.sleep(300)
            }
            manifest.appendLine("PASS ${case.name}")
        } finally {
            if (created) {
                var api = ""
                edt { api = window.renderApi.toString() }
                manifest.appendLine("  renderer actual $api; frames ${frames.keys.joinToString()}")
            }
            // Only frames whose every pixel outside the expected areas was checked are kept: a foreign overlay
            // (another window, a system menu) is never written to disk.
            for ((name, image) in frames) {
                // A window-surface capture cannot contain another window, so it is kept even when a check failed.
                if (name !in safe && !(synthetic || captureMode() == "window")) { manifest.appendLine("  ${case.name}/$name not kept (not verified free of foreign content)"); continue }
                val file = File(folder, "$name.png")
                ImageIO.write(image, "png", file)
                manifest.appendLine("  ${case.name}/$name.png ${image.width}x${image.height} sha256=${sha256(file.readBytes())}; ${if (captureMode() == "window") "screen" else "window"} cross-check ${crossCheck[name]}")
            }
            edt { if (created) window.dispose() }
            Thread.sleep(200)
        }
    }

    @androidx.compose.runtime.Composable private fun Scene(case: Case, layout: Layout) {
        val base = LocalDensity.current
        layout.baseDensity.set(base.density)
        CompositionLocalProvider(LocalDensity provides Density(base.density * case.ui, base.fontScale * case.text)) {
            MaterialTheme(colorScheme = ringColors) {
                CompositionLocalProvider(LocalReduceMotion provides true) {
                    Surface(Modifier.fillMaxSize(), color = Tech.Canvas, contentColor = Tech.Ink) {
                        Box(Modifier.fillMaxSize().background(Tech.Canvas).padding(24.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                ConnectionRouteTag(case.state, case.mode,
                                    Modifier.onFocusChanged { layout.focused.set(it.hasFocus) }.onGloballyPositioned { layout.tag.set(it.boundsInWindow()) },
                                    clock = { RouteTagSamples.now })
                                Spacer(Modifier.weight(1f))
                                if (case.control) TooltipArea(
                                    tooltip = { Surface(color = Tech.Raised, contentColor = Tech.Ink) { Text("Plain Compose tooltip control", Modifier.padding(8.dp), fontSize = 12.sp) } },
                                    delayMillis = 300,
                                    tooltipPlacement = TooltipPlacement.ComponentRect(Alignment.BottomCenter, Alignment.BottomCenter, DpOffset(0.dp, 6.dp)),
                                ) { Text("CONTROL", Modifier.onGloballyPositioned { layout.control.set(it.boundsInWindow()) }, fontSize = 12.sp, color = Tech.Ink) }
                            }
                            Text("Synthetic connection; no server access", Modifier.align(Alignment.BottomEnd).onGloballyPositioned { layout.sentinel.set(it.boundsInWindow()) },
                                fontSize = 12.sp, color = Tech.Muted)
                        }
                    }
                }
            }
        }
    }

    private fun same(a: BufferedImage, b: BufferedImage): Boolean {
        if (a.width != b.width || a.height != b.height) return false
        for (y in 0 until a.height) for (x in 0 until a.width) if (a.getRGB(x, y) != b.getRGB(x, y)) return false
        return true
    }
    private fun diffIn(a: BufferedImage, b: BufferedImage, box: Box, exclude: Box? = null): Int {
        val c = box.clip(a.width, a.height); var n = 0
        for (y in c.t until c.b) for (x in c.l until c.r) if (exclude?.contains(x, y) != true && a.getRGB(x, y) != b.getRGB(x, y)) n++
        return n
    }
    private fun changesIn(a: BufferedImage, b: BufferedImage, area: Box, exclude: Box? = null): Box? {
        val c = area.clip(a.width, a.height)
        var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = -1; var bt = -1
        for (y in c.t until c.b) for (x in c.l until c.r) if (exclude?.contains(x, y) != true && a.getRGB(x, y) != b.getRGB(x, y)) {
            if (x < l) l = x; if (y < t) t = y; if (x >= r) r = x + 1; if (y >= bt) bt = y + 1
        }
        return if (r < 0) null else Box(l, t, r, bt)
    }
    private fun ink(image: BufferedImage, box: Box, bg: Int): Int {
        val c = box.clip(image.width, image.height); var n = 0
        for (y in c.t until c.b) for (x in c.l until c.r) if (distance(image.getRGB(x, y), bg) > 40) n++
        return n
    }
    private inline fun countWhere(image: BufferedImage, test: (Int, Int) -> Boolean): Int {
        var n = 0
        for (y in 0 until image.height) for (x in 0 until image.width) if (test(x, y)) n++
        return n
    }
    /** Separated horizontal bands of text-like ink inside [box] (inset past its border), measured against the popup fill. */
    private fun inkBands(image: BufferedImage, box: Box): Int {
        val inner = Box(box.l + 6, box.t + 6, box.r - 6, box.b - 6).clip(image.width, image.height)
        if (inner.w <= 0 || inner.h <= 0) return 0
        val fill = mostCommon(image, inner)
        var bands = 0; var inBand = false
        for (y in inner.t until inner.b) {
            var hits = 0
            for (x in inner.l until inner.r) if (distance(image.getRGB(x, y), fill) > 60) hits++
            val inkRow = hits >= 3
            if (inkRow && !inBand) bands++
            inBand = inkRow
        }
        return bands
    }
    private fun mostCommon(image: BufferedImage, box: Box): Int {
        val counts = HashMap<Int, Int>()
        for (y in box.t until box.b step 2) for (x in box.l until box.r step 2) counts.merge(image.getRGB(x, y) and 0xffffff, 1, Int::plus)
        return counts.maxBy { it.value }.key
    }
    private fun distance(a: Int, b: Int): Int =
        maxOf(kotlin.math.abs((a shr 16 and 255) - (b shr 16 and 255)), kotlin.math.abs((a shr 8 and 255) - (b shr 8 and 255)), kotlin.math.abs((a and 255) - (b and 255)))
    /**
     * Synthetic input only: RINGMASTER_ROUTE_NATIVE_PLACE=behind puts the window on the primary screen at the bottom of
     * the z-order instead of off-screen. Off-screen, SOFTWARE_COMPAT surfaces were observed never painted (pure black)
     * or not repainted after a state change, while DIRECT3D rendered them; on-screen they are painted like any window.
     */
    private fun behind() = System.getenv("RINGMASTER_ROUTE_NATIVE_PLACE")?.lowercase() == "behind"

    private fun inputMode() = System.getenv("RINGMASTER_ROUTE_NATIVE_INPUT")?.lowercase()?.takeIf { it == "synthetic" } ?: "physical"

    private fun captureMode() = System.getenv("RINGMASTER_ROUTE_NATIVE_CAPTURE")?.lowercase()?.takeIf { it == "screen" } ?: "window"

    /**
     * The test window's own composed surface (DWM redirection content), at device resolution. Unlike a screen grab it
     * cannot contain another window drawn over the test window. Windows only.
     */
    private fun printWindow(hwnd: Long): BufferedImage {
        val window = com.sun.jna.Pointer(hwnd)
        val rect = IntArray(4)
        check(ProbeUser32.lib.GetWindowRect(window, rect)) { "GetWindowRect failed" }
        val w = rect[2] - rect[0]; val h = rect[3] - rect[1]
        val screenDc = ProbeUser32.lib.GetDC(null) ?: error("GetDC failed")
        val memory = ProbeGdi32.lib.CreateCompatibleDC(screenDc) ?: error("CreateCompatibleDC failed")
        val bitmap = ProbeGdi32.lib.CreateCompatibleBitmap(screenDc, w, h) ?: error("CreateCompatibleBitmap failed")
        try {
            val previous = ProbeGdi32.lib.SelectObject(memory, bitmap)
            check(ProbeUser32.lib.PrintWindow(window, memory, 2 /* PW_RENDERFULLCONTENT */)) { "PrintWindow failed" }
            if (previous != null) ProbeGdi32.lib.SelectObject(memory, previous)
            val info = ProbeBitmapInfo().apply { biWidth = w; biHeight = -h }
            val pixels = IntArray(w * h)
            check(ProbeGdi32.lib.GetDIBits(memory, bitmap, 0, h, pixels, info, 0) == h) { "GetDIBits failed" }
            return BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, w, h, pixels, 0, w) }
        } finally {
            ProbeGdi32.lib.DeleteObject(bitmap); ProbeGdi32.lib.DeleteDC(memory); ProbeUser32.lib.ReleaseDC(null, screenDc)
        }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

@Suppress("FunctionName")
private interface ProbeUser32 : com.sun.jna.win32.StdCallLibrary {
    fun GetDC(hwnd: com.sun.jna.Pointer?): com.sun.jna.Pointer?
    fun ReleaseDC(hwnd: com.sun.jna.Pointer?, hdc: com.sun.jna.Pointer): Int
    fun PrintWindow(hwnd: com.sun.jna.Pointer, hdc: com.sun.jna.Pointer, flags: Int): Boolean
    fun GetWindowRect(hwnd: com.sun.jna.Pointer, rect: IntArray): Boolean
    companion object { val lib: ProbeUser32 by lazy { com.sun.jna.Native.load("user32", ProbeUser32::class.java) } }
}

@Suppress("FunctionName")
private interface ProbeGdi32 : com.sun.jna.win32.StdCallLibrary {
    fun CreateCompatibleDC(hdc: com.sun.jna.Pointer?): com.sun.jna.Pointer?
    fun CreateCompatibleBitmap(hdc: com.sun.jna.Pointer, width: Int, height: Int): com.sun.jna.Pointer?
    fun SelectObject(hdc: com.sun.jna.Pointer, obj: com.sun.jna.Pointer): com.sun.jna.Pointer?
    fun DeleteObject(obj: com.sun.jna.Pointer): Boolean
    fun DeleteDC(hdc: com.sun.jna.Pointer): Boolean
    fun GetDIBits(hdc: com.sun.jna.Pointer, bitmap: com.sun.jna.Pointer, start: Int, lines: Int, bits: IntArray, info: ProbeBitmapInfo, usage: Int): Int
    companion object { val lib: ProbeGdi32 by lazy { com.sun.jna.Native.load("gdi32", ProbeGdi32::class.java) } }
}

/** BITMAPINFO for a 32-bit top-down BI_RGB read: the header plus room for the (unused) colour table. */
@com.sun.jna.Structure.FieldOrder("biSize", "biWidth", "biHeight", "biPlanes", "biBitCount", "biCompression", "biSizeImage",
    "biXPelsPerMeter", "biYPelsPerMeter", "biClrUsed", "biClrImportant", "bmiColors")
internal class ProbeBitmapInfo : com.sun.jna.Structure() {
    @JvmField var biSize: Int = 40
    @JvmField var biWidth: Int = 0
    @JvmField var biHeight: Int = 0
    @JvmField var biPlanes: Short = 1
    @JvmField var biBitCount: Short = 32
    @JvmField var biCompression: Int = 0
    @JvmField var biSizeImage: Int = 0
    @JvmField var biXPelsPerMeter: Int = 0
    @JvmField var biYPelsPerMeter: Int = 0
    @JvmField var biClrUsed: Int = 0
    @JvmField var biClrImportant: Int = 0
    @JvmField var bmiColors: IntArray = IntArray(4)
}
