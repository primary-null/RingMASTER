package local.ringleader.core

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException

/** Explicit cancellation closes the active connection even while blocked in a write or reply read. */
class UploadCancel {
    @Volatile private var stopped = false
    private var close: (() -> Unit)? = null
    @Synchronized fun cancel() { stopped = true; close?.invoke() }
    @Synchronized internal fun attach(action: () -> Unit) { close = action; if (stopped) action() }
    @Synchronized internal fun detach() { close = null }
    internal fun check() { if (stopped || Thread.currentThread().isInterrupted) throw CancellationException("Upload stopped.") }
}
class MultipartFrame internal constructor(val boundary: String, internal val head: ByteArray, internal val tail: ByteArray) {
    fun totalLength(size: Long): Long = Math.addExact(Math.addExact(size, head.size.toLong()), tail.size.toLong())
}
fun uploadMultipart(boundary: String, content: String, checksum: Checksum?, fileName: String): MultipartFrame {
    require(boundary.matches(Regex("[a-f0-9]{32}")))
    require(content in listOf("iso", "vztmpl") && contentFileNameError(content, fileName) == null && checksumError(checksum) == null)
    fun part(name: String, value: String) = "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n"
    val head = buildString {
        append(part("content", content))
        checksum?.let { append(part("checksum-algorithm", it.algorithm)); append(part("checksum", it.hex)) }
        append("--$boundary\r\nContent-Disposition: form-data; name=\"filename\"; filename=\"$fileName\"\r\nContent-Type: application/octet-stream\r\n\r\n")
    }.toByteArray(Charsets.US_ASCII)
    return MultipartFrame(boundary, head, "\r\n--$boundary--\r\n".toByteArray(Charsets.US_ASCII))
}
/** Fixed capture size, 64 KiB memory, monotone progress bounded by captured file size. */
internal fun streamUpload(input: InputStream, output: OutputStream, size: Long, progress: (Long) -> Unit, cancel: UploadCancel) {
    val buffer = ByteArray(65536)
    var written = 0L
    try {
        cancel.check(); progress(0)
        while (written < size) {
            cancel.check()
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), size - written).toInt())
            if (n < 0) error("Selected file changed during upload.")
            if (n == 0) continue
            cancel.check(); output.write(buffer, 0, n); written += n; progress(written)
        }
        cancel.check()
        require(input.read() == -1) { "Selected file changed during upload." }
    } finally { buffer.fill(0) }
}
