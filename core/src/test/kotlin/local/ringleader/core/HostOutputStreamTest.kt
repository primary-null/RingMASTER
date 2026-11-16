package local.ringleader.core

import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The terminal's host-output queue ([HostOutputStream]): the replacement for a pipe that died with its writer thread. */
class HostOutputStreamTest {
    private fun HostOutputStream.readAll(count: Int): ByteArray {
        val out = ByteArray(count); var got = 0
        while (got < count) { val n = read(out, got, count - got); check(n > 0) { "stream ended after $got of $count bytes" }; got += n }
        return out
    }

    @Test fun aReaderBlockedForAWhileStillGetsBytesFromAnotherThreadAfterTheFirstWriterHasExited() {
        val stream = HostOutputStream()
        val first = Thread { stream.write("abc".toByteArray()) }
        first.start(); first.join()
        assertEquals("abc", String(stream.readAll(3)))
        // The pipe failed here: its reader noticed that the thread which wrote last was dead, 3 s after it ended.
        val seen = AtomicReference<String>()
        val reader = Thread { seen.set(String(stream.readAll(3))) }
        reader.start()
        Thread.sleep(3500)
        val second = Thread { stream.write("def".toByteArray()) }
        second.start(); second.join()
        reader.join(5000)
        assertEquals("def", seen.get())
        assertFalse(reader.isAlive)
    }

    @Test fun bytesKeepTheirOrderAcrossManyWrapsOfTheRing() {
        val stream = HostOutputStream(capacity = 997)               // a prime, so chunk sizes keep landing on different offsets
        val data = Random(7).nextBytes(300_000)
        val writer = Thread {
            var at = 0
            val sizes = Random(11)
            while (at < data.size) { val n = minOf(sizes.nextInt(1, 2500), data.size - at); stream.write(data, at, n); at += n }
            stream.finish()
        }
        writer.start()
        val received = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(1500)
        while (true) { val n = stream.read(buffer, 0, buffer.size); if (n < 0) break; received.write(buffer, 0, n) }
        writer.join()
        assertContentEquals(data, received.toByteArray())
    }

    @Test fun aFloodAgainstASlowReaderNeverQueuesMoreThanTheCapacityAndLosesNothing() {
        val capacity = 16 * 1024
        val stream = HostOutputStream(capacity)
        val data = Random(3).nextBytes(768 * 1024)
        val queuedHigh = intArrayOf(0)
        val writeDone = CountDownLatch(1)
        val writer = Thread { stream.write(data); stream.finish(); writeDone.countDown() }
        writer.start()
        val received = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        var fullSeen = false
        while (true) {
            val queued = stream.queued
            queuedHigh[0] = maxOf(queuedHigh[0], queued)
            if (queued == capacity) fullSeen = true
            val n = stream.read(buffer, 0, buffer.size)
            if (n < 0) break
            received.write(buffer, 0, n)
            if (received.size() % (64 * 1024) < n) Thread.sleep(2)   // a reader that cannot keep up
        }
        assertTrue(writeDone.await(5, TimeUnit.SECONDS))
        assertTrue(queuedHigh[0] <= capacity, "queued ${queuedHigh[0]} of $capacity")
        assertTrue(fullSeen, "the writer was made to wait (back-pressure) at least once")
        assertContentEquals(data, received.toByteArray())
    }

    @Test fun aWriterWaitsWhileTheQueueIsFullAndContinuesAsTheReaderTakesBytes() {
        val stream = HostOutputStream(capacity = 8)
        val done = CountDownLatch(1)
        val writer = Thread { stream.write(ByteArray(20) { it.toByte() }); done.countDown() }
        writer.start()
        Thread.sleep(300)
        assertEquals(8, stream.queued)
        assertEquals(1, done.count, "still waiting for room")
        assertContentEquals(ByteArray(8) { it.toByte() }, stream.readAll(8))
        assertContentEquals(ByteArray(8) { (it + 8).toByte() }, stream.readAll(8))
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertContentEquals(ByteArray(4) { (it + 16).toByte() }, stream.readAll(4))
    }

    @Test fun aUtf8SequenceSplitAcrossWritesIsJoinedByTheReader() {
        val stream = HostOutputStream(capacity = 64)
        val text = "aé€😀z"                  // 1, 2, 3 and 4 byte sequences
        val bytes = text.toByteArray(Charsets.UTF_8)
        assertEquals(1 + 2 + 3 + 4 + 1, bytes.size)
        val writer = Thread { bytes.forEach { stream.write(byteArrayOf(it)); Thread.sleep(15) }; stream.finish() }
        writer.start()
        val reader = InputStreamReader(stream, Charsets.UTF_8)
        val out = StringBuilder()
        while (true) { val c = reader.read(); if (c < 0) break; out.append(c.toChar()) }
        writer.join()
        assertEquals(text, out.toString())
    }

    @Test fun closingFromTheReaderSideReleasesABlockedWriterAndEndsTheReader() {
        val stream = HostOutputStream(capacity = 4)
        val failure = AtomicReference<Throwable>()
        val writer = Thread { try { stream.write(ByteArray(100)) } catch (e: Throwable) { failure.set(e) } }
        writer.start()
        Thread.sleep(200)
        assertTrue(writer.isAlive, "the writer is waiting for room")
        val started = System.nanoTime()
        stream.close()
        writer.join(2000)
        assertFalse(writer.isAlive)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1500)
        assertTrue(failure.get() is IOException, "a closed queue refuses output: ${failure.get()}")
        assertEquals(-1, stream.read())
        assertEquals(0, stream.available())
        assertFailsWith<IOException> { stream.write(byteArrayOf(1)) }
        stream.close(); stream.finish()                            // both ends are idempotent
    }

    @Test fun closingFromTheReaderSideWakesAReaderThatIsWaiting() {
        val stream = HostOutputStream()
        val result = AtomicReference<Int>()
        val reader = Thread { result.set(stream.read()) }
        reader.start()
        Thread.sleep(200)
        stream.close()
        reader.join(2000)
        assertEquals(-1, result.get())
    }

    @Test fun finishingFromTheWriterSideLetsTheReaderDrainAndThenSeeTheEnd() {
        val stream = HostOutputStream(capacity = 256)
        stream.write(ByteArray(100) { it.toByte() })
        stream.finish()
        assertEquals(100, stream.available())
        assertContentEquals(ByteArray(100) { it.toByte() }, stream.readAll(100))
        assertEquals(-1, stream.read())
        assertFailsWith<IOException> { stream.write(byteArrayOf(1)) }
        stream.finish(); stream.close()
    }

    @Test fun finishingWakesAReaderThatIsWaiting() {
        val stream = HostOutputStream()
        val result = AtomicReference<Int>()
        val reader = Thread { result.set(stream.read()) }
        reader.start()
        Thread.sleep(200)
        stream.finish()
        reader.join(2000)
        assertEquals(-1, result.get())
    }

    @Test fun anInterruptedWaitIsReportedAsAnInterruptedIoException() {
        val stream = HostOutputStream(capacity = 2)
        val failure = AtomicReference<Throwable>()
        val writer = Thread { try { stream.write(ByteArray(10)) } catch (e: Throwable) { failure.set(e) } }
        writer.start()
        Thread.sleep(200)
        writer.interrupt()
        writer.join(2000)
        assertTrue(failure.get() is java.io.InterruptedIOException, "${failure.get()}")
        assertNotNull(failure.get())
    }
}
