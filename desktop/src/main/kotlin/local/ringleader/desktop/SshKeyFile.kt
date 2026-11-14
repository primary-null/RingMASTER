@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package local.ringleader.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

internal const val SSH_KEY_FILE_MAX_BYTES = local.ringleader.core.CREATE_SSH_KEYS_MAX_FILE_BYTES

/** File chooser and desktop drop surface for the SSH public-key editor. File data stays in wipeable char buffers. */
@Composable
fun SshKeyFile(
    state: TextFieldState,
    error: String? = null,
    onError: (String?) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }

    fun load(path: Path) {
        scope.launch {
            var loaded: CharArray? = null
            try {
                withContext(Dispatchers.IO) { loaded = readSshKeyChars(path) }
                loaded?.let { appendSshKeyChars(state, it) }
                onError(null)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onError("The key file could not be read (UTF-8, at most 64 KiB).")
            } finally { loaded?.fill('\u0000') }
        }
    }

    val target = remember(state) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { dragging = true }
            override fun onExited(event: DragAndDropEvent) { dragging = false }
            override fun onEnded(event: DragAndDropEvent) { dragging = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                dragging = false
                val path = droppedFile(event) ?: return false
                load(path)
                return true
            }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, if (dragging) Tech.Amber else Tech.Edge, Tech.Control)
            .background(if (dragging) Tech.AmberWash else Tech.Raised.copy(alpha = .28f), Tech.Control)
            .dragAndDropTarget(
                shouldStartDragAndDrop = { event -> eventFileTransfer(event) != null },
                target = target,
            )
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("KEY FILE", color = Tech.Muted, fontFamily = Tech.Mono, fontSize = 10.sp, letterSpacing = .8.sp)
                Text(if (dragging) "Release to add the key" else "Drop a public-key file here", color = if (dragging) Tech.Amber else Tech.Ink, fontSize = 12.sp)
            }
            MiniAction("Load key") {
                chooseSshKeyFile()?.let(::load)
            }
        }
        (error ?: "UTF-8 text, at most 64 KiB. Existing keys are kept.").let {
            Text(it, color = if (error == null) Tech.Muted else Tech.Red, fontSize = 11.sp)
        }
    }
}

private fun chooseSshKeyFile(): Path? {
    val dialog = FileDialog(null as Frame?, "Load SSH public key", FileDialog.LOAD)
    return try {
        dialog.isMultipleMode = false
        dialog.isVisible = true
        val file = dialog.file ?: return null
        Path.of(dialog.directory ?: "", file)
    } finally { dialog.dispose() }
}

private fun eventFileTransfer(event: DragAndDropEvent): java.awt.datatransfer.Transferable? = when (val native = event.nativeEvent) {
    is DropTargetDragEvent -> native.transferable
    is DropTargetDropEvent -> native.transferable
    else -> null
}

private fun droppedFile(event: DragAndDropEvent): Path? {
    val transfer = eventFileTransfer(event) ?: return null
    if (!transfer.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return null
    val files = transfer.getTransferData(DataFlavor.javaFileListFlavor) as? List<*> ?: return null
    return (files.singleOrNull() as? java.io.File)?.toPath()
}

/** Reads UTF-8 text without constructing a String. Every intermediate array is overwritten before it is released. */
internal fun readSshKeyChars(path: Path, maxBytes: Int = SSH_KEY_FILE_MAX_BYTES): CharArray {
    require(maxBytes > 0)
    if (!Files.isRegularFile(path)) throw IOException("Choose a key file.")
    // Bound bytes at the stream, including files that grow after the initial size check.
    val bytes = ByteArray(maxBytes + 1)
    var chars: java.nio.CharBuffer? = null
    try {
        val count = Files.newInputStream(path).use { input ->
            var at = 0
            while (at < bytes.size) {
                val n = input.read(bytes, at, bytes.size - at)
                if (n < 0) break
                at += n
            }
            at
        }
        if (count > maxBytes) throw IOException("The key file is larger than 64 KiB.")
        chars = java.nio.CharBuffer.allocate(count)
        val decoder = StandardCharsets.UTF_8.newDecoder()
        decoder.decode(java.nio.ByteBuffer.wrap(bytes, 0, count), chars, true).let { if (it.isError) it.throwException() }
        decoder.flush(chars).let { if (it.isError) it.throwException() }
        chars.flip()
        return CharArray(chars.remaining()).also { chars.get(it) }
    } finally {
        bytes.fill(0)
        chars?.let { buffer -> if (buffer.hasArray()) buffer.array().fill('\u0000') }
    }
}

/** Appends one loaded file to the field in-place, separating it from existing text by a newline. */
internal fun appendSshKeyChars(state: TextFieldState, chars: CharArray) {
    val separator = if (state.text.isNotEmpty() && state.text.last() != '\n') 1 else 0
    if (state.text.length + separator + chars.size > local.ringleader.core.CREATE_SSH_KEYS_MAX_CHARS ||
        secretInputLimit(java.nio.CharBuffer.wrap(chars), true) != null) throw IOException("The SSH keys exceed the character or line limit. Existing keys were kept.")
    state.edit {
        if (length > 0 && charAt(length - 1) != '\n') append('\n')
        for (char in chars) append(char)
    }
}
