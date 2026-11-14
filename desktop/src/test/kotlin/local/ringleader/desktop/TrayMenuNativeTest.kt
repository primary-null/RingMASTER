package local.ringleader.desktop

import java.awt.Point
import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Opt-in: native popup, real mouse and keyboard; actions are recorded, never sent to a server. */
class TrayMenuNativeTest {
    @Test fun mouseKeyboardSubmenuAndOutsideDismiss() {
        val folder = System.getenv("RINGMASTER_TRAY_MENU_NATIVE")
        org.junit.jupiter.api.Assumptions.assumeTrue(folder != null && !java.awt.GraphicsEnvironment.isHeadless())
        val dir = Path.of(folder!!)
        Files.createDirectories(dir)
        val robot = Robot().apply { autoDelay = 70 }
        val host = TrayMenuWindow()
        val seen = java.util.concurrent.CopyOnWriteArrayList<TrayDestination>()
        var opened = 0
        lateinit var menu: JPopupMenu
        val gc = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
        val anchor = Point(gc.bounds.x + gc.bounds.width - 40, gc.bounds.y + gc.bounds.height - 80)
        fun show() {
            robot.mouseMove(anchor.x, anchor.y)
            SwingUtilities.invokeAndWait {
                menu = createTrayMenu(AppVersion.value, "No active schedules", TrayDestination.entries.toSet(), { opened++ }, seen::add, {})
                host.show(anchor, menu)
            }
            robot.waitForIdle()
            Thread.sleep(300)
            SwingUtilities.invokeAndWait { assertTrue(menu.isShowing) }
        }
        fun click(item: JMenuItem) {
            var point = Point()
            SwingUtilities.invokeAndWait {
                point = item.locationOnScreen.apply { translate(item.width / 2, item.height / 2) }
            }
            robot.mouseMove(point.x, point.y)
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            robot.waitForIdle()
            Thread.sleep(200)
        }
        try {
            show()
            var bounds = java.awt.Rectangle()
            SwingUtilities.invokeAndWait { bounds = java.awt.Rectangle(menu.locationOnScreen, menu.size) }
            ImageIO.write(robot.createScreenCapture(bounds), "png", dir.resolve("native-menu.png").toFile())
            val fleet = menu.components.filterIsInstance<JMenuItem>().single { it.text == "Fleet" }
            click(fleet)
            assertEquals(listOf(TrayDestination.Fleet), seen.toList())
            SwingUtilities.invokeAndWait { assertFalse(menu.isShowing) }

            show()
            val more = menu.components.filterIsInstance<JMenu>().single()
            click(more)
            Thread.sleep(400)
            SwingUtilities.invokeAndWait { assertTrue(more.popupMenu.isShowing, "submenu survives focus changes") }
            click(more.menuComponents.filterIsInstance<JMenuItem>().single { it.text == "Storage" })
            assertEquals(TrayDestination.Storage, seen.last())

            show()
            robot.keyPress(KeyEvent.VK_DOWN); robot.keyRelease(KeyEvent.VK_DOWN)
            robot.waitForIdle()
            robot.keyPress(KeyEvent.VK_ENTER); robot.keyRelease(KeyEvent.VK_ENTER)
            robot.waitForIdle()
            Thread.sleep(200)
            assertEquals(1, opened, "keyboard activates the first action, skipping header labels; routes=$seen")

            show()
            robot.keyPress(KeyEvent.VK_ESCAPE); robot.keyRelease(KeyEvent.VK_ESCAPE)
            robot.waitForIdle()
            Thread.sleep(200)
            SwingUtilities.invokeAndWait { assertFalse(menu.isShowing, "Escape dismisses") }

            show()
            // A separate owned test window receives the outside click, never an arbitrary desktop application.
            lateinit var outside: javax.swing.JWindow
            SwingUtilities.invokeAndWait {
                outside = javax.swing.JWindow().apply { setBounds(gc.bounds.x + 40, gc.bounds.y + 40, 160, 100); isAlwaysOnTop = true; isVisible = true }
            }
            try {
                robot.mouseMove(gc.bounds.x + 80, gc.bounds.y + 80)
                robot.mousePress(InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                robot.waitForIdle()
                Thread.sleep(200)
                SwingUtilities.invokeAndWait { assertFalse(menu.isShowing, "outside click dismisses") }
            } finally { SwingUtilities.invokeAndWait { outside.dispose() } }
        } finally { SwingUtilities.invokeAndWait { host.close() } }
    }
}
