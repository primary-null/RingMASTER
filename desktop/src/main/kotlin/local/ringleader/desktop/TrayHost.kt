package local.ringleader.desktop

import java.awt.AWTException
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.ActionListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

/** What the resident runtime needs from a tray; tests substitute a fake. */
internal interface TrayPort {
    fun install(): TrayStatus
    fun update(tooltip: String, line: String)
    fun destinations(available: Set<TrayDestination>) {}
    fun notify(title: String, text: String)
    fun remove()
}

/**
 * The notification-area icon (AWT SystemTray: the Windows notification area, or an XEmbed tray on Linux panels that
 * offer one). Text is generic: no server, guest or schedule names, so Showcase and screen sharing stay private.
 * Every call runs on the AWT event thread.
 */
internal class TrayHost(private val onOpen: () -> Unit, private val onNavigate: (TrayDestination) -> Unit, private val onQuit: () -> Unit) : TrayPort {
    private var icon: TrayIcon? = null
    private var status = "Starting"
    private var available = TrayDestination.entries.toSet()
    private val menuWindow = TrayMenuWindow()

    override fun install(): TrayStatus {
        if (java.awt.GraphicsEnvironment.isHeadless()) return TrayStatus.Unsupported("No desktop is available.")
        val supported = runCatching { SystemTray.isSupported() }.getOrDefault(false)
        if (!supported) return TrayStatus.Unsupported("This desktop has no notification area that RingMASTER can use.")
        return try {
            val tray = SystemTray.getSystemTray()
            val image = javax.imageio.ImageIO.read(TrayHost::class.java.getResourceAsStream("/ringmaster-icon-small.png")
                ?: TrayHost::class.java.getResourceAsStream("/ringmaster-icon.png"))
            val created = TrayIcon(image, "RingMASTER").apply {
                isImageAutoSize = true
                // Windows: double-click (or Enter on the focused icon) opens the window.
                addActionListener(ActionListener { onOpen() })
                addMouseListener(object : MouseAdapter() {
                    private var openedOnPress = false
                    override fun mousePressed(e: MouseEvent) {
                        openedOnPress = e.isPopupTrigger
                        if (openedOnPress) openMenu(e)
                    }
                    override fun mouseReleased(e: MouseEvent) {
                        if (!openedOnPress && (e.isPopupTrigger || e.button == MouseEvent.BUTTON3)) openMenu(e)
                        openedOnPress = false
                    }
                    private fun openMenu(e: MouseEvent) {
                        menuWindow.show(e.locationOnScreen, createTrayMenu(AppVersion.value, status, available,
                            { menuWindow.close(); onOpen() },
                            { menuWindow.close(); onNavigate(it) },
                            { menuWindow.close(); onQuit() }))
                    }
                })
            }
            tray.add(created)
            icon = created
            TrayStatus.Installed
        } catch (e: AWTException) {
            TrayStatus.Failed("The notification area refused the icon.")
        } catch (e: Exception) {
            TrayStatus.Failed("The notification-area icon could not be created (${e.javaClass.simpleName}).")
        }
    }

    override fun update(tooltip: String, line: String) {
        icon?.toolTip = tooltip.take(120)
        status = line.take(80)
    }

    override fun destinations(available: Set<TrayDestination>) { this.available = available }

    override fun notify(title: String, text: String) {
        runCatching { icon?.displayMessage(title, text, TrayIcon.MessageType.INFO) }
    }

    override fun remove() {
        menuWindow.close()
        val current = icon ?: return
        icon = null
        runCatching { SystemTray.getSystemTray().remove(current) }
    }
}
