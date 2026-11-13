package local.ringleader.desktop

import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.swing.SwingUtilities
import kotlinx.coroutines.*
import local.ringleader.core.UploadSource

internal class DesktopUpload(val source: UploadSource, val basename: String, internal val path: Path) {
    override fun toString() = "DesktopUpload(redacted)"
}
internal const val STORAGE_CHOOSER_TITLE = "Choose ISO or container template"
/** Opens a file chooser on the AWT event thread; may block there in a modal loop. [closer] receives an action that closes it when the caller is cancelled. */
internal fun interface StorageFilePicker { fun pick(closer: (() -> Unit) -> Unit): Path? }
internal val NativeStorageFilePicker = StorageFilePicker { closer ->
    val dialog=FileDialog(null as Frame?, STORAGE_CHOOSER_TITLE, FileDialog.LOAD)
    closer { dialog.dispose() }
    try { dialog.isVisible=true; dialog.file?.let { Path.of(dialog.directory,it) } } finally { dialog.dispose() }
}
/** Tests replace the native chooser; the app always uses [NativeStorageFilePicker]. */
internal val LocalStorageFilePicker = androidx.compose.runtime.staticCompositionLocalOf<StorageFilePicker> { NativeStorageFilePicker }
internal suspend fun chooseStorageUpload(browser: Boolean, picker: StorageFilePicker = NativeStorageFilePicker): DesktopUpload? {
    require(!browser) { "Browser mode cannot read host files." }
    val path = suspendCancellableCoroutine<Path?> { continuation ->
        SwingUtilities.invokeLater {
            if(continuation.isActive) {
                // A chooser that throws (no display, a bad path) must resume the caller, or its busy state never clears.
                val selected=runCatching { picker.pick { close -> continuation.invokeOnCancellation { SwingUtilities.invokeLater(close) } } }
                if(continuation.isActive) continuation.resumeWith(selected)
            }
        }
    } ?: return null
    return withContext(Dispatchers.IO) { DesktopUpload(UploadSource.capture(path),path.fileName.toString(),path) }
}
internal suspend fun hashStorageUpload(file: DesktopUpload, progress: (Long)->Unit): String = withContext(Dispatchers.IO) {
    require(file.source.unchanged()) { "File changed." }
    val digest=MessageDigest.getInstance("SHA-256")
    Files.newInputStream(file.path,java.nio.file.LinkOption.NOFOLLOW_LINKS).use { input ->
        val buffer=ByteArray(65536);var bytes=0L
        while(true) {ensureActive();val n=input.read(buffer);if(n<0) break
            digest.update(buffer,0,n);bytes+=n;require(bytes<=file.source.size) { "File changed." };progress(bytes)
        }
        require(bytes==file.source.size && file.source.unchanged()) { "File changed." }
    }
    digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}
