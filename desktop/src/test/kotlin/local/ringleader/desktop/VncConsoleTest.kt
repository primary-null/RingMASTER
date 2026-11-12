package local.ringleader.desktop

import local.ringleader.core.*
import java.awt.event.KeyEvent
import java.time.Instant
import kotlin.test.*

class VncConsoleTest {
    @Test fun wholeScalesAreSharpAndFitSnapsDownToThem() {
        val oneToOne = vncFrameLayout(500f, 300f, 1024, 768, fit = false)!!
        assertEquals(listOf(0, 0, 1024, 768), listOf(oneToOne.left, oneToOne.top, oneToOne.width, oneToOne.height))
        assertTrue(oneToOne.sharp, "100 % is always drawn without smoothing")

        // 1100×800 for 1024×768 is a 1.04 fit: drawn at exactly 1:1, centred, sharp.
        val near = vncFrameLayout(1100f, 800f, 1024, 768, fit = true)!!
        assertEquals(1f, near.scale); assertTrue(near.sharp)
        assertEquals(listOf(38, 16, 1024, 768), listOf(near.left, near.top, near.width, near.height))

        val double = vncFrameLayout(2048f, 1600f, 1024, 768, fit = true)!!
        assertEquals(2f, double.scale); assertTrue(double.sharp)
        assertEquals(2048, double.width); assertEquals(1536, double.height)

        val stretched = vncFrameLayout(1600f, 1000f, 1024, 768, fit = true)!!
        assertFalse(stretched.sharp, "a fractional scale keeps smoothing")
        assertEquals(1000f / 768f, stretched.scale)

        val shrunk = vncFrameLayout(800f, 600f, 1024, 768, fit = true)!!
        assertFalse(shrunk.sharp); assertEquals(800, shrunk.width)

        assertNull(vncFrameLayout(0f, 0f, 1024, 768, fit = true))
        assertNull(vncFrameLayout(100f, 100f, 0, 768, fit = true))
    }

    @Test fun pointerMappingFollowsTheDrawnLayout() {
        // Snapped to 1:1 inside 1100×800: the frame starts at (38, 16).
        assertNull(vncPointerPoint(37f, 20f, 1100f, 800f, 1024, 768, fit = true))
        assertEquals(0 to 0, vncPointerPoint(38f, 16f, 1100f, 800f, 1024, 768, fit = true))
        assertEquals(1023 to 767, vncPointerPoint(1061.9f, 783.9f, 1100f, 800f, 1024, 768, fit = true))
        assertNull(vncPointerPoint(1062f, 400f, 1100f, 800f, 1024, 768, fit = true))
        assertEquals(10 to 5, vncPointerPoint(20.5f, 10.5f, 2048f, 1536f, 1024, 768, fit = true))
        assertEquals(5 to 7, vncPointerPoint(5.5f, 7.9f, 1024f, 768f, 1024, 768, fit = false))
        assertNull(vncPointerPoint(1024f, 7f, 1024f, 768f, 1024, 768, fit = false))
    }

    @Test fun windowsScanCodesGetTheirE0Flag() {
        val standard = KeyEvent.KEY_LOCATION_STANDARD
        assertEquals(0x1e, windowsXtKeycode(0x1e, KeyEvent.VK_A, standard))
        assertEquals(0x15, windowsXtKeycode(0x15, KeyEvent.VK_Z, standard), "the scan code names the physical key, whatever the layout calls it")
        assertEquals(0xcb, windowsXtKeycode(0x4b, KeyEvent.VK_LEFT, standard))
        assertEquals(0x4b, windowsXtKeycode(0x4b, KeyEvent.VK_LEFT, KeyEvent.KEY_LOCATION_NUMPAD), "keypad 4 without Num Lock")
        assertEquals(0xd3, windowsXtKeycode(0x53, KeyEvent.VK_DELETE, standard))
        assertEquals(0x1d, windowsXtKeycode(0x1d, KeyEvent.VK_CONTROL, KeyEvent.KEY_LOCATION_LEFT))
        assertEquals(0x9d, windowsXtKeycode(0x1d, KeyEvent.VK_CONTROL, KeyEvent.KEY_LOCATION_RIGHT))
        assertEquals(0xb8, windowsXtKeycode(0x38, KeyEvent.VK_ALT, KeyEvent.KEY_LOCATION_RIGHT))
        assertEquals(0xb8, windowsXtKeycode(0x38, KeyEvent.VK_ALT_GRAPH, standard))
        assertEquals(0x9c, windowsXtKeycode(0x1c, KeyEvent.VK_ENTER, KeyEvent.KEY_LOCATION_NUMPAD))
        assertEquals(0x1c, windowsXtKeycode(0x1c, KeyEvent.VK_ENTER, standard))
        assertEquals(0xdb, windowsXtKeycode(0x5b, KeyEvent.VK_WINDOWS, KeyEvent.KEY_LOCATION_LEFT))
        assertEquals(0xb7, windowsXtKeycode(0x54, KeyEvent.VK_PRINTSCREEN, standard))
        assertEquals(0xc6, windowsXtKeycode(0x45, KeyEvent.VK_PAUSE, standard))
        assertEquals(0, windowsXtKeycode(0, KeyEvent.VK_A, standard), "unknown scan code: keysym only")
    }

