@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package local.ringleader.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowDecoration
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import java.awt.GraphicsEnvironment
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * A real undecorated window, only when RINGMASTER_NATIVE_FRAME_PROBE=1. Ordinary runs never open one.
 * The window stays small, in a corner, and is closed in finally.
 */
class WindowsNativeFrameProbeTest {
    @Test fun probeNativeFrameOnARealWindow() {
        assumeTrue(!GraphicsEnvironment.isHeadless()) { "headless" }
        assumeTrue(System.getenv("RINGMASTER_NATIVE_FRAME_PROBE") == "1") { "probe off" }
        val previousNativeFrame = System.getProperty("ringmaster.nativeFrame")
        System.setProperty("ringmaster.nativeFrame", "true")
        try {
        val failure = AtomicReference<Throwable>()
        val running = AtomicBoolean(true)
        val windowRef = AtomicReference<java.awt.Window>()
        val thread = Thread({
            try {
                application(exitProcessOnExit = false) {
                    val screen = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
                    val state = remember {
                        WindowState(position = WindowPosition((screen.x + screen.width - 700).dp, (screen.y + 8).dp), size = DpSize(640.dp, 420.dp))
                    }
                    Window(onCloseRequest = {}, state = state, visible = true, title = " ", focusable = false, alwaysOnTop = false,
                        decoration = WindowDecoration.Undecorated(0.dp), resizable = true) {
                        windowRef.set(window)
                        var frame by remember { mutableStateOf<WindowsNativeFrame?>(null) }
                        var title by remember { mutableStateOf<IntRect?>(null) }
                        var maximize by remember { mutableStateOf<IntRect?>(null) }
                        var minimize by remember { mutableStateOf<IntRect?>(null) }
                        var close by remember { mutableStateOf<IntRect?>(null) }
                        var width by remember { mutableStateOf(0) }
                        var height by remember { mutableStateOf(0) }
                        val publishedFrame = frame
                        val publishedTitle = title
                        val publishedMaximize = maximize
                        val publishedMinimize = minimize
                        val publishedClose = close
                        val publishedWidth = width
                        val publishedHeight = height
                        val publishedPlacement = state.placement
                        val publishedDark = !Appearance.palette.light
                        val publishedEdge = Appearance.palette.edge
                        androidx.compose.runtime.SideEffect {
                            val installed = publishedFrame ?: return@SideEffect
                            installed.publish(publishedPlacement, FrameRegions(
                                publishedWidth, publishedHeight, publishedTitle, publishedMaximize, listOfNotNull(publishedMinimize, publishedClose),
                                resizeBandPx = 6, resizeCornerPx = 14, titleBarHeightPx = publishedTitle?.height ?: 0,
                            ), dark = publishedDark, border = colorRef(publishedEdge))
                        }
                        MaterialTheme(colorScheme = ringColors) {
                            Column(Modifier.fillMaxSize().onGloballyPositioned { width = it.size.width; height = it.size.height }) {
                                Row(Modifier.fillMaxWidth().height(32.dp).background(Tech.Raised).padding(horizontal = 12.dp).onGloballyPositioned { title = it.frameRect() }, verticalAlignment = Alignment.CenterVertically) {
                                    Text("RingMASTER", Modifier.weight(1f), color = Tech.Amber)
                                    Box(Modifier.onGloballyPositioned { minimize = it.frameRect() }) { WindowControl(WindowGlyph.Minimize) {} }
                                    Box(Modifier.onGloballyPositioned { maximize = it.frameRect() }) { WindowControl(WindowGlyph.Maximize, onClick = null) }
                                    Box(Modifier.onGloballyPositioned { close = it.frameRect() }) { WindowControl(WindowGlyph.Close) {} }
                                }
                                Spacer(Modifier.weight(1f).fillMaxWidth())
                            }
                        }
                        LaunchedEffect(Unit) {
                            try {
                                window.isAutoRequestFocus = false
                                window.focusableWindowState = false
                                val bounds = window.graphicsConfiguration.bounds
                                window.setLocation(bounds.x + (bounds.width - 660).coerceAtLeast(0), bounds.y + 8)
                                val outcome = WindowsNativeFrame.attempt(window, state.placement)
                                val installed = outcome.frame ?: error("install failed")
                                frame = installed
                                try {
                                    await("layout") { title != null && maximize != null && width > 0 && height > 0 }
                                    await("published") { installed.layoutReady() }
                                    withFrameNanos { }
                                    delay(80)
                                    note("focused=${window.isFocused} ${installed.borderMetrics()}")
                                    val scale = window.graphicsConfiguration.defaultTransform.scaleX
                                    val floating = installed.windowBox()
                                    note("scale=$scale awt=${window.width}x${window.height} compose=${width}x$height physical=${floating.width}x${floating.height} insets=${window.insets.left},${window.insets.top},${window.insets.right},${window.insets.bottom}")
                                    note("floating-rect ${floating.x},${floating.y} ${floating.width}x${floating.height}")
                                    assertEquals(FRAME_MASK, installed.currentStyle() and FRAME_MASK)
                                    assertZeroInsets(window)
                                    // Compose layout pixels and GetWindowRect are physical. AWT window size is logical (pixels / scale).
                                    assertEquals(floating.width, width)
                                    assertEquals(floating.height, height)
                                    assertEquals(floating.width, (window.width * scale).roundToInt())
                                    assertEquals(floating.height, (window.height * scale).roundToInt())
                                    fun at(rect: IntRect) = (floating.x + (rect.left + rect.right) / 2) to (floating.y + (rect.top + rect.bottom) / 2)
                                    val caption = installed.sendHitTest(floating.x + 80, floating.y + 16)
                                    val max = installed.sendHitTest(at(maximize!!).first, at(maximize!!).second.coerceAtLeast(floating.y + 10))
                                    val client = installed.sendHitTest(floating.x + floating.width / 2, floating.y + floating.height / 2)
                                    val left = installed.sendHitTest(floating.x + 2, floating.y + floating.height / 2)
                                    val right = installed.sendHitTest(floating.x + floating.width - 2, floating.y + floating.height / 2)
                                    val bottom = installed.sendHitTest(floating.x + floating.width / 2, floating.y + floating.height - 2)
                                    val top = installed.sendHitTest(floating.x + floating.width / 2, floating.y + 2)
                                    note(installed.debugLine())
                                    note("hit caption=$caption max=$max client=$client left=$left right=$right top=$top bottom=$bottom")
                                    assertEquals(HTCAPTION, caption)
                                    assertEquals(HTMAXBUTTON, max)
                                    assertEquals(HTCLIENT, client)
                                    assertEquals(HTLEFT, left)
                                    assertEquals(HTRIGHT, right)
                                    assertEquals(HTBOTTOM, bottom)
                                    assertEquals(HTTOP, top)
                                    // The answers above come from asking the frame directly. Windows does not: it asks the
                                    // window under the pointer, and that is the child Compose draws into. In 0.23.6 that
                                    // child said "client" everywhere, so the frame was never asked and the window could
                                    // not be moved, resized or maximised. The child must step aside (-1, transparent)
                                    // wherever the frame has an answer, and keep ordinary content for itself.
                                    val kids = installed.childWindows()
                                    assertTrue(kids.isNotEmpty(), "Compose draws into a child window; without one this probe proves nothing")
                                    val maxAt = at(maximize!!).first to at(maximize!!).second.coerceAtLeast(floating.y + 10)
                                    val kidCaption = kids.map { installed.sendHitTestTo(it, floating.x + 80, floating.y + 16) }
                                    val kidLeft = kids.map { installed.sendHitTestTo(it, floating.x + 2, floating.y + floating.height / 2) }
                                    val kidBottom = kids.map { installed.sendHitTestTo(it, floating.x + floating.width / 2, floating.y + floating.height - 2) }
                                    val kidMax = kids.map { installed.sendHitTestTo(it, maxAt.first, maxAt.second) }
                                    val kidClient = kids.map { installed.sendHitTestTo(it, floating.x + floating.width / 2, floating.y + floating.height / 2) }
                                    note("children=${kids.size} caption=$kidCaption left=$kidLeft bottom=$kidBottom max=$kidMax client=$kidClient")
                                    assertTrue((kidCaption + kidLeft + kidBottom + kidMax).all { it == -1 }, "a child window still claims a zone that belongs to the frame")
                                    assertTrue(kidClient.all { it == HTCLIENT }, "a child window gave up ordinary content")
                                    val before = installed.windowBox()
                                    applyMaximizedBounds(window)
                                    val frameBounds = (window as? java.awt.Frame)?.maximizedBounds
                                    note("maximizedBounds=${frameBounds?.x},${frameBounds?.y} ${frameBounds?.width}x${frameBounds?.height}")
                                    installed.postCommand(SC_MAXIMIZE)
                                    await("maximised") { state.placement == WindowPlacement.Maximized }
                                    withFrameNanos { }
                                    delay(120)
                                    val monitor = installed.monitorWork() ?: error("no monitor")
                                    val edge = WindowsTaskbar.autoHideEdge
                                    val expected = maximizedWorkBox(
                                        monitor.monitorLeft, monitor.monitorTop, monitor.monitorRight, monitor.monitorBottom,
                                        monitor.workLeft, monitor.workTop, monitor.workRight, monitor.workBottom,
                                        edge, autoHideStripPx(monitor.dpi),
                                    )
                                    val sizeDeadline = System.currentTimeMillis() + 4_000
                                    var sizeNote = ""
                                    while (System.currentTimeMillis() < sizeDeadline) {
                                        if (width == expected.width && height == expected.height) break
                                        val box = installed.windowBox()
                                        sizeNote = "compose=${width}x$height window=${box.x},${box.y} ${box.width}x${box.height} ${installed.debugLine()}"
                                        delay(40)
                                    }
                                    if (width != expected.width || height != expected.height) error("timed out waiting for maximised client $sizeNote")
                                    val zoomed = installed.windowBox()
                                    val growX = zoomed.width - expected.width
                                    val growY = zoomed.height - expected.height
                                    note("maximised placement=${state.placement} rect=${zoomed.x},${zoomed.y} ${zoomed.width}x${zoomed.height} expected=${expected.x},${expected.y} ${expected.width}x${expected.height} autoHide=$edge dpi=${monitor.dpi}")
                                    note(installed.debugLine())
                                    note("maximised insets=${window.insets.left},${window.insets.top},${window.insets.right},${window.insets.bottom} compose=${width}x$height")
                                    assertEquals(WindowPlacement.Maximized, state.placement)
                                    assertEquals(expected.width, width)
                                    assertEquals(expected.height, height)
                                    assertEquals(0, growX % 2)
                                    assertEquals(0, growY % 2)
                                    assertEquals(expected.x, zoomed.x + growX / 2)
                                    assertEquals(expected.y, zoomed.y + growY / 2)
                                    assertEquals(expected.width, zoomed.width - growX)
                                    assertEquals(expected.height, zoomed.height - growY)
                                    val maxLeft = installed.sendHitTest(zoomed.x + 2, zoomed.y + zoomed.height / 2)
                                    val maxTop = installed.sendHitTest(zoomed.x + zoomed.width / 2, zoomed.y + 2)
                                    note("maximised-edge left=$maxLeft top=$maxTop")
                                    assertTrue(maxLeft == HTCLIENT || maxLeft == HTCAPTION)
                                    assertTrue(maxTop == HTCLIENT || maxTop == HTCAPTION)
                                    installed.postCommand(SC_RESTORE)
                                    await("restored") { state.placement == WindowPlacement.Floating }
                                    delay(120)
                                    val restored = installed.windowBox()
                                    note("restored placement=${state.placement} rect=${restored.x},${restored.y} ${restored.width}x${restored.height} before=${before.width}x${before.height}")
                                    assertEquals(WindowPlacement.Floating, state.placement)
                                    assertEquals(before.width, restored.width)
                                    assertEquals(before.height, restored.height)
                                    state.placement = WindowPlacement.Fullscreen
                                    await("fullscreen") { state.placement == WindowPlacement.Fullscreen }
                                    withFrameNanos { }
                                    delay(150)
                                    val full = installed.windowBox()
                                    val fullHit = listOf(
                                        installed.sendHitTest(full.x + full.width / 2, full.y + 16),
                                        installed.sendHitTest(full.x + 2, full.y + full.height / 2),
                                        installed.sendHitTest(full.x + full.width / 2, full.y + full.height / 2),
                                    )
                                    note("fullscreen placement=${state.placement} hits=$fullHit styleBits=${installed.currentStyle() and FRAME_MASK}")
                                    assertTrue(fullHit.all { it == HTCLIENT })
                                    state.placement = WindowPlacement.Floating
                                    await("left fullscreen") { state.placement == WindowPlacement.Floating }
                                    withFrameNanos { }
                                    delay(150)
                                    note("after-fullscreen styleBits=${installed.currentStyle() and FRAME_MASK}")
                                    assertEquals(FRAME_MASK, installed.currentStyle() and FRAME_MASK)
                                    val styleBefore = installed.originalStyle()
                                    val procBefore = installed.originalProc()
                                    installed.uninstall()
                                    frame = null
                                    note("uninstalled styleMatches=${installed.currentStyle() == styleBefore} procMatches=${installed.currentProc() == procBefore}")
                                    assertEquals(styleBefore, installed.currentStyle())
                                    assertEquals(procBefore, installed.currentProc())
                                } finally {
                                    installed.uninstall()
                                }
                            } catch (thrown: Throwable) {
                                failure.set(thrown)
                            } finally {
                                exitApplication()
                            }
                        }
                    }
                }
            } catch (thrown: Throwable) {
                failure.compareAndSet(null, thrown)
            } finally {
                running.set(false)
            }
        }, "native-frame-probe")
        thread.isDaemon = true
        thread.start()
        val deadline = System.currentTimeMillis() + 25_000
        while (running.get() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        if (running.get()) failure.compareAndSet(null, IllegalStateException("probe timed out"))
        val open = windowRef.get()
        if (open != null && open.isDisplayable) {
            runCatching { SwingUtilities.invokeAndWait { open.dispose() } }
        }
        thread.join(2_000)
        failure.get()?.let { throw it }
        } finally {
            if (previousNativeFrame == null) System.clearProperty("ringmaster.nativeFrame")
            else System.setProperty("ringmaster.nativeFrame", previousNativeFrame)
        }
    }

    private suspend fun await(what: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 4_000
        while (!cond() && System.currentTimeMillis() < deadline) delay(40)
        if (!cond()) error("timed out waiting for $what")
    }

    private fun assertZeroInsets(window: java.awt.Window) {
        val insets = window.insets
        assertEquals(0, insets.left)
        assertEquals(0, insets.top)
        assertEquals(0, insets.right)
        assertEquals(0, insets.bottom)
    }

    private fun note(line: String) = println("native-frame: $line")
}

private const val FRAME_MASK = 0x00C00000 or 0x00040000 or 0x00010000 or 0x00020000 or 0x00080000
private const val SC_MAXIMIZE = 0xF030
private const val SC_RESTORE = 0xF120
