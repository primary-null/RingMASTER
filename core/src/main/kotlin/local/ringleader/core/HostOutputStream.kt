package local.ringleader.core

import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The hand-off between a terminal's websocket and its emulator: a bounded byte queue that any thread may write to and one
 * thread reads, used in place of a PipedInputStream/PipedOutputStream pair.
 *
 * A pipe remembers the last thread that wrote to it and fails the reader (and the next writer) once that thread has
 * exited. The JDK's websocket callbacks run on pool threads that exit after 60 seconds without work, so a terminal that
 * was quiet for a minute lost its emulator at the next byte of output (network audit 2026-11-16, N-01). This queue has
 * no rule about which thread writes or whether it is still alive.
 *
 * It keeps what the pipe gave the websocket: back-pressure. [write] blocks while [capacity] bytes are waiting, so a
 * flood of host output slows the websocket (whose next message is requested only after the write returns) instead of
 * growing memory. Bytes keep their order, and the stream is byte-exact: a UTF-8 sequence split across writes is joined
 * by the reader's decoder, not here.
 *
 * It ends from either side. [finish] says no more bytes will come: the reader takes what is queued and then sees end
 * of stream. [close] says nobody will read: the queued bytes are dropped, a blocked writer fails at once with an
 * [IOException], and the reader sees end of stream. Both are idempotent and neither needs an interrupt.
 */
internal class HostOutputStream(val capacity: Int = HOST_OUTPUT_CAPACITY) : InputStream() {
    init { require(capacity > 0) }
    private val lock = ReentrantLock()
    private val hasBytes = lock.newCondition()
    private val hasRoom = lock.newCondition()
    private val ring = ByteArray(capacity)
    private var head = 0
    private var size = 0
    private var finished = false
    private var closed = false

    /** Bytes waiting for the reader. */
    val queued: Int get() = lock.withLock { size }

    /**
     * Queues [length] bytes from [bytes], waiting for room. Throws [IOException] once the stream is closed or finished,
     * including while waiting; an interrupt of the waiting thread throws [InterruptedIOException].
     */
    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
        var done = 0
        lock.withLock {
            while (done < length) {
                while (size == capacity && !closed && !finished) {
                    try { hasRoom.await() } catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw InterruptedIOException("Interrupted while the terminal display was busy.") }
                }
                if (closed) throw IOException("The terminal display has closed.")
                if (finished) throw IOException("The terminal output has ended.")
                val count = minOf(length - done, capacity - size)
                val tail = (head + size) % capacity
                val first = minOf(count, capacity - tail)
                bytes.copyInto(ring, tail, offset + done, offset + done + first)
                if (count > first) bytes.copyInto(ring, 0, offset + done + first, offset + done + count)
                size += count
                done += count
                hasBytes.signalAll()
            }
        }
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
    }

    /** Waits for at least one byte, then returns what is queued (up to [length]). -1 is the end of the stream. */
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || offset + length > bytes.size) throw IndexOutOfBoundsException()
        if (length == 0) return 0
        lock.withLock {
            while (size == 0) {
                if (closed || finished) return -1
                try { hasBytes.await() } catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw InterruptedIOException("Interrupted while waiting for terminal output.") }
            }
            if (closed) return -1
            val count = minOf(length, size)
            val first = minOf(count, capacity - head)
            ring.copyInto(bytes, offset, head, head + first)
            if (count > first) ring.copyInto(bytes, offset + first, 0, count - first)
            head = (head + count) % capacity
            size -= count
            hasRoom.signalAll()
            return count
        }
    }

    /** What a read would return without waiting. A reader's decoder uses this to know it can stop and draw. */
    override fun available(): Int = lock.withLock { if (closed) 0 else size }

    /** No more bytes will be written. The reader drains what is queued, then sees the end of the stream. */
    fun finish() = lock.withLock {
        finished = true
        hasBytes.signalAll(); hasRoom.signalAll()
    }

    /** Nobody will read any more. Queued bytes are dropped; blocked writers and the reader are released. */
    override fun close() = lock.withLock {
        closed = true
        size = 0
        hasBytes.signalAll(); hasRoom.signalAll()
    }
}

/** Host output the emulator has not yet read: what a flood may queue before the websocket is made to wait. */
internal const val HOST_OUTPUT_CAPACITY = 64 * 1024
