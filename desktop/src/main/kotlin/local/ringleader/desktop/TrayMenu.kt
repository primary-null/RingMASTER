package local.ringleader.desktop

import androidx.compose.ui.graphics.toArgb
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.RenderingHints
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.JDialog
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.MenuSelectionManager
import javax.swing.SwingUtilities
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import javax.swing.plaf.basic.BasicLabelUI
import javax.swing.plaf.basic.BasicMenuItemUI
import javax.swing.plaf.basic.BasicMenuUI
import javax.swing.plaf.basic.BasicPanelUI
import javax.swing.plaf.basic.BasicPopupMenuUI

/** Navigation only. Opening a tray shortcut never starts a scan, signs in, or changes a guest. */
internal enum class TrayDestination(val label: String, val page: String? = null, val settings: SettingsSection? = null) {
    Connections("Connections", "Desk"), Fleet("Fleet", "Fleet"), Activity("Activity", "Activity"),
    Notifications("Notifications"), Schedules("Schedules", "Schedules"), Storage("Storage", "Storage"),
    Watcher("Watcher", WATCHER_PAGE), Scan("Network scan", "Scan"), Tools("Network tools", "Tools"),
    Bluetooth("Bluetooth", "Bluetooth"), Vpn("VPN", "VPN"),
    Settings("Settings…", settings = SettingsSection.Appearance),
    Updates("Check for updates…", settings = SettingsSection.Updates),
    About("About RingMASTER", settings = SettingsSection.About),
}

internal data class TrayMenuColors(val background: Color, val text: Color, val muted: Color, val border: Color, val selected: Color, val onSelected: Color) {
    companion object {
        fun current(): TrayMenuColors {
            val p = Appearance.targetPalette
            return TrayMenuColors(Color(p.raised.toArgb(), true), Color(p.ink.toArgb(), true),
                Color(p.muted.toArgb(), true), Color(p.edge.toArgb(), true),
                Color(p.amber.toArgb(), true), Color(p.onAccent.toArgb(), true))
        }
    }
}

/** Logical screen coordinates: AWT applies the monitor's DPI transform exactly once. */
internal fun trayPopupPosition(anchor: Point, size: Dimension, work: Rectangle): Point = Point(
    (anchor.x - size.width).coerceIn(work.x, (work.x + work.width - size.width).coerceAtLeast(work.x)),
    (anchor.y - size.height).coerceIn(work.y, (work.y + work.height - size.height).coerceAtLeast(work.y)),
)

/** Not a look-and-feel key: rows take no icon column, offsets or colours from the system theme. */
private const val TRAY_PREFIX = "RingMasterTray"

/** The submenu arrow, drawn in the row's own text colour instead of the system theme's glyph. */
private class TrayArrow(private val colors: TrayMenuColors, private val size: Int) : Icon {
    override fun getIconWidth() = size
    override fun getIconHeight() = size
    override fun paintIcon(c: Component, g: Graphics, x: Int, y: Int) {
        val item = c as? JMenuItem
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = when {
                item?.isEnabled == false -> colors.muted
                item?.model?.let { it.isArmed || it.isSelected } == true -> colors.onSelected
                else -> colors.text
            }
            g2.stroke = BasicStroke(size / 6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            val left = x + size * 3 / 10; val right = x + size * 7 / 10
            g2.drawPolyline(intArrayOf(left, right, left), intArrayOf(y + size / 6, y + size / 2, y + size * 5 / 6), 3)
        } finally { g2.dispose() }
    }
}

/**
 * A popup the system theme cannot repaint. The app runs under the system look and feel, whose popup painter (Windows
 * visual styles, GTK) draws its own background and icon gutter over the colours set here: a white header with white
 * text on Windows 11, a stray gutter line on Windows 10. The Basic painter fills with the background and nothing else.
 */
private fun plainPopup(popup: JPopupMenu, colors: TrayMenuColors) {
    popup.setUI(BasicPopupMenuUI())
    popup.isOpaque = true
    popup.background = colors.background
    popup.foreground = colors.text
    popup.border = BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(colors.border),
        BorderFactory.createEmptyBorder(6, 6, 6, 6))
    popup.isLightWeightPopupEnabled = false
}

