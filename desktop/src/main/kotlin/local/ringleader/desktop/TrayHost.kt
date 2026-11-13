package local.ringleader.desktop

import java.awt.AWTException
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.ActionListener

/** What the resident runtime needs from a tray; tests substitute a fake. */
internal interface TrayPort {
    fun install(): TrayStatus
    fun update(tooltip: String, line: String)
    fun notify(title: String, text: String)
    fun remove()
}

/**
 * The notification-area icon (AWT SystemTray: the Windows notification area, or an XEmbed tray on Linux panels that
 * offer one). Text is generic: no server, guest or schedule names, so Showcase and screen sharing stay private.
 * Every call runs on the AWT event thread.
 */
internal class TrayHost(private val onOpen: () -> Unit, private val onSchedules: () -> Unit, private val onQuit: () -> Unit) : TrayPort {
    private var icon: TrayIcon? = null
    private var status: MenuItem? = null

    override fun install(): TrayStatus {
        if (java.awt.GraphicsEnvironment.isHeadless()) return TrayStatus.Unsupported("No desktop is available.")
        val supported = runCatching { SystemTray.isSupported() }.getOrDefault(false)
        if (!supported) return TrayStatus.Unsupported("This desktop has no notification area that RingMASTER can use.")
        return try {
            val tray = SystemTray.getSystemTray()
            val image = javax.imageio.ImageIO.read(TrayHost::class.java.getResourceAsStream("/ringmaster-icon-small.png")
                ?: TrayHost::class.java.getResourceAsStream("/ringmaster-icon.png"))
            val menu = PopupMenu()
            fun item(label: String, action: () -> Unit) = MenuItem(label).apply { addActionListener(ActionListener { action() }) }
            menu.add(item("Open RingMASTER", onOpen))
            menu.add(item("Schedules", onSchedules))
            menu.addSeparator()
            val line = MenuItem("Starting").apply { isEnabled = false }
            menu.add(line)
            menu.addSeparator()
            menu.add(item("Quit RingMASTER", onQuit))
            val created = TrayIcon(image, "RingMASTER", menu).apply {
                isImageAutoSize = true
                // Windows: double-click (or Enter on the focused icon) opens the window.
                addActionListener(ActionListener { onOpen() })
            }
            tray.add(created)
            icon = created
            status = line
            TrayStatus.Installed
        } catch (e: AWTException) {
            TrayStatus.Failed("The notification area refused the icon.")
        } catch (e: Exception) {
            TrayStatus.Failed("The notification-area icon could not be created (${e.javaClass.simpleName}).")
        }
    }

    override fun update(tooltip: String, line: String) {
        icon?.toolTip = tooltip.take(120)
        status?.label = line.take(80)
    }

    override fun notify(title: String, text: String) {
        runCatching { icon?.displayMessage(title, text, TrayIcon.MessageType.INFO) }
    }

    override fun remove() {
        val current = icon ?: return
        icon = null
        runCatching { SystemTray.getSystemTray().remove(current) }
    }
}
