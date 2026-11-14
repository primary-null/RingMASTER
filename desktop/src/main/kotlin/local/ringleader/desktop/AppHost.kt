package local.ringleader.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.input.key.KeyEvent
import java.nio.file.Path

/** [close] follows the close-to-tray policy; [quit] always exits (used after Clear everything). */
data class WindowControls(val minimize: () -> Unit, val maximize: () -> Unit, val close: () -> Unit, val quit: () -> Unit = close)

/** The resident runtime (tray and scheduler) of the desktop window. Null in browser mode, tests and when not the owner. */
internal val LocalResident = staticCompositionLocalOf<ResidentRuntime?> { null }

open class AppHost(val web: Boolean, val hostActions: Boolean = !web) {
    open val windowControls: WindowControls? = null
    val filePicker get() = !web
    val nativeSpice get() = !web
    val embeddedConsole get() = !web
    val traceView get() = !web
    var shortcuts: ((KeyEvent) -> Boolean)? = null
    open fun pickFile(title: String, extensions: Set<String>): Path? = null
}
class DesktopAppHost(override val windowControls: WindowControls? = null) : AppHost(false) {
    override fun pickFile(title: String, extensions: Set<String>): Path? {
        val dialog = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.LOAD)
        try {
            dialog.setFilenameFilter { _, name -> name.substringAfterLast('.').lowercase() in extensions }
            if (System.getProperty("os.name").startsWith("Windows")) dialog.file = extensions.joinToString(";") { "*.$it" }
            dialog.isVisible = true
            return dialog.file?.let { Path.of(dialog.directory, it) }
        } finally { dialog.dispose() }
    }
}
val LocalAppHost = staticCompositionLocalOf<AppHost> { DesktopAppHost() }
