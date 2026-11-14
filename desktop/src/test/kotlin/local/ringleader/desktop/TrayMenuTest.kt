package local.ringleader.desktop

import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrayMenuTest {
    private fun items(menu: JPopupMenu): List<JMenuItem> = menu.components.filterIsInstance<JMenuItem>().flatMap {
        listOf(it) + if (it is JMenu) items(it.popupMenu) else emptyList()
    }

    @Test fun shortcutsDispatchTheirOwnDestinationAndDisabledFeaturesDoNothing() {
        SwingUtilities.invokeAndWait {
            val seen = mutableListOf<TrayDestination>()
            var opens = 0
            var quits = 0
            val available = TrayDestination.entries.toSet() - TrayDestination.Watcher
            val menu = createTrayMenu("1.2.3", "No active schedules", available, { opens++ }, seen::add, { quits++ })
            for (destination in TrayDestination.entries) {
                val item = items(menu).single { it.text == destination.label }
                assertEquals(destination in available, item.isEnabled)
                item.doClick(0)
            }
            assertEquals(available, seen.toSet())
            assertEquals(available.size, seen.size, "each click routes once")
            items(menu).single { it.text == "Open RingMASTER" }.doClick(0)
            items(menu).single { it.text == "Quit RingMASTER" }.doClick(0)
            assertEquals(1, opens)
            assertEquals(1, quits)
        }
    }

    @Test fun readableRowsAndSubmenusInBothThemesAndAtLargeText() {
        SwingUtilities.invokeAndWait {
            val before = Appearance.theme
            val text = Appearance.textScale
            val showcase = Appearance.showcase
            try {
                for (light in listOf(false, true)) for (scale in listOf(1f, 1.5f)) {
                    Appearance.preview(light, textScale = scale)
                    val menu = createTrayMenu("0.25.2", "3 schedules on", TrayDestination.entries.toSet(), {}, {}, {})
                    fun check(popup: JPopupMenu) {
                        popup.setSize(popup.preferredSize)
                        popup.doLayout()
                        for (item in popup.components.filterIsInstance<JMenuItem>()) {
                            assertTrue(item.height >= item.getFontMetrics(item.font).height + 12, "room above and below ${item.text}")
                            assertTrue(item.width >= item.getFontMetrics(item.font).stringWidth(item.text) + 28, "intact ${item.text}")
                            if (item is JMenu) check(item.popupMenu)
                        }
                    }
                    check(menu)
                    val folder = System.getenv("RINGMASTER_TRAY_MENU_SHOTS")
                    if (folder != null) {
                        val image = BufferedImage(menu.width, menu.height, BufferedImage.TYPE_INT_ARGB)
                        val g = image.createGraphics()
                        try { menu.printAll(g) } finally { g.dispose() }
                        val dir = java.nio.file.Path.of(folder)
                        Files.createDirectories(dir)
                        ImageIO.write(image, "png", dir.resolve("tray-${if (light) "light" else "dark"}-$scale.png").toFile())
                    }
                }
            } finally { Appearance.preview(before, showcase, text) }
        }
    }

    @Test fun popupStaysInsideTheTrayMonitorsWorkAreaIncludingNegativeCoordinates() {
        val size = Dimension(280, 450)
        for (work in listOf(Rectangle(0, 0, 1920, 1040), Rectangle(-1920, -200, 1920, 1040))) {
            for (point in listOf(Point(work.x, work.y), Point(work.x + work.width - 1, work.y),
                Point(work.x, work.y + work.height - 1), Point(work.x + work.width - 1, work.y + work.height - 1))) {
                assertTrue(work.contains(Rectangle(trayPopupPosition(point, size, work), size)))
            }
        }
    }

    @Test fun shortDisplaysKeepEveryActionReachableThroughOverflow() {
        SwingUtilities.invokeAndWait {
            val menu = createTrayMenu("1.2.3", "No active schedules", TrayDestination.entries.toSet(), {}, {}, {}, textScale = 1.5f)
            val before = items(menu).filter { it !is JMenu }.map { it.text }.sorted()
            fitTrayMenu(menu, 280)
            fun check(popup: JPopupMenu) {
                assertTrue(popup.preferredSize.height <= 280, "menu fits the short work area")
                popup.components.filterIsInstance<JMenu>().forEach { check(it.popupMenu) }
            }
            check(menu)
            assertEquals(before, items(menu).filter { it !is JMenu }.map { it.text }.sorted())
        }
    }

    @Test fun shortcutRestoresHiddenResidentAndQueuesTypedRequestWithoutQuitting() {
        val preferences = MotionPreferences(Files.createTempDirectory("tray-navigation").resolve("motion.txt"))
        var navigate: ((TrayDestination) -> Unit)? = null
        var removed = false
        val fake = object : TrayPort {
            override fun install() = TrayStatus.Installed
            override fun update(tooltip: String, line: String) = Unit
            override fun notify(title: String, text: String) = Unit
            override fun remove() { removed = true }
        }
        val runtime = ResidentRuntime(null, null, null, null, preferences,
            { _, onNavigate, _ -> navigate = onNavigate; fake }, {})
        SwingUtilities.invokeAndWait { runtime.installTray(); runtime.hide() }
        assertFalse(runtime.windowVisible)
        for (destination in TrayDestination.entries) {
            SwingUtilities.invokeAndWait { navigate!!(destination) }
            SwingUtilities.invokeAndWait {}
            assertEquals(destination, runtime.trayRequest)
            assertTrue(runtime.windowVisible)
        }
        assertEquals(TrayDestination.entries.size, runtime.raiseToken)
        assertFalse(removed)
    }
}