/**
 * A per-popup Swing menu: readable row padding, app colours and real menu keyboard/accessibility behavior.
 * Every part has its own Basic painter, so it looks the same under Windows visual styles, Windows high contrast, GTK
 * and the Java look and feels. No global LookAndFeel/UIManager changes; the rest of the desktop is unaffected.
 */
internal fun createTrayMenu(
    version: String,
    status: String,
    available: Set<TrayDestination>,
    onOpen: () -> Unit,
    onNavigate: (TrayDestination) -> Unit,
    onQuit: () -> Unit,
    colors: TrayMenuColors = TrayMenuColors.current(),
    textScale: Float = Appearance.textScale,
): JPopupMenu {
    val systemFont = runCatching { Toolkit.getDefaultToolkit().getDesktopProperty("win.menu.font") as? Font }.getOrNull()
        ?: Font(Font.DIALOG, Font.PLAIN, 13)
    val font = systemFont.deriveFont((systemFont.size2D.coerceAtLeast(13f) * textScale).coerceIn(11f, 24f))
    val menu = JPopupMenu().apply { plainPopup(this, colors) }
    val arrow = TrayArrow(colors, (font.size2D * .8f).toInt().coerceAtLeast(9))
    fun style(item: JMenuItem) {
        if (item is JMenu) item.setUI(object : BasicMenuUI() {
            override fun getPropertyPrefix() = TRAY_PREFIX
            override fun installDefaults() {
                super.installDefaults()
                selectionBackground = colors.selected; selectionForeground = colors.onSelected
                disabledForeground = colors.muted
                checkIcon = null; arrowIcon = arrow
            }
        }) else item.setUI(object : BasicMenuItemUI() {
            override fun getPropertyPrefix() = TRAY_PREFIX
            override fun installDefaults() {
                super.installDefaults()
                selectionBackground = colors.selected; selectionForeground = colors.onSelected
                disabledForeground = colors.muted
                checkIcon = null; arrowIcon = null
            }
        })
        item.font = font
        item.foreground = colors.text
        item.background = colors.background
        item.isOpaque = true
        item.border = BorderFactory.createEmptyBorder(8, 14, 8, 14)
        item.preferredSize = Dimension(item.preferredSize.width.coerceAtLeast(254), item.preferredSize.height)
    }
    fun action(label: String, block: () -> Unit) = JMenuItem(label).apply {
        style(this)
        addActionListener { block() }
    }
    fun destination(where: TrayDestination) = action(where.label) { onNavigate(where) }.apply { isEnabled = where in available }
    fun separator(target: JPopupMenu = menu) {
        target.add(JPanel().apply {
            setUI(BasicPanelUI())
            isOpaque = true
            background = colors.border
            preferredSize = Dimension(1, 1)
            border = BorderFactory.createEmptyBorder()
        })
    }
    fun heading(text: String) = JLabel(text).apply {
        setUI(BasicLabelUI())
        isOpaque = true
        background = colors.background
    }
    menu.add(heading("RingMASTER   ·   $version").apply {
        this.font = font.deriveFont(Font.BOLD)
        foreground = colors.text
        border = BorderFactory.createEmptyBorder(8, 14, 4, 14)
    })
    menu.add(heading(status).apply {
        this.font = font.deriveFont((font.size2D - 1).coerceAtLeast(11f))
        foreground = colors.muted
        border = BorderFactory.createEmptyBorder(0, 14, 10, 14)
    })
    separator()
    menu.add(action("Open RingMASTER", onOpen).apply { this.font = font.deriveFont(Font.BOLD) })
    for (where in listOf(TrayDestination.Fleet, TrayDestination.Activity, TrayDestination.Notifications, TrayDestination.Schedules)) {
        menu.add(destination(where))
    }
    fun submenu(label: String): JMenu = JMenu(label).apply {
        style(this)
        plainPopup(popupMenu, colors)
    }
    val more = submenu("More shortcuts")
    for (where in listOf(TrayDestination.Connections, TrayDestination.Storage, TrayDestination.Watcher)) more.add(destination(where))
    separator(more.popupMenu)
    for (where in listOf(TrayDestination.Scan, TrayDestination.Tools, TrayDestination.Bluetooth, TrayDestination.Vpn)) more.add(destination(where))
    menu.add(more)
    separator()
    for (where in listOf(TrayDestination.Settings, TrayDestination.Updates, TrayDestination.About)) menu.add(destination(where))
    separator()
    menu.add(action("Quit RingMASTER", onQuit))
    menu.putClientProperty("tray.submenuFactory", ::submenu)
    return menu
}

