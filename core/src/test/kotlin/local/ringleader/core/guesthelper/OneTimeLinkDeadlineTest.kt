package local.ringleader.core.guesthelper

import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.file.Files
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OneTimeLinkDeadlineTest {
    private val dir = Files.createTempDirectory("engine-link-deadline")

    @AfterTest fun clean() { dir.toFile().deleteRecursively() }

    private fun bundle(size: Long = 64) : GuestHelperBundle {
        val path = dir.resolve("ringmaster-engine-0.1.0-linux-x86_64.tar.gz")
        RandomAccessFile(path.toFile(), "rw").use { it.setLength(size) }
        return chosenGuestHelperBundle(path).getOrThrow()
    }

    private fun connect(link: OneTimeLink): Socket {
        val uri = URI(link.url)
        return Socket().apply {
            receiveBufferSize = 1024
            soTimeout = 3000
            connect(InetSocketAddress(uri.host, uri.port), 1000)
        }
    }

    private fun awaitExpired(link: OneTimeLink) {
        val deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos()
        while (link.status.value == OneTimeLinkState.Waiting && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(OneTimeLinkState.Expired, link.status.value)
    }

    private fun linkWorkers() = Thread.getAllStackTraces().keys.filter { it.name == "ringmaster-engine-link" }.toSet()

    @Test fun repeatedlyClosedLinksLeaveNoWorkerThreads() {
        val before = linkWorkers()
        val chosen = bundle()
        repeat(8) {
            val link = OneTimeLink.open(chosen, "127.0.0.1")
            connect(link).use { socket ->
                socket.getOutputStream().write('G'.code)
                link.close()
                assertClosedWithoutResponse(socket)
            }
        }
        val deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos()
        while ((linkWorkers() - before).any { it.isAlive } && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue((linkWorkers() - before).none { it.isAlive })
    }

    private fun assertClosedWithoutResponse(socket: Socket) {
        val response = try { socket.getInputStream().read() } catch (_: java.net.SocketException) { -1 }
        assertEquals(-1, response, "a closed or expired request must not get an HTTP response")
    }

    @Test fun anAcceptedPartialHeaderExpiresAtTheAbsoluteDeadline() {
        OneTimeLink.open(bundle(), "127.0.0.1", Duration.ofMillis(350)).use { link ->
            connect(link).use { socket ->
                socket.getOutputStream().write("GET ${URI(link.url).rawPath} HTTP/1.1\r\nHost: ".toByteArray())
                awaitExpired(link)
                assertClosedWithoutResponse(socket)
            }
        }
    }

    @Test fun closingALinkAlsoClosesAnAcceptedPartialHeader() {
        OneTimeLink.open(bundle(), "127.0.0.1").use { link ->
            connect(link).use { socket ->
                socket.getOutputStream().write("GET ${URI(link.url).rawPath} HTTP/1.1\r\nHost: ".toByteArray())
                Thread.sleep(100)
                link.close()
                assertEquals(OneTimeLinkState.Expired, link.status.value)
                assertClosedWithoutResponse(socket)
                link.close()
                assertEquals(OneTimeLinkState.Expired, link.status.value)
            }
        }
    }

    @Test fun tricklingHeaderBytesCannotExtendTheLifetime() {
        OneTimeLink.open(bundle(), "127.0.0.1", Duration.ofMillis(350)).use { link ->
            connect(link).use { socket ->
                val request = "GET ${URI(link.url).rawPath} HTTP/1.1\r\nHost: localhost\r\n\r\n"
                for (byte in request.toByteArray()) {
                    try { socket.getOutputStream().write(byte.toInt()) } catch (_: IOException) { break }
                    Thread.sleep(50)
                    if (link.status.value != OneTimeLinkState.Waiting) break
                }
                awaitExpired(link)
                assertClosedWithoutResponse(socket)
            }
        }
    }

    @Test fun expiryInterruptsATransferWhenTheReceiverStopsReading() {
        val chosen = bundle(64L * 1024 * 1024)
        OneTimeLink.open(chosen, "127.0.0.1", Duration.ofMillis(700)).use { link ->
            connect(link).use { socket ->
                socket.getOutputStream().write("GET ${URI(link.url).rawPath} HTTP/1.1\r\n\r\n".toByteArray())
                val input = socket.getInputStream()
                val head = StringBuilder()
                while (!head.endsWith("\r\n\r\n") && head.length < 1024) head.append(input.read().toChar())
                assertTrue(head.startsWith("HTTP/1.1 200"))
                awaitExpired(link)
                var bytes = 0L
                val buffer = ByteArray(8192)
                try {
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        bytes += count
                    }
                } catch (_: java.net.SocketException) {
                    // Windows can reset a socket that expires while a write is blocked.
                }
                assertTrue(bytes < chosen.size, "the expired transfer must be interrupted")
                assertFalse(link.status.value == OneTimeLinkState.Served)
            }
        }
    }
}