    @Test fun x11KeycodesMapThroughEvdev() {
        assertEquals(0x01, x11XtKeycode(9))        // Escape
        assertEquals(0x1e, x11XtKeycode(38))       // A position
        assertEquals(0x56, x11XtKeycode(94))       // the ISO key left of Z
        assertEquals(0x58, x11XtKeycode(96))       // F12
        assertEquals(0x9d, x11XtKeycode(105))      // right Ctrl
        assertEquals(0xb8, x11XtKeycode(108))      // right Alt / AltGr
        assertEquals(0xcb, x11XtKeycode(113))      // Left
        assertEquals(0xd3, x11XtKeycode(119))      // Delete
        assertEquals(0x9c, x11XtKeycode(104))      // keypad Enter
        assertEquals(0xdb, x11XtKeycode(133))      // left Super
        assertEquals(0, x11XtKeycode(93))          // evdev 85 has no plain XT code
        assertEquals(0, x11XtKeycode(0))
        assertEquals(0, x11XtKeycode(400))
    }

    @Test fun awtParamStringGivesRawCodeAndScanCode() {
        assertEquals(65 to 30, awtKeyCodes("KEY_PRESSED,keyCode=65,keyText=A,keyChar='a',keyLocation=KEY_LOCATION_STANDARD,rawCode=65,primaryLevelUnicode=97,scancode=30,extendedKeyCode=0x41"))
        assertEquals(0 to 0, awtKeyCodes("KEY_PRESSED,keyCode=44,keyText=Comma,keyChar=',',keyLocation=KEY_LOCATION_STANDARD,rawCode=0,primaryLevelUnicode=0,scancode=0,extendedKeyCode=0x0"))
        assertEquals(0 to 0, awtKeyCodes("KEY_PRESSED,keyCode=65"))
        // A real AWT event's own text, so a format change in a later JDK fails here first.
        val event = KeyEvent(object : java.awt.Component() {}, KeyEvent.KEY_PRESSED, 0L, 0, KeyEvent.VK_A, 'a', KeyEvent.KEY_LOCATION_STANDARD)
        assertTrue(Regex(",rawCode=\\d+,").containsMatchIn(event.paramString()) && Regex(",scancode=\\d+,").containsMatchIn(event.paramString()), event.paramString())
    }

    @Test fun stillsAreNotAutoCapturedWhileAConsoleIsOpen() {
        val now = Instant.parse("2026-11-11T12:00:00Z")
        assertTrue(shouldAutoCaptureStill(null, now, liveConsole = false))
        assertTrue(shouldAutoCaptureStill(now.minusSeconds(11 * 60), now, liveConsole = false))
        assertFalse(shouldAutoCaptureStill(now.minusSeconds(60), now, liveConsole = false))
        assertFalse(shouldAutoCaptureStill(null, now, liveConsole = true))
        assertFalse(shouldAutoCaptureStill(now.minusSeconds(3600), now, liveConsole = true))
    }

    @Test fun liveConsolesAreCounted() {
        val id = ResourceId("https://192.0.2.10:8006", "", "qemu", 9113)
        assertFalse(LiveConsoles.isOpen(id))
        LiveConsoles.open(id); LiveConsoles.open(id)
        LiveConsoles.close(id)
        assertTrue(LiveConsoles.isOpen(id), "one view replacing another keeps the guest marked")
        LiveConsoles.close(id)
        assertFalse(LiveConsoles.isOpen(id))
        LiveConsoles.close(id)
        assertFalse(LiveConsoles.isOpen(id))
    }
}