/** On short/high-DPI displays, overflow becomes keyboard-accessible submenus rather than clipped rows. */
internal fun fitTrayMenu(menu: JPopupMenu, height: Int) {
    @Suppress("UNCHECKED_CAST")
    val submenu = menu.getClientProperty("tray.submenuFactory") as? (String) -> JMenu ?: return
    fun fit(popup: JPopupMenu) {
        if (popup.preferredSize.height > height && popup.components.filterIsInstance<JMenuItem>().size > 2) {
            val overflow = submenu("More actions")
            popup.add(overflow)
            while (popup.preferredSize.height > height && popup.components.filterIsInstance<JMenuItem>().size > 2) {
                val component = popup.getComponent(popup.componentCount - 2)
                popup.remove(component)
                overflow.popupMenu.insert(component, 0)
            }
        }
        popup.components.filterIsInstance<JMenu>().forEach { fit(it.popupMenu) }
    }
    fit(menu)
}

/** Owns only a tiny focus anchor, never the main window. Outside click, Escape and activation dispose it. */
internal class TrayMenuWindow {
    private var owner: JDialog? = null
    private var popup: JPopupMenu? = null

    fun show(at: Point, menu: JPopupMenu) {
        close()
        val config = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            .flatMap { it.configurations.toList() }.firstOrNull { it.bounds.contains(at) }
            ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
        val work = Rectangle(config.bounds).apply {
            x += insets.left; y += insets.top
            width -= insets.left + insets.right; height -= insets.top + insets.bottom
        }
        fitTrayMenu(menu, work.height)
        val location = trayPopupPosition(at, menu.preferredSize, work)
        // JWindow's implicit invisible owner makes it non-focusable on Windows. A modeless undecorated dialog
        // can own keyboard focus without restoring the application's hidden main window.
        val anchor = JDialog(null as java.awt.Frame?, "RingMASTER tray menu", false, config).apply {
            isUndecorated = true
            name = "RingMASTER tray menu"
            isAlwaysOnTop = true
            focusableWindowState = true
            // One pixel, but in the menu's colour: the dialog's own default is the system's window colour.
            background = menu.background
            contentPane.background = menu.background
            setBounds(location.x, location.y, 1, 1)
        }
        owner = anchor
        popup = menu
        menu.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) = Unit
            override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) { SwingUtilities.invokeLater { if (popup === menu) close() } }
            override fun popupMenuCanceled(e: PopupMenuEvent) { SwingUtilities.invokeLater { if (popup === menu) close() } }
        })
        anchor.rootPane.isFocusable = true
        anchor.addWindowFocusListener(object : WindowAdapter() {
            override fun windowGainedFocus(e: WindowEvent) { anchor.rootPane.requestFocusInWindow() }
        })
        anchor.isVisible = true
        menu.show(anchor.contentPane, 0, 0)
        MenuSelectionManager.defaultManager().selectedPath = arrayOf(menu)
        anchor.rootPane.requestFocusInWindow()
    }

    fun close() {
        val old = popup
        popup = null
        if (old != null) {
            if (MenuSelectionManager.defaultManager().selectedPath.firstOrNull() === old) {
                MenuSelectionManager.defaultManager().clearSelectedPath()
            }
            old.isVisible = false
        }
        owner?.dispose()
        owner = null
    }
}
